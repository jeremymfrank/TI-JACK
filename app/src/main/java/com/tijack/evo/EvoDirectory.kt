package com.tijack.evo

internal data class EvoEntry(
    val name: String,
    val type: Int,
    val size: Long,
    val archived: Boolean,
    val tokenName: ByteArray
)

internal object EvoDirectory {
    fun parse(payload: ByteArray): List<EvoEntry> {
        val root = CborLite(payload).decode() as? Map<*, *>
            ?: error("directory response was not a CBOR map")

        val data = root["data"] as? List<*>
            ?: return emptyList()

        return data.mapNotNull { raw ->
            val item = raw as? Map<*, *> ?: return@mapNotNull null
            val type = number(item["type"]).toInt()
            val size = number(item["size"])
            val archived = item["mem"] as? Boolean ?: false
            val tokenName = item["tokName"] as? ByteArray ?: byteArrayOf()
            val name = displayName(item, type)
            EvoEntry(name, type, size, archived, tokenName)
        }
    }

    private fun displayName(
        item: Map<*, *>,
        type: Int
    ): String {
        val tokenName = item["tokName"] as? ByteArray

        if (tokenName != null) {
            val words = tokenWords(tokenName)

            if (type in setOf(2, 8, 9, 15, 18)) {
                decodeCustom(tokenName)?.let { return it }
            }

            if (
                type == 1 &&
                words.firstOrNull() == 0xE836
            ) {
                decodeCustom(
                    tokenName.copyOfRange(
                        minOf(2, tokenName.size),
                        tokenName.size
                    )
                )?.let { return it }
            }

            if (words.size == 1) {
                commonName(type, words[0])?.let { return it }
            }
        }

        return when (val display = item["dispName"]) {
            is String -> display
            is ByteArray -> display.toString(Charsets.UTF_8)
            else -> {
                if (tokenName == null) {
                    "UNKNOWN"
                } else {
                    "VAR_" + tokenName.joinToString("") {
                        "%02X".format(it.toInt() and 0xFF)
                    }
                }
            }
        }
    }

    private fun commonName(type: Int, word: Int): String? =
        when (type) {
            0 -> when {
                word in 0xE800..0xE819 ->
                    ('A'.code + word - 0xE800).toChar().toString()
                word == 0xE81A -> "theta"
                else -> null
            }
            1 -> if (word in 0xE830..0xE835) {
                "L${word - 0xE830 + 1}"
            } else null
            3 -> when {
                word == 0xE899 -> "GDB0"
                word in 0xE890..0xE898 ->
                    "GDB${word - 0xE890 + 1}"
                else -> null
            }
            4 -> when {
                word == 0xE889 -> "Pic0"
                word in 0xE880..0xE888 ->
                    "Pic${word - 0xE880 + 1}"
                else -> null
            }
            5 -> when {
                word == 0xE8B9 -> "Image0"
                word in 0xE8B0..0xE8B8 ->
                    "Image${word - 0xE8B0 + 1}"
                else -> null
            }
            6 -> if (word in 0xE820..0xE829) {
                ('A'.code + word - 0xE820).toChar().toString()
            } else null
            7 -> when {
                word == 0xE849 -> "Y0"
                word in 0xE840..0xE848 ->
                    "Y${word - 0xE840 + 1}"
                word in 0xE850..0xE85B -> {
                    val delta = word - 0xE850
                    val index = delta / 2 + 1
                    if (delta % 2 == 0) "X${index}T"
                    else "Y${index}T"
                }
                word in 0xE860..0xE865 ->
                    "r${word - 0xE860 + 1}"
                word in 0xE870..0xE872 ->
                    ('u'.code + word - 0xE870).toChar().toString()
                else -> null
            }
            10 -> when {
                word == 0xE8A9 -> "Str0"
                word in 0xE8A0..0xE8A8 ->
                    "Str${word - 0xE8A0 + 1}"
                else -> null
            }
            12 -> if (word == 0xE8BA) "Window" else null
            13 -> if (word == 0xE8BB) "RclWindw" else null
            14 -> if (word == 0xE8BC) "TblSet" else null
            else -> null
        }

    private fun decodeCustom(bytes: ByteArray): String? {
        val chars = StringBuilder()

        for (word in tokenWords(bytes)) {
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
        }

        return chars.toString().takeIf { it.isNotEmpty() }
    }

    private fun tokenWords(bytes: ByteArray): List<Int> {
        val result = ArrayList<Int>()
        var i = 0
        while (i + 1 < bytes.size) {
            val word =
                (bytes[i].toInt() and 0xFF) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8)
            if (word == 0) break
            result += word
            i += 2
        }
        return result
    }

    private fun number(value: Any?): Long =
        when (value) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Int -> value.toLong()
            is Long -> value
            is Number -> value.toLong()
            else -> 0L
        }
}
