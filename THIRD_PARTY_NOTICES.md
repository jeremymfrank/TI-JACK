# Third-party notes

TI-JACK Android v0.16.1 currently uses:

- `usb-serial-for-android` by mik3y, version 3.11.0, MIT licensed.
- Android SDK / Kotlin / Gradle components under their respective licenses.

The v0.16 experiment compiled the MIT-licensed `tivars_lib_cpp` conversion engine into the APK for legacy TI file conversion. After that APK triggered a Google Play Protect harmful-app warning on test hardware, v0.16.1 removes the native converter from the Android package while a non-native conversion path is developed. The project history retains the experiment and attribution.

The Evo USB wire behavior was independently implemented in Kotlin from observed public protocol behavior. The Android project does not contain or redistribute the `evo_usb_py` Python source.

TI-JACK is an unofficial utility and is not affiliated with or endorsed by Texas Instruments.
