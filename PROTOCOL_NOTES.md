# Evo Android protocol notes

This test targets the USB device:

- TI vendor ID: `0x0451`
- Evo product ID: `0xE018`

The calculator exposes a CDC-style serial connection and the tested desktop
transport uses 115200 baud.

The read-only directory transaction uses Kermit framing and requests the Evo
resource:

    hh01/get/hh01/inf/res?name=directory&gotohome=1

The returned Kermit data stream is decoded and then parsed as CBOR. The
directory `data` array supplies fields including tokenized/display name,
variable type, size, and memory location.

The app implements only enough Kermit and CBOR for this directory test.
No calculator write operation is implemented in v0.1.
