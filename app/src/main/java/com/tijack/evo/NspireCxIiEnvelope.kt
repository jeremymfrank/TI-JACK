package com.tijack.evo

/**
 * TI-Nspire CX II outer USB envelope.
 *
 * Public protocol research shows CX II wraps the ordinary Nspire/NavNet byte
 * stream in a small big-endian message layer. This codec is deliberately
 * transport-only: it does not perform the device handshake or filesystem
 * mutations.
 */
internal object NspireCxIiEnvelope {
    const val HEADER_SIZE = 12

    const val ADDRESS_BROADCAST = 0xFF
    const val ADDRESS_HOST = 0xFE
    const val ADDRESS_CALCULATOR = 0x01

    const val SERVICE_ADDRESS = 0x01
    const val SERVICE_TIME = 0x02
    const val SERVICE_ECHO = 0x03
    const val SERVICE_STREAM = 0x04
    const val SERVICE_TRANSMIT = 0x05
    const val SERVICE_LOOPBACK = 0x06
    const val SERVICE_STATS = 0x07
    const val SERVICE_UNKNOWN = 0x08
    const val ACK_FLAG = 0x80

    data class Frame(
        val misc: Int,
        val service: Int,
        val source: Int,
        val destination: Int,
        val unknown: Int,
        val requestAck: Int,
        val sequence: Int,
        val payload: ByteArray
    ) {
        val isAck: Boolean
            get() = service and ACK_FLAG != 0

        val baseService: Int
            get() = service and ACK_FLAG.inv() and 0xFF
    }

    fun encode(
        service: Int,
        sequence: Int,
        payload: ByteArray = byteArrayOf(),
        source: Int = ADDRESS_HOST,
        destination: Int = ADDRESS_CALCULATOR,
        misc: Int = 0,
        unknown: Int = 0,
        requestAck: Boolean = true
    ): ByteArray {
        val length = HEADER_SIZE + payload.size
        require(length <= 0xFFFF) { "CX II envelope exceeds 16-bit length field" }
        require(sequence in 0..0xFFFF) { "sequence must fit in 16 bits" }

        val packet = ByteArray(length)
        packet[0] = misc.toByte()
        packet[1] = service.toByte()
        packet[2] = source.toByte()
        packet[3] = destination.toByte()
        packet[4] = unknown.toByte()
        packet[5] = if (requestAck) 0x01 else 0x00
        putU16be(packet, 6, length)
        putU16be(packet, 8, sequence)
        putU16be(packet, 10, 0)
        payload.copyInto(packet, HEADER_SIZE)

        putU16be(packet, 10, checksumFor(packet))
        check(isChecksumValid(packet))
        return packet
    }

    fun decode(packet: ByteArray): Frame {
        require(packet.size >= HEADER_SIZE) { "CX II envelope is shorter than its header" }

        val declared = u16be(packet, 6)
        require(declared == packet.size) {
            "CX II envelope length mismatch: header=$declared actual=${packet.size}"
        }
        require(isChecksumValid(packet)) { "CX II envelope checksum mismatch" }

        return Frame(
            misc = packet[0].toInt() and 0xFF,
            service = packet[1].toInt() and 0xFF,
            source = packet[2].toInt() and 0xFF,
            destination = packet[3].toInt() and 0xFF,
            unknown = packet[4].toInt() and 0xFF,
            requestAck = packet[5].toInt() and 0xFF,
            sequence = u16be(packet, 8),
            payload = packet.copyOfRange(HEADER_SIZE, packet.size)
        )
    }

    fun ackFor(frame: Frame): ByteArray =
        encode(
            service = frame.baseService or ACK_FLAG,
            sequence = frame.sequence,
            source = frame.destination,
            destination = frame.source,
            misc = frame.misc,
            unknown = frame.unknown,
            requestAck = false
        )

    fun checksumFor(packetWithZeroChecksum: ByteArray): Int =
        foldedWordSum(packetWithZeroChecksum).inv() and 0xFFFF

    fun isChecksumValid(packet: ByteArray): Boolean =
        foldedWordSum(packet) == 0xFFFF

    private fun foldedWordSum(data: ByteArray): Int {
        var sum = 0L
        var index = 0
        while (index + 1 < data.size) {
            sum += ((data[index].toInt() and 0xFF) shl 8) or
                (data[index + 1].toInt() and 0xFF)
            index += 2
        }
        if (index < data.size) {
            sum += (data[index].toInt() and 0xFF) shl 8
        }
        while (sum ushr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.toInt() and 0xFFFF
    }

    private fun putU16be(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 1] = (value and 0xFF).toByte()
    }

    private fun u16be(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or
            (data[offset + 1].toInt() and 0xFF)
}
