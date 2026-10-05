# TI-JACK platform roadmap

TI-JACK is intended to remain one project with multiple host platforms and calculator backends.

## Host targets

### Android

Android remains the primary field/mobile target. USB host/OTG is used directly from the phone or tablet.

Current:
- TI-84 Evo transfer, conversion, delete, memory management, JACKVIEW/JACKCAT media.
- Teacher-oriented settings and classroom repeat workflows.

Next:
- TI-Nspire CX II / CX II CAS USB filesystem backend.

### Windows PC

Windows is the first desktop target. The desktop application should expose the same core workflows as Android:
- browse host files and calculator content;
- send/receive multiple files;
- duplicate policies;
- delete/rename/move where the calculator backend supports them;
- verified transfers;
- classroom repeat/distribution mode;
- diagnostics;
- the same theme/settings concepts where appropriate.

The desktop UI and USB implementation may be platform-specific, but transfer semantics and protocol test vectors should live in this repository so Android and desktop behavior do not drift.

## Calculator backend model

The Evo and Nspire families should not be forced into one fake storage model.

### TI-84 Evo backend

The Evo exposes calculator variables. TI-JACK models:
- variable name/token identity;
- variable type;
- size;
- RAM vs Archive;
- upload/download/delete;
- Archive/RAM moves;
- media conversion and JACKVIEW management.

### TI-Nspire CX II backend

The Nspire exposes a hierarchical filesystem. TI-JACK should model:
- device name/model/OS information;
- current path;
- folders and files;
- .tns documents and other transferable calculator files;
- upload/download;
- create folder;
- rename/move;
- delete;
- storage information when available.

Common UI actions should dispatch through calculator-specific capabilities rather than hiding unsupported operations.

## TI-Nspire CX II transport milestones

1. **USB probe**
   - detect TI vendor ID 0x0451;
   - detect CX II / CX II CAS product ID 0xE022;
   - record interfaces and bulk endpoints;
   - reliable permission/reconnect behavior on Android.

2. **Session / device information**
   - independently implement the required host-side framing and handshake;
   - read device identity and OS information;
   - capture protocol traces in diagnostics.

3. **Filesystem read path**
   - list root folder;
   - navigate folders;
   - download a small .tns file;
   - compare downloaded bytes with the source fixture.

4. **Filesystem write path**
   - upload a small temporary .tns file;
   - re-list and read it back;
   - require byte-for-byte verification before success.

5. **File management**
   - mkdir;
   - rename/move;
   - delete;
   - large-file fallback where native copy commands have limits;
   - reconnect/retry tests.

6. **Classroom workflow**
   - repeat-send a selected .tns document to successive calculators;
   - receive-as-copy for same-named student submissions;
   - simple classroom UI.

## Desktop milestones

1. Windows USB enumeration for Evo and Nspire.
2. Desktop file browser + calculator browser shell.
3. Evo backend parity with Android for native transfers.
4. Nspire backend parity with Android.
5. Shared protocol fixtures and transfer-verification tests.
6. Packaged Windows installer/build artifact.

## Protocol research and licensing

TI-JACK will implement its Nspire transport independently from observed behavior and public protocol information.

Useful compatibility references include:
- Texas Instruments TI-Nspire CX II Connect, which demonstrates current WebUSB file transfer support.
- TiLP/libticalcs2, which historically supports Nspire-family calculators.
- TI-Nspire-Python-Link, which reports CX II USB product ID 0xE022 and demonstrates modern filesystem operations.

Some community implementations are GPL-licensed. Their source is useful as behavioral research, but GPL implementation code must not be copied into TI-JACK unless TI-JACK's licensing strategy explicitly changes. Protocol observations, USB descriptors, independently written test fixtures, and black-box traces should be recorded here instead.
