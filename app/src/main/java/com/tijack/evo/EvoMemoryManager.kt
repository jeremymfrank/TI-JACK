package com.tijack.evo

internal data class EvoMemorySnapshot(
    val ramUsed: Long,
    val archiveUsed: Long,
    val ramFreeEstimate: Long,
    val archiveFreeEstimate: Long
)

/**
 * Conservative memory accounting for the Evo plus JACKVIEW GIF cleanup helpers.
 *
 * The calculator directory exposes each variable's size and RAM/archive state,
 * but the currently observed directory response does not expose the same exact
 * RAM FREE / ARC FREE counters shown by the calculator's Memory screen. These
 * budgets intentionally stay below observed fresh-calculator free space so the
 * UI/preflight estimate errs on the safe side instead of promising bytes that
 * may be reserved by the OS or preloaded content.
 */
internal object EvoMemoryManager {
    const val RAM_SAFE_CAPACITY_BYTES: Long = 560L * 1024L
    const val ARCHIVE_SAFE_CAPACITY_BYTES: Long = 2700L * 1024L
    const val MAX_SINGLE_MEDIA_BYTES: Int = 2400 * 1024

    private const val VARIABLE_OVERHEAD_BYTES = 64L
    private const val MANIFEST_SCAN_LIMIT_BYTES = 8L * 1024L
    private val generatedFramePattern =
        Regex("^[JK][A-Z0-9_]{2}[0-9A-F]{3}[0-9A-Z]{2}$")

    fun footprint(size: Long): Long =
        size.coerceAtLeast(0L) + VARIABLE_OVERHEAD_BYTES

    fun snapshot(entries: List<EvoEntry>): EvoMemorySnapshot {
        var ram = 0L
        var archive = 0L
        for (entry in entries) {
            val bytes = footprint(entry.size)
            if (entry.archived) archive += bytes else ram += bytes
        }
        return EvoMemorySnapshot(
            ramUsed = ram,
            archiveUsed = archive,
            ramFreeEstimate = (RAM_SAFE_CAPACITY_BYTES - ram).coerceAtLeast(0L),
            archiveFreeEstimate = (ARCHIVE_SAFE_CAPACITY_BYTES - archive).coerceAtLeast(0L)
        )
    }

    fun isGeneratedGifFrameName(name: String): Boolean =
        generatedFramePattern.matches(name.uppercase())

    /**
     * Find generated JACKVIEW frame variables that are not referenced by any
     * readable TIJGIF01 manifest currently on the calculator.
     *
     * A candidate prefix must contain at least two frame-like AppVars. That
     * extra requirement avoids treating a single unrelated eight-character
     * AppVar as disposable merely because its name happens to match TI-JACK's
     * generated frame pattern.
     */
    fun findOrphanGifFrames(
        client: EvoUsbClient,
        entries: List<EvoEntry>,
        log: (String) -> Unit
    ): List<EvoEntry> {
        val appVars = entries.filter { it.type == 8 }
        val candidates = appVars.filter { isGeneratedGifFrameName(it.name) }
        if (candidates.isEmpty()) return emptyList()

        val referenced = HashSet<String>()
        for (entry in appVars) {
            if (entry.size > MANIFEST_SCAN_LIMIT_BYTES) continue
            try {
                val file = client.downloadVariable(entry)
                parseGifManifestFrameNames(file)?.let { referenced += it }
            } catch (t: Throwable) {
                log("GIF cleanup scan skipped ${entry.name}: ${t.message.orEmpty()}")
            }
        }

        val unreferenced = candidates.filter { it.name.uppercase() !in referenced }
        val eligiblePrefixes = unreferenced
            .groupBy { it.name.uppercase().take(6) }
            .filterValues { it.size >= 2 }
            .keys

        return unreferenced
            .filter { it.name.uppercase().take(6) in eligiblePrefixes }
            .sortedWith(compareBy<EvoEntry> { it.name.uppercase() }.thenBy { it.size })
    }

    private fun parseGifManifestFrameNames(file: ByteArray): Set<String>? {
        if (file.size < 4) return null
        val info = try {
            EvoFileCodec.inspect(file)
        } catch (_: Throwable) {
            return null
        }
        if (info.type != 8) return null

        val body = file.copyOfRange(0, file.size - 2)
        val root = try {
            CborLite(body).decode() as? Map<*, *>
        } catch (_: Throwable) {
            return null
        } ?: return null
        val data = root["data"] as? ByteArray ?: return null
        if (data.size < 22) return null

        val coreLength = u16le(data, 0)
        if (coreLength <= 0 || 2 + coreLength > data.size) return null
        val signature = data.copyOfRange(2, 10).toString(Charsets.US_ASCII)
        if (signature != "TIJGIF01") return null

        val count = u16le(data, 14)
        if (count !in 1..300) return null
        val required = 22 + count * 10
        if (required > data.size || required > 2 + coreLength) return null

        val result = LinkedHashSet<String>(count)
        var pos = 22
        repeat(count) {
            val raw = data.copyOfRange(pos, pos + 8)
            val zero = raw.indexOf(0)
            val end = if (zero >= 0) zero else raw.size
            val name = raw.copyOfRange(0, end).toString(Charsets.US_ASCII).uppercase()
            if (name.isNotBlank()) result += name
            pos += 10
        }
        return result
    }

    private fun u16le(data: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 1 >= data.size) return -1
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8)
    }
}
