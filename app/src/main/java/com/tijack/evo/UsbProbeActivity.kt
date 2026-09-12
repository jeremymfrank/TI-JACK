package com.tijack.evo

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Temporary first-hardware-test launcher.
 *
 * It deliberately enumerates every USB device Android exposes before TI-JACK
 * applies its Evo VID/PID filter. If the expected Evo is present, control is
 * handed straight to MainActivity. If not, the screen gives us the raw
 * VID:PID/interface information needed to distinguish USB-role problems from
 * an unexpected device identity.
 */
class UsbProbeActivity : Activity() {

    private lateinit var usbManager: UsbManager
    private lateinit var status: TextView
    private lateinit var details: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var handedOff = false

    private val poll = object : Runnable {
        override fun run() {
            refresh()
            if (!isFinishing && !handedOff) {
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(USB_SERVICE) as UsbManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(18))
            setBackgroundColor(0xFF10100E.toInt())
        }

        root.addView(TextView(this).apply {
            text = "TI-JACK"
            textSize = 28f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(0xFFFFB300.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
        })

        root.addView(TextView(this).apply {
            text = "EVO / ANDROID USB PROBE 0.2"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFFB88700.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, dp(24))
        })

        status = TextView(this).apply {
            textSize = 18f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(0xFFFFB300.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(status)

        root.addView(TextView(this).apply {
            text = "USB DEVICES VISIBLE TO ANDROID"
            textSize = 13f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(0xFFFFB300.toInt())
            setPadding(0, dp(28), 0, dp(8))
        })

        details = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFFFFC84A.toInt())
            setTextIsSelectable(true)
        }

        val scroll = ScrollView(this).apply {
            addView(details)
        }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        root.addView(TextView(this).apply {
            text = "SCHOOL PROPERTY"
            textSize = 12f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(0xFF8A6500.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(poll)
        handedOff = false
        handler.post(poll)
    }

    override fun onPause() {
        handler.removeCallbacks(poll)
        super.onPause()
    }

    private fun refresh() {
        val devices = usbManager.deviceList.values.sortedBy { it.deviceId }
        val evo = devices.firstOrNull {
            it.vendorId == EvoUsbClient.TI_VID &&
                it.productId == EvoUsbClient.EVO_PID
        }

        if (evo != null) {
            status.text = "● TI-84 EVO USB FOUND"
            status.setTextColor(0xFF4CAF50.toInt())
            details.text = describe(devices)

            if (!handedOff) {
                handedOff = true
                handler.postDelayed({
                    if (!isFinishing) {
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    }
                }, 500)
            }
            return
        }

        status.text = if (devices.isEmpty()) {
            "● ANDROID SEES NO USB DEVICES"
        } else {
            "● USB DEVICE SEEN · EVO ID NOT FOUND"
        }
        status.setTextColor(0xFFFFB300.toInt())
        details.text = if (devices.isEmpty()) {
            "No entries in UsbManager.deviceList.\n\n" +
                "TI-JACK needs the phone to be the USB HOST.\n" +
                "Do not rely on Android's MTP/File Transfer mode.\n\n" +
                "Expected Evo: 0451:E018"
        } else {
            describe(devices) +
                "\nEXPECTED EVO\nVID:PID 0451:E018"
        }
    }

    private fun describe(devices: List<android.hardware.usb.UsbDevice>): String {
        val out = StringBuilder()
        devices.forEachIndexed { index, d ->
            out.append("DEVICE ").append(index + 1).append('\n')
            out.append("VID:PID ")
                .append("%04X:%04X".format(d.vendorId, d.productId))
                .append('\n')
            out.append("DEVICE CLASS ").append(d.deviceClass)
                .append(" SUB ").append(d.deviceSubclass)
                .append(" PROTO ").append(d.deviceProtocol)
                .append('\n')
            out.append("INTERFACES ").append(d.interfaceCount).append('\n')
            for (i in 0 until d.interfaceCount) {
                val intf = d.getInterface(i)
                out.append("  #").append(i)
                    .append(" CLASS ").append(intf.interfaceClass)
                    .append(" SUB ").append(intf.interfaceSubclass)
                    .append(" PROTO ").append(intf.interfaceProtocol)
                    .append(" EP ").append(intf.endpointCount)
                    .append('\n')
            }
            out.append("PERMISSION ")
                .append(if (usbManager.hasPermission(d)) "YES" else "NO")
                .append("\n\n")
        }
        return out.toString()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
