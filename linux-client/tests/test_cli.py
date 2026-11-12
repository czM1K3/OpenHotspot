import time

import pytest

from openhotspot.cli import Deps, main
from openhotspot.config import Config, ConnectConfig
from openhotspot.connectivity import Connectivity, ConnectivityChecker
from openhotspot.constants import Security
from openhotspot.doctor import Check
from openhotspot.errors import WifiError
from openhotspot.state import StateFile
from openhotspot.storage import FileKeyStore
from openhotspot.transport import InMemoryTransport
from openhotspot.wifi import WifiConnector
from tests.fake_phone import FakePhone


class FakeWifi(WifiConnector):
    def __init__(self):
        self.connected, self.fail = None, False

    def connect(self, ssid, passphrase, security=Security.WPA2_PSK, band=None):
        if self.fail:
            raise WifiError("association failed")
        self.connected = (ssid, passphrase, security)

    def disconnect(self, ssid=None):
        was, self.connected = self.connected is not None, None
        return was


class FakeNet(ConnectivityChecker):
    """Online once the fake Wi-Fi has 'connected' (set by the tests through FakeWifi)."""

    def __init__(self):
        self.state = Connectivity.NONE

    def check(self, probe=False):
        return self.state


@pytest.fixture
def env(clock, phone, tmp_path):
    wifi, net = FakeWifi(), FakeNet()
    real_connect = wifi.connect

    def connect(ssid, passphrase, security=Security.WPA2_PSK, band=None):
        real_connect(ssid, passphrase, security)
        net.state = Connectivity.FULL

    wifi.connect = connect
    deps = Deps(FileKeyStore(tmp_path / "cfg"), InMemoryTransport(phone), wifi, clock=clock,
                qr_decoder=lambda path: open(path).read().strip(), hostname="my-laptop", connectivity=net)
    deps.net = net
    return deps, phone, wifi


def cli(deps, *argv):
    return main(list(argv), deps)


def test_pair_connect_status_disconnect(env, capsys):
    deps, phone, wifi = env
    assert cli(deps, "pair", phone.open_pairing()) == 0
    assert "Paired with phone" in capsys.readouterr().out
    assert list(phone.clients.values())[0].label == "my-laptop"

    assert cli(deps, "connect") == 0
    out = capsys.readouterr().out
    assert "Connected." in out and "Fake Phone" in out
    assert "correct horse battery" not in out  # the passphrase is never printed
    assert wifi.connected == (b"Fake Phone", "correct horse battery", Security.WPA2_WPA3)

    assert cli(deps, "status") == 0
    out = capsys.readouterr().out
    assert "hotspot: ON" in out and "hotspot_active=True" in out and "correct horse" not in out
    assert cli(deps, "status", "--show-password") == 0
    assert "passphrase: correct horse battery" in capsys.readouterr().out

    assert cli(deps, "disconnect") == 0
    assert wifi.connected is None and not phone.hotspot_on
    assert "turn the hotspot off" in capsys.readouterr().out


def test_disconnect_keep_hotspot(env):
    deps, phone, wifi = env
    cli(deps, "pair", phone.open_pairing()); cli(deps, "connect")
    assert cli(deps, "disconnect", "--keep-hotspot") == 0
    assert wifi.connected is None and phone.hotspot_on


def test_pair_from_image_file(env, tmp_path):
    deps, phone, _ = env
    img = tmp_path / "qr.png"
    img.write_text(phone.open_pairing())  # the injected decoder just reads the file back
    assert cli(deps, "pair", str(img), "--label", "Named") == 0
    assert list(phone.clients.values())[0].label == "Named"


def test_pair_rejects_garbage(env, capsys):
    deps, *_ = env
    assert cli(deps, "pair", "not-a-qr") == 1
    assert "neither" in capsys.readouterr().err
    assert cli(deps, "pair", "openhotspot://pair/AAAA") == 1


def test_connect_without_pairing_exits_3(env, capsys):
    deps, *_ = env
    assert cli(deps, "connect") == 3
    assert "openhotspot pair" in capsys.readouterr().err


def test_connect_reports_phone_errors(env, capsys):
    deps, phone, wifi = env
    cli(deps, "pair", phone.open_pairing())
    phone.shizuku_ready = False
    assert cli(deps, "connect") == 1
    assert "SHIZUKU_UNAVAILABLE" in capsys.readouterr().err and wifi.connected is None


def test_connect_reports_wifi_failure(env, capsys):
    deps, phone, wifi = env
    cli(deps, "pair", phone.open_pairing())
    wifi.fail = True
    assert cli(deps, "connect") == 1
    assert "association failed" in capsys.readouterr().err


def test_phone_not_nearby(env, capsys):
    deps, phone, _ = env
    cli(deps, "pair", phone.open_pairing())
    phone.revoke(phone.client_ids()[0])
    assert cli(deps, "connect") == 1
    assert "not found nearby" in capsys.readouterr().err


def test_unpair(env, capsys):
    deps, phone, _ = env
    cli(deps, "pair", phone.open_pairing())
    assert cli(deps, "unpair") == 0
    assert phone.clients == {} and deps.store.list() == []
    assert cli(deps, "connect") == 3


def test_several_phones_need_an_explicit_choice(env, clock, capsys):
    deps, phone, _ = env
    cli(deps, "pair", phone.open_pairing())
    other = FakePhone(clock, ssid=b"Other")
    deps2 = Deps(deps.store, InMemoryTransport(other), deps.wifi, clock=clock)
    cli(deps2, "pair", other.open_pairing())
    capsys.readouterr()
    assert cli(deps, "connect") == 3
    assert "--phone" in capsys.readouterr().err
    pid = next(p.phone_id for p in deps.store.list() if p.phone_id != deps.store.list()[0].phone_id)
    assert cli(deps2, "connect", "--phone", pid) == 0


# ---- connect verification, disconnect pause, daemon, doctor, config ------------------------------

def test_connect_fails_loudly_when_there_is_no_internet(env, capsys):
    deps, phone, wifi = env
    cli(deps, "pair", phone.open_pairing())
    wifi.connect = lambda ssid, pw, security=None, band=None: setattr(wifi, "connected", ssid)  # joins, but net stays down
    deps.config = Config(connect=ConnectConfig(verify_timeout_s=0.05))
    capsys.readouterr()
    assert cli(deps, "connect") == 1
    err = capsys.readouterr().err
    assert "internet is not reachable" in err and "Fake Phone" in err
    assert wifi.connected == b"Fake Phone"  # left in place for the user to inspect
    assert cli(deps, "connect", "--no-verify") == 0


def test_status_shows_connectivity(env, capsys):
    deps, phone, _ = env
    cli(deps, "pair", phone.open_pairing()); capsys.readouterr()
    assert cli(deps, "status") == 0
    assert "connectivity: none" in capsys.readouterr().out


def test_disconnect_pauses_the_daemon(env, tmp_path, clock):
    deps, phone, wifi = env
    deps.state = StateFile(tmp_path / "state.json")
    cli(deps, "pair", phone.open_pairing()); cli(deps, "connect")
    assert cli(deps, "disconnect") == 0
    assert deps.state.suppress_until() == clock() + 3600


def test_daemon_once_connects_in_auto_mode(env, capsys):
    deps, phone, wifi = env
    cli(deps, "pair", phone.open_pairing()); capsys.readouterr()
    assert cli(deps, "daemon", "--once") == 0
    assert capsys.readouterr().out.strip().startswith("connected") and wifi.connected[0] == b"Fake Phone"


def test_daemon_mode_override_and_unpaired(env, capsys):
    deps, phone, wifi = env
    assert cli(deps, "daemon", "--once", "--mode", "off") == 0  # not paired, but off: nothing to say
    assert capsys.readouterr().out.strip() == "off"
    assert cli(deps, "daemon", "--once") == 1  # auto and not paired: an error, reported, not a traceback
    assert "openhotspot pair" in capsys.readouterr().out


def test_daemon_with_tray_runs_the_daemon_in_a_thread(env):
    deps, phone, wifi = env
    cli(deps, "pair", phone.open_pairing())
    seen = {}

    def fake_tray(daemon):
        for _ in range(100):  # the worker thread connects within a moment
            if wifi.connected:
                break
            time.sleep(0.02)
        seen["connected"] = wifi.connected

    deps.tray = fake_tray
    assert cli(deps, "daemon", "--tray") == 0
    assert seen["connected"][0] == b"Fake Phone"


def test_doctor_exit_code(env, capsys):
    deps, *_ = env
    deps.checks = lambda store: [Check("A", "ok", "fine"), Check("B", "warn", "meh")]
    assert cli(deps, "doctor") == 0
    deps.checks = lambda store: [Check("A", "FAIL", "broken")]
    assert cli(deps, "doctor") == 1
    assert "[FAIL] A: broken" in capsys.readouterr().out


def test_bad_config_is_a_usage_error(tmp_path, capsys):
    cfg = tmp_path / "c.toml"
    cfg.write_text("[daemon]\nmode = 'banana'\n")
    assert main(["--config", str(cfg), "--keystore", "file", "--config-dir", str(tmp_path), "status"]) == 2
    assert "mode must be one of" in capsys.readouterr().err


def test_config_selects_the_phone(env):
    deps, phone, _ = env
    cli(deps, "pair", phone.open_pairing())
    deps.config = Config(phone="nonexistent")
    assert cli(deps, "status") == 3


def test_bluetooth_problems_are_reported_cleanly(env, capsys):
    from openhotspot.errors import BluetoothError

    async def off(matcher, timeout):
        raise BluetoothError("Bluetooth is turned off", "run `bluetoothctl power on`")

    deps, phone, _ = env
    cli(deps, "pair", phone.open_pairing())
    deps.transport.scan = off
    capsys.readouterr()
    assert cli(deps, "connect") == 1
    assert "turned off. Run `bluetoothctl power on`" in capsys.readouterr().err
