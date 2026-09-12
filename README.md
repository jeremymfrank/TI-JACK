<p align="center">
  <img src="docs/ti-jack-logo.svg" alt="TI-JACK — Universal file transfer for TI calculators" width="900">
</p>

# TI-JACK

**Universal file transfer for TI calculators.**

TI-JACK is an open-source project for moving calculator variables and programs between TI calculators and modern computers or mobile devices. The current Android hardware target is the **TI-84 Evo USB protocol** (`0451:E018`), with additional calculator families planned as the project grows.

## Current status — Android v0.11

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
| Android 15 navigation-bar safe area | Working |
| TI-JACK adaptive launcher icon | Working |
| Automatic USB release while idle | Added in v0.11 |
| Samsung USB role selection after reconnect | Requires Android system UI |
| Additional TI calculator families | Planned |

The transfer protocol fix proven in **v0.8** sends the complete checksum-bearing Evo file, includes the variable name and type in the transfer request, and terminates the upload using the observed `S/F/A/D*/Z/B` Kermit sequence. TI-JACK then re-reads the calculator directory and downloads the uploaded variable back before reporting it as transferred.

**v0.11 keeps that working transfer protocol unchanged.** Instead of holding the CDC serial port open for the entire time the calculator is connected, TI-JACK now opens the port only for a directory or transfer operation and immediately closes it afterward. DTR/RTS are deasserted and the Android USB device connection is released before the UI returns to `READY`.

## What you need

- Android 10 or newer (`minSdk 29`)
- A phone or tablet with USB host / OTG support
- A supported Evo-protocol calculator
- A known-good USB data cable

Some Android phones initially attach the calculator in a charging/device role. If TI-JACK does not detect the calculator, open Android's USB options and select **USB controlled by connected device** (the setting required on the tested Samsung phone) so Android enumerates the Evo as a USB host device.

Android's public `UsbManager` API lets TI-JACK communicate with USB devices only after Android is already in host mode. A normal third-party app cannot force the phone's USB-C host/device role itself, so this system-level role selection may still be required after a physical reconnect on some phones.

## Using the Android app

1. Connect the calculator and wait for `TI-84 EVO CONNECTED`.
2. Tap **CHOOSE FOLDER** and select the Android folder containing your calculator files.
3. Tap one or more files in the **ANDROID** pane and choose **SEND** to copy them to the calculator.
4. Tap one or more variables in the **CALCULATOR** pane and choose **SAVE** to copy them to Android.
5. When a destination already contains the selected variable/file, choose **REPLACE** or **SKIP EXISTING**.

TI-JACK recognizes the Evo-style `.8x*2` file family used by the current protocol implementation.

### USB idle behavior

In v0.11, `READY` also means the CDC port is closed. TI-JACK automatically releases the physical serial connection after each directory read or transfer, so there is no longer a **RELEASE USB BEFORE UNPLUG** button and no separate disconnect step to remember.

Do not unplug while the app says `CONNECTING` or `TRANSFERRING`. Once it returns to `READY`, the link has been released and the cable can be removed normally.

After reconnecting, Android may still require the system USB role to be changed before the Evo appears in `UsbManager`. That role negotiation happens below TI-JACK and is separate from the calculator transfer protocol.

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

Build and install the `debug` APK, then connect the calculator over USB.

### GitHub Actions

The included workflow is:

```text
.github/workflows/build-debug-apk.yml
```

Every push to `main` builds the debug APK and publishes it as a workflow artifact. The v0.11 artifact is named:

```text
TI-JACK-Evo-Android-v0.11
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

## Roadmap

The near-term work is to confirm automatic idle release eliminates the calculator reboot requirement, improve the reconnect/USB-role guidance where Android permits it, exercise batch replace/skip behavior across more variable types, and then begin adding additional TI calculator protocols behind the same transfer UI.

## Project direction

TI-JACK is intended to become one transfer tool rather than a separate utility for every calculator generation. The UI, file-selection model, conflict handling, and verification layer are being kept calculator-agnostic while protocol-specific transports are added underneath.

---

TI-JACK is an independent project and is not affiliated with or endorsed by Texas Instruments.
