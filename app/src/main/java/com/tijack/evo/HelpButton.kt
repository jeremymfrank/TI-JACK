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
        • Animated GIFs become IM8C frame AppVars plus a TIJGIF01 manifest. Frames are sent before the manifest.
        • JACKVIEW is bundled with TI-JACK. On a normal calculator directory refresh, TI-JACK installs or updates JACKVIEW as needed and regenerates JACKCAT from IM8C images actually present on the calculator.
        • JACKCAT is generated automatically. Static images may keep their full AppVar name; GIF frame sets use a six-character prefix plus hexadecimal frame suffixes.
        • Name a still source Image1 through Image7 (or Img1 through Img7) to use the graph-background .8ca2 slot instead of JACKVIEW media.
        • Viewer-media conversion preserves aspect ratio; GIF preparation currently supports up to 120 frames and 6 MB of generated variables.

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
