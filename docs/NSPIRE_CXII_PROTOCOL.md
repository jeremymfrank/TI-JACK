# TI-Nspire CX II USB research notes

Status: protocol research / implementation target. This document does not claim working TI-JACK CX II transfer support yet.

## Device identity

Texas Instruments USB vendor ID:

```text
VID 0451
```

Community tools identify:
- classic TI-Nspire / CX family: PID E012;
- TI-Nspire CX II / CX II CAS: PID E022.

The Android implementation should initially match only the CX II PID for the new backend so the first hardware test surface stays small.

## Storage model

Unlike the TI-84 Evo variable directory, the Nspire presents a hierarchical document filesystem. The initial TI-JACK backend API therefore needs path-based operations rather than Evo token/type identities.

Minimum operations:
- device info;
- list directory;
- change/navigate path in the UI;
- read file;
- write file;
- mkdir;
- rename/move;
- delete.

The UI should treat .tns as the main classroom document format but should not hard-code the transport to .tns if the device accepts additional file types.

## Current external evidence

Texas Instruments' current TI-Nspire CX II Connect web application supports:
- sending and receiving calculator files;
- screenshots;
- OS updates;
- exiting Press-to-Test.

That confirms current CX II hardware supports browser/host USB workflows and gives TI-JACK a useful black-box compatibility target.

Independent community work also demonstrates libusb/PyUSB access to CX II devices and path-based file management. TI-JACK should reproduce required behavior independently and record its own packet traces.

## Android implementation plan

1. Extend the USB device filter with 0451:E022 only when the probe backend is ready.
2. Request Android USB host permission and inspect interfaces/endpoints.
3. Keep Nspire connection/session code separate from Evo CDC/Kermit code.
4. Log USB descriptor and protocol-stage diagnostics in the same TI-JACK log.
5. Do not expose SEND/DELETE as successful until list/read/write verification is implemented.
6. Test reconnect, cable reversal/OTG behavior, and screen-off behavior on real Android hardware.

## Verification rules

For upload:
1. send file;
2. re-list destination;
3. read file back;
4. compare bytes;
5. only then report success.

For delete:
1. request delete;
2. re-list parent directory;
3. require the exact path to be absent.

For rename/move:
1. request operation;
2. re-list both relevant locations;
3. require source absent and destination present.

## Windows implementation plan

Use a user-space USB backend suitable for Windows and keep packet/session logic independent of the GUI. libusb is a viable cross-platform host library, but Windows driver installation and coexistence with TI software must be tested before choosing the final packaging strategy.

The first Windows Nspire milestone should use the same protocol fixtures and verification rules as Android.


## CX II outer USB envelope

CX II-class hardware adds an outer message layer around the ordinary Nspire/NavNet stream.

Current independently implemented facts recorded for the probe:

- normal CX II bulk traffic uses endpoint `0x01` OUT and `0x81` IN;
- envelope header is 12 bytes;
- header length and sequence fields are big-endian 16-bit values;
- header checksum is a one's-complement 16-bit word checksum;
- host address is `0xFE`, calculator address `0x01`, broadcast `0xFF`;
- service `0x04` carries the ordinary Nspire protocol stream;
- bit `0x80` on a service identifies an ACK;
- incoming messages requesting acknowledgement must be ACKed using the same sequence number and reversed addresses.

The probe branch now has a pure codec for this envelope with JVM tests. The live handshake remains disabled until endpoint descriptors and initial packets are confirmed on a real CX II.
