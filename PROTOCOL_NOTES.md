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

## Delete

Calculator-side delete uses the Evo variable-delete transaction implemented in `EvoUsbClient.deleteVariable()`. The app verifies deletion by re-reading the directory and requiring the target identity to be absent before reporting success.

## v0.17 conversion layer

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

### Removed Evo commands

The Evo token table still contains `TOK_BORDER_COLOR` (`E5BA`), but real-hardware testing showed a converted `BorderColor 2` statement produces `SYNTAX ERROR`. The Evo has no physical graph border corresponding to the older color-calculator feature.

v0.17 does not silently erase the statement. A standalone `BorderColor` line is retained as a TI-BASIC comment by prefixing `TOK_APOST` and a space. If `BorderColor` appears inside a compound statement, conversion is refused until a safe transformation is implemented.

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
