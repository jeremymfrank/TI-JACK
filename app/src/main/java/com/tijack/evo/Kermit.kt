package com.tijack.evo

import java.io.ByteArrayOutputStream

internal object Kermit {
    const val SOH = 0x01
    const val CR = 0x0D
    const val QCTL = 0x23
    const val REPT = 0x7E

    val sendInit: ByteArray =
        hex("7e3020402d2359317e2e22354d")

    data class Session(
        var checkType: Int = 1,
        var controlQuote: Int = QCTL,
        var binaryQuote: Int? = null,
        var repeatQuote: Int = REPT,
        var maxPacketLength: Int = 2040
    ) {
        val dataChunkSize: Int
            get() = maxOf(1, minOf(2000, maxPacketLength - checkType - 8))

        fun updateFromSendInit(data: ByteArray) {
            if (data.size > 5 && u(data[5]) != 0x20) {
                controlQuote = u(data[5])
            }
            if (data.size > 6) {
                val value = u(data[6])
                if (value != 'Y'.code && value != 'N'.code && value != 0x20) {
                    binaryQuote = value
                }
            }
            if (data.size > 7) {
                val c = u(data[7]).toChar()
                if (c in '1'..'3') {
                    checkType = c.digitToInt()
                }
            }
            if (data.size > 8 && u(data[8]) != 0x20) {
                repeatQuote = u(data[8])
            }
            if (data.size > 12) {
                val maxLong =
                    unchar(u(data[11])) * 95 + unchar(u(data[12]))
                if (maxLong >= 60) {
                    maxPacketLength = minOf(maxLong, 2040)
                }
            }
        }
    }

    data class Packet(
        val sequence: Int,
        val type: Char,
        val data: ByteArray
    )

    fun fileAttribute(tag: Char, value: String = ""): ByteArray {
        val valueBytes = value.toByteArray(Charsets.US_ASCII)
        require(valueBytes.size <= 94)
        return byteArrayOf(
            tag.code.toByte(),
            tochar(valueBytes.size).toByte()
        ) + valueBytes
    }

    fun encode(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var index = 0
        while (index < data.size) {
            var run = 1
            while (
                index + run < data.size &&
                data[index + run] == data[index] &&
                run < 94
            ) {
                run++
            }

            val encoded = encodeByte(u(data[index]))
            if (run >= 3) {
                out.write(REPT)
                out.write(tochar(run))
                out.write(encoded)
            } else {
                repeat(run) {
                    out.write(encoded)
                }
            }
            index += run
        }
        return out.toByteArray()
    }

    fun splitEncoded(
        wire: ByteArray,
        chunkSize: Int
    ): List<ByteArray> {
        require(chunkSize > 0)
        if (wire.isEmpty()) return emptyList()

        val chunks = ArrayList<ByteArray>()
        var pos = 0
        var chunkStart = 0

        while (pos < wire.size) {
            when {
                u(wire[pos]) == REPT && pos + 2 < wire.size -> {
                    pos += 2
                    if (u(wire[pos]) == QCTL && pos + 1 < wire.size) {
                        pos += 2
                    } else {
                        pos += 1
                    }
                }
                u(wire[pos]) == QCTL && pos + 1 < wire.size -> {
                    pos += 2
                }
                else -> pos += 1
            }

            if (pos - chunkStart >= chunkSize) {
                chunks += wire.copyOfRange(chunkStart, pos)
                chunkStart = pos
            }
        }

        if (chunkStart < wire.size) {
            chunks += wire.copyOfRange(chunkStart, wire.size)
        }
        return chunks
    }

    fun makePacket(
        sequence: Int,
        type: Char,
        data: ByteArray = byteArrayOf(),
        session: Session? = null,
        forceCheckType: Int? = null
    ): ByteArray {
        val checkType = forceCheckType ?: session?.checkType ?: 1
        val seq = tochar(sequence and 63)
        val packetType = type.code
        val n = data.size + 2 + checkType

        if (n <= 94) {
            val body = byteArrayOf(
                tochar(n).toByte(),
                seq.toByte(),
                packetType.toByte()
            ) + data
            return byteArrayOf(SOH.toByte()) +
                body +
                blockCheck(body, checkType) +
                byteArrayOf(CR.toByte())
        }

        val dataLength = data.size + checkType
        val header = byteArrayOf(
            tochar(0).toByte(),
            seq.toByte(),
            packetType.toByte(),
            tochar(dataLength / 95).toByte(),
            tochar(dataLength % 95).toByte()
        )
        val body =
            header +
            byteArrayOf(checksum1(header).toByte()) +
            data

        return byteArrayOf(SOH.toByte()) +
            body +
            blockCheck(body, checkType) +
            byteArrayOf(CR.toByte())
    }

    fun parsePacket(
        raw: ByteArray,
        session: Session? = null,
        forceCheckType: Int? = null
    ): Packet {
        val checkType = forceCheckType ?: session?.checkType ?: 1
        require(raw.size >= 5) { "short Kermit packet" }
        require(u(raw.first()) == SOH) { "packet missing SOH" }
        require(u(raw.last()) == CR) { "packet missing CR" }

        val body = raw.copyOfRange(1, raw.size - 1)
        val extended = u(body[0]) == tochar(0)

        val dataStart: Int
        if (extended) {
            require(body.size >= 6 + checkType) {
                "short extended Kermit packet"
            }
            val header = body.copyOfRange(0, 5)
            require(u(body[5]) == checksum1(header)) {
                "bad extended Kermit header checksum"
            }
            dataStart = 6
        } else {
            require(body.size >= 3 + checkType) {
                "short Kermit packet body"
            }
            dataStart = 3
        }

        val checked = body.copyOfRange(0, body.size - checkType)
        val suppliedCheck =
            body.copyOfRange(body.size - checkType, body.size)
        val expectedCheck = blockCheck(checked, checkType)
        require(suppliedCheck.contentEquals(expectedCheck)) {
            "bad Kermit block check"
        }

        val dataEnd = body.size - checkType
        val data =
            if (dataEnd > dataStart) {
                body.copyOfRange(dataStart, dataEnd)
            } else {
                byteArrayOf()
            }

        return Packet(
            sequence = unchar(u(body[1])),
            type = u(body[2]).toChar(),
            data = data
        )
    }

    fun decode(data: ByteArray, session: Session): ByteArray {
        val out = ByteArrayOutputStream()
        var index = 0

        while (index < data.size) {
            var repeat = 1
            if (
                u(data[index]) == session.repeatQuote &&
                index + 2 < data.size
            ) {
                repeat = unchar(u(data[index + 1]))
                index += 2
            }

            var highBit = 0
            val binary = session.binaryQuote
            if (
                binary != null &&
                index < data.size &&
                u(data[index]) == binary
            ) {
                highBit = 0x80
                index++
            }

            if (index >= data.size) break

            val current = u(data[index])
            val decoded: Int
            if (
                current == session.controlQuote &&
                index + 1 < data.size
            ) {
                decoded = uncontrol(u(data[index + 1]))
                index += 2
            } else {
                decoded = current
                index++
            }

            repeat(repeat) {
                out.write(decoded or highBit)
            }
        }

        return out.toByteArray()
    }

    private fun encodeByte(value: Int): ByteArray {
        val low = value and 0x7F
        return when {
            low < 0x20 || low == 0x7F ->
                byteArrayOf(QCTL.toByte(), (value xor 0x40).toByte())
            value == QCTL ->
                byteArrayOf(QCTL.toByte(), QCTL.toByte())
            value == REPT ->
                byteArrayOf(QCTL.toByte(), REPT.toByte())
            else -> byteArrayOf(value.toByte())
        }
    }

    private fun blockCheck(data: ByteArray, type: Int): ByteArray =
        when (type) {
            1 -> byteArrayOf(checksum1(data).toByte())
            2 -> {
                val sum = data.sumOf { u(it) } and 0xFFFF
                byteArrayOf(
                    tochar((sum shr 6) and 0x3F).toByte(),
                    tochar(sum and 0x3F).toByte()
                )
            }
            3 -> {
                val crc = crc16Kermit(data)
                byteArrayOf(
                    tochar((crc shr 12) and 0x0F).toByte(),
                    tochar((crc shr 6) and 0x3F).toByte(),
                    tochar(crc and 0x3F).toByte()
                )
            }
            else -> error("unsupported Kermit check type $type")
        }

    private fun checksum1(data: ByteArray): Int {
        val sum = data.sumOf { u(it) } and 0xFFFF
        return tochar((sum + ((sum shr 6) and 3)) and 0x3F)
    }

    private fun crc16Kermit(data: ByteArray): Int {
        var crc = 0
        for (value in data) {
            val b = u(value)
            var q = (crc xor b) and 0x0F
            crc = (crc shr 4) xor (q * 0x1081)
            q = (crc xor (b shr 4)) and 0x0F
            crc = (crc shr 4) xor (q * 0x1081)
        }
        return crc and 0xFFFF
    }

    private fun uncontrol(value: Int): Int =
        if (
            (value and 0x7F) == 0x3F ||
            (value and 0x60) == 0x40
        ) {
            value xor 0x40
        } else {
            value
        }

    private fun tochar(value: Int): Int = value + 0x20
    private fun unchar(value: Int): Int = value - 0x20
    private fun u(value: Byte): Int = value.toInt() and 0xFF

    private fun hex(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { i ->
            value.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
