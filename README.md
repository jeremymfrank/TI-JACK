<p align="center">
  <img src="docs/ti-jack-logo.svg" alt="TI-JACK — Universal file transfer for TI calculators" width="900">
</p>

# TI-JACK

**Universal file transfer for TI calculators.**

TI-JACK is an open-source project for moving calculator variables and programs between TI calculators and modern computers or mobile devices. The current Android hardware target is the **TI-84 Evo USB protocol** (`0451:E018`), with additional calculator families planned as the project grows.

## Current status — Android v0.13

The Evo Android path is bidirectional and has been tested on real hardware.

| Capability | Status |
| --- | --- |
| Detect Evo over Android USB host | Working |
| Read calculator directory | Working |
| Calculator → Android transfer | Working |
| Android → calculator transfer | Working |
| Multiple-file selection | Implemented |
| Replace / skip existing files | Implemented |
| Upload read-back verification | Working |
| RAM / Archive display | Working |
| Android folder picker | Working |
| Android 15 status-bar / camera-cutout / navigation safe area | Added in v0.13 |
| In-app `?` help / version / connection tips | Added in v0.13 |
| Manual release-USB button | Removed in v0.13; normal detach is automatic |
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

No external power is required. A real USB hub also establishes the correct host role and works reliably, but the hub is not required once a known-good OTG/host adapter is used.

Android's public `UsbManager` API lets TI-JACK communicate with the calculator only after Android is already in USB host mode. A normal third-party app cannot force the phone's USB-C host/device role itself.

## Using the Android app

1. Connect the calculator and wait for `TI-84 EVO CONNECTED`.
2. Tap **CHOOSE FOLDER** and select the Android folder containing your calculator files.
3. Tap one or more files in the **ANDROID** pane and choose **SEND** to copy them to the calculator.
4. Tap one or more variables in the **CALCULATOR** pane and choose **SAVE** to copy them to Android.
5. When a destination already contains the selected variable/file, choose **REPLACE** or **SKIP EXISTING**.
6. Tap the **?** in the upper-right for the installed version, credits, and connection tips.

TI-JACK recognizes the Evo-style `.8x*2` file family used by the current protocol implementation. Legacy `.8xp` files are not directly interchangeable with Evo `.8xp2` files.

### USB connection notes

On the tested Samsung phone:

- Repeated connect/disconnect cycles are stable when the USB role is not manually changed.
- Direct C-to-C attaches with **USB controlled by: This device**, so TI-JACK cannot enumerate the Evo.
- Manually changing to **Connected device** makes TI-JACK work, but repeated role swaps can wedge the Samsung USB stack until reboot.
- A genuine USB-C OTG/host adapter correctly puts the phone in host mode from initial attachment.
- With that OTG adapter and a known-good USB-A-to-C data cable, the Evo reconnects automatically and TI-JACK's transfer functions work normally.
- A USB hub produces the same good host-mode behavior and is useful as a diagnostic, but it is not required for field use.

Do not unplug during an active transfer. Once a transfer completes, normal physical disconnect/reconnect is supported; there is no manual **RELEASE USB** step in v0.13.

## Transfer verification

TI-JACK deliberately does more than trust a transport ACK for uploads. A successful Android → calculator transfer must pass all of the following before the app counts it as transferred:

1. The calculator acknowledges the Kermit upload session.
2. The variable appears in a fresh calculator directory read.
3. TI-JACK downloads that variable back from the calculator.
4. The downloaded file matches the file that was sent.

This behavior was added after early test builds could receive a successful transport acknowledgment without producing a visible calculator variable.

## Building

Open the repository as an Android Studio project and let Gradle sync.

The Android app currently uses:

- compile / target SDK 35
- min SDK 29
- Kotlin / Java 17
- `usb-serial-for-android` 3.11.0
- Android `DocumentFile` folder access

Build and install the `debug` APK, then connect the calculator over USB host/OTG.

### GitHub Actions

The included workflow is:

```text
.github/workflows/build-debug-apk.yml
```

Every push to `main` builds the debug APK and publishes it as a workflow artifact named:

```text
TI-JACK-Evo-Android-v0.13
```

## Protocol notes

The Android implementation is a native Kotlin implementation of the observed Evo USB/Kermit behavior. It does not bundle Python or invoke the desktop helper at runtime.

The current calculator USB identity is:

```text
VID 0451
PID E018
CDC/ACM 115200 baud
```

Protocol research and implementation notes live in `PROTOCOL_NOTES.md`.

## Diagnostics

The app shows a short diagnostic line directly in the UI and also writes an internal log named:

```text
ti_jack_evo_android.log
```

inside the app's private files directory.

## Credits

TI-JACK project: **Jawatech / jeremymfrank**. Android USB serial support is provided by the open-source `usb-serial-for-android` library. See `THIRD_PARTY_NOTICES.md` for third-party notices.

TI-JACK is an independent project and is not affiliated with or endorsed by Texas Instruments.

## Roadmap

Near-term work includes improving direct USB-C role compatibility across Android devices, expanding connection diagnostics, exercising batch replace/skip behavior across more variable types, and adding additional TI calculator protocols behind the same transfer UI.

## Project direction

TI-JACK is intended to become one transfer tool rather than a separate utility for every calculator generation. The UI, file-selection model, conflict handling, and verification layer are being kept calculator-agnostic while protocol-specific transports are added underneath.
