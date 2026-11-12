"""Building requests and verifying responses (PROTOCOL.md section 6)."""
from __future__ import annotations

from .constants import TIMESTAMP_SKEW_MS, ErrorCode, MsgType
from .errors import PhoneError, ProtocolError
from .framing import Frame, open_body, seal_body
from .messages import (
    pack_request,
    unpack_error_auth,
    unpack_error_plain,
    unpack_response,
)


def build_request(
    k_req: bytes,
    mtype: int,
    msg_id: int,
    timestamp_ms: int,
    counter: int,
    body: bytes = b"",
    nonce: bytes | None = None,
) -> tuple[Frame, bytes]:
    """Returns (frame, nonce). ``aad`` is the 5 header bytes."""
    header = Frame(mtype, msg_id, b"").header
    sealed = seal_body(k_req, header, pack_request(timestamp_ms, counter, body), nonce)
    return Frame(mtype, msg_id, sealed), sealed[:12]


def parse_response(
    k_resp: bytes,
    frame: Frame,
    *,
    request_type: int,
    request_msg_id: int,
    request_nonce: bytes,
    request_counter: int,
    now_ms: int | None = None,
) -> bytes:
    """Verify and decrypt a response; returns its body. Raises PhoneError for ERROR frames.

    Checks type, msg_id, AAD binding to the request nonce, counter echo and (if ``now_ms``
    is given) the timestamp skew.
    """
    if frame.msg_id != request_msg_id:
        raise ProtocolError(ErrorCode.MALFORMED, "response msg_id does not match the request")
    if frame.type == MsgType.ERROR and frame.plaintext:
        code, retry = unpack_error_plain(frame.body)
        raise PhoneError(code, retry, authenticated=False)
    if frame.type not in (MsgType.ERROR, request_type | 0x80):
        raise ProtocolError(ErrorCode.MALFORMED, f"unexpected response type 0x{frame.type:02x}")
    plaintext, _ = open_body(k_resp, frame.header + request_nonce, frame.body)
    if frame.type == MsgType.ERROR:
        ts, ctr, code, retry = unpack_error_auth(plaintext)
    else:
        ts, ctr, body = unpack_response(plaintext)
    if ctr != request_counter:
        raise ProtocolError(ErrorCode.MALFORMED, "response counter echo does not match the request")
    if frame.type == MsgType.ERROR:
        # Surface authenticated errors before any skew check: with skewed clocks the useful
        # message is the phone's STALE_TIMESTAMP, not a local timestamp complaint.
        raise PhoneError(code, retry, authenticated=True)
    if now_ms is not None and abs(ts - now_ms) > TIMESTAMP_SKEW_MS:
        raise ProtocolError(ErrorCode.STALE_TIMESTAMP, "response timestamp outside the allowed skew")
    return body
