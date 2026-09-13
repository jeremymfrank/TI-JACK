package com.tijack.evo

import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.widget.TextView

/** Small header help/about control. */
class HelpButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextView(context, attrs, defStyleAttr) {

    init {
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setOnClickListener { showHelp() }
    }

    private fun showHelp() {
        AlertDialog.Builder(context)
            .setTitle("TI-JACK")
            .setMessage(helpText())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun appVersion(): String = try {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                context.packageName,
                android.content.pm.PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        info.versionName ?: "unknown"
    } catch (_: Throwable) {
        "unknown"
    }

    private fun helpText(): String = """
        Version ${appVersion()}

        CREDITS
        TI-JACK project: Jawatech / jeremymfrank
        USB serial support: usb-serial-for-android
        Independent project; not affiliated with or endorsed by Texas Instruments.

        TRANSFER & CONVERSION
        • Current Evo files transfer normally.
        • Legacy .8xp conversion is available as a pure-Kotlin preview; no native converter is included.
        • Programs using the classic 265 × 165 CE graph canvas are centered on the Evo when TI-JACK can identify that layout safely.
        • A token or layout TI-JACK cannot convert safely is refused instead of guessed.
        • PNG, JPG, JPEG, and WebP files with ordinary names become named IM8C .8xv2 AppVars for JACKVIEW.
        • JACKVIEW media is scaled up or down to the largest aspect-preserving fit within the 320 × 210 viewer area and centered when letterboxing remains.
        • JACKVIEW clears the previous media item before opening the next image or animation; GIF frames are not cleared between frames.
        • Animated GIFs become IM8C frame AppVars plus a TIJGIF01 manifest. Frames are sent before the manifest.
        • GIFs longer than 300 frames are clipped to their first 300 frames instead of being rejected.
        • JACKVIEW is bundled with TI-JACK. TI-JACK installs or updates JACKVIEW as needed and regenerates JACKCAT from verified IM8C media on the calculator.
        • JACKCAT is a normal Python program and should appear beside JACKVIEW. Unrelated or unreadable AppVars are skipped; they do not block the catalog.
        • Up to 256 GIF frames use two-digit hexadecimal suffixes. Longer GIFs use two-digit base36 suffixes so all frame names still fit the Evo's eight-character variable limit.
        • Name a still source Image1 through Image7 (or Img1 through Img7) to use the graph-background .8ca2 slot instead of JACKVIEW media.
        • Viewer-media conversion preserves aspect ratio and may reduce resolution to fit Evo media limits.
        • JACKVIEW media is sent directly to Archive and checked against a conservative archive-space estimate before transfer. A single prepared media source is capped at about 2.4 MB.

        MEMORY MANAGEMENT
        • Each calculator row shows RAM or ARC. The calculator header shows conservative EST FREE values for RAM and Archive.
        • EST FREE is calculated from the directory's variable sizes with space reserved for the OS and preloaded content; it is not the calculator's exact hidden Memory-screen counter.
        • Select variables and tap ARCHIVE or RAM to move them. TI-JACK reads the original bytes, rewrites them to the requested memory, then verifies both the bytes and memory location.
        • CLEAN GIF scans TIJGIF01 manifests and offers to delete groups of generated GIF frame variables that are no longer referenced by a readable manifest. Deletion is still verified afterward.
        • If a prepared media transfer is larger than the safe per-media guard or the estimated remaining Archive, TI-JACK stops before sending it.

        JACKVIEW CONTROLS
        • Browser: UP/DOWN select, ENTER open, CLEAR exit.
        • Image: LEFT/RIGHT previous/next, CLEAR return.
        • GIF: UP faster, DOWN slower, ENTER pause/resume, LEFT/RIGHT previous/next, CLEAR return.

        CONNECTION TIPS
        • Plug the USB-C adapter into the phone, then connect the calculator with a USB data cable.
        • If the calculator is not detected, unplug both ends and reconnect them with the adapter still on the phone side.
        • Once TI-JACK shows TI-84 EVO CONNECTED, normal unplug/reconnect works automatically.
    """.trimIndent()
}
