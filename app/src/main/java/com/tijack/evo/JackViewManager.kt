package com.tijack.evo

/**
 * Installs/updates JACKVIEW and regenerates JACKCAT from IM8C AppVars found on
 * the calculator. JACKCAT is derived from calculator contents; it is not a
 * second source of truth.
 */
internal object JackViewManager {
    private const val APPVAR_TYPE = 8
    private const val PYTHON_TYPE = 15
    private const val MAX_SCAN_SIZE = 70_000L
    private const val MAX_GIF_FRAMES = 300
    private const val LEGACY_HEX_FRAME_LIMIT = 0x100
    private const val BASE36_FRAME_LIMIT = 36 * 36
    private const val BASE36_DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val VIEWPORT_WIDTH = 320
    private const val VIEWPORT_HEIGHT = 210
    private const val JACKVIEW_NAME = "JACKVIEW"
    private const val JACKCAT_NAME = "JACKCAT"

    private data class MediaItem(
        val title: String,
        val base: String,
        val frameCount: Int,
        val defaultDelayMs: Int = 0,
        val width: Int = VIEWPORT_WIDTH,
        val height: Int = VIEWPORT_HEIGHT
    )

    private data class GifManifest(
        val title: String,
        val frameNames: List<String>,
        val width: Int,
        val height: Int
    )

    fun sync(
        client: EvoUsbClient,
        entries: List<EvoEntry>,
        log: (String) -> Unit
    ): Boolean {
        var changed = false

        val jackView = EvoPythonPackager.build(JACKVIEW_NAME, jackViewSource)
        val jackViewInfo = EvoFileCodec.inspect(jackView)
        var jackViewPresent = entries.any { EvoFileCodec.sameIdentity(jackViewInfo, it) }
        try {
            changed = syncVariable(client, entries, jackView, JACKVIEW_NAME, log) || changed
            jackViewPresent = true
        } catch (t: Throwable) {
            // An already-installed JACKVIEW must not prevent a fresh JACKCAT
            // from being generated. This keeps catalog updates independent from
            // a non-critical companion-program refresh failure.
            log("JACKVIEW update warning: ${t.message.orEmpty()}")
            if (!jackViewPresent) throw t
        }

        val imageNames = LinkedHashSet<String>()
        val imageDimensions = LinkedHashMap<String, Pair<Int, Int>>()
        val manifests = ArrayList<GifManifest>()
        val skippedAppVars = ArrayList<String>()

        val candidates = entries.filter {
            it.type == APPVAR_TYPE &&
                it.size <= MAX_SCAN_SIZE &&
                it.tokenName.isNotEmpty()
        }

        for (entry in candidates) {
            try {
                val file = client.downloadVariable(entry)
                val data = appVarData(file)
                val core = lengthPrefixedCore(data) ?: continue
                when {
                    core.startsWithAscii("IM8C") -> {
                        imageNames += entry.name
                        parseIm8cDimensions(core)?.let {
                            imageDimensions[entry.name] = it
                        }
                    }
                    core.startsWithAscii("TIJGIF01") -> {
                        parseGifManifest(entry.name, core)?.let { manifests += it }
                    }
                }
            } catch (t: Throwable) {
                skippedAppVars += entry.name
                log("JACKCAT ignored unreadable AppVar ${entry.name}: ${t.message.orEmpty()}")
            }
        }

        if (skippedAppVars.isNotEmpty()) {
            log(
                "JACKCAT continuing after ${skippedAppVars.size} unreadable AppVar" +
                    if (skippedAppVars.size == 1) "" else "s"
            )
        }

        val media = ArrayList<MediaItem>()
        val consumedFrames = LinkedHashSet<String>()

        for (manifest in manifests) {
            val frames = manifest.frameNames
            if (frames.isEmpty() || !frames.all { it in imageNames }) continue
            val item = mediaItemForFrames(
                manifest.title,
                frames,
                manifest.width,
                manifest.height
            ) ?: continue
            media += item
            consumedFrames += frames
        }

        // Recover a contiguous TI-JACK animation even when its TIJGIF01
        // manifest is missing. Up to 256 frames use the original two-digit hex
        // suffix. Longer sets use two-digit base36 suffixes, still keeping every
        // calculator variable name within eight characters.
        val grouped = imageNames
            .filter {
                it !in consumedFrames &&
                    Regex("^[A-Z][A-Z0-9_]{5}[0-9A-Z]{2}$").matches(it)
            }
            .groupBy { it.take(6) }
        for ((prefix, names) in grouped) {
            val available = names.toHashSet()
            if (prefix + "00" !in available) continue
            val useBase36 = names.any { name ->
                name.takeLast(2).any { it !in '0'..'9' && it !in 'A'..'F' }
            }
            var count = 0
            if (useBase36) {
                while (
                    count < MAX_GIF_FRAMES &&
                    prefix + base36Suffix(count) in available
                ) {
                    count++
                }
            } else {
                while (
                    count < LEGACY_HEX_FRAME_LIMIT &&
                    prefix + "%02X".format(count) in available
                ) {
                    count++
                }
            }
            if (count < 2) continue
            val frames = (0 until count).map {
                prefix + if (useBase36) base36Suffix(it) else "%02X".format(it)
            }
            val dimensions = imageDimensions[frames.first()]
                ?: Pair(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
            media += MediaItem(
                title = prefix,
                base = prefix,
                frameCount = count,
                defaultDelayMs = 0,
                width = dimensions.first,
                height = dimensions.second
            )
            consumedFrames += frames
        }

        for (name in imageNames) {
            if (name !in consumedFrames) {
                val dimensions = imageDimensions[name]
                    ?: Pair(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                media += MediaItem(
                    title = titleFor(name),
                    base = name,
                    frameCount = 1,
                    defaultDelayMs = 0,
                    width = dimensions.first,
                    height = dimensions.second
                )
            }
        }

        val ordered = media.sortedWith(
            compareBy<MediaItem> { it.title.lowercase() }.thenBy { it.base }
        )
        val catalogSource = buildCatalogSource(ordered)
        val jackCat = EvoPythonPackager.build(JACKCAT_NAME, catalogSource)
        changed = syncVariable(client, entries, jackCat, JACKCAT_NAME, log) || changed

        // syncVariable either matched and downloaded the existing JACKCAT or
        // completed a verified upload/read-back. Re-listing here used to invoke
        // catalog sync recursively, so the successful sync itself is the proof.
        require(jackViewPresent) {
            "JACKVIEW is missing from the calculator after catalog sync"
        }

        val skippedText = if (skippedAppVars.isEmpty()) {
            ""
        } else {
            " · skipped AppVars: ${skippedAppVars.joinToString(",")}"
        }
        log(
            "JACKCAT cataloged ${ordered.size} media item" +
                (if (ordered.size == 1) "" else "s") + skippedText
        )
        return changed
    }

    private fun syncVariable(
        client: EvoUsbClient,
        entries: List<EvoEntry>,
        expected: ByteArray,
        label: String,
        log: (String) -> Unit
    ): Boolean {
        val info = EvoFileCodec.inspect(expected)
        val existing = entries.firstOrNull { EvoFileCodec.sameIdentity(info, it) }
        if (existing != null) {
            try {
                if (client.downloadVariable(existing).contentEquals(expected)) {
                    return false
                }
            } catch (t: Throwable) {
                log("$label compare warning: ${t.message.orEmpty()}")
            }
        }

        client.uploadVariable(expected, overwrite = existing != null)
        log(if (existing == null) "$label installed" else "$label updated")
        return true
    }

    private fun appVarData(file: ByteArray): ByteArray {
        EvoFileCodec.inspect(file)
        require(file.size >= 3) { "AppVar file is too short" }
        val body = file.copyOfRange(0, file.size - 2)
        val root = CborLite(body).decode() as? Map<*, *>
            ?: error("AppVar is not a CBOR map")
        return root["data"] as? ByteArray
            ?: error("AppVar has no data payload")
    }

    private fun lengthPrefixedCore(data: ByteArray): ByteArray? {
        if (data.size < 2) return null
        val size = u16le(data, 0)
        if (size <= 0 || size > data.size - 2) return null
        return data.copyOfRange(2, 2 + size)
    }

    private fun parseIm8cDimensions(core: ByteArray): Pair<Int, Int>? {
        if (!core.startsWithAscii("IM8C") || core.size < 10) return null
        val width = u16le(core, 6)
        val height = u16le(core, 8)
        if (width <= 0 || height <= 0) return null
        return Pair(width, height)
    }

    private fun parseGifManifest(title: String, core: ByteArray): GifManifest? {
        if (!core.startsWithAscii("TIJGIF01") || core.size < 20) return null
        val width = u16le(core, 8)
        val height = u16le(core, 10)
        val frameCount = u16le(core, 12)
        if (width <= 0 || height <= 0) return null
        if (frameCount !in 1..MAX_GIF_FRAMES) return null
        if (core.size < 20 + frameCount * 10) return null

        val names = ArrayList<String>(frameCount)
        var offset = 20
        repeat(frameCount) {
            val raw = core.copyOfRange(offset, offset + 8)
            val end = raw.indexOf(0.toByte()).let { if (it < 0) raw.size else it }
            val name = raw.copyOfRange(0, end).toString(Charsets.US_ASCII)
            if (!Regex("^[A-Z][A-Z0-9_]{0,7}$").matches(name)) return null
            names += name
            offset += 10
        }
        return GifManifest(titleFor(title), names, width, height)
    }

    private fun mediaItemForFrames(
        title: String,
        frames: List<String>,
        width: Int,
        height: Int
    ): MediaItem? {
        if (frames.size == 1) {
            return MediaItem(title, frames[0], 1, 0, width, height)
        }
        if (frames.size !in 2..MAX_GIF_FRAMES) return null
        val first = frames[0]
        if (first.length != 8 || first.takeLast(2) != "00") return null
        val prefix = first.take(6)
        val useBase36 = frames.size > LEGACY_HEX_FRAME_LIMIT
        for (i in frames.indices) {
            val suffix = if (useBase36) base36Suffix(i) else "%02X".format(i)
            if (frames[i] != prefix + suffix) return null
        }
        return MediaItem(title, prefix, frames.size, 0, width, height)
    }

    private fun buildCatalogSource(items: List<MediaItem>): String = buildString {
        append("# TI-JACK / JACKVIEW media catalog\n")
        append("# title, image/frame base, frame count, added delay ms, width, height\n")
        append("media=(\n")
        for (item in items) {
            append(" (\"")
            append(item.title)
            append("\",\"")
            append(item.base)
            append("\",")
            append(item.frameCount)
            append(',')
            append(item.defaultDelayMs)
            append(',')
            append(item.width)
            append(',')
            append(item.height)
            append("),\n")
        }
        append(")\n")
    }

    private fun titleFor(name: String): String =
        name.replace('_', ' ').take(24)

    private fun base36Suffix(index: Int): String {
        require(index in 0 until BASE36_FRAME_LIMIT)
        return "" +
            BASE36_DIGITS[index / 36] +
            BASE36_DIGITS[index % 36]
    }

    private fun ByteArray.startsWithAscii(value: String): Boolean {
        val prefix = value.toByteArray(Charsets.US_ASCII)
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }

    private fun u16le(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8)

    /** Hardware-tested JACKVIEW source from the TI-JACK/JACKVIEW handoff. */
    private val jackViewSource = """
from ti_graphics import drawImage
from ti_system import get_key,disp_clr,disp_at
from time import monotonic

UP=25
DOWN=34
LEFT=24
RIGHT=26
ENTER=105
CLEAR=45
B36="0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
VW=320
VH=210
VY=30

try:
 from JACKCAT import media
except:
 media=()

def released():
 while get_key(0)!=0:
  pass

def b36(i):
 return B36[i//36]+B36[i%36]

def frame_names(base,count):
 if count==1:
  return [base]
 out=[]
 i=0
 while i<count:
  if count>256:
   s=b36(i)
  else:
   s=hex(i)[2:].upper()
   if len(s)<2:
    s="0"+s
  out.append(base+s)
  i+=1
 return out

def media_pos(item):
 w=VW
 h=VH
 if len(item)>5:
  w=item[4]
  h=item[5]
 x=(VW-w)//2
 y=VY+(VH-h)//2
 if x<0:x=0
 if y<VY:y=VY
 return x,y

def browser(sel):
 n=len(media)
 while True:
  disp_clr()
  disp_at(1,"JACKVIEW","center")
  if n==0:
   disp_at(5,"(NO MEDIA)","center")
   disp_at(11,"CLEAR EXIT","center")
   k=get_key(1)
   released()
   if k==CLEAR:
    return -1
   continue
  disp_at(2,"UP/DOWN  ENTER","center")
  start=sel-3
  if start<0:start=0
  if start>n-7:start=n-7
  if start<0:start=0
  row=4
  i=start
  while i<n and row<11:
   kind="GIF" if media[i][2]>1 else "IMG"
   mark=">" if i==sel else " "
   disp_at(row,mark+media[i][0]+" ["+kind+"]","left")
   row+=1
   i+=1
  disp_at(11,"CLEAR EXIT","center")
  k=get_key(1)
  released()
  if k==UP:
   sel-=1
   if sel<0:sel=n-1
  elif k==DOWN:
   sel+=1
   if sel==n:sel=0
  elif k==ENTER:
   return sel
  elif k==CLEAR:
   return -1

def wait_delay(delay,last):
 if delay<=0:
  return delay,last,0
 end=monotonic()+delay
 while monotonic()<end:
  k=get_key(0)
  if k!=last:
   if k==UP:
    delay-=.005
    if delay<0:delay=0
    end=monotonic()+delay
   elif k==DOWN:
    delay+=.005
    if delay>.5:delay=.5
    end=monotonic()+delay
   elif k==LEFT:
    return delay,k,-1
   elif k==RIGHT:
    return delay,k,1
   elif k==ENTER:
    released()
    while True:
     p=get_key(1)
     released()
     if p==ENTER:break
     if p==CLEAR:return delay,0,2
   elif k==CLEAR:
    return delay,k,2
  last=k
 return delay,last,0

def show_item(sel,delay):
 item=media[sel]
 title=item[0]
 base=item[1]
 count=item[2]
 default_delay=item[3]
 x,y=media_pos(item)
 if delay<0:
  delay=default_delay/1000
 frames=frame_names(base,count)

 # Clear once when a new still/animation is opened. GIF frames are then drawn
 # without clearing between frames to avoid flicker and preserve playback speed.
 disp_clr()

 if count==1:
  drawImage(frames[0],x,y)
  while True:
   k=get_key(1)
   released()
   if k==LEFT:return sel-1,delay
   if k==RIGHT:return sel+1,delay
   if k==CLEAR:return sel,delay

 i=0
 last=0
 while True:
  drawImage(frames[i],x,y)
  i+=1
  if i==count:i=0

  k=get_key(0)
  if k!=last:
   if k==UP:
    delay-=.005
    if delay<0:delay=0
   elif k==DOWN:
    delay+=.005
    if delay>.5:delay=.5
   elif k==LEFT:
    return sel-1,delay
   elif k==RIGHT:
    return sel+1,delay
   elif k==ENTER:
    released()
    while True:
     p=get_key(1)
     released()
     if p==ENTER:break
     if p==CLEAR:return sel,delay
   elif k==CLEAR:
    return sel,delay
  last=k

  if delay>0:
   delay,last,action=wait_delay(delay,last)
   if action==-1:return sel-1,delay
   if action==1:return sel+1,delay
   if action==2:return sel,delay

sel=0
delay=-1
while True:
 sel=browser(sel)
 if sel<0:break
 while True:
  nxt,delay=show_item(sel,delay)
  if nxt==sel:
   break
  sel=nxt
  if sel<0:sel=len(media)-1
  if sel==len(media):sel=0
""".trimIndent() + "\n"
}
