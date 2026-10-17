package com.hereliesaz.illumera.data.torrent

import android.content.Context
import android.util.Log
import com.hereliesaz.illumera.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TorrServerEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "LumeraTorrent"
        private const val PORT = 8090
        private const val BINARY_NAME = "libtorrserver.so"
    }

    // start()/stop() are serialized on [lock]. Each start() bumps [generation] and returns
    // it; stop(generation) is ignored once a newer start() has happened, so a slow
    // teardown from a destroyed service cannot kill the engine a newer service started.
    private val lock = Any()
    @Volatile private var process: Process? = null
    @Volatile private var generation = 0L
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun getBaseUrl(): String = "http://127.0.0.1:$PORT"

    /** Starts (or reuses) TorrServer and returns the generation token to pass to [stop]. */
    fun start(): Long = synchronized(lock) {
        val token = ++generation
        if (isRunning()) {
            if (BuildConfig.DEBUG) Log.d(TAG, "TorrServer already running")
            return token
        }

        val binaryPath = getBinaryPath()
        if (binaryPath == null || !File(binaryPath).exists()) {
            throw IllegalStateException("TorrServer binary not found at: $binaryPath")
        }

        if (BuildConfig.DEBUG) Log.d(TAG, "Starting TorrServer from: $binaryPath")

        // Ensure the binary is executable
        val binaryFile = File(binaryPath)
        if (!binaryFile.canExecute()) {
            binaryFile.setExecutable(true)
        }

        val configDir = File(context.filesDir, "torrserver")
        configDir.mkdirs()

        // -i binds the web/API server to loopback only (TorrServer listens on all
        // interfaces by default, which would expose the API to the LAN).
        process = ProcessBuilder(
            binaryPath, "-i", "127.0.0.1", "-p", PORT.toString(), "-d", configDir.absolutePath
        )
            .redirectErrorStream(true)
            .start()

        // Always drain stdout/stderr: an unread pipe fills up and blocks TorrServer.
        // Lines are only logged in debug builds.
        val proc = process
        Thread({
            try {
                proc?.inputStream?.bufferedReader()?.forEachLine { line ->
                    if (BuildConfig.DEBUG) Log.v(TAG, "TorrServer: $line")
                }
            } catch (_: Exception) {}
        }, "torrserver-log").apply { isDaemon = true }.start()

        // Wait for server to be ready
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            if (echo()) {
                if (BuildConfig.DEBUG) Log.d(TAG, "TorrServer started successfully on port $PORT")
                return token
            }
            Thread.sleep(200)
        }

        throw IllegalStateException("TorrServer failed to start within 10 seconds")
    }

    /**
     * Stops TorrServer. With [expectedGeneration], the stop is skipped when a newer
     * [start] has happened since that token was issued.
     */
    fun stop(expectedGeneration: Long? = null): Unit = synchronized(lock) {
        if (expectedGeneration != null && expectedGeneration != generation) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Skipping stale TorrServer stop")
            return
        }
        try {
            // Try graceful shutdown first
            val request = Request.Builder()
                .url("${getBaseUrl()}/shutdown")
                .get()
                .build()
            try {
                httpClient.newCall(request).execute().close()
            } catch (_: Exception) {}

            process?.let { proc ->
                // Wait for graceful exit
                val exited = proc.waitFor(3, TimeUnit.SECONDS)
                if (!exited) {
                    proc.destroyForcibly()
                    if (BuildConfig.DEBUG) Log.w(TAG, "TorrServer force-killed")
                }
            }
        } catch (e: Exception) {
            com.hereliesaz.illumera.crash.AppErrors.w(TAG, "Error stopping TorrServer", e)
            process?.destroyForcibly()
        } finally {
            process = null
            if (BuildConfig.DEBUG) Log.d(TAG, "TorrServer stopped")
        }
    }

    fun isRunning(): Boolean {
        val proc = process ?: return false
        return proc.isAlive && echo()
    }

    fun echo(): Boolean {
        return try {
            val request = Request.Builder()
                .url("${getBaseUrl()}/echo")
                .get()
                .build()
            val response = httpClient.newCall(request).execute()
            val ok = response.isSuccessful
            response.close()
            ok
        } catch (_: Exception) {
            false
        }
    }

    private fun getBinaryPath(): String? {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val binary = File(nativeLibDir, BINARY_NAME)
        return if (binary.exists()) binary.absolutePath else null
    }
}
