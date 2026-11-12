"""QR payload, key derivation and the client side of the pairing handshake (PROTOCOL.md section 4)."""
from __future__ import annotations

import base64
import binascii
import hashlib
import hmac
import secrets
import struct
import time
from collections.abc import Callable
from dataclasses import dataclass

from . import crypto
from .constants import (
    MAX_LABEL_BYTES,
    ErrorCode,
    QR_PREFIX,
    QR_RAW_LEN,
    SERVICE_UUID_BYTES,
    TIMESTAMP_SKEW_MS,
    VERSION,
    MsgType,
)
from .errors import AuthenticationError, PairingError, PhoneError, ProtocolError
from .framing import Frame, open_body, seal_body
from .messages import unpack_error_auth, unpack_error_plain


@dataclass(frozen=True)
class PairingQr:
    version: int
    service_uuid: bytes
    phone_pub: bytes
    secret: bytes

    @classmethod
    def parse(cls, text: str) -> PairingQr:
        text = text.strip()
        if not text.startswith(QR_PREFIX):
            raise PairingError("not an OpenHotspot pairing QR (bad prefix)")
        data = text[len(QR_PREFIX):]
        try:
            raw = base64.urlsafe_b64decode(data + "=" * (-len(data) % 4))
        except (binascii.Error, ValueError) as exc:
            raise PairingError("pairing QR is not valid base64url") from exc
        if len(raw) != QR_RAW_LEN:
            raise PairingError(f"pairing QR payload must be {QR_RAW_LEN} bytes, got {len(raw)}")
        if raw[0] != VERSION:
            raise PairingError(f"unsupported pairing QR version {raw[0]}")
        if raw[1:17] != SERVICE_UUID_BYTES:
            raise PairingError("pairing QR is for a different service UUID")
        return cls(raw[0], raw[1:17], raw[17:49], raw[49:65])

    def encode(self) -> str:
        raw = bytes([self.version]) + self.service_uuid + self.phone_pub + self.secret
        return QR_PREFIX + base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


@dataclass(frozen=True)
class SessionKeys:
    k_req: bytes
    k_resp: bytes
    k_adv: bytes


def derive_session_keys(ltk: bytes) -> SessionKeys:
    return SessionKeys(
        k_req=crypto.hkdf(b"", ltk, b"OHS1 req"),
        k_resp=crypto.hkdf(b"", ltk, b"OHS1 resp"),
        k_adv=crypto.hkdf(b"", ltk, b"OHS1 adv"),
    )


def derive_pairing_keys(dh: bytes, secret: bytes, phone_pub: bytes, client_pub: bytes) -> tuple[bytes, bytes]:
    """Returns (K_pair, LTK)."""
    transcript = SERVICE_UUID_BYTES + phone_pub + client_pub
    return (
        crypto.hkdf(secret, dh, b"OHS1 pair" + transcript),
        crypto.hkdf(secret, dh, b"OHS1 ltk" + transcript),
    )


def pair_confirm(ltk: bytes, client_pub: bytes, phone_pub: bytes) -> bytes:
    return crypto.hmac_sha256(ltk, b"OHS1 pair confirm" + client_pub + phone_pub)[:16]


def phone_id_for(phone_pub: bytes) -> str:
    """Short local identifier for CLI selection. Never sent over the air."""
    return hashlib.sha256(phone_pub).hexdigest()[:8]


@dataclass
class PairedPhone:
    """Everything the client stores about one paired phone. ``ltk`` is secret."""

    phone_id: str
    ltk: bytes
    phone_pub: bytes
    label: str = ""  # the label *we* gave ourselves, kept for display
    counter: int = 0  # last request counter used (persisted before sending)
    paired_at: int = 0

    @property
    def keys(self) -> SessionKeys:
        return derive_session_keys(self.ltk)


class ClientPairing:
    """Client side of the handshake. Build the PAIR_REQ, then feed the PAIR_RESP to ``finish``."""

    def __init__(
        self,
        qr: PairingQr,
        label: str,
        *,
        clock: Callable[[], float] = time.time,
        private_key: bytes | None = None,
        nonce: bytes | None = None,
        msg_id: int | None = None,
    ) -> None:
        label_bytes = label.encode()
        if len(label_bytes) > MAX_LABEL_BYTES:
            raise PairingError(f"label longer than {MAX_LABEL_BYTES} bytes")
        self._qr, self._label, self._clock = qr, label, clock
        self._priv = private_key if private_key is not None else crypto.x25519_generate()
        self._client_pub = crypto.x25519_public(self._priv)
        dh = crypto.x25519_shared(self._priv, qr.phone_pub)
        self._k_pair, self._ltk = derive_pairing_keys(dh, qr.secret, qr.phone_pub, self._client_pub)
        self._nonce_override = nonce
        self._msg_id = msg_id if msg_id is not None else secrets.randbelow(0x10000)
        self._req_nonce: bytes | None = None

    @property
    def client_pub(self) -> bytes:
        return self._client_pub

    def request_frame(self) -> Frame:
        ts = int(self._clock() * 1000)
        label = self._label.encode()
        plaintext = struct.pack(">QB", ts, len(label)) + label
        header = Frame(MsgType.PAIR_REQ, self._msg_id, b"").header
        sealed = seal_body(self._k_pair, header + self._client_pub, plaintext, self._nonce_override)
        self._req_nonce = sealed[:12]
        return Frame(MsgType.PAIR_REQ, self._msg_id, self._client_pub + sealed)

    def finish(self, frame: Frame) -> PairedPhone:
        if self._req_nonce is None:
            raise PairingError("request_frame() has not been built")
        if frame.msg_id != self._msg_id:
            raise ProtocolError(ErrorCode.MALFORMED, "PAIR_RESP msg_id mismatch")
        now_ms = int(self._clock() * 1000)
        if frame.type == MsgType.ERROR:
            if frame.plaintext:  # forgeable: a hint only
                code, retry = unpack_error_plain(frame.body)
                raise PhoneError(code, retry, authenticated=False)
            plaintext, _ = open_body(self._k_pair, frame.header + self._req_nonce, frame.body)
            _, _, code, retry = unpack_error_auth(plaintext)
            raise PhoneError(code, retry, authenticated=True)
        if frame.type != MsgType.PAIR_RESP or frame.plaintext:
            raise ProtocolError(ErrorCode.MALFORMED, "expected PAIR_RESP")
        plaintext, _ = open_body(self._k_pair, frame.header + self._req_nonce, frame.body)
        if len(plaintext) != 24:
            raise ProtocolError(ErrorCode.MALFORMED, "PAIR_RESP plaintext must be 24 bytes")
        (ts,) = struct.unpack(">Q", plaintext[:8])
        if abs(ts - now_ms) > TIMESTAMP_SKEW_MS:
            raise PairingError("PAIR_RESP timestamp outside the allowed skew (check clocks)")
        expected = pair_confirm(self._ltk, self._client_pub, self._qr.phone_pub)
        if not hmac.compare_digest(plaintext[8:], expected):
            raise AuthenticationError("key confirmation from the phone did not match")
        return PairedPhone(
            phone_id=phone_id_for(self._qr.phone_pub),
            ltk=self._ltk,
            phone_pub=self._qr.phone_pub,
            label=self._label,
            counter=0,
            paired_at=int(self._clock()),
        )
