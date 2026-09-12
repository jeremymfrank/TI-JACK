# TI-JACK viewer media format

TI-JACK can prepare ordinary phone/computer images for a separate TI-84 Evo viewer without consuming the calculator's numbered graph-background slots.

## Static images

PNG, JPG/JPEG, and WebP sources that are **not** explicitly named `Image1` through `Image7` are converted to a standard Evo AppVar (`.8xv2`, type 8) containing an **IM8C** image.

The AppVar name is derived from the source filename and is limited to eight calculator-safe characters. For example:

```text
FMRLOGO.png -> FMRLOGO
```

The AppVar data is:

```text
u16le  im8c_core_length
bytes  im8c_core
```

The IM8C core is:

```text
char[4] "IM8C"
u16le   mode             # 1 = indexed, 2 = RLE
u16le   width
u16le   height
u8      has_transparency # 0 or 1
u8      transparent_index
u16le   palette_count    # 1..256
u16le[] RGB565 palette
bytes    pixel_stream
```

For indexed mode, `pixel_stream` is one palette-index byte per pixel.

For RLE mode:

- control `0x00..0x7F`: literal packet of `control + 1` following palette-index bytes;
- control `0x80..0xFE`: repeated packet of `(control - 0x80) + 2` pixels, followed by one palette-index byte.

TI-JACK chooses indexed or RLE storage per image based on which is smaller. Images with at most 256 colors keep their RGB565 palette directly. Richer images use a deterministic reduced palette. Transparency is reduced to one transparent palette index.

The current converter preserves aspect ratio, never upscales, and fits media within 320 x 210.

## Animated GIFs

A GIF is prepared as:

1. one standard IM8C `.8xv2` AppVar per composited frame; and
2. one small TI-JACK manifest AppVar, transferred **last**.

The manifest's calculator variable name is derived from the GIF filename. Frame names are deterministic eight-character calculator names beginning with `J` (or `K` only if needed to avoid a name collision).

The manifest AppVar data is:

```text
u16le  manifest_core_length
bytes  manifest_core
```

Manifest core version 1:

```text
char[8] "TIJGIF01"
u16le   width
u16le   height
u16le   frame_count
u16le   loop_count       # raw GIF/Netscape repeat count; 0 = infinite
u32le   total_duration_ms
repeat frame_count times:
    char[8] frame_appvar_name # ASCII, NUL padded
    u16le   delay_ms
```

A future viewer should identify an AppVar by inspecting the bytes after the two-byte length prefix:

- `IM8C` => directly displayable static/frame image;
- `TIJGIF01` => animation manifest referencing IM8C frame AppVars.

TI-JACK preserves GIF frame order, composited appearance, frame delays, and the Netscape loop count where present. Zero/unspecified frame delays are normalized for practical playback. The current preparation limit is 120 frames and 6 MiB of generated calculator variables per GIF.

Frames are transmitted before the manifest so an interrupted transfer does not leave a valid manifest pointing at frames that were never sent.

## Graph-background compatibility

The older graph-background import path remains available deliberately and explicitly: name a still source `Image1.png` through `Image7.png` (or `Img1` through `Img7`) to create the corresponding Evo `.8ca2` background variable instead of viewer media.

## Implementation note

The IM8C layout was independently implemented in TI-JACK from public Evo format behavior and cross-checked against TI-Planet's `img2calc` research. TI-JACK does not copy or bundle `img2calc` code.
