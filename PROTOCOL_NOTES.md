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

## v0.16 conversion layer

Conversion is deliberately above the USB protocol. The wire transaction remains the same whether the source was already an Evo file or was converted on the phone.

The v0.16 Android path is:

```text
source file
  → native Evo validation OR supported conversion
  → Evo CBOR/checksum validation
  → existing upload transaction
  → directory verification
  → byte-for-byte read-back verification
```

Legacy TI variables are converted locally using the pinned Evo-capable `tivars_lib_cpp` engine. Ordinary PNG/JPEG/WebP images are converted locally into type-5 `.8ca2` Evo background-image variables. Unsupported or failed conversions never reach the USB upload method.

### Evo background image container

The generated type-5 background image uses:

- metadata version `1`
- metadata flags `1`
- Image1–Image7 token words `E8B0`–`E8B6`
- body version `1`
- data length `33601`
- format marker byte `0x16`
- `160 × 105` RGB565 pixels, little-endian
- rows stored bottom-to-top

The generated CBOR body receives the same Evo XOR checksum used by calculator-exported files and is passed through `EvoFileCodec.inspect()` before transmission.
