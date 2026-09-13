# TI-JACK viewer media format

TI-JACK can prepare ordinary phone/computer images for JACKVIEW without consuming the calculator's numbered graph-background slots.

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

The converter preserves aspect ratio and scales media up or down toward the largest fit inside the 320 x 210 JACKVIEW viewport. It does not intentionally stretch an image to a different aspect ratio or crop it merely to fill both dimensions. If the resulting IM8C payload is too large, TI-JACK retries at smaller dimensions until the file fits the current format limits.

## Animated GIFs

A GIF is prepared as:

1. one standard IM8C `.8xv2` AppVar per composited frame; and
2. one small TI-JACK manifest AppVar, transferred **last**.

The manifest's calculator variable name is derived from the GIF filename. Frame names are deterministic eight-character calculator names beginning with `J` (or `K` only if needed to avoid a name collision).

### Frame limit

TI-JACK accepts GIFs with more than 300 source frames. It does not reject them solely because of frame count. Instead, it clips the animation to the **first 300 frames** and transfers those frames with their original order and delays. Frames after 300 are omitted.

The current generated-media budget remains 6 MiB per GIF. If needed, TI-JACK progressively reduces the animation resolution to fit that budget.

### Frame naming

The Evo variable-name limit is eight characters, so TI-JACK uses a six-character frame prefix plus a two-character suffix.

- animations with 1-256 frames use the original two-digit hexadecimal suffixes: `00` through `FF`;
- animations with 257-300 frames use two-digit base36 suffixes using `0-9A-Z`.

JACKVIEW chooses the matching suffix scheme from the frame count stored in JACKCAT. The `TIJGIF01` manifest always contains the exact frame names, so external tools should read those names directly rather than recreate them.

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

A viewer identifies an AppVar by inspecting the bytes after the two-byte length prefix:

- `IM8C` => directly displayable static/frame image;
- `TIJGIF01` => animation manifest referencing IM8C frame AppVars.

TI-JACK preserves the order, composited appearance, delays, and loop metadata for every retained frame. Zero/unspecified frame delays are normalized for practical playback.

Frames are transmitted before the manifest so an interrupted transfer does not leave a valid manifest pointing at frames that were never sent.

## JACKCAT

`JACKCAT.8xpy2` is generated from viewer-compatible media found on the calculator. Static images are cataloged by exact AppVar name. GIF manifests are cataloged as one animation entry instead of exposing every frame individually.

Current JACKCAT entries use six values:

```text
(title, base_name_or_frame_prefix, frame_count, added_delay_ms, width, height)
```

The width and height come from the verified IM8C image or GIF manifest. JACKVIEW uses those dimensions to center the prepared media in its 320 x 210 viewport. JACKVIEW remains backward-compatible with older four-value catalog entries and uses the old top-left placement when dimensions are unavailable.

When a manifest is missing, TI-JACK can recover contiguous TI-JACK frame sets using the legacy hexadecimal naming scheme or the extended base36 naming scheme. The dimensions of the first verified frame are used for centering the recovered animation.

JACKVIEW clears the previous media item before opening the next still image or animation. It does not clear between every GIF frame, avoiding unnecessary flicker and preserving playback speed.

## Graph-background compatibility

The graph-background import path remains available deliberately and explicitly: name a still source `Image1.png` through `Image7.png` (or `Img1` through `Img7`) to create the corresponding Evo `.8ca2` background variable instead of JACKVIEW media.

## Implementation note

The IM8C layout was independently implemented in TI-JACK from public Evo format behavior and cross-checked against TI-Planet's `img2calc` research. TI-JACK does not copy or bundle `img2calc` code.
