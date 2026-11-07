package dev.spofify.mobile

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import java.io.File

/** Optional bounded component-render evidence, never a substitute for installed-device results. */
internal fun capturePolishEvidence(name: String, node: SemanticsNodeInteraction) {
    val root = System.getProperty("spofify.polishEvidenceDir") ?: return
    File(root, "$name.png").outputStream().use {
        check(node.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
    }
}
