package com.tijack.evo

/**
 * Independently written TI-Nspire protocol primitives derived from public
 * packet documentation. These helpers deliberately stop below the USB session
 * layer: they do not claim a working CX II handshake until hardware confirms
 * endpoint selection, reset/address assignment, ACK sequencing, and timeouts.
 */
internal object NspireProtocol {
    const val SERVICE_DEVICE_INFO = 0x4020
    const val SERVICE_FILE_MANAGEMENT = 0x4060
    const val SERVICE_ADDRESS = 0x4003
    const val SERVICE_DISCONNECT = 0x40DE

    const val CMD_PUT_FILE = 0x03
    const val CMD_READY_FILE = 0x04
    const val CMD_FILE_CONTENTS = 0x05
    const val CMD_GET_FILE = 0x07
    const val CMD_DELETE_FILE = 0x09
    const val CMD_CREATE_FOLDER = 0x0A
    const val CMD_DELETE_FOLDER = 0x0B
    const val CMD_COPY = 0x0C
    const val CMD_DIR_START = 0x0D
    const val CMD_DIR_NEXT = 0x0E
    const val CMD_DIR_STOP = 0x0F
    const val CMD_DIR_ENTRY = 0x10
    const val CMD_ATTRIBUTES = 0x20
    const val CMD_RENAME = 0x21

    data class DirectoryEntry(
        val name: String,
        val size: Long,
        val dateRaw: Long,
        val directory: Boolean
    )

    /** CRC-like 16-bit checksum documented for Nspire packet data. */
    fun dataChecksum(data: ByteArray): Int {
        var acc = 0
        for (raw in data) {
            val byte = raw.toInt() and 0xFF
            val first = (byte shl 8) or (acc ushr 8)
            acc = acc and 0xFF
            val second = ((((acc and 0xF) shl 4) xor acc) shl 8)
            val third = second ushr 5
            acc = third ushr 7
            acc = (acc xor first xor second xor third) and 0xFFFF
        }
        return acc
    }

    fun deviceInfoSerialAndMemory(): ByteArray = byteArrayOf(0x01)
    fun deviceInfoName(): ByteArray = byteArrayOf(0x02)
    fun deviceInfoExtensions(): ByteArray = byteArrayOf(0x03)

    fun dirStart(path: String): ByteArray =
        byteArrayOf(CMD_DIR_START.toByte()) + paddedCString(path, 8)

    fun dirNext(): ByteArray = byteArrayOf(CMD_DIR_NEXT.toByte())
    fun dirStop(): ByteArray = byteArrayOf(CMD_DIR_STOP.toByte())

    fun attributes(path: String): ByteArray =
        byteArrayOf(CMD_ATTRIBUTES.toByte(), 0x01) + paddedCString(path, 8)

    fun putFile(path: String, size: Long): ByteArray {
        require(size in 0..0xFFFF_FFFFL) { "Nspire file size exceeds 32-bit protocol field" }
        require(path.lowercase().endsWith(".tns")) { "Nspire document path must end in .tns" }
        return byteArrayOf(CMD_PUT_FILE.toByte(), 0x01) +
            paddedCString(path, 8) +
            u32be(size)
    }

    fun fileChunk(data: ByteArray): ByteArray {
        require(data.size <= 253) { "Nspire file-content chunks are limited to 253 bytes" }
        return byteArrayOf(CMD_FILE_CONTENTS.toByte()) + data
    }

    fun getFile(path: String): ByteArray {
        require(path.lowercase().endsWith(".tns")) { "Nspire document path must end in .tns" }
        return byteArrayOf(CMD_GET_FILE.toByte(), 0x01) + paddedCString(path, 8)
    }

    fun readyForFile(): ByteArray = byteArrayOf(CMD_READY_FILE.toByte())
    fun success(): ByteArray = byteArrayOf(0xFF.toByte(), 0x00)

    fun deleteFile(path: String): ByteArray =
        byteArrayOf(CMD_DELETE_FILE.toByte(), 0x01) + paddedCString(path, 8)

    fun createFolder(path: String): ByteArray =
        byteArrayOf(CMD_CREATE_FOLDER.toByte(), 0x03) + paddedCString(path, 8)

    fun deleteFolder(path: String): ByteArray =
        byteArrayOf(CMD_DELETE_FOLDER.toByte(), 0x03) + paddedCString(path, 8)

    fun parseDirectoryEntry(data: ByteArray): DirectoryEntry? {
        if (data.size < 14 || (data[0].toInt() and 0xFF) != CMD_DIR_ENTRY) return null
        var zero = 3
        while (zero < data.size && data[zero].toInt() != 0) zero++
        if (zero >= data.size || zero + 10 > data.size) return null

        val name = data.copyOfRange(3, zero).toString(Charsets.UTF_8)
        var p = zero + 1
        val size = readU32be(data, p)
        p += 4
        val date = readU32be(data, p)
        p += 4
        val directory = (data[p].toInt() and 0xFF) == 1

        return DirectoryEntry(
            name = name,
            size = size,
            dateRaw = date,
            directory = directory
        )
    }

    fun isSuccess(data: ByteArray): Boolean =
        data.size >= 2 &&
            (data[0].toInt() and 0xFF) == 0xFF &&
            (data[1].toInt() and 0xFF) == 0

    fun failureCode(data: ByteArray): Int? =
        if (data.size >= 2 && (data[0].toInt() and 0xFF) == 0xFF) {
            data[1].toInt() and 0xFF
        } else {
            null
        }

    private fun paddedCString(value: String, minBytesBeforeNull: Int): ByteArray {
        val encoded = value.toByteArray(Charsets.UTF_8)
        require(encoded.none { it.toInt() == 0 }) { "path contains NUL" }
        val bodySize = maxOf(encoded.size, minBytesBeforeNull)
        return ByteArray(bodySize + 1).also {
            encoded.copyInto(it)
            it[bodySize] = 0
        }
    }

    private fun u32be(value: Long): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte()
    )

    private fun readU32be(data: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset + 4 <= data.size)
        return ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)
    }
}
