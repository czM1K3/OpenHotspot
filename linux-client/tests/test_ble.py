"""BleakTransport against a fake bleak/BlueZ stack. Real BlueZ behaviour is in docs/TESTING.md."""
import asyncio

import pytest
from bleak.exc import (
    BleakBluetoothNotAvailableError,
    BleakBluetoothNotAvailableReason,
    BleakDBusError,
    BleakDeviceNotFoundError,
    BleakError,
)

from openhotspot import advertisement
from openhotspot.advertisement import Advertisement
from openhotspot.ble import BleakTransport, translate_error
from openhotspot.client import HotspotClient
from openhotspot.constants import ErrorCode, HotspotState
from openhotspot.errors import BluetoothError, PhoneError, PhoneNotFound, TransportError, TransportTimeout
from openhotspot.pairing import PairingQr
from openhotspot.storage import FileKeyStore
from tests.fake_ble import FakeBle


async def no_sleep(_s):
    return None


@pytest.fixture
def ble(phone):
    return FakeBle(phone)


@pytest.fixture
def transport(ble):
    return BleakTransport(scanner_factory=ble.scanner_factory, client_factory=ble.client_factory,
                          sleep=no_sleep, mtu_settle_s=0.3)


@pytest.fixture
def client(transport, clock, tmp_path):
    return HotspotClient(transport, FileKeyStore(tmp_path / "cfg"), clock=clock, monotonic=clock,
                         scan_timeout=0.2, response_timeout=0.3, attempts=2, retry_delay=0, sleep=no_sleep)


async def pair(client, phone):
    return await client.pair(PairingQr.parse(phone.open_pairing()), "laptop")


# ---- scanning ------------------------------------------------------------------------------

async def test_scan_recognises_the_paired_phone(client, phone, ble):
    paired = await pair(client, phone)
    adv = await client.find_phone(paired)
    assert adv is not None and adv.address == "AA:00:00:00:00:01"
    assert ble.scanners[-1].stopped  # scanning stops as soon as the phone is found


async def test_scan_ignores_other_services_and_foreign_tokens(client, phone, ble, transport):
    paired = await pair(client, phone)
    other = paired.keys.k_adv[::-1]
    token = advertisement.build_payload(0, advertisement.paired_token(other, client.clock()))
    ble.extra_adverts = [("BB:00:00:00:00:02", {"0000feed-0000-1000-8000-00805f9b34fb": token}),
                         ("BB:00:00:00:00:03", {"7e5f0001-8c3a-4d2b-9a61-0b4c7d9e1f20": token})]
    adv = await client.find_phone(paired)
    assert adv.address == "AA:00:00:00:00:01"  # the foreign token did not match; the phone did


async def test_scan_times_out_with_none(transport, ble):
    ble.phone.clients.clear()
    assert await transport.scan(lambda a: False, 0.05) is None
    assert ble.scanners[-1].stopped


async def test_find_raises_phone_not_found(client, phone):
    paired = await pair(client, phone)
    phone.revoke(next(iter(phone.client_ids())))  # no adverts any more
    with pytest.raises(PhoneNotFound):
        await client.enable(paired)


async def test_passive_scan_falls_back_to_active(phone, ble):
    t = BleakTransport(passive=True, scanner_factory=ble.scanner_factory, client_factory=ble.client_factory, sleep=no_sleep)
    ble.extra_adverts = [("CC:00:00:00:00:04", {"7e5f0001-8c3a-4d2b-9a61-0b4c7d9e1f20": b"\x10" + bytes(8)})]
    ble.scan_errors = [BleakError("passive scanning mode requires bluez or_patterns")]
    adv = await t.scan(lambda a: True, 0.2)
    assert adv is not None
    assert [s.passive for s in ble.scanners] == [True, False]


async def test_scan_errors_are_translated(phone, ble):
    t = BleakTransport(scanner_factory=ble.scanner_factory, client_factory=ble.client_factory, sleep=no_sleep)
    ble.scan_errors = [BleakBluetoothNotAvailableError("off", BleakBluetoothNotAvailableReason.POWERED_OFF)]
    with pytest.raises(BluetoothError, match="turned off.*rfkill"):
        await t.scan(lambda a: True, 0.1)


# ---- end to end ------------------------------------------------------------------------------

@pytest.mark.parametrize("mtu", [23, 100, 247, 517])
async def test_pair_enable_query_disable_over_bleak(client, phone, ble, mtu):
    ble.mtu = mtu
    paired = await pair(client, phone)
    info = await client.enable(paired)
    assert info.state == HotspotState.ON and info.ssid == b"Fake Phone"
    assert phone.hotspot_on
    assert (await client.query(paired)).state == HotspotState.ON
    await client.disable(paired)
    assert not phone.hotspot_on
    assert all(c.link.closed for c in ble.clients)  # every connection is closed again


async def test_chunking_follows_the_negotiated_mtu(client, phone, ble):
    ble.mtu = 23
    paired = await pair(client, phone)
    before = sum(c.writes for c in ble.clients)
    await client.enable(paired)
    assert sum(c.writes for c in ble.clients) - before > 1  # a 20-byte ATT payload needs several chunks


async def test_status_read(client, phone):
    paired = await pair(client, phone)
    status = await client.read_status(paired)
    assert status.ready and not status.hotspot_active


# ---- MTU ---------------------------------------------------------------------------------

async def test_mtu_waits_for_the_property_to_settle(transport, ble):
    ble.char_sizes = [20, 20, 244]
    conn = await transport.connect(Advertisement("AA:00:00:00:00:01", b""))
    assert conn.mtu == 247


async def test_mtu_falls_back_to_23_on_old_bluez(transport, ble):
    ble.char_sizes = [20]  # BlueZ < 5.62 always says 20
    conn = await transport.connect(Advertisement("AA:00:00:00:00:01", b""))
    assert conn.mtu == 23


async def test_mtu_is_capped_at_517(transport, ble):
    ble.char_sizes = [900]
    assert (await transport.connect(Advertisement("A", b""))).mtu == 517


# ---- connecting, retries, failures -------------------------------------------------------------

async def test_transient_connect_error_is_retried(transport, ble):
    ble.connect_errors = [BleakDBusError("org.bluez.Error.InProgress", [])]
    await transport.connect(Advertisement("AA:00:00:00:00:01", b""))
    assert ble.connect_calls == 2


async def test_connect_gives_up_after_the_attempt_limit(transport, ble):
    ble.connect_errors = [BleakDBusError("org.bluez.Error.Failed", ["le-connection-abort-by-local"])] * 3
    with pytest.raises(BluetoothError, match="le-connection-abort-by-local"):
        await transport.connect(Advertisement("A", b""))
    assert ble.connect_calls == 2


async def test_fatal_connect_error_is_not_retried(transport, ble):
    ble.connect_errors = [BleakDBusError("org.freedesktop.DBus.Error.ServiceUnknown", [])]
    with pytest.raises(BluetoothError, match="systemctl enable --now bluetooth"):
        await transport.connect(Advertisement("A", b""))
    assert ble.connect_calls == 1


async def test_connect_timeout_is_a_retryable_bluetooth_error(transport, ble):
    ble.connect_errors = [asyncio.TimeoutError()]
    await transport.connect(Advertisement("A", b""))
    assert ble.connect_calls == 2


async def test_missing_service_is_reported(transport, ble):
    ble.has_service = False
    with pytest.raises(BluetoothError, match="OpenHotspot service"):
        await transport.connect(Advertisement("A", b""))
    assert ble.clients[-1].connected is False  # cleaned up


async def test_response_timeout(client, phone, ble):
    paired = await pair(client, phone)
    ble.swallow_notifications = True
    with pytest.raises(TransportTimeout):
        await client.enable(paired)
    assert ble.connect_calls == 2  # pairing + one request: a response timeout is not retried


async def test_disconnect_mid_exchange_is_retried_with_a_new_counter(client, phone, ble):
    paired = await pair(client, phone)
    counter = paired.counter
    ble.drop_after_writes = 1  # the first request's link drops right after the write
    info = await client.enable(paired)
    assert info.state == HotspotState.ON
    assert paired.counter == counter + 2  # two attempts, two counters: never a replay


async def test_disconnect_without_retries_surfaces_a_clear_error(transport, phone, ble, clock, tmp_path):
    c = HotspotClient(transport, FileKeyStore(tmp_path / "cfg2"), clock=clock, monotonic=clock,
                      scan_timeout=0.2, response_timeout=0.3, attempts=1)
    paired = await pair(c, phone)
    ble.drop_after_writes = 1
    with pytest.raises(TransportError, match="disconnected"):
        await c.enable(paired)


async def test_phone_errors_are_not_retried(client, phone, ble):
    paired = await pair(client, phone)
    phone.shizuku_ready = False
    calls = ble.connect_calls
    with pytest.raises(PhoneError) as e:
        await client.enable(paired)
    assert e.value.code == ErrorCode.SHIZUKU_UNAVAILABLE and ble.connect_calls == calls + 1


async def test_close_is_quiet_when_bluez_is_already_gone(transport, ble):
    conn = await transport.connect(Advertisement("A", b""))
    ble.clients[-1].connected = False
    conn.on_disconnect()
    await conn.close()
    with pytest.raises(TransportError):
        await conn.write_request(b"\xc0\x01")


# ---- error translation -----------------------------------------------------------------------

@pytest.mark.parametrize("exc, needle, retryable", [
    (BleakDBusError("org.freedesktop.DBus.Error.AccessDenied", []), "D-Bus refused", False),
    (BleakDBusError("org.freedesktop.DBus.Error.NameHasNoOwner", []), "bluetoothd", False),
    (BleakDBusError("org.bluez.Error.NotReady", []), "bluetoothctl power on", False),
    (BleakDBusError("org.bluez.Error.InProgress", []), "busy", True),
    (BleakDBusError("org.bluez.Error.Whatever", ["x"]), "BlueZ error", False),
    (BleakBluetoothNotAvailableError("n", BleakBluetoothNotAvailableReason.NO_BLUETOOTH), "no Bluetooth adapter", False),
    (BleakDeviceNotFoundError("AA"), "forgot the device", True),
    (BleakError("Bleak requires BlueZ >= 5.55."), "Upgrade BlueZ", False),
    (FileNotFoundError(), "system D-Bus", False),
    (PermissionError(), "permission denied", False),
    (asyncio.TimeoutError(), "timed out", True),
])
def test_translate_error(exc, needle, retryable):
    out = translate_error(exc)
    assert isinstance(out, BluetoothError) and needle in str(out) and out.retryable is retryable


def test_translate_error_passthrough_and_unknown():
    original = TransportError("x")
    assert translate_error(original) is original
    assert "disconnected" in str(translate_error(BleakError("Not connected")))
    assert "RuntimeError" in str(translate_error(RuntimeError("boom")))
