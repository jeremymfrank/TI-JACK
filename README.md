<p align="center">
  <img src="docs/ti-jack-logo.svg" alt="TI-JACK — Universal file transfer for TI calculators" width="900">
</p>

# TI-JACK

**Universal file transfer for TI calculators.**

TI-JACK is an open-source project for moving calculator variables and programs between TI calculators and modern computers or mobile devices. The current Android hardware target is the **TI-84 Evo USB protocol** (`0451:E018`), with additional calculator families planned as the project grows.

## Current status — Android v0.17

The Evo Android path is bidirectional and has been tested on real hardware. v0.17 restores legacy TI-BASIC `.8xp` conversion with a **pure-Kotlin, strict conversion preview** and adds an Evo fidelity pass for programs written around the older CE graph canvas.

| Capability | Status |
| --- | --- |
| Detect Evo over Android USB host | Working |
| Read calculator directory | Working |
| Calculator → Android transfer | Working |
| Android → calculator transfer | Working |
| Multiple-file selection | Working |
| Select All / Clear selection | Working |
| Replace / skip existing files | Working |
| Android-side file deletion | Working |
| Calculator-side variable deletion | Working |
| Upload read-back verification | Working |
| RAM / Archive display | Working |
| Android folder picker | Working |
| Android 15 status-bar / camera-cutout / navigation safe area | Working |
| In-app `?` help / version / connection tips | Working |
| Legacy `.8xp` → Evo `.8xp2` conversion | Pure-Kotlin preview; hardware testing in progress |
| Classic 265 × 165 CE canvas centering | Implemented when the layout can be identified safely |
| PNG / JPG / JPEG / WebP → Evo background image | Implemented; hardware testing in progress |
| Manual release-USB button | Removed; normal detach is automatic |
| USB-C OTG adapter + USB-A-to-C data cable | Working repeatedly on tested Samsung + Evo |
| Direct USB-C-to-C on tested Samsung + Evo | USB role mismatch; see connection notes below |
| Additional TI calculator families | Planned |

The transfer protocol fix proven in **v0.8** sends the complete checksum-bearing Evo file, includes the variable name and type in the transfer request, and terminates the upload using the observed `S/F/A/D*/Z/B` Kermit sequence. TI-JACK then re-reads the calculator directory and downloads the uploaded variable back before reporting it as transferred.

## What you need

- Android 10 or newer (`minSdk 29`)
- A phone or tablet with USB host / OTG support
- A supported Evo-protocol calculator
- A known-good USB data cable

For the tested Samsung + TI-84 Evo combination, direct USB-C-to-USB-C consistently starts with the phone in the wrong USB data role for TI-JACK. Repeatedly forcing **USB controlled by → Connected device** did make the calculator visible, but after several role changes the Samsung USB options stopped responding until the phone was rebooted.

The reliable battery-powered field setup is:

```text
Phone → USB-C OTG/host adapter → USB-A-to-USB-C data cable → TI-84 Evo
```

No external power is required. A slim USB-C OTG adapter has also been tested successfully and works with the phone case. The OTG adapter must be on the **phone side**. A powered USB hub also establishes the correct host role and works reliably.

Android's public `UsbManager` API lets TI-JACK communicate with the calculator only after Android is already in USB host mode. A normal third-party app cannot force the phone's USB-C host/device role itself.

## Using the Android app

1. Plug the USB adapter into the phone, connect the calculator, and wait for `TI-84 EVO CONNECTED`.
2. Tap **CHOOSE FOLDER** and select the Android folder containing your calculator files.
3. Tap one or more files in the **ANDROID** pane and choose **TRANSMIT →** to copy or convert-and-copy them to the calculator.
4. Tap one or more variables in the **CALCULATOR** pane and choose **← TRANSMIT** to copy them to Android.
5. Use **SELECT ALL** or **CLEAR** to manage groups of selected items.
6. Use **DELETE** on either pane to remove selected Android files or calculator variables. TI-JACK asks for confirmation before deletion.
7. When a destination already contains the selected variable/file, choose **REPLACE** or **SKIP EXISTING**.
8. Tap the **?** in the upper-right for the installed version, credits, conversion notes, and connection tips.

Native Evo files transfer unchanged. Legacy TI-BASIC `.8xp` files display **[CONVERT]** in the Android pane. Ordinary supported image files display **[TO IMAGE]** and are converted to an Evo background image.

## Conversion safety and fidelity

TI-JACK does not send arbitrary files to the calculator. The v0.17 path is:

```text
recognize source → convert if needed → validate Evo container → transmit → read back and verify
```

The v0.16 native C++ converter experiment successfully produced an Evo file, but the test APK triggered a Google Play Protect warning and a converted Snake program exposed an important second issue: a structurally valid conversion can still contain commands the Evo no longer executes. v0.17 therefore uses a **pure-Kotlin converter** and treats program compatibility as part of conversion rather than merely repackaging tokens.

The current `.8xp` converter is intentionally strict. It validates the legacy TI file structure and checksum, converts only token mappings TI-JACK knows, creates a native Evo `.8xp2` CBOR container, validates that output, and refuses an unknown token instead of substituting a guess. Coverage will be expanded as programs are validated on real hardware.

### Preserving old program behavior

The conversion priority is:

1. preserve the original operation directly when Evo supports it,
2. translate or emulate changed features where that can be done safely,
3. preserve the original logical canvas and **center it** when stretching would alter hard-coded drawing/collision coordinates,
4. retain a visible note in the converted source and avoid executing a removed command when there is no exact Evo equivalent,
5. refuse conversion rather than silently remove behavior that could change program logic.

The first hardware fidelity target is the uploaded **SNAKE.8xp** program. It explicitly sets the classic color-calculator graph window to `0..264` by `0..164` and uses matching `Text(` / `pxl-Test(` coordinates. v0.17 detects that exact layout and centers the 265 × 165 logical canvas inside the Evo's larger graph area instead of stretching it. Graph-coordinate drawing remains in the legacy coordinate system, while pixel-based text/collision coordinates receive the matching center offset.

`BorderColor` is a special case. The Evo removed the old physical graph border, so there is no exact border-color target. Rather than silently deleting `BorderColor 2`, v0.17 keeps the original statement visible in the converted program as a TI-BASIC comment so it cannot generate a runtime `SYNTAX ERROR`. SNAKE also draws its own in-game frame, which remains active and is centered with the game canvas.

This is a preview, not a claim that every `.8xp` is already portable. A program using an unverified token or a layout TI-JACK cannot transform safely is refused instead of being sent as a questionable conversion.

### Phone image conversion

TI-JACK accepts PNG, JPG/JPEG, and WebP files and creates an Evo **Image1–Image7** background-image variable (`.8ca2`) in memory before transmission.

The converter:

- fits the source onto the Evo's 160 × 105 background-image canvas,
- preserves source aspect ratio,
- uses a white background for unused space,
- converts pixels to RGB565,
- writes the calculator's expected bottom-to-top row order,
- creates a valid Evo CBOR container and checksum,
- then uses the normal upload and read-back verification path.

A source named like `Image3.jpg` prefers **Image3**. Otherwise TI-JACK chooses the next free Image slot. If the chosen slot already exists, the normal **REPLACE / SKIP EXISTING** dialog is used. A single batch is limited to seven ordinary images because the Evo has seven background-image slots.

## USB connection notes

On the tested Samsung phone:

- Repeated connect/disconnect cycles are stable when the USB role is not manually changed.
- Direct C-to-C attaches with **USB controlled by: This device**, so TI-JACK cannot enumerate the Evo.
- Manually changing to **Connected device** makes TI-JACK work, but repeated role swaps can wedge the Samsung USB stack until reboot.
- A genuine USB-C OTG/host adapter correctly puts the phone in host mode from initial attachment.
- With that OTG adapter and a known-good USB-A-to-C data cable, the Evo reconnects automatically and TI-JACK's transfer functions work normally.
- Putting the OTG adapter on the calculator side does not establish the needed phone host role.
- A USB hub produces the same good host-mode behavior and is useful as a diagnostic, but it is not required for field use.

The app's disconnected message is intentionally written for nontechnical users: **Make sure the USB adapter is plugged into your phone, then reconnect the calculator.**

Do not unplug during an active transfer. Once a transfer completes, normal physical disconnect/reconnect is supported; there is no manual **RELEASE USB** step.

## Transfer verification

A successful Android → calculator transfer must pass all of the following before the app counts it as transferred:

1. The input is a valid native Evo file or is successfully converted into one.
2. TI-JACK validates the Evo container and checksum.
3. The calculator acknowledges the Kermit upload session.
4. The variable appears in a fresh calculator directory read.
5. TI-JACK downloads that variable back from the calculator.
6. The downloaded file matches the file that was sent.

Calculator-side deletion is also verified by re-reading the calculator directory after the delete transaction before reporting success.

## Building

Open the repository as an Android Studio project and let Gradle sync.

The Android app currently uses:

- compile / target SDK 35
- min SDK 29
- Kotlin / Java 17
- `usb-serial-for-android` 3.11.0
- Android `DocumentFile` folder access
- pure-Kotlin Evo/legacy conversion code; **no native converter or NDK is required in v0.17**

Conversion happens locally on the phone and requires no Internet connection.

### GitHub Actions

The included workflow is:

```text
.github/workflows/build-debug-apk.yml
```

Every push to `main` builds the debug APK and publishes it as:

```text
TI-JACK-Evo-Android-v0.17
```

## Protocol notes

The Android USB implementation is native Kotlin code for the observed Evo USB/Kermit behavior. File conversion happens before the USB layer and does not change the proven transfer protocol.

The current calculator USB identity is:

```text
VID 0451
PID E018
CDC/ACM 115200 baud
```

Protocol research and implementation notes live in `PROTOCOL_NOTES.md`.

## Diagnostics

The app shows short user-facing status guidance directly in the UI and writes an internal log named `ti_jack_evo_android.log` inside the app's private files directory.

## Credits

TI-JACK project: **Jawatech / jeremymfrank**. Android USB serial support is provided by the open-source `usb-serial-for-android` library. The legacy/Evo format work was cross-checked against the MIT-licensed `tivars_lib_cpp` Evo research by Adrien "Adriweb" Bertrand; v0.17 does **not** bundle that native library. See `THIRD_PARTY_NOTICES.md`.

TI-JACK is an independent project and is not affiliated with or endorsed by Texas Instruments.

## Roadmap

Near-term work includes expanding the pure-Kotlin token compatibility table from real program tests, improving compatibility/emulation notes, validating image import on hardware, adding image preview/crop controls, and adding additional TI calculator protocols behind the same transfer UI.

## Project direction

TI-JACK is intended to become one transfer tool rather than a separate utility for every calculator generation. The UI, file-selection model, conflict handling, conversion layer, deletion workflow, and verification layer are being kept calculator-agnostic while protocol-specific transports are added underneath.
