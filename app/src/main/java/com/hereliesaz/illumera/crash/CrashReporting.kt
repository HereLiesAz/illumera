package com.hereliesaz.illumera.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import com.hereliesaz.illumera.BuildConfig
import org.acra.ACRA
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Automatic crash and ANR reporting for the GitHub release build.
 *
 * Crashes are captured by ACRA (configured in LumeraApplication). ANRs are not, so this
 * adds them: on Android 11+ the system's own ANR records are read on the next launch;
 * on older versions a main-thread watchdog reports stalls as they happen. Everything is
 * sent through ACRA's HTTP sender to the crash relay, which files GitHub issues.
 *
 * Default on; the user can turn it off in Settings. The switch is ACRA's own enable
 * preference, so turning it off stops crash reports and ANR reports alike.
 */
object CrashReporting {
    private const val PREF_LAST_ANR_TIMESTAMP = "crash.last_reported_anr_timestamp"
    private const val WATCHDOG_TIMEOUT_MS = 5_000L

    // ACRA reads its enable flag from the framework default preferences file.
    private fun prefs(context: Context) =
        context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)

    fun isAvailable(): Boolean = BuildConfig.CRASH_REPORTING_AVAILABLE

    fun isEnabled(context: Context): Boolean =
        isAvailable() && prefs(context)
            .getBoolean(ACRA.PREF_ENABLE_ACRA, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(ACRA.PREF_ENABLE_ACRA, enabled)
            putBoolean(ACRA.PREF_DISABLE_ACRA, !enabled)
        }
    }

    /** Call once from Application.onCreate, after ACRA is initialized. */
    fun startAnrReporting(context: Context) {
        if (!isEnabled(context) || !ACRA.isInitialised) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Thread({ reportRecordedAnrs(context.applicationContext) }, "anr-exit-info").start()
        } else {
            startWatchdog(context.applicationContext)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun reportRecordedAnrs(context: Context) {
        val prefs = prefs(context)
        val lastReported = prefs.getLong(PREF_LAST_ANR_TIMESTAMP, 0L)
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
        val anrs = runCatching {
            activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 0)
        }.getOrDefault(emptyList())
            .filter { it.reason == ApplicationExitInfo.REASON_ANR && it.timestamp > lastReported }
            .sortedBy { it.timestamp }
        if (anrs.isEmpty()) return

        // First launch after enabling: don't flood with history from before reporting existed.
        if (lastReported == 0L) {
            prefs.edit { putLong(PREF_LAST_ANR_TIMESTAMP, anrs.last().timestamp) }
            return
        }

        for (info in anrs) {
            val trace = runCatching {
                info.traceInputStream?.bufferedReader()?.use { it.readText() }
            }.getOrNull().orEmpty()
            val error = ApplicationNotResponding(
                info.description?.takeIf { it.isNotBlank() } ?: "Application not responding",
                parseMainThread(trace)
            )
            ACRA.errorReporter.putCustomData("anr_source", "ApplicationExitInfo")
            ACRA.errorReporter.putCustomData("anr_timestamp", info.timestamp.toString())
            ACRA.errorReporter.putCustomData("anr_trace", trace.take(30_000))
            ACRA.errorReporter.handleSilentException(error)
            prefs.edit { putLong(PREF_LAST_ANR_TIMESTAMP, info.timestamp) }
        }
        ACRA.errorReporter.clearCustomData()
    }

    private fun startWatchdog(context: Context) {
        val main = Handler(Looper.getMainLooper())
        Thread({
            var reportedThisStall = false
            while (true) {
                val answered = AtomicBoolean(false)
                main.post { answered.set(true) }
                SystemClock.sleep(WATCHDOG_TIMEOUT_MS)
                if (answered.get()) {
                    reportedThisStall = false
                    continue
                }
                if (reportedThisStall || !isEnabled(context)) continue
                reportedThisStall = true
                val error = ApplicationNotResponding(
                    "Main thread blocked for ${WATCHDOG_TIMEOUT_MS / 1000}s",
                    Looper.getMainLooper().thread.stackTrace
                )
                ACRA.errorReporter.putCustomData("anr_source", "watchdog")
                ACRA.errorReporter.handleSilentException(error)
                ACRA.errorReporter.clearCustomData()
            }
        }, "anr-watchdog").apply { isDaemon = true }.start()
    }

    /** Extracts the "main" thread's frames from an ANR trace dump. */
    internal fun parseMainThread(trace: String): Array<StackTraceElement> {
        val lines = trace.lines()
        val start = lines.indexOfFirst { it.startsWith("\"main\"") }
        if (start < 0) return emptyArray()
        val frame = Regex("""^\s+at (.+)\.([^.(]+)\((.*)\)\s*$""")
        return lines.drop(start + 1)
            .takeWhile { it.isNotBlank() }
            .mapNotNull { line ->
                val match = frame.find(line) ?: return@mapNotNull null
                val (declaringClass, method, location) = match.destructured
                val file = location.substringBefore(':').takeIf { it.isNotBlank() && !it.startsWith("Native") }
                val lineNumber = when {
                    location.startsWith("Native") -> -2
                    else -> location.substringAfter(':', "").toIntOrNull() ?: -1
                }
                StackTraceElement(declaringClass, method, file, lineNumber)
            }
            .toTypedArray()
    }
}

/** Synthetic throwable carrying the blocked main thread's stack. */
class ApplicationNotResponding(message: String, mainThreadStack: Array<StackTraceElement>) :
    Throwable(message) {
    init {
        stackTrace = mainThreadStack
    }
}
