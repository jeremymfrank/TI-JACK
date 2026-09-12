package com.tijack.evo

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException

internal data class EvoUploadResult(
    val info: EvoFileInfo,
    val archived: Boolean
)

internal class EvoUsbClient(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val log: (String) -> Unit
) : Closeable {

    companion object {
        const val TI_VID = 0x0451
        const val EVO_PID = 0xE018
        private const val BAUD = 115200
        private const val TIMEOUT_MS = 5000
        private const val DIRECTORY_URL =
            "hh01/get/hh01/inf/res?name=directory&gotohome=1"
    }

    private var connection: UsbDeviceConnection? = null
    private var port: UsbSerialPort? = null
    private var sessionDepth = 0
    private val rx = ArrayList<Byte>()

    @Synchronized
    fun open() {
        if (port != null) return

        require(device.vendorId == TI_VID && device.productId == EVO_PID) {
            "not a TI-84 Evo USB device"
        }

        log(
            "USB ${"%04X".format(device.vendorId)}:" +
                "${"%04X".format(device.productId)} " +
                "interfaces=${device.interfaceCount}"
        )

        val conn = usbManager.openDevice(device)
            ?: error("Android could not open the Evo USB device")

        try {
            val driver = CdcAcmSerialDriver(device)
            val serialPort = driver.ports.firstOrNull()
                ?: error("Evo CDC driver exposed no serial ports")

            serialPort.open(conn)
            serialPort.setParameters(
                BAUD,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )

            try {
                serialPort.setDTR(true)
                serialPort.setRTS(true)
                log("CDC DTR/RTS asserted")
            } catch (t: Throwable) {
                log("CDC control-line warning: ${t.message.orEmpty()}")
            }

            connection = conn
            port = serialPort
            drainInput()
            log("CDC open at $BAUD baud")
        } catch (t: Throwable) {
            try {
                conn.close()
            } catch (_: Throwable) {
            }
            throw t
        }
    }

    fun listFiles(): List<EvoEntry> = withSession {
        val raw = getRequest(DIRECTORY_URL)
        log("directory payload ${raw.size} bytes")
        EvoDirectory.parse(raw)
    }

    fun downloadVariable(entry: EvoEntry): ByteArray = withSession {
        require(entry.tokenName.isNotEmpty()) {
            "calculator did not provide a token name for ${entry.name}"
        }
        val encodedName = EvoFileCodec.urlEncodeTokenName(entry.tokenName)
        val url =
            "hh01/get/hh01/xfr/var?name=$encodedName&type=${entry.type}"
        val body = getRequest(url)
        val file = EvoFileCodec.appendChecksum(body)
        log(
            "downloaded ${entry.name} type=${entry.type} " +
                "${file.size} bytes"
        )
        file
    }

    fun uploadVariable(
        file: ByteArray,
        overwrite: Boolean
    ): EvoUploadResult = withSession {
        val info = EvoFileCodec.inspect(file)
        val tokenName = info.tokenName
        require(tokenName != null && tokenName.isNotEmpty()) {
            "Evo file metadata has no variable name"
        }
        var archive = info.type in setOf(4, 5, 18)

        try {
            putVariable(file, info, archive, overwrite)
        } catch (first: Throwable) {
            val text = first.message.orEmpty()
            if (!archive && ("PM" in text || "DP" in text)) {
                log(
                    "RAM target rejected type=${info.type}; " +
                        "retrying Archive"
                )
                archive = true
                putVariable(file, info, archive, overwrite)
            } else {
                throw first
            }
        }

        // Give the calculator a short moment to publish the newly committed
        // variable into its directory before verification starts.
        SystemClock.sleep(120)
        verifyUpload(info, file)
        log(
            "uploaded and verified type=${info.type} " +
                "payload=${file.size} bytes to " +
                if (archive) "Archive" else "RAM"
        )
        EvoUploadResult(info, archive)
    }

    private fun verifyUpload(
        info: EvoFileInfo,
        expectedFile: ByteArray
    ) {
        var match: EvoEntry? = null
        var lastError: Throwable? = null

        for (attempt in 1..3) {
            try {
                val entries = listFiles()
                match = entries.firstOrNull {
                    EvoFileCodec.sameIdentity(info, it)
                }
                if (match != null) break
            } catch (t: Throwable) {
                lastError = t
                log(
                    "upload verification directory attempt $attempt/3 failed: " +
                        t.message.orEmpty()
                )
            }
            SystemClock.sleep(120L * attempt)
        }

        val entry = match ?: run {
            if (lastError != null) {
                throw IOException(
                    "upload was acknowledged but verification could not read the calculator directory",
                    lastError
                )
            }
            throw IOException(
                "upload was acknowledged but the variable did not appear on the calculator"
            )
        }

        log(
            "upload directory verified ${entry.name} type=${entry.type} " +
                "mem=${if (entry.archived) "Archive" else "RAM"} size=${entry.size}"
        )
        val readBack = downloadVariable(entry)
        if (!readBack.contentEquals(expectedFile)) {
            throw IOException(
                "upload verification mismatch for ${entry.name}: " +
                    "sent ${expectedFile.size} bytes, read back ${readBack.size} bytes"
            )
        }
        log("upload read-back verified ${entry.name} ${readBack.size} bytes")
    }

    private fun putVariable(
        payload: ByteArray,
        info: EvoFileInfo,
        archive: Boolean,
        overwrite: Boolean
    ) {
        val tokenName = requireNotNull(info.tokenName)
        val encodedName = EvoFileCodec.urlEncodeTokenName(tokenName)
        require(encodedName.isNotBlank()) { "Evo variable name is empty" }
        val memTarget = if (archive) 1 else 0
        val policy = if (overwrite) 1 else 0
        val url =
            "hh01/xfr/var?name=$encodedName&type=${info.type}" +
                "&memtarget=$memTarget&policy=$policy"
        log(
            "upload request type=${info.type} memtarget=$memTarget " +
                "policy=$policy name=$encodedName"
        )
        putRequest(url, payload)
    }

    private fun putRequest(url: String, payload: ByteArray) {
        val serial = requireNotNull(port) { "serial port is not open" }
        drainInput(40)
        val session = Kermit.Session()
        var sequence = 0

        val attributes =
            Kermit.fileAttribute('"', "B8") +
                Kermit.fileAttribute('1', payload.size.toString()) +
                Kermit.fileAttribute('@')

        sendExpectAck(serial, sequence++, 'S', Kermit.sendInit, session)
        sendExpectAck(
            serial,
            sequence++,
            'F',
            url.toByteArray(Charsets.UTF_8),
            session
        )
        sendExpectAck(serial, sequence++, 'A', attributes, session)

        val wire = Kermit.encode(payload)
        val chunks = Kermit.splitEncoded(wire, session.dataChunkSize)
        log(
            "upload payload=${payload.size} encoded=${wire.size} " +
                "chunks=${chunks.size}"
        )
        for (chunk in chunks) {
            sendExpectAck(serial, sequence++, 'D', chunk, session)
        }

        sendExpectAck(serial, sequence++, 'Z', byteArrayOf(), session)
        sendExpectAck(serial, sequence, 'B', byteArrayOf(), session)

        // The observed Evo upload transaction ends here: S/F/A/D*/Z/B,
        // with a Y acknowledgment for every outbound packet. Unlike GET,
        // PUT does not start a second server-to-client Kermit response session.
        // Waiting for one leaves the link in the wrong state and can interfere
        // with the next request or reconnect.
        drainInput(30)
    }

    private fun getRequest(url: String): ByteArray {
        val serial = requireNotNull(port) { "serial port is not open" }
        drainInput(40)
        val session = Kermit.Session()
        var sequence = 0

        val attributes =
            Kermit.fileAttribute('"', "B8") +
                Kermit.fileAttribute('1', "1") +
                Kermit.fileAttribute('@')

        sendExpectAck(serial, sequence++, 'S', Kermit.sendInit, session)
        sendExpectAck(
            serial,
            sequence++,
            'F',
            url.toByteArray(Charsets.UTF_8),
            session
        )
        sendExpectAck(serial, sequence++, 'A', attributes, session)
        sendExpectAck(
            serial,
            sequence++,
            'D',
            byteArrayOf(0x68),
            session
        )
        sendExpectAck(serial, sequence++, 'Z', byteArrayOf(), session)
        sendExpectAck(serial, sequence, 'B', byteArrayOf(), session)

        return readServerResponse(serial, session)
    }

    private fun readServerResponse(
        serial: UsbSerialPort,
        session: Kermit.Session
    ): ByteArray {
        val serverInit = readPacket(serial, session, TIMEOUT_MS)
        if (serverInit.type == 'E') {
            throw IOException("calculator error: ${errorText(serverInit.data)}")
        }
        require(serverInit.type == 'S') {
            "expected server S packet, got ${serverInit.type}"
        }
        writePacket(
            serial,
            Kermit.makePacket(
                serverInit.sequence,
                'Y',
                serverInit.data,
                session
            )
        )

        for (expected in charArrayOf('F', 'A')) {
            val packet = readPacket(serial, session, TIMEOUT_MS)
            if (packet.type == 'E') {
                throw IOException("calculator error: ${errorText(packet.data)}")
            }
            require(packet.type == expected) {
                "expected server $expected packet, got ${packet.type}"
            }
            writePacket(
                serial,
                Kermit.makePacket(
                    packet.sequence,
                    'Y',
                    byteArrayOf(),
                    session
                )
            )
        }

        val encoded = ByteArrayOutputStream()

        while (true) {
            val packet = readPacket(serial, session, TIMEOUT_MS)
            if (packet.type == 'E') {
                throw IOException("calculator error: ${errorText(packet.data)}")
            }

            writePacket(
                serial,
                Kermit.makePacket(
                    packet.sequence,
                    'Y',
                    byteArrayOf(),
                    session
                )
            )

            if (packet.type == 'Z') break
            if (packet.type == 'D') {
                encoded.write(packet.data)
            }
        }

        val end = readPacket(serial, session, TIMEOUT_MS)
        if (end.type == 'E') {
            throw IOException("calculator error: ${errorText(end.data)}")
        }
        if (end.type == 'B') {
            writePacket(
                serial,
                Kermit.makePacket(
                    end.sequence,
                    'Y',
                    byteArrayOf(),
                    session
                )
            )
        }

        return Kermit.decode(encoded.toByteArray(), session)
    }

    private fun sendExpectAck(
        serial: UsbSerialPort,
        sequence: Int,
        type: Char,
        data: ByteArray,
        session: Kermit.Session
    ): ByteArray {
        val outbound = Kermit.makePacket(
            sequence,
            type,
            data,
            session
        )

        repeat(3) { attempt ->
            writePacket(serial, outbound)
            val reply = readPacket(serial, session, TIMEOUT_MS)

            when (reply.type) {
                'Y' -> {
                    if (type == 'S') {
                        session.updateFromSendInit(reply.data)
                    }
                    return reply.data
                }
                'E' -> {
                    throw IOException(
                        "calculator error on $type: ${errorText(reply.data)}"
                    )
                }
                else -> {
                    log(
                        "packet $type/$sequence got ${reply.type}; " +
                            "retry ${attempt + 1}/3"
                    )
                    drainInput(80)
                }
            }
        }

        error("packet $type/$sequence was not acknowledged")
    }

    private fun errorText(data: ByteArray): String {
        val text = data.toString(Charsets.UTF_8).trim()
        val message = when (text) {
            "PM" -> "PARAM"
            "NP" -> "NOPORT"
            "NM" -> "NOMEM"
            "FL" -> "FLASH"
            "IN" -> "INVALID"
            "NC" -> "NOTFOUND_CLI"
            "NF" -> "NOTFOUND_SRV"
            "CN" -> "CANCEL"
            "TO" -> "TIMEOUT"
            "DI" -> "DISCONNECT"
            "UN" -> "UNSUPPORTED_REQUEST"
            "NV" -> "VERSION_TOO_NEW"
            "VE" -> "VAR_EXISTS"
            "DP" -> "INVALID_DATA_PAYLOAD"
            "BZ" -> "CALCULATOR_BUSY"
            "LB" -> "LOW_BATT"
            "WT" -> "WAIT_USER"
            "OW" -> "USER_OVERWRITE"
            "OA" -> "USER_OVERWRITE_ALL"
            "OM" -> "USER_OMIT"
            "QU" -> "USER_QUIT"
            "NR" -> "USER_NOT_IN_RECEIVE"
            "DR" -> "DEFRAG_INITIATED"
            else -> null
        }
        return when {
            text.isBlank() -> data.joinToString("") {
                "%02X".format(it.toInt() and 0xFF)
            }
            message != null -> "$text ($message)"
            else -> text
        }
    }

    private fun writePacket(
        serial: UsbSerialPort,
        bytes: ByteArray
    ) {
        serial.write(bytes, TIMEOUT_MS)
    }

    private fun readPacket(
        serial: UsbSerialPort,
        session: Kermit.Session,
        timeoutMs: Int
    ): Kermit.Packet {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs

        while (true) {
            extractRawPacket()?.let { raw ->
                try {
                    return Kermit.parsePacket(raw, session)
                } catch (t: Throwable) {
                    log("discarding malformed packet: ${t.message.orEmpty()}")
                }
            }

            val remaining =
                (deadline - SystemClock.elapsedRealtime()).toInt()
            if (remaining <= 0) {
                throw IOException("Evo serial read timed out")
            }

            val buffer = ByteArray(4096)
            try {
                val count = serial.read(
                    buffer,
                    minOf(remaining, 750)
                )
                if (count > 0) {
                    for (i in 0 until count) {
                        rx += buffer[i]
                    }
                }
            } catch (t: Throwable) {
                val text = (
                    t.javaClass.simpleName + " " +
                        t.message.orEmpty()
                    ).lowercase()
                if (
                    "timeout" !in text &&
                    t !is IOException
                ) {
                    throw t
                }
            }
        }
    }

    private fun extractRawPacket(): ByteArray? {
        if (rx.isEmpty()) return null

        val start = rx.indexOfFirst {
            (it.toInt() and 0xFF) == Kermit.SOH
        }
        if (start < 0) {
            if (rx.size > 8192) rx.clear()
            return null
        }

        if (start > 0) {
            repeat(start) { rx.removeAt(0) }
        }

        val end = rx.indexOfFirst {
            (it.toInt() and 0xFF) == Kermit.CR
        }
        if (end < 0) return null

        val packet = ByteArray(end + 1)
        for (i in 0..end) {
            packet[i] = rx[i]
        }
        repeat(end + 1) { rx.removeAt(0) }
        return packet
    }

    private fun drainInput(windowMs: Int = 120) {
        val serial = port ?: return
        val deadline = SystemClock.elapsedRealtime() + windowMs
        val buffer = ByteArray(1024)
        rx.clear()

        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                val count = serial.read(buffer, 25)
                if (count <= 0) break
            } catch (_: Throwable) {
                break
            }
        }
    }

    private inline fun <T> withSession(block: () -> T): T {
        val outermost = sessionDepth == 0
        if (outermost) open()
        sessionDepth++
        try {
            return block()
        } finally {
            sessionDepth = (sessionDepth - 1).coerceAtLeast(0)
            if (outermost) closePort()
        }
    }

    @Synchronized
    private fun closePort() {
        val serial = port
        if (serial != null) {
            try {
                serial.setDTR(false)
            } catch (_: Throwable) {
            }
            try {
                serial.setRTS(false)
            } catch (_: Throwable) {
            }
            try {
                serial.close()
            } catch (_: Throwable) {
            }
        }
        try {
            connection?.close()
        } catch (_: Throwable) {
        }
        port = null
        connection = null
        rx.clear()
        if (serial != null) log("CDC closed; USB idle")
    }

    override fun close() {
        sessionDepth = 0
        closePort()
    }
}
