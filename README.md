<p align="center">
  <img src="docs/ti-jack-logo.svg" alt="TI-JACK — Universal file transfer for TI calculators" width="900">
</p>

# TI-JACK

**Universal file transfer for TI calculators.**

TI-JACK is an open-source file transfer and compatibility project for Texas Instruments calculators. The current Android transport targets the **TI-84 Evo** USB protocol, while transfer, conversion, validation, and conflict handling are kept separate so additional TI families can be added later.

## Current status

**Android v0.18**

The Evo transfer path works on real hardware in both directions. TI-JACK can browse calculator variables, transfer multiple files, replace or skip duplicates, delete files on either side, and verify uploads by downloading them back from the calculator. Legacy TI-BASIC conversion and media preparation are active compatibility previews.

| Capability | Status |
| --- | --- |
| Detect TI-84 Evo over Android USB host | Working |
| Read calculator directory | Working |
| Calculator → Android transfer | Working |
| Android → calculator transfer | Working |
| Multiple-file selection | Working |
| Replace / skip existing files | Working |
| Android-side deletion | Working |
| Calculator-side deletion with verification | Working |
| Upload read-back verification | Working |
| RAM / Archive display | Working |
| Android folder picker | Working |
| Legacy TI-BASIC `.8xp` → Evo `.8xp2` | Preview; hardware-tested with SNAKE |
| Classic CE canvas centering / compatibility fixes | Preview; hardware-tested with SNAKE |
| Named PNG / JPG / JPEG / WebP → IM8C `.8xv2` | Preview; ready for hardware/viewer testing |
| Animated GIF → IM8C frames + animation manifest | Preview; ready for hardware/viewer testing |
| Explicit `Image1`–`Image7` graph-background import | Implemented |
| Additional calculator families | Planned |

## Hardware setup

TI-JACK needs Android USB host/OTG mode. The reliable tested connection is:

```text
Phone → USB-C OTG/host adapter → USB-A-to-USB-C data cable → TI-84 Evo
```

The OTG adapter belongs on the **phone side**. No external power is required for the tested setup.

On the tested Samsung phone, a direct USB-C-to-USB-C cable normally puts the phone in the wrong USB data role. Manually changing **USB controlled by** can work, but repeated role switching can make the phone's USB controls stop responding until reboot. TI-JACK cannot force the USB-C role from a normal Android app, so the OTG/host adapter is the recommended setup.

### Requirements

- Android 10 or newer (`minSdk 29`)
- USB host / OTG support
- A supported calculator
- A known-good USB data cable

## Using the Android app

1. Connect the calculator through the phone-side OTG/host adapter and wait for **TI-84 EVO CONNECTED**.
2. Tap **CHOOSE FOLDER** and select the Android folder containing calculator files or media.
3. Select one or more files in either pane.
4. Tap **TRANSMIT** in the desired direction.
5. If the destination already contains a variable, choose **REPLACE** or **SKIP EXISTING**.
6. Use **DELETE**, **SELECT ALL**, and **CLEAR** as needed.
7. Tap **?** for the installed version, connection tips, and conversion notes.

Do not unplug during an active transfer. Normal disconnect/reconnect is automatic after a transfer completes.

## File handling

### Native Evo files

Native Evo variables are validated and transferred without conversion. Current recognized suffixes include:

```text
.8xn2 .8xl2 .8xp2 .8xd2 .8ci2 .8ca2 .8xm2 .8xy2
.8xv2 .8xs2 .8xw2 .8xz2 .8xt2 .8xpy2 .8mp2
```

### Legacy TI-BASIC programs

Legacy `.8xp` conversion is implemented in pure Kotlin; the Android APK contains no native conversion library, JNI bridge, or NDK component.

The converter is intentionally fail-closed. It verifies the legacy TI file structure and checksum, maps only tokens TI-JACK knows how to convert, rebuilds a native Evo program container, validates the result, and refuses unknown or unsafe constructs instead of guessing.

For programs built around the classic **265 × 165** color-calculator graph area, TI-JACK can preserve the original logical canvas rather than stretching it across the Evo display. When that layout is positively identified, the converter currently centers the old canvas, keeps graph-coordinate drawing in the original coordinate system, offsets pixel-based coordinates consistently, and emulates unsupported CE-only visual behavior where it can do so safely.

These transforms are deliberately narrow. If TI-JACK cannot identify a layout or command safely, conversion is refused rather than silently changing program behavior.

### Viewer media

Ordinary still images now default to **named Evo IM8C AppVars (`.8xv2`)** rather than consuming a numbered graph-background slot. A file such as:

```text
FMRLOGO.png
```

becomes a calculator AppVar named `FMRLOGO` when that name fits the Evo naming rules. Longer or unsuitable filenames are shortened deterministically.

TI-JACK fits still images within **320 × 210**, preserves aspect ratio, uses up to 256 indexed RGB565 colors, keeps one-bit transparency when present, and chooses indexed or RLE IM8C storage based on size. The result is a standard IM8C image AppVar, which also gives the future viewer a simple native image primitive rather than a TI-JACK-only pixel format.

On an Evo Python environment with `ti_graphics`, a static test image can be exercised independently of the future viewer, for example:

```python
from ti_graphics import drawImage
from ti_system import disp_wait

drawImage("FMRLOGO", 0, 30)
disp_wait()
```

Animated GIFs are decoded on Android before transfer. TI-JACK preserves the composited frame sequence, timing, and loop metadata, then sends one IM8C AppVar per frame plus a small **`TIJGIF01` manifest AppVar** describing frame order and delays. Frames are sent first and the manifest last. The current preview caps a GIF at 120 frames and 6 MiB of generated calculator variables.

The manifest is intentionally documented so the separate calculator viewer project can consume it without depending on TI-JACK internals. See [`docs/TIJ_GIF_MANIFEST.md`](docs/TIJ_GIF_MANIFEST.md).

If a still source is explicitly named `Image1` through `Image7` (or `Img1` through `Img7`), TI-JACK keeps the existing graph-background behavior and creates that `.8ca2` image slot instead.

## Transfer integrity

Android → calculator transfers are not considered successful merely because the calculator acknowledges the USB session. TI-JACK requires the complete path to succeed:

```text
recognize → validate/convert → transmit → reread directory → download back → compare bytes
```

For converted files, validation happens before USB transmission. Calculator-side deletion is also verified by rereading the directory and confirming the variable is gone.

## Evo USB protocol

The current hardware target is:

```text
VID: 0451
PID: E018
Transport: CDC/ACM, 115200 baud
```

Uploads use the observed Kermit `S/F/A/D*/Z/B` exchange. Directory, upload, download, delete, framing, and verification details are documented in [`PROTOCOL_NOTES.md`](PROTOCOL_NOTES.md).

## Building

Open the repository in Android Studio or build with Gradle.

Current Android configuration:

```text
compileSdk / targetSdk: 35
minSdk: 29
Java / Kotlin target: 17
usb-serial-for-android: 3.11.0
androidx.documentfile: 1.0.1
```

The app does not need Internet access for file conversion or calculator transfers.

GitHub Actions builds every push to `main` with:

```text
.github/workflows/build-debug-apk.yml
```

The current artifact is named:

```text
TI-JACK-Evo-Android-v0.18
```

## Known limitations

- The Android USB transport currently targets the TI-84 Evo; other calculator transports are not implemented yet.
- Legacy `.8xp` conversion does not yet cover every TI-BASIC token or every CE/Evo behavioral difference.
- The current pixel-exact SNAKE compatibility pass removes turn artifacts but has been observed to make movement slower; that optimization remains active work.
- The new viewer-media path is a preview awaiting real-hardware validation with named IM8C images and prepared GIFs.
- GIF playback requires the separate calculator-side viewer project; TI-JACK prepares and transfers the media but does not run the viewer on the calculator.
- Direct USB-C-to-USB-C behavior depends on the phone's USB role; use a phone-side OTG/host adapter for the tested reliable setup.

## Project direction

TI-JACK is intended to become one transfer application rather than a separate utility for every TI calculator generation. File selection, duplicate handling, conversion, deletion, validation, and verification are kept separate from calculator-specific transports so new calculator families can be added underneath the same workflow.

Near-term work is focused on hardware-testing the new viewer-media output, optimizing CE program fidelity/performance, expanding legacy token coverage from real programs, and adding more TI calculator protocols. The calculator-side media viewer is intentionally a separate software project; TI-JACK's role is to prepare and transfer compatible media for it.

## Credits

TI-JACK project: **Jawatech / jeremymfrank**.

Android USB serial support is provided by the MIT-licensed `usb-serial-for-android` project. Evo file/token research was cross-checked against Adrien "Adriweb" Bertrand's MIT-licensed `tivars_lib_cpp`. IM8C behavior was independently implemented from public format information and cross-checked against TI-Planet's `img2calc` research; no `img2calc` source is bundled in the APK. See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

TI-JACK is an independent project and is not affiliated with or endorsed by Texas Instruments.
