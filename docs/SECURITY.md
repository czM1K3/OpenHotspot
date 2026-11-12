# OpenHotspot Security

Companion to `PROTOCOL.md` (the source of truth for wire formats). Status: v1 draft, not externally reviewed.

## 1. What we protect

1. **Hotspot credentials** (SSID and passphrase): confidentiality.
2. **Control of the phone's hotspot**: only paired clients may turn it on or off.
3. **Privacy of the phone and clients**: no stable identifier or device name over the air.
4. **Integrity of pairing**: a client ends up paired with the phone whose QR it scanned.

## 2. Threat model

**Attacker capabilities (in scope):** passive and active BLE attacker within radio range (sniff, replay, inject, relay, jam, connect to the GATT server, spoof advertisements); attacker who can read the Status characteristic; attacker who steals a *revoked* client's data; a malicious app on the client machine **without** root/same-user access.

**Out of scope for v1:** a rooted or compromised phone/OS, a malicious app with Shizuku permission (it already controls the phone), same-user malware on the client (it can read the key the client uses), hardware attacks, denial of service by radio jamming, the security of the Wi-Fi link itself once joined (WPA2/WPA3 applies), and physical coercion of the user.

| Threat | Mitigation |
|---|---|
| Eavesdropper learns SSID/passphrase | Credentials only inside ChaCha20-Poly1305 under `K_resp`, derived from a 256-bit `LTK` |
| Attacker at pairing time | QR carries a 16-byte secret mixed into HKDF; needs line of sight to the QR. 120 s validity, single use, 3 attempts |
| Rogue phone impersonation at pairing | Client trusts only the `phone_pub` from the QR; the AEAD reply and `confirm` MAC prove knowledge of the secret and key |
| Attacker connects and sends junk | Trial-decrypt fails; unauthenticated `AUTH_FAILED`; rate limited (section 3) |
| Replay of an old request | Random nonce, timestamp ±60 s, strictly increasing per-client counter |
| Response replay or reflection | Directional keys; response AAD binds the request nonce; counter echo |
| Tracking the phone by advertisement | Rotating HMAC token (5 min), no name, per-client keys |
| Tracking the client | No client identifier on the wire (trial decryption), random nonces |
| Stolen or lost client | Revocation (section 4) |
| Downgrade or confusion across versions | `OHS1` domain-separated labels; explicit version byte |

## 3. Rate limiting of failed attempts

Tracked on the phone, **globally** (an attacker has no stable identity), in memory and persisted so that a restart does not reset it.

An *authentication failure* is: AEAD failure with every stored key, a malformed frame, a chunking violation, or an unauthenticated write to Request.

- **Connection level:** at most 1 active GATT connection is serviced at a time for unauthenticated peers; further connections are dropped. A connection that sends no valid, authenticated frame within **10 s** is disconnected.
- **Per-connection:** 3 failures disconnect the peer.
- **Global backoff:** after **5 failures in 60 s** the phone enters lockout. During lockout it answers every write with unauthenticated `RATE_LIMITED` (`retry_after_s` = remaining time) **without** attempting decryption. Lockout lasts 30 s, doubling per consecutive lockout up to 1 h. It resets after one hour without failures or one successful authenticated request (which MUST NOT be able to cancel an active lockout early: success is only possible once the lockout ends).
- **Pairing:** 3 failed `PAIR_REQ` close the pairing window; a new QR (new keypair and secret) is required. The window also expires after 120 s.
- A user-visible notification on the phone reports lockouts ("N failed attempts from nearby devices").

Note: brute-forcing a 256-bit key or a 128-bit pairing secret is infeasible; rate limiting here is about resource use, noise and defence in depth, not the main security barrier.

## 4. Revocation

- **Phone side (authoritative):** the user removes a client in the app, which deletes that client's `LTK`, counter and label. From then on its requests fail trial decryption (`AUTH_FAILED`) and its advertisement token is no longer produced. "Remove all" erases every key. Because tokens and traffic keys are per client, no other client needs to change.
- **Client side:** the client sends `UNPAIR` (best effort) and deletes its `LTK`. If the phone is unreachable, the phone-side removal still applies.
- **Lost phone / factory reset:** all pairings are void; clients must re-pair. Clients cannot distinguish "revoked" from "phone reset" from an attacker (the error is unauthenticated), so they show "pairing may have been removed" and never auto-delete on one error.
- **Passphrase rotation:** a revoked client may know the current hotspot passphrase. Revocation therefore does not stop it joining the Wi-Fi network if the passphrase is unchanged. The app SHOULD offer "rotate the hotspot passphrase on revoke" (see PROTOCOL.md R10); other clients get the new one on their next request.
- Future (not in v1): the phone-side confirmation prompt per new client and a "last used" audit list.

## 5. Key storage

| Platform | Secret | Storage |
|---|---|---|
| Phone (Android) | per-client `LTK`, counters; transient pairing keypair and secret | LTKs encrypted with a non-exportable AES-256-GCM key in the **Android Keystore** (StrongBox when available, else TEE-backed), blob in app-private storage with auto-backup **disabled**. Pairing keypair and secret live in memory only |
| Tablet (Android) | per-phone `LTK`, counter | Same scheme as the phone |
| Linux client | per-phone `LTK`, counter | Freedesktop **Secret Service** (via the `keyring` package) when a real backend exists (`--keystore auto`). Fallback: `~/.config/openhotspot/phones.json` (`$XDG_CONFIG_HOME`), mode `0600` in a `0700` directory, written atomically; a group- or world-accessible file is refused. The file is only as safe as the user account |

Rules: LTK never leaves the device except through the QR-based pairing, is never logged, never part of a backup or cloud sync, and is zeroised in memory when practical. The counter MUST be persisted before the request is sent (client) and before the action is taken (phone).

## 6. Known limitations

1. **Pairing relies on the QR being private.** Anyone who photographs it within the 120 s window can pair (and the phone shows no per-pairing confirmation in v1). The phone app blocks screenshots of the QR screen (`FLAG_SECURE`) and closes the window when the screen leaves the foreground, but cannot stop a camera.
2. **Delayed replay within 60 s** can trigger one hotspot activation (PROTOCOL.md section 6, R8). Nothing leaks.
3. **Anyone in range can read Status** (ready, pairing window open, hotspot active) and can tell that an OpenHotspot phone is nearby (R12).
4. **The Wi-Fi passphrase is a long-lived secret** transferred to every paired client; compromise of any client exposes it (section 4).
5. **No forward secrecy for requests and responses.** `LTK` compromise decrypts recorded traffic. Fixing this needs an ephemeral exchange per session (extra round trip); deferred.
6. **Clock dependence** (R7): tokens and replay window need roughly correct clocks. A client with a wrong clock will fail rather than fall back.
7. **BLE address privacy depends on the Android stack** (R3).
8. **Denial of service** by jamming, by flooding connections, or by deliberately triggering the lockout (which blocks legitimate clients for up to the lockout duration). Accepted trade-off.
9. **Shizuku is a high-privilege dependency.** Anything that gains Shizuku permission on the phone can bypass the protocol.
10. **Android crypto provider specifics are unverified** (R1) and implementations are not constant-time audited. Vectors catch correctness, not timing leaks.
11. **No external security review** has been done.
12. **The Wi-Fi passphrase appears briefly in the process list on Linux.** `nmcli device wifi connect ... password <pass>` takes it as an argument (DECISIONS.md D16), so another local user can read it from `/proc/<pid>/cmdline` for the duration of the call. Same for the pairing QR string if passed as an argument to `openhotspot pair` (it is single-use and expires in 120 s, so the exposure is short). The shell hotspot backend on the phone has the same property inside the Shizuku shell process.
13. **Any app on the tablet can ask the tablet app to connect.** `MainActivity` is exported (it is the launcher) and honours the `org.openhotspot.tablet.CONNECT` action, which the Quick Settings tile uses. A malicious app on the same tablet can therefore trigger a connect attempt (turn the phone's hotspot on). It learns no secret. Same-device malware is out of scope for v1 (section 2).
14. **Linux key file read-modify-write is not locked.** The CLI and the daemon can both update the counter; a lost update can only make a later request fail with `REPLAY` (the client then retries with a higher counter), never reuse a counter unsafely, because the client increments from its own in-memory value and the phone only accepts strictly higher ones.

## 7. Implementation review (2026-11-12)

A read-through of the code against sections 2 to 6, plus the tests named below. Everything here is **code and unit-test level**: no BLE exchange with real hardware has been tested for security properties (TESTING.md).

| Property | Result | Evidence |
|---|---|---|
| Replay | OK. Counter strictly increasing and compared **unsigned**; timestamp within +-60 s; counter committed (fsync) before the action; replayed and rolled-back counters rejected without acting | `PhoneEngineTest.replayedRequestIsRejectedWithoutActingOrRateLimiting`, `clientCounterRollbackIsRejected`, `counterCompareIsUnsigned`, `staleTimestampIsRejectedInsideAndOutsideTheWindow`, `tamperedRequestIsRejectedWithNoEffect`; Python `test_e2e.py`, `test_crypto_and_vectors.py` |
| Response reflection / replay | OK. Directional keys, request nonce in the response AAD, counter echo verified by both clients | the `enable_response` and `negative` vectors in both languages |
| Downgrade | OK. Version byte checked first (`UNSUPPORTED_VERSION`), all HKDF labels carry `OHS1 `, unknown flags/types/trailing bytes are `MALFORMED`, `ENABLE` options must be 0. An unauthenticated error never deletes a pairing and is only a hint | `FramingChunkingTest`, `test_framing_chunking.py`, `EngineVectorsTest` |
| Rate limiting (section 3) | OK in the engine: 5 failures per 60 s then 30 s lockout, doubling to 1 h, no decryption while locked out, 3 failures per connection, lockout persisted across restarts (`RateLimitPrefs`, committed synchronously). **Fixed in this review:** the 10 s deadline for unauthenticated BLE connections was reset by every write, so one peer sending junk, CCCD writes or half a frame could hold the single unauthenticated slot indefinitely and lock out real clients; it is now measured from connect time (`GattServerController`, **not covered by an automated test**: it needs a real GATT stack) | `globalLockoutBlocksValidRequestsWithoutDecrypting`, `lockoutDoublesUpToOneHourThenResetsAfterAQuietHour`, `lockoutSurvivesARestart`, `connectionIsToldToCloseAfterThreeFailures` |
| Key storage | OK by design: Keystore AES-256-GCM (StrongBox when present) on both Android apps, files in `noBackupFilesDir`, `allowBackup=false` and empty backup/transfer rules, atomic fsync'd writes, unreadable file kept as `.corrupt` rather than overwritten. Linux: keyring or 0600 file. **Not verified on a device:** that StrongBox/TEE key creation works on the Nothing phone and the Samsung tablet | `EncryptedClientStoreTest`, `test_storage_wifi.py` |
| Secrets in logs | None found by search and by test. **Fixed in this review (Linux):** the daemon logged the SSID of the hotspot to the journal, and its progress callback did too; both removed. Dataclass `repr()`s of `HotspotInfo`, `PairedPhone`, `PairingQr`, `SessionKeys` now hide passphrase, `LTK`, QR secret and derived keys (the Kotlin classes already override `toString`). Phone log lines never contain SSID, passphrase or keys; the shell backend logs `<ssid> <passphrase>` placeholders | `PhoneEngineTest.logNeverContainsSecrets`, the redaction tests in `ShellCommandBackendTest`, Python `test_no_secret_leaks.py` |
| QR exposure window | OK. 120 s expiry (checked against the wall clock on every use, so a clock set backwards on the phone would extend a window that is already open), single use (erased on success), closed after 3 failed `PAIR_REQ`, closed when the screen stops or the Pairing tab is left, `FLAG_SECURE` while shown, keypair and secret in memory only. The tablet's `QrGate` accepts one parsing QR and never echoes the payload | `pairingWindowIsSingleUse`, `wrongSecretIsRejectedAndThreeFailuresCloseTheWindow`, `pairingWindowExpiresAfter120Seconds`; UI behaviour (`ON_STOP`, `FLAG_SECURE`) is **not** automatically tested |
| Pairing downgrade/MITM | OK in design: the client trusts only `phone_pub` from the QR, and the `PAIR_RESP` AEAD plus `confirm` MAC prove the phone knew the secret. Low-order X25519 points are rejected | `sharedSecretRejectsLowOrderPeerInPairing` |

Still open after this review: the points in section 6, the lack of a test for the GATT-level timeout, no constant-time audit of the Kotlin crypto (D9, D20), unverified behaviour of `FLAG_SECURE` on the user's ROM, and unauthenticated plaintext `RATE_LIMITED` replies, which any in-range device can provoke by deliberately failing 5 times (limitation 8).
