#!/usr/bin/env python3
"""Deterministic test-vector generator for OpenHotspot protocol v1.

Usage:
    python3 gen_vectors.py            # (re)write ../test-vectors.json, then verify it
    python3 gen_vectors.py --check    # verify ../test-vectors.json is exactly what this script produces

Stdlib only. X25519, ChaCha20-Poly1305 and HKDF are implemented here from the RFCs
as an independent reference. The script (1) proves those primitives against the RFC
known-answer tests, (2) cross-checks them against the `cryptography` package when
installed, (3) generates the vectors from fixed inputs, and (4) re-parses and
decrypts every generated frame as a receiver would. Any failure exits non-zero.
The byte layouts mirrored here are specified in docs/PROTOCOL.md; keep both in sync.
"""
import hashlib, hmac, json, struct, sys, base64, uuid
from pathlib import Path

# ----------------------------------------------------------------- primitives
P = 2**255 - 19
A24 = 121665

def _clamp(k: bytes) -> int:
    b = bytearray(k); b[0] &= 248; b[31] &= 127; b[31] |= 64
    return int.from_bytes(b, "little")

def x25519(k: bytes, u: bytes) -> bytes:
    """RFC 7748 Montgomery ladder."""
    kk = _clamp(k)
    x1 = int.from_bytes(u, "little") & ((1 << 255) - 1)
    x2, z2, x3, z3, swap = 1, 0, x1, 1, 0
    for t in range(254, -1, -1):
        bit = (kk >> t) & 1
        swap ^= bit
        if swap: x2, x3, z2, z3 = x3, x2, z3, z2
        swap = bit
        a = (x2 + z2) % P; aa = a * a % P
        b = (x2 - z2) % P; bb = b * b % P
        e = (aa - bb) % P
        c = (x3 + z3) % P; d = (x3 - z3) % P
        da = d * a % P; cb = c * b % P
        x3 = (da + cb) ** 2 % P
        z3 = x1 * (da - cb) ** 2 % P
        x2 = aa * bb % P
        z2 = e * (aa + A24 * e) % P
    if swap: x2, x3, z2, z3 = x3, x2, z3, z2
    return (x2 * pow(z2, P - 2, P) % P).to_bytes(32, "little")

BASE = (9).to_bytes(32, "little")
def x25519_pub(priv: bytes) -> bytes: return x25519(priv, BASE)

def _rotl(v, n): return ((v << n) & 0xFFFFFFFF) | (v >> (32 - n))

def _chacha_block(key: bytes, counter: int, nonce: bytes) -> bytes:
    s = list(struct.unpack("<4I", b"expand 32-byte k")) + list(struct.unpack("<8I", key)) \
        + [counter] + list(struct.unpack("<3I", nonce))
    w = s[:]
    def qr(a, b, c, d):
        w[a] = (w[a] + w[b]) & 0xFFFFFFFF; w[d] = _rotl(w[d] ^ w[a], 16)
        w[c] = (w[c] + w[d]) & 0xFFFFFFFF; w[b] = _rotl(w[b] ^ w[c], 12)
        w[a] = (w[a] + w[b]) & 0xFFFFFFFF; w[d] = _rotl(w[d] ^ w[a], 8)
        w[c] = (w[c] + w[d]) & 0xFFFFFFFF; w[b] = _rotl(w[b] ^ w[c], 7)
    for _ in range(10):
        qr(0, 4, 8, 12); qr(1, 5, 9, 13); qr(2, 6, 10, 14); qr(3, 7, 11, 15)
        qr(0, 5, 10, 15); qr(1, 6, 11, 12); qr(2, 7, 8, 13); qr(3, 4, 9, 14)
    return struct.pack("<16I", *[(w[i] + s[i]) & 0xFFFFFFFF for i in range(16)])

def _chacha_xor(key, counter, nonce, data: bytes) -> bytes:
    out = bytearray()
    for i in range(0, len(data), 64):
        ks = _chacha_block(key, counter + i // 64, nonce)
        out += bytes(a ^ b for a, b in zip(data[i:i + 64], ks))
    return bytes(out)

def _poly1305(key: bytes, msg: bytes) -> bytes:
    r = int.from_bytes(key[:16], "little") & 0x0ffffffc0ffffffc0ffffffc0fffffff
    s = int.from_bytes(key[16:], "little")
    acc, p = 0, (1 << 130) - 5
    for i in range(0, len(msg), 16):
        n = int.from_bytes(msg[i:i + 16] + b"\x01", "little")
        acc = (acc + n) * r % p
    return ((acc + s) & ((1 << 128) - 1)).to_bytes(16, "little")

def _pad16(b): return b + b"\x00" * (-len(b) % 16)

def _aead_tag(key, nonce, aad, ct):
    otk = _chacha_block(key, 0, nonce)[:32]
    return _poly1305(otk, _pad16(aad) + _pad16(ct) + struct.pack("<QQ", len(aad), len(ct)))

def aead_seal(key, nonce, aad, pt) -> bytes:
    assert len(key) == 32 and len(nonce) == 12
    ct = _chacha_xor(key, 1, nonce, pt)
    return ct + _aead_tag(key, nonce, aad, ct)

def aead_open(key, nonce, aad, ct_tag) -> bytes:
    ct, tag = ct_tag[:-16], ct_tag[-16:]
    if not hmac.compare_digest(tag, _aead_tag(key, nonce, aad, ct)):
        raise ValueError("AEAD authentication failed")
    return _chacha_xor(key, 1, nonce, ct)

def hkdf(salt: bytes, ikm: bytes, info: bytes, length: int) -> bytes:
    """RFC 5869 HKDF-SHA256. Empty salt == HashLen zero bytes per the RFC."""
    prk = hmac.new(salt or b"\x00" * 32, ikm, hashlib.sha256).digest()
    okm, t, i = b"", b"", 1
    while len(okm) < length:
        t = hmac.new(prk, t + info + bytes([i]), hashlib.sha256).digest(); okm += t; i += 1
    return okm[:length]

def hmac256(key, msg): return hmac.new(key, msg, hashlib.sha256).digest()

# --------------------------------------------------------- self-test (RFC KATs)
def selftest():
    h = bytes.fromhex
    # RFC 7748 section 6.1
    a_priv = h("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
    b_priv = h("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
    assert x25519_pub(a_priv) == h("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
    assert x25519_pub(b_priv) == h("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
    assert x25519(a_priv, x25519_pub(b_priv)) == h("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")
    # RFC 5869 test case 1
    assert hkdf(bytes(range(13)), b"\x0b" * 22, bytes(range(0xf0, 0xfa)), 42) == h(
        "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865")
    # RFC 8439 section 2.8.2
    pt = (b"Ladies and Gentlemen of the class of '99: If I could offer you only one tip "
          b"for the future, sunscreen would be it.")
    sealed = aead_seal(bytes(range(0x80, 0xa0)), h("070000004041424344454647"),
                       h("50515253c0c1c2c3c4c5c6c7"), pt)
    assert sealed[-16:] == h("1ae10b594f09e26a7e902ecbd0600691"), "RFC 8439 tag mismatch"
    assert sealed[:16] == h("d31a8d34648e60db7b86afbc53ef7ec2")
    # optional cross-check with the `cryptography` package
    try:
        from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
        from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
        from cryptography.hazmat.primitives.kdf.hkdf import HKDF
        from cryptography.hazmat.primitives import hashes
    except ImportError:
        print("note: `cryptography` not installed; skipping cross-check", file=sys.stderr)
        return
    for i in range(8):
        sk = hashlib.sha256(b"x25519-%d" % i).digest(); pk = hashlib.sha256(b"peer-%d" % i).digest()
        pub = X25519PrivateKey.from_private_bytes(pk).public_key()
        theirs = X25519PrivateKey.from_private_bytes(sk).exchange(pub)
        assert theirs == x25519(sk, x25519_pub(pk))
        key, nonce, aad, msg = sk, pk[:12], b"aad%d" % i, b"m" * (i * 37)
        assert ChaCha20Poly1305(key).encrypt(nonce, msg, aad) == aead_seal(key, nonce, aad, msg)
        okm = HKDF(hashes.SHA256(), 32, bytes(i), b"info").derive(sk)
        assert okm == hkdf(bytes(i), sk, b"info", 32)

# ------------------------------------------------------------ protocol layouts
VERSION = 0x01
SERVICE_UUID = uuid.UUID("7e5f0001-8c3a-4d2b-9a61-0b4c7d9e1f20")
CHAR_REQUEST = "7e5f0002-8c3a-4d2b-9a61-0b4c7d9e1f20"
CHAR_RESPONSE = "7e5f0003-8c3a-4d2b-9a61-0b4c7d9e1f20"
CHAR_STATUS = "7e5f0004-8c3a-4d2b-9a61-0b4c7d9e1f20"
T_PAIR_REQ, T_PAIR_RESP = 0x01, 0x81
T_ENABLE, T_DISABLE, T_QUERY, T_UNPAIR = 0x10, 0x11, 0x12, 0x13
T_ERROR = 0xFF
FLAG_PLAINTEXT = 0x01
ERR_AUTH_FAILED = 0x03
ADV_WINDOW_S = 300
ADV_MODE_PAIRED, ADV_MODE_PAIRING = 0, 1

def header(mtype, flags, msg_id): return struct.pack(">BBBH", VERSION, mtype, flags, msg_id)
def frame(hdr, body): return hdr + struct.pack(">H", len(body)) + body

def derive_pairing(phone_priv, client_priv, secret):
    phone_pub, client_pub = x25519_pub(phone_priv), x25519_pub(client_priv)
    dh = x25519(client_priv, phone_pub)
    assert dh == x25519(phone_priv, client_pub)
    tr = SERVICE_UUID.bytes + phone_pub + client_pub
    k_pair = hkdf(secret, dh, b"OHS1 pair" + tr, 32)
    ltk = hkdf(secret, dh, b"OHS1 ltk" + tr, 32)
    return dict(phone_pub=phone_pub, client_pub=client_pub, dh=dh, k_pair=k_pair, ltk=ltk,
                k_req=hkdf(b"", ltk, b"OHS1 req", 32), k_resp=hkdf(b"", ltk, b"OHS1 resp", 32),
                k_adv=hkdf(b"", ltk, b"OHS1 adv", 32))

def adv_token(key, label: bytes, unix_s):
    return hmac256(key, label + struct.pack(">Q", unix_s // ADV_WINDOW_S))[:8]

def adv_ad_bytes(mode, token):
    payload = bytes([(VERSION << 4) | mode]) + token
    return bytes([1 + 16 + len(payload), 0x21]) + SERVICE_UUID.bytes[::-1] + payload  # Service Data - 128-bit UUID; BLE sends the UUID fully byte-reversed (NOT uuid.bytes_le)

def chunk(fr, mtu):
    n = mtu - 3 - 1
    parts = [fr[i:i + n] for i in range(0, len(fr), n)]
    out = []
    for i, p in enumerate(parts):
        f = (0x80 if i == 0 else 0) | (0x40 if i == len(parts) - 1 else 0) | (i & 0x3F)
        out.append(bytes([f]) + p)
    return out

def reassemble(chunks):
    buf, expect_seq, total = b"", 0, None
    for i, c in enumerate(chunks):
        first, last, seq = bool(c[0] & 0x80), bool(c[0] & 0x40), c[0] & 0x3F
        assert first == (i == 0) and seq == expect_seq % 64
        buf += c[1:]; expect_seq += 1
        if total is None:
            total = 7 + struct.unpack(">H", buf[5:7])[0]
        assert last == (i == len(chunks) - 1)
    assert len(buf) == total
    return buf

def parse_frame(fr):
    ver, mtype, flags, msg_id, blen = struct.unpack(">BBBHH", fr[:7])
    assert ver == VERSION and len(fr) == 7 + blen
    return dict(type=mtype, flags=flags, msg_id=msg_id, body=fr[7:], header=fr[:5])

def hx(b): return b.hex()

# ------------------------------------------------------------------- vectors
def build():
    secret = bytes(range(0x10, 0x20))
    phone_priv = hashlib.sha256(b"OpenHotspot test phone static key").digest()
    client_priv = hashlib.sha256(b"OpenHotspot test client ephemeral key").digest()
    d = derive_pairing(phone_priv, client_priv, secret)
    V = {"_comment": "Generated by docs/tools/gen_vectors.py. Do not edit by hand. All values hex unless noted.",
         "protocol_version": VERSION,
         "uuids": {"service": str(SERVICE_UUID), "request": CHAR_REQUEST, "response": CHAR_RESPONSE, "status": CHAR_STATUS}}

    qr_raw = bytes([VERSION]) + SERVICE_UUID.bytes + d["phone_pub"] + secret
    V["qr"] = {"pairing_secret": hx(secret), "phone_private": hx(phone_priv), "phone_public": hx(d["phone_pub"]),
               "raw": hx(qr_raw),
               "uri": "openhotspot://pair/" + base64.urlsafe_b64encode(qr_raw).rstrip(b"=").decode()}

    V["key_derivation"] = {
        "client_private": hx(client_priv), "client_public": hx(d["client_pub"]),
        "x25519_shared": hx(d["dh"]),
        "hkdf_info_transcript": hx(SERVICE_UUID.bytes + d["phone_pub"] + d["client_pub"]),
        "k_pair": hx(d["k_pair"]), "ltk": hx(d["ltk"]),
        "k_req": hx(d["k_req"]), "k_resp": hx(d["k_resp"]), "k_adv": hx(d["k_adv"])}

    # advertisement
    now_s = 1_800_000_000
    t_paired, t_pair = adv_token(d["k_adv"], b"OHS1 adv", now_s), adv_token(secret, b"OHS1 adv-pair", now_s)
    V["advertisement"] = {"unix_seconds": now_s, "window_seconds": ADV_WINDOW_S, "window_index": now_s // ADV_WINDOW_S,
        "paired_token": hx(t_paired), "paired_ad_structure": hx(adv_ad_bytes(ADV_MODE_PAIRED, t_paired)),
        "pairing_token": hx(t_pair), "pairing_ad_structure": hx(adv_ad_bytes(ADV_MODE_PAIRING, t_pair))}

    # PAIR_REQ / PAIR_RESP
    ts = now_s * 1000
    label = b"Test Laptop"
    pr_pt = struct.pack(">QB", ts, len(label)) + label
    pr_hdr = header(T_PAIR_REQ, 0, 1); pr_nonce = bytes(range(0x30, 0x3c))
    pr_aad = pr_hdr + d["client_pub"]
    pr_ct = aead_seal(d["k_pair"], pr_nonce, pr_aad, pr_pt)
    pr_frame = frame(pr_hdr, d["client_pub"] + pr_nonce + pr_ct)
    confirm = hmac256(d["ltk"], b"OHS1 pair confirm" + d["client_pub"] + d["phone_pub"])[:16]
    ps_pt = struct.pack(">Q", ts + 120) + confirm
    ps_hdr = header(T_PAIR_RESP, 0, 1); ps_nonce = bytes(range(0x40, 0x4c))
    ps_ct = aead_seal(d["k_pair"], ps_nonce, ps_hdr + pr_nonce, ps_pt)
    ps_frame = frame(ps_hdr, ps_nonce + ps_ct)
    V["pairing_messages"] = {
        "pair_req": {"plaintext": hx(pr_pt), "header": hx(pr_hdr), "nonce": hx(pr_nonce), "aad": hx(pr_aad), "frame": hx(pr_frame)},
        "pair_resp": {"plaintext": hx(ps_pt), "header": hx(ps_hdr), "nonce": hx(ps_nonce), "aad": hx(ps_hdr + pr_nonce),
                      "key_confirmation": hx(confirm), "frame": hx(ps_frame)}}

    # ENABLE request + response
    ctr = 1
    rq_pt = struct.pack(">QQ", ts + 1000, ctr) + struct.pack(">BH", 0, 0)  # opts=0, auto_off=0
    rq_hdr = header(T_ENABLE, 0, 0x0102); rq_nonce = bytes(range(0x50, 0x5c))
    rq_ct = aead_seal(d["k_req"], rq_nonce, rq_hdr, rq_pt)
    rq_frame = frame(rq_hdr, rq_nonce + rq_ct)
    ssid, pw = b"Nothing Phone (2)", b"correct horse battery"
    rs_body = struct.pack(">BBBB", 2, 3, 2, len(ssid)) + ssid + bytes([len(pw)]) + pw
    rs_pt = struct.pack(">QQ", ts + 1900, ctr) + rs_body
    rs_hdr = header(T_ENABLE | 0x80, 0, 0x0102); rs_nonce = bytes(range(0x60, 0x6c))
    rs_ct = aead_seal(d["k_resp"], rs_nonce, rs_hdr + rq_nonce, rs_pt)
    rs_frame = frame(rs_hdr, rs_nonce + rs_ct)
    V["enable_request"] = {"plaintext": hx(rq_pt), "header": hx(rq_hdr), "nonce": hx(rq_nonce), "aad": hx(rq_hdr),
                           "ciphertext_and_tag": hx(rq_ct), "frame": hx(rq_frame),
                           "chunks_mtu23": [hx(c) for c in chunk(rq_frame, 23)]}
    V["enable_response"] = {"plaintext": hx(rs_pt), "body": hx(rs_body), "header": hx(rs_hdr), "nonce": hx(rs_nonce),
                            "aad": hx(rs_hdr + rq_nonce), "ciphertext_and_tag": hx(rs_ct), "frame": hx(rs_frame),
                            "chunks_mtu23": [hx(c) for c in chunk(rs_frame, 23)],
                            "chunks_mtu247": [hx(c) for c in chunk(rs_frame, 247)]}

    # unauthenticated error frame (AUTH_FAILED, retry after 30 s)
    er_hdr = header(T_ERROR, FLAG_PLAINTEXT, 0x0102)
    V["error_unauthenticated"] = {"frame": hx(frame(er_hdr, struct.pack(">BH", ERR_AUTH_FAILED, 30)))}

    # replay negative case: tampered ciphertext must fail
    bad = bytearray(rq_ct); bad[0] ^= 1
    V["negative"] = {"tampered_enable_request_ciphertext_and_tag": hx(bytes(bad)),
                     "expected": "AEAD authentication failure; reply with unauthenticated AUTH_FAILED"}
    return V

# -------------------------------------------------------------------- verify
def verify(V):
    """Re-derive everything from the JSON the way an independent receiver would."""
    h = bytes.fromhex
    secret = h(V["qr"]["pairing_secret"])
    # QR round trip
    uri = V["qr"]["uri"]; assert uri.startswith("openhotspot://pair/")
    raw = base64.urlsafe_b64decode(uri.split("/")[-1] + "==")
    assert raw == h(V["qr"]["raw"]) and raw[0] == VERSION and len(raw) == 65
    assert raw[1:17] == uuid.UUID(V["uuids"]["service"]).bytes
    assert raw[17:49] == h(V["qr"]["phone_public"]) and raw[49:] == secret
    assert x25519_pub(h(V["qr"]["phone_private"])) == h(V["qr"]["phone_public"])
    # derivation from the client side (uses only QR contents + client key)
    kd = V["key_derivation"]
    c_priv = h(kd["client_private"]); phone_pub = raw[17:49]
    d = derive_pairing(h(V["qr"]["phone_private"]), c_priv, secret)
    assert x25519(c_priv, phone_pub) == h(kd["x25519_shared"])
    for k in ("k_pair", "ltk", "k_req", "k_resp", "k_adv"): assert d[k] == h(kd[k]), k
    # pairing messages
    pm = V["pairing_messages"]
    f = parse_frame(h(pm["pair_req"]["frame"])); assert f["type"] == T_PAIR_REQ
    cpub, nonce, ct = f["body"][:32], f["body"][32:44], f["body"][44:]
    assert cpub == d["client_pub"]
    pt = aead_open(d["k_pair"], nonce, f["header"] + cpub, ct); assert pt == h(pm["pair_req"]["plaintext"])
    assert pt[9:9 + pt[8]] == b"Test Laptop"
    f2 = parse_frame(h(pm["pair_resp"]["frame"])); n2, ct2 = f2["body"][:12], f2["body"][12:]
    pt2 = aead_open(d["k_pair"], n2, f2["header"] + nonce, ct2)
    assert pt2[8:] == hmac256(d["ltk"], b"OHS1 pair confirm" + cpub + phone_pub)[:16]
    # ENABLE request: decrypt as the phone, check replay fields
    er = V["enable_request"]; fr = parse_frame(h(er["frame"])); assert fr["type"] == T_ENABLE
    rn, rct = fr["body"][:12], fr["body"][12:]
    rpt = aead_open(d["k_req"], rn, fr["header"], rct); assert rpt == h(er["plaintext"])
    ts, ctr = struct.unpack(">QQ", rpt[:16]); assert ctr == 1 and len(rpt[16:]) == 3
    assert reassemble([h(c) for c in er["chunks_mtu23"]]) == h(er["frame"])
    # ENABLE response: decrypt as the client, check binding to request nonce and counter echo
    rs = V["enable_response"]; fs = parse_frame(h(rs["frame"])); assert fs["type"] == T_ENABLE | 0x80
    spt = aead_open(d["k_resp"], fs["body"][:12], fs["header"] + rn, fs["body"][12:])
    assert spt == h(rs["plaintext"]) and struct.unpack(">Q", spt[8:16])[0] == ctr
    b = spt[16:]; state, sec, band, sl = b[0], b[1], b[2], b[3]
    ssid = b[4:4 + sl]; pl = b[4 + sl]; pw = b[5 + sl:5 + sl + pl]
    assert (state, ssid, pw) == (2, b"Nothing Phone (2)", b"correct horse battery") and len(b) == 5 + sl + pl
    for key in ("chunks_mtu23", "chunks_mtu247"):
        assert reassemble([h(c) for c in rs[key]]) == h(rs["frame"])
    assert len(rs["chunks_mtu247"]) == 1
    # wrong direction key / wrong request nonce / tamper must fail
    for bad_key, bad_aad in ((d["k_req"], fs["header"] + rn), (d["k_resp"], fs["header"])):
        try: aead_open(bad_key, fs["body"][:12], bad_aad, fs["body"][12:]); raise SystemExit("negative case accepted")
        except ValueError: pass
    try: aead_open(d["k_req"], rn, fr["header"], h(V["negative"]["tampered_enable_request_ciphertext_and_tag"])); raise SystemExit("tamper accepted")
    except ValueError: pass
    # advertisement
    a = V["advertisement"]; assert a["window_index"] == a["unix_seconds"] // ADV_WINDOW_S
    assert adv_token(d["k_adv"], b"OHS1 adv", a["unix_seconds"]) == h(a["paired_token"])
    assert adv_token(secret, b"OHS1 adv-pair", a["unix_seconds"]) == h(a["pairing_token"])
    ad = h(a["paired_ad_structure"]); assert len(ad) == 27 and 3 + len(ad) <= 31  # +3 for Android's flags AD
    assert ad[0] == len(ad) - 1 and ad[1] == 0x21 and ad[2:18] == SERVICE_UUID.bytes[::-1] and ad[18] == 0x10
    assert h(a["pairing_ad_structure"])[18] == 0x11
    # unauthenticated error
    e = parse_frame(h(V["error_unauthenticated"]["frame"]))
    assert e["type"] == T_ERROR and e["flags"] & FLAG_PLAINTEXT and e["body"] == bytes([ERR_AUTH_FAILED, 0, 30])

def main():
    selftest()
    path = Path(__file__).resolve().parent.parent / "test-vectors.json"
    V = build()
    text = json.dumps(V, indent=2) + "\n"
    if "--check" in sys.argv:
        if path.read_text() != text: raise SystemExit(f"{path} is out of date; rerun without --check")
    else:
        path.write_text(text)
    verify(json.loads(path.read_text()))
    print(f"ok: primitives pass RFC KATs; {path.name} {'matches' if '--check' in sys.argv else 'written'} and verifies")

if __name__ == "__main__":
    main()
