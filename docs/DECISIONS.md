# Decisions

Log of design decisions, newest at the bottom. Each entry: decision, why, and what it costs.

## D1. Monorepo with four components
`common` (Kotlin protocol/crypto), `phone-app`, `tablet-app`, `linux-client` (Python 3.11+), `docs`. The protocol is specified in `docs/PROTOCOL.md` and pinned by language-neutral vectors so Kotlin and Python implementations cannot drift.

## D2. MIT license
Per the project rules. Dependencies must be MIT-compatible (MIT, BSD, Apache-2.0, ISC); every dependency's license is listed below.

## D3. Application-layer security only; no BLE pairing or bonding
BLE legacy/LE Secure Connections pairing is platform specific, can trigger OS dialogs and gives no control over key material. We do our own X25519 + HKDF + ChaCha20-Poly1305 and treat the BLE link as plaintext. Cost: we own the crypto design (hence test vectors and a review item in SECURITY.md).

## D4. Pairing by QR with a one-time keypair and 16-byte secret
Out-of-band secret defeats a BLE eavesdropper or MITM without needing a PIN or a user comparison step. 120 s window, single use. Alternative considered: SAS/numeric comparison; rejected for v1 as extra UX for little gain given the QR's line-of-sight requirement. Cost: anyone who photographs the QR in the window can pair.

## D5. Compact binary framing, not CBOR
Frames are small and fixed layouts; a 7-byte header plus fixed fields is trivial to implement identically in Kotlin and Python without a dependency and is easy to bound (512 bytes). CBOR would add a library per platform (licenses to track) and canonicalisation questions. Cost: extensibility is by version bump and appending fields (PROTOCOL.md section 10).

## D6. Random 96-bit nonces; counter and timestamp inside the plaintext
Random nonces remove a nonce-reuse failure if a counter is restored from backup. Putting counter and timestamp in the AEAD plaintext hides them from observers (no linkability). Directional keys (`K_req`, `K_resp`) and request-nonce AAD binding prevent reflection and response replay.

## D7. No client identifier on the wire; trial decryption
The phone tries each paired client's key (max 8). This keeps clients untrackable. Cost: a bounded amount of work per request; hence the 8-client cap (R14).

## D8. Per-client advertisement tokens, round-robin
Token = truncated HMAC over a 5-minute window, key derived per client so revoking one client does not require re-keying the others, and no shared secret is needed. Cost: depends on Android allowing the service data to change about once per second (R6). Fallback documented there.

## D9. Crypto provider on Android: RESOLVED in D20 (pure Kotlin in :common)
Whether to use the platform provider (ChaCha20-Poly1305 and X25519 availability per API level is unverified, see R1) or a small library. Must be decided, with its license added below, before `common` is implemented. First step of the next batch: test on the actual devices.

## D10. Python vectors tool is stdlib-only with its own reference implementations
`gen_vectors.py` implements X25519, ChaCha20-Poly1305 and HKDF itself, proves them against RFC 7748/8439/5869 known answers, and cross-checks them with the `cryptography` package when present. This keeps the vectors independent of any single library and removes the dependency for contributors. It is for vectors only and is **not** a production crypto implementation (not constant-time).

## D11. Linux client crypto library: tentative (settled in D13)
Expected: `cryptography` (Apache-2.0 / BSD dual) for production code, BlueZ via `bleak` (MIT) for BLE, and `secretstorage`/`keyring` for key storage. To be confirmed in the Linux client batch; not yet added.

## D12. Commit and branch
Work is committed on the existing `master` branch in small steps (the repository's default branch for PRs is `main`; no remote or PR exists yet).

## D13. Linux client: layout, async transport, crypto library (settles D11)
`linux-client/` is a `src`-layout package `openhotspot` (setuptools). Production crypto uses `cryptography` (a required dependency); it is independent of the stdlib reference in `gen_vectors.py`, and the tests prove both reproduce the same vectors. `Transport`/`Connection` are `async` (bleak, Batch 4, is asyncio-based) and chunk-level, so chunking and reassembly are exercised end to end even in memory. Scanning takes a matcher callback so a real scanner can stop at the first match. `keyring` is an optional extra. `bleak` is not added yet.

## D14. FakePhone lives in the tests package and shares primitives
`tests/fake_phone.py` implements the phone's behaviour (pairing window, trial decryption, replay and timestamp checks, global and per-connection rate limiting, lockout doubling, revocation, advertisements, Status) but reuses the package's crypto, framing and chunking code. A bug in those shared modules would therefore be mirrored, which is why they are also pinned by RFC known answers and by `docs/test-vectors.json`. The Kotlin phone app must pass the same vectors. Its rate limiter follows SECURITY.md section 3; ordering details are decided there, not by the fake.

## D15. Key storage default is `auto`
`auto` uses the system keyring only if a real backend exists (not `keyring.backends.fail`), else `~/.config/openhotspot/phones.json` (0700 directory, 0600 file, atomic replace). A key file that is group/world accessible is refused rather than silently fixed, since the key may already be exposed. The request counter is saved before each request is sent (PROTOCOL.md section 6).

## D16. Wi-Fi via `nmcli`; passphrase appears in its argument list
`nmcli device wifi connect SSID password PW name openhotspot-SSID`. NetworkManager chooses WPA2 or WPA3 from the scan. Known weakness: the passphrase is visible in the process list to other local users for the duration of the call. A safer route (D-Bus via NetworkManager's API, or a keyfile profile) is deferred; it needs testing on real NetworkManager. Non-UTF-8 SSIDs and SSIDs starting with `-` are refused. Error text has the passphrase masked. Our own profiles are prefixed `openhotspot-`, and `disconnect` touches only those.

## D17. QR images are decoded by the external `zbarimg` tool
No Python image or QR library is added (fewer dependencies and licences). If `zbarimg` is missing the CLI says so and the QR string can be passed directly. ZBar is LGPL and is only executed as a separate program.

## D18. Client does not fail fast on the Status characteristic
PROTOCOL.md allows reading Status first. The client reads it only for the `status` command: Status is unauthenticated, and the phone already answers a request with an authenticated `SHIZUKU_UNAVAILABLE` or `PERMISSION_DENIED`, which is more trustworthy and just as fast.

## D19. Protocol fixes found while implementing the client
1. Errors that occur during pairing after the `PAIR_REQ` decrypts are encrypted with `K_pair` (the spec had no key for them). PROTOCOL.md 4.3 and the error table were updated.
2. The advertisement vector used Python's `uuid.bytes_le` (mixed-endian GUID layout) instead of the fully reversed bytes BLE requires. `gen_vectors.py`, `test-vectors.json` and PROTOCOL.md section 3 were corrected; the Kotlin implementation must follow the corrected vector.
3. In the client, authenticated error frames are surfaced before the local timestamp-skew check, so a clock problem reports the phone's `STALE_TIMESTAMP` instead of a local protocol error.

## D20. Crypto in `:common` is pure Kotlin, not the platform provider (resolves D9)
Android guarantees X25519 only from API 33 and our minSdk is 30, so relying on the platform would break Android 11 and 12 phones. `:common` therefore implements X25519 (a port of public-domain TweetNaCl), ChaCha20 and Poly1305 (26-bit limbs, after poly1305-donna) itself. HKDF and HMAC use `javax.crypto.Mac` (available everywhere). Safety net: RFC 7748, 5869 and 8439 known answers; randomized differential tests against the JDK's own X25519 and ChaCha20-Poly1305 (every length 0..130 and more); a test that flips every bit of a sealed message; and replaying the Python vectors. The code avoids secret-dependent branches and tables, but the JVM gives no constant-time guarantee and the implementation has had no external audit. Revisit if a vetted library with an acceptable licence is ever preferred.

## D21. Build toolchain: AGP 8.13 / Gradle 8.14.3 / Kotlin 2.2.21
The newest releases (AGP 9.x, Gradle 9.x, Kotlin 2.5 RC) change the Android DSL and plugin model substantially. We chose the previous, well-understood generation and pinned everything in `gradle/libs.versions.toml`. `compileSdk` 36, `targetSdk` 35, `minSdk` 30, build tools 35.0.1. Sources compile with the JDK 21 toolchain and emit Java 17 bytecode (what Android and D8 expect). The Gradle wrapper's `distributionSha256Sum` is pinned to Gradle's published checksum. Migrating to AGP 9 is future work. `local.properties` (the SDK path) is not committed.

## D22. Phone-side protocol engine lives in `:common`
`PhoneEngine` (pairing window, trial decryption, replay and timestamp checks, rate limiting, dispatch, advertisement tokens) is pure JVM and sits in `:common` behind `ClientStore`, `HotspotController`, `Clock`, `RandomSource` and `EngineLog` interfaces. That makes the security-critical logic unit-testable without a device, lets the Kotlin side be replayed against the Python vectors byte for byte, and keeps `:phone-app` to moving bytes. The tablet app does not use it.

## D23. Phone key storage: Keystore AES-256-GCM over an app-private file
Client keys are written as one blob encrypted with a non-exportable AES-256-GCM key in the Android Keystore (StrongBox if available, else TEE), AAD-bound, atomically replaced (temp file, fsync, rename) in `noBackupFilesDir`. Backup and device transfer are disabled. No user-authentication requirement, since the service must work on a locked phone. If the blob cannot be decrypted (lost key, tampering) the app starts with no clients and keeps the file as `clients.bin.corrupt` rather than trusting or deleting it. Lockout state is persisted in plain SharedPreferences (not secret).

## D24. Phone app structure and Android specifics
- **Permissions:** API 30 needs only install-time `BLUETOOTH`/`BLUETOOTH_ADMIN`; API 31+ asks for `BLUETOOTH_ADVERTISE` and `BLUETOOTH_CONNECT`; API 33+ adds `POST_NOTIFICATIONS`. `BLUETOOTH_SCAN` is deliberately not requested because the phone never scans (on API 30 it would also pull in location).
- **Service:** foreground service of type `connectedDevice`, `START_STICKY`. A boot/package-replaced receiver restarts it only if the user left it running, start-on-boot is on and Bluetooth permission is held (BOOT_COMPLETED is exempt from the background FGS-start rules, and connectedDevice is not an Android 15 restricted type).
- **GATT:** each connection has an ordered input channel, an ordered output channel and exactly one notification in flight (gated on `onNotificationSent`). One unauthenticated connection at a time; 10 s idle limit before authenticating, 30 s after. Writes are acknowledged at the GATT level immediately; protocol errors travel in notifications.
- **Advertising:** one advertisement, no name, no TX power, only the service data. A 1 s loop restarts it only when the token changes (so a single client restarts every 5 minutes; several clients rotate each second, which is the open risk R6).
- **UI:** pairing QR only while the pairing screen is in the foreground (closed on stop) and with `FLAG_SECURE` (screenshots come out black, verified on an emulator); the log is an in-memory ring buffer that is never persisted and never fed secrets.
- **Battery:** the exemption is requested with the one-tap system dialog and re-checked on resume.

## D25. Spec clarifications found by the second implementation
The counter is an unsigned 64-bit integer, compared unsigned. A pairing label must be valid UTF-8 (else `MALFORMED`). In v1 a nonzero ENABLE `options` byte gets `UNSUPPORTED_REQUEST`. PROTOCOL.md, the Python fake phone and the Kotlin engine all follow. Beyond these, the Kotlin engine reproduced the Python vectors byte for byte on the first run.

## D26. Hotspot control: three backends behind one controller
`RealHotspotController` implements the existing `HotspotController`. Backends (Shizuku+TetheringManager, Shizuku+`cmd wifi`, local-only) sit behind `HotspotBackend`; Shizuku and the Wi-Fi binder sit behind `ShizukuGateway`, `TetheringApi`, `WifiBinderApi`, `ShellRunner`, so selection, fallback and auto-off are JVM-tested. Details and verification status: `docs/HOTSPOT_BACKENDS.md`. Android 11 is supported by the tethering backend only; the shell backend needs 12+ (Android 11's `start-softap` quotes the SSID).

## D27. Reflection runs in our process, so hidden-API exemption is required
Shizuku executes only the binder transactions as shell; our code builds `TetheringManager` and `IWifiManager` proxies by reflection in the app process, where hidden-API enforcement applies. We use `org.lsposed.hiddenapibypass` (Apache-2.0) at version 6.1, because 6.2 requires compileSdk 37 which AGP 8.13 does not support. Risk: it may stop working on a future Android release (unverified on 17). Alternative not taken: a Shizuku UserService (runs unrestricted in the shell process) needs AIDL and more plumbing; it is the planned fallback if the bypass fails on the device.

## D28. Shell commands via reflection on `Shizuku.newProcess`
`newProcess` is `private static` in Shizuku API 13.1.5 (checked with javap). We call it reflectively with an argument array (no shell). A UserService is the supported route but heavier. Revisit together with D27.

## D29. No-internet fallback is on by default, never chosen silently after a credentials problem
The user asked for the local-only fallback. It is allowed by default, loudly labelled in the UI and log, and switchable off in Settings. A missing-credentials failure stops the fallback chain instead of producing a no-internet hotspot. The protocol cannot yet tell a client that the hotspot has no internet (needs a protocol change: doc and vectors first).

## D30. Auto-off and credential rules
Auto-off: shorter of client request and user setting wins; unknown client count means no auto-off. Credentials: the tethering backend reports the saved system config (what the OS actually starts), the shell backend the user entry. User-entered passphrases are stored Keystore-encrypted. The log and diagnostics export never contain SSIDs or passphrases (`Redactor`, only lengths in test steps).

## D31. Shell backend is dead on Android 17 with adb-mode Shizuku; kept as a root-mode fallback
Device test (Nothing A059, Android 17): `cmd wifi start-softap` and `stop-softap` throw `SecurityException: Uid 2000 does not have access`. The TetheringManager backend works and local-only works. The shell backend is kept (it may work on older versions or with root-mode Shizuku, unverified) but the probe now reports the refusal, so it is never selected on this phone.

## D32. Real BLE on Linux: bleak 3.x over BlueZ, filtered in Python, MTU from the characteristic
`BleakTransport` (`linux-client/src/openhotspot/ble.py`) uses `bleak>=3.0,<4`; only 3.1.0 was read and exercised, since its API differs from older releases (`bluez=` argument dict, `BleakBluetoothNotAvailableError`). Choices:
- **Scan unfiltered and match the service-data key in Python.** BlueZ's discovery UUID filter is documented for advertised service UUIDs; that it also covers *service data* keys is not something we verified, so we do not rely on it. Cost: more callbacks while scanning, bounded by the short scan windows (D35).
- **MTU:** bleak's public `mtu_size` is hard-wired to 23 on BlueZ. We read `max_write_without_response_size + 3` from the Request characteristic (BlueZ's `GattCharacteristic1.MTU`, BlueZ >= 5.62; older BlueZ reports 20, which we treat as "unknown, use 23", PROTOCOL.md R13) and wait up to 1 s for it to rise above 20, since it can lag the connection. Unverified against a real phone.
- **Passive scanning is optional** (`[ble] passive_scan`): a BlueZ advertisement monitor with an or-pattern on AD type `0x21` and the service UUID; if BlueZ refuses it, we fall back to an active scan.
- **Errors:** every bleak, D-Bus and asyncio failure is translated to `BluetoothError` (summary, hint, `retryable`) in one function, `translate_error`. The D-Bus error names in its table come from BlueZ documentation and bleak's source, not from observing a broken system.

## D33. Retries are bounded and never reuse a counter
`BleakTransport.connect` retries transient BlueZ errors (in progress, connection aborted, timeout) once more. `HotspotClient` retries a whole request on a dropped link with a **new connection, a fresh scan and a new counter** (persisted before sending, as always), so a retry can never look like a replay. Not retried: no phone found (the scan already waited), a response timeout (the phone already had its 20 s), and any error the phone itself reports. The default for library users is one attempt; the CLI and daemon use `[ble] attempts` (2). Cost: a request whose answer was lost may run on the phone twice (ENABLE is idempotent).

## D34. One connect sequence; verify the internet; CLI keeps, daemon undoes
`flow.connect_flow` is used by both: enable, wait for ON, join, then verify with NetworkManager's own connectivity probe (optionally plus an HTTP URL). "Online" means NetworkManager's state is full; limited/captive-portal counts as offline. If joining works but the internet does not (phone without data, or the local-only fallback of D29) the CLI exits 1 but leaves the connection for inspection, while the daemon removes it and turns the hotspot off, because a dead network can block the laptop from joining a real one. The protocol still cannot say "this hotspot has no internet" (D29); that would be a protocol change.

## D35. Daemon policy
- Modes: `auto` connects, `manual` only notifies (one notice per interval) and `off` idles. The tray's *Connect now* overrides mode and cooldown.
- Power: no scan at all while online (it polls NetworkManager's state); offline it scans `scan_duration_s` every `scan_interval_s` (6 s / 60 s, about a 10 % duty cycle), backing off by doubling to `max_scan_interval_s` after five misses; no scanning during a cooldown. Power use is **unmeasured** (TESTING.md section 7).
- Cooldown is set *before* an attempt, so neither a crash nor a failure can cause a retry storm. After the user's own `disconnect`, the CLI writes `suppress_until` into `~/.local/state/openhotspot/state.json` and the daemon honours it (default 1 h). The file only affects politeness; a corrupt one is ignored.
- Only one phone is handled (R15): `phone = "<id prefix>"` in the config picks it when several are paired.
- The config is strict TOML: unknown keys, wrong types and silly ranges are errors (exit code 2), so a typo never silently turns a feature off.

## D36. NetworkManager handling
Unchanged: `nmcli device wifi connect` with the passphrase in the argument list (D16). Added: (a) a profile of ours left from an earlier session is deleted first, so a changed passphrase is used; (b) if the SSID is not in the scan results, rescan once, then retry with `hidden yes` (the protocol has no hidden flag, R9); (c) `disconnect` now deletes all `openhotspot-*` profiles by UUID (not just deactivating them), as the user asked; (d) failures get a hint chosen by substrings of nmcli's message. **Those substrings come from documentation and memory, not from captured real output**, so TESTING.md section 4 asks for the real texts to be compared.

## D37. Tray, units, scripts, doctor
- The tray (`pystray` 0.19.5, LGPL-3.0, with Pillow) is an optional extra and never imported otherwise. The daemon's asyncio loop runs in a worker thread and the tray owns the main thread. Menu logic is in `TrayModel` and is unit tested; the pystray binding is not (it needs a display). The icon is drawn with Pillow at run time, so there are no image assets to ship. Mode changes from the tray last until restart; they are not written to the config.
- Two static user units: `openhotspot.service` (`default.target`) and `openhotspot-tray.service` (`graphical-session.target`, conflicts with the first), because the tray needs the desktop's environment. Hardening is only `NoNewPrivileges=yes`: stronger sandboxing options can fail on kernels without unprivileged user namespaces and could not be tried on a variety of systems.
- `install.sh` needs no root: private venv under `~/.local/share/openhotspot`, symlink in `~/.local/bin`, config copied only if absent. `uninstall.sh` keeps keys and config unless `--purge`.
- `openhotspot doctor` runs read-only checks (BlueZ version, adapter power, NetworkManager, Wi-Fi radio and device, NTP) with the remedy for each failure. Minimum BlueZ: 5.55 (bleak), 5.62 recommended (MTU).

## D38. Async tests without a plugin
`tests/conftest.py` runs `async def` tests with `asyncio.run` through a `pytest_pyfunc_call` hook, so `pytest-asyncio` is not needed (fewer dependencies to track). `tests/fake_ble.py` fakes bleak's scanner and client in front of the existing `FakePhone`, so `BleakTransport` runs its real code against the simulated phone.

## D39. QR scanning: CameraX + bundled ML Kit, behind a gate
Requested explicitly. `com.google.mlkit:barcode-scanning` 17.3.0 is the *bundled* variant: the APK contains `libbarhopper_v3.so` and the `.tflite` models (checked in the built APK), and the merged manifest has no ML Kit "DEPENDENCIES" module marker, so nothing is downloaded at run time. It does pull Google client libraries (`play-services-basement`, `-tasks`, `firebase-components`) as plain jars; no Play Services *app* is needed, but that was **not tested on a device without Play services**. This sits uneasily with "no Google Play Services dependency" and with the MIT-compatible rule: **ML Kit is proprietary (Google ML Kit terms), not open source**, and adds about 50 MB to the debug APK. ZXing (Apache-2.0, already used on the phone) is the open alternative; only `CameraQrScanner.kt` would change. `QrGate` accepts only a parsing OpenHotspot QR, once, and never echoes payloads (they hold the pairing secret). CameraX 1.5.3 (Apache-2.0). Repository filters in `settings.gradle.kts` were widened for `com.google.mlkit` and `com.google.firebase`.

## D40. Wi-Fi: specifier by default, suggestion as an option, and what each really gives
`WifiNetworkSpecifier` (as the task asked): immediate, system prompt, process bound to the network with `bindProcessToNetwork`, but usable by this app only; `WifiNetworkSuggestion`: whole device, system-timed. Checked against the API 36 stubs: `WifiNetworkSpecifier.Builder` has no raw-bytes SSID setter (only suggestions do, API 33+), so non-UTF-8 SSIDs fail with a clear error on the specifier path. A suggestion's "post connection" broadcast is only sent with `setIsAppInteractionRequired` and location permission (read in the SDK sources), so success is detected as "a Wi-Fi network that was not there before appeared", then an HTTP probe. The `onUnavailable` callback cannot distinguish "user declined" from "not found". Internet check: `http://connectivitycheck.gstatic.com/generate_204` over exactly that `Network`, 204 means online, other 2xx/3xx a captive portal; cleartext is allowed for that one host only (network security config). Default is the specifier because the task listed it first; the UI and SETUP.md state its limitation plainly. Flipping the default is one line in `AppSettings`.

## D41. Tablet connect flow, retries, auto-trigger
Same rules as the Linux client (D33 to D35): a dropped BLE link restarts with a fresh scan, connection and counter (at most 2 attempts); phone-reported errors are not retried; the counter is persisted before sending; an unauthenticated error never deletes a pairing. One `ConnectionController` serves button, tile and auto-trigger. Unattended attempts that join but find no internet are undone and the phone's hotspot is switched off; user attempts stay joined so the user can look, and can Disconnect. Auto-trigger: off by default; no scan while online/connecting/connected; 10 s offline debounce once per offline period; 6 s scan windows every 60 s doubling to 300 s after 5 misses (wakes early when the tablet gets online); the cooldown is set before the attempt and applies after success and failure alike. Tests caught two bugs in this logic (an overflow in the cooldown comparison, and the debounce being repeated before every scan). The tile opens the app rather than connecting directly, because the system Wi-Fi prompt needs a visible app and starting a foreground job from a tile is not guaranteed.

## D42. Tablet key storage and duplication
`EncryptedPhoneStore` repeats the phone app's Keystore AES-GCM file design (D23) in the tablet module instead of moving it into `:common`, which is pure JVM and has no Keystore; sharing would need a new Android library module. The two copies should be merged when a third consumer appears. No navigation or DI library was added; icons come from `material-icons-core`.

## Dependencies and licenses

| Dependency | Where | License | Status |
|---|---|---|---|
| Python standard library | `docs/tools/gen_vectors.py` | PSF | used |
| `cryptography` | `linux-client` (required); optional cross-check in `gen_vectors.py` | Apache-2.0 OR BSD-3-Clause | used |
| Shizuku `api` and `provider` 13.1.5 | `:phone-app` | MIT (per the artifact POM; corrects the earlier Apache-2.0 guess) | used |
| `org.lsposed.hiddenapibypass` 6.1 | `:phone-app` | Apache-2.0 (per POM) | used |
| `bleak` 3.1.0 (`>=3.0,<4`) | linux-client BLE transport | MIT | used |
| `dbus-fast` | transitive via `bleak` (D-Bus client) | MIT | transitive |
| `pystray` 0.19.5 | linux-client `tray` extra only | LGPL-3.0 (imported, not copied or bundled; optional) | optional extra |
| `Pillow` | linux-client `tray` extra only (draws the icon) | MIT-CMU (HPND) | optional extra |
| BlueZ (`bluetoothd`, `bluetoothctl`) | external daemon and tool, not linked | GPL-2.0-or-later | external, required at run time |
| NetworkManager (`nmcli`) | external tool, not linked | GPL-2.0-or-later | external, required at run time |
| `notify-send` (libnotify) | external tool, not linked | LGPL-2.1-or-later | optional external tool |
| Python `tomllib` | config parsing | PSF (stdlib, 3.11+) | used |
| `keyring` | linux-client optional extra | MIT | used (optional) |
| `secretstorage` | via `keyring` on Linux (transitive) | BSD-3-Clause | transitive, optional |
| `pytest` | linux-client dev only | MIT | dev only |
| `zbarimg` (ZBar) | external CLI for QR images, not linked | LGPL-2.1 | optional external tool |
| Android platform APIs | apps | n/a (AOSP, Apache-2.0) | no Google Play Services |
| `kotlinx-coroutines` (core, android; test for `:common`) | `:common`, `:phone-app` | Apache-2.0 | used |
| ZXing `core` | `:phone-app` (QR rendering; pure Java, no Play Services) | Apache-2.0 | used |
| AndroidX core-ktx, activity-compose, lifecycle-*, Compose BOM (ui, material3, tooling) | `:phone-app` | Apache-2.0 | used |
| Android Gradle Plugin, Kotlin Gradle plugin (build only) | build | Apache-2.0 | build only |
| CameraX (core, camera2, lifecycle, view) 1.5.3, `material-icons-core` | `:tablet-app` | Apache-2.0 | used |
| ML Kit `barcode-scanning` 17.3.0 (bundled) and its Google client libraries (`play-services-basement`, `-tasks`, `firebase-components`, `odml image`) | `:tablet-app` (QR decoding) | **Proprietary: Google ML Kit terms; some client libraries Apache-2.0.** Not MIT-compatible in the open-source sense; requested explicitly, see D39 | used |
| `kotlin-test`, Gson | tests only | Apache-2.0 | test only |
| JUnit 4 | tests only | EPL-1.0 | test only, not shipped |
