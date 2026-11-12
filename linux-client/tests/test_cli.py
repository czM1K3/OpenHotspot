import pytest

from openhotspot.cli import Deps, main
from openhotspot.constants import Security
from openhotspot.storage import FileKeyStore
from openhotspot.transport import InMemoryTransport
from openhotspot.wifi import WifiConnector
from openhotspot.errors import WifiError
from tests.fake_phone import FakePhone


class FakeWifi(WifiConnector):
    def __init__(self):
        self.connected, self.fail = None, False

    def connect(self, ssid, passphrase, security=Security.WPA2_PSK):
        if self.fail:
            raise WifiError("association failed")
        self.connected = (ssid, passphrase, security)

    def disconnect(self, ssid=None):
        was, self.connected = self.connected is not None, None
        return was


@pytest.fixture
def env(clock, phone, tmp_path):
    wifi = FakeWifi()
    deps = Deps(FileKeyStore(tmp_path / "cfg"), InMemoryTransport(phone), wifi, clock=clock,
                qr_decoder=lambda path: open(path).read().strip(), hostname="my-laptop")
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


def test_default_deps_use_ble_stub_and_say_so(tmp_path, capsys):
    # no injected deps: real BLE is not implemented until Batch 4, and the CLI must say so cleanly
    assert main(["--keystore", "file", "--config-dir", str(tmp_path), "pair", "openhotspot://pair/" + "A" * 87]) == 1
    # a syntactically valid QR gets as far as the transport
    from openhotspot.pairing import PairingQr
    from openhotspot.constants import SERVICE_UUID_BYTES
    qr = PairingQr(1, SERVICE_UUID_BYTES, bytes(range(1, 33)), bytes(16)).encode()
    capsys.readouterr()
    assert main(["--keystore", "file", "--config-dir", str(tmp_path), "pair", qr]) == 1
    assert "not implemented yet (Batch 4)" in capsys.readouterr().err
