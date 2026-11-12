"""Frame envelope and encrypted-body helpers (PROTOCOL.md sections 5 and 6)."""
from __future__ import annotations

import struct
from dataclasses import dataclass

from . import crypto
from .constants import (
    FLAG_PLAINTEXT,
    FRAME_HEADER_LEN,
    MAX_FRAME,
    NONCE_LEN,
    TAG_LEN,
    VERSION,
    ErrorCode,
    MsgType,
)
from .errors import ProtocolError

_KNOWN_TYPES = frozenset(int(t) for t in MsgType)


@dataclass(frozen=True)
class Frame:
    type: int
    msg_id: int
    body: bytes
    flags: int = 0

    @property
    def header(self) -> bytes:
        """The 5 header bytes (version, type, flags, msg_id) used as AAD."""
        return struct.pack(">BBBH", VERSION, self.type, self.flags, self.msg_id)

    @property
    def plaintext(self) -> bool:
        return bool(self.flags & FLAG_PLAINTEXT)

    def encode(self) -> bytes:
        if FRAME_HEADER_LEN + len(self.body) > MAX_FRAME:
            raise ProtocolError(ErrorCode.MALFORMED, "frame exceeds 512 bytes")
        return self.header + struct.pack(">H", len(self.body)) + self.body

    @classmethod
    def decode(cls, raw: bytes) -> Frame:
        if len(raw) < FRAME_HEADER_LEN:
            raise ProtocolError(ErrorCode.MALFORMED, "frame shorter than header")
        if len(raw) > MAX_FRAME:
            raise ProtocolError(ErrorCode.MALFORMED, "frame exceeds 512 bytes")
        version, mtype, flags, msg_id, body_len = struct.unpack(">BBBHH", raw[:FRAME_HEADER_LEN])
        if version != VERSION:
            raise ProtocolError(ErrorCode.UNSUPPORTED_VERSION, f"version {version}")
        if mtype not in _KNOWN_TYPES:
            raise ProtocolError(ErrorCode.MALFORMED, f"unknown type 0x{mtype:02x}")
        if flags & ~FLAG_PLAINTEXT or (flags and mtype != MsgType.ERROR):
            raise ProtocolError(ErrorCode.MALFORMED, "invalid flags")
        if len(raw) != FRAME_HEADER_LEN + body_len:
            raise ProtocolError(ErrorCode.MALFORMED, "body length mismatch")
        return cls(type=mtype, msg_id=msg_id, body=raw[FRAME_HEADER_LEN:], flags=flags)


def seal_body(key: bytes, aad: bytes, plaintext: bytes, nonce: bytes | None = None) -> bytes:
    """Return ``nonce(12) || ciphertext || tag(16)``. A random nonce is used unless one is given
    (tests only: implementations MUST NOT derive nonces from counters or timestamps)."""
    nonce = nonce if nonce is not None else crypto.random_nonce()
    return nonce + crypto.aead_seal(key, nonce, aad, plaintext)


def split_sealed(body: bytes) -> tuple[bytes, bytes]:
    """Split ``nonce || ciphertext||tag``; returns (nonce, ciphertext_and_tag)."""
    if len(body) < NONCE_LEN + TAG_LEN:
        raise ProtocolError(ErrorCode.MALFORMED, "encrypted body too short")
    return body[:NONCE_LEN], body[NONCE_LEN:]


def open_body(key: bytes, aad: bytes, body: bytes) -> tuple[bytes, bytes]:
    """Decrypt ``nonce || ct||tag``; returns (plaintext, nonce)."""
    nonce, ct = split_sealed(body)
    return crypto.aead_open(key, nonce, aad, ct), nonce
