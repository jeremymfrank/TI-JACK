package com.tijack.evo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.RectF
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Converts ordinary images into named Evo IM8C AppVars and animated GIFs into
 * a TI-JACK animation manifest plus a set of standard IM8C frame AppVars.
 *
 * Static output can already be displayed by Evo Python's ti_graphics.drawImage.
 * The GIF manifest is intentionally small and documented so JACKVIEW can play
 * generated frame variables without decoding GIF data on the calculator.
 */
internal object ViewerMediaConverter {
    private const val EVO_APPVAR_TYPE = 8
    private const val MAX_WIDTH = 320
    private const val MAX_HEIGHT = 210
    private const val MAX_GIF_FRAMES = 300
    private const val MAX_GIF_OUTPUT_BYTES = 6 * 1024 * 1024
    private const val MAX_IM8C_CORE_BYTES = 0xFFFF
    private const val LEGACY_HEX_FRAME_LIMIT = 0x100
    private const val BASE36_FRAME_LIMIT = 36 * 36
    private const val BASE36_DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    private val stillExtensions = setOf("png", "jpg", "jpeg", "webp")

    fun isGifFilename(name: String): Boolean =
        name.substringAfterLast('.', "").equals("gif", ignoreCase = true)

    fun isStillFilename(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in stillExtensions

    /**
     * Image1..Image7 names remain the explicit Evo graph-background path.
     * Other supported still images, plus all GIFs, become named viewer media.
     */
    fun canConvertFilename(name: String): Boolean =
        isGifFilename(name) ||
            (isStillFilename(name) && !EvoImageConverter.isExplicitBackgroundFilename(name))

    fun displayBadge(name: String): String =
        if (isGifFilename(name)) "GIF→VIEWER" else "VIEWER"

    fun convertToEvoVariables(source: ByteArray, sourceName: String): List<ByteArray> {
        require(canConvertFilename(sourceName)) { "file is not a viewer-media source" }
        return if (isGifFilename(sourceName)) {
            convertGif(source, sourceName)
        } else {
            listOf(convertStill(source, sourceName))
        }
    }

    /** 1-8 characters, A-Z/0-9/underscore, starts with a letter. */
    fun viewerNameFromFilename(sourceName: String): String {
        val base = sourceName.substringBeforeLast('.', sourceName)
        var cleaned = sanitizeName(base)
        if (cleaned.isBlank()) cleaned = "MEDIA"
        if (cleaned.first() !in 'A'..'Z') cleaned = "M$cleaned"
        if (Regex("^IMAGE[0-9]$").matches(cleaned)) cleaned = "J$cleaned"
        if (cleaned.length <= 8) return cleaned

        val crc = CRC32().apply { update(sourceName.toByteArray(Charsets.UTF_8)) }.value
        return cleaned.take(5) + "%03X".format(crc.toInt() and 0xFFF)
    }

    private fun convertStill(source: ByteArray, sourceName: String): ByteArray {
        val decoded = BitmapFactory.decodeByteArray(source, 0, source.size)
            ?: error("Android could not decode this image")
        require(decoded.width > 0 && decoded.height > 0) { "image has invalid dimensions" }

        try {
            var scale = fitScale(decoded.width, decoded.height)
            repeat(12) {
                val width = max(1, (decoded.width * scale).roundToInt())
                val height = max(1, (decoded.height * scale).roundToInt())
                val bitmap = renderBitmap(decoded, width, height)
                try {
                    val im8c = encodeIm8c(bitmap)
                    if (im8c != null) {
                        return packageAppVar(viewerNameFromFilename(sourceName), im8c)
                    }
                } finally {
                    bitmap.recycle()
                }
                scale *= 0.95f
            }
            error("image could not be reduced enough for an Evo IM8C AppVar")
        } finally {
            decoded.recycle()
        }
    }

    private fun convertGif(source: ByteArray, sourceName: String): List<ByteArray> {
        val movie = Movie.decodeByteArray(source, 0, source.size)
            ?: error("Android could not decode this GIF")
        require(movie.width() > 0 && movie.height() > 0) { "GIF has invalid dimensions" }

        val parsedTimeline = parseGifTimeline(source)
        require(parsedTimeline.delaysMs.isNotEmpty()) { "GIF contains no image frames" }

        // Long GIFs are intentionally clipped instead of rejected. The first
        // MAX_GIF_FRAMES frames keep their original order and delays; frames
        // after the limit are not transferred to the calculator.
        val timeline = if (parsedTimeline.delaysMs.size > MAX_GIF_FRAMES) {
            GifTimeline(
                delaysMs = parsedTimeline.delaysMs.take(MAX_GIF_FRAMES),
                loopCount = parsedTimeline.loopCount
            )
        } else {
            parsedTimeline
        }

        var scale = fitScale(movie.width(), movie.height())
        repeat(18) {
            val width = max(1, (movie.width() * scale).roundToInt())
            val height = max(1, (movie.height() * scale).roundToInt())
            val converted = renderGifVariables(
                movie = movie,
                sourceName = sourceName,
                timeline = timeline,
                width = width,
                height = height
            )
            if (converted != null) return converted
            scale *= 0.85f
        }
        error("GIF could not be reduced below the Evo media size limits")
    }

    /**
     * Returns null when a frame exceeds the 16-bit IM8C payload length or the
     * generated animation exceeds the current media budget, allowing the caller
     * to retry the whole animation at a smaller resolution.
     */
    private fun renderGifVariables(
        movie: Movie,
        sourceName: String,
        timeline: GifTimeline,
        width: Int,
        height: Int
    ): List<ByteArray>? {
        val frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val frameNames = gifFrameNames(sourceName, timeline.delaysMs.size)
        val outputs = ArrayList<ByteArray>(frameNames.size + 1)
        var totalBytes = 0
        var timeMs = 0
        val movieDuration = movie.duration().takeIf { it > 0 }
            ?: timeline.delaysMs.sum().coerceAtLeast(1)

        try {
            for (index in frameNames.indices) {
                frameBitmap.eraseColor(Color.TRANSPARENT)
                val canvas = Canvas(frameBitmap)
                canvas.save()
                canvas.scale(
                    width.toFloat() / movie.width().toFloat(),
                    height.toFloat() / movie.height().toFloat()
                )
                movie.setTime(timeMs.coerceIn(0, max(0, movieDuration - 1)))
                movie.draw(canvas, 0f, 0f)
                canvas.restore()

                val im8c = encodeIm8c(frameBitmap) ?: return null
                val file = packageAppVar(frameNames[index], im8c)
                outputs += file
                totalBytes += file.size
                if (totalBytes > MAX_GIF_OUTPUT_BYTES) return null
                timeMs += timeline.delaysMs[index]
            }

            val manifest = buildGifManifest(
                width = width,
                height = height,
                frameNames = frameNames,
                delaysMs = timeline.delaysMs,
                loopCount = timeline.loopCount
            )
            val manifestFile = packageAppVar(viewerNameFromFilename(sourceName), manifest)
            totalBytes += manifestFile.size
            if (totalBytes > MAX_GIF_OUTPUT_BYTES) return null
            // Frames first, manifest last: an interrupted transfer does not leave
            // a manifest advertising frames that were never sent.
            outputs += manifestFile
            return outputs
        } finally {
            frameBitmap.recycle()
        }
    }

    /**
     * Scale both down and up so one side reaches the JACKVIEW viewport while
     * preserving aspect ratio. The remaining letterbox area is centered by
     * JACKVIEW. If the resulting IM8C is too large, the retry loops above reduce
     * the scale until the payload fits.
     */
    private fun fitScale(width: Int, height: Int): Float =
        min(MAX_WIDTH.toFloat() / width, MAX_HEIGHT.toFloat() / height)

    private fun renderBitmap(source: Bitmap, width: Int, height: Int): Bitmap {
        val target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        canvas.drawColor(Color.TRANSPARENT)
        val paint = Paint().apply {
            isFilterBitmap = false
            isAntiAlias = false
        }
        canvas.drawBitmap(
            source,
            null,
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            paint
        )
        return target
    }

    private data class IndexedImage(
        val palette565: IntArray,
        val indices: ByteArray,
        val transparentIndex: Int?
    )

    /**
     * Preserve images with <=256 colors exactly. For richer images, use a
     * deterministic 6×7×6 RGB palette (252 colors) plus optional transparency.
     */
    private fun indexBitmap(bitmap: Bitmap): IndexedImage {
        val width = bitmap.width
        val height = bitmap.height
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)

        val colors = LinkedHashMap<Int, Int>()
        var hasTransparency = false
        for (pixel in argb) {
            if (Color.alpha(pixel) < 128) {
                hasTransparency = true
                continue
            }
            val rgb565 = rgb565(pixel)
            if (!colors.containsKey(rgb565)) {
                colors[rgb565] = colors.size
                if (colors.size + (if (hasTransparency) 1 else 0) > 256) break
            }
        }

        if (colors.size + (if (hasTransparency) 1 else 0) <= 256) {
            val palette = IntArray(colors.size + (if (hasTransparency) 1 else 0))
            val transparentIndex = if (hasTransparency) 0 else null
            val offset = if (hasTransparency) 1 else 0
            if (hasTransparency) palette[0] = 0
            colors.entries.forEachIndexed { i, entry -> palette[i + offset] = entry.key }
            val lookup = HashMap<Int, Int>(colors.size * 2)
            colors.keys.forEachIndexed { i, color -> lookup[color] = i + offset }
            val indices = ByteArray(argb.size)
            for (i in argb.indices) {
                indices[i] = if (Color.alpha(argb[i]) < 128) {
                    requireNotNull(transparentIndex).toByte()
                } else {
                    requireNotNull(lookup[rgb565(argb[i])]).toByte()
                }
            }
            return IndexedImage(palette, indices, transparentIndex)
        }

        val opaquePalette = IntArray(6 * 7 * 6)
        var p = 0
        for (ri in 0 until 6) {
            val r5 = ((ri * 31f) / 5f).roundToInt()
            for (gi in 0 until 7) {
                val g6 = ((gi * 63f) / 6f).roundToInt()
                for (bi in 0 until 6) {
                    val b5 = ((bi * 31f) / 5f).roundToInt()
                    opaquePalette[p++] = (r5 shl 11) or (g6 shl 5) or b5
                }
            }
        }
        val transparentIndex = if (hasTransparency) opaquePalette.size else null
        val palette = if (hasTransparency) opaquePalette + 0 else opaquePalette
        val indices = ByteArray(argb.size)
        for (i in argb.indices) {
            val pixel = argb[i]
            if (Color.alpha(pixel) < 128) {
                indices[i] = requireNotNull(transparentIndex).toByte()
                continue
            }
            val r5 = (Color.red(pixel) * 31 + 127) / 255
            val g6 = (Color.green(pixel) * 63 + 127) / 255
            val b5 = (Color.blue(pixel) * 31 + 127) / 255
            val ri = (r5 * 5 + 15) / 31
            val gi = (g6 * 6 + 31) / 63
            val bi = (b5 * 5 + 15) / 31
            indices[i] = ((ri * 7 + gi) * 6 + bi).toByte()
        }
        return IndexedImage(palette, indices, transparentIndex)
    }

    /**
     * Evo IM8C core: IM8C, mode(u16), width(u16), height(u16), alpha metadata,
     * palette count(u16), RGB565LE palette, then indexed or RLE pixel data.
     * A 16-bit core length is prepended inside the AppVar data field.
     */
    private fun encodeIm8c(bitmap: Bitmap): ByteArray? {
        val indexed = indexBitmap(bitmap)
        val rawPixels = indexed.indices
        val rlePixels = encodeIm8cRle(rawPixels)
        val useRle = rlePixels.size < rawPixels.size
        val pixelData = if (useRle) rlePixels else rawPixels
        val mode = if (useRle) 2 else 1

        val core = ByteArrayOutputStream()
        core.write("IM8C".toByteArray(Charsets.US_ASCII))
        writeU16(core, mode)
        writeU16(core, bitmap.width)
        writeU16(core, bitmap.height)
        core.write(if (indexed.transparentIndex == null) 0 else 1)
        core.write(indexed.transparentIndex ?: 0)
        writeU16(core, indexed.palette565.size)
        for (color in indexed.palette565) writeU16(core, color)
        core.write(pixelData)

        val coreBytes = core.toByteArray()
        if (coreBytes.size > MAX_IM8C_CORE_BYTES) return null
        validateIm8cCore(coreBytes, bitmap.width, bitmap.height)

        val appVarData = ByteArrayOutputStream(coreBytes.size + 2)
        writeU16(appVarData, coreBytes.size)
        appVarData.write(coreBytes)
        return appVarData.toByteArray()
    }

    /** IM8C RLE: literal control 0..127; repeated control 0x80..0xFE. */
    private fun encodeIm8cRle(indices: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(indices.size)
        val literals = ArrayList<Byte>(128)

        fun flushLiterals() {
            var offset = 0
            while (offset < literals.size) {
                val count = min(128, literals.size - offset)
                out.write(count - 1)
                for (i in 0 until count) out.write(literals[offset + i].toInt() and 0xFF)
                offset += count
            }
            literals.clear()
        }

        var i = 0
        while (i < indices.size) {
            val value = indices[i]
            var run = 1
            while (i + run < indices.size && run < 128 && indices[i + run] == value) run++
            if (run >= 2) {
                flushLiterals()
                out.write(0x80 + run - 2)
                out.write(value.toInt() and 0xFF)
                i += run
            } else {
                literals += value
                if (literals.size == 128) flushLiterals()
                i++
            }
        }
        flushLiterals()
        return out.toByteArray()
    }

    private fun validateIm8cCore(core: ByteArray, width: Int, height: Int) {
        require(core.size >= 14) { "generated IM8C image is too short" }
        require(core.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "IM8C") {
            "generated IM8C signature is invalid"
        }
        val mode = u16le(core, 4)
        require(mode == 1 || mode == 2) { "generated IM8C mode is invalid" }
        require(u16le(core, 6) == width && u16le(core, 8) == height) {
            "generated IM8C dimensions are invalid"
        }
        val paletteCount = u16le(core, 12)
        require(paletteCount in 1..256) { "generated IM8C palette is invalid" }
        var pos = 14 + paletteCount * 2
        require(pos <= core.size) { "generated IM8C palette is truncated" }

        var pixels = 0
        if (mode == 1) {
            pixels = core.size - pos
        } else {
            while (pos < core.size) {
                val control = core[pos++].toInt() and 0xFF
                if (control < 0x80) {
                    val count = control + 1
                    require(pos + count <= core.size) { "generated IM8C literal run is truncated" }
                    pos += count
                    pixels += count
                } else {
                    val count = (control - 0x80) + 2
                    require(pos < core.size) { "generated IM8C repeat run is truncated" }
                    pos++
                    pixels += count
                }
            }
        }
        require(pixels == width * height) { "generated IM8C pixel count is invalid" }
    }

    private fun buildGifManifest(
        width: Int,
        height: Int,
        frameNames: List<String>,
        delaysMs: List<Int>,
        loopCount: Int
    ): ByteArray {
        require(frameNames.size == delaysMs.size)
        val core = ByteArrayOutputStream()
        core.write("TIJGIF01".toByteArray(Charsets.US_ASCII))
        writeU16(core, width)
        writeU16(core, height)
        writeU16(core, frameNames.size)
        writeU16(core, loopCount.coerceIn(0, 0xFFFF))
        writeU32(core, delaysMs.fold(0L) { acc, value -> acc + value }.coerceAtMost(0xFFFF_FFFFL))
        for (i in frameNames.indices) {
            val name = frameNames[i].toByteArray(Charsets.US_ASCII)
            require(name.size <= 8)
            core.write(name)
            repeat(8 - name.size) { core.write(0) }
            writeU16(core, delaysMs[i].coerceIn(0, 0xFFFF))
        }
        val coreBytes = core.toByteArray()
        require(coreBytes.size <= MAX_IM8C_CORE_BYTES) { "GIF manifest is too large" }

        val data = ByteArrayOutputStream(coreBytes.size + 2)
        writeU16(data, coreBytes.size)
        data.write(coreBytes)
        return data.toByteArray()
    }

    private fun gifFrameNames(sourceName: String, count: Int): List<String> {
        require(count in 1..MAX_GIF_FRAMES)
        require(count <= BASE36_FRAME_LIMIT) { "GIF frame naming limit exceeded" }
        val base = sanitizeName(sourceName.substringBeforeLast('.', sourceName)).padEnd(2, 'X')
        val crc = CRC32().apply { update(sourceName.toByteArray(Charsets.UTF_8)) }.value.toInt()
        var prefix = "J" + base.take(2) + "%03X".format(crc and 0xFFF)
        val manifest = viewerNameFromFilename(sourceName)
        val useBase36 = count > LEGACY_HEX_FRAME_LIMIT

        fun suffix(index: Int): String =
            if (useBase36) base36Suffix(index) else "%02X".format(index)

        if ((0 until count).any { manifest == prefix + suffix(it) }) {
            prefix = "K" + base.take(2) + "%03X".format(crc and 0xFFF)
        }
        return (0 until count).map { prefix + suffix(it) }
    }

    private fun base36Suffix(index: Int): String {
        require(index in 0 until BASE36_FRAME_LIMIT)
        return "" +
            BASE36_DIGITS[index / 36] +
            BASE36_DIGITS[index % 36]
    }

    private fun packageAppVar(name: String, appVarData: ByteArray): ByteArray {
        val writer = EvoContainerWriter()
        writer.beginMap()
        writer.text("metaData")
        writer.beginMap()
        writer.text("type")
        writer.uint(EVO_APPVAR_TYPE.toLong())
        writer.text("version")
        writer.uint(1)
        writer.text("flags")
        writer.uint(1)
        writer.text("name")
        writer.bytes(appVarNameBytes(name))
        writer.end()
        writer.text("version")
        writer.uint(1)
        writer.text("size")
        writer.uint(appVarData.size.toLong())
        writer.text("data")
        writer.bytes(appVarData)
        writer.end()

        val file = EvoFileCodec.appendChecksum(writer.toByteArray())
        val info = EvoFileCodec.inspect(file)
        require(info.type == EVO_APPVAR_TYPE) { "generated media did not validate as an Evo AppVar" }
        require(info.displayName == name) { "generated media AppVar name did not validate" }
        return file
    }

    private fun appVarNameBytes(name: String): ByteArray {
        require(Regex("^[A-Z][A-Z0-9_]{0,7}$").matches(name)) {
            "viewer media name '$name' is not a valid Evo AppVar name"
        }
        val out = ByteArrayOutputStream((name.length + 1) * 2)
        for (char in name) {
            val token = when (char) {
                in 'A'..'Z' -> 0xE800 + (char - 'A')
                in '0'..'9' -> 0xE401 + (char - '0')
                '_' -> 0x005F
                else -> error("unsupported AppVar name character")
            }
            writeU16(out, token)
        }
        writeU16(out, 0)
        return out.toByteArray()
    }

    private fun sanitizeName(text: String): String = buildString {
        for (ch in text.uppercase()) if (ch in 'A'..'Z' || ch in '0'..'9' || ch == '_') append(ch)
    }

    private fun rgb565(pixel: Int): Int {
        val r5 = (Color.red(pixel) * 31 + 127) / 255
        val g6 = (Color.green(pixel) * 63 + 127) / 255
        val b5 = (Color.blue(pixel) * 31 + 127) / 255
        return (r5 shl 11) or (g6 shl 5) or b5
    }

    private data class GifTimeline(val delaysMs: List<Int>, val loopCount: Int)

    /** Parse GIF timing/loop metadata; Android Movie performs the image decode/compositing. */
    private fun parseGifTimeline(data: ByteArray): GifTimeline {
        require(data.size >= 13) { "GIF is too short" }
        val signature = data.copyOfRange(0, 6).toString(Charsets.US_ASCII)
        require(signature == "GIF87a" || signature == "GIF89a") { "not a GIF file" }

        var pos = 13
        val packed = data[10].toInt() and 0xFF
        if ((packed and 0x80) != 0) {
            pos += 3 * (1 shl ((packed and 0x07) + 1))
        }
        require(pos <= data.size) { "GIF global color table is truncated" }

        val delays = ArrayList<Int>()
        var pendingDelayMs: Int? = null
        var loopCount = 0

        while (pos < data.size) {
            when (data[pos++].toInt() and 0xFF) {
                0x3B -> break
                0x2C -> {
                    require(pos + 9 <= data.size) { "GIF image descriptor is truncated" }
                    val localPacked = data[pos + 8].toInt() and 0xFF
                    pos += 9
                    if ((localPacked and 0x80) != 0) {
                        pos += 3 * (1 shl ((localPacked and 0x07) + 1))
                        require(pos <= data.size) { "GIF local color table is truncated" }
                    }
                    require(pos < data.size) { "GIF image data is truncated" }
                    pos++
                    pos = skipGifSubBlocks(data, pos)
                    delays += pendingDelayMs ?: 100
                    pendingDelayMs = null
                }
                0x21 -> {
                    require(pos < data.size) { "GIF extension is truncated" }
                    when (data[pos++].toInt() and 0xFF) {
                        0xF9 -> {
                            require(pos < data.size) { "GIF graphics control extension is truncated" }
                            val size = data[pos++].toInt() and 0xFF
                            require(size >= 4 && pos + size < data.size) {
                                "GIF graphics control extension is invalid"
                            }
                            val delayCs = u16le(data, pos + 1)
                            pendingDelayMs = if (delayCs == 0) 100 else max(20, delayCs * 10)
                            pos += size
                            require((data[pos++].toInt() and 0xFF) == 0) {
                                "GIF graphics control extension is unterminated"
                            }
                        }
                        0xFF -> {
                            require(pos < data.size) { "GIF application extension is truncated" }
                            val size = data[pos++].toInt() and 0xFF
                            require(pos + size <= data.size) { "GIF application id is truncated" }
                            val appId = data.copyOfRange(pos, pos + size).toString(Charsets.US_ASCII)
                            pos += size
                            if (appId.startsWith("NETSCAPE2.0") || appId.startsWith("ANIMEXTS1.0")) {
                                if (pos < data.size) {
                                    val subSize = data[pos].toInt() and 0xFF
                                    if (
                                        subSize >= 3 && pos + 1 + subSize <= data.size &&
                                        (data[pos + 1].toInt() and 0xFF) == 1
                                    ) {
                                        loopCount = u16le(data, pos + 2)
                                    }
                                }
                            }
                            pos = skipGifSubBlocks(data, pos)
                        }
                        else -> pos = skipGifSubBlocks(data, pos)
                    }
                }
                else -> error("GIF contains an unknown block")
            }
        }
        return GifTimeline(delays, loopCount)
    }

    private fun skipGifSubBlocks(data: ByteArray, start: Int): Int {
        var pos = start
        while (true) {
            require(pos < data.size) { "GIF sub-blocks are truncated" }
            val size = data[pos++].toInt() and 0xFF
            if (size == 0) return pos
            require(pos + size <= data.size) { "GIF sub-block is truncated" }
            pos += size
        }
    }

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        require(value in 0..0xFFFF)
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }

    private fun writeU32(out: ByteArrayOutputStream, value: Long) {
        require(value in 0..0xFFFF_FFFFL)
        out.write((value and 0xFF).toInt())
        out.write(((value ushr 8) and 0xFF).toInt())
        out.write(((value ushr 16) and 0xFF).toInt())
        out.write(((value ushr 24) and 0xFF).toInt())
    }

    private fun u16le(data: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset + 1 < data.size) { "truncated little-endian value" }
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8)
    }
}
