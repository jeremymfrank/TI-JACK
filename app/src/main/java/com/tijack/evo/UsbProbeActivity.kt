package com.tijack.evo

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * TI USB-role and calculator descriptor diagnostic launcher.
 *
 * Probe 0.4 keeps the working Evo handoff and also recognizes a TI-Nspire
 * CX II / CX II CAS as a diagnostic-only target. Nspire file transfer is not
 * enabled here; the screen reports USB interfaces/endpoints for protocol work.
 */
class UsbProbeActivity : Activity() {

    companion object {
        private const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"
        private const val ACTION_PROBE_USB_PERMISSION =
            "com.tijack.evo.NSPIRE_PROBE_USB_PERMISSION"
    }

    private lateinit var usbManager: UsbManager
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var nspireCapture: Button

    private val handler = Handler(Looper.getMainLooper())
    private var handedOff = false
    private var lastUsbEvent = "NONE"
    private var lastUsbState = "NO USB_STATE BROADCAST RECEIVED"
    private var lastNspireCapture = "CX II HANDSHAKE CAPTURE: NOT RUN"

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    lastUsbEvent = "DEVICE ATTACHED"
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    lastUsbEvent = "DEVICE DETACHED"
                }
                ACTION_USB_STATE -> {
                    lastUsbEvent = "USB_STATE"
                    lastUsbState = describeExtras(intent)
                }
                ACTION_PROBE_USB_PERMISSION -> {
                    val device = intent.usbDevice()
                    val granted = intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED,
                        false
                    )
                    if (granted && device != null && NspireCxIiUsb.matches(device)) {
                        lastUsbEvent = "CX II USB ACCESS GRANTED"
                        captureFirstCxIiPacket(device)
                    } else {
                        lastUsbEvent = "CX II USB ACCESS NOT GRANTED"
                        lastNspireCapture =
                            "CX II HANDSHAKE CAPTURE: USB permission was not granted."
                    }
                }
            }
            refresh()
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            refresh()
            if (!isFinishing && !handedOff) {
                handler.postDelayed(this, 750)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(USB_SERVICE) as UsbManager

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_STATE)
            addAction(ACTION_PROBE_USB_PERMISSION)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbReceiver, filter)
        }

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
            text = "TI USB DEVICE PROBE 0.4"
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

        nspireCapture = Button(this).apply {
            text = "CAPTURE CX II HANDSHAKE · READ ONLY"
            isEnabled = false
            setOnClickListener { beginCxIiCapture() }
        }
        root.addView(
            nspireCapture,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
            ).apply {
                topMargin = dp(10)
                bottomMargin = dp(4)
            }
        )

        root.addView(TextView(this).apply {
            text = "ANDROID USB DIAGNOSTICS"
            textSize = 13f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(0xFFFFB300.toInt())
            setPadding(0, dp(28), 0, dp(8))
        })

        details = TextView(this).apply {
            textSize = 12f
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

        lastUsbEvent = "APP STARTED: ${intent?.action ?: "NO ACTION"}"
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        lastUsbEvent = "NEW INTENT: ${intent.action ?: "NO ACTION"}"
        refresh()
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

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        try {
            unregisterReceiver(usbReceiver)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    private fun refresh() {
        val devices = usbManager.deviceList.values.sortedBy { it.deviceId }
        val evo = devices.firstOrNull {
            it.vendorId == EvoUsbClient.TI_VID &&
                it.productId == EvoUsbClient.EVO_PID
        }
        val nspireCxIi = devices.firstOrNull { NspireCxIiUsb.matches(it) }

        val hostFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
        val battery = batteryDescription()
        val accessories = try {
            usbManager.accessoryList?.size ?: 0
        } catch (_: Throwable) {
            -1
        }

        val header = buildString {
            append("PHONE: ")
                .append(Build.MANUFACTURER)
                .append(' ')
                .append(Build.MODEL)
                .append("\nANDROID: ")
                .append(Build.VERSION.RELEASE)
                .append("  SDK ")
                .append(Build.VERSION.SDK_INT)
                .append('\n')
            append("USB HOST FEATURE: ")
                .append(if (hostFeature) "YES" else "NO")
                .append('\n')
            append("UsbManager DEVICES: ").append(devices.size).append('\n')
            append("USB ACCESSORIES: ").append(accessories).append('\n')
            append("PHONE POWER: ").append(battery).append('\n')
            append("LAST USB EVENT: ").append(lastUsbEvent).append("\n\n")
            append("USB_STATE:\n").append(lastUsbState).append("\n\n")
            append(typecSysfsDescription()).append("\n\n")
        }

        if (evo != null) {
            status.text = "● TI-84 EVO USB FOUND"
            status.setTextColor(0xFF4CAF50.toInt())
            details.text = header + describe(devices)

            if (!handedOff) {
                handedOff = true
                handler.postDelayed({
                    if (!isFinishing) {
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    }
                }, 900)
            }
            return
        }

        if (nspireCxIi != null) {
            status.text = "● TI-NSPIRE CX II USB FOUND · PROBE ONLY"
            status.setTextColor(0xFF4CAF50.toInt())
            nspireCapture.isEnabled = true
            details.text =
                header +
                    NspireCxIiUsb.describe(nspireCxIi) +
                    "\nFILE TRANSFER: NOT ENABLED IN THIS PROBE BUILD\n\n" +
                    lastNspireCapture +
                    "\n\n" +
                    describe(devices)
            return
        }

        nspireCapture.isEnabled = false
        status.text = when {
            !hostFeature -> "● ANDROID REPORTS NO USB HOST SUPPORT"
            devices.isEmpty() -> "● USB HOST CAPABLE · NO PERIPHERALS ENUMERATED"
            else -> "● USB DEVICE SEEN · SUPPORTED ID NOT FOUND"
        }
        status.setTextColor(0xFFFFB300.toInt())

        details.text = if (devices.isEmpty()) {
            header +
                "USB DEVICE LIST IS EMPTY\n" +
                "Expected Evo: 0451:E018\n" +
                "Expected Nspire CX II/CX II CAS: 0451:E022\n\n" +
                "If PHONE POWER says CHARGING VIA USB while this list is empty, " +
                "the phone is very likely operating as the USB power sink/device " +
                "instead of enumerating the calculator as a host peripheral."
        } else {
            header + describe(devices) +
                "\nEXPECTED CALCULATORS\n" +
                "TI-84 Evo: 0451:E018\n" +
                "TI-Nspire CX II / CX II CAS: 0451:E022"
        }
    }

    private fun beginCxIiCapture() {
        val device = usbManager.deviceList.values.firstOrNull { NspireCxIiUsb.matches(it) }
        if (device == null) {
            lastNspireCapture = "CX II HANDSHAKE CAPTURE: no CX II is currently connected."
            refresh()
            return
        }

        if (usbManager.hasPermission(device)) {
            captureFirstCxIiPacket(device)
            return
        }

        lastNspireCapture = "CX II HANDSHAKE CAPTURE: waiting for Android USB permission..."
        val permissionIntent = PendingIntent.getBroadcast(
            this,
            91,
            Intent(ACTION_PROBE_USB_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        usbManager.requestPermission(device, permissionIntent)
        refresh()
    }

    private fun captureFirstCxIiPacket(device: UsbDevice) {
        nspireCapture.isEnabled = false
        lastNspireCapture = "CX II HANDSHAKE CAPTURE: listening on the bulk IN endpoint..."
        refresh()

        Thread {
            val report = captureFirstCxIiPacketBlocking(device)
            runOnUiThread {
                lastNspireCapture = report
                refresh()
            }
        }.start()
    }

    private fun captureFirstCxIiPacketBlocking(device: UsbDevice): String {
        val connection = usbManager.openDevice(device)
            ?: return "CX II HANDSHAKE CAPTURE: Android could not open the USB device."

        val intf = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { candidate ->
                (0 until candidate.endpointCount).any { index ->
                    val ep = candidate.getEndpoint(index)
                    ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        ep.direction == UsbConstants.USB_DIR_IN
                }
            }
            ?: run {
                connection.close()
                return "CX II HANDSHAKE CAPTURE: no bulk IN interface was found."
            }

        val input = (0 until intf.endpointCount)
            .map { intf.getEndpoint(it) }
            .firstOrNull {
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    it.direction == UsbConstants.USB_DIR_IN
            }
            ?: run {
                connection.close()
                return "CX II HANDSHAKE CAPTURE: no bulk IN endpoint was found."
            }

        if (!connection.claimInterface(intf, true)) {
            connection.close()
            return "CX II HANDSHAKE CAPTURE: Android could not claim USB interface ${intf.id}."
        }

        return try {
            val accumulated = ByteArrayOutputStream()
            var declaredLength: Int? = null

            for (attempt in 0 until 12) {
                val chunk = ByteArray(2048)
                val timeout = if (accumulated.size() == 0) 2500 else 350
                val read = connection.bulkTransfer(input, chunk, chunk.size, timeout)
                if (read <= 0) {
                    if (accumulated.size() > 0) break
                    continue
                }

                accumulated.write(chunk, 0, read)
                val snapshot = accumulated.toByteArray()
                if (declaredLength == null && snapshot.size >= NspireCxIiEnvelope.HEADER_SIZE) {
                    declaredLength =
                        ((snapshot[6].toInt() and 0xFF) shl 8) or
                            (snapshot[7].toInt() and 0xFF)
                }

                val wanted = declaredLength
                if (wanted != null &&
                    wanted >= NspireCxIiEnvelope.HEADER_SIZE &&
                    snapshot.size >= wanted
                ) {
                    break
                }
            }

            val raw = accumulated.toByteArray()
            if (raw.isEmpty()) {
                return "CX II HANDSHAKE CAPTURE: no packet arrived within the read-only capture window. " +
                    "Unplug/reconnect the calculator and try CAPTURE again."
            }

            val declared = if (raw.size >= NspireCxIiEnvelope.HEADER_SIZE) {
                ((raw[6].toInt() and 0xFF) shl 8) or
                    (raw[7].toInt() and 0xFF)
            } else {
                raw.size
            }
            val packet = if (declared in NspireCxIiEnvelope.HEADER_SIZE..raw.size) {
                raw.copyOf(declared)
            } else {
                raw
            }

            val hex = packet.take(96).joinToString(" ") {
                "%02X".format(it.toInt() and 0xFF)
            }
            val decoded = try {
                val frame = NspireCxIiEnvelope.decode(packet)
                "service=0x%02X base=0x%02X src=0x%02X dst=0x%02X seq=%d ack=%s payload=%d B"
                    .format(
                        frame.service,
                        frame.baseService,
                        frame.source,
                        frame.destination,
                        frame.sequence,
                        frame.isAck,
                        frame.payload.size
                    )
            } catch (t: Throwable) {
                "could not decode complete CX II envelope: ${t.message.orEmpty()}"
            }

            val prefix = if (packet.size > 96) "(first 96 B) " else ""
            "CX II HANDSHAKE CAPTURE · READ ONLY\n" +
                "interface=${intf.id} IN=0x%02X bytes=%d\n".format(
                    input.address,
                    packet.size
                ) +
                "$decoded\nHEX $prefix$hex"
        } finally {
            try {
                connection.releaseInterface(intf)
            } catch (_: Throwable) {
            }
            connection.close()
        }
    }

    private fun batteryDescription(): String {
        return try {
            val batteryIntent = registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            ) ?: return "UNKNOWN"

            val plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            when (plugged) {
                BatteryManager.BATTERY_PLUGGED_USB -> "CHARGING VIA USB"
                BatteryManager.BATTERY_PLUGGED_AC -> "CHARGING VIA AC"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "CHARGING WIRELESS"
                else -> "NOT CHARGING / OTHER"
            }
        } catch (t: Throwable) {
            "UNKNOWN (${t.javaClass.simpleName})"
        }
    }

    private fun typecSysfsDescription(): String {
        val candidates = listOf(
            "/sys/class/typec/port0/data_role",
            "/sys/class/typec/port0/power_role",
            "/sys/class/typec/port0/port_type",
            "/sys/class/typec/port1/data_role",
            "/sys/class/typec/port1/power_role",
            "/sys/class/typec/port1/port_type"
        )

        val readable = mutableListOf<String>()
        for (path in candidates) {
            try {
                val file = File(path)
                if (file.exists()) {
                    readable += path.substringAfterLast("/typec/") + " = " +
                        file.readText().trim()
                }
            } catch (_: Throwable) {
            }
        }

        return if (readable.isEmpty()) {
            "TYPE-C SYSFS ROLE: UNAVAILABLE TO APP"
        } else {
            "TYPE-C SYSFS ROLE:\n" + readable.joinToString("\n")
        }
    }

    private fun describeExtras(intent: Intent): String {
        val extras = intent.extras ?: return "(NO EXTRAS)"
        val keys = extras.keySet().sorted()
        if (keys.isEmpty()) return "(NO EXTRAS)"
        return keys.joinToString("\n") { key ->
            val value = try {
                extras.get(key)
            } catch (_: Throwable) {
                "<UNREADABLE>"
            }
            "$key=$value"
        }
    }

    private fun describe(devices: List<android.hardware.usb.UsbDevice>): String {
        val out = StringBuilder()
        devices.forEachIndexed { index, d ->
            out.append("DEVICE ").append(index + 1).append('\n')
            out.append("VID:PID ")
                .append("%04X:%04X".format(d.vendorId, d.productId))
                .append('\n')
            out.append("NAME ").append(d.deviceName).append('\n')
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

    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
