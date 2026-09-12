# TI-JACK Android — Evo Test 0.1

This is the first **read-only Android hardware test** for TI-JACK.

## Scope

Only the **TI-84 Evo** is supported in this build.

The app:

1. Watches for USB VID `0451`, PID `E018`.
2. Requests Android USB-host permission.
3. Opens the Evo as CDC/ACM serial at 115200 baud.
4. Performs the Evo/Kermit directory request.
5. Decodes the returned CBOR directory.
6. Displays variable name, type, size, and RAM/Archive state.

It contains **no upload, delete, OS-update, or file-writing commands** in v0.1.

## What you need

- Android 10 or newer.
- A phone/tablet with USB host / OTG support.
- TI-84 Evo.
- A known-good USB data cable.
- If direct USB-C host negotiation does not work, use a USB-C OTG adapter/hub.

Android should display its normal USB permission prompt when the Evo is attached.

## Build in Android Studio

Open this folder as an Android Studio project and let Gradle sync.

The project uses:

- compile SDK 35
- min SDK 29 (Android 10)
- Kotlin
- `usb-serial-for-android` 3.11.0

Then build/install the `debug` APK and connect the Evo.

Expected UI:

    ● TI-84 EVO CONNECTED
    READY

followed by the calculator variable list.

## GitHub Actions build option

A workflow is included at:

    .github/workflows/build-debug-apk.yml

If this project is pushed to GitHub, the workflow builds `app-debug.apk`
and publishes it as a workflow artifact. This is useful if the local machine
does not have an Android SDK.

## If it fails

The screen intentionally exposes a short diagnostic line in this test build.
The app also writes an internal log named:

    ti_jack_evo_android.log

inside the app's private files directory.

For the first test, a screenshot of the error/diagnostic line is enough.

## Why the protocol code is local

The Android implementation is written as a small native Kotlin implementation
of the observed Evo USB/Kermit behavior. It does not bundle Python or execute
the desktop `evo_usb.py` helper.
