package com.tijack.evo

internal class CborLite(private val bytes: ByteArray) {
    private var position = 0

    fun decode(): Any? {
        val value = readItem()
        require(position == bytes.size) {
            "trailing CBOR bytes: ${bytes.size - position}"
        }
        return value
    }

    private fun readItem(): Any? {
        require(position < bytes.size) { "unexpected end of CBOR" }

        val initial = readU8()
        val major = initial ushr 5
        val additional = initial and 0x1F

        return when (major) {
            0 -> readLength(additional)
            1 -> -1L - requireLength(readLength(additional))
            2 -> readByteString(additional)
            3 -> readTextString(additional)
            4 -> readArray(additional)
            5 -> readMap(additional)
            7 -> when (additional) {
                20 -> false
                21 -> true
                22, 23 -> null
                31 -> Break
                else -> error(
                    "unsupported CBOR simple value 0x${initial.toString(16)}"
                )
            }
            else -> error(
                "unsupported CBOR item 0x${initial.toString(16)}"
            )
        }
    }

    private fun readByteString(additional: Int): ByteArray {
        val length = readLength(additional)
        if (length != null) {
            return readBytes(length.toInt())
        }

        val chunks = ArrayList<ByteArray>()
        while (!nextIsBreak()) {
            val chunk = readItem()
            require(chunk is ByteArray) {
                "indefinite byte string contained non-byte chunk"
            }
            chunks += chunk
        }
        position++ // break
        val total = chunks.sumOf { it.size }
        val output = ByteArray(total)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(output, offset)
            offset += chunk.size
        }
        return output
    }

    private fun readTextString(additional: Int): String {
        val length = readLength(additional)
        if (length != null) {
            return readBytes(length.toInt()).toString(Charsets.UTF_8)
        }

        val out = StringBuilder()
        while (!nextIsBreak()) {
            val chunk = readItem()
            require(chunk is String) {
                "indefinite text string contained non-text chunk"
            }
            out.append(chunk)
        }
        position++
        return out.toString()
    }

    private fun readArray(additional: Int): List<Any?> {
        val length = readLength(additional)
        val result = ArrayList<Any?>()

        if (length == null) {
            while (!nextIsBreak()) {
                val value = readItem()
                if (value === Break) break
                result += value
            }
            if (position < bytes.size && nextIsBreak()) position++
            return result
        }

        repeat(length.toInt()) {
            result += readItem()
        }
        return result
    }

    private fun readMap(additional: Int): Map<Any?, Any?> {
        val length = readLength(additional)
        val result = LinkedHashMap<Any?, Any?>()

        if (length == null) {
            while (!nextIsBreak()) {
                val key = readItem()
                if (key === Break) break
                val value = readItem()
                result[key] = value
            }
            if (position < bytes.size && nextIsBreak()) position++
            return result
        }

        repeat(length.toInt()) {
            val key = readItem()
            val value = readItem()
            result[key] = value
        }
        return result
    }

    private fun readLength(additional: Int): Long? =
        when {
            additional < 24 -> additional.toLong()
            additional == 24 -> readU8().toLong()
            additional == 25 -> readUInt(2)
            additional == 26 -> readUInt(4)
            additional == 27 -> readUInt(8)
            additional == 31 -> null
            else -> error("unsupported CBOR additional info $additional")
        }

    private fun requireLength(value: Long?): Long =
        value ?: error("indefinite integer is invalid")

    private fun readUInt(count: Int): Long {
        require(position + count <= bytes.size) {
            "unexpected end of CBOR integer"
        }
        var value = 0L
        repeat(count) {
            value = (value shl 8) or readU8().toLong()
        }
        return value
    }

    private fun readBytes(count: Int): ByteArray {
        require(position + count <= bytes.size) {
            "unexpected end of CBOR byte string"
        }
        val result = bytes.copyOfRange(position, position + count)
        position += count
        return result
    }

    private fun nextIsBreak(): Boolean =
        position < bytes.size &&
        (bytes[position].toInt() and 0xFF) == 0xFF

    private fun readU8(): Int =
        (bytes[position++].toInt() and 0xFF)

    private object Break
}
