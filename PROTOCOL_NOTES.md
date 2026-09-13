# Evo Android protocol notes

TI-JACK currently targets the TI-84 Evo USB device:

- TI vendor ID: `0x0451`
- Evo product ID: `0xE018`
- CDC/ACM serial transport: 115200 baud

## Directory

The directory transaction uses Kermit framing and requests:

```text
hh01/get/hh01/inf/res?name=directory&gotohome=1
```

The returned Kermit data stream is decoded and parsed as CBOR. The directory `data` array supplies the tokenized/display name, variable type, size, and RAM/archive location.

The currently observed directory payload does **not** expose the same exact `RAM FREE` / `ARC FREE` counters shown by the calculator's Memory screen. v0.21.0 therefore treats free-space values in the Android UI as conservative estimates calculated from directory entries rather than protocol-provided counters.

## Download

Variable downloads use:

```text
hh01/get/hh01/xfr/var?name=<token-name>&type=<type>
```

The calculator returns the Evo CBOR body. TI-JACK appends and validates the Evo checksum before saving the file on Android.

## Upload

Variable uploads use:

```text
hh01/xfr/var?name=<token-name>&type=<type>&memtarget=<0|1>&policy=<0|1>
```

Observed `memtarget` behavior:

```text
memtarget=0  -> RAM
memtarget=1  -> Archive
```

The observed upload transaction is:

```text
S / F / A / D* / Z / B
```

with a `Y` acknowledgment for each outbound Kermit packet. The uploaded payload is the complete checksum-bearing Evo variable file.

TI-JACK does not treat the transport acknowledgment alone as success. After upload it:

1. re-reads the calculator directory,
2. finds the expected variable by type and token identity,
3. downloads the variable back,
4. requires the downloaded bytes to match the transmitted bytes.

JACKVIEW media is explicitly written with `memtarget=1` in v0.21.0 so multi-frame animation data does not consume RAM first. Native uploads without an explicit target retain the prior behavior: use their normal target and, when a RAM write is rejected as a memory/data-target failure, retry in Archive.

## RAM / Archive move

v0.21.0 moves an existing variable by reusing the verified upload transaction instead of using a delete-first sequence:

```text
download current variable
  -> validate identity and bytes
  -> upload same bytes with policy=1
  -> set memtarget=0 (RAM) or memtarget=1 (Archive)
  -> reread directory
  -> verify final memory location
  -> download again
  -> byte-for-byte compare
```

Using `policy=1` allows the variable with the same identity to be replaced in place while `memtarget` changes its memory location. TI-JACK reports the move as successful only after the directory location and read-back bytes both verify.

This avoids deleting the only copy before the destination-memory write is known to have succeeded.

## Delete

Calculator-side delete uses the Evo variable-delete transaction implemented in `EvoUsbClient.deleteVariable()`. The app verifies deletion by re-reading the directory and requiring the target identity to be absent before reporting success.

## v0.17+ conversion layer

Conversion stays above the USB protocol. The wire transaction remains the same whether the source was already an Evo file or was converted on the phone.

```text
source file
  → native Evo validation OR supported conversion
  → Evo CBOR/checksum validation
  → existing upload transaction
  → directory verification
  → byte-for-byte read-back verification
```

### Pure-Kotlin legacy TI-BASIC preview

v0.17 replaces the v0.16 native converter experiment with a pure-Kotlin `.8xp` path. The legacy parser verifies the TI file signature, section lengths, single-entry program type, token-data length, and legacy checksum before token conversion starts.

The converter is intentionally fail-closed: an unknown/unverified legacy token aborts conversion instead of becoming `?` or being sent raw. Successful output is written as an Evo type-2 program container with:

- metadata type `2`
- metadata version `1`
- flags `0`
- tokenized program name terminated by `0000`
- body version `1`
- `arraylen` equal to the number of 16-bit token words
- `size` equal to `arraylen * 2`
- little-endian 16-bit program tokens terminated by `TOK_EOS` (`0000`)

The generated file is passed through `EvoFileCodec.inspect()` before upload.

### Fidelity pass for classic CE geometry

The first real-hardware compatibility target, `SNAKE.8xp`, explicitly sets:

```text
0   -> Xmin
264 -> Xmax
0   -> Ymin
164 -> Ymax
```

That identifies a 265 × 165 hard-coded CE logical canvas. Stretching that canvas over the Evo's larger graph area would change line geometry, text placement, and pixel collision tests. v0.17 therefore centers it by changing the Evo graph window to:

```text
-27 -> Xmin
291 -> Xmax
-22 -> Ymin
186 -> Ymax
```

This preserves one logical graph unit per display pixel while leaving a 27-pixel horizontal and 22-pixel vertical margin. `Text(` row/column arguments and the tested `pxl-Test(` coordinates receive matching `+22` / `+27` offsets. Graph-coordinate commands such as `Line(`, `Horizontal`, and `Pt-On(`/`Pt-Off(` remain in their original legacy coordinate system.

The transformation is only enabled when all four exact legacy window assignments are present. TI-JACK does not assume every program should be centered.

### BorderColor compatibility

The Evo token table still contains `TOK_BORDER_COLOR` (`E5BA`), but real-hardware testing showed a converted `BorderColor 2` statement produces `SYNTAX ERROR`. The Evo has no CE-style physical graph border corresponding to that command.

v0.17 first tried prefixing the line with an apostrophe, which hardware testing showed was not an executable comment. v0.17.1 removed the unsupported token entirely and replaced it with a quoted source note. That stopped the syntax error but did not preserve the visual behavior, and a standalone string can also affect `Ans`.

v0.17.2 therefore treats `BorderColor` as a visual compatibility transform instead of a source note. When TI-JACK has positively identified the classic 265 × 165 CE canvas, a standalone `BorderColor 1`–`4` line is replaced by eight normal Evo `Line(` commands: two one-pixel rectangles immediately outside the legacy `0..264 × 0..164` drawing area. The frame lives entirely in the new centered margins, so it does not overwrite the program's original drawing coordinates.

Drawable-color mapping is conservative:

- CE border 1 (Light Gray) → Evo draw color 21 (LTGRAY)
- CE border 2 (Snowy Mint / Light Madang) → Evo draw color 21 (LTGRAY approximation)
- CE border 3 (Light Blue) → Evo draw color 18 (LTBLUE)
- CE border 4 (White) → Evo draw color 20 (WHITE)

CE border color 2 is a special border-only color and has no ordinary TI-BASIC draw-color equivalent, so exact color reproduction is not possible through normal `Line(` drawing. TI-JACK preserves geometry and uses the closest conservative palette approximation rather than pretending the color is exact.

If `BorderColor` appears in a compound expression or in a program whose legacy canvas cannot be identified safely, conversion is refused instead of silently deleting the feature.

### Evo background image container

Ordinary PNG/JPEG/WebP images are converted locally into type-5 `.8ca2` Evo background-image variables. The generated image uses:

- metadata version `1`
- metadata flags `1`
- Image1–Image7 token words `E8B0`–`E8B6`
- body version `1`
- data length `33601`
- format marker byte `0x16`
- `160 × 105` RGB565 pixels, little-endian
- rows stored bottom-to-top

The generated CBOR body receives the Evo XOR checksum and is passed through `EvoFileCodec.inspect()` before transmission.
