from pathlib import Path

path = Path("app/src/main/java/com/tijack/evo/MainActivity.kt")
text = path.read_text()
original = text


def replace_once(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected exactly one match, found {count}: {old[:100]!r}")
    text = text.replace(old, new, 1)


replace_once(
    "    private var androidSelectableCount = 0\n",
    "    private var androidSelectableCount = 0\n"
    "    private var pendingImageSlots: Map<String, Int> = emptyMap()\n"
)

replace_once(
    "        val supportedFiles = files.filter { isEvoFilename(it.name.orEmpty()) }\n",
    "        val supportedFiles = files.filter { isTransferableFilename(it.name.orEmpty()) }\n"
)

replace_once(
    "            val supported = isEvoFilename(name)\n",
    "            val supported = isTransferableFilename(name)\n"
    "            val badge = conversionBadge(name)\n"
)

replace_once(
    '''                title.text = when {\n                    !supported -> "$name  [NOT EVO]"\n                    selected -> "✓ $name"\n                    else -> name\n                }\n''',
    '''                val decorated = if (badge == null) name else "$name  [$badge]"\n                title.text = when {\n                    !supported -> "$name  [UNSUPPORTED]"\n                    selected -> "✓ $decorated"\n                    else -> decorated\n                }\n'''
)

replace_once(
    "                it.isFile && isEvoFilename(it.name.orEmpty())\n",
    "                it.isFile && isTransferableFilename(it.name.orEmpty())\n"
)

replace_once(
    '''        if (files.isEmpty()) return\n\n        transferring = true\n''',
    '''        if (files.isEmpty()) return\n\n        val imageFiles = files.filter { EvoImageConverter.canConvertFilename(it.name.orEmpty()) }\n        if (imageFiles.size > 7) {\n            return setTransferError("THE EVO HAS 7 IMAGE SLOTS · SELECT 7 OR FEWER IMAGES")\n        }\n        pendingImageSlots = allocateImageSlots(imageFiles, calculatorEntries)\n\n        transferring = true\n'''
)

replace_once(
    '''                    runOnUiThread {\n                        diagnostic.text =\n                            "READING ${index + 1}/${files.size} · ${file.name ?: "ANDROID FILE"}"\n                    }\n                    val data = readDocumentFile(file)\n''',
    '''                    runOnUiThread {\n                        val fileName = file.name ?: "ANDROID FILE"\n                        val action = when {\n                            EvoImageConverter.canConvertFilename(fileName) -> "CONVERTING IMAGE"\n                            LegacyTiConverter.canConvertFilename(fileName) -> "CONVERTING"\n                            else -> "READING"\n                        }\n                        diagnostic.text = "$action ${index + 1}/${files.size} · $fileName"\n                    }\n                    val data = readDocumentFile(file)\n'''
)

replace_once(
    '''            runOnUiThread {\n                transferring = false\n                updateActionButtons()\n                if (prepared.isEmpty()) {\n''',
    '''            pendingImageSlots = emptyMap()\n            runOnUiThread {\n                transferring = false\n                updateActionButtons()\n                if (prepared.isEmpty()) {\n'''
)

replace_once(
    "    private fun readDocumentFile(file: DocumentFile): ByteArray {\n",
    '''    private fun readDocumentFile(file: DocumentFile): ByteArray {\n        val raw = readRawDocumentFile(file)\n        val name = file.name.orEmpty()\n        return when {\n            isNativeEvoFilename(name) -> raw\n            LegacyTiConverter.canConvertFilename(name) ->\n                LegacyTiConverter.convertToEvo(raw, name, cacheDir, smart = true)\n            EvoImageConverter.canConvertFilename(name) -> {\n                val slot = pendingImageSlots[androidKey(file)]\n                    ?: error("no Evo image slot was assigned to $name")\n                EvoImageConverter.convertToBackgroundImage(raw, slot)\n            }\n            else -> error("unsupported file type")\n        }\n    }\n\n    private fun readRawDocumentFile(file: DocumentFile): ByteArray {\n'''
)

replace_once(
    '''    private fun isEvoFilename(name: String): Boolean {\n        val lower = name.lowercase()\n        return listOf(\n            ".8xn2", ".8xl2", ".8xp2", ".8xd2", ".8ci2",\n            ".8ca2", ".8xm2", ".8xy2", ".8xv2", ".8xs2",\n            ".8xw2", ".8xz2", ".8xt2", ".8xpy2", ".8mp2"\n        ).any { lower.endsWith(it) }\n    }\n''',
    '''    private fun isNativeEvoFilename(name: String): Boolean {\n        val lower = name.lowercase()\n        return listOf(\n            ".8xn2", ".8xl2", ".8xp2", ".8xd2", ".8ci2",\n            ".8ca2", ".8xm2", ".8xy2", ".8xv2", ".8xs2",\n            ".8xw2", ".8xz2", ".8xt2", ".8xpy2", ".8mp2"\n        ).any { lower.endsWith(it) }\n    }\n\n    private fun isTransferableFilename(name: String): Boolean =\n        isNativeEvoFilename(name) ||\n            LegacyTiConverter.canConvertFilename(name) ||\n            EvoImageConverter.canConvertFilename(name)\n\n    private fun conversionBadge(name: String): String? = when {\n        isNativeEvoFilename(name) -> null\n        LegacyTiConverter.canConvertFilename(name) -> "CONVERT"\n        EvoImageConverter.canConvertFilename(name) -> "TO IMAGE"\n        else -> null\n    }\n\n    private fun allocateImageSlots(\n        files: List<DocumentFile>,\n        existing: List<EvoEntry>\n    ): Map<String, Int> {\n        if (files.isEmpty()) return emptyMap()\n        require(files.size <= 7) { "The Evo has only 7 background image slots" }\n\n        val occupied = existing.asSequence()\n            .filter { it.type == 5 }\n            .mapNotNull { entry ->\n                Regex("(?i)^Image([1-7])$")\n                    .matchEntire(entry.name)\n                    ?.groupValues\n                    ?.get(1)\n                    ?.toIntOrNull()\n            }\n            .toMutableSet()\n        val assigned = mutableSetOf<Int>()\n        val result = LinkedHashMap<String, Int>()\n\n        for (file in files) {\n            val preferred = EvoImageConverter.preferredSlotFromFilename(file.name.orEmpty())\n            val slot = preferred?.takeIf { it !in assigned }\n                ?: (1..7).firstOrNull { it !in occupied && it !in assigned }\n                ?: (1..7).first { it !in assigned }\n            assigned += slot\n            result[androidKey(file)] = slot\n        }\n        return result\n    }\n'''
)

if text == original:
    raise SystemExit("no changes made")
path.write_text(text)
print("patched MainActivity.kt for v0.16 conversion support")
