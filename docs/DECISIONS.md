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

## D11. Linux client crypto library: tentative
Expected: `cryptography` (Apache-2.0 / BSD dual) for production code, BlueZ via `bleak` (MIT) for BLE, and `secretstorage`/`keyring` for key storage. To be confirmed in the Linux client batch; not yet added.

## D12. Commit and branch
Work is committed on the existing `master` branch in small steps (the repository's default branch for PRs is `main`; no remote or PR exists yet).

## Dependencies and licenses

| Dependency | Where | License | Status |
|---|---|---|---|
| Python standard library | `docs/tools/gen_vectors.py` | PSF | used |
| `cryptography` | optional cross-check in `gen_vectors.py`; tentative Linux client | Apache-2.0 OR BSD-3-Clause | optional, not required |
| Shizuku API | phone-app (planned) | Apache-2.0 | planned, to be verified when added |
| `bleak` | linux-client (tentative) | MIT | not added |
| `secretstorage` / `keyring` | linux-client (tentative) | BSD-3-Clause / MIT | not added |
| Android platform APIs | apps | n/a (AOSP, Apache-2.0) | no Google Play Services |
