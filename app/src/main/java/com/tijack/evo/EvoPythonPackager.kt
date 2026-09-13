package com.tijack.evo

import java.io.ByteArrayOutputStream

/** Builds native TI-84 Evo Python program containers (.8xpy2) from ASCII source. */
internal object EvoPythonPackager {
    private const val PYTHON_TYPE = 15

    fun build(name: String, source: String): ByteArray {
        require(Regex("^[A-Z][A-Z0-9_]{0,7}$").matches(name)) {
            "Python program name '$name' is not a valid Evo name"
        }
        require(source.all { it.code in 0..0x7F }) {
            "Evo Python source must be ASCII"
        }

        val sourceBytes = source.toByteArray(Charsets.US_ASCII)
        require(sourceBytes.size <= 0xFFFF) { "Evo Python source is too large" }
        val payload = pythonPayload(name, sourceBytes)

        val writer = EvoContainerWriter()
        writer.beginMap()
        writer.text("metaData")
        writer.beginMap()
        writer.text("type")
        writer.uint(PYTHON_TYPE.toLong())
        writer.text("version")
        writer.uint(1)
        writer.text("flags")
        writer.uint(0)
        writer.text("name")
        writer.bytes(tokenName(name))
        writer.end()
        writer.text("version")
        writer.uint(1)
        writer.text("size")
        writer.uint(payload.size.toLong())
        writer.text("data")
        writer.bytes(payload)
        writer.end()

        val file = EvoFileCodec.appendChecksum(writer.toByteArray())
        val info = EvoFileCodec.inspect(file)
        require(info.type == PYTHON_TYPE && info.displayName == name) {
            "generated Python program did not validate"
        }
        return file
    }

    private fun pythonPayload(name: String, source: ByteArray): ByteArray {
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        val payloadSize = 12 + nameBytes.size + 1 + 4 + source.size + 1
        val out = ByteArrayOutputStream(payloadSize)
        writeU32(out, 0x113)
        writeU32(out, payloadSize)
        writeU32(out, nameBytes.size)
        out.write(nameBytes)
        out.write(0)
        writeU16(out, source.size)
        out.write(0)
        out.write(2)
        out.write(source)
        out.write(0)
        return out.toByteArray()
    }

    private fun tokenName(name: String): ByteArray {
        val out = ByteArrayOutputStream((name.length + 1) * 2)
        for (char in name) {
            val token = when (char) {
                in 'A'..'Z' -> 0xE800 + (char - 'A')
                in '0'..'9' -> 0xE401 + (char - '0')
                '_' -> 0xE400
                else -> error("unsupported Evo Python name character")
            }
            writeU16(out, token)
        }
        writeU16(out, 0)
        return out.toByteArray()
    }

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        require(value in 0..0xFFFF)
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }

    private fun writeU32(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }
}
