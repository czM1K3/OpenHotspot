# OpenHotspot Protocol, version 1

Status: draft. This document is the source of truth. If the protocol changes, update this file and `docs/tools/gen_vectors.py` (and regenerate `docs/test-vectors.json`) **before** any code.

Terms: **phone** = GATT peripheral/server (Android, Shizuku). **client** = GATT central (Linux laptop or Android tablet). MUST/SHOULD/MAY as in RFC 2119. All multi-byte integers are **big-endian**. "‖" is concatenation. Hex strings are lowercase.

## 1. Primitives

| Purpose | Primitive |
|---|---|
| Key agreement | X25519 (RFC 7748) |
| KDF | HKDF-SHA256 (RFC 5869); an empty salt means 32 zero bytes, as in the RFC |
| AEAD | ChaCha20-Poly1305 (RFC 8439), 32-byte key, 12-byte nonce, 16-byte tag |
| MAC | HMAC-SHA256 |
| Randomness | OS CSPRNG (`SecureRandom`, `os.urandom`) |

All HKDF `info` strings are ASCII, begin with `OHS1 ` (protocol v1 domain separation), and have no terminator.

## 2. GATT layout

Platform BLE APIs only (no Google Play Services). The UUIDs are randomly generated for this project and not registered with the Bluetooth SIG.

| Item | UUID | Properties |
|---|---|---|
| Service | `7e5f0001-8c3a-4d2b-9a61-0b4c7d9e1f20` | primary |
| Request | `7e5f0002-8c3a-4d2b-9a61-0b4c7d9e1f20` | **Write** (with response; Write Without Response MUST NOT be used) |
| Response | `7e5f0003-8c3a-4d2b-9a61-0b4c7d9e1f20` | **Notify** (CCCD) |
| Status | `7e5f0004-8c3a-4d2b-9a61-0b4c7d9e1f20` | **Read**, **Notify** (CCCD) |

No BLE-level pairing or bonding is used and none is required. All security is at the application layer (sections 4 to 7); the BLE link is treated as a plaintext, observable transport. Characteristics MUST NOT require BLE encryption, so clients never trigger an OS pairing dialog.

### Status characteristic

Unauthenticated, 2 bytes, readable by anyone in range. It leaks only coarse state:

| Byte | Meaning |
|---|---|
| 0 | Protocol version (`0x01`) |
| 1 | Bit 0: ready (Shizuku bound and permission granted). Bit 1: pairing window open. Bit 2: hotspot active. Bits 3 to 7: 0 |

Clients use it to fail fast (for example "Shizuku not running") before sending a request. It is a hint, never trusted for security decisions. The phone sends a notification when it changes.

## 3. Advertisement and recognition

The phone advertises as connectable, with **no device name and no TX power, no manufacturer data**. The only payload is one *Service Data, 128-bit UUID* AD structure (type `0x21`) keyed by the service UUID:

```
len(1)=0x1a | type(1)=0x21 | service UUID (16 bytes, the RFC 4122 big-endian form fully byte-reversed, as BLE AD structures require; this is not the mixed-endian GUID layout) | mode_ver(1) | token(8)
```

27 bytes, plus the 3-byte Flags AD that Android adds, is 30 of 31 legacy-advertising bytes. Scan response is not used.

`mode_ver`: high nibble = protocol version (`1`), low nibble = mode: `0` paired mode, `1` pairing mode.

### Token

`window = floor(unix_seconds / 300)`. Tokens rotate every 300 s.

- **Paired mode:** `token = HMAC-SHA256(K_adv, "OHS1 adv" ‖ u64(window))[0:8]`, `K_adv` per client (section 4.4).
- **Pairing mode:** `token = HMAC-SHA256(pairing_secret, "OHS1 adv-pair" ‖ u64(window))[0:8]`.

### Multiple paired clients

Each client has its own `K_adv`, so a revoked client learns nothing about future tokens. The phone has one advertisement slot, so with N paired clients it cycles through their N tokens, switching the advertised service data about once per second. N is capped at 8 clients. In pairing mode it advertises only the pairing token.

### How a paired client recognises the phone

The client scans with a filter on the service-data UUID. For each hit it recomputes the paired-mode tokens for windows `w-1, w, w+1` (tolerating clock skew up to about 5 minutes) with each stored `K_adv` and accepts a match. The token is **only a hint to decide whether to connect**; the real authentication is the AEAD in the first request. A false positive costs one wasted connection.

Privacy note: the 8-byte token is 64 bits, rotates every 5 minutes, and is not linkable to the phone without `K_adv`. The BLE address is a separate matter, see risk R3.

### Pairing-mode advertisement

Only while the user has the pairing QR on screen (max 120 s, section 4.2). Clients scanning for pairing know the pairing secret from the QR, so they compute the pairing token the same way and ignore everything else.

## 4. Pairing

### 4.1 QR payload

The phone generates a fresh X25519 keypair `(phone_priv, phone_pub)` and a random 16-byte `pairing_secret` each time the pairing screen opens. Binary payload (65 bytes):

```
version(1)=0x01 | service UUID (16, RFC 4122 byte order) | phone_pub (32) | pairing_secret (16)
```

Encoded as `openhotspot://pair/` followed by base64url without padding (87 characters). The test vector includes a full example.

### 4.2 Pairing window rules

- The keypair and secret are valid for **120 s**, or until one pairing succeeds, whichever is first; then they are erased.
- Pairing requests are only accepted while the QR screen is in the foreground.
- After **3** failed `PAIR_REQ` attempts, the window closes (erase and require a new QR).
- At most 8 paired clients; otherwise `TOO_MANY_CLIENTS`.

### 4.3 Handshake

1. Client scans the QR, connects to the phone (found by pairing token, section 3), generates an ephemeral keypair `(client_priv, client_pub)`.
2. Both sides compute `dh = X25519(client_priv, phone_pub)` = `X25519(phone_priv, client_pub)`. Reject an all-zero result.
3. `transcript = service_uuid(16) ‖ phone_pub(32) ‖ client_pub(32)`.
4. Derivation:

```
K_pair = HKDF(salt = pairing_secret, ikm = dh, info = "OHS1 pair" ‖ transcript, L = 32)
LTK    = HKDF(salt = pairing_secret, ikm = dh, info = "OHS1 ltk"  ‖ transcript, L = 32)
```

`LTK` is the **per-device long-term key**. The phone stores one `LTK` per paired client; the client stores one per phone. `K_pair` is used only for the two pairing messages.

5. Client sends `PAIR_REQ` (type `0x01`), body `client_pub(32) ‖ nonce(12) ‖ AEAD(K_pair, nonce, aad, plaintext)`. `aad = header(5) ‖ client_pub(32)`. Plaintext: `timestamp_ms(8) ‖ label_len(1) ‖ label` (UTF-8, at most 32 bytes, a display name such as "Michal's laptop", shown in the phone's paired-clients list). A label that is not valid UTF-8 or exceeds 32 bytes is `MALFORMED` (authenticated, `K_pair`). Phones SHOULD treat the label as untrusted text when displaying it.
6. Phone decrypts. Failure counts toward the 3-attempt limit. On success it checks the timestamp (section 6), stores `LTK` and the label with `last_counter = 0`, and replies `PAIR_RESP` (type `0x81`): `nonce(12) ‖ AEAD(K_pair, nonce, header ‖ req_nonce, plaintext)` where plaintext = `timestamp_ms(8) ‖ confirm(16)` and
`confirm = HMAC-SHA256(LTK, "OHS1 pair confirm" ‖ client_pub ‖ phone_pub)[0:16]`.
7. Client verifies the AEAD and `confirm`, then stores `LTK` and `phone_pub` (for display and diagnostics only).

**Errors during pairing.** Once the phone has successfully decrypted a `PAIR_REQ` (so the failure is authenticated), it reports `STALE_TIMESTAMP`, `TOO_MANY_CLIENTS` or `INTERNAL` as an authenticated `ERROR` frame encrypted with `K_pair` (since `K_resp` does not exist yet), `aad = header ‖ req_nonce`, plaintext `timestamp_ms(8) ‖ request_counter(8) = 0 ‖ code(1) ‖ retry_after_s(2)`. Failures before decryption succeeds (`AUTH_FAILED`, `NOT_IN_PAIRING_MODE`, `MALFORMED`) are plaintext errors as in section 5. A client treats an unauthenticated error during pairing as a hint only.

Security property: only a party that saw the QR (knows `pairing_secret` and `phone_pub`) can produce a `PAIR_REQ` that decrypts, and the phone's reply proves it too. A BLE eavesdropper who did not see the QR learns nothing. Because `K_pair` and `LTK` use different `info`, compromise of `K_pair` does not reveal `LTK`.

### 4.4 Keys derived from LTK

```
K_req  = HKDF(salt = "", ikm = LTK, info = "OHS1 req",  L = 32)   client -> phone
K_resp = HKDF(salt = "", ikm = LTK, info = "OHS1 resp", L = 32)   phone -> client
K_adv  = HKDF(salt = "", ikm = LTK, info = "OHS1 adv",  L = 32)   advertisement tokens
```

Directional keys mean a request can never be reflected back as a valid response.

## 5. Message framing

Compact binary. One **frame** = application message; frames are chunked for transport (section 8).

```
offset size  field
0      1     version        0x01. Other values: reply UNSUPPORTED_VERSION (plaintext error)
1      1     type
2      1     flags          bit 0: body is plaintext (only for type 0xFF). Other bits MUST be 0
3      2     msg_id         chosen by the client, echoed in the response
5      2     body_len       length of body in bytes
7      n     body
```

`header` = the first 5 bytes (version, type, flags, msg_id). Maximum frame size: **512 bytes**; larger MUST be rejected with `MALFORMED`. Unknown flag bits, unknown types and length mismatches are `MALFORMED`.

### Types

| Type | Name | Direction |
|---|---|---|
| `0x01` | PAIR_REQ | client to phone |
| `0x81` | PAIR_RESP | phone to client |
| `0x10` / `0x90` | ENABLE / ENABLE_RESP | request hotspot on |
| `0x11` / `0x91` | DISABLE / DISABLE_RESP | request hotspot off |
| `0x12` / `0x92` | QUERY / QUERY_RESP | read hotspot state and credentials |
| `0x13` / `0x93` | UNPAIR / UNPAIR_RESP | client asks the phone to forget it |
| `0xFF` | ERROR | phone to client |

A response type is the request type with bit `0x80` set.

### Encrypted body

For every type except plaintext `0xFF`: `body = nonce(12) ‖ ciphertext ‖ tag(16)`.
`PAIR_REQ` additionally has `client_pub(32)` before the nonce (section 4.3).

### Request plaintext (ENABLE, DISABLE, QUERY, UNPAIR)

```
timestamp_ms(8) | counter(8) | request-specific body
```

- ENABLE body: `options(1)` ‖ `auto_off_s(2)`. In v1 `options` MUST be 0; a phone receiving any other value replies `UNSUPPORTED_REQUEST` (authenticated) and does nothing. `auto_off_s` of 0 means the phone default; the phone MAY clamp other values.
- DISABLE, QUERY, UNPAIR: empty body.

### Response plaintext

```
timestamp_ms(8) | request_counter(8) | response-specific body
```

`request_counter` echoes the request, binding the response to it.

- ENABLE_RESP and QUERY_RESP body (section 9):
  `state(1) ‖ security(1) ‖ band(1) ‖ ssid_len(1) ‖ ssid ‖ pass_len(1) ‖ pass`.
- DISABLE_RESP body: `state(1)`. UNPAIR_RESP: empty.

### Error frames

Type `0xFF`. Two forms:

- **Authenticated** (decryption of the request succeeded; the failure is at a higher level): encrypted with `K_resp`; plaintext `timestamp_ms(8) ‖ request_counter(8) ‖ code(1) ‖ retry_after_s(2)`.
- **Unauthenticated** (decryption failed, malformed input, rate limited): flags bit 0 set, body = `code(1) ‖ retry_after_s(2)` in plaintext. A client MUST treat it as a **hint**, not proof, because anyone can forge it. It MUST NOT delete a pairing because of one.

`retry_after_s` is 0 when not applicable.

### Error codes

| Code | Name | Form | Meaning |
|---|---|---|---|
| `0x01` | MALFORMED | plain | bad length, flags, type, or chunking |
| `0x02` | UNSUPPORTED_VERSION | plain | |
| `0x03` | AUTH_FAILED | plain | no stored key decrypted the request, or the phone has no pairing |
| `0x04` | RATE_LIMITED | plain | see SECURITY.md section 3; `retry_after_s` set |
| `0x05` | REPLAY | auth | counter not greater than the last accepted counter |
| `0x06` | STALE_TIMESTAMP | auth | timestamp outside the allowed skew (carries nothing else; the client shows "check clocks") |
| `0x07` | NOT_IN_PAIRING_MODE | plain | |
| `0x08` | BUSY | auth/plain | another request is in flight |
| `0x09` | TOO_MANY_CLIENTS | auth (K_pair) | pairing, 8 clients already |
| `0x10` | SHIZUKU_UNAVAILABLE | auth | Shizuku not running or not bound |
| `0x11` | PERMISSION_DENIED | auth | Shizuku permission not granted |
| `0x12` | HOTSPOT_FAILED | auth | tethering API call failed or timed out |
| `0x13` | UNSUPPORTED_REQUEST | auth | valid but unsupported type or option |
| `0xFF` | INTERNAL | auth/plain | |

## 6. Encryption and replay protection

**Nonce scheme.** Every encrypted message uses a fresh **random 96-bit nonce** from the CSPRNG, sent in the clear in the body. Random nonces avoid a nonce-reuse hazard if a client restores a stale counter from a backup. Keys are directional (`K_req`, `K_resp`, `K_pair`), and with at most about 2^32 messages per key the birthday collision risk for 96-bit nonces is negligible (about 2^-32 at 2^32 messages, far below that at realistic volumes). Implementations MUST NOT derive nonces from counters or timestamps.

**AAD.** `aad = header(5)`, with additions: `PAIR_REQ` adds `client_pub(32)`; **every response and PAIR_RESP adds the request's 12-byte nonce** (`aad = header ‖ request_nonce`). That binds each response to exactly one request, so an attacker cannot answer a request with a recorded response.

**Identifying the client.** The frame carries no client identifier (to keep the link untraceable). The phone trial-decrypts with each stored client's `K_req` (at most 8; negligible cost). The first success identifies the client.

**Replay protection** (inside the AEAD plaintext):

1. `timestamp_ms` MUST be within **±60 s** of the receiver's clock, else `STALE_TIMESTAMP` (authenticated error).
2. `counter` is an **unsigned** 64-bit integer per client (key), starting at 1 and strictly increasing (compared as unsigned; a client MUST NOT send a counter above 2^64-1, and a runtime without unsigned integers MUST use unsigned comparison); the client persists it **before** sending. The phone stores `last_counter` per client and requires `counter > last_counter`, else `REPLAY`. It updates `last_counter` only after full AEAD success **and** timestamp success, and before acting on the request.
3. Responses are protected by the nonce binding and the echoed counter; the client MUST verify the echo equals its request and the timestamp is within ±60 s.

Residual risk: an attacker who captures a request, prevents its delivery, then replays it within the 60 s window can trigger one hotspot activation. The response is encrypted to the client, so nothing leaks; see SECURITY.md.

## 7. Request flow

1. Client sees the paired-mode token, connects, negotiates the largest ATT MTU it can (Android phones accept 247 or more), discovers the service, optionally reads Status, enables notifications on Response (and Status).
2. Client writes the chunked ENABLE frame to Request (section 8).
3. Phone validates (section 6), then enables the hotspot through Shizuku. Since this can take seconds, the phone answers when the hotspot state is `ON` (or has failed), with a timeout of 20 s; if the request takes longer than 20 s it sends `HOTSPOT_FAILED`.
4. Phone notifies the chunked ENABLE_RESP on Response. The client joins the Wi-Fi network and disconnects BLE.

At most one request is processed per connection at a time (`BUSY` otherwise). Idle connections are closed by the phone after 30 s.

## 8. Chunking

ATT limits a write or notification to `MTU - 3` bytes (default MTU 23 gives 20). Each ATT write (Request) or notification (Response) carries one **chunk**:

```
chunk_header(1) | fragment
chunk_header bits: 7 = FIRST, 6 = LAST, 5..0 = sequence number mod 64 (FIRST has sequence 0)
```

Fragment capacity is `MTU - 3 - 1`. A frame that fits in one chunk has `FIRST|LAST` = `0xC0`.

Receiver rules:

- A `FIRST` chunk discards any partially assembled frame and starts a new one. Its fragment holds at least the 7 header bytes (always true, since capacity is at least 19).
- Sequence numbers MUST increase by 1; any gap, or a non-FIRST chunk with nothing in progress, discards the frame (and, on the phone, counts as a malformed attempt).
- The expected total length is `7 + body_len`, read from the frame header in the first chunk. The frame is complete when this many bytes have arrived **and** `LAST` is set; both conditions MUST agree, else `MALFORMED`.
- Reassembly timeout: **5 s** from the first chunk. Maximum frame 512 bytes.
- Request chunks are written with Write With Response, in order. Response chunks are sent as ordered notifications; the sender waits for the stack's `onNotificationSent` (Android) before the next one.

## 9. The hotspot response

ENABLE_RESP and QUERY_RESP carry the credentials. Body layout:

| Field | Size | Values |
|---|---|---|
| state | 1 | `0` OFF, `1` STARTING, `2` ON |
| security | 1 | `0` open (never produced by this app), `1` WPA2-PSK, `2` WPA3-SAE, `3` WPA2/WPA3 transition |
| band | 1 | `0` unknown, `1` 2.4 GHz, `2` 5 GHz, `3` 6 GHz |
| ssid_len | 1 | 0 to 32 |
| ssid | ssid_len | raw bytes (usually UTF-8, but SSIDs are bytes, not text) |
| pass_len | 1 | 0, or 8 to 63 |
| pass | pass_len | passphrase, UTF-8 |

If `state` is not ON, `ssid_len` and `pass_len` MAY be 0 and the client should QUERY again, or wait for the Status notification. The credentials only ever travel inside the AEAD under `K_resp`; they are never in Status, the advertisement or any plaintext error. Maximum body is 100 bytes, so the largest response frame is 151 bytes (8 chunks at MTU 23, 1 at MTU 247).

## 10. Versioning

`version` is `0x01`. A phone receiving another version replies `UNSUPPORTED_VERSION` (plaintext) and its Status byte 0 tells clients what it speaks. New fields MUST be appended to bodies in a later version; v1 receivers reject trailing bytes they do not expect (`MALFORMED`) so downgrade behaviour stays explicit. Any change to primitives bumps the `OHS1` info prefix.

## 11. Test vectors

`docs/test-vectors.json`, generated and self-verified by `docs/tools/gen_vectors.py`. Covers: QR payload, key derivation, advertisement tokens and AD bytes, PAIR_REQ/PAIR_RESP, an ENABLE request and response (plaintext, AAD, nonce, ciphertext, framing bytes, chunks at MTU 23 and 247), an unauthenticated error frame and a tampering negative case. Each implementation MUST reproduce them in unit tests, including decryption of the given frames.

## 12. Risks and open questions

- **R1. Android crypto availability: resolved.** The Kotlin implementation does not depend on the platform providers; X25519, ChaCha20-Poly1305 and HKDF are implemented in `:common` (DECISIONS.md D9, D20) and pass the RFC and interoperability vectors. Remaining caveat: no constant-time or external audit.
- **R2. Hidden tethering APIs differ across Android versions.** The phone side's `TetheringManager` calls via Shizuku are unverified on the Nothing Phone's Android version and may need per-version code and fallback. The protocol is independent of this, but `HOTSPOT_FAILED`, the 20 s timeout and the SSID/passphrase source (reading the saved `SoftApConfiguration`) depend on it.
- **R3. BLE address tracking.** Rotating tokens do not help if the BLE address is static. Android advertisers normally use a resolvable private address that rotates (about every 15 min), but this is stack-dependent and unverified. Linux clients are not affected since they do not advertise.
- **R4. Background BLE scanning for the client.** An Android tablet app needs a foreground service or similar to scan, and Doze, scan throttling (5 starts per 30 s) and OEM battery policies may delay wake-up. Linux needs BlueZ and an always-on scan; power cost is unmeasured.
- **R5. Phone-side background execution.** The GATT server and advertising must survive Doze and OEM app killing on the phone; Shizuku itself stops at reboot unless started over ADB wireless debugging again. This affects reliability more than any protocol choice.
- **R6. Advertisement slot rotation.** The round-robin among N clients assumes that Android permits changing service data about once per second without throttling or errors. Unverified. Fallback: advertise a single phone-wide token derived from a shared key (worse revocation) or use extended advertising sets where the chipset allows.
- **R7. Clock dependence.** Tokens (±5 min) and requests (±60 s) require roughly synchronised clocks. A laptop without NTP will fail with `STALE_TIMESTAMP`. Open: a phone-supplied time hint on `STALE_TIMESTAMP`, or a challenge-response mode that removes the dependence (costs one round trip).
- **R8. Replay window.** A delayed replay within 60 s is possible (section 6). A phone-issued nonce challenge would close it at the cost of a round trip; deferred.
- **R9. Wi-Fi connect step.** The protocol ends at delivering credentials. Joining is platform specific (NetworkManager `nmcli`, Android `WifiNetworkSpecifier` which shows a prompt on Android 10+ and may not be fully silent, or `WifiNetworkSuggestion`). Not specified here; may need a protocol hint (for example a hidden SSID flag) later.
- **R10. Passphrase exposure.** The hotspot passphrase is the sensitive result. Whether to rotate it per session is open; v1 sends the phone's existing hotspot configuration.
- **R11. Compatibility with phones' own hotspot UX.** Some Android builds turn the hotspot off after idle; `auto_off_s` is advisory and may be clamped or ignored.
- **R12. Custom UUIDs are fingerprintable.** Anyone can tell that some phone in range runs OpenHotspot (the service UUID is constant). The token hides which phone. Acceptable for v1.
- **R13. MTU and chunk limits.** Some stacks cap notifications below MTU-3. Implementations should use the negotiated MTU only after `onMtuChanged`, and fall back to 23.
- **R14. 8-client cap.** An arbitrary limit that bounds trial decryption and slot rotation. Revisit with real use.
- **R15. Single identity per client.** A client pairing with several phones keeps one LTK per phone. No multi-phone selection logic is defined yet.
