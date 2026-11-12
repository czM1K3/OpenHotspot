# OpenHotspot

Turn on the internet-sharing hotspot of a stock Android phone from a Linux laptop or an Android tablet over Bluetooth LE, and connect automatically, similar to Apple's Instant Hotspot. The phone side uses [Shizuku](https://shizuku.rikka.app/) to toggle tethering without root. No Google Play Services.

**Status: design and scaffolding only. There is no application code yet.**

## Layout

| Path | Purpose | State |
|---|---|---|
| `common/` | Kotlin protocol and crypto, shared by both Android apps | placeholder |
| `phone-app/` | Android app on the phone (BLE GATT server, Shizuku tethering control) | placeholder |
| `tablet-app/` | Android app on the tablet (BLE client, Wi-Fi connect) | placeholder |
| `linux-client/` | Python 3.11+ client (BlueZ, NetworkManager) | placeholder |
| `docs/PROTOCOL.md` | Wire protocol. **Source of truth.** | v1 draft |
| `docs/SECURITY.md` | Threat model, rate limiting, revocation, key storage | v1 draft |
| `docs/DECISIONS.md` | Decision log and dependency licenses | started |
| `docs/SETUP.md` | User setup guide | not yet written |
| `docs/test-vectors.json` | Deterministic crypto and framing vectors | generated |
| `docs/tools/gen_vectors.py` | Generates and self-verifies the vectors (stdlib only) | working |

## How it works, in short

1. **Pair once.** The phone shows a QR code holding its service UUID, a one-time X25519 public key and a 16-byte secret. The client scans it, does an X25519 exchange and derives a per-device long-term key with HKDF.
2. **Wake.** The phone advertises a rotating, non-identifying token. A paired client recognises it with a key derived from the long-term key.
3. **Ask.** The client writes an encrypted, replay-protected request (ChaCha20-Poly1305). The phone enables the hotspot through Shizuku and answers with the SSID and passphrase, encrypted.
4. **Connect.** The client joins the Wi-Fi network.

Details: [docs/PROTOCOL.md](docs/PROTOCOL.md). Risks and open questions are at its end.

## Verifying the vectors

```
python3 docs/tools/gen_vectors.py          # regenerate and verify
python3 docs/tools/gen_vectors.py --check  # fail if docs/test-vectors.json is stale
```

Every implementation (Kotlin, Python) must reproduce these vectors in its unit tests.

## License

MIT, see [LICENSE](LICENSE).
