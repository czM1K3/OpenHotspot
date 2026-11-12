# OpenHotspot: project context
Goal: a client (Linux laptop or Android tablet) remotely turns on the internet-sharing hotspot of a stock Android phone (e.g. Nothing Phone) over BLE and auto-connects, like Apple's Instant Hotspot. The phone side uses Shizuku (no root).

Layout: /common (Kotlin protocol and crypto), /phone-app, /tablet-app, /linux-client (Python 3.11+), /docs (PROTOCOL.md, SECURITY.md, SETUP.md, DECISIONS.md).

Rules:
- /docs/PROTOCOL.md is the source of truth. If you must change the protocol, update the doc and the test vectors first.
- No Google Play Services dependency. Use platform BLE APIs only.
- MIT license, minimal dependencies, and note each dependency's license in DECISIONS.md.
- Hide BLE and hotspot layers behind interfaces so the logic is unit-testable.
- Verify APIs before using them. Hidden tethering APIs differ between Android versions.
- Ask questions only if truly blocked. Record decisions in /docs/DECISIONS.md.
- Commit in small logical steps. Be honest about what can't be tested without hardware.
- At the end of the session, summarize what is done, what is untested, and what the next batch needs.
