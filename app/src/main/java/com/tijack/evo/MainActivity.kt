package com.tijack.evo

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {

    companion object {
        private const val ACTION_USB_PERMISSION =
            "com.tijack.evo.USB_PERMISSION"
    }

    private lateinit var usbManager: UsbManager
    private lateinit var connectionStatus: TextView
    private lateinit var operationStatus: TextView
    private lateinit var diagnostic: TextView
    private lateinit var fileList: LinearLayout

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var connecting = false

    private var client: EvoUsbClient? = null
    private var currentDeviceId: Int? = null

    private val retryRunnable = Runnable {
        if (!isFinishing) {
            scanForEvo()
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(
            context: Context,
            intent: Intent
        ) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = intent.usbDevice()
                    val granted = intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED,
                        false
                    )
                    if (granted && device != null) {
                        connectAndList(device)
                    } else {
                        connecting = false
                        showError(
                            "USB ACCESS NOT GRANTED",
                            "Android permission was declined."
                        )
                    }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.usbDevice()
                    if (
                        device == null ||
                        device.deviceId == currentDeviceId
                    ) {
                        closeClient()
                        setSearching()
                        scheduleRetry(500)
                    }
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    scheduleRetry(200)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usbManager =
            getSystemService(Context.USB_SERVICE) as UsbManager

        connectionStatus = findViewById(R.id.connectionStatus)
        operationStatus = findViewById(R.id.operationStatus)
        diagnostic = findViewById(R.id.diagnostic)
        fileList = findViewById(R.id.fileList)

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                usbReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbReceiver, filter)
        }

        scanForEvo()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        scheduleRetry(150)
    }

    override fun onResume() {
        super.onResume()
        scheduleRetry(150)
    }

    override fun onDestroy() {
        handler.removeCallbacks(retryRunnable)
        try {
            unregisterReceiver(usbReceiver)
        } catch (_: Throwable) {
        }
        closeClient()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun scanForEvo() {
        if (connecting) return

        val device = usbManager.deviceList.values.firstOrNull {
            it.vendorId == EvoUsbClient.TI_VID &&
            it.productId == EvoUsbClient.EVO_PID
        }

        if (device == null) {
            closeClient()
            setSearching()
            scheduleRetry(1200)
            return
        }

        if (usbManager.hasPermission(device)) {
            connectAndList(device)
            return
        }

        connecting = true
        setConnecting("WAITING FOR ANDROID USB PERMISSION")

        val permissionIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun connectAndList(device: UsbDevice) {
        if (connecting && currentDeviceId == device.deviceId) {
            return
        }

        connecting = true
        currentDeviceId = device.deviceId
        setConnecting("READING CALCULATOR")

        executor.execute {
            var localClient: EvoUsbClient? = null
            try {
                closeClient()
                localClient = EvoUsbClient(
                    usbManager,
                    device,
                    ::appendLog
                )
                localClient.open()
                val entries = localClient.listFiles()
                    .sortedWith(
                        compareBy<EvoEntry> {
                            it.type
                        }.thenBy {
                            it.name.lowercase()
                        }
                    )

                client = localClient
                localClient = null

                runOnUiThread {
                    connecting = false
                    connectionStatus.text =
                        "● TI-84 EVO CONNECTED"
                    connectionStatus.setTextColor(
                        getColor(R.color.green)
                    )
                    operationStatus.text = "READY"
                    operationStatus.setTextColor(
                        getColor(R.color.green)
                    )
                    diagnostic.text =
                        "${entries.size} VARIABLES · " +
                        "0451:E018 · CDC 115200"
                    renderEntries(entries)
                }
            } catch (t: Throwable) {
                try {
                    localClient?.close()
                } catch (_: Throwable) {
                }

                appendLog(
                    "ERROR ${t.javaClass.simpleName}: " +
                    t.message.orEmpty()
                )

                runOnUiThread {
                    connecting = false

                    // Treat a busy/temporarily-unavailable Evo as CONNECTING
                    // and automatically try again while it remains attached.
                    val message = t.message.orEmpty()
                    val temporary =
                        "busy" in message.lowercase() ||
                        "timeout" in message.lowercase()

                    if (temporary) {
                        setConnecting("CALCULATOR BUSY · RETRYING")
                    } else {
                        showError(
                            "EVO CONNECTION ERROR",
                            message.ifBlank {
                                t.javaClass.simpleName
                            }
                        )
                    }
                    scheduleRetry(1600)
                }
            }
        }
    }

    private fun renderEntries(entries: List<EvoEntry>) {
        fileList.removeAllViews()

        if (entries.isEmpty()) {
            fileList.addView(
                textCell(
                    "(NO VARIABLES)",
                    13f,
                    R.color.amber_dim
                )
            )
            return
        }

        for (entry in entries) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }

            val name = TextView(this).apply {
                text = "${entry.name}  [${entry.type}]"
                setTextColor(getColor(R.color.amber))
                textSize = 14f
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            val size = TextView(this).apply {
                text = formatBytes(entry.size)
                setTextColor(getColor(R.color.amber))
                textSize = 12f
                gravity = Gravity.END
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(
                    dp(80),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val memory = TextView(this).apply {
                text = if (entry.archived) "ARC" else "RAM"
                setTextColor(getColor(R.color.amber_dim))
                textSize = 11f
                gravity = Gravity.END
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(
                    dp(48),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            row.addView(name)
            row.addView(size)
            row.addView(memory)
            fileList.addView(row)

            fileList.addView(
                View(this).apply {
                    setBackgroundColor(
                        getColor(R.color.divider)
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(1)
                    )
                }
            )
        }
    }

    private fun setSearching() {
        connecting = false
        currentDeviceId = null
        connectionStatus.text =
            "● NO CALCULATOR CONNECTED"
        connectionStatus.setTextColor(
            getColor(R.color.amber)
        )
        operationStatus.text = "SEARCHING..."
        operationStatus.setTextColor(
            getColor(R.color.amber)
        )
        diagnostic.text =
            "Connect TI-84 Evo by USB."
        fileList.removeAllViews()
    }

    private fun setConnecting(detail: String) {
        connectionStatus.text =
            "● TI-84 EVO DETECTED"
        connectionStatus.setTextColor(
            getColor(R.color.amber)
        )
        operationStatus.text = "CONNECTING..."
        operationStatus.setTextColor(
            getColor(R.color.amber)
        )
        diagnostic.text = detail
    }

    private fun showError(
        title: String,
        detail: String
    ) {
        connectionStatus.text = "● $title"
        connectionStatus.setTextColor(
            getColor(R.color.red)
        )
        operationStatus.text = "CONNECTING..."
        operationStatus.setTextColor(
            getColor(R.color.amber)
        )
        diagnostic.text = detail.take(180)
    }

    private fun scheduleRetry(delayMs: Long) {
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, delayMs)
    }

    @Synchronized
    private fun closeClient() {
        try {
            client?.close()
        } catch (_: Throwable) {
        }
        client = null
    }

    private fun appendLog(message: String) {
        try {
            val line =
                "${System.currentTimeMillis()} $message\n"
            File(
                filesDir,
                "ti_jack_evo_android.log"
            ).appendText(line)
        } catch (_: Throwable) {
        }

        runOnUiThread {
            if (message.startsWith("ERROR")) {
                diagnostic.text = message.take(180)
            }
        }
    }

    private fun Intent.usbDevice(): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(
                UsbManager.EXTRA_DEVICE,
                UsbDevice::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    private fun textCell(
        value: String,
        size: Float,
        color: Int
    ): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(getColor(color))
            setPadding(dp(4), dp(12), dp(4), dp(12))
            typeface = Typeface.MONOSPACE
        }

    private fun formatBytes(value: Long): String =
        when {
            value < 1024 -> "$value B"
            value < 1024 * 1024 ->
                "%.1f KB".format(value / 1024.0)
            else ->
                "%.1f MB".format(value / (1024.0 * 1024.0))
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
