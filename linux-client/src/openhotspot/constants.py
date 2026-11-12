"""Constants from docs/PROTOCOL.md (version 1). Keep in sync with the spec."""
from __future__ import annotations

import uuid
from enum import IntEnum

VERSION = 0x01

SERVICE_UUID = "7e5f0001-8c3a-4d2b-9a61-0b4c7d9e1f20"
CHAR_REQUEST_UUID = "7e5f0002-8c3a-4d2b-9a61-0b4c7d9e1f20"
CHAR_RESPONSE_UUID = "7e5f0003-8c3a-4d2b-9a61-0b4c7d9e1f20"
CHAR_STATUS_UUID = "7e5f0004-8c3a-4d2b-9a61-0b4c7d9e1f20"
SERVICE_UUID_BYTES = uuid.UUID(SERVICE_UUID).bytes  # RFC 4122 order

HEADER_LEN = 5  # version, type, flags, msg_id (AAD part)
FRAME_HEADER_LEN = 7  # header + body_len
MAX_FRAME = 512
NONCE_LEN = 12
TAG_LEN = 16
MIN_MTU = 23

FLAG_PLAINTEXT = 0x01

ADV_WINDOW_S = 300
ADV_TOLERANCE_WINDOWS = 1
ADV_MODE_PAIRED = 0
ADV_MODE_PAIRING = 1
ADV_LABEL_PAIRED = b"OHS1 adv"
ADV_LABEL_PAIRING = b"OHS1 adv-pair"

TIMESTAMP_SKEW_MS = 60_000
PAIRING_WINDOW_S = 120
PAIRING_MAX_FAILURES = 3
MAX_CLIENTS = 8
MAX_LABEL_BYTES = 32
REASSEMBLY_TIMEOUT_S = 5.0

QR_PREFIX = "openhotspot://pair/"
QR_RAW_LEN = 65


class MsgType(IntEnum):
    PAIR_REQ = 0x01
    PAIR_RESP = 0x81
    ENABLE = 0x10
    ENABLE_RESP = 0x90
    DISABLE = 0x11
    DISABLE_RESP = 0x91
    QUERY = 0x12
    QUERY_RESP = 0x92
    UNPAIR = 0x13
    UNPAIR_RESP = 0x93
    ERROR = 0xFF


REQUEST_TYPES = frozenset({MsgType.ENABLE, MsgType.DISABLE, MsgType.QUERY, MsgType.UNPAIR})


class ErrorCode(IntEnum):
    MALFORMED = 0x01
    UNSUPPORTED_VERSION = 0x02
    AUTH_FAILED = 0x03
    RATE_LIMITED = 0x04
    REPLAY = 0x05
    STALE_TIMESTAMP = 0x06
    NOT_IN_PAIRING_MODE = 0x07
    BUSY = 0x08
    TOO_MANY_CLIENTS = 0x09
    SHIZUKU_UNAVAILABLE = 0x10
    PERMISSION_DENIED = 0x11
    HOTSPOT_FAILED = 0x12
    UNSUPPORTED_REQUEST = 0x13
    INTERNAL = 0xFF


class HotspotState(IntEnum):
    OFF = 0
    STARTING = 1
    ON = 2


class Security(IntEnum):
    OPEN = 0
    WPA2_PSK = 1
    WPA3_SAE = 2
    WPA2_WPA3 = 3


class Band(IntEnum):
    UNKNOWN = 0
    GHZ_2_4 = 1
    GHZ_5 = 2
    GHZ_6 = 3
