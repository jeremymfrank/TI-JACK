# TI-JACK Desktop for Windows

This is the first buildable Windows host for TI-JACK.

## Current v0.1 milestone

The desktop app currently provides:

- native Windows EXE build on .NET 8 / WinForms;
- TI-JACK two-pane PC/calculator layout;
- persistent Amber, TI Blue, Classic Green, High Contrast, and Light Classroom themes;
- teacher settings including Repeat Send, Keep Awake, duplicate policy, destructive confirmations, and Simple Classroom UI;
- PC folder browsing and file selection/deletion;
- native Windows registry detection of Texas Instruments USB devices without changing their drivers;
- explicit recognition of TI-84 Evo 0451:E018 and TI-Nspire CX II / CX II CAS 0451:E022;
- capability-gated transfer controls so unfinished transports cannot falsely report success.

## Transport status

This desktop milestone does not yet advertise calculator transfers as working.

- Evo: the verified Android Kermit/CDC implementation is being ported to a Windows backend.
- Nspire CX II: USB session, device-info, and filesystem services are being implemented from independent public protocol documentation and verified on real hardware before enabling send/save/delete.

The Windows UI is intentionally usable before the transports are enabled so classroom workflows and packaging can be tested independently.