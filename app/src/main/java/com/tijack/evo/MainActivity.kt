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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.tijack.evo.USB_PERMISSION"
        private const val REQUEST_FOLDER = 401
        private const val PREFS = "ti_jack_android"
        private const val PREF_FOLDER_URI = "folder_uri"
    }

    private enum class ConflictPolicy { REPLACE, SKIP }

    private data class PreparedUpload(
        val file: DocumentFile,
        val data: ByteArray,
        val info: EvoFileInfo,
        val conflict: Boolean
    )

    private lateinit var usbManager: UsbManager
    private lateinit var connectionStatus: TextView
    private lateinit var operationStatus: TextView
    private lateinit var diagnostic: TextView
    private lateinit var computerFolder: TextView
    private lateinit var computerList: LinearLayout
    private lateinit var calculatorList: LinearLayout
    private lateinit var chooseFolder: Button
    private lateinit var sendSelected: Button
    private lateinit var saveSelected: Button

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var connecting = false
    @Volatile private var transferring = false

    private var client: EvoUsbClient? = null
    private var currentDeviceId: Int? = null
    private var calculatorEntries: List<EvoEntry> = emptyList()
    private var folder: DocumentFile? = null
    private var pendingDownloads: List<EvoEntry> = emptyList()

    private val selectedAndroid = LinkedHashSet<String>()
    private val selectedCalculator = LinkedHashSet<String>()

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
        sendSelected = findViewById(R.id.sendSelected)
        saveSelected = findViewById(R.id.saveSelected)

        chooseFolder.setOnClickListener { chooseAndroidFolder() }
        sendSelected.setOnClickListener { prepareUploadBatch() }
        saveSelected.setOnClickListener {
            prepareDownloadBatch(selectedCalculatorEntries())
        }

        loadSavedFolder()
        updateActionButtons()

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
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
            if (pendingDownloads.isNotEmpty()) {
                pendingDownloads = emptyList()
                setReady("DOWNLOAD CANCELLED · NO ANDROID FOLDER SELECTED")
            }
            return
        }

        val uri = requireNotNull(data.data)
        val takeFlags = data.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
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
        val waiting = pendingDownloads
        pendingDownloads = emptyList()
        if (waiting.isNotEmpty()) handler.post { prepareDownloadBatch(waiting) }
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
            it.vendorId == EvoUsbClient.TI_VID && it.productId == EvoUsbClient.EVO_PID
        }
        if (device == null) {
            closeClient()
            setSearching()
            scheduleRetry(1200)
            return
        }
        if (client != null && currentDeviceId == device.deviceId) return

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
                    setReady("${entries.size} VARIABLES · 0451:E018 · CDC 115200")
                }
            } catch (t: Throwable) {
                try { localClient?.close() } catch (_: Throwable) {}
                appendLog("ERROR ${t.javaClass.simpleName}: ${t.message.orEmpty()}")
                runOnUiThread {
                    connecting = false
                    val message = t.message.orEmpty()
                    val temporary =
                        "busy" in message.lowercase() || "timeout" in message.lowercase()
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
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(PREF_FOLDER_URI).apply()
            folder = null
            renderAndroidFiles()
        }
    }

    private fun setFolder(uri: Uri) {
        val doc = DocumentFile.fromTreeUri(this, uri)
        require(doc != null && doc.exists() && doc.isDirectory) {
            "selected Android folder is unavailable"
        }
        folder = doc
        selectedAndroid.clear()
        computerFolder.text = doc.name ?: "SELECTED FOLDER"
        renderAndroidFiles()
    }

    private fun renderAndroidFiles() {
        if (!::computerList.isInitialized) return
        computerList.removeAllViews()
        val currentFolder = folder
        if (currentFolder == null || !currentFolder.exists()) {
            selectedAndroid.clear()
            computerFolder.text = "NO FOLDER SELECTED"
            computerList.addView(
                textCell("CHOOSE A FOLDER TO SHOW ANDROID FILES", 11f, R.color.amber_dim)
            )
            updateActionButtons()
            return
        }

        computerFolder.text = currentFolder.name ?: "SELECTED FOLDER"
        val files = try {
            currentFolder.listFiles().filter { it.isFile }
                .sortedBy { it.name?.lowercase().orEmpty() }
        } catch (t: Throwable) {
            computerList.addView(
                textCell("FOLDER ERROR: ${t.message.orEmpty()}", 11f, R.color.red)
            )
            updateActionButtons()
            return
        }

        val validKeys = files.filter { isEvoFilename(it.name.orEmpty()) }
            .mapTo(HashSet()) { androidKey(it) }
        selectedAndroid.retainAll(validKeys)

        if (files.isEmpty()) {
            computerList.addView(textCell("(EMPTY FOLDER)", 11f, R.color.amber_dim))
            updateActionButtons()
            return
        }

        for (file in files) {
            val name = file.name ?: "(unnamed)"
            val supported = isEvoFilename(name)
            val key = androidKey(file)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(7), dp(4), dp(7))
                isClickable = supported
                isFocusable = supported
            }
            val title = TextView(this).apply {
                setTextColor(getColor(if (supported) R.color.amber else R.color.amber_dim))
                textSize = 12f
                typeface = Typeface.MONOSPACE
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val size = TextView(this).apply {
                text = formatBytes(file.length())
                setTextColor(getColor(R.color.amber_dim))
                textSize = 10f
                gravity = Gravity.END
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val marker = TextView(this).apply {
                setTextColor(getColor(R.color.amber))
                textSize = 10f
                gravity = Gravity.END
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(dp(34), LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            fun refreshSelection() {
                val selected = key in selectedAndroid
                title.text = when {
                    !supported -> "$name  [NOT EVO]"
                    selected -> "✓ $name"
                    else -> name
                }
                marker.text = if (selected) "SEL" else ""
                row.setBackgroundColor(getColor(if (selected) R.color.selection else R.color.panel))
            }

            if (supported) {
                row.setOnClickListener {
                    if (!selectedAndroid.add(key)) selectedAndroid.remove(key)
                    refreshSelection()
                    updateActionButtons()
                }
            }
            row.addView(title)
            row.addView(size)
            row.addView(marker)
            refreshSelection()
            computerList.addView(row)
            computerList.addView(divider())
        }
        updateActionButtons()
    }

    private fun renderCalculatorEntries(entries: List<EvoEntry>) {
        calculatorList.removeAllViews()
        val validKeys = entries.mapTo(HashSet()) { calculatorKey(it) }
        selectedCalculator.retainAll(validKeys)

        if (entries.isEmpty()) {
            calculatorList.addView(textCell("(NO VARIABLES)", 11f, R.color.amber_dim))
            updateActionButtons()
            return
        }

        for (entry in entries) {
            val key = calculatorKey(entry)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(7), dp(4), dp(7))
                isClickable = true
                isFocusable = true
            }
            val name = TextView(this).apply {
                setTextColor(getColor(R.color.amber))
                textSize = 12f
                typeface = Typeface.MONOSPACE
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val size = TextView(this).apply {
                text = formatBytes(entry.size)
                setTextColor(getColor(R.color.amber))
                textSize = 10f
                gravity = Gravity.END
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(dp(76), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val memory = TextView(this).apply {
                text = if (entry.archived) "ARC" else "RAM"
                setTextColor(getColor(R.color.amber_dim))
                textSize = 9f
                gravity = Gravity.END
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(dp(43), LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            fun refreshSelection() {
                val selected = key in selectedCalculator
                name.text =
                    "${if (selected) "✓ " else ""}${entry.name}  [${EvoFileCodec.extensionForType(entry.type)}]"
                row.setBackgroundColor(getColor(if (selected) R.color.selection else R.color.panel))
            }
            row.setOnClickListener {
                if (!selectedCalculator.add(key)) selectedCalculator.remove(key)
                refreshSelection()
                updateActionButtons()
            }
            row.addView(name)
            row.addView(size)
            row.addView(memory)
            refreshSelection()
            calculatorList.addView(row)
            calculatorList.addView(divider())
        }
        updateActionButtons()
    }

    private fun prepareUploadBatch() {
        if (transferring || connecting) return
        if (client == null) return setTransferError("NO CALCULATOR CONNECTED")
        val currentFolder = folder ?: return setTransferError("NO ANDROID FOLDER SELECTED")

        val files = currentFolder.listFiles()
            .filter { it.isFile && androidKey(it) in selectedAndroid }
            .sortedBy { it.name?.lowercase().orEmpty() }
        if (files.isEmpty()) return

        transferring = true
        updateActionButtons()
        setTransferStatus("READING ${files.size} SELECTED FILE${if (files.size == 1) "" else "S"}")
        val existingSnapshot = calculatorEntries

        executor.execute {
            val prepared = ArrayList<PreparedUpload>()
            var skipped = 0
            for ((index, file) in files.withIndex()) {
                try {
                    runOnUiThread {
                        diagnostic.text =
                            "READING ${index + 1}/${files.size} · ${file.name ?: "ANDROID FILE"}"
                    }
                    val data = readDocumentFile(file)
                    runOnUiThread {
                        diagnostic.text =
                            "VALIDATING ${index + 1}/${files.size} · ${file.name ?: "ANDROID FILE"}"
                    }
                    val info = EvoFileCodec.inspect(data)
                    val conflict = existingSnapshot.any { EvoFileCodec.sameIdentity(info, it) }
                    prepared += PreparedUpload(file, data, info, conflict)
                } catch (t: Throwable) {
                    skipped++
                    appendLog("UPLOAD SKIP ${file.name.orEmpty()}: ${t.message.orEmpty()}")
                }
            }

            runOnUiThread {
                transferring = false
                updateActionButtons()
                if (prepared.isEmpty()) {
                    setReady(batchSummary(0, skipped, 0))
                    return@runOnUiThread
                }
                val conflicts = prepared.count { it.conflict }
                if (conflicts > 0) {
                    showConflictDialog(
                        conflicts,
                        "CALCULATOR",
                        onReplace = { startUploadBatch(prepared, ConflictPolicy.REPLACE, skipped) },
                        onSkip = { startUploadBatch(prepared, ConflictPolicy.SKIP, skipped) }
                    )
                } else {
                    startUploadBatch(prepared, ConflictPolicy.SKIP, skipped)
                }
            }
        }
    }

    private fun startUploadBatch(
        prepared: List<PreparedUpload>,
        policy: ConflictPolicy,
        preSkipped: Int
    ) {
        if (transferring || connecting) return
        val activeClient = client ?: return setTransferError("NO CALCULATOR CONNECTED")
        transferring = true
        updateActionButtons()
        setTransferStatus("SENDING ${prepared.size} FILE${if (prepared.size == 1) "" else "S"} → EVO")

        executor.execute {
            var transferredCount = 0
            var skippedCount = preSkipped
            var failedCount = 0
            val failedKeys = LinkedHashSet<String>()

            for ((index, item) in prepared.withIndex()) {
                if (item.conflict && policy == ConflictPolicy.SKIP) {
                    skippedCount++
                    continue
                }
                runOnUiThread {
                    diagnostic.text =
                        "SENDING ${index + 1}/${prepared.size} · ${item.file.name ?: "ANDROID FILE"}"
                }
                try {
                    activeClient.uploadVariable(
                        item.data,
                        overwrite = item.conflict && policy == ConflictPolicy.REPLACE
                    )
                    transferredCount++
                } catch (t: Throwable) {
                    failedCount++
                    failedKeys += androidKey(item.file)
                    appendLog("UPLOAD ERROR ${item.file.name.orEmpty()}: ${t.message.orEmpty()}")
                }
            }

            val refreshed = try {
                sortedEntries(activeClient.listFiles())
            } catch (t: Throwable) {
                appendLog("POST-UPLOAD LIST ERROR ${t.message.orEmpty()}")
                calculatorEntries
            }
            calculatorEntries = refreshed

            runOnUiThread {
                transferring = false
                selectedAndroid.clear()
                selectedAndroid.addAll(failedKeys)
                renderCalculatorEntries(refreshed)
                renderAndroidFiles()
                setReady(batchSummary(transferredCount, skippedCount, failedCount))
            }
        }
    }

    private fun prepareDownloadBatch(entries: List<EvoEntry>) {
        if (transferring || connecting || entries.isEmpty()) return
        if (client == null) return setTransferError("NO CALCULATOR CONNECTED")
        val currentFolder = folder
        if (currentFolder == null || !currentFolder.exists()) {
            pendingDownloads = entries
            setTransferStatus("CHOOSE ANDROID DESTINATION FOLDER")
            chooseAndroidFolder()
            return
        }

        val conflicts = entries.count {
            currentFolder.findFile(EvoFileCodec.outputFileName(it)) != null
        }
        if (conflicts > 0) {
            showConflictDialog(
                conflicts,
                "ANDROID",
                onReplace = { startDownloadBatch(entries, ConflictPolicy.REPLACE) },
                onSkip = { startDownloadBatch(entries, ConflictPolicy.SKIP) }
            )
        } else {
            startDownloadBatch(entries, ConflictPolicy.SKIP)
        }
    }

    private fun startDownloadBatch(entries: List<EvoEntry>, policy: ConflictPolicy) {
        if (transferring || connecting) return
        val activeClient = client ?: return setTransferError("NO CALCULATOR CONNECTED")
        val currentFolder = folder ?: return setTransferError("NO ANDROID FOLDER SELECTED")
        transferring = true
        updateActionButtons()
        setTransferStatus("SAVING ${entries.size} FILE${if (entries.size == 1) "" else "S"} → ANDROID")

        executor.execute {
            var transferredCount = 0
            var skippedCount = 0
            var failedCount = 0
            val failedKeys = LinkedHashSet<String>()

            for ((index, entry) in entries.withIndex()) {
                val requestedName = EvoFileCodec.outputFileName(entry)
                val existing = currentFolder.findFile(requestedName)
                if (existing != null && policy == ConflictPolicy.SKIP) {
                    skippedCount++
                    continue
                }
                runOnUiThread {
                    diagnostic.text =
                        "SAVING ${index + 1}/${entries.size} · ${entry.name} → ANDROID"
                }
                try {
                    val data = activeClient.downloadVariable(entry)
                    writeAndroidFile(currentFolder, requestedName, data, existing != null)
                    transferredCount++
                } catch (t: Throwable) {
                    failedCount++
                    failedKeys += calculatorKey(entry)
                    appendLog("DOWNLOAD ERROR ${entry.name}: ${t.message.orEmpty()}")
                }
            }

            runOnUiThread {
                transferring = false
                selectedCalculator.clear()
                selectedCalculator.addAll(failedKeys)
                renderAndroidFiles()
                renderCalculatorEntries(calculatorEntries)
                setReady(batchSummary(transferredCount, skippedCount, failedCount))
            }
        }
    }

    private fun readDocumentFile(file: DocumentFile): ByteArray {
        val declared = file.length()
        require(declared >= 0) { "Android reported an invalid file size" }
        require(declared <= 64L * 1024L * 1024L) { "file is too large" }
        val input = contentResolver.openInputStream(file.uri)
            ?: error("Android could not read ${file.name ?: "file"}")
        input.use { stream ->
            val out = ByteArrayOutputStream(
                if (declared in 1..Int.MAX_VALUE.toLong()) declared.toInt() else 8192
            )
            val buffer = ByteArray(8192)
            if (declared > 0) {
                var remaining = declared
                while (remaining > 0) {
                    val count = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count < 0) break
                    if (count == 0) continue
                    out.write(buffer, 0, count)
                    remaining -= count
                }
                require(remaining == 0L) {
                    "${file.name ?: "file"} ended before its reported $declared bytes"
                }
            } else {
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (count > 0) out.write(buffer, 0, count)
                    require(out.size() <= 64 * 1024 * 1024) { "file is too large" }
                }
            }
            return out.toByteArray()
        }
    }

    private fun writeAndroidFile(
        directory: DocumentFile,
        requestedName: String,
        data: ByteArray,
        replace: Boolean
    ) {
        var target = directory.findFile(requestedName)
        if (target != null && replace) {
            val direct = try {
                contentResolver.openOutputStream(target.uri, "rwt")
            } catch (_: Throwable) {
                null
            }
            if (direct != null) {
                direct.use { it.write(data); it.flush() }
                return
            }
            require(target.delete()) { "Android could not replace $requestedName" }
            target = null
        }
        require(target == null) { "$requestedName already exists" }
        val created = directory.createFile("application/octet-stream", requestedName)
            ?: error("Android could not create $requestedName")
        contentResolver.openOutputStream(created.uri, "w")?.use {
            it.write(data)
            it.flush()
        } ?: error("Android could not open $requestedName")
    }

    private fun showConflictDialog(
        conflicts: Int,
        destination: String,
        onReplace: () -> Unit,
        onSkip: () -> Unit
    ) {
        AlertDialog.Builder(this)
            .setTitle("Replace existing files?")
            .setMessage(
                "$conflicts selected file${if (conflicts == 1) "" else "s"} already " +
                    "exist${if (conflicts == 1) "s" else ""} on $destination."
            )
            .setPositiveButton("REPLACE") { _, _ -> onReplace() }
            .setNegativeButton("SKIP EXISTING") { _, _ -> onSkip() }
            .setNeutralButton("CANCEL") { _, _ -> setReady("TRANSFER CANCELLED") }
            .setOnCancelListener { setReady("TRANSFER CANCELLED") }
            .show()
    }

    private fun selectedCalculatorEntries(): List<EvoEntry> =
        calculatorEntries.filter { calculatorKey(it) in selectedCalculator }

    private fun androidKey(file: DocumentFile): String = file.uri.toString()

    private fun calculatorKey(entry: EvoEntry): String =
        "${entry.type}:" + entry.tokenName.joinToString("") {
            "%02X".format(it.toInt() and 0xFF)
        }

    private fun updateActionButtons() {
        if (!::sendSelected.isInitialized) return
        sendSelected.text = if (selectedAndroid.isEmpty()) {
            "SEND SELECTED →"
        } else {
            "SEND ${selectedAndroid.size} →"
        }
        saveSelected.text = if (selectedCalculator.isEmpty()) {
            "← SAVE SELECTED"
        } else {
            "← SAVE ${selectedCalculator.size}"
        }
        val enabled = !connecting && !transferring
        sendSelected.isEnabled = enabled && selectedAndroid.isNotEmpty() && client != null
        saveSelected.isEnabled = enabled && selectedCalculator.isNotEmpty() && client != null
    }

    private fun batchSummary(done: Int, skipped: Int, failed: Int): String =
        "$done TRANSFERRED · $skipped SKIPPED · $failed FAILED"

    private fun sortedEntries(entries: List<EvoEntry>): List<EvoEntry> =
        entries.sortedWith(compareBy<EvoEntry> { it.type }.thenBy { it.name.lowercase() })

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
        selectedCalculator.clear()
        connectionStatus.text = "● NO CALCULATOR CONNECTED"
        connectionStatus.setTextColor(getColor(R.color.amber))
        operationStatus.text = "SEARCHING..."
        operationStatus.setTextColor(getColor(R.color.amber))
        diagnostic.text = "Set USB controlled by CONNECTED DEVICE if Android does not enumerate the Evo."
        calculatorList.removeAllViews()
        updateActionButtons()
    }

    private fun setConnecting(detail: String) {
        connectionStatus.text = "● TI-84 EVO DETECTED"
        connectionStatus.setTextColor(getColor(R.color.amber))
        operationStatus.text = "CONNECTING..."
        operationStatus.setTextColor(getColor(R.color.amber))
        diagnostic.text = detail
        updateActionButtons()
    }

    private fun setTransferStatus(detail: String) {
        connectionStatus.text = "● TI-84 EVO CONNECTED"
        connectionStatus.setTextColor(getColor(R.color.green))
        operationStatus.text = "TRANSFERRING..."
        operationStatus.setTextColor(getColor(R.color.amber))
        diagnostic.text = detail.take(180)
        updateActionButtons()
    }

    private fun setReady(detail: String) {
        connectionStatus.text = "● TI-84 EVO CONNECTED"
        connectionStatus.setTextColor(getColor(R.color.green))
        operationStatus.text = "READY"
        operationStatus.setTextColor(getColor(R.color.green))
        diagnostic.text = detail.take(180)
        updateActionButtons()
    }

    private fun setTransferError(detail: String) {
        connectionStatus.text = "● TI-84 EVO CONNECTED"
        connectionStatus.setTextColor(getColor(R.color.green))
        operationStatus.text = "TRANSFER FAILED"
        operationStatus.setTextColor(getColor(R.color.red))
        diagnostic.text = detail.take(180)
        updateActionButtons()
    }

    private fun showConnectionError(title: String, detail: String) {
        connectionStatus.text = "● $title"
        connectionStatus.setTextColor(getColor(R.color.red))
        operationStatus.text = "CONNECTING..."
        operationStatus.setTextColor(getColor(R.color.amber))
        diagnostic.text = detail.take(180)
        updateActionButtons()
    }

    private fun scheduleRetry(delayMs: Long) {
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, delayMs)
    }

    @Synchronized
    private fun closeClient() {
        try { client?.close() } catch (_: Throwable) {}
        client = null
    }

    private fun appendLog(message: String) {
        try {
            File(filesDir, "ti_jack_evo_android.log")
                .appendText("${System.currentTimeMillis()} $message\n")
        } catch (_: Throwable) {}
    }

    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(getColor(R.color.divider))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun textCell(value: String, size: Float, color: Int): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(getColor(color))
            setPadding(dp(4), dp(10), dp(4), dp(10))
            typeface = Typeface.MONOSPACE
        }

    private fun formatBytes(value: Long): String = when {
        value < 1024 -> "$value B"
        value < 1024 * 1024 -> "%.1f KB".format(value / 1024.0)
        else -> "%.1f MB".format(value / (1024.0 * 1024.0))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
