"""Primitives against RFC known answers, and every protocol layer against docs/test-vectors.json."""
import pytest

from openhotspot import advertisement, crypto
from openhotspot.chunking import Reassembler, chunk_frame
from openhotspot.constants import ErrorCode, MsgType
from openhotspot.errors import AuthenticationError, PhoneError
from openhotspot.framing import Frame, open_body
from openhotspot.messages import HotspotInfo, pack_enable_body
from openhotspot.pairing import ClientPairing, PairingQr, derive_pairing_keys, derive_session_keys, pair_confirm
from openhotspot.session import build_request, parse_response

H = bytes.fromhex


# ---- RFC known-answer tests --------------------------------------------------------------

def test_x25519_rfc7748():
    a = H("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
    b = H("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
    assert crypto.x25519_public(a).hex() == "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"
    assert crypto.x25519_shared(a, crypto.x25519_public(b)).hex() == "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"


def test_x25519_rejects_all_zero_result():
    with pytest.raises(AuthenticationError):
        crypto.x25519_shared(bytes(range(32)), bytes(32))


def test_hkdf_rfc5869_case1():
    okm = crypto.hkdf(bytes(range(13)), b"\x0b" * 22, bytes(range(0xF0, 0xFA)), 42)
    assert okm.hex() == "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"


def test_chacha20poly1305_rfc8439():
    pt = (b"Ladies and Gentlemen of the class of '99: If I could offer you only one tip "
          b"for the future, sunscreen would be it.")
    key, nonce, aad = bytes(range(0x80, 0xA0)), H("070000004041424344454647"), H("50515253c0c1c2c3c4c5c6c7")
    sealed = crypto.aead_seal(key, nonce, aad, pt)
    assert sealed[-16:].hex() == "1ae10b594f09e26a7e902ecbd0600691"
    assert crypto.aead_open(key, nonce, aad, sealed) == pt
    with pytest.raises(AuthenticationError):
        crypto.aead_open(key, nonce, aad + b"x", sealed)


# ---- test-vectors.json -------------------------------------------------------------------

def test_vector_qr(vectors):
    qr = PairingQr.parse(vectors["qr"]["uri"])
    assert qr.phone_pub.hex() == vectors["qr"]["phone_public"]
    assert qr.secret.hex() == vectors["qr"]["pairing_secret"]
    assert qr.encode() == vectors["qr"]["uri"]
    assert crypto.x25519_public(H(vectors["qr"]["phone_private"])) == qr.phone_pub


def test_vector_uuids_version_and_qr_raw(vectors):
    from openhotspot import constants

    u = vectors["uuids"]
    assert (u["service"], u["request"], u["response"], u["status"]) == (
        constants.SERVICE_UUID, constants.CHAR_REQUEST_UUID, constants.CHAR_RESPONSE_UUID, constants.CHAR_STATUS_UUID)
    assert vectors["protocol_version"] == constants.VERSION
    assert vectors["advertisement"]["window_seconds"] == 300
    qr = PairingQr.parse(vectors["qr"]["uri"])
    assert bytes([1]) + qr.service_uuid + qr.phone_pub + qr.secret == H(vectors["qr"]["raw"])
    kd = vectors["key_derivation"]
    client_pub = crypto.x25519_public(H(kd["client_private"]))
    assert constants.SERVICE_UUID_BYTES + qr.phone_pub + client_pub == H(kd["hkdf_info_transcript"])


def test_vector_key_derivation(vectors):
    qr = PairingQr.parse(vectors["qr"]["uri"])
    kd = vectors["key_derivation"]
    client_priv = H(kd["client_private"])
    client_pub = crypto.x25519_public(client_priv)
    assert client_pub.hex() == kd["client_public"]
    dh = crypto.x25519_shared(client_priv, qr.phone_pub)
    assert dh.hex() == kd["x25519_shared"]
    k_pair, ltk = derive_pairing_keys(dh, qr.secret, qr.phone_pub, client_pub)
    assert (k_pair.hex(), ltk.hex()) == (kd["k_pair"], kd["ltk"])
    keys = derive_session_keys(ltk)
    assert (keys.k_req.hex(), keys.k_resp.hex(), keys.k_adv.hex()) == (kd["k_req"], kd["k_resp"], kd["k_adv"])


def test_vector_advertisement(vectors):
    a, kd = vectors["advertisement"], vectors["key_derivation"]
    now = a["unix_seconds"]
    assert advertisement.window_index(now) == a["window_index"]
    paired = advertisement.paired_token(H(kd["k_adv"]), now)
    pairing = advertisement.pairing_token(H(vectors["qr"]["pairing_secret"]), now)
    assert (paired.hex(), pairing.hex()) == (a["paired_token"], a["pairing_token"])
    assert advertisement.build_ad_structure(0, paired).hex() == a["paired_ad_structure"]
    assert advertisement.build_ad_structure(1, pairing).hex() == a["pairing_ad_structure"]
    adv = advertisement.Advertisement("x", advertisement.build_payload(0, paired))
    for drift in (-300, 0, 300):  # neighbouring windows are accepted
        assert advertisement.matches_paired(adv, H(kd["k_adv"]), now + drift)
    assert not advertisement.matches_paired(adv, H(kd["k_adv"]), now + 900)
    assert not advertisement.matches_paired(adv, bytes(32), now)  # wrong key
    assert not advertisement.matches_pairing(adv, H(vectors["qr"]["pairing_secret"]), now)  # wrong mode


def _client_pairing(vectors):
    qr = PairingQr.parse(vectors["qr"]["uri"])
    now = vectors["advertisement"]["unix_seconds"]
    pm = vectors["pairing_messages"]["pair_req"]
    return ClientPairing(
        qr, "Test Laptop", clock=lambda: float(now), private_key=H(vectors["key_derivation"]["client_private"]),
        nonce=H(pm["nonce"]), msg_id=1,
    ), now


def test_vector_pair_req_frame_is_reproduced_byte_for_byte(vectors):
    pairing, _ = _client_pairing(vectors)
    assert pairing.request_frame().encode().hex() == vectors["pairing_messages"]["pair_req"]["frame"]


def test_vector_pair_resp_completes_pairing(vectors):
    pairing, now = _client_pairing(vectors)
    pairing.request_frame()
    resp = Frame.decode(H(vectors["pairing_messages"]["pair_resp"]["frame"]))
    phone = pairing.finish(resp)
    assert phone.ltk.hex() == vectors["key_derivation"]["ltk"]
    assert pair_confirm(phone.ltk, pairing.client_pub, phone.phone_pub).hex() == vectors["pairing_messages"]["pair_resp"]["key_confirmation"]


def test_vector_pair_resp_with_bad_confirmation_is_rejected(vectors):
    pairing, _ = _client_pairing(vectors)
    pairing.request_frame()
    raw = bytearray(H(vectors["pairing_messages"]["pair_resp"]["frame"]))
    raw[-1] ^= 1
    with pytest.raises(AuthenticationError):
        pairing.finish(Frame.decode(bytes(raw)))


def test_vector_enable_request(vectors):
    kd, er = vectors["key_derivation"], vectors["enable_request"]
    frame, nonce = build_request(
        H(kd["k_req"]), MsgType.ENABLE, 0x0102, vectors["advertisement"]["unix_seconds"] * 1000 + 1000, 1,
        pack_enable_body(0, 0), nonce=H(er["nonce"]),
    )
    assert frame.header.hex() == er["header"]
    assert frame.encode().hex() == er["frame"]
    assert [c.hex() for c in chunk_frame(frame.encode(), 23)] == er["chunks_mtu23"]
    plaintext, got_nonce = open_body(H(kd["k_req"]), frame.header, frame.body)
    assert plaintext.hex() == er["plaintext"] and got_nonce == nonce


def test_vector_enable_response(vectors):
    kd, er, rs = vectors["key_derivation"], vectors["enable_request"], vectors["enable_response"]
    frame = Frame.decode(H(rs["frame"]))
    body = parse_response(
        H(kd["k_resp"]), frame, request_type=MsgType.ENABLE, request_msg_id=0x0102,
        request_nonce=H(er["nonce"]), request_counter=1,
        now_ms=vectors["advertisement"]["unix_seconds"] * 1000 + 1900,
    )
    assert body.hex() == rs["body"]
    info = HotspotInfo.decode(body)
    assert (info.ssid, info.passphrase) == (b"Nothing Phone (2)", "correct horse battery")
    assert info.encode() == body
    for key in ("chunks_mtu23", "chunks_mtu247"):
        reasm, out = Reassembler(), None
        for c in rs[key]:
            out = reasm.feed(H(c))
        assert out == H(rs["frame"])
        assert [c.hex() for c in chunk_frame(H(rs["frame"]), 23 if "23" in key else 247)] == rs[key]


def test_vector_response_is_bound_to_its_request(vectors):
    kd, rs = vectors["key_derivation"], vectors["enable_response"]
    frame = Frame.decode(H(rs["frame"]))
    kwargs = dict(request_type=MsgType.ENABLE, request_msg_id=0x0102, request_counter=1)
    with pytest.raises(AuthenticationError):  # response replayed against a different request nonce
        parse_response(H(kd["k_resp"]), frame, request_nonce=bytes(12), **kwargs)
    with pytest.raises(AuthenticationError):  # request key used as response key (no reflection)
        parse_response(H(kd["k_req"]), frame, request_nonce=H(vectors["enable_request"]["nonce"]), **kwargs)
    with pytest.raises(Exception):  # wrong counter echo
        parse_response(H(kd["k_resp"]), frame, request_nonce=H(vectors["enable_request"]["nonce"]),
                       request_type=MsgType.ENABLE, request_msg_id=0x0102, request_counter=2)


def test_vector_tampered_request_fails(vectors):
    kd, er = vectors["key_derivation"], vectors["enable_request"]
    nonce = H(er["nonce"])
    with pytest.raises(AuthenticationError):
        crypto.aead_open(H(kd["k_req"]), nonce, H(er["aad"]), H(vectors["negative"]["tampered_enable_request_ciphertext_and_tag"]))


def test_vector_unauthenticated_error_frame(vectors):
    frame = Frame.decode(H(vectors["error_unauthenticated"]["frame"]))
    assert frame.type == MsgType.ERROR and frame.plaintext
    with pytest.raises(PhoneError) as exc:
        parse_response(bytes(32), frame, request_type=MsgType.ENABLE, request_msg_id=0x0102, request_nonce=bytes(12), request_counter=1)
    assert exc.value.code == ErrorCode.AUTH_FAILED and exc.value.retry_after_s == 30 and not exc.value.authenticated
