package com.tijack.evo

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {

    companion object {
        private const val ACTION_USB_PERMISSION =
            "com.tijack.evo.USB_PERMISSION"
        private const val REQUEST_FOLDER = 401
        private const val PREFS = "ti_jack_android"
        private const val PREF_FOLDER_URI = "folder_uri"
    }

    private lateinit var usbManager: UsbManager
    private lateinit var connectionStatus: TextView
    private lateinit var operationStatus: TextView
    private lateinit var diagnostic: TextView
    private lateinit var computerFolder: TextView
    private lateinit var computerList: LinearLayout
    private lateinit var calculatorList: LinearLayout
    private lateinit var chooseFolder: Button

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var connecting = false

    @Volatile
    private var transferring = false

    private var client: EvoUsbClient? = null
    private var currentDeviceId: Int? = null
    private var calculatorEntries: List<EvoEntry> = emptyList()
    private var folderUri: Uri? = null
    private var folder: DocumentFile? = null
    private var pendingDownload: EvoEntry? = null

    private val retryRunnable = Runnable {
        if (!isFinishing) scanForEvo()
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = intent.usbDevice()
                    val granted = intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED,
                        false
                    )
                    if (granted && device != null) {
                        connecting = false
                        connectAndList(device)
                    } else {
                        connecting = false
                        showConnectionError(
                            "USB ACCESS NOT GRANTED",
                            "Android USB permission was declined."
                        )
                    }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.usbDevice()
                    if (device == null || device.deviceId == currentDeviceId) {
                        closeClient()
                        setSearching()
                        scheduleRetry(500)
                    }
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> scheduleRetry(200)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        connectionStatus = findViewById(R.id.connectionStatus)
        operationStatus = findViewById(R.id.operationStatus)
        diagnostic = findViewById(R.id.diagnostic)
        computerFolder = findViewById(R.id.computerFolder)
        computerList = findViewById(R.id.computerList)
        calculatorList = findViewById(R.id.calculatorList)
        chooseFolder = findViewById(R.id.chooseFolder)

        chooseFolder.setOnClickListener { chooseAndroidFolder() }
        loadSavedFolder()

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
        renderAndroidFiles()
        scheduleRetry(150)
    }

    @Deprecated("Deprecated in Android; retained for minSdk 29 folder picker compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_FOLDER) return

        if (resultCode != RESULT_OK || data?.data == null) {
            val waiting = pendingDownload
            pendingDownload = null
            if (waiting != null) {
                setReady("DOWNLOAD CANCELLED · NO ANDROID FOLDER SELECTED")
            }
            return
        }

        val uri = requireNotNull(data.data)
        val takeFlags = data.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (t: Throwable) {
            appendLog("folder persist warning: ${t.message.orEmpty()}")
        }

        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(PREF_FOLDER_URI, uri.toString())
            .apply()

        setFolder(uri)

        val waiting = pendingDownload
        pendingDownload = null
        if (waiting != null) {
            handler.post { downloadVariable(waiting) }
        }
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
        if (connecting || transferring) return

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

        if (client != null && currentDeviceId == device.deviceId) {
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
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun connectAndList(device: UsbDevice) {
        if (connecting || transferring) return

        connecting = true
        currentDeviceId = device.deviceId
        setConnecting("READING CALCULATOR")

        executor.execute {
            var localClient: EvoUsbClient? = null
            try {
                closeClient()
                localClient = EvoUsbClient(usbManager, device, ::appendLog)
                localClient.open()
                val entries = sortedEntries(localClient.listFiles())

                client = localClient
                localClient = null
                calculatorEntries = entries

                runOnUiThread {
                    connecting = false
                    renderCalculatorEntries(entries)
                    setReady(
                        "${entries.size} VARIABLES · 0451:E018 · CDC 115200"
                    )
                }
            } catch (t: Throwable) {
                try {
                    localClient?.close()
                } catch (_: Throwable) {
                }
                appendLog("ERROR ${t.javaClass.simpleName}: ${t.message.orEmpty()}")

                runOnUiThread {
                    connecting = false
                    val message = t.message.orEmpty()
                    val temporary =
                        "busy" in message.lowercase() ||
                            "timeout" in message.lowercase()
                    if (temporary) {
                        setConnecting("CALCULATOR BUSY · RETRYING")
                    } else {
                        showConnectionError(
                            "EVO CONNECTION ERROR",
                            message.ifBlank { t.javaClass.simpleName }
                        )
                    }
                    scheduleRetry(1600)
                }
            }
        }
    }

    private fun chooseAndroidFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_FOLDER)
    }

    private fun loadSavedFolder() {
        val text = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(PREF_FOLDER_URI, null)
            ?: return renderAndroidFiles()
        try {
            setFolder(Uri.parse(text))
        } catch (t: Throwable) {
            appendLog("saved folder unavailable: ${t.message.orEmpty()}")
            getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .remove(PREF_FOLDER_URI)
                .apply()
            folderUri = null
            folder = null
            renderAndroidFiles()
        }
    }

    private fun setFolder(uri: Uri) {
        val doc = DocumentFile.fromTreeUri(this, uri)
        require(doc != null && doc.exists() && doc.isDirectory) {
            "selected Android folder is unavailable"
        }
        folderUri = uri
        folder = doc
        computerFolder.text = doc.name ?: "SELECTED FOLDER"
        renderAndroidFiles()
    }

    private fun renderAndroidFiles() {
        if (!::computerList.isInitialized) return
        computerList.removeAllViews()

        val currentFolder = folder
        if (currentFolder == null || !currentFolder.exists()) {
            computerFolder.text = "NO FOLDER SELECTED"
            computerList.addView(
                textCell("CHOOSE A FOLDER TO SHOW ANDROID FILES", 11f, R.color.amber_dim)
            )
            return
        }

        computerFolder.text = currentFolder.name ?: "SELECTED FOLDER"
        val files = try {
            currentFolder.listFiles()
                .filter { it.isFile }
                .sortedBy { it.name?.lowercase().orEmpty() }
        } catch (t: Throwable) {
            computerList.addView(
                textCell("FOLDER ERROR: ${t.message.orEmpty()}", 11f, R.color.red)
            )
            return
        }

        if (files.isEmpty()) {
            computerList.addView(
                textCell("(EMPTY FOLDER)", 11f, R.color.amber_dim)
            )
            return
        }

        for (file in files) {
            val name = file.name ?: "(unnamed)"
            val supported = isEvoFilename(name)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(7), dp(4), dp(7))
                isClickable = supported
                isFocusable = supported
                if (supported) {
                    setOnClickListener { prepareUpload(file) }
                }
            }

            val title = TextView(this).apply {
                text = if (supported) name else "$name  [NOT EVO]"
                setTextColor(
                    getColor(if (supported) R.color.amber else R.color.amber_dim)
                )
                textSize = 12f
                typeface = Typeface.MONOSPACE
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }
            val size = TextView(this).apply {
                text = formatBytes(file.length())
                setTextColor(getColor(R.color.amber_dim))
                textSize = 10f
                gravity = Gravity.END
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(
                    dp(72),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val arrow = TextView(this).apply {
                text = if (supported) "→" else ""
                setTextColor(getColor(R.color.amber))
                textSize = 18f
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(
                    dp(28),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            row.addView(title)
            row.addView(size)
            row.addView(arrow)
            computerList.addView(row)
            computerList.addView(divider())
        }
    }

    private fun renderCalculatorEntries(entries: List<EvoEntry>) {
        calculatorList.removeAllViews()

        if (entries.isEmpty()) {
            calculatorList.addView(
                textCell("(NO VARIABLES)", 11f, R.color.amber_dim)
            )
            return
        }

        for (entry in entries) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(7), dp(4), dp(7))
                isClickable = true
                isFocusable = true
                setOnClickListener { downloadVariable(entry) }
            }

            val name = TextView(this).apply {
                text = "${entry.name}  [${EvoFileCodec.extensionForType(entry.type)}]"
                setTextColor(getColor(R.color.amber))
                textSize = 12f
                typeface = Typeface.MONOSPACE
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            val size = TextView(this).apply {
                text = formatBytes(entry.size)
                setTextColor(getColor(R.color.amber))
                textSize = 10f
                gravity = Gravity.END
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(
                    dp(76),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val memory = TextView(this).apply {
                text = if (entry.archived) "ARC" else "RAM"
                setTextColor(getColor(R.color.amber_dim))
                textSize = 9f
                gravity = Gravity.END
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(
                    dp(43),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            row.addView(name)
            row.addView(size)
            row.addView(memory)
            calculatorList.addView(row)
            calculatorList.addView(divider())
        }
    }

    private fun prepareUpload(file: DocumentFile) {
        if (transferring || connecting) return
        val activeClient = client
        if (activeClient == null) {
            setTransferError("NO CALCULATOR CONNECTED")
            return
        }

        transferring = true
        setTransferStatus("READING ${file.name ?: "ANDROID FILE"}")

        executor.execute {
            try {
                val data = contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
                    ?: error("Android could not read ${file.name ?: "file"}")
                val info = EvoFileCodec.inspect(data)
                val existing = calculatorEntries.firstOrNull {
                    EvoFileCodec.sameIdentity(info, it)
                }

                if (existing != null) {
                    runOnUiThread {
                        transferring = false
                        AlertDialog.Builder(this)
                            .setTitle("Replace existing variable?")
                            .setMessage(
                                "${existing.name} already exists on the Evo. Replace it?"
                            )
                            .setPositiveButton("REPLACE") { _, _ ->
                                uploadPrepared(file, data, true)
                            }
                            .setNegativeButton("CANCEL") { _, _ ->
                                setReady("UPLOAD CANCELLED")
                            }
                            .setOnCancelListener { setReady("UPLOAD CANCELLED") }
                            .show()
                    }
                } else {
                    runOnUiThread { uploadPrepared(file, data, false) }
                }
            } catch (t: Throwable) {
                appendLog("UPLOAD PREP ERROR ${t.message.orEmpty()}")
                runOnUiThread {
                    transferring = false
                    setTransferError(t.message ?: t.javaClass.simpleName)
                }
            }
        }
    }

    private fun uploadPrepared(
        file: DocumentFile,
        data: ByteArray,
        overwrite: Boolean
    ) {
        if (transferring || connecting) return
        val activeClient = client ?: run {
            setTransferError("NO CALCULATOR CONNECTED")
            return
        }

        transferring = true
        val displayName = file.name ?: "ANDROID FILE"
        setTransferStatus("SENDING $displayName → EVO")

        executor.execute {
            try {
                val result = activeClient.uploadVariable(data, overwrite)
                val entries = sortedEntries(activeClient.listFiles())
                calculatorEntries = entries
                val target = if (result.archived) "ARC" else "RAM"

                runOnUiThread {
                    transferring = false
                    renderCalculatorEntries(entries)
                    renderAndroidFiles()
                    setReady("SENT $displayName → EVO · $target")
                }
            } catch (t: Throwable) {
                appendLog("UPLOAD ERROR ${t.message.orEmpty()}")
                runOnUiThread {
                    transferring = false
                    setTransferError(t.message ?: t.javaClass.simpleName)
                }
            }
        }
    }

    private fun downloadVariable(entry: EvoEntry) {
        if (transferring || connecting) return
        val activeClient = client
        if (activeClient == null) {
            setTransferError("NO CALCULATOR CONNECTED")
            return
        }

        val currentFolder = folder
        if (currentFolder == null || !currentFolder.exists()) {
            pendingDownload = entry
            setTransferStatus("CHOOSE ANDROID DESTINATION FOLDER")
            chooseAndroidFolder()
            return
        }

        transferring = true
        setTransferStatus("SAVING ${entry.name} → ANDROID")

        executor.execute {
            try {
                val data = activeClient.downloadVariable(entry)
                val output = createUniqueFile(
                    currentFolder,
                    EvoFileCodec.outputFileName(entry)
                )
                contentResolver.openOutputStream(output.uri, "w")?.use {
                    it.write(data)
                    it.flush()
                } ?: error("Android could not open destination file")

                runOnUiThread {
                    transferring = false
                    renderAndroidFiles()
                    setReady("SAVED ${output.name ?: entry.name} → ANDROID")
                }
            } catch (t: Throwable) {
                appendLog("DOWNLOAD ERROR ${t.message.orEmpty()}")
                runOnUiThread {
                    transferring = false
                    setTransferError(t.message ?: t.javaClass.simpleName)
                }
            }
        }
    }

    private fun createUniqueFile(
        directory: DocumentFile,
        requestedName: String
    ): DocumentFile {
        var name = requestedName
        var index = 1
        while (directory.findFile(name) != null) {
            val dot = requestedName.lastIndexOf('.')
            name = if (dot > 0) {
                requestedName.substring(0, dot) +
                    " ($index)" +
                    requestedName.substring(dot)
            } else {
                "$requestedName ($index)"
            }
            index++
            require(index < 1000) { "too many files with the same name" }
        }
        return directory.createFile("application/octet-stream", name)
            ?: error("Android could not create $name")
    }

    private fun sortedEntries(entries: List<EvoEntry>): List<EvoEntry> =
        entries.sortedWith(
            compareBy<EvoEntry> { it.type }
                .thenBy { it.name.lowercase() }
        )

    private fun isEvoFilename(name: String): Boolean {
        val lower = name.lowercase()
        return listOf(
            ".8xn2", ".8xl2", ".8xp2", ".8xd2", ".8ci2",
            ".8ca2", ".8xm2", ".8xy2", ".8xv2", ".8xs2",
            ".8xw2", ".8xz2", ".8xt2", ".8xpy2", ".8mp2"
        ).any { lower.endsWith(it) }
    }

    private fun setSearching() {
        connecting = false
        transferring = false
        currentDeviceId = null
        calculatorEntries = emptyList()
        connectionStatus.text = "● NO CALCULATOR CONNECTED"
        connectionStatus.setTextColor(getColor(R.color.amber))
        operationStatus.text = "SEARCHING..."
        operationStatus.setTextColor(getColor(R.color.amber))
        diagnostic.text =
            "Set USB controlled by CONNECTED DEVICE if Android does not enumerate the Evo."
        calculatorList.removeAllViews()
    }

    private fun setConnecting(detail: String) {
        connectionStatus.text = "● TI-84 EVO DETECTED"
        connectionStatus.setTextColor(getColor(R.color.amber))
        operationStatus.text = "CONNECTING..."
        operationStatus.setTextColor(getColor(R.color.amber))
        diagnostic.text = detail
    }

    private fun setTransferStatus(detail: String) {
        connectionStatus.text = "● TI-84 EVO CONNECTED"
        connectionStatus.setTextColor(getColor(R.color.green))
        operationStatus.text = "TRANSFERRING..."
        operationStatus.setTextColor(getColor(R.color.amber))
        diagnostic.text = detail.take(180)
    }

    private fun setReady(detail: String) {
        connectionStatus.text = "● TI-84 EVO CONNECTED"
        connectionStatus.setTextColor(getColor(R.color.green))
        operationStatus.text = "READY"
        operationStatus.setTextColor(getColor(R.color.green))
        diagnostic.text = detail.take(180)
    }

    private fun setTransferError(detail: String) {
        connectionStatus.text = "● TI-84 EVO CONNECTED"
        connectionStatus.setTextColor(getColor(R.color.green))
        operationStatus.text = "TRANSFER FAILED"
        operationStatus.setTextColor(getColor(R.color.red))
        diagnostic.text = detail.take(180)
    }

    private fun showConnectionError(title: String, detail: String) {
        connectionStatus.text = "● $title"
        connectionStatus.setTextColor(getColor(R.color.red))
        operationStatus.text = "CONNECTING..."
        operationStatus.setTextColor(getColor(R.color.amber))
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
            val line = "${System.currentTimeMillis()} $message\n"
            File(filesDir, "ti_jack_evo_android.log").appendText(line)
        } catch (_: Throwable) {
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

    private fun divider(): View =
        View(this).apply {
            setBackgroundColor(getColor(R.color.divider))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            )
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
            setPadding(dp(4), dp(10), dp(4), dp(10))
            typeface = Typeface.MONOSPACE
        }

    private fun formatBytes(value: Long): String =
        when {
            value < 1024 -> "$value B"
            value < 1024 * 1024 -> "%.1f KB".format(value / 1024.0)
            else -> "%.1f MB".format(value / (1024.0 * 1024.0))
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
