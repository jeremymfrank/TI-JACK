<p align="center">
  <img src="docs/ti-jack-logo.svg" alt="TI-JACK — Universal file transfer for TI calculators" width="900">
</p>

# TI-JACK

**Universal file transfer and compatibility tools for TI calculators.**

TI-JACK is an Android-first project for moving files between a phone and TI calculators, converting compatible legacy files when needed, and verifying what actually reached the calculator. The current transport target is the **TI-84 Evo**.

Current Android version: **v0.20.1**

## What works today

TI-JACK can connect to a TI-84 Evo over USB host/OTG, browse calculator variables, transfer files in either direction, select multiple files, replace or skip duplicates, delete files, and verify uploads by reading them back byte-for-byte.

It also includes compatibility work beyond simple file copying:

- pure-Kotlin legacy `.8xp` to Evo `.8xp2` conversion for supported TI-BASIC programs;
- named image conversion for JACKVIEW;
- animated GIF preparation for JACKVIEW;
- automatic JACKVIEW installation/update;
- automatic JACKCAT generation from viewer-compatible media found on the calculator;
- automatic viewer sizing/centering metadata in JACKCAT;
- explicit `Image1`-`Image7` graph-background import for users who want the calculator's numbered image slots.

## Hardware setup

The reliable tested connection is:

```text
Android phone -> USB-C OTG/host adapter -> USB-A to USB-C data cable -> TI-84 Evo
```

The OTG adapter belongs on the **phone side**.

A direct USB-C-to-USB-C cable may leave some phones in the wrong USB role. TI-JACK cannot force Android's USB-C role from a normal app, so the host/OTG adapter is the recommended setup.

Requirements:

- Android 10 or newer (`minSdk 29`)
- USB host/OTG support
- known-good USB data cable
- TI-84 Evo for the current transport implementation

## Basic use

1. Connect the calculator through the phone-side OTG adapter.
2. Wait for TI-JACK to detect the Evo.
3. Choose an Android folder.
4. Select one or more files on either side.
5. Tap **TRANSMIT** in the direction you want.
6. Choose **REPLACE** or **SKIP EXISTING** when a destination conflict exists.
7. Use **DELETE**, **SELECT ALL**, and **CLEAR** as needed.

TI-JACK rereads the calculator directory after write operations and verifies uploaded variables by downloading them back.

## Native Evo files

Recognized Evo suffixes currently include:

```text
.8xn2 .8xl2 .8xp2 .8xd2 .8ci2 .8ca2 .8xm2 .8xy2
.8xv2 .8xs2 .8xw2 .8xz2 .8xt2 .8xpy2 .8mp2
```

Native Evo variables are validated and transferred without conversion.

## Legacy TI-BASIC conversion

Legacy `.8xp` conversion is implemented in pure Kotlin. TI-JACK parses the source file, validates its structure/checksum, maps supported tokens to Evo equivalents, rebuilds a native Evo program container, validates that result, and only then transmits it.

The converter is intentionally conservative. It does **not** silently delete unsupported commands just to make a program run. When TI-JACK can safely translate or emulate a behavior, it does so. When it cannot, conversion is refused instead of guessing.

For programs that clearly use the classic 265 x 165 color-calculator drawing area, TI-JACK can preserve that logical layout on the Evo. Current compatibility work includes centered classic-canvas handling, pixel-coordinate adjustment, and narrow visual emulation for known CE/Evo differences.

## JACKVIEW media

**JACKVIEW** is the calculator-side TI Python browser/player bundled with TI-JACK. TI-JACK handles media preparation on Android, while JACKVIEW uses the Evo image-drawing path.

**JACKCAT** is the generated TI Python catalog JACKVIEW imports. TI-JACK rebuilds JACKCAT from viewer-compatible media present on the calculator rather than treating the catalog as the source of truth.

The normal flow is:

```text
source image/GIF
    -> TI-JACK converts and transmits media AppVars
    -> TI-JACK scans calculator media
    -> TI-JACK regenerates JACKCAT when needed
    -> JACKVIEW reads JACKCAT
```

Real-calculator testing has confirmed named still-image display, NYAN CAT animation playback, and the current automatic JACKCAT update path on the tested media set.

### Viewer layout and scaling

JACKVIEW uses a **320 x 210** media viewport beginning below the Python header area.

For new viewer-media imports, TI-JACK now scales images and GIF frames **up or down** to the largest size that fits inside that viewport while preserving the source aspect ratio. It does not stretch the image to a different aspect ratio and does not crop simply to fill both dimensions.

Examples:

```text
100 x 100   -> 210 x 210, centered horizontally
1920 x 1080 -> 320 x 180, centered vertically
800 x 1200  -> 140 x 210, centered horizontally
```

JACKCAT records the prepared media width and height. JACKVIEW uses those values to center the image or animation in the 320 x 210 viewport. Older four-field JACKCAT entries remain readable; they simply fall back to the original top-left behavior until TI-JACK regenerates the catalog.

When switching from one media item to another, JACKVIEW clears the previous screen before drawing the new item. Animated GIF frames are **not** cleared between every frame, avoiding unnecessary flicker and preserving playback speed.

Existing AppVars are not resampled in place. To get the new full-viewport scaling on an image or GIF that was prepared by an older TI-JACK build, retransmit the original source image/GIF with v0.20.1 or later. Existing smaller media can still be centered after JACKCAT is regenerated because TI-JACK reads the dimensions from the IM8C data.

### Still images

Ordinary `PNG`, `JPG`, `JPEG`, and `WebP` files become named Evo IM8C AppVars (`.8xv2`) for JACKVIEW.

Example:

```text
LOGO.png -> LOGO
```

TI-JACK:

- preserves aspect ratio;
- scales media toward the largest 320 x 210 fit, including upscaling smaller sources;
- centers letterboxed media through JACKCAT/JACKVIEW;
- uses indexed RGB565 color with up to 256 palette entries;
- preserves one-bit transparency where possible;
- chooses indexed or RLE IM8C storage based on size;
- can reduce resolution further if necessary to fit Evo media limits.

### Animated GIFs

GIFs are decoded and composited on Android. TI-JACK creates one IM8C AppVar per retained frame plus a small `TIJGIF01` manifest containing frame names, timing, dimensions, and loop metadata.

Frames are transmitted before the manifest so an interrupted transfer does not leave a valid manifest pointing to frames that never arrived.

Current GIF policy:

- up to **300 frames per GIF**;
- a source GIF longer than 300 frames is **clipped to its first 300 frames** instead of being rejected for frame count;
- retained frames keep their original order and delays;
- frames after number 300 are omitted;
- the generated-media budget is currently **6 MiB per GIF**;
- TI-JACK progressively reduces animation resolution when needed to fit that budget.

The Evo only allows eight-character variable names. TI-JACK therefore uses a six-character frame prefix plus a two-character suffix:

- 1-256 frames: hexadecimal suffixes (`00`-`FF`);
- 257-300 frames: base36 suffixes using `0-9A-Z`.

JACKVIEW understands both naming schemes. The manifest always stores the exact frame names.

The binary format is documented in [`docs/TIJ_GIF_MANIFEST.md`](docs/TIJ_GIF_MANIFEST.md).

### Numbered graph backgrounds

If a still image is explicitly named `Image1` through `Image7` (or `Img1` through `Img7`), TI-JACK uses the Evo graph-background path instead and creates the corresponding `.8ca2` variable.

These numbered OS background images are separate from JACKVIEW media and are not meant to appear in JACKCAT.

## JACKVIEW controls

```text
Browser: UP/DOWN select · ENTER open · CLEAR exit
Still:   LEFT/RIGHT previous/next · CLEAR return
GIF:     UP faster · DOWN slower · ENTER pause/resume
         LEFT/RIGHT previous/next · CLEAR return
```

## Transfer integrity

Android-to-calculator transfers follow this path:

```text
recognize -> validate/convert -> transmit -> reread directory -> download back -> compare bytes
```

A USB acknowledgement alone is not treated as success.

Calculator deletion is also verified by rereading the directory and confirming the exact variable identity disappeared.

## Evo USB transport

Current hardware target:

```text
VID:       0451
PID:       E018
Transport: CDC/ACM
Baud:      115200
```

Uploads use the observed Kermit-style `S/F/A/D*/Z/B` transaction. Protocol notes live in [`PROTOCOL_NOTES.md`](PROTOCOL_NOTES.md).

## Build

Current Android configuration:

```text
compileSdk / targetSdk: 35
minSdk:                  29
Java / Kotlin target:    17
usb-serial-for-android:  3.11.0
androidx.documentfile:   1.0.1
```

Build from Android Studio or with Gradle:

```text
gradle --no-daemon assembleDebug
```

GitHub Actions builds the debug APK from `.github/workflows/build-debug-apk.yml`.

## Current limitations

- The Android USB transport currently targets the TI-84 Evo only.
- Legacy TI-BASIC conversion does not yet cover every token or every CE/Evo behavioral difference.
- The pixel-exact SNAKE compatibility path fixes the observed green turn artifact but currently makes movement slower than the original conversion.
- JACKVIEW/JACKCAT synchronization is still being exercised with larger and more varied real-calculator media libraries.
- A GIF longer than 300 frames is intentionally clipped rather than fully preserved.
- Very large media may be reduced in resolution to stay within current IM8C/generated-media limits.
- `Image1`-`Image7` graph backgrounds are separate from JACKVIEW's IM8C library.

## Project direction

TI-JACK is intended to become one transfer application for multiple TI calculator generations. The project keeps transport, file recognition, conversion, compatibility analysis, validation, conflict handling, media preparation, and verification separated so additional calculators can be added without rebuilding the whole user experience.

Near-term work is focused on:

- JACKVIEW testing with larger image/GIF libraries;
- performance improvements for legacy-program compatibility transforms;
- broader TI-BASIC token coverage;
- additional TI calculator transports.

## Credits

TI-JACK / JACKVIEW: **Jawatech / jeremymfrank**

Android USB serial support is provided by the MIT-licensed `usb-serial-for-android` project. Evo file/token research was cross-checked against Adrien "Adriweb" Bertrand's MIT-licensed `tivars_lib_cpp`. IM8C behavior was independently implemented from public format information and cross-checked against TI-Planet `img2calc` research. See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

TI-JACK is an independent project and is not affiliated with or endorsed by Texas Instruments.
