"""Chunking and reassembly (PROTOCOL.md section 8)."""
from __future__ import annotations

import struct
import time
from collections.abc import Callable

from .constants import FRAME_HEADER_LEN, MAX_FRAME, MIN_MTU, REASSEMBLY_TIMEOUT_S, ErrorCode
from .errors import ProtocolError

FIRST = 0x80
LAST = 0x40
SEQ_MASK = 0x3F


def chunk_frame(frame: bytes, mtu: int) -> list[bytes]:
    """Split an encoded frame into chunks that fit one ATT write/notification at ``mtu``."""
    if mtu < MIN_MTU:
        raise ValueError(f"MTU must be at least {MIN_MTU}")
    if not frame:
        raise ValueError("empty frame")
    size = mtu - 3 - 1
    parts = [frame[i : i + size] for i in range(0, len(frame), size)]
    last = len(parts) - 1
    return [
        bytes([(FIRST if i == 0 else 0) | (LAST if i == last else 0) | (i & SEQ_MASK)]) + p
        for i, p in enumerate(parts)
    ]


class Reassembler:
    """Reassembles chunks into frames. Any violation raises MALFORMED and discards the partial frame."""

    def __init__(self, clock: Callable[[], float] = time.monotonic, timeout: float = REASSEMBLY_TIMEOUT_S) -> None:
        self._clock = clock
        self._timeout = timeout
        self.reset()

    def reset(self) -> None:
        self._buf = bytearray()
        self._next_seq = 0
        self._total = 0
        self._started = 0.0
        self._active = False

    @property
    def in_progress(self) -> bool:
        return self._active

    def _fail(self, msg: str) -> ProtocolError:
        self.reset()
        return ProtocolError(ErrorCode.MALFORMED, msg)

    def feed(self, chunk: bytes) -> bytes | None:
        """Feed one chunk; returns the complete frame bytes when finished, else None."""
        if len(chunk) < 2:
            raise self._fail("empty chunk")
        if self._active and self._clock() - self._started > self._timeout:
            self.reset()  # stale partial frame is silently discarded
        head, fragment = chunk[0], chunk[1:]
        first, last, seq = bool(head & FIRST), bool(head & LAST), head & SEQ_MASK
        if first:
            self.reset()
            if seq != 0:
                raise self._fail("FIRST chunk must have sequence 0")
            self._active, self._started = True, self._clock()
        elif not self._active:
            raise self._fail("continuation chunk with nothing in progress")
        elif seq != self._next_seq:
            raise self._fail("chunk sequence gap")
        self._next_seq = (seq + 1) & SEQ_MASK
        self._buf += fragment
        if first:
            if len(self._buf) < FRAME_HEADER_LEN:
                raise self._fail("first chunk shorter than frame header")
            self._total = FRAME_HEADER_LEN + struct.unpack(">H", bytes(self._buf[5:7]))[0]
            if self._total > MAX_FRAME:
                raise self._fail("frame exceeds 512 bytes")
        if len(self._buf) > self._total:
            raise self._fail("more data than the frame header declares")
        complete = len(self._buf) == self._total
        if complete != last:
            raise self._fail("LAST flag disagrees with frame length")
        if not complete:
            return None
        frame = bytes(self._buf)
        self.reset()
        return frame
