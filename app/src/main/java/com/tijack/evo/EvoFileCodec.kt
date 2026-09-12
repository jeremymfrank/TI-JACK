package com.tijack.evo

internal data class EvoFileInfo(
    val type: Int,
    val tokenName: ByteArray?,
    val displayName: String?
)

internal object EvoFileCodec {
    private val extensions = mapOf(
        0 to "8xn2",
        1 to "8xl2",
        2 to "8xp2",
        3 to "8xd2",
        4 to "8ci2",
        5 to "8ca2",
        6 to "8xm2",
        7 to "8xy2",
        8 to "8xv2",
        10 to "8xs2",
        12 to "8xw2",
        13 to "8xz2",
        14 to "8xt2",
        15 to "8xpy2",
        18 to "8mp2"
    )

    fun extensionForType(type: Int): String =
        extensions[type] ?: "bin"

    fun outputFileName(entry: EvoEntry): String =
        "${safeFilename(entry.name)}.${extensionForType(entry.type)}"

    fun safeFilename(name: String): String {
        val cleaned = buildString {
            for (c in name) {
                append(
                    if (c.isLetterOrDigit() || c == '.' || c == '_' || c == '-') {
                        c
                    } else {
                        '_'
                    }
                )
            }
        }.trim('.')
        return cleaned.ifBlank { "var" }
    }

    fun inspect(data: ByteArray): EvoFileInfo {
        require(data.size >= 3) { "file is too short to be an Evo variable" }

        val body = data.copyOfRange(0, data.size - 2)
        val supplied =
            ((data[data.size - 2].toInt() and 0xFF) shl 8) or
            (data[data.size - 1].toInt() and 0xFF)
        val calculated = checksum(body)
        require(supplied == calculated) {
            "Evo file checksum is invalid"
        }

        val root = CborLite(body).decode() as? Map<*, *>
            ?: error("Evo file is not a CBOR map")
        val metadata = root["metaData"] as? Map<*, *>
            ?: error("Evo file has no metadata")
        val type = number(metadata["type"]).toInt()
        require(type in extensions.keys) {
            "Evo variable type $type is not supported yet"
        }

        val tokenName = metadata["name"] as? ByteArray
        val displayName = tokenName?.let { decodeCustomName(it) }
        return EvoFileInfo(type, tokenName, displayName)
    }

    fun checksum(body: ByteArray): Int {
        if (body.size < 3) return 0
        val adjusted = body.size - 3
        var wordCount = adjusted shr 1
        if ((adjusted and 1) != 0 && wordCount > 0) {
            wordCount--
        }

        var checksum = 0
        for (i in 0 until wordCount) {
            val word =
                (body[i * 2].toInt() and 0xFF) or
                ((body[i * 2 + 1].toInt() and 0xFF) shl 8)
            checksum = checksum xor word
        }
        return checksum and 0xFFFF
    }

    fun appendChecksum(body: ByteArray): ByteArray {
        val checksum = checksum(body)
        return body + byteArrayOf(
            (checksum ushr 8).toByte(),
            checksum.toByte()
        )
    }

    fun urlEncodeTokenName(nameBytes: ByteArray): String {
        val out = StringBuilder()
        var i = 0
        while (i + 1 < nameBytes.size) {
            val word =
                (nameBytes[i].toInt() and 0xFF) or
                ((nameBytes[i + 1].toInt() and 0xFF) shl 8)
            if (word == 0) break
            val bytes = word.toChar().toString().toByteArray(Charsets.UTF_8)
            for (b in bytes) {
                out.append('%')
                out.append("%02X".format(b.toInt() and 0xFF))
            }
            i += 2
        }
        return out.toString()
    }

    fun sameIdentity(info: EvoFileInfo, entry: EvoEntry): Boolean {
        val name = info.tokenName ?: return false
        return info.type == entry.type && name.contentEquals(entry.tokenName)
    }

    private fun decodeCustomName(bytes: ByteArray): String? {
        val chars = StringBuilder()
        var i = 0
        while (i + 1 < bytes.size) {
            val word =
                (bytes[i].toInt() and 0xFF) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8)
            if (word == 0) break
            val char = when {
                word in 0xE800..0xE819 ->
                    ('A'.code + word - 0xE800).toChar()
                word in 0x0061..0x007A -> word.toChar()
                word in 0x0041..0x005A -> word.toChar()
                word in 0x0030..0x0039 -> word.toChar()
                word in 0xE401..0xE40A ->
                    ('0'.code + word - 0xE401).toChar()
                word == 0x005F || word == 0xE400 -> '_'
                word == 0xE81A -> 'θ'
                else -> return null
            }
            chars.append(char)
            i += 2
        }
        return chars.toString().takeIf { it.isNotEmpty() }
    }

    private fun number(value: Any?): Long =
        when (value) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Int -> value.toLong()
            is Long -> value
            is Number -> value.toLong()
            else -> error("missing numeric Evo metadata field")
        }
}
