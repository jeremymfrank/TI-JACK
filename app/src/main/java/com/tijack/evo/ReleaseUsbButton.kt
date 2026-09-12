package com.tijack.evo

import android.app.Activity
import android.content.Context
import android.util.AttributeSet
import android.widget.Button
import android.widget.Toast

/**
 * Hardware test control for the Evo CDC reconnect problem.
 *
 * Finishing MainActivity runs its normal onDestroy() path, which closes the
 * EvoUsbClient, deasserts DTR/RTS, closes the CDC port and releases the USB
 * device connection before the user physically removes the cable.
 */
class ReleaseUsbButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : Button(context, attrs, defStyleAttr) {

    init {
        setOnClickListener {
            isEnabled = false
            Toast.makeText(
                context,
                "USB RELEASED — UNPLUG CALCULATOR NOW",
                Toast.LENGTH_LONG
            ).show()
            (context as? Activity)?.finish()
        }
    }
}
