package dev.spofify.mobile.playback

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession

/**
 * Admission only for Media3's framework-attributed media-button callback, never Intent extras.
 *
 * Media3 1.11.1 MediaSessionLegacyStub.onMediaButtonEvent deliberately supplies isTrusted=false,
 * unlike its ordinary legacy transport admission. Recheck the attributed caller rather than
 * accepting every legacy caller or delegating rejected keys through a notification controller.
 * This does NOT change onConnect, raw player capabilities, or media/URI injection admission.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal object PlaybackMediaButtonTrust {
    fun accepts(context: Context, controller: MediaSession.ControllerInfo): Boolean {
        if (controller.uid == Process.myUid() || controller.isTrusted) return true
        if (controller.controllerVersion != MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION) return false
        val name = controller.packageName
        // This is Media3's absent-identity placeholder, not an Android/system credential.
        if (name.isBlank() || name == MediaSession.ControllerInfo.LEGACY_CONTROLLER_PACKAGE_NAME) return false
        // API26–27 Media3's framework bridge normally has only getCallingPackage and UNKNOWN_UID.
        // A privileged-looking installed name is not sufficient caller identity. Keep that case
        // closed rather than making an unverified compatibility exception (or trusting extras).
        if (controller.uid < 0) return false
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                // The platform checks the attributed package/UID and control privileges. An
                // app-side package lookup here adds package-visibility failures on Android 11+.
                context.getSystemService(MediaSessionManager::class.java)?.isTrustedForMediaControl(
                    MediaSessionManager.RemoteUserInfo(name, -1, controller.uid),
                ) == true
            } else {
                @Suppress("DEPRECATION")
                val installedUid = context.packageManager.getApplicationInfo(name, 0).uid
                if (controller.uid != installedUid) return false
                installedUid == Process.myUid() || installedUid == Process.SYSTEM_UID ||
                    context.packageManager.checkPermission("android.permission.STATUS_BAR_SERVICE", name) == PackageManager.PERMISSION_GRANTED ||
                    context.packageManager.checkPermission("android.permission.MEDIA_CONTENT_CONTROL", name) == PackageManager.PERMISSION_GRANTED ||
                    Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                        ?.split(':')?.any { ComponentName.unflattenFromString(it)?.packageName == name } == true
            }
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: RuntimeException) {
            // Failed identity/permission lookup is not permission to run the fallback transport.
            false
        }
    }
}
