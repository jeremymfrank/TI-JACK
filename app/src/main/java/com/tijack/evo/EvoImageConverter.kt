package com.tijack.evo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Converts explicitly named Android images into TI-84 Evo graph-background vars.
 *
 * Background imports are intentionally explicit now: name the source Image1,
 * Image2, ... Image7 (extension may be png/jpg/jpeg/webp). Other still-image
 * names are handled by ViewerMediaConverter as named IM8C AppVars.
 */
internal object EvoImageConverter {
    const val WIDTH = 160
    const val HEIGHT = 105
    private const val DATA_MARKER = 0x16

    private val supportedExtensions = setOf("png", "jpg", "jpeg", "webp")

    fun isExplicitBackgroundFilename(name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension !in supportedExtensions) return false
        val base = name.substringBeforeLast('.')
        return Regex("(?i)^(?:image|img)[ _-]?([1-7])$").matches(base)
    }

    fun canConvertFilename(name: String): Boolean = isExplicitBackgroundFilename(name)

    fun preferredSlotFromFilename(name: String): Int? {
        if (!isExplicitBackgroundFilename(name)) return null
        val base = name.substringBeforeLast('.')
        val match = Regex("(?i)^(?:image|img)[ _-]?([1-7])$").matchEntire(base) ?: return null
        return match.groupValues[1].toIntOrNull()?.takeIf { it in 1..7 }
    }

    fun convertToBackgroundImage(source: ByteArray, slot: Int): ByteArray {
        require(slot in 1..7) { "Evo background image slot must be Image1 through Image7" }
        val decoded = BitmapFactory.decodeByteArray(source, 0, source.size)
            ?: error("Android could not decode this image")
        require(decoded.width > 0 && decoded.height > 0) { "image has invalid dimensions" }

        val target = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(target)
            canvas.drawColor(Color.WHITE)
            val scale = min(WIDTH.toFloat() / decoded.width, HEIGHT.toFloat() / decoded.height)
            val drawWidth = decoded.width * scale
            val drawHeight = decoded.height * scale
            val left = (WIDTH - drawWidth) / 2f
            val top = (HEIGHT - drawHeight) / 2f
            val paint = Paint().apply {
                isFilterBitmap = false
                isAntiAlias = false
            }
            canvas.drawBitmap(decoded, null, RectF(left, top, left + drawWidth, top + drawHeight), paint)

            val imageData = ByteArray(1 + WIDTH * HEIGHT * 2)
            imageData[0] = DATA_MARKER.toByte()
            var offset = 1
            val row = IntArray(WIDTH)
            for (storedY in 0 until HEIGHT) {
                val displayY = HEIGHT - 1 - storedY
                target.getPixels(row, 0, WIDTH, 0, displayY, WIDTH, 1)
                for (pixel in row) {
                    val r5 = (Color.red(pixel) * 31f / 255f).roundToInt().coerceIn(0, 31)
                    val g6 = (Color.green(pixel) * 63f / 255f).roundToInt().coerceIn(0, 63)
                    val b5 = (Color.blue(pixel) * 31f / 255f).roundToInt().coerceIn(0, 31)
                    val rgb565 = (r5 shl 11) or (g6 shl 5) or b5
                    imageData[offset++] = (rgb565 and 0xFF).toByte()
                    imageData[offset++] = (rgb565 ushr 8).toByte()
                }
            }

            val token = 0xE8B0 + (slot - 1)
            val nameBytes = byteArrayOf(
                (token and 0xFF).toByte(),
                (token ushr 8).toByte(),
                0,
                0
            )
            val writer = EvoContainerWriter()
            writer.beginMap()
            writer.text("metaData")
            writer.beginMap()
            writer.text("type")
            writer.uint(5)
            writer.text("version")
            writer.uint(1)
            writer.text("flags")
            writer.uint(1)
            writer.text("name")
            writer.bytes(nameBytes)
            writer.end()
            writer.text("version")
            writer.uint(1)
            writer.text("size")
            writer.uint(imageData.size.toLong())
            writer.text("data")
            writer.bytes(imageData)
            writer.end()

            val file = EvoFileCodec.appendChecksum(writer.toByteArray())
            val info = EvoFileCodec.inspect(file)
            require(info.type == 5) { "generated image did not validate as an Evo background image" }
            return file
        } finally {
            target.recycle()
            decoded.recycle()
        }
    }
}
