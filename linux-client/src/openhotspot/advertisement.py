"""Advertisement tokens and recognition (PROTOCOL.md section 3)."""
from __future__ import annotations

import struct
from dataclasses import dataclass

from . import crypto
from .constants import (
    ADV_LABEL_PAIRED,
    ADV_LABEL_PAIRING,
    ADV_MODE_PAIRED,
    ADV_MODE_PAIRING,
    ADV_TOLERANCE_WINDOWS,
    ADV_WINDOW_S,
    SERVICE_UUID_BYTES,
    VERSION,
)

TOKEN_LEN = 8


@dataclass(frozen=True)
class Advertisement:
    """What a scanner reports: the service-data payload (after the UUID) of one advertisement."""

    address: str
    service_data: bytes  # mode_ver(1) | token(8)
    rssi: int | None = None


@dataclass(frozen=True)
class AdvertisementPayload:
    version: int
    mode: int
    token: bytes


def window_index(unix_seconds: float) -> int:
    return int(unix_seconds) // ADV_WINDOW_S


def make_token(key: bytes, label: bytes, window: int) -> bytes:
    return crypto.hmac_sha256(key, label + struct.pack(">Q", window))[:TOKEN_LEN]


def paired_token(k_adv: bytes, unix_seconds: float) -> bytes:
    return make_token(k_adv, ADV_LABEL_PAIRED, window_index(unix_seconds))


def pairing_token(pairing_secret: bytes, unix_seconds: float) -> bytes:
    return make_token(pairing_secret, ADV_LABEL_PAIRING, window_index(unix_seconds))


def build_payload(mode: int, token: bytes) -> bytes:
    return bytes([(VERSION << 4) | mode]) + token


def build_ad_structure(mode: int, token: bytes) -> bytes:
    """The full AD structure (Service Data, 128-bit UUID), 27 bytes."""
    payload = build_payload(mode, token)
    return bytes([1 + 16 + len(payload), 0x21]) + SERVICE_UUID_BYTES[::-1] + payload


def parse_payload(service_data: bytes) -> AdvertisementPayload | None:
    if len(service_data) != 1 + TOKEN_LEN or service_data[0] >> 4 != VERSION:
        return None
    mode = service_data[0] & 0x0F
    if mode not in (ADV_MODE_PAIRED, ADV_MODE_PAIRING):
        return None
    return AdvertisementPayload(VERSION, mode, service_data[1:])


def _matches(adv: Advertisement, mode: int, key: bytes, label: bytes, now: float) -> bool:
    payload = parse_payload(adv.service_data)
    if payload is None or payload.mode != mode:
        return False
    w = window_index(now)
    return any(
        payload.token == make_token(key, label, w + d)
        for d in range(-ADV_TOLERANCE_WINDOWS, ADV_TOLERANCE_WINDOWS + 1)
    )


def matches_paired(adv: Advertisement, k_adv: bytes, now: float) -> bool:
    """True if ``adv`` is a paired-mode token for this client's K_adv (windows w-1, w, w+1).
    The token is only a hint to connect; real authentication is the AEAD."""
    return _matches(adv, ADV_MODE_PAIRED, k_adv, ADV_LABEL_PAIRED, now)


def matches_pairing(adv: Advertisement, pairing_secret: bytes, now: float) -> bool:
    return _matches(adv, ADV_MODE_PAIRING, pairing_secret, ADV_LABEL_PAIRING, now)
