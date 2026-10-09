package com.wholphinplus.sources

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.net.URI
import java.security.MessageDigest

/**
 * Checks for the in-app updater (hook in Wholphin's UpdateChecker): an update is downloaded only over
 * https from GitHub's release hosts, and installed only when it is this same app signed with the
 * same key as the copy that is running. Without this, a hijacked release could offer *any* APK
 * through the "Install update" prompt (Android itself only stops a differently signed copy of this
 * same package).
 */
object UpdateGuard {
    /** Where GitHub serves release assets (the download redirects from github.com to one of these). */
    private val HOSTS = setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")

    fun isAllowedUrl(url: String?): Boolean {
        val uri = runCatching { URI(url ?: return false) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host?.lowercase() in HOSTS &&
            uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port == 443)
    }

    /** True when [apk] is this app (same package) signed by the key the installed copy is signed with. */
    fun isUpdateOfThisApp(
        context: Context,
        apk: File,
    ): Boolean =
        runCatching {
            val pm = context.packageManager
            val archive = pm.getPackageArchiveInfo(apk.path, FLAGS) ?: return false
            if (archive.packageName != context.packageName) return false
            val installed = pm.getPackageInfo(context.packageName, FLAGS)
            val mine = signers(installed, history = false)
            // The update may have rotated its key: then the installed key is in its proven history
            mine.isNotEmpty() && signers(archive, history = true).containsAll(mine)
        }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private val FLAGS =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        } else {
            PackageManager.GET_SIGNATURES
        }

    @Suppress("DEPRECATION")
    private fun signers(
        info: PackageInfo,
        history: Boolean,
    ): Set<String> {
        val certs =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
                val s = info.signingInfo!!
                when {
                    s.hasMultipleSigners() -> s.apkContentsSigners.toList()
                    history -> s.signingCertificateHistory.toList()
                    else -> listOfNotNull(s.signingCertificateHistory.lastOrNull())
                }
            } else {
                info.signatures?.toList().orEmpty()
            }
        return certs.map { sha256(it.toByteArray()) }.toSet()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
