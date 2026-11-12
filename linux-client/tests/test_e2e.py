"""Full flows in-process: client library <-> FakePhone through the in-memory transport."""
import asyncio
import os

import pytest

from openhotspot import advertisement
from openhotspot.chunking import chunk_frame
from openhotspot.client import HotspotClient
from openhotspot.constants import ErrorCode, HotspotState, MsgType
from openhotspot.errors import AuthenticationError, PhoneError, PhoneNotFound, TransportError
from openhotspot.framing import Frame
from openhotspot.messages import pack_enable_body
from openhotspot.pairing import PairingQr
from openhotspot.session import build_request, parse_response
from openhotspot.transport import Connection, InMemoryTransport

run = asyncio.run


def pair(phone, client, label="Test Laptop"):
    return run(client.pair(PairingQr.parse(phone.open_pairing()), label))


class TamperConnection(Connection):
    """Flips the last byte of the last chunk written (request) and/or received (response)."""

    def __init__(self, inner, request=False, response=False):
        self.inner, self.request, self.response, self.mtu = inner, request, response, inner.mtu

    async def write_request(self, chunk):
        if self.request and chunk[0] & 0x40:
            chunk = chunk[:-1] + bytes([chunk[-1] ^ 1])
        await self.inner.write_request(chunk)

    async def read_response_chunk(self, timeout):
        chunk = await self.inner.read_response_chunk(timeout)
        if self.response and chunk[0] & 0x40:
            chunk = chunk[:-1] + bytes([chunk[-1] ^ 1])
        return chunk

    async def read_status(self):
        return await self.inner.read_status()

    async def close(self):
        await self.inner.close()


class TamperTransport(InMemoryTransport):
    def __init__(self, endpoint, mtu=247, **flags):
        super().__init__(endpoint, mtu)
        self.flags = flags

    async def connect(self, adv):
        return TamperConnection(await super().connect(adv), **self.flags)


def raw_connection(transport, client, phone_rec):
    adv = run(transport.scan(lambda a: advertisement.matches_paired(a, phone_rec.keys.k_adv, client.clock()), 1))
    return run(transport.connect(adv))


# ---- pairing ---------------------------------------------------------------------------------

@pytest.mark.parametrize("mtu", [23, 247])
def test_pairing_stores_matching_ltk(phone, make_client, mtu):
    client, _, store = make_client(mtu)
    paired = pair(phone, client)
    (record,) = phone.clients.values()
    assert record.ltk == paired.ltk and record.label == "Test Laptop"
    assert store.resolve().ltk == paired.ltk and store.resolve().counter == 0
    assert phone.pairing is None  # window closed after success


def test_pairing_window_is_single_use(phone, make_client):
    client, _, _ = make_client()
    qr = PairingQr.parse(phone.open_pairing())
    run(client.pair(qr, "one"))
    conn = run(client.transport.connect(advertisement.Advertisement("x", bytes(9))))
    with pytest.raises(PhoneError) as e:
        run(client.pair_on(conn, qr, "two"))
    assert e.value.code == ErrorCode.NOT_IN_PAIRING_MODE


def test_wrong_pairing_secret_is_rejected_and_three_failures_close_the_window(phone, make_client):
    client, transport, _ = make_client()
    good = PairingQr.parse(phone.open_pairing())
    bad = PairingQr(good.version, good.service_uuid, good.phone_pub, bytes(16))
    for _ in range(3):
        conn = run(transport.connect(advertisement.Advertisement("x", bytes(9))))
        with pytest.raises(PhoneError) as e:
            run(client.pair_on(conn, bad, "attacker"))
        assert e.value.code == ErrorCode.AUTH_FAILED and not e.value.authenticated
    assert phone.clients == {} and phone.pairing is None
    conn = run(transport.connect(advertisement.Advertisement("x", bytes(9))))
    with pytest.raises(PhoneError) as e:  # even the genuine QR no longer works
        run(client.pair_on(conn, good, "late"))
    assert e.value.code == ErrorCode.NOT_IN_PAIRING_MODE


def test_pairing_window_expires_after_120_seconds(phone, make_client, clock):
    client, _, _ = make_client()
    qr = PairingQr.parse(phone.open_pairing())
    clock.advance(121)
    with pytest.raises(PhoneNotFound):  # not advertising any more
        run(client.pair(qr, "late"))
    conn = run(client.transport.connect(advertisement.Advertisement("x", bytes(9))))
    with pytest.raises(PhoneError) as e:
        run(client.pair_on(conn, qr, "late"))
    assert e.value.code == ErrorCode.NOT_IN_PAIRING_MODE


def test_pairing_with_skewed_client_clock_gets_authenticated_stale_error(phone, make_client, clock):
    _, transport, store = make_client()
    skewed = HotspotClient(transport, store, clock=lambda: clock() + 120, monotonic=clock)
    with pytest.raises(PhoneError) as e:
        run(skewed.pair_on(run(transport.connect(advertisement.Advertisement("x", bytes(9)))),
                           PairingQr.parse(phone.open_pairing()), "skewed"))
    assert e.value.code == ErrorCode.STALE_TIMESTAMP and e.value.authenticated
    assert phone.clients == {}


def test_ninth_client_is_refused(phone, make_client):
    client, _, _ = make_client()
    for i in range(8):
        pair(phone, client, f"c{i}")
    with pytest.raises(PhoneError) as e:
        pair(phone, client, "ninth")
    assert e.value.code == ErrorCode.TOO_MANY_CLIENTS and e.value.authenticated


# ---- requests --------------------------------------------------------------------------------

@pytest.mark.parametrize("mtu", [23, 247])
def test_enable_returns_credentials_and_persists_counter(phone, make_client, mtu):
    client, transport, store = make_client(mtu)
    paired = pair(phone, client)
    info = run(client.enable(paired))
    assert (info.state, info.ssid, info.passphrase) == (HotspotState.ON, b"Fake Phone", "correct horse battery")
    assert phone.hotspot_on and phone.enable_calls == 1
    assert store.resolve().counter == 1
    assert run(client.query(paired)).state == HotspotState.ON and store.resolve().counter == 2
    run(client.disable(paired))
    assert not phone.hotspot_on and run(client.query(paired)).state == HotspotState.OFF
    # credentials never cross the link in plaintext
    wire = b"".join(b"".join(c.written + c.notified) for c in transport.connections)
    assert b"correct horse battery" not in wire and b"Fake Phone" not in wire


def test_query_while_off_reveals_nothing(phone, make_client):
    client, _, _ = make_client()
    info = run(client.query(pair(phone, client)))
    assert info.state == HotspotState.OFF and info.ssid == b"" and info.passphrase == ""


@pytest.mark.parametrize("attr,code", [
    ("shizuku_ready", ErrorCode.SHIZUKU_UNAVAILABLE), ("shizuku_permission", ErrorCode.PERMISSION_DENIED),
])
def test_phone_side_errors_are_authenticated(phone, make_client, attr, code):
    client, _, _ = make_client()
    paired = pair(phone, client)
    setattr(phone, attr, False)
    with pytest.raises(PhoneError) as e:
        run(client.enable(paired))
    assert e.value.code == code and e.value.authenticated
    assert not run(client.read_status(paired)).ready


def test_hotspot_failure_reported(phone, make_client):
    client, _, _ = make_client()
    paired = pair(phone, client)
    phone.hotspot_fails = True
    with pytest.raises(PhoneError) as e:
        run(client.enable(paired))
    assert e.value.code == ErrorCode.HOTSPOT_FAILED


def test_status_characteristic(phone, make_client):
    client, _, _ = make_client()
    paired = pair(phone, client)
    s = run(client.read_status(paired))
    assert s.ready and not s.pairing_open and not s.hotspot_active
    run(client.enable(paired))
    assert run(client.read_status(paired)).hotspot_active


# ---- replay ----------------------------------------------------------------------------------

def test_replayed_request_is_rejected_without_acting_or_rate_limiting(phone, make_client, clock):
    client, transport, store = make_client()
    rec = pair(phone, client)
    conn = raw_connection(transport, client, rec)
    keys = rec.keys
    ts = int(clock() * 1000)
    frame, nonce = build_request(keys.k_req, MsgType.ENABLE, 7, ts, 5, pack_enable_body())
    kw = dict(request_type=MsgType.ENABLE, request_msg_id=7, request_nonce=nonce, request_counter=5)
    parse_response(keys.k_resp, run(client.exchange(conn, frame)), **kw)
    assert phone.enable_calls == 1
    for _ in range(10):  # identical bytes replayed, plus a lower counter
        with pytest.raises(PhoneError) as e:
            parse_response(keys.k_resp, run(client.exchange(conn, frame)), **kw)
        assert e.value.code == ErrorCode.REPLAY and e.value.authenticated
    low, low_nonce = build_request(keys.k_req, MsgType.ENABLE, 8, ts, 4, pack_enable_body())
    with pytest.raises(PhoneError) as e:
        parse_response(keys.k_resp, run(client.exchange(conn, low)), request_type=MsgType.ENABLE,
                       request_msg_id=8, request_nonce=low_nonce, request_counter=4)
    assert e.value.code == ErrorCode.REPLAY
    assert phone.enable_calls == 1 and phone.limiter.retry_after() == 0


def test_client_rolling_back_its_counter_is_rejected(phone, make_client):
    client, _, store = make_client()
    rec = pair(phone, client)
    run(client.enable(rec))
    rec.counter = 0  # e.g. restored from an old backup
    with pytest.raises(PhoneError) as e:
        run(client.enable(rec))
    assert e.value.code == ErrorCode.REPLAY


def test_stale_timestamp_is_rejected(phone, make_client, clock):
    client, transport, store = make_client()
    rec = pair(phone, client)
    skewed = HotspotClient(transport, store, clock=lambda: clock() - 120, monotonic=clock)
    with pytest.raises(PhoneError) as e:
        run(skewed.enable(rec))
    assert e.value.code == ErrorCode.STALE_TIMESTAMP and e.value.authenticated
    assert phone.enable_calls == 0
    with pytest.raises(PhoneError):  # inside the 60 s window still works
        phone.shizuku_ready = False
        run(HotspotClient(transport, store, clock=lambda: clock() - 59, monotonic=clock).enable(rec))
    phone.shizuku_ready = True
    assert run(HotspotClient(transport, store, clock=lambda: clock() - 59, monotonic=clock).enable(rec)).state == HotspotState.ON


# ---- bad MAC ---------------------------------------------------------------------------------

def test_tampered_request_is_rejected_and_has_no_effect(phone, make_client, clock):
    client, transport, store = make_client()
    rec = pair(phone, client)
    bad = HotspotClient(TamperTransport(phone, request=True), store, clock=clock, monotonic=clock)
    with pytest.raises(PhoneError) as e:
        run(bad.enable(rec))
    assert e.value.code == ErrorCode.AUTH_FAILED and not e.value.authenticated
    assert phone.enable_calls == 0 and not phone.hotspot_on
    assert phone.clients and next(iter(phone.clients.values())).last_counter == 0


def test_tampered_response_is_rejected_by_the_client(phone, make_client, clock):
    client, _, store = make_client()
    rec = pair(phone, client)
    bad = HotspotClient(TamperTransport(phone, response=True), store, clock=clock, monotonic=clock)
    with pytest.raises(AuthenticationError):
        run(bad.enable(rec))


def test_request_from_an_unpaired_key_is_rejected(phone, make_client, clock, tmp_path):
    client, _, _ = make_client()
    pair(phone, client)
    from openhotspot.pairing import PairedPhone
    stranger = PairedPhone("deadbeef", os.urandom(32), os.urandom(32))
    conn = run(client.transport.connect(advertisement.Advertisement("x", bytes(9))))
    frame, nonce = build_request(stranger.keys.k_req, MsgType.ENABLE, 1, int(clock() * 1000), 1, pack_enable_body())
    with pytest.raises(PhoneError) as e:
        parse_response(stranger.keys.k_resp, run(client.exchange(conn, frame)), request_type=MsgType.ENABLE,
                       request_msg_id=1, request_nonce=nonce, request_counter=1)
    assert e.value.code == ErrorCode.AUTH_FAILED


# ---- rate limiting ---------------------------------------------------------------------------

def _garbage(client, conn, n=1):
    for i in range(n):
        frame = Frame(MsgType.ENABLE, i, os.urandom(40))
        for c in chunk_frame(frame.encode(), conn.mtu):
            run(conn.write_request(c))


def test_connection_is_dropped_after_three_failures(phone, make_client):
    client, transport, _ = make_client()
    pair(phone, client)
    conn = run(transport.connect(advertisement.Advertisement("x", bytes(9))))
    _garbage(client, conn, 3)
    with pytest.raises(TransportError):
        _garbage(client, conn, 1)


def test_global_lockout_blocks_even_valid_requests_without_decrypting(phone, make_client, clock):
    client, transport, store = make_client()
    rec = pair(phone, client)
    bad = HotspotClient(TamperTransport(phone, request=True), store, clock=clock, monotonic=clock)
    for _ in range(5):  # one failure per fresh connection, so the per-connection limit is not hit
        with pytest.raises(PhoneError) as e:
            run(bad.enable(rec))
        assert e.value.code == ErrorCode.AUTH_FAILED
    attempts = phone.decrypt_attempts
    with pytest.raises(PhoneError) as e:  # a perfectly valid request, during lockout
        run(client.enable(rec))
    assert e.value.code == ErrorCode.RATE_LIMITED and 1 <= e.value.retry_after_s <= 30 and not e.value.authenticated
    assert phone.decrypt_attempts == attempts and phone.enable_calls == 0
    clock.advance(31)
    assert run(client.enable(rec)).state == HotspotState.ON  # lockout over, legit client works again


def test_lockout_doubles_on_consecutive_lockouts(phone, make_client, clock):
    client, _, store = make_client()
    rec = pair(phone, client)
    bad = HotspotClient(TamperTransport(phone, request=True), store, clock=clock, monotonic=clock)

    def burst():
        for _ in range(5):
            with pytest.raises(PhoneError):
                run(bad.enable(rec))

    burst()
    clock.advance(31)
    burst()  # second lockout without an intervening success
    with pytest.raises(PhoneError) as e:
        run(client.enable(rec))
    assert e.value.code == ErrorCode.RATE_LIMITED and 31 < e.value.retry_after_s <= 60
    clock.advance(61)
    assert run(client.enable(rec)).state == HotspotState.ON


def test_old_failures_age_out_of_the_window(phone, make_client, clock):
    client, _, store = make_client()
    rec = pair(phone, client)
    bad = HotspotClient(TamperTransport(phone, request=True), store, clock=clock, monotonic=clock)
    for _ in range(4):
        with pytest.raises(PhoneError):
            run(bad.enable(rec))
        clock.advance(20)  # 4 failures spread over 80 s never reach 5 within 60 s
    assert run(client.enable(rec)).state == HotspotState.ON


# ---- revocation ------------------------------------------------------------------------------

def test_revoked_client_is_no_longer_advertised_or_served(phone, make_client):
    client, transport, _ = make_client()
    rec = pair(phone, client)
    run(client.enable(rec))
    old_adv = phone.advertisements()[0]
    (cid,) = phone.client_ids()
    phone.revoke(cid)
    assert phone.advertisements() == []
    with pytest.raises(PhoneNotFound):
        run(client.enable(rec))
    # a client that ignores the missing advertisement and connects anyway is still refused
    conn = run(transport.connect(old_adv))
    keys = rec.keys
    frame, nonce = build_request(keys.k_req, MsgType.QUERY, 9, int(client.clock() * 1000), 99)
    with pytest.raises(PhoneError) as e:
        parse_response(keys.k_resp, run(client.exchange(conn, frame)), request_type=MsgType.QUERY,
                       request_msg_id=9, request_nonce=nonce, request_counter=99)
    assert e.value.code == ErrorCode.AUTH_FAILED and not e.value.authenticated


def test_revoking_one_client_leaves_others_working(phone, make_client, clock, tmp_path):
    a, _, _ = make_client()
    rec_a = pair(phone, a)
    from openhotspot.storage import FileKeyStore
    b = HotspotClient(InMemoryTransport(phone), FileKeyStore(tmp_path / "other"), clock=clock, monotonic=clock)
    rec_b = pair(phone, b, "second")
    phone.revoke(next(k for k, v in phone.clients.items() if v.ltk == rec_a.ltk))
    assert run(b.query(rec_b)).state == HotspotState.OFF
    with pytest.raises(PhoneNotFound):
        run(a.query(rec_a))


def test_client_initiated_unpair(phone, make_client):
    client, _, store = make_client()
    rec = pair(phone, client)
    assert run(client.unpair(rec)) is True
    assert phone.clients == {} and store.list() == []


def test_unpair_when_phone_already_revoked_still_clears_local_key(phone, make_client):
    client, _, store = make_client()
    rec = pair(phone, client)
    phone.revoke(phone.client_ids()[0])
    assert run(client.unpair(rec)) is False
    assert store.list() == []
