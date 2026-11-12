"""Client orchestration: scan, connect, pair, request, verify (PROTOCOL.md section 7)."""
from __future__ import annotations

import asyncio
import logging
import secrets
import time
from collections.abc import Awaitable, Callable
from typing import TypeVar

from . import advertisement
from .chunking import Reassembler, chunk_frame
from .constants import VERSION, ErrorCode, MsgType
from .errors import OpenHotspotError, PhoneNotFound, ProtocolError, TransportError, TransportTimeout
from .framing import Frame
from .messages import HotspotInfo, Status, pack_enable_body
from .pairing import ClientPairing, PairedPhone, PairingQr
from .session import build_request, parse_response
from .storage import KeyStore
from .transport import Connection, Transport

log = logging.getLogger(__name__)
T = TypeVar("T")


def retryable(exc: BaseException) -> bool:
    """A transport failure worth another attempt: a dropped link or a transient BlueZ error.
    Not retried: no phone in range (the scan already waited) and a response timeout (the phone
    already had its 20 s)."""
    if not isinstance(exc, TransportError) or isinstance(exc, (PhoneNotFound, TransportTimeout)):
        return False
    return getattr(exc, "retryable", True)


class HotspotClient:
    def __init__(
        self,
        transport: Transport,
        store: KeyStore,
        *,
        clock: Callable[[], float] = time.time,
        scan_timeout: float = 10.0,
        response_timeout: float = 25.0,  # the phone answers within 20 s (PROTOCOL.md section 7)
        monotonic: Callable[[], float] = time.monotonic,
        attempts: int = 1,
        retry_delay: float = 1.0,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
    ) -> None:
        self.transport, self.store, self.clock = transport, store, clock
        self.scan_timeout, self.response_timeout, self._monotonic = scan_timeout, response_timeout, monotonic
        self.attempts, self.retry_delay, self._sleep = max(1, attempts), retry_delay, sleep

    # ---- low level -------------------------------------------------------------------------

    async def exchange(self, conn: Connection, frame: Frame) -> Frame:
        """Write one frame as chunks, then reassemble one response frame from notifications."""
        for chunk in chunk_frame(frame.encode(), conn.mtu):
            await conn.write_request(chunk)
        reassembler = Reassembler(self._monotonic)
        while True:
            raw = reassembler.feed(await conn.read_response_chunk(self.response_timeout))
            if raw is not None:
                return Frame.decode(raw)

    async def _find(self, matcher: Callable[[advertisement.Advertisement], bool]) -> advertisement.Advertisement:
        adv = await self.transport.scan(matcher, self.scan_timeout)
        if adv is None:
            raise PhoneNotFound("phone not found nearby (no matching advertisement)")
        return adv

    async def find_phone(self, phone: PairedPhone, timeout: float | None = None) -> advertisement.Advertisement | None:
        """Scan for ``phone``'s paired-mode token. Returns None if it is not heard within ``timeout``."""
        keys = phone.keys
        return await self.transport.scan(
            lambda a: advertisement.matches_paired(a, keys.k_adv, self.clock()),
            self.scan_timeout if timeout is None else timeout,
        )

    async def _retrying(self, op: Callable[[bool], Awaitable[T]]) -> T:
        """Run ``op(first_attempt)``, repeating on transient transport failures. Each attempt of a
        request uses a fresh counter, so a retry can never be mistaken for a replay."""
        for attempt in range(1, self.attempts + 1):
            try:
                return await op(attempt == 1)
            except TransportError as exc:
                if attempt == self.attempts or not retryable(exc):
                    raise
                log.info("attempt %d/%d failed (%s); retrying", attempt, self.attempts, exc)
                await self._sleep(self.retry_delay * attempt)
        raise AssertionError("unreachable")  # pragma: no cover

    # ---- pairing ---------------------------------------------------------------------------

    async def pair(self, qr: PairingQr, label: str) -> PairedPhone:
        adv = await self._find(lambda a: advertisement.matches_pairing(a, qr.secret, self.clock()))
        conn = await self.transport.connect(adv)
        try:
            phone = await self.pair_on(conn, qr, label)
        finally:
            await conn.close()
        return phone

    async def pair_on(self, conn: Connection, qr: PairingQr, label: str) -> PairedPhone:
        """Run the handshake over an existing connection and store the result."""
        pairing = ClientPairing(qr, label, clock=self.clock)
        response = await self.exchange(conn, pairing.request_frame())
        phone = pairing.finish(response)
        self.store.save(phone)
        return phone

    # ---- requests --------------------------------------------------------------------------

    async def _request(self, phone: PairedPhone, mtype: MsgType, body: bytes = b"",
                       adv: advertisement.Advertisement | None = None) -> bytes:
        keys = phone.keys

        async def attempt(first: bool) -> bytes:
            # A caller-supplied advertisement is only trusted for the first attempt; a retry rescans.
            found = adv if (first and adv is not None) else await self._find(
                lambda a: advertisement.matches_paired(a, keys.k_adv, self.clock()))
            conn = await self.transport.connect(found)
            try:
                # Persist the counter BEFORE sending so a crash can never reuse it.
                phone.counter += 1
                counter = phone.counter
                self.store.save(phone)
                msg_id = secrets.randbelow(0x10000)
                frame, nonce = build_request(keys.k_req, mtype, msg_id, int(self.clock() * 1000), counter, body)
                response = await self.exchange(conn, frame)
                return parse_response(
                    keys.k_resp, response, request_type=mtype, request_msg_id=msg_id,
                    request_nonce=nonce, request_counter=counter, now_ms=int(self.clock() * 1000),
                )
            finally:
                await conn.close()

        return await self._retrying(attempt)

    async def enable(self, phone: PairedPhone, auto_off_s: int = 0, *, adv: advertisement.Advertisement | None = None) -> HotspotInfo:
        return HotspotInfo.decode(await self._request(phone, MsgType.ENABLE, pack_enable_body(0, auto_off_s), adv))

    async def query(self, phone: PairedPhone, *, adv: advertisement.Advertisement | None = None) -> HotspotInfo:
        return HotspotInfo.decode(await self._request(phone, MsgType.QUERY, adv=adv))

    async def disable(self, phone: PairedPhone, *, adv: advertisement.Advertisement | None = None) -> None:
        body = await self._request(phone, MsgType.DISABLE, adv=adv)
        if len(body) != 1:
            raise ProtocolError(ErrorCode.MALFORMED, "DISABLE_RESP body must be 1 byte")

    async def unpair(self, phone: PairedPhone) -> bool:
        """Ask the phone to forget us (best effort), then always delete the local key.
        Returns True if the phone acknowledged."""
        acknowledged = False
        try:
            body = await self._request(phone, MsgType.UNPAIR)
            acknowledged = body == b""
        except OpenHotspotError:
            pass  # phone unreachable or refused: the local key is still removed
        self.store.delete(phone.phone_id)
        return acknowledged

    async def read_status(self, phone: PairedPhone) -> Status:
        """Unauthenticated hint only; never used for security decisions."""
        keys = phone.keys

        async def attempt(_first: bool) -> Status:
            adv = await self._find(lambda a: advertisement.matches_paired(a, keys.k_adv, self.clock()))
            conn = await self.transport.connect(adv)
            try:
                return Status.decode(await conn.read_status())
            finally:
                await conn.close()

        status = await self._retrying(attempt)
        if status.version != VERSION:
            raise ProtocolError(ErrorCode.UNSUPPORTED_VERSION, f"phone speaks protocol {status.version}")
        return status
