# TI-JACK Desktop

Windows is the first TI-JACK desktop target.

This directory is reserved for the desktop host implementation. The desktop app is not yet advertised as a working release.

## Goals

- same TI-JACK identity and classroom workflow as Android;
- TI-84 Evo support;
- TI-Nspire CX II / CX II CAS support;
- multi-select send/receive;
- duplicate Ask / Replace / Skip / Rename Copy behavior;
- verified transfers;
- teacher repeat-send workflow;
- diagnostic export;
- no dependency on TI's licensed student/teacher desktop software.

## Architecture

Desktop UI code should not contain calculator protocol details. A backend should expose calculator capabilities and storage entries, while platform USB code provides transport primitives.

The Nspire backend is filesystem-oriented; the Evo backend is variable/memory-oriented. The UI should show calculator-specific actions only when the connected backend supports them.

See:
- `docs/PLATFORM_ROADMAP.md`
- `docs/NSPIRE_CXII_PROTOCOL.md`
