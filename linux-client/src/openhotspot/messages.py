"""Plaintext layouts inside encrypted frames (PROTOCOL.md sections 5 and 9)."""
from __future__ import annotations

import struct
from dataclasses import dataclass

from .constants import Band, ErrorCode, HotspotState, Security
from .errors import ProtocolError


def _malformed(msg: str) -> ProtocolError:
    return ProtocolError(ErrorCode.MALFORMED, msg)


# ---- request / response plaintext envelopes -------------------------------------------------

def pack_request(timestamp_ms: int, counter: int, body: bytes = b"") -> bytes:
    return struct.pack(">QQ", timestamp_ms, counter) + body


def unpack_request(plaintext: bytes) -> tuple[int, int, bytes]:
    if len(plaintext) < 16:
        raise _malformed("request plaintext too short")
    ts, counter = struct.unpack(">QQ", plaintext[:16])
    return ts, counter, plaintext[16:]


# The response envelope has the same shape: timestamp_ms | request_counter | body.
pack_response = pack_request
unpack_response = unpack_request


# ---- ENABLE request body --------------------------------------------------------------------

def pack_enable_body(options: int = 0, auto_off_s: int = 0) -> bytes:
    return struct.pack(">BH", options, auto_off_s)


def unpack_enable_body(body: bytes) -> tuple[int, int]:
    if len(body) != 3:
        raise _malformed("ENABLE body must be 3 bytes")
    return struct.unpack(">BH", body)


# ---- hotspot info (ENABLE_RESP / QUERY_RESP) -------------------------------------------------

@dataclass(frozen=True)
class HotspotInfo:
    state: HotspotState
    security: Security = Security.WPA2_PSK
    band: Band = Band.UNKNOWN
    ssid: bytes = b""
    passphrase: str = ""

    def encode(self) -> bytes:
        pw = self.passphrase.encode()
        if len(self.ssid) > 32:
            raise _malformed("SSID longer than 32 bytes")
        if len(pw) and not 8 <= len(pw) <= 63:
            raise _malformed("passphrase must be 8..63 bytes")
        return (
            struct.pack(">BBBB", self.state, self.security, self.band, len(self.ssid))
            + self.ssid
            + bytes([len(pw)])
            + pw
        )

    @classmethod
    def decode(cls, body: bytes) -> HotspotInfo:
        if len(body) < 5:
            raise _malformed("hotspot info too short")
        state, security, band, ssid_len = body[0], body[1], body[2], body[3]
        if ssid_len > 32 or len(body) < 5 + ssid_len:
            raise _malformed("bad SSID length")
        ssid = body[4 : 4 + ssid_len]
        pass_len = body[4 + ssid_len]
        if pass_len and not 8 <= pass_len <= 63:
            raise _malformed("bad passphrase length")
        if len(body) != 5 + ssid_len + pass_len:
            raise _malformed("hotspot info length mismatch")
        try:
            return cls(
                HotspotState(state), Security(security), Band(band), ssid,
                body[5 + ssid_len :].decode(),
            )
        except ValueError as exc:
            raise _malformed(f"invalid hotspot info field: {exc}") from exc


# ---- errors ---------------------------------------------------------------------------------

def pack_error_plain(code: int, retry_after_s: int = 0) -> bytes:
    return struct.pack(">BH", code, retry_after_s)


def unpack_error_plain(body: bytes) -> tuple[int, int]:
    if len(body) != 3:
        raise _malformed("plain error body must be 3 bytes")
    return struct.unpack(">BH", body)


def pack_error_auth(timestamp_ms: int, request_counter: int, code: int, retry_after_s: int = 0) -> bytes:
    return struct.pack(">QQBH", timestamp_ms, request_counter, code, retry_after_s)


def unpack_error_auth(plaintext: bytes) -> tuple[int, int, int, int]:
    if len(plaintext) != 19:
        raise _malformed("authenticated error plaintext must be 19 bytes")
    return struct.unpack(">QQBH", plaintext)


# ---- status characteristic ------------------------------------------------------------------

@dataclass(frozen=True)
class Status:
    version: int
    ready: bool
    pairing_open: bool
    hotspot_active: bool

    def encode(self) -> bytes:
        return bytes([self.version, int(self.ready) | int(self.pairing_open) << 1 | int(self.hotspot_active) << 2])

    @classmethod
    def decode(cls, raw: bytes) -> Status:
        if len(raw) != 2 or raw[1] & 0xF8:
            raise _malformed("bad status")
        return cls(raw[0], bool(raw[1] & 1), bool(raw[1] & 2), bool(raw[1] & 4))
