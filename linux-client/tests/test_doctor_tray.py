import subprocess

import pytest

from openhotspot.config import Config
from openhotspot.daemon import Activity, Daemon, Mode
from openhotspot.doctor import FAIL, OK, WARN, format_checks, run_checks
from openhotspot.storage import FileKeyStore
from openhotspot.tray import COLORS, TrayModel


def done(argv, rc=0, out="", err=""):
    return subprocess.CompletedProcess(argv, rc, out, err)


def scripted(answers):
    """answers: {substring of the command line: (rc, stdout)}; None raises FileNotFoundError, 'timeout' times out."""
    def run(argv):
        key = " ".join(argv)
        for k, v in answers.items():
            if k in key:
                if v is None:
                    raise FileNotFoundError(argv[0])
                if v == "timeout":
                    raise subprocess.TimeoutExpired(argv, 6)
                return done(argv, v[0], v[1])
        raise AssertionError(f"unscripted {argv}")
    return run


GOOD = {
    "bluetoothctl --version": (0, "bluetoothctl: 5.87\n"),
    "bluetoothctl show": (0, "Controller AA\n\tPowered: yes\n"),
    "general": (0, "running\n"),
    "radio wifi": (0, "enabled\n"),
    "device": (0, "ethernet:connected\nwifi:disconnected\n"),
    "timedatectl show": (0, "yes\n"),
}


def statuses(answers, tmp_path, which=lambda n: "/usr/bin/" + n, spec=lambda n: object()):
    return {c.name: c for c in run_checks(FileKeyStore(tmp_path), scripted(answers), which, spec)}


def test_everything_fine(tmp_path):
    checks = statuses(GOOD, tmp_path)
    assert checks["BlueZ"].status == OK and checks["Bluetooth adapter"].status == OK
    assert checks["NetworkManager"].status == OK and checks["Wi-Fi device"].status == OK
    assert checks["Clock"].status == OK
    assert checks["Pairing"].status == WARN  # nothing paired in an empty store
    assert checks["notify-send"].status == OK and checks["Tray extra"].status == OK


@pytest.mark.parametrize("version, status", [("5.54", FAIL), ("5.60", WARN), ("5.62", OK)])
def test_bluez_version_thresholds(tmp_path, version, status):
    checks = statuses({**GOOD, "bluetoothctl --version": (0, f"bluetoothctl: {version}\n")}, tmp_path)
    assert checks["BlueZ"].status == status


def test_bluetooth_problems_have_remedies(tmp_path):
    off = statuses({**GOOD, "bluetoothctl show": (0, "Controller AA\n\tPowered: no\n")}, tmp_path)
    assert off["Bluetooth adapter"].status == FAIL and "bluetoothctl power on" in off["Bluetooth adapter"].message
    none = statuses({**GOOD, "bluetoothctl show": (1, "No default controller available\n")}, tmp_path)
    assert "no Bluetooth adapter" in none["Bluetooth adapter"].message
    down = statuses({**GOOD, "bluetoothctl show": "timeout"}, tmp_path)
    assert "systemctl enable --now bluetooth" in down["Bluetooth adapter"].message
    missing = statuses({**GOOD, "bluetoothctl --version": None, "bluetoothctl show": None}, tmp_path)
    assert missing["BlueZ"].status == WARN and "Bluetooth adapter" not in missing


def test_networkmanager_problems_have_remedies(tmp_path):
    stopped = statuses({**GOOD, "general": (0, "not running\n")}, tmp_path)
    assert stopped["NetworkManager"].status == FAIL and "Wi-Fi device" not in stopped
    off = statuses({**GOOD, "radio wifi": (0, "disabled\n")}, tmp_path)
    assert off["Wi-Fi radio"].status == FAIL
    nodev = statuses({**GOOD, "device": (0, "ethernet:connected\n")}, tmp_path)
    assert nodev["Wi-Fi device"].status == FAIL
    unmanaged = statuses({**GOOD, "device": (0, "wifi:unmanaged\n")}, tmp_path)
    assert "iwd" in unmanaged["Wi-Fi device"].message
    gone = statuses({**GOOD, "general": None}, tmp_path)
    assert gone["NetworkManager"].status == FAIL


def test_clock_and_optional_tools(tmp_path):
    c = statuses({**GOOD, "timedatectl show": (0, "no\n")}, tmp_path, which=lambda n: None, spec=lambda n: None)
    assert c["Clock"].status == WARN and "set-ntp" in c["Clock"].message
    assert c["notify-send"].status == WARN and c["Tray extra"].status == WARN
    assert "[FAIL]" not in format_checks(list(c.values()))


# ---- tray model (no pystray needed) ----------------------------------------------------------------

@pytest.fixture
def daemon(tmp_path):
    return Daemon(None, FileKeyStore(tmp_path), None, None, Config())


def test_tray_entries_reflect_the_daemon(daemon):
    quits = []
    model = TrayModel(daemon, lambda: quits.append(1))
    daemon.activity, daemon.detail = Activity.PHONE_NEARBY, ""
    entries = model.entries()
    assert [e.label for e in entries][:2] == ["OpenHotspot, phone nearby", "Connect now"]
    assert not entries[0].enabled and entries[1].enabled
    radios = {e.label: e.checked for e in entries if e.checked is not None}
    assert radios == {"Auto: connect by itself": True, "Manual: only notify": False, "Off": False}
    next(e for e in entries if e.label == "Off").action()
    assert daemon.mode == Mode.OFF
    next(e for e in model.entries() if e.label == "Connect now").action()
    assert daemon._connect_now is True
    model.entries()[-1].action()
    assert quits == [1]


def test_tray_connect_disabled_while_connecting_and_every_activity_has_a_colour(daemon):
    model = TrayModel(daemon, lambda: None)
    daemon.activity = Activity.CONNECTING
    assert not model.entries()[1].enabled
    assert set(COLORS) == set(Activity)
    daemon.activity, daemon.detail = Activity.ERROR, "x" * 500
    assert len(model.title()) <= 120
