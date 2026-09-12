package com.tijack.evo

import java.io.File

/** Offline bridge to the pinned MIT-licensed tivars_lib_cpp conversion engine. */
internal object LegacyTiConverter {
    private val convertibleExtensions = setOf(
        "8xn", "8xl", "8xp", "8xd", "8xi", "8ca", "8xm", "8xy",
        "8xv", "8xs", "8xw", "8xz", "8xt"
    )

    init {
        System.loadLibrary("tijack_converter")
    }

    fun canConvertFilename(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in convertibleExtensions

    fun convertToEvo(
        source: ByteArray,
        sourceName: String,
        cacheDir: File,
        smart: Boolean = true
    ): ByteArray {
        require(canConvertFilename(sourceName)) { "legacy TI file type is not supported for conversion" }
        val sourceExt = sourceName.substringAfterLast('.', "8xp").lowercase()
        val input = File.createTempFile("tijack_legacy_", ".$sourceExt", cacheDir)
        val output = File.createTempFile("tijack_evo_", ".bin", cacheDir)
        try {
            input.writeBytes(source)
            val resultPath = convertToEvoNative(input.absolutePath, output.absolutePath, smart)
            val result = File(resultPath)
            require(result.exists() && result.length() > 2) { "converter produced no Evo file" }
            val bytes = result.readBytes()
            EvoFileCodec.inspect(bytes)
            return bytes
        } finally {
            input.delete()
            output.delete()
        }
    }

    private external fun convertToEvoNative(
        inputPath: String,
        outputPath: String,
        smart: Boolean
    ): String
}
