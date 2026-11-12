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

## D9. Crypto provider on Android: OPEN
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

## Dependencies and licenses

| Dependency | Where | License | Status |
|---|---|---|---|
| Python standard library | `docs/tools/gen_vectors.py` | PSF | used |
| `cryptography` | `linux-client` (required); optional cross-check in `gen_vectors.py` | Apache-2.0 OR BSD-3-Clause | used |
| Shizuku API | phone-app (planned) | Apache-2.0 | planned, to be verified when added |
| `bleak` | linux-client BLE transport (Batch 4) | MIT | not added |
| `keyring` | linux-client optional extra | MIT | used (optional) |
| `secretstorage` | via `keyring` on Linux (transitive) | BSD-3-Clause | transitive, optional |
| `pytest` | linux-client dev only | MIT | dev only |
| `zbarimg` (ZBar) | external CLI for QR images, not linked | LGPL-2.1 | optional external tool |
| Android platform APIs | apps | n/a (AOSP, Apache-2.0) | no Google Play Services |
