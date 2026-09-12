# Third-party notes

TI-JACK Android currently uses:

- `usb-serial-for-android` by mik3y, version 3.11.0, MIT licensed.
- `tivars_lib_cpp` by Adrien "Adriweb" Bertrand, MIT licensed. TI-JACK pins Evo-capable commit `ad61ebba76fc00f30bb9365aff86c35162a50189` and compiles its amalgamated C++ distribution into the Android app for legacy TI variable conversion.
- Android SDK / Kotlin / Gradle / NDK components under their respective licenses.

The `tivars_lib_cpp` conversion engine is obtained at build time from its public GitHub repository. The installed Android app does not require Internet access to perform conversions.

The Evo USB wire behavior was independently implemented in Kotlin from observed public protocol behavior. The Android project does not contain or redistribute the `evo_usb_py` Python source.

TI-JACK is an unofficial utility and is not affiliated with or endorsed by Texas Instruments.
