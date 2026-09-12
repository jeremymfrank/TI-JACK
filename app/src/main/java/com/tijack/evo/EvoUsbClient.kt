package com.tijack.evo

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import android.os.SystemClock

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
    private val rx = ArrayList<Byte>()

    fun open() {
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
            // Force CDC/ACM for the known Evo VID/PID instead of depending on
            // generic device-class probing.
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

            // Match the ordinary CDC control-line state produced by desktop
            // serial stacks such as pyserial. Some Android USB hosts leave
            // these deasserted unless the app explicitly enables them.
            try {
                serialPort.setDTR(true)
                serialPort.setRTS(true)
                log("CDC DTR/RTS asserted")
            } catch (t: Throwable) {
                log(
                    "CDC control-line warning: " +
                    t.message.orEmpty()
                )
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

    fun listFiles(): List<EvoEntry> {
        val raw = getRequest(DIRECTORY_URL)
        log("directory payload ${raw.size} bytes")
        return EvoDirectory.parse(raw)
    }

    private fun getRequest(url: String): ByteArray {
        val serial = requireNotNull(port) { "serial port is not open" }
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

        val serverInit = readPacket(serial, session, TIMEOUT_MS)
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
                throw IOException(
                    "calculator error: " +
                    packet.data.toString(Charsets.UTF_8)
                )
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
            encoded.write(packet.data)
        }

        val end = readPacket(serial, session, TIMEOUT_MS)
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
                        "calculator error on $type: " +
                        reply.data.toString(Charsets.UTF_8)
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
                    log(
                        "discarding malformed packet: " +
                        t.message.orEmpty()
                    )
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
                // usb-serial-for-android can signal a short read timeout with
                // an exception on some Android/USB stacks. Keep waiting until
                // our overall packet deadline before treating it as failure.
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

        var start = rx.indexOfFirst {
            (it.toInt() and 0xFF) == Kermit.SOH
        }
        if (start < 0) {
            if (rx.size > 8192) rx.clear()
            return null
        }

        if (start > 0) {
            repeat(start) { rx.removeAt(0) }
            start = 0
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

    override fun close() {
        try {
            port?.close()
        } catch (_: Throwable) {
        }
        try {
            connection?.close()
        } catch (_: Throwable) {
        }
        port = null
        connection = null
        rx.clear()
    }
}
