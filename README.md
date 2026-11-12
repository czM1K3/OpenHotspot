# OpenHotspot

Turn on the internet-sharing hotspot of a stock Android phone from a Linux laptop or an Android tablet over Bluetooth LE, and connect automatically, similar to Apple's Instant Hotspot. The phone side uses [Shizuku](https://shizuku.rikka.app/) to toggle tethering without root. No Google Play Services.

**Status (2026-11-12): pre-alpha, not yet field-tested end to end.** The protocol, both clients and the phone app are implemented and unit-tested (about 350 JVM tests and 260 Python tests, plus shared test vectors that both languages reproduce byte for byte). On real hardware only one thing has been run so far: the phone-side hotspot control (Shizuku `TetheringManager` backend on a Nothing phone, Android 17, adb-started Shizuku). **No BLE exchange between a real client and the real phone app has happened yet**, nor has the tablet app run on a real device. See [docs/TESTING.md](docs/TESTING.md) for the checklist that closes that gap and the final section of this file for the known limitations.

## Features

**Phone (Android 11+, stock, no root)**
- Foreground service with a BLE GATT server and a non-identifying, rotating advertisement (no name, no stable ID).
- Turns the hotspot on and off through Shizuku (`TetheringManager`; `cmd wifi` and a no-internet local-only hotspot as fallbacks), with capability detection, a diagnostics screen and an exportable report.
- Auto-off after a configurable time with no connected client.
- Pairing by one-time QR (120 s, single use, screenshot-blocked); up to 8 clients, each revocable on its own.
- Keys encrypted with an Android Keystore key, excluded from backups; lockout state survives restarts.
- Restarts itself after reboot (Shizuku itself must be restarted by hand).

**Linux laptop (Python 3.11+, BlueZ, NetworkManager)**
- CLI: `pair`, `connect`, `status`, `disconnect`, `unpair`, `doctor`.
- Background daemon (systemd user service): when offline and the phone is in range, turns the hotspot on, joins it, verifies the internet and undoes the join if it goes nowhere; auto / manual / off modes; no scanning while online.
- Optional tray icon and desktop notifications; keys in the Secret Service keyring or a 0600 file.
- `tools/attack_probe.py` replays, tampers with and floods a real phone to check it refuses correctly.

**Android tablet**
- Connect button, Quick Settings tile and an optional auto-connect foreground service.
- QR pairing with the camera (bundled ML Kit) or a pasted link; joins with `WifiNetworkSpecifier` or `WifiNetworkSuggestion`; verifies the internet.

**Security** (details in [docs/SECURITY.md](docs/SECURITY.md))
- X25519 pairing, per-device keys, ChaCha20-Poly1305, random nonces, +-60 s timestamps and strictly increasing counters, response binding to the request, global rate limiting with exponential lockout.
- Credentials never travel in plaintext and are never logged.

## Known limitations

- **Unproven over the air.** BLE interoperability (BlueZ, Android GATT server and the tablet stack), pairing with real cameras, token rotation and auto-connect are only tested against mocks and simulators.
- **Shizuku must be restarted after every phone reboot**, which needs Wi-Fi (wireless debugging) or a USB cable; until then the phone refuses with `SHIZUKU_UNAVAILABLE`. No root-free way around this is known.
- **Hidden Android APIs.** The hotspot control uses hidden `TetheringManager`/`IWifiManager` calls through a hidden-API bypass. It worked on Android 17 on one phone; other versions and makers are unverified and a future Android release can break it.
- **Whoever sees the QR can pair** during its 120 seconds (no per-pairing confirmation on the phone yet).
- **A revoked client keeps the hotspot password** it already knows; change the hotspot password after revoking.
- **Passphrase visible briefly in the Linux process list** (`nmcli ... password`), and a delayed replay within 60 s can trigger one hotspot activation. Both are documented in SECURITY.md.
- **The tablet's default join method** (`WifiNetworkSpecifier`) gives internet to this app only and shows a system prompt each time; the *share with all apps* option uses suggestions, whose timing Android decides.
- **Clocks must agree** to within about a minute; there is no fallback for a wrong clock.
- **The no-internet local-only hotspot** is an opt-out fallback; the protocol cannot yet tell the client that it has no internet.
- **Battery policies** of some makers (Samsung, Xiaomi, OnePlus, Oppo, Vivo) stop background services unless the app is exempted (docs/SETUP.md); none of that is verified.
- **Linux only needs NetworkManager**; iwd or wpa_supplicant-only systems are unsupported. No macOS or Windows client.
- **ML Kit** (tablet QR scanning) is proprietary Google software and was not tested without Play Services; ZXing is the open alternative (docs/DECISIONS.md D39).
- No external security review; the Kotlin crypto was not audited for timing leaks.

## Roadmap

1. **Hardware validation** (next): work through TESTING.md on the Nothing phone with the Linux client, then the tablet, then a Samsung tablet and a second phone make; fix what breaks and record results.
2. **Phone-side confirmation** of each new pairing and a "last used" list.
3. **Protocol v1.1/v2 candidates:** a no-internet flag in the hotspot response, a challenge-response mode to close the 60 s replay window and remove clock dependence, optional per-session forward secrecy (PROTOCOL.md R7, R8, SECURITY.md limitation 5).
4. **Hotspot password rotation on revoke** (PROTOCOL.md R10).
5. **Shizuku UserService backend** as the fallback if the hidden-API bypass stops working (DECISIONS.md D27).
6. **Easier Shizuku restart** after reboot; investigate Shizuku's start-on-boot options.
7. **Packaging:** AUR/deb/Flatpak for the Linux client, signed release APKs, F-Droid metadata.
8. More clients (other desktop environments, a minimal CLI for non-NetworkManager systems), merge the duplicated Keystore store code (D42), a proper audit.

## Layout

| Path | Purpose | State |
|---|---|---|
| `common/` | Kotlin/JVM protocol, crypto and the phone-side `PhoneEngine`; passes `docs/test-vectors.json` | working |
| `phone-app/` | Android app (Compose): foreground service, GATT server, pairing QR, revoke, log. Hotspot backends in `hotspot/`; see `docs/HOTSPOT_BACKENDS.md` | builds, unit-tested; hotspot backend verified on one real phone; BLE not tested on hardware |
| `tablet-app/` | Android app on the tablet (BLE client, Wi-Fi connect, QR pairing, Quick Settings tile, auto-connect) | built and unit tested; never run against a real phone or on Samsung hardware |
| `linux-client/` | Python 3.11+ client: protocol, real BLE (bleak/BlueZ), CLI, daemon, systemd user service, optional tray | unit-tested with mocked BLE; not tested on a real phone |
| `docs/PROTOCOL.md` | Wire protocol. **Source of truth.** | v1 draft |
| `docs/SECURITY.md` | Threat model, rate limiting, revocation, key storage | v1 draft |
| `docs/HOTSPOT_BACKENDS.md` | Hotspot backends per Android version, verified vs unverified | written |
| `docs/DECISIONS.md` | Decision log and dependency licenses | started |
| `docs/SETUP.md` | User setup guide: phone and Shizuku, Linux, tablet, troubleshooting by phone maker | written; mostly unverified on hardware |
| `docs/TESTING.md` | What the tests prove, and the manual hardware checklist | written |
| `docs/test-vectors.json` | Deterministic crypto and framing vectors | generated |
| `docs/tools/gen_vectors.py` | Generates and self-verifies the vectors (stdlib only) | working |
| `.github/workflows/ci.yml` | CI: ruff, mypy, pytest on Python 3.11 to 3.13; Gradle test, lint and assemble | added, not yet run on GitHub |

## How it works, in short

1. **Pair once.** The phone shows a QR code holding its service UUID, a one-time X25519 public key and a 16-byte secret. The client scans it, does an X25519 exchange and derives a per-device long-term key with HKDF.
2. **Wake.** The phone advertises a rotating, non-identifying token. A paired client recognises it with a key derived from the long-term key.
3. **Ask.** The client writes an encrypted, replay-protected request (ChaCha20-Poly1305). The phone enables the hotspot through Shizuku and answers with the SSID and passphrase, encrypted.
4. **Connect.** The client joins the Wi-Fi network.

Details: [docs/PROTOCOL.md](docs/PROTOCOL.md). Risks and open questions are at its end.

## Build and test (Android and Kotlin)

```
./gradlew test lintDebug assembleDebug   # JDK 21 and the Android SDK (set sdk.dir in local.properties); lint must report no issues
```
Debug APKs: `phone-app/build/outputs/apk/debug/phone-app-debug.apk` and `tablet-app/build/outputs/apk/debug/tablet-app-debug.apk`.

Linux client:
```
cd linux-client && python3 -m venv .venv && .venv/bin/pip install -e '.[dev]'
.venv/bin/ruff check src tests tools && .venv/bin/mypy src && .venv/bin/python -m pytest
```

## Verifying the vectors

```
python3 docs/tools/gen_vectors.py          # regenerate and verify
python3 docs/tools/gen_vectors.py --check  # fail if docs/test-vectors.json is stale
```

Both implementations reproduce these vectors in their unit tests, and the Kotlin phone engine is replayed against the Python-generated frames byte for byte.

## License

MIT, see [LICENSE](LICENSE).
