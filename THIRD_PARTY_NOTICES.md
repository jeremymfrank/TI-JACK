# Third-party notes

TI-JACK Android v0.20.1 uses:

- `usb-serial-for-android` by mik3y, version 3.11.0, MIT licensed.
- Android SDK / Kotlin / Gradle components under their respective licenses.

Legacy `.8xp` conversion is implemented by TI-JACK in pure Kotlin and does **not** bundle `tivars_lib_cpp`, the Android NDK, or a JNI conversion bridge. Evo container/token research and conversion results were cross-checked against the public MIT-licensed `tivars_lib_cpp` Evo work by Adrien "Adriweb" Bertrand, so that project remains credited as a research/reference source.

TI-JACK independently implements Evo IM8C AppVar encoding for named still images and GIF frames. The wire/container behavior was cross-checked against public TI-Planet `img2calc` format research. TI-JACK does **not** copy or bundle `img2calc` source code or dependencies.

JACKVIEW and JACKCAT are first-party TI-JACK project code. JACKVIEW is bundled as TI Python source and JACKCAT is generated with TI-JACK's Evo Python container writer.

The Evo USB wire behavior was independently implemented in Kotlin from observed public protocol behavior. The Android project does not contain or redistribute the `evo_usb_py` Python source.

TI-JACK is an unofficial utility and is not affiliated with or endorsed by Texas Instruments.
