"""Client orchestration: scan, connect, pair, request, verify (PROTOCOL.md section 7)."""
from __future__ import annotations

import secrets
import time
from collections.abc import Callable

from . import advertisement
from .chunking import Reassembler, chunk_frame
from .constants import VERSION, ErrorCode, MsgType
from .errors import OpenHotspotError, PhoneNotFound, ProtocolError
from .framing import Frame
from .messages import HotspotInfo, Status, pack_enable_body
from .pairing import ClientPairing, PairedPhone, PairingQr
from .session import build_request, parse_response
from .storage import KeyStore
from .transport import Connection, Transport


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
    ) -> None:
        self.transport, self.store, self.clock = transport, store, clock
        self.scan_timeout, self.response_timeout, self._monotonic = scan_timeout, response_timeout, monotonic

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

    async def _request(self, phone: PairedPhone, mtype: MsgType, body: bytes = b"") -> bytes:
        keys = phone.keys
        adv = await self._find(lambda a: advertisement.matches_paired(a, keys.k_adv, self.clock()))
        conn = await self.transport.connect(adv)
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

    async def enable(self, phone: PairedPhone, auto_off_s: int = 0) -> HotspotInfo:
        return HotspotInfo.decode(await self._request(phone, MsgType.ENABLE, pack_enable_body(0, auto_off_s)))

    async def query(self, phone: PairedPhone) -> HotspotInfo:
        return HotspotInfo.decode(await self._request(phone, MsgType.QUERY))

    async def disable(self, phone: PairedPhone) -> None:
        body = await self._request(phone, MsgType.DISABLE)
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
        adv = await self._find(lambda a: advertisement.matches_paired(a, keys.k_adv, self.clock()))
        conn = await self.transport.connect(adv)
        try:
            status = Status.decode(await conn.read_status())
        finally:
            await conn.close()
        if status.version != VERSION:
            raise ProtocolError(ErrorCode.UNSUPPORTED_VERSION, f"phone speaks protocol {status.version}")
        return status
