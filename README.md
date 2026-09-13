<p align="center">
  <img src="docs/ti-jack-logo.svg" alt="TI-JACK — Universal file transfer for TI calculators" width="900">
</p>

# TI-JACK

**Universal file transfer and compatibility tools for TI calculators.**

TI-JACK is an Android-first project for moving files between a phone and TI calculators, converting compatible legacy files when needed, and verifying what actually reached the calculator. The current transport target is the **TI-84 Evo**.

Current Android version: **v0.21.0**

## What works today

TI-JACK can connect to a TI-84 Evo over USB host/OTG, browse calculator variables, transfer files in either direction, select multiple files, replace or skip duplicates, delete files, move variables between RAM and Archive, and verify writes by reading them back byte-for-byte.

It also includes compatibility and maintenance work beyond simple file copying:

- pure-Kotlin legacy `.8xp` to Evo `.8xp2` conversion for supported TI-BASIC programs;
- named image conversion for JACKVIEW;
- animated GIF preparation for JACKVIEW;
- automatic JACKVIEW installation/update;
- automatic JACKCAT generation from viewer-compatible media found on the calculator;
- automatic viewer sizing/centering metadata in JACKCAT;
- explicit `Image1`-`Image7` graph-background import for users who want the calculator's numbered image slots;
- conservative RAM/Archive free-space estimates from the calculator directory;
- verified **ARCHIVE** / **RAM** moves for selected variables;
- **CLEAN GIF** recovery for generated GIF frames left behind by interrupted transfers;
- archive-space preflight before large JACKVIEW media transfers.

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
8. On the calculator side, use **ARCHIVE** or **RAM** to move selected variables, and **CLEAN GIF** to look for orphaned JACKVIEW animation frames.

TI-JACK rereads the calculator directory after write operations and verifies uploaded variables by downloading them back.

## Calculator memory management

Each calculator row identifies the variable as `RAM` or `ARC`. The calculator header also shows:

```text
EST FREE · RAM ... · ARC ...
```

The Evo directory protocol currently exposes each variable's size and memory location, but TI-JACK has not found an exact protocol field for the calculator's own Memory-screen `RAM FREE` / `ARC FREE` counters. v0.21.0 therefore calculates a **conservative estimate**, using safe working capacities of 560 KiB RAM and 2700 KiB Archive and reserving a small amount per variable for overhead. This is intentionally labeled `EST FREE`; it should not be treated as an exact OS counter.

### Moving variables between RAM and Archive

Select one or more calculator variables and tap **ARCHIVE** or **RAM**.

TI-JACK does not delete the original first. For each variable it:

```text
download exact bytes
    -> verify identity
    -> rewrite same variable with requested memtarget
    -> reread directory
    -> verify RAM/Archive location
    -> download again
    -> compare bytes
```

That makes the memory move use the same write-integrity standard as ordinary transfers.

### GIF cleanup

A JACKVIEW GIF consists of generated frame AppVars plus a `TIJGIF01` manifest. Frames are deliberately transmitted first, so an interrupted or out-of-space transfer can leave generated frames behind without a valid manifest.

**CLEAN GIF** scans the small AppVars that could contain manifests, collects the frame names referenced by readable `TIJGIF01` manifests, and then looks for unreferenced variables matching TI-JACK's generated frame naming pattern. To reduce false positives, cleanup only offers groups containing at least two unreferenced generated-frame candidates with the same six-character prefix. Nothing is deleted until the user confirms, and each deletion is verified afterward.

### Media preflight

New JACKVIEW still images and GIF frame variables are sent directly to **Archive**. Before transmission, TI-JACK calculates the prepared output size and compares it with the conservative Archive estimate.

v0.21.0 also applies a **2.4 MiB prepared-media guard per source image/GIF**. If the media exceeds that guard, or the prepared Archive payload exceeds the estimated remaining Archive space, the transfer is stopped before the first generated variable is sent. Replacing existing archived variables credits their estimated reclaimed space during the preflight calculation.

These checks are intentionally conservative. They are meant to prevent the failure mode where a large GIF consumes most of the calculator before the final manifest is reached.

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
    -> TI-JACK converts and preflights media
    -> TI-JACK transmits media AppVars to Archive
    -> TI-JACK scans calculator media
    -> TI-JACK regenerates JACKCAT when needed
    -> JACKVIEW reads JACKCAT
```

Real-calculator testing has confirmed named still-image display, NYAN CAT animation playback, and the current automatic JACKCAT update path on the tested media set.

### Viewer layout and scaling

JACKVIEW uses a **320 x 210** media viewport beginning below the Python header area.

For new viewer-media imports, TI-JACK scales images and GIF frames **up or down** to the largest size that fits inside that viewport while preserving the source aspect ratio. It does not stretch the image to a different aspect ratio and does not crop simply to fill both dimensions.

Examples:

```text
100 x 100   -> 210 x 210, centered horizontally
1920 x 1080 -> 320 x 180, centered vertically
800 x 1200  -> 140 x 210, centered horizontally
```

JACKCAT records the prepared media width and height. JACKVIEW uses those values to center the image or animation in the 320 x 210 viewport. Older four-field JACKCAT entries remain readable; they simply fall back to the original top-left behavior until TI-JACK regenerates the catalog.

When switching from one media item to another, JACKVIEW clears the previous screen before drawing the new item. Animated GIF frames are **not** cleared between every frame, avoiding unnecessary flicker and preserving playback speed.

Existing AppVars are not resampled in place. To get the current full-viewport scaling on an image or GIF that was prepared by an older TI-JACK build, retransmit the original source image/GIF. Existing smaller media can still be centered after JACKCAT is regenerated because TI-JACK reads the dimensions from the IM8C data.

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
- can reduce resolution further if necessary to fit Evo media limits;
- sends the prepared viewer AppVar to Archive.

### Animated GIFs

GIFs are decoded and composited on Android. TI-JACK creates one IM8C AppVar per retained frame plus a small `TIJGIF01` manifest containing frame names, timing, dimensions, and loop metadata.

Frames are transmitted before the manifest so an interrupted transfer does not leave a valid manifest pointing to frames that never arrived. v0.21.0 adds **CLEAN GIF** specifically to recover frame groups left behind by that safety ordering.

Current GIF policy:

- up to **300 frames per GIF**;
- a source GIF longer than 300 frames is **clipped to its first 300 frames** instead of being rejected for frame count;
- retained frames keep their original order and delays;
- frames after number 300 are omitted;
- the converter progressively reduces animation resolution when needed to fit IM8C/internal media limits;
- the Android transfer layer applies a **2.4 MiB prepared-media guard** and an Archive free-space preflight before sending generated GIF variables;
- generated GIF frames and the final manifest target Archive directly.

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
recognize -> validate/convert -> preflight -> transmit -> reread directory -> download back -> compare bytes
```

A USB acknowledgement alone is not treated as success.

Calculator deletion is also verified by rereading the directory and confirming the exact variable identity disappeared. RAM/Archive moves similarly verify both final memory location and byte-for-byte contents.

## Evo USB transport

Current hardware target:

```text
VID:       0451
PID:       E018
Transport: CDC/ACM
Baud:      115200
```

Uploads and RAM/Archive moves use the observed Kermit-style `S/F/A/D*/Z/B` transaction. The `memtarget` parameter chooses RAM or Archive while `policy=1` permits an in-place overwrite for a verified memory move. Protocol notes live in [`PROTOCOL_NOTES.md`](PROTOCOL_NOTES.md).

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
- `EST FREE` is deliberately a conservative calculation from directory sizes; TI-JACK does not yet read the Evo's exact OS RAM-free and Archive-free counters from the protocol.
- The current safe working capacities are 560 KiB RAM and 2700 KiB Archive, so the app may under-report usable free space on some calculators rather than risk over-promising it.
- Legacy TI-BASIC conversion does not yet cover every token or every CE/Evo behavioral difference.
- The pixel-exact SNAKE compatibility path fixes the observed green turn artifact but currently makes movement slower than the original conversion.
- JACKVIEW/JACKCAT synchronization and the new orphan-frame cleanup are still being exercised with larger and more varied real-calculator media libraries.
- A GIF longer than 300 frames is intentionally clipped rather than fully preserved.
- Large media may be reduced in resolution by the converter and is additionally subject to the 2.4 MiB prepared-media guard.
- `Image1`-`Image7` graph backgrounds are separate from JACKVIEW's IM8C library.

## Project direction

TI-JACK is intended to become one transfer application for multiple TI calculator generations. The project keeps transport, file recognition, conversion, compatibility analysis, validation, conflict handling, media preparation, memory management, and verification separated so additional calculators can be added without rebuilding the whole user experience.

Near-term work is focused on:

- real-calculator validation of v0.21.0 RAM/Archive moves, memory preflight, and GIF cleanup;
- research into an exact Evo free-memory protocol resource if one is exposed;
- performance improvements for legacy-program compatibility transforms;
- broader TI-BASIC token coverage;
- additional TI calculator transports.

## Credits

TI-JACK / JACKVIEW: **Jawatech / jeremymfrank**

Android USB serial support is provided by the MIT-licensed `usb-serial-for-android` project. Evo file/token research was cross-checked against Adrien "Adriweb" Bertrand's MIT-licensed `tivars_lib_cpp`. IM8C behavior was independently implemented from public format information and cross-checked against TI-Planet `img2calc` research. See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

TI-JACK is an independent project and is not affiliated with or endorsed by Texas Instruments.
