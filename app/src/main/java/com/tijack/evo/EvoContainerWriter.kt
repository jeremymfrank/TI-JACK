package com.tijack.evo

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** Minimal CBOR writer for the Evo variable containers TI-JACK creates. */
internal class EvoContainerWriter {
    private val out = ByteArrayOutputStream()

    fun beginMap() {
        out.write(0xBF)
    }

    fun end() {
        out.write(0xFF)
    }

    fun text(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeLength(3, bytes.size.toLong())
        out.write(bytes)
    }

    fun bytes(value: ByteArray) {
        writeLength(2, value.size.toLong())
        out.write(value)
    }

    fun uint(value: Long) {
        require(value >= 0) { "CBOR unsigned integer cannot be negative" }
        writeLength(0, value)
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun writeLength(major: Int, value: Long) {
        val prefix = major shl 5
        when {
            value < 24 -> out.write(prefix or value.toInt())
            value <= 0xFF -> {
                out.write(prefix or 24)
                out.write(value.toInt())
            }
            value <= 0xFFFF -> {
                out.write(prefix or 25)
                out.write((value ushr 8).toInt())
                out.write(value.toInt())
            }
            value <= 0xFFFF_FFFFL -> {
                out.write(prefix or 26)
                for (shift in intArrayOf(24, 16, 8, 0)) {
                    out.write((value ushr shift).toInt())
                }
            }
            else -> {
                out.write(prefix or 27)
                for (shift in intArrayOf(56, 48, 40, 32, 24, 16, 8, 0)) {
                    out.write((value ushr shift).toInt())
                }
            }
        }
    }
}
