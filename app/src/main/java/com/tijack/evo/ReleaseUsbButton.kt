package com.tijack.evo

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.AttributeSet
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/**
 * USB lifecycle guard for the Evo reconnect quirk seen on the test Samsung phone.
 *
 * v0.10 proved that fully finishing MainActivity before unplugging lets Android
 * negotiate the next connection again. v0.12 keeps the manual release button as
 * a known-good fallback and also finishes the activity automatically as soon as
 * Android reports the Evo detached. That stops TI-JACK's retry loop from staying
 * alive across a physical reconnect.
 */
class ReleaseUsbButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : Button(context, attrs, defStyleAttr) {

    private var receiverRegistered = false

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val device = intent.usbDevice()
            if (device == null ||
                (device.vendorId == EvoUsbClient.TI_VID &&
                    device.productId == EvoUsbClient.EVO_PID)
            ) {
                isEnabled = false
                (this@ReleaseUsbButton.context as? Activity)?.finish()
            }
        }
    }

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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (receiverRegistered) return
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(
                detachReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(detachReceiver, filter)
        }
        receiverRegistered = true
    }

    override fun onDetachedFromWindow() {
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(detachReceiver)
            } catch (_: Throwable) {
            }
            receiverRegistered = false
        }
        super.onDetachedFromWindow()
    }

    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
}
