<p align="center">
  <img src="docs/ti-jack-logo.svg" alt="TI-JACK — Universal file transfer for TI calculators" width="900">
</p>

# TI-JACK

**Universal file transfer for TI calculators.**

TI-JACK is an open-source file transfer and compatibility project for Texas Instruments calculators. The current Android transport targets the **TI-84 Evo** USB protocol, while the app and conversion layers are being built so additional TI families can use the same interface later.

## Current status

**Android v0.17.3**

The Evo transfer path is working on real hardware in both directions. TI-JACK can browse calculator variables, move multiple files, resolve duplicates, delete files on either side, and verify uploads by downloading them back from the calculator. Legacy TI-BASIC conversion and phone-image import are available as compatibility previews.

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
| PNG / JPG / JPEG / WebP → Evo background image | Implemented; hardware validation continuing |
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
2. Tap **CHOOSE FOLDER** and select the Android folder containing calculator files.
3. Select one or more files in either pane.
4. Tap **TRANSMIT** in the desired direction.
5. If a destination already contains a file, choose **REPLACE** or **SKIP EXISTING**.
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

For programs built around the classic **265 × 165** color-calculator graph area, TI-JACK can preserve the original logical canvas rather than stretching it across the Evo display. When that layout is positively identified, the converter currently applies these compatibility rules:

- centers the original `0..264 × 0..164` logical canvas on the Evo;
- offsets pixel-based text and collision coordinates by the same margins;
- keeps graph-coordinate drawing in the original coordinate system;
- emulates standalone CE `BorderColor 1`–`4` outside the original canvas because Evo does not execute `BorderColor`;
- converts CE Dot-Thick `Pt-On(...,1,...)` / `Pt-Off(...,1)` drawing to matching explicit **3 × 3** pixel footprints so drawing and erasing rasterize identically on Evo.

These transforms are deliberately narrow. If TI-JACK cannot identify a layout or command safely, conversion is refused rather than silently changing program behavior.

### Image import

PNG, JPG/JPEG, and WebP files can be converted locally into Evo **Image1–Image7** background-image variables (`.8ca2`). The image converter fits the source into the Evo 160 × 105 background canvas, preserves aspect ratio, converts to RGB565, writes the expected row order, and validates the generated Evo container before transmission.

A source named like `Image3.jpg` prefers **Image3**. Otherwise TI-JACK chooses an available image slot. Existing image variables use the normal **REPLACE / SKIP EXISTING** flow.

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
TI-JACK-Evo-Android-v0.17.3
```

## Known limitations

- The Android USB transport currently targets the TI-84 Evo; other calculator transports are not implemented yet.
- Legacy `.8xp` conversion does not yet cover every TI-BASIC token or every CE/Evo behavioral difference.
- CE `BorderColor 2` uses a border-only color with no exact normal Evo drawing-color equivalent, so its emulated frame uses a conservative approximation.
- Image import is implemented but is still receiving real-hardware validation.
- Direct USB-C-to-USB-C behavior depends on the phone's USB role; use a phone-side OTG/host adapter for the tested reliable setup.

## Project direction

TI-JACK is intended to become one transfer application rather than a separate utility for every TI calculator generation. File selection, duplicate handling, conversion, deletion, validation, and verification are kept separate from calculator-specific transports so new calculator families can be added underneath the same workflow.

Near-term work is focused on expanding legacy conversion from real program tests, improving visual/behavioral fidelity, completing image-import validation, and adding more TI calculator protocols.

## Credits

TI-JACK project: **Jawatech / jeremymfrank**.

Android USB serial support is provided by the MIT-licensed `usb-serial-for-android` project. Evo file/token research was cross-checked against Adrien "Adriweb" Bertrand's MIT-licensed `tivars_lib_cpp`; the current Android APK does **not** bundle that native library. See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

TI-JACK is an independent project and is not affiliated with or endorsed by Texas Instruments.
