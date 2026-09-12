package com.tijack.evo

import android.app.AlertDialog
import android.content.Context
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

    private fun helpText(): String = """
        Version ${BuildConfig.VERSION_NAME}

        CREDITS
        TI-JACK project: Jawatech / jeremymfrank
        USB serial support: usb-serial-for-android
        Independent project; not affiliated with or endorsed by Texas Instruments.

        CONNECTION TIPS
        • TI-JACK requires Android USB host / OTG mode.
        • On the tested Samsung + TI-84 Evo setup, direct USB-C to USB-C starts in the wrong USB role for TI-JACK.
        • Known-good field setup: USB-C OTG/host adapter → USB-A to USB-C data cable → calculator. No external power is required.
        • A real USB hub also works for testing, but is not required.
        • Avoid repeatedly changing “USB controlled by” on affected Samsung phones; testing showed the USB options can stop responding until the phone is rebooted.
        • Once TI-JACK shows TI-84 EVO CONNECTED, normal unplug/reconnect works automatically.
    """.trimIndent()
}
