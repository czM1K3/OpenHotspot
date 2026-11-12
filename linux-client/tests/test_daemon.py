import re
"""Daemon logic with a fake phone, fake network state and a fake clock (no BLE, no NetworkManager)."""
import asyncio

import pytest

from openhotspot.config import Config, ConnectConfig, DaemonConfig, parse_config
from openhotspot.connectivity import Connectivity, ConnectivityChecker
from openhotspot.constants import ErrorCode, Security
from openhotspot.daemon import Activity, Daemon, Mode
from openhotspot.errors import BluetoothError, WifiError
from openhotspot.notify import Notifier
from openhotspot.pairing import PairingQr
from openhotspot.state import StateFile
from openhotspot.wifi import WifiConnector


class FakeNet(ConnectivityChecker):
    def __init__(self, state=Connectivity.NONE):
        self.state, self.probes, self.polls = state, 0, 0

    def check(self, probe=False):
        self.probes += probe
        self.polls += not probe
        return self.state


class FakeWifi(WifiConnector):
    def __init__(self, net, internet=True):
        self.net, self.internet, self.connected, self.fail = net, internet, None, None
        self.disconnects = 0

    def connect(self, ssid, passphrase, security=Security.WPA2_PSK, band=None):
        if self.fail:
            raise self.fail
        self.connected = ssid
        self.net.state = Connectivity.FULL if self.internet else Connectivity.LIMITED

    def disconnect(self, ssid=None):
        self.disconnects += 1
        was, self.connected = self.connected is not None, None
        self.net.state = Connectivity.NONE
        return was


class Notes(Notifier):
    def __init__(self):
        self.sent = []

    def notify(self, title, body="", *, urgent=False):
        self.sent.append((title, body, urgent))


class Env:
    pass


@pytest.fixture
def env(clock, phone, make_client, tmp_path):
    e = Env()
    e.clock, e.phone = clock, phone
    e.client, e.transport, e.store = make_client()
    asyncio.run(e.client.pair(PairingQr.parse(phone.open_pairing()), "laptop"))
    e.net = FakeNet()
    e.wifi = FakeWifi(e.net)
    e.notes = Notes()
    e.state = StateFile(tmp_path / "state.json")
    scans = []
    real_scan = e.transport.scan

    async def counting_scan(matcher, timeout):
        scans.append(timeout)
        return await real_scan(matcher, timeout)

    e.transport.scan = counting_scan
    e.scans = scans

    def make(mode="auto", **daemon_kw):
        cfg = Config(daemon=DaemonConfig(mode=mode, **daemon_kw), connect=ConnectConfig(verify_timeout_s=0.05))
        e.daemon = Daemon(e.client, e.store, e.wifi, e.net, cfg, notifier=e.notes, state=e.state, clock=clock)
        return e.daemon

    e.make = make
    return e


def tick(daemon):
    return asyncio.run(daemon.tick())


def test_auto_connects_when_offline_and_phone_in_range(env):
    d = env.make()
    r = tick(d)
    assert r.activity == Activity.CONNECTED and env.wifi.connected == b"Fake Phone"
    assert env.phone.hotspot_on and env.net.probes >= 1  # connectivity was verified, not assumed
    assert env.notes.sent == [("Connected to your phone's hotspot", "Fake Phone", False)]
    assert "correct horse" not in repr(env.notes.sent)


def test_online_means_no_scanning(env):
    env.net.state = Connectivity.FULL
    r = tick(env.make())
    assert r.activity == Activity.ONLINE and env.scans == [] and r.delay == 30.0


def test_off_mode_does_nothing(env):
    r = tick(env.make("off"))
    assert r.activity == Activity.OFF and env.scans == [] and env.net.polls == 0


def test_scan_is_short(env):
    env.phone.clients.clear()
    tick(env.make(scan_duration_s=4.0))
    assert env.scans == [4.0]


def test_manual_mode_only_notifies_once_per_interval(env):
    d = env.make("manual")
    assert tick(d).activity == Activity.PHONE_NEARBY
    assert not env.phone.hotspot_on and env.wifi.connected is None
    assert [n[0] for n in env.notes.sent] == ["Phone nearby"]
    tick(d)
    assert len(env.notes.sent) == 1
    env.clock.advance(1801)
    tick(d)
    assert len(env.notes.sent) == 2


def test_request_connect_overrides_mode_and_cooldown(env):
    d = env.make("manual")
    d.request_connect()
    r = tick(d)
    assert r.activity == Activity.CONNECTED and env.phone.hotspot_on
    env.wifi.disconnect(); env.phone.hotspot_on = False
    d.request_connect()
    assert tick(d).activity == Activity.CONNECTED  # forced again despite the cooldown


def test_phone_out_of_range_backs_off_then_resets(env):
    saved = dict(env.phone.clients)
    env.phone.clients.clear()
    d = env.make()
    delays = [tick(d).delay for _ in range(9)]
    assert delays == [60, 60, 60, 60, 60, 120, 240, 300, 300]  # 5 scans at the base interval, then doubling to the cap
    assert tick(d).activity == Activity.SEARCHING
    env.phone.clients.update(saved)
    assert tick(d).activity == Activity.CONNECTED
    assert d._misses == 0


def test_cooldown_after_success_stops_immediate_retrigger(env):
    d = env.make()
    tick(d)
    env.wifi.disconnect(); env.phone.hotspot_on = False  # the link drops straight away
    scans_before = len(env.scans)
    r = tick(d)
    assert r.activity == Activity.COOLDOWN and len(env.scans) == scans_before  # no radio use while cooling down
    env.clock.advance(301)
    assert tick(d).activity == Activity.CONNECTED


def test_verification_failure_undoes_the_connection(env):
    env.wifi.internet = False
    d = env.make()
    r = tick(d)
    assert r.activity == Activity.ERROR and "internet is not reachable" in r.detail
    assert env.wifi.connected is None and env.wifi.disconnects == 1
    assert not env.phone.hotspot_on  # asked the phone to switch the useless hotspot off again
    assert env.notes.sent[-1][0] == "Hotspot has no internet" and env.notes.sent[-1][2] is True
    assert r.delay == 120.0


def test_phone_error_is_explained_and_cooled_down(env):
    env.phone.shizuku_ready = False
    d = env.make()
    r = tick(d)
    assert r.activity == Activity.ERROR and "Shizuku is not running" in r.detail
    assert env.notes.sent[-1][0] == "Could not use the phone's hotspot" and env.wifi.connected is None
    assert tick(d).activity == Activity.COOLDOWN  # no retry storm


def test_wifi_failure_is_reported(env):
    env.wifi.fail = WifiError("could not join the hotspot: nope")
    r = tick(env.make())
    assert r.activity == Activity.ERROR and "nope" in r.detail


def test_bluetooth_off_backs_off_and_reports(env, monkeypatch):
    async def boom(matcher, timeout):
        raise BluetoothError("Bluetooth is turned off", "run `bluetoothctl power on`")
    env.transport.scan = boom
    d = env.make()
    r = tick(d)
    assert r.activity == Activity.ERROR and "turned off" in r.detail and env.notes.sent == []


def test_manual_disconnect_pause_from_the_state_file(env):
    env.state.suppress(env.clock() + 600)
    r = tick(env.make())
    assert r.activity == Activity.COOLDOWN and env.scans == []
    env.clock.advance(601)
    assert tick(env.make()).activity == Activity.CONNECTED


def test_not_paired_is_an_error_not_a_crash(clock, tmp_path):
    from openhotspot.storage import FileKeyStore
    d = Daemon(None, FileKeyStore(tmp_path / "empty"), None, FakeNet(), Config(), clock=clock)
    r = asyncio.run(d.tick())
    assert r.activity == Activity.ERROR and "openhotspot pair" in r.detail


def test_run_loop_stops_promptly_and_reacts_to_controls(env):
    async def scenario():
        d = env.make("off", idle_interval_s=30.0)
        seen = []
        d.on_change(lambda a, detail: seen.append(a))
        stop = asyncio.Event()
        task = asyncio.create_task(d.run(stop))
        await asyncio.sleep(0.05)
        assert d.activity == Activity.OFF
        d.set_mode(Mode.AUTO)  # wakes the loop long before the 30 s idle interval
        await asyncio.sleep(0.1)
        assert Activity.CONNECTED in seen
        d.stop()
        await asyncio.wait_for(task, 1)

    asyncio.run(scenario())


def test_run_loop_survives_an_unexpected_exception(env):
    async def scenario():
        d = env.make()
        calls = []
        real = d.tick

        async def flaky():
            calls.append(1)
            if len(calls) == 1:
                raise RuntimeError("surprise")
            return await real()

        d.tick = flaky
        stop = asyncio.Event()
        task = asyncio.create_task(d.run(stop))
        await asyncio.sleep(0.05)
        assert d.activity == Activity.ERROR and "surprise" in d.detail
        stop.set()
        await asyncio.wait_for(task, 1)

    asyncio.run(scenario())


# ---- configuration -----------------------------------------------------------------------------

def test_config_defaults_and_overrides():
    assert parse_config("").daemon.mode == "auto"
    cfg = parse_config('''
phone = "ab12"
[daemon]
mode = "manual"
cooldown_s = 90
[ble]
adapter = "hci1"
passive_scan = true
[connect]
verify = false
[notifications]
enabled = false
''')
    assert (cfg.phone, cfg.daemon.mode, cfg.daemon.cooldown_s, cfg.ble.adapter, cfg.ble.passive_scan,
            cfg.connect.verify, cfg.notifications.enabled) == ("ab12", "manual", 90.0, "hci1", True, False, False)


@pytest.mark.parametrize("text, needle", [
    ("[daemon]\nmode = 'sometimes'", "mode must be one of auto, manual, off"),
    ("[daemon]\nscan_intervall_s = 5", "unknown key(s) in [daemon]: scan_intervall_s"),
    ("[daemon]\ncooldown_s = 'x'", "cooldown_s must be a float"),
    ("[ble]\npassive_scan = 1", "passive_scan must be a bool"),
    ("[daemon]\nscan_interval_s = 1", "at least 5"),
    ("[daemon]\nscan_duration_s = 90", "scan_duration_s"),
    ("[daemon]\ncooldown_s = -1", "must not be negative"),
    ("bogus = 1", "unknown top-level"),
    ("[wifi]\nx = 1", "unknown top-level"),
    ("mode = ", "invalid TOML"),
    ("phone = 3", "phone must be a string"),
])
def test_config_errors_are_specific(text, needle):
    from openhotspot.errors import ConfigError
    with pytest.raises(ConfigError, match=re.escape(needle)):
        parse_config(text, "test.toml")


def test_config_file_loading(tmp_path, monkeypatch):
    from openhotspot.config import load_config
    from openhotspot.errors import ConfigError
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    assert load_config().daemon.mode == "auto"  # the default path may be absent
    with pytest.raises(ConfigError, match="does not exist"):
        load_config(tmp_path / "nope.toml")
    f = tmp_path / "c.toml"
    f.write_text("[daemon]\nmode = 'off'\n")
    assert load_config(f).daemon.mode == "off"
