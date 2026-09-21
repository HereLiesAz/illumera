package com.hereliesaz.illumera.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.hereliesaz.illumera.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class UpdateInfo(
    val versionName: String,
    val changelog: String,
    val apkUrl: String
)

sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data class UpdateAvailable(val info: UpdateInfo) : UpdateState()
    data object UpToDate : UpdateState()
    data class Downloading(val progress: Float, val downloadedMb: Float = 0f, val totalMb: Float = 0f) : UpdateState()
    data class ReadyToInstall(val file: File) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

// GitHub Releases API response (only fields we need)
private data class GitHubRelease(
    @SerializedName("tag_name") val tagName: String,
    @SerializedName("body") val body: String?,
    @SerializedName("assets") val assets: List<GitHubAsset>
)

private data class GitHubAsset(
    @SerializedName("browser_download_url") val downloadUrl: String,
    @SerializedName("name") val name: String
)

@Singleton
class AppUpdateManager @Inject constructor(
    private val okHttpClient: OkHttpClient,
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("lumera_update_prefs", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = _state.asStateFlow()

    val isPopupEnabled: Boolean
        get() = prefs.getBoolean(KEY_UPDATE_POPUP_ENABLED, true)

    fun setPopupEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_UPDATE_POPUP_ENABLED, enabled).apply()
    }

    private val gson = Gson()

    /** Cached release body from the last checkForUpdate, used for checksum extraction. */
    @Volatile
    private var lastReleaseBody: String? = null

    suspend fun checkForUpdate() {
        _state.value = UpdateState.Checking
        try {
            val release = withContext(Dispatchers.IO) { fetchLatestRelease() }

            val remoteVersion = release.tagName.removePrefix("v")
            val currentVersion = BuildConfig.VERSION_NAME

            if (isNewerVersion(remoteVersion, currentVersion)) {
                val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }
                if (apkAsset != null) {
                    if (!isAllowedDownloadUrl(apkAsset.downloadUrl)) {
                        _state.value = UpdateState.Error("Update URL is not from a trusted source.")
                        return
                    }
                    lastReleaseBody = release.body
                    _state.value = UpdateState.UpdateAvailable(
                        UpdateInfo(
                            versionName = remoteVersion,
                            changelog = stripHashFromChangelog(release.body),
                            apkUrl = apkAsset.downloadUrl
                        )
                    )
                } else {
                    _state.value = UpdateState.Error("Release found but no APK attached.")
                }
            } else {
                _state.value = UpdateState.UpToDate
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = UpdateState.Error(e.message ?: "Failed to check for updates.")
        }
    }

    suspend fun downloadAndInstall(apkUrl: String) {
        // Re-entrancy guard: a rapid double-press on the "Download & Install" row (or the
        // auto-popup and Settings both triggering it) would otherwise start two concurrent
        // downloads truncating and writing the same cache file, corrupting it mid-transfer.
        if (_state.value is UpdateState.Downloading) return
        if (!isAllowedDownloadUrl(apkUrl)) {
            _state.value = UpdateState.Error("Download URL is not from a trusted source.")
            return
        }
        _state.value = UpdateState.Downloading(0f)
        try {
            val apkFile = withContext(Dispatchers.IO) { downloadApk(apkUrl) }
            // Defense in depth beyond the checksum above (which is sourced from the
            // same release response as the download itself, so it only guards
            // against transit corruption/MITM, not a compromised release). Require
            // the downloaded APK to be signed with the same certificate as the
            // currently-installed app — Android's own installer enforces this on
            // upgrade too, but failing fast here avoids showing an install prompt
            // for a build that would just be rejected.
            val signatureOk = withContext(Dispatchers.IO) { verifyApkSignatureMatchesInstalled(apkFile) }
            if (!signatureOk) {
                apkFile.delete()
                _state.value = UpdateState.Error("Downloaded update is not signed with the same key as this app.")
                return
            }
            _state.value = UpdateState.ReadyToInstall(apkFile)
            installApk(apkFile)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = UpdateState.Error("Download failed: ${e.message}")
        }
    }

    fun resetState() {
        _state.value = UpdateState.Idle
    }

    /** Re-launch the installer for an APK that already finished downloading and verifying. */
    fun retryInstall() {
        val file = (_state.value as? UpdateState.ReadyToInstall)?.file ?: return
        if (!file.exists()) {
            _state.value = UpdateState.Error("Downloaded update is no longer available. Please check for updates again.")
            return
        }
        installApk(file)
    }

    /**
     * Throws on a non-2xx response or an unparseable body instead of returning null — those are
     * a failed check (rate limit, outage), not evidence there's no newer release. Conflating them
     * used to report "You're up to date" for a check that never actually completed.
     */
    private fun fetchLatestRelease(): GitHubRelease {
        val url = "https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/latest"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) throw Exception("Update check failed: HTTP ${response.code}")

        val body = response.body?.string() ?: throw Exception("Update check failed: empty response")
        return gson.fromJson(body, GitHubRelease::class.java)
            ?: throw Exception("Update check failed: malformed response")
    }

    private fun downloadApk(apkUrl: String): File {
        // Capture once so a concurrent checkForUpdate() updating the @Volatile
        // field mid-download does not affect checksum verification for this run.
        val releaseBody = lastReleaseBody
        val request = Request.Builder().url(apkUrl).build()
        val response = okHttpClient.newCall(request).execute()

        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")

        val updateDir = File(context.cacheDir, "updates")
        updateDir.mkdirs()
        val apkFile = File(updateDir, "lumera-update.apk")

        val totalBytes = response.body?.contentLength() ?: -1L
        var downloadedBytes = 0L
        val digest = MessageDigest.getInstance("SHA-256")

        response.body?.byteStream()?.use { input ->
            apkFile.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    digest.update(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    if (totalBytes > 0) {
                        _state.value = UpdateState.Downloading(
                            progress = downloadedBytes.toFloat() / totalBytes,
                            downloadedMb = downloadedBytes / (1024f * 1024f),
                            totalMb = totalBytes / (1024f * 1024f)
                        )
                    }
                }
            }
        }

        // Verify checksum if one was provided in the release notes
        val expectedHash = extractSha256FromBody(releaseBody)
        if (expectedHash != null) {
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                apkFile.delete()
                throw SecurityException("APK checksum mismatch — file may be tampered")
            }
        }

        return apkFile
    }

    /** Signing-certificate SHA-256 hashes for the currently-installed app. */
    @Suppress("DEPRECATION")
    private fun installedSigningCertHashes(): Set<String> {
        val pm = context.packageManager
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = info.signingInfo ?: return emptySet()
                val certs = if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
                certs.orEmpty().map { sha256Hex(it.toByteArray()) }.toSet()
            } else {
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                info.signatures.orEmpty().map { sha256Hex(it.toByteArray()) }.toSet()
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    /** Signing-certificate SHA-256 hashes for an on-disk (not yet installed) APK file. */
    @Suppress("DEPRECATION")
    private fun apkSigningCertHashes(apkFile: File): Set<String> {
        val pm = context.packageManager
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = info?.signingInfo ?: return emptySet()
                val certs = if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
                certs.orEmpty().map { sha256Hex(it.toByteArray()) }.toSet()
            } else {
                val info = pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
                info?.signatures.orEmpty().map { sha256Hex(it.toByteArray()) }.toSet()
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun verifyApkSignatureMatchesInstalled(apkFile: File): Boolean {
        val installed = installedSigningCertHashes()
        if (installed.isEmpty()) return false
        val apk = apkSigningCertHashes(apkFile)
        if (apk.isEmpty()) return false
        return installed == apk
    }

    private fun installApk(apkFile: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    companion object {
        private const val KEY_UPDATE_POPUP_ENABLED = "update_popup_enabled"

        /** Trusted domains for APK downloads. */
        private val ALLOWED_DOWNLOAD_HOSTS = setOf(
            "github.com",
            "objects.githubusercontent.com"
        )

        /** Validate that the download URL is from a trusted GitHub domain over HTTPS. */
        private fun isAllowedDownloadUrl(url: String): Boolean {
            val uri = try {
                Uri.parse(url)
            } catch (_: Exception) {
                return false
            }
            if (!uri.scheme.equals("https", ignoreCase = true)) return false
            val host = uri.host?.lowercase() ?: return false
            return ALLOWED_DOWNLOAD_HOSTS.any { host == it || host.endsWith(".$it") }
        }

        /**
         * True only if [remote]'s numeric major.minor.patch is strictly greater than
         * [current]'s. Comparing by plain string inequality (the previous behavior)
         * would offer a "downgrade" whenever the latest published release is ever
         * older than what's installed (a rolled-back release, a dev build ahead of
         * the public tag), and would nag on every tag-format/suffix difference even
         * when the underlying version hasn't changed.
         */
        private fun isNewerVersion(remote: String, current: String): Boolean {
            fun parse(v: String): List<Int> =
                v.substringBefore('-').substringBefore('+')
                    .split('.')
                    .map { it.toIntOrNull() ?: 0 }
            val remoteParts = parse(remote)
            val currentParts = parse(current)
            val size = maxOf(remoteParts.size, currentParts.size)
            for (i in 0 until size) {
                val r = remoteParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (r != c) return r > c
            }
            return false
        }

        /** Extract SHA-256 hash from release body. Expected format: `SHA-256: abcdef1234...` */
        private fun extractSha256FromBody(body: String?): String? {
            if (body == null) return null
            val regex = Regex("""SHA-256:\s*([a-fA-F0-9]{64})""")
            return regex.find(body)?.groupValues?.get(1)
        }

        /** Strip the SHA-256 line from the changelog shown to users. */
        private fun stripHashFromChangelog(body: String?): String {
            if (body == null) return "No changelog provided."
            return body.replace(
                Regex("""(?m)^SHA-256:[ \t]*[a-fA-F0-9]{64}[ \t]*(?:\r?\n|$)"""),
                ""
            ).trim().ifEmpty { "No changelog provided." }
        }
    }
}
