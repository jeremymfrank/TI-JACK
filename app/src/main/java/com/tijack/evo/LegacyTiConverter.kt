package com.tijack.evo

import java.io.File
import java.io.ByteArrayOutputStream

/**
 * Pure-Kotlin legacy TI-BASIC program converter.
 *
 * Conversion is deliberately strict: unsupported tokens fail instead of being
 * guessed. A fidelity pass adapts a positively identified 265x165 CE canvas to
 * the Evo while preserving the program's original logical coordinates.
 */
internal object LegacyTiConverter {
    private const val LEGACY_PROGRAM = 0x05
    private const val LEGACY_PROTECTED_PROGRAM = 0x06

    private const val EVO_LPAREN = 0xE410
    private const val EVO_RPAREN = 0xE411
    private const val EVO_NEW_LINE = 0xE41C
    private const val EVO_COMMA = 0xE417
    private const val EVO_ADD = 0xE428
    private const val EVO_SUB = 0xE429
    private const val EVO_CHS = 0xE42E
    private const val EVO_LINE = 0xE4EB
    private const val EVO_PT_OFF = 0xE4ED
    private const val EVO_PT_ON = 0xE4EE
    private const val EVO_PXL_OFF = 0xE4F0
    private const val EVO_PXL_ON = 0xE4F1
    private const val EVO_PXL_TEST = 0xE4F2
    private const val EVO_TEXT = 0xE4F5
    private const val EVO_BORDER_COLOR = 0xE5BA

    // TI-84 Plus CE graph canvas dimensions used by programs such as SNAKE.
    // The Evo graph area is 319x209, so a 265x165 legacy canvas centers with
    // 27 pixels horizontally and 22 pixels vertically.
    private const val X_OFFSET = 27
    private const val Y_OFFSET = 22
    private const val CE_X_MAX = 264
    private const val CE_Y_MAX = 164

    private val twoBytePrefixes = setOf(
        0x5C, 0x5D, 0x5E, 0x60, 0x61, 0x62, 0x63, 0x7E, 0xAA, 0xBB, 0xEF
    )

    fun canConvertFilename(name: String): Boolean =
        name.lowercase().endsWith(".8xp")

    @Suppress("UNUSED_PARAMETER")
    fun convertToEvo(
        source: ByteArray,
        sourceName: String,
        cacheDir: File,
        smart: Boolean = true
    ): ByteArray {
        require(canConvertFilename(sourceName)) {
            "this conversion preview currently supports TI-BASIC .8xp programs"
        }

        val parsed = parseLegacyProgram(source)
        val legacyTokens = tokenizeLegacy(parsed.tokenData)
        val evoTokens = legacyTokens.mapIndexed { index, token ->
            mapLegacyToken(token) ?: throw IllegalArgumentException(
                "legacy token 0x${token.toString(16).uppercase().padStart(4, '0')} " +
                    "at token ${index + 1} is not supported yet; file was not sent"
            )
        }

        val adapted = applyEvoFidelityPass(evoTokens)
        val dataWords = adapted + 0 // TOK_EOS
        val data = wordsToBytes(dataWords)
        val nameBytes = wordsToBytes(programNameWords(parsed.name) + 0)

        val writer = EvoContainerWriter()
        writer.beginMap()
        writer.text("metaData")
        writer.beginMap()
        writer.text("type")
        writer.uint(2)
        writer.text("version")
        writer.uint(1)
        writer.text("flags")
        writer.uint(0)
        writer.text("name")
        writer.bytes(nameBytes)
        writer.end()
        writer.text("version")
        writer.uint(1)
        writer.text("arraylen")
        writer.uint(dataWords.size.toLong())
        writer.text("size")
        writer.uint(data.size.toLong())
        writer.text("data")
        writer.bytes(data)
        writer.end()

        val file = EvoFileCodec.appendChecksum(writer.toByteArray())
        val info = EvoFileCodec.inspect(file)
        require(info.type == 2) { "generated program did not validate as an Evo program" }
        return file
    }

    private data class LegacyProgram(val name: String, val tokenData: ByteArray)

    private fun parseLegacyProgram(source: ByteArray): LegacyProgram {
        require(source.size >= 76) { "legacy TI program is too short" }
        val signature = source.copyOfRange(0, 8).toString(Charsets.US_ASCII)
        require(signature.startsWith("**TI83F")) { "not a TI-83/84 variable file" }

        val sectionLength = u16le(source, 53)
        require(sectionLength == source.size - 57) { "legacy TI file length is inconsistent" }

        val storedChecksum = u16le(source, source.size - 2)
        var checksum = 0
        for (i in 55 until source.size - 2) {
            checksum = (checksum + (source[i].toInt() and 0xFF)) and 0xFFFF
        }
        require(checksum == storedChecksum) { "legacy TI file checksum is invalid" }

        val entry = 55
        val headerLength = u16le(source, entry)
        require(headerLength == 0x0D) { "unsupported legacy TI variable header" }
        val dataLength = u16le(source, entry + 2)
        val type = source[entry + 4].toInt() and 0xFF
        require(type == LEGACY_PROGRAM || type == LEGACY_PROTECTED_PROGRAM) {
            "selected .8xp is not a TI-BASIC program"
        }

        val rawName = source.copyOfRange(entry + 5, entry + 13)
        val nameLength = rawName.indexOf(0).let { if (it < 0) rawName.size else it }
        val name = rawName.copyOfRange(0, nameLength).toString(Charsets.US_ASCII)
        require(name.isNotBlank()) { "legacy TI program has no name" }

        val duplicateLength = u16le(source, entry + 15)
        require(dataLength == duplicateLength) { "legacy TI program data lengths disagree" }
        val payloadStart = entry + 17
        val payloadEnd = payloadStart + dataLength
        require(payloadEnd == source.size - 2) { "grouped or multi-entry TI files are not supported yet" }
        require(dataLength >= 2) { "legacy TI program payload is empty" }

        val tokenLength = u16le(source, payloadStart)
        require(tokenLength == dataLength - 2) { "legacy TI program token length is invalid" }
        return LegacyProgram(
            name = name,
            tokenData = source.copyOfRange(payloadStart + 2, payloadEnd)
        )
    }

    private fun tokenizeLegacy(data: ByteArray): List<Int> {
        val result = ArrayList<Int>()
        var offset = 0
        while (offset < data.size) {
            val first = data[offset].toInt() and 0xFF
            if (first in twoBytePrefixes) {
                require(offset + 1 < data.size) { "truncated two-byte TI-BASIC token" }
                result += (first shl 8) or (data[offset + 1].toInt() and 0xFF)
                offset += 2
            } else {
                result += first
                offset++
            }
        }
        return result
    }

    /** Strict mapping for the token set currently hardware-tested by TI-JACK. */
    private fun mapLegacyToken(token: Int): Int? = when (token) {
        0x0004 -> 0xE41D
        0x0006 -> 0xE412
        0x0007 -> 0xE413
        0x0008 -> 0xE414
        0x0010 -> 0xE410
        0x0011 -> 0xE411
        0x0013 -> 0xE4F2
        0x0029 -> 0xE419
        0x002A -> 0xE416
        0x002B -> 0xE417
        0x002D -> 0xE421
        in 0x0030..0x0039 -> 0xE401 + (token - 0x0030)
        0x003A -> 0xE40B
        0x003E -> 0xE418
        0x003F -> 0xE41C
        0x0040 -> 0xE47C
        in 0x0041..0x005A -> 0xE800 + (token - 0x0041)
        0x005B -> 0xE81A
        0x006A -> 0xE47F
        0x006C -> 0xE481
        0x006F -> 0xE484
        0x0070 -> 0xE428
        0x0071 -> 0xE429
        0x0085 -> 0xE4EA
        0x0093 -> 0xE4F5
        0x009C -> 0xE4EB
        0x009E -> 0xE4EE
        0x009F -> 0xE4ED
        0x00A1 -> 0xE4F1
        0x00A2 -> 0xE4F0
        0x00A6 -> 0xE4F4
        0x00AD -> 0xE4D1
        0x00B5 -> 0xE466
        0x00B6 -> 0xE458
        0x00B8 -> 0xE485
        0x00B9 -> 0xE432
        0x00C0 -> 0xE43D
        0x00CE -> 0xE4C0
        0x00CF -> 0xE4C1
        0x00D2 -> 0xE4C4
        0x00D3 -> 0xE4C5
        0x00D4 -> 0xE4C6
        0x00D8 -> 0xE4CA
        0x00DE -> 0xE4D3
        0x00E0 -> 0xE4D6
        0x00E1 -> 0xE4D7
        0x00EB -> 0xE836
        in 0x5D00..0x5D05 -> 0xE830 + (token - 0x5D00)
        0x630A -> 0xE98F
        0x630B -> 0xE990
        0x630C -> 0xE993
        0x630D -> 0xE994
        0x7E09 -> 0xE604
        0x7E0B -> 0xE606
        0xBB0A -> 0xE436
        0xBB4A -> 0xE4DE
        0xBB68 -> 0xE4E5
        0xBBB0 -> 0x0061
        0xBBB1 -> 0x0062
        0xBBB2 -> 0x0063
        0xBBB3 -> 0x0064
        0xBBB4 -> 0x0065
        0xBBB5 -> 0x0066
        0xBBB6 -> 0x0067
        0xBBB7 -> 0x0068
        0xBBB8 -> 0x0069
        0xBBB9 -> 0x006A
        0xBBBA -> 0x006B
        0xBBBC -> 0x006C
        0xBBBD -> 0x006D
        0xBBBE -> 0x006E
        0xBBBF -> 0x006F
        0xBBC0 -> 0x0070
        0xBBC1 -> 0x0071
        0xBBC2 -> 0x0072
        0xBBC3 -> 0x0073
        0xBBC4 -> 0x0074
        0xBBC5 -> 0x0075
        0xBBC6 -> 0x0076
        0xBBC7 -> 0x0077
        0xBBC8 -> 0x0078
        0xBBC9 -> 0x0079
        0xBBCA -> 0x007A
        0xEF42 -> 0xE5A1
        0xEF43 -> 0xE5A2
        0xEF44 -> 0xE5A3
        0xEF45 -> 0xE5A4
        0xEF49 -> 0xE5A8
        0xEF4E -> 0xE5AD
        0xEF5B -> 0xE5B6
        0xEF67 -> 0xE5B9
        0xEF6C -> 0xE5BA
        else -> null
    }

    private fun applyEvoFidelityPass(tokens: List<Int>): List<Int> {
        val lines = splitLines(tokens)
        val centered = hasClassicCeCanvas(lines)
        val result = ArrayList<List<Int>>(lines.size)

        for (sourceLine in lines) {
            var line = sourceLine.toMutableList()

            if (centered) {
                line = centeredWindowLine(line).toMutableList()
                line = centerPixelBasedCommands(line).toMutableList()

                // CE Dot-Thick points are a 3x3 pixel footprint. Real Evo
                // hardware can rasterize Pt-On/Pt-Off slightly differently at a
                // turn, leaving a single colored pixel behind. For the classic
                // canvas only, replace literal mark-1 point operations with nine
                // matching Pxl-On/Pxl-Off operations. Drawing and erasing then
                // touch exactly the same pixels without changing game logic.
                val exactPoint = emulateClassicCeDotPoint(line)
                if (exactPoint != null) {
                    result.addAll(exactPoint)
                    continue
                }
            }

            val borderIndex = line.indexOf(EVO_BORDER_COLOR)
            if (borderIndex >= 0) {
                require(borderIndex == 0 && line.size == 2) {
                    "BorderColor in a compound or complex statement needs manual conversion; file was not sent"
                }
                val colorToken = line[1]
                require(colorToken in 0xE402..0xE405) {
                    "BorderColor must use legacy border value 1 through 4; file was not sent"
                }
                val color = colorToken - 0xE401
                require(centered) {
                    "BorderColor $color needs a recognized legacy graph canvas for safe Evo emulation; file was not sent"
                }
                result.addAll(classicCeBorderLines(color))
                continue
            }
            result += line
        }

        return joinLines(result)
    }

    /**
     * Convert Pt-On(x,y,1,color) and Pt-Off(x,y,1) to an exact 3x3 pixel block.
     * Only the literal CE Dot-Thick mark (1) is rewritten. Other point styles
     * retain their normal Evo point command until independently hardware-tested.
     */
    private fun emulateClassicCeDotPoint(line: List<Int>): List<List<Int>>? {
        if (line.isEmpty()) return null
        val command = line.first()
        if (command != EVO_PT_ON && command != EVO_PT_OFF) return null

        val args = splitTopLevelArguments(line) ?: return null
        val expectedCount = if (command == EVO_PT_ON) 4 else 3
        if (args.size != expectedCount || args[2] != listOf(0xE402)) return null

        val x = args[0]
        val y = args[1]
        if (x.isEmpty() || y.isEmpty()) return null

        val color = if (command == EVO_PT_ON) {
            args[3].singleOrNull() ?: return null
        } else {
            null
        }

        val result = ArrayList<List<Int>>(9)
        val centerRow = CE_Y_MAX + Y_OFFSET // 186 - y
        for (dy in -1..1) {
            for (dx in -1..1) {
                val row = numberTokens(centerRow + dy) +
                    listOf(EVO_SUB, EVO_LPAREN) + y + listOf(EVO_RPAREN)
                val col = listOf(EVO_LPAREN) + x + listOf(EVO_RPAREN, EVO_ADD) +
                    numberTokens(X_OFFSET + dx)
                result += pixelCommand(
                    token = if (command == EVO_PT_ON) EVO_PXL_ON else EVO_PXL_OFF,
                    row = row,
                    col = col,
                    color = color
                )
            }
        }
        return result
    }

    /** Split command arguments while respecting explicit parentheses in expressions. */
    private fun splitTopLevelArguments(line: List<Int>): List<List<Int>>? {
        val args = ArrayList<List<Int>>()
        var current = ArrayList<Int>()
        var depth = 0

        for (index in 1 until line.size) {
            val token = line[index]
            when {
                token == EVO_LPAREN -> {
                    depth++
                    current += token
                }
                token == EVO_RPAREN && depth > 0 -> {
                    depth--
                    current += token
                }
                token == EVO_RPAREN && depth == 0 -> {
                    if (index != line.lastIndex) return null
                }
                token == EVO_COMMA && depth == 0 -> {
                    if (current.isEmpty()) return null
                    args += current
                    current = ArrayList()
                }
                else -> current += token
            }
        }
        if (depth != 0 || current.isEmpty()) return null
        args += current
        return args
    }

    private fun pixelCommand(
        token: Int,
        row: List<Int>,
        col: List<Int>,
        color: Int?
    ): List<Int> {
        val result = ArrayList<Int>(row.size + col.size + 8)
        result += token
        result.addAll(row)
        result += EVO_COMMA
        result.addAll(col)
        if (color != null) {
            result += EVO_COMMA
            result += color
        }
        result += EVO_RPAREN
        return result
    }

    private fun classicCeBorderLines(borderColor: Int): List<List<Int>> {
        val drawColor = when (borderColor) {
            1 -> 21 // CE BorderColor 1: Light Gray -> LTGRAY
            2 -> 21 // CE-only Snowy Mint; nearest conservative draw approximation
            3 -> 18 // CE BorderColor 3: Light Blue -> LTBLUE
            4 -> 20 // CE BorderColor 4: White -> WHITE
            else -> error("unsupported BorderColor value")
        }

        val result = ArrayList<List<Int>>(8)
        for (offset in 1..2) {
            val left = -offset
            val right = CE_X_MAX + offset
            val bottom = -offset
            val top = CE_Y_MAX + offset
            result += lineCommand(left, bottom, right, bottom, drawColor)
            result += lineCommand(right, bottom, right, top, drawColor)
            result += lineCommand(right, top, left, top, drawColor)
            result += lineCommand(left, top, left, bottom, drawColor)
        }
        return result
    }

    private fun lineCommand(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        color: Int
    ): List<Int> {
        val arguments = listOf(x1, y1, x2, y2, 1, color, 1)
        val result = ArrayList<Int>(24)
        result += EVO_LINE
        arguments.forEachIndexed { index, value ->
            result.addAll(numberTokens(value))
            if (index != arguments.lastIndex) result += EVO_COMMA
        }
        result += EVO_RPAREN
        return result
    }

    private fun hasClassicCeCanvas(lines: List<List<Int>>): Boolean {
        val expected = setOf(
            listOf(0xE401, 0xE41D, 0xE98F),
            listOf(0xE403, 0xE407, 0xE405, 0xE41D, 0xE990),
            listOf(0xE401, 0xE41D, 0xE993),
            listOf(0xE402, 0xE407, 0xE405, 0xE41D, 0xE994)
        )
        return expected.all { target -> lines.any { it == target } }
    }

    private fun centeredWindowLine(line: List<Int>): List<Int> = when (line) {
        listOf(0xE401, 0xE41D, 0xE98F) ->
            numberTokens(-X_OFFSET) + listOf(0xE41D, 0xE98F)
        listOf(0xE403, 0xE407, 0xE405, 0xE41D, 0xE990) ->
            numberTokens(291) + listOf(0xE41D, 0xE990)
        listOf(0xE401, 0xE41D, 0xE993) ->
            numberTokens(-Y_OFFSET) + listOf(0xE41D, 0xE993)
        listOf(0xE402, 0xE407, 0xE405, 0xE41D, 0xE994) ->
            numberTokens(186) + listOf(0xE41D, 0xE994)
        else -> line
    }

    private fun centerPixelBasedCommands(source: List<Int>): List<Int> {
        var line = source
        if (EVO_TEXT in line) {
            line = offsetTextArguments(line)
        }
        if (EVO_PXL_TEST in line) {
            line = offsetPxlTestArguments(line)
        }
        return line
    }

    private fun offsetTextArguments(source: List<Int>): List<Int> {
        val command = source.indexOf(EVO_TEXT)
        if (command < 0) return source
        val commas = (command + 1 until source.size).filter { source[it] == EVO_COMMA }
        require(commas.size >= 2) { "could not safely center a Text( command" }

        val firstInsert = commas[0]
        val y = listOf(EVO_ADD) + numberTokens(Y_OFFSET)
        var line = source.take(firstInsert) + y + source.drop(firstInsert)

        val secondInsert = commas[1] + y.size
        val x = listOf(EVO_ADD) + numberTokens(X_OFFSET)
        line = line.take(secondInsert) + x + line.drop(secondInsert)
        return line
    }

    private fun offsetPxlTestArguments(source: List<Int>): List<Int> {
        val command = source.indexOf(EVO_PXL_TEST)
        if (command < 0) return source
        val comma = (command + 1 until source.size).firstOrNull { source[it] == EVO_COMMA }
            ?: throw IllegalArgumentException("could not safely center a pxl-Test( command")

        val y = listOf(EVO_ADD) + numberTokens(Y_OFFSET)
        var line = source.take(comma) + y + source.drop(comma)
        val x = listOf(EVO_ADD) + numberTokens(X_OFFSET)
        line = line.take(line.size) + x
        return line
    }

    private fun splitLines(tokens: List<Int>): List<List<Int>> {
        val lines = ArrayList<List<Int>>()
        var current = ArrayList<Int>()
        for (token in tokens) {
            if (token == EVO_NEW_LINE) {
                lines += current
                current = ArrayList()
            } else {
                current += token
            }
        }
        lines += current
        return lines
    }

    private fun joinLines(lines: List<List<Int>>): List<Int> {
        val result = ArrayList<Int>()
        lines.forEachIndexed { index, line ->
            result.addAll(line)
            if (index != lines.lastIndex) result += EVO_NEW_LINE
        }
        return result
    }

    private fun numberTokens(value: Int): List<Int> {
        val result = ArrayList<Int>()
        var text = value.toString()
        if (text.startsWith('-')) {
            result += EVO_CHS
            text = text.drop(1)
        }
        for (char in text) {
            require(char in '0'..'9')
            result += 0xE401 + (char - '0')
        }
        return result
    }

    private fun programNameWords(name: String): List<Int> = name.map { char ->
        when (char) {
            in 'A'..'Z' -> 0xE800 + (char - 'A')
            in '0'..'9' -> 0xE401 + (char - '0')
            else -> throw IllegalArgumentException(
                "program name '$name' contains a character not supported by the Evo preview"
            )
        }
    }

    private fun wordsToBytes(words: List<Int>): ByteArray {
        val out = ByteArrayOutputStream(words.size * 2)
        for (word in words) {
            out.write(word and 0xFF)
            out.write((word ushr 8) and 0xFF)
        }
        return out.toByteArray()
    }

    private fun u16le(data: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset + 1 < data.size) { "truncated legacy TI file" }
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8)
    }
}
