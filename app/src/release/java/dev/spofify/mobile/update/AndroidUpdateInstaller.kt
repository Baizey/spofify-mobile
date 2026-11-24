package dev.spofify.mobile.update

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

private const val RELEASE_PACKAGE = "dev.spofify.mobile.release"
internal const val UPDATE_DIRECTORY = "mobile-updates"
private const val APK_MIME = "application/vnd.android.package-archive"

/** Non-exported and scoped in the release manifest. Only the verified APK receives a URI grant. */
class UpdateApkProvider : FileProvider()

internal data class UpdatePackageIdentity(val name: String, val versionCode: Long, val signerSha256: String)

internal fun acceptsUpdateArchive(installed: UpdatePackageIdentity, archive: UpdatePackageIdentity, expectedCode: Long): Boolean =
    installed.name == RELEASE_PACKAGE && archive.name == RELEASE_PACKAGE &&
        expectedCode in 1..2_100_000_000L && expectedCode > installed.versionCode && archive.versionCode == expectedCode &&
        archive.signerSha256 == installed.signerSha256

internal class AndroidUpdateInstaller(private val context: Context) {
    private val packages = context.packageManager
    private val installed = runCatching { packages.getPackageInfo(context.packageName, signingFlags()).identity() }.getOrNull()
    val installedVersionCode = installed?.takeIf { it.name == RELEASE_PACKAGE }?.versionCode

    fun verify(file: File, expectedCode: Long): Boolean {
        val current = installed ?: return false
        // Android parses the actual archive and collects its verified signing certificate(s).
        val candidate = packages.getPackageArchiveInfo(file.path, signingFlags())?.identity() ?: return false
        return acceptsUpdateArchive(current, candidate, expectedCode)
    }

    @Suppress("DEPRECATION") // The content-URI installer UI is user-confirmed on the supported API range.
    fun prepare(apk: File): Intent? = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.update-apk", apk)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, APK_MIME)
        // Do not grant the file to a third-party app that registers an APK MIME handler.
        val handlers = packages.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_SYSTEM_ONLY)
        val components = handlers.mapNotNull { handler ->
            val activity = handler.activityInfo ?: return@mapNotNull null
            if (activity.exported && packages.checkPermission(Manifest.permission.INSTALL_PACKAGES, activity.packageName) ==
                PackageManager.PERMISSION_GRANTED) ComponentName(activity.packageName, activity.name) else null
        }.distinct()
        val target = components.singleOrNull() ?: return null
        intent.setComponent(target).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }.getOrNull()

    fun open(activity: Activity, intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

@Suppress("DEPRECATION")
private fun signingFlags(): Int = PackageManager.GET_SIGNATURES or
    if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0

@Suppress("DEPRECATION")
private fun PackageInfo.identity(): UpdatePackageIdentity? {
    // Current signers only, never signing history. The channel uses one stable key.
    val signatures = if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures
    val signer = signatures?.singleOrNull() ?: return null
    val digest = MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()).joinToString("") { "%02x".format(it) }
    return UpdatePackageIdentity(packageName, if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong(), digest)
}
