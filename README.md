# OpenHotspot

Turn on the internet-sharing hotspot of a stock Android phone from a Linux laptop or an Android tablet over Bluetooth LE, and connect automatically, similar to Apple's Instant Hotspot. The phone side uses [Shizuku](https://shizuku.rikka.app/) to toggle tethering without root. No Google Play Services.

**Status: protocol designed and implemented in Python (Linux client) and Kotlin (`:common`, phone app). The phone app now has real hotspot backends (Shizuku TetheringManager, `cmd wifi`, local-only) with capability detection, auto-off and a diagnostics screen, but none of it has run on a real phone yet; see docs/HOTSPOT_BACKENDS.md. The Linux client has a real BLE transport, a daemon and a service, all tested only against mocks: no real BLE exchange has happened yet (see docs/TESTING.md).**

## Layout

| Path | Purpose | State |
|---|---|---|
| `common/` | Kotlin/JVM protocol, crypto and the phone-side `PhoneEngine`; passes `docs/test-vectors.json` | working |
| `phone-app/` | Android app (Compose): foreground service, GATT server, pairing QR, revoke, log. Hotspot backends in `hotspot/`; see `docs/HOTSPOT_BACKENDS.md` | builds, unit-tested; not tested on real BLE or Shizuku |
| `tablet-app/` | Android app on the tablet (BLE client, Wi-Fi connect) | placeholder |
| `linux-client/` | Python 3.11+ client: protocol, real BLE (bleak/BlueZ), CLI, daemon, systemd user service, optional tray | unit-tested with mocked BLE; not tested on a real phone |
| `docs/PROTOCOL.md` | Wire protocol. **Source of truth.** | v1 draft |
| `docs/SECURITY.md` | Threat model, rate limiting, revocation, key storage | v1 draft |
| `docs/HOTSPOT_BACKENDS.md` | Hotspot backends per Android version, verified vs unverified | written |
| `docs/DECISIONS.md` | Decision log and dependency licenses | started |
| `docs/SETUP.md` | User setup guide | Linux section written; phone and tablet to do |
| `docs/TESTING.md` | What the tests prove, and the manual hardware checklist | written |
| `docs/test-vectors.json` | Deterministic crypto and framing vectors | generated |
| `docs/tools/gen_vectors.py` | Generates and self-verifies the vectors (stdlib only) | working |

## How it works, in short

1. **Pair once.** The phone shows a QR code holding its service UUID, a one-time X25519 public key and a 16-byte secret. The client scans it, does an X25519 exchange and derives a per-device long-term key with HKDF.
2. **Wake.** The phone advertises a rotating, non-identifying token. A paired client recognises it with a key derived from the long-term key.
3. **Ask.** The client writes an encrypted, replay-protected request (ChaCha20-Poly1305). The phone enables the hotspot through Shizuku and answers with the SSID and passphrase, encrypted.
4. **Connect.** The client joins the Wi-Fi network.

Details: [docs/PROTOCOL.md](docs/PROTOCOL.md). Risks and open questions are at its end.

## Build and test (Android and Kotlin)

```
./gradlew test assembleDebug     # needs JDK 17+ and the Android SDK (set sdk.dir in local.properties)
```
Debug APK: `phone-app/build/outputs/apk/debug/phone-app-debug.apk`. The Linux client has its own tests (see `linux-client/README.md`).

## Verifying the vectors

```
python3 docs/tools/gen_vectors.py          # regenerate and verify
python3 docs/tools/gen_vectors.py --check  # fail if docs/test-vectors.json is stale
```

Both implementations reproduce these vectors in their unit tests, and the Kotlin phone engine is replayed against the Python-generated frames byte for byte.

## License

MIT, see [LICENSE](LICENSE).
