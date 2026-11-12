"""In-process implementation of the *phone* side of docs/PROTOCOL.md, for end-to-end tests.

It reuses the package's primitives (crypto, framing, chunking) but implements the phone's own
logic itself: pairing window, trial decryption, replay checks, rate limiting, revocation,
the hotspot state machine and advertisements. It satisfies ``openhotspot.transport.PhoneEndpoint``.
"""
from __future__ import annotations

import hmac
import hashlib
import struct
from collections import deque
from collections.abc import Callable
from dataclasses import dataclass, field

from openhotspot import advertisement, crypto
from openhotspot.advertisement import Advertisement
from openhotspot.chunking import Reassembler, chunk_frame
from openhotspot.constants import (
    ADV_MODE_PAIRED,
    ADV_MODE_PAIRING,
    MAX_CLIENTS,
    MAX_LABEL_BYTES,
    PAIRING_MAX_FAILURES,
    PAIRING_WINDOW_S,
    REQUEST_TYPES,
    SERVICE_UUID_BYTES,
    TIMESTAMP_SKEW_MS,
    VERSION,
    Band,
    ErrorCode,
    HotspotState,
    MsgType,
    Security,
)
from openhotspot.errors import AuthenticationError, ProtocolError
from openhotspot.framing import Frame, open_body, seal_body
from openhotspot.messages import (
    HotspotInfo,
    Status,
    pack_error_auth,
    pack_error_plain,
    pack_response,
    unpack_enable_body,
    unpack_request,
)
from openhotspot.pairing import PairingQr, derive_pairing_keys, derive_session_keys, pair_confirm

# Rate limiting, SECURITY.md section 3
CONN_MAX_FAILURES = 3
GLOBAL_FAILURES = 5
GLOBAL_WINDOW_S = 60
LOCKOUT_BASE_S = 30
LOCKOUT_MAX_S = 3600
QUIET_RESET_S = 3600


class RateLimiter:
    def __init__(self, clock: Callable[[], float]) -> None:
        self._clock = clock
        self._failures: deque[float] = deque()
        self._level = 0
        self._locked_until = 0.0
        self._last_failure = 0.0

    def retry_after(self) -> int:
        remaining = self._locked_until - self._clock()
        return max(0, int(remaining + 0.999)) if remaining > 0 else 0

    def failure(self) -> None:
        now = self._clock()
        if self._level and now - self._last_failure >= QUIET_RESET_S:
            self._level = 0
        self._last_failure = now
        self._failures.append(now)
        while self._failures and now - self._failures[0] > GLOBAL_WINDOW_S:
            self._failures.popleft()
        if len(self._failures) >= GLOBAL_FAILURES:
            self._level += 1
            self._locked_until = now + min(LOCKOUT_BASE_S * 2 ** (self._level - 1), LOCKOUT_MAX_S)
            self._failures.clear()

    def success(self) -> None:
        self._level = 0
        self._failures.clear()


@dataclass
class ClientRecord:
    ltk: bytes
    label: str
    last_counter: int = 0

    def __post_init__(self) -> None:
        self.keys = derive_session_keys(self.ltk)


@dataclass
class Pairing:
    priv: bytes
    pub: bytes
    secret: bytes
    expires: float
    failures: int = 0


class FakeLink:
    """One BLE connection as seen by the phone."""

    def __init__(self, phone: FakePhone, mtu: int) -> None:
        self.phone, self.mtu = phone, mtu
        self.closed = False
        self.failures = 0
        self._reasm = Reassembler(phone.clock)

    def on_write(self, chunk: bytes) -> list[bytes]:
        if self.closed:
            return []
        try:
            raw = self._reasm.feed(chunk)
        except ProtocolError as exc:
            return self.phone._reply(self, self.phone._plain_error(0, exc.code), count_failure=True)
        if raw is None:
            return []
        return self.phone._on_frame(self, raw)

    def read_status(self) -> bytes:
        return self.phone.status().encode()

    def close(self) -> None:
        self.closed = True


class FakePhone:
    def __init__(
        self,
        clock: Callable[[], float],
        *,
        ssid: bytes = b"Fake Phone",
        passphrase: str = "correct horse battery",
        security: Security = Security.WPA2_WPA3,
        band: Band = Band.GHZ_5,
    ) -> None:
        self.clock = clock
        self.ssid, self.passphrase, self.security, self.band = ssid, passphrase, security, band
        self.clients: dict[str, ClientRecord] = {}
        self.pairing: Pairing | None = None
        self.limiter = RateLimiter(clock)
        self.hotspot_on = False
        self.shizuku_ready = True
        self.shizuku_permission = True
        self.hotspot_fails = False
        self.enable_calls = 0
        self.links: list[FakeLink] = []
        self.decrypt_attempts = 0  # counts trial decryptions, to prove lockout skips them

    # ---- user actions on the phone ---------------------------------------------------------

    def open_pairing(self) -> str:
        priv = crypto.x25519_generate()
        self.pairing = Pairing(priv, crypto.x25519_public(priv), crypto.random_bytes(16), self.clock() + PAIRING_WINDOW_S)
        return PairingQr(VERSION, SERVICE_UUID_BYTES, self.pairing.pub, self.pairing.secret).encode()

    def revoke(self, client_id: str) -> None:
        del self.clients[client_id]

    def client_ids(self) -> list[str]:
        return list(self.clients)

    # ---- PhoneEndpoint ---------------------------------------------------------------------

    def _pairing_open(self) -> bool:
        if self.pairing and self.clock() > self.pairing.expires:
            self.pairing = None
        return self.pairing is not None

    def advertisements(self) -> list[Advertisement]:
        now = self.clock()
        if self._pairing_open():
            tok = advertisement.pairing_token(self.pairing.secret, now)
            return [Advertisement("AA:00:00:00:00:01", advertisement.build_payload(ADV_MODE_PAIRING, tok))]
        return [
            Advertisement("AA:00:00:00:00:01", advertisement.build_payload(ADV_MODE_PAIRED, advertisement.paired_token(c.keys.k_adv, now)))
            for c in self.clients.values()
        ]

    def open_link(self, mtu: int) -> FakeLink:
        link = FakeLink(self, mtu)
        self.links.append(link)
        return link

    def status(self) -> Status:
        return Status(VERSION, self.shizuku_ready and self.shizuku_permission, self._pairing_open(), self.hotspot_on)

    # ---- framing helpers -------------------------------------------------------------------

    def _plain_error(self, msg_id: int, code: ErrorCode, retry: int = 0) -> Frame:
        return Frame(MsgType.ERROR, msg_id, pack_error_plain(code, retry), flags=1)

    def _auth_error(self, key: bytes, msg_id: int, req_nonce: bytes, req_counter: int, code: ErrorCode, retry: int = 0) -> Frame:
        header = Frame(MsgType.ERROR, msg_id, b"").header
        plaintext = pack_error_auth(int(self.clock() * 1000), req_counter, code, retry)
        return Frame(MsgType.ERROR, msg_id, seal_body(key, header + req_nonce, plaintext))

    def _reply(self, link: FakeLink, frame: Frame, *, count_failure: bool = False) -> list[bytes]:
        if count_failure:
            self.limiter.failure()
            link.failures += 1
        chunks = chunk_frame(frame.encode(), link.mtu)
        if link.failures >= CONN_MAX_FAILURES:
            link.closed = True
        return chunks

    # ---- dispatch --------------------------------------------------------------------------

    def _on_frame(self, link: FakeLink, raw: bytes) -> list[bytes]:
        msg_id = struct.unpack(">H", raw[3:5])[0] if len(raw) >= 5 else 0
        retry = self.limiter.retry_after()
        if retry:  # locked out: answer without attempting any decryption
            return self._reply(link, self._plain_error(msg_id, ErrorCode.RATE_LIMITED, retry))
        try:
            frame = Frame.decode(raw)
        except ProtocolError as exc:
            return self._reply(link, self._plain_error(msg_id, exc.code), count_failure=True)
        if frame.type == MsgType.PAIR_REQ and not frame.plaintext:
            return self._on_pair_req(link, frame)
        if frame.type in REQUEST_TYPES:
            return self._on_request(link, frame)
        return self._reply(link, self._plain_error(msg_id, ErrorCode.MALFORMED), count_failure=True)

    # ---- pairing ---------------------------------------------------------------------------

    def _on_pair_req(self, link: FakeLink, frame: Frame) -> list[bytes]:
        if not self._pairing_open():
            return self._reply(link, self._plain_error(frame.msg_id, ErrorCode.NOT_IN_PAIRING_MODE))
        pairing = self.pairing
        assert pairing is not None
        if len(frame.body) < 32 + 12 + 16:
            return self._pair_failure(link, frame, ErrorCode.MALFORMED)
        client_pub, sealed = frame.body[:32], frame.body[32:]
        try:
            dh = crypto.x25519_shared(pairing.priv, client_pub)
            k_pair, ltk = derive_pairing_keys(dh, pairing.secret, pairing.pub, client_pub)
            self.decrypt_attempts += 1
            plaintext, req_nonce = open_body(k_pair, frame.header + client_pub, sealed)
        except AuthenticationError:
            return self._pair_failure(link, frame, ErrorCode.AUTH_FAILED)
        except ProtocolError:
            return self._pair_failure(link, frame, ErrorCode.MALFORMED)
        # Authenticated from here on: errors use K_pair (PROTOCOL.md 4.3, "Errors during pairing")
        err = lambda code: self._reply(link, self._auth_error(k_pair, frame.msg_id, req_nonce, 0, code))
        if len(plaintext) < 9 or len(plaintext) != 9 + plaintext[8] or plaintext[8] > MAX_LABEL_BYTES:
            return err(ErrorCode.MALFORMED)
        (ts,) = struct.unpack(">Q", plaintext[:8])
        if abs(ts - self.clock() * 1000) > TIMESTAMP_SKEW_MS:
            return err(ErrorCode.STALE_TIMESTAMP)
        if len(self.clients) >= MAX_CLIENTS:
            return err(ErrorCode.TOO_MANY_CLIENTS)
        client_id = hashlib.sha256(client_pub).hexdigest()[:8]
        self.clients[client_id] = ClientRecord(ltk, plaintext[9:].decode())
        self.pairing = None  # single use
        body_plain = struct.pack(">Q", int(self.clock() * 1000)) + pair_confirm(ltk, client_pub, pairing.pub)
        header = Frame(MsgType.PAIR_RESP, frame.msg_id, b"").header
        resp = Frame(MsgType.PAIR_RESP, frame.msg_id, seal_body(k_pair, header + req_nonce, body_plain))
        return self._reply(link, resp)

    def _pair_failure(self, link: FakeLink, frame: Frame, code: ErrorCode) -> list[bytes]:
        assert self.pairing is not None
        self.pairing.failures += 1
        if self.pairing.failures >= PAIRING_MAX_FAILURES:
            self.pairing = None  # window closes; a new QR is required
        return self._reply(link, self._plain_error(frame.msg_id, code), count_failure=True)

    # ---- requests --------------------------------------------------------------------------

    def _on_request(self, link: FakeLink, frame: Frame) -> list[bytes]:
        found: tuple[ClientRecord, bytes, bytes] | None = None
        for rec in self.clients.values():  # trial decryption, no client id on the wire
            self.decrypt_attempts += 1
            try:
                plaintext, nonce = open_body(rec.keys.k_req, frame.header, frame.body)
            except (AuthenticationError, ProtocolError):
                continue
            found = (rec, plaintext, nonce)
            break
        if found is None:
            return self._reply(link, self._plain_error(frame.msg_id, ErrorCode.AUTH_FAILED), count_failure=True)
        rec, plaintext, nonce = found
        k_resp = rec.keys.k_resp

        def err(code: ErrorCode, counter: int = 0) -> list[bytes]:
            return self._reply(link, self._auth_error(k_resp, frame.msg_id, nonce, counter, code))

        try:
            ts, counter, body = unpack_request(plaintext)
        except ProtocolError:
            return err(ErrorCode.MALFORMED)
        if abs(ts - self.clock() * 1000) > TIMESTAMP_SKEW_MS:
            return err(ErrorCode.STALE_TIMESTAMP, counter)
        if counter <= rec.last_counter:
            return err(ErrorCode.REPLAY, counter)
        rec.last_counter = counter  # only after AEAD and timestamp success, before acting
        self.limiter.success()
        try:
            resp_body = self._execute(frame.type, body, rec)
        except _RequestError as e:
            return err(e.code, counter)
        header = Frame(frame.type | 0x80, frame.msg_id, b"").header
        sealed = seal_body(k_resp, header + nonce, pack_response(int(self.clock() * 1000), counter, resp_body))
        if frame.type == MsgType.UNPAIR:
            self.clients = {k: v for k, v in self.clients.items() if v is not rec}
        return self._reply(link, Frame(frame.type | 0x80, frame.msg_id, sealed))

    def _info(self) -> HotspotInfo:
        if not self.hotspot_on:
            return HotspotInfo(HotspotState.OFF)
        return HotspotInfo(HotspotState.ON, self.security, self.band, self.ssid, self.passphrase)

    def _execute(self, mtype: int, body: bytes, rec: ClientRecord) -> bytes:
        if mtype == MsgType.ENABLE:
            try:
                unpack_enable_body(body)
            except ProtocolError:
                raise _RequestError(ErrorCode.MALFORMED)
            if not self.shizuku_ready:
                raise _RequestError(ErrorCode.SHIZUKU_UNAVAILABLE)
            if not self.shizuku_permission:
                raise _RequestError(ErrorCode.PERMISSION_DENIED)
            if self.hotspot_fails:
                raise _RequestError(ErrorCode.HOTSPOT_FAILED)
            self.enable_calls += 1
            self.hotspot_on = True
            return self._info().encode()
        if body:
            raise _RequestError(ErrorCode.MALFORMED)
        if mtype == MsgType.QUERY:
            return self._info().encode()
        if mtype == MsgType.DISABLE:
            self.hotspot_on = False
            return bytes([HotspotState.OFF])
        return b""  # UNPAIR


class _RequestError(Exception):
    def __init__(self, code: ErrorCode) -> None:
        self.code = code
