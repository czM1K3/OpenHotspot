# openhotspot (Linux client)

Python 3.11+ client for the OpenHotspot protocol ([docs/PROTOCOL.md](../docs/PROTOCOL.md) is the source of truth).

**Status (Batch 2):** protocol logic, an in-process phone simulator, key storage, a Wi-Fi connector and the CLI are done and tested. **Real BLE is not implemented**: `BleTransport` is a stub that raises `NotImplementedError` (Batch 4), so against a real phone the CLI stops after saying so.

## Install and test

```
cd linux-client
python3 -m venv .venv && .venv/bin/pip install -e '.[dev]'
.venv/bin/python -m pytest          # 136 tests, incl. every vector in docs/test-vectors.json
.venv/bin/python examples/demo.py   # pair/connect/status/disconnect/unpair against the FakePhone
```

## CLI

```
openhotspot pair <qr-string-or-image> [--label NAME]   # image needs the `zbarimg` tool (zbar-tools)
openhotspot connect [--auto-off SECONDS]               # hotspot on, then join it via nmcli
openhotspot status [--show-password]
openhotspot disconnect [--keep-hotspot]
openhotspot unpair
```
Global: `--keystore {auto,file,keyring}`, `--config-dir`; per command `--phone ID` when several phones are paired.
Exit codes: 0 ok, 1 error, 2 usage, 3 not paired / ambiguous phone.

Keys live in the system keyring if a real backend exists (`pip install 'openhotspot[keyring]'`), otherwise in `~/.config/openhotspot/phones.json` (directory 0700, file 0600; a looser file is refused).

## Layout

| Module | Role |
|---|---|
| `constants`, `errors` | protocol constants and enums; exception types |
| `crypto` | X25519, HKDF, HMAC, ChaCha20-Poly1305 (via `cryptography`) |
| `framing`, `messages` | frame envelope and sealed bodies; plaintext layouts |
| `chunking` | chunker and `Reassembler` (5 s timeout, strict sequence/LAST checks) |
| `advertisement` | rotating tokens, AD structure, recognition |
| `pairing` | QR payload, key derivation, client handshake, `PairedPhone` |
| `session` | `build_request` / `parse_response` (nonce-bound AAD, counter echo) |
| `transport` | `Transport`/`Connection` ABCs, `InMemoryTransport`, `BleTransport` stub |
| `client` | `HotspotClient`: scan, connect, pair, enable/query/disable/unpair |
| `storage`, `wifi`, `qr`, `cli` | key stores, `WifiConnector` + `NmcliConnector`, QR input, command line |

`tests/fake_phone.py` is the phone side (pairing window, trial decryption, replay checks, rate limiting, revocation, advertisements) used for end-to-end tests.
