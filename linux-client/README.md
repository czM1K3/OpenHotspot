# openhotspot (Linux client)

Python 3.11+ client for the OpenHotspot protocol ([docs/PROTOCOL.md](../docs/PROTOCOL.md) is the source of truth).

**Status:** the protocol logic, an in-process phone simulator, key storage, the CLI, the **real BLE transport (bleak on BlueZ)**, the Wi-Fi connector (NetworkManager), a background **daemon**, a systemd user service and an optional tray icon are implemented and unit tested with mocked BLE and NetworkManager. **Nothing has run against a real phone yet**; `docs/TESTING.md` says what is proven and gives the manual hardware checklist. User guide and troubleshooting: [docs/SETUP.md](../docs/SETUP.md), section Linux.

## Install and test

```
cd linux-client
python3 -m venv .venv && .venv/bin/pip install -e '.[dev]'
.venv/bin/python -m pytest          # ~250 tests, incl. every vector in docs/test-vectors.json
.venv/bin/python examples/demo.py   # pair/connect/status/disconnect/unpair against the FakePhone
```

## CLI

```
openhotspot pair <qr-string-or-image> [--label NAME]   # image needs the `zbarimg` tool (zbar-tools)
openhotspot connect [--auto-off SECONDS] [--no-verify] # hotspot on, join via nmcli, verify the internet
openhotspot status [--show-password]
openhotspot disconnect [--keep-hotspot]                # leave, delete the Wi-Fi profile, hotspot off
openhotspot unpair
openhotspot daemon [--mode auto|manual|off] [--once] [--tray]   # connect by itself when offline and the phone is near
openhotspot doctor                                     # check BlueZ, NetworkManager, clock, pairing
```
Global: `--keystore {auto,file,keyring}`, `--config-dir`, `--config FILE` (TOML, default `~/.config/openhotspot/config.toml`, example in `packaging/config.example.toml`), `-v`; per command `--phone ID` when several phones are paired.
Exit codes: 0 ok, 1 error, 2 usage or bad config, 3 not paired / ambiguous phone.

Background service: `packaging/install.sh [--tray]` / `packaging/uninstall.sh [--purge]` (no root; see SETUP.md).

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
| `transport` | `Transport`/`Connection` ABCs, `InMemoryTransport` |
| `ble` | `BleakTransport`: scan, connect with retries, MTU, notifications, D-Bus/BlueZ error translation |
| `client` | `HotspotClient`: scan, connect, pair, enable/query/disable/unpair, bounded retries |
| `flow` | the connect sequence shared by CLI and daemon (enable, wait ON, join, verify) |
| `daemon`, `config`, `state` | background mode, strict TOML config, the "stay quiet" state file |
| `wifi`, `connectivity`, `notify` | NetworkManager connector (hidden SSIDs, profile cleanup, hints), connectivity check, `notify-send` |
| `tray`, `doctor`, `explain` | optional tray (menu model + pystray binding), prerequisite checks, user-facing error text |
| `storage`, `qr`, `cli` | key stores, QR input, command line |

`tests/fake_phone.py` is the phone side (pairing window, trial decryption, replay checks, rate limiting, revocation, advertisements) used for end-to-end tests.

`tests/fake_ble.py` fakes bleak's scanner and client in front of `FakePhone`, so `BleakTransport` is exercised end to end (pair, connect, retries, MTU 23 to 517) without a radio.
