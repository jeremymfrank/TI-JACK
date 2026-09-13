# Third-party notes

TI-JACK Android v0.19.2 uses:

- `usb-serial-for-android` by mik3y, version 3.11.0, MIT licensed.
- Android SDK / Kotlin / Gradle components under their respective licenses.

The v0.16 experiment compiled the MIT-licensed `tivars_lib_cpp` conversion engine into the APK for legacy TI file conversion. After that APK triggered a Google Play Protect harmful-app warning on test hardware, v0.16.1 removed the native converter.

v0.17+ restores `.8xp` conversion with TI-JACK's own pure-Kotlin parser/token writer and does **not** bundle `tivars_lib_cpp`, the Android NDK, or the v0.16 JNI bridge. Evo container/token research and conversion results were cross-checked against the public MIT-licensed `tivars_lib_cpp` Evo work by Adrien "Adriweb" Bertrand, so that project remains credited as a research/reference source.

v0.18+ independently implements Evo IM8C AppVar encoding for named still images and GIF frames. The wire/container behavior was cross-checked against public TI-Planet `img2calc` format research. TI-JACK does **not** copy or bundle `img2calc` source code or dependencies.

v0.19+ bundles TI-JACK's own calculator-side JACKVIEW Python source and generates JACKCAT with TI-JACK's own Evo Python container writer. JACKVIEW/JACKCAT are first-party TI-JACK project code, not third-party dependencies.

The Evo USB wire behavior was independently implemented in Kotlin from observed public protocol behavior. The Android project does not contain or redistribute the `evo_usb_py` Python source.

TI-JACK is an unofficial utility and is not affiliated with or endorsed by Texas Instruments.
