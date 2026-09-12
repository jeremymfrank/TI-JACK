package com.tijack.evo

import android.app.Activity
import android.content.Context
import android.util.AttributeSet
import android.widget.Button
import android.widget.TextView
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
            val activity = context as? Activity ?: return@setOnClickListener
            val status = activity.findViewById<TextView?>(R.id.operationStatus)
                ?.text
                ?.toString()
                .orEmpty()
            if (status.contains("TRANSFERRING", ignoreCase = true) ||
                status.contains("CONNECTING", ignoreCase = true)
            ) {
                Toast.makeText(
                    context,
                    "WAIT FOR THE CURRENT USB OPERATION TO FINISH",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            isEnabled = false
            activity.finish()
            Toast.makeText(
                context,
                "USB RELEASED — UNPLUG CALCULATOR NOW",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
