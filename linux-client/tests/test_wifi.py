"""NmcliConnector, NmConnectivity and NotifySend against a scripted fake `nmcli`."""
import subprocess

import pytest

from openhotspot.connectivity import Connectivity, NmConnectivity
from openhotspot.constants import Band, Security
from openhotspot.errors import WifiError
from openhotspot.notify import NotifySend
from openhotspot.wifi import NmcliConnector


def done(argv, rc=0, out="", err=""):
    return subprocess.CompletedProcess(argv, rc, out, err)


class FakeNm:
    """Just enough of nmcli: saved profiles, active ones, a list of visible/hidden SSIDs."""

    def __init__(self, visible=("net",), hidden=(), profiles=(), active=(), connect_error=None):
        self.visible, self.hidden, self.connect_error = set(visible), set(hidden), connect_error
        self.profiles = {f"uuid-{i}": n for i, n in enumerate(profiles)}
        self.active = set(active)
        self.calls: list[list[str]] = []

    def __call__(self, argv):
        argv = list(argv)
        self.calls.append(argv)
        rest = argv[1:]
        if rest[:2] == ["-t", "-f"] and rest[2] == "UUID,NAME":
            return done(argv, out="".join(f"{u}:{n.replace(':', chr(92) + ':')}\n" for u, n in self.profiles.items()))
        if rest[:2] == ["-t", "-f"] and rest[2] == "NAME":
            return done(argv, out="".join(f"{n}\n" for n in self.active))
        if rest[:2] == ["connection", "delete"]:
            self.profiles.pop(rest[3], None)
            return done(argv)
        if rest[:2] == ["connection", "down"]:
            if rest[3] not in self.active:
                return done(argv, 10, err=f"Error: '{rest[3]}' is not an active connection.")
            self.active.discard(rest[3])
            return done(argv)
        if rest[:3] == ["device", "wifi", "rescan"]:
            return done(argv)
        if "connect" in rest:
            if self.connect_error:
                return done(argv, *self.connect_error)
            ssid = rest[rest.index("connect") + 1]
            hidden = "hidden" in rest
            if ssid in self.visible or (hidden and ssid in self.hidden):
                name = rest[rest.index("name") + 1]
                self.profiles[f"uuid-new-{len(self.profiles)}"] = name
                self.active.add(name)
                return done(argv, out="Device 'wlan0' successfully activated")
            return done(argv, 10, err=f"Error: No network with SSID '{ssid}' found.")
        raise AssertionError(f"unexpected nmcli call {argv}")


def connector(nm):
    return NmcliConnector(nm, sleep=lambda s: None)


def test_connect_command_and_stale_profile_is_removed_first():
    nm = FakeNm(visible=["My Phone"], profiles=["openhotspot-My Phone", "Home"])
    connector(nm).connect(b"My Phone", "s3cretpass", Security.WPA2_PSK)
    assert ["nmcli", "connection", "delete", "uuid", "uuid-0"] in nm.calls
    assert nm.calls[-1] == ["nmcli", "--wait", "30", "device", "wifi", "connect", "My Phone",
                            "password", "s3cretpass", "name", "openhotspot-My Phone"]
    assert "Home" in nm.profiles.values()  # other people's profiles are never touched


def test_hidden_ssid_is_found_by_the_last_resort_retry():
    nm = FakeNm(visible=[], hidden=["secret"])
    connector(nm).connect(b"secret", "s3cretpass")
    assert nm.calls[-1][-2:] == ["hidden", "yes"]
    assert ["nmcli", "device", "wifi", "rescan"] in nm.calls  # it rescanned before giving up on a visible one


def test_not_visible_error_mentions_band_and_hidden():
    nm = FakeNm(visible=[])
    with pytest.raises(WifiError) as e:
        connector(nm).connect(b"net", "s3cretpass", Security.WPA2_PSK, Band.GHZ_5)
    msg = str(e.value)
    assert "not visible" in msg and "5 GHz" in msg and "2.4 GHz" in msg and "hidden" in msg and "s3cretpass" not in msg


@pytest.mark.parametrize("rc, err, needle", [
    (4, "Error: Connection activation failed: (7) Secrets were required, but not provided.", "password was rejected"),
    (1, "Error: Wi-Fi is disabled.", "nmcli radio wifi on"),
    (8, "Error: NetworkManager is not running.", "systemctl enable --now NetworkManager"),
    (1, "Error: Not authorized to control networking.", "polkit"),
    (10, "Error: No Wi-Fi device found.", "no Wi-Fi adapter"),
    (3, "Error: Timeout expired (30 seconds)", "timed out"),
    (4, "Error: Connection activation failed: IP configuration could not be reserved (no available address)", "DHCP"),
])
def test_nm_errors_get_clear_hints(rc, err, needle):
    with pytest.raises(WifiError, match=needle):
        connector(FakeNm(visible=["net"], connect_error=(rc, "", err))).connect(b"net", "s3cretpass")


def test_failure_never_leaks_the_passphrase():
    nm = FakeNm(connect_error=(4, "", "Error: password s3cretpass rejected"))
    with pytest.raises(WifiError) as e:
        connector(nm).connect(b"net", "s3cretpass")
    assert "s3cretpass" not in str(e.value) and "***" in str(e.value)


@pytest.mark.parametrize("ssid", [b"\xff\xfe", b"", b"-h"])
def test_rejects_unusable_ssids(ssid):
    nm = FakeNm()
    with pytest.raises(WifiError):
        connector(nm).connect(ssid, "s3cretpass")
    assert nm.calls == []


def test_disconnect_leaves_and_removes_only_our_profiles():
    nm = FakeNm(profiles=["Wired", "openhotspot-My Phone", "openhotspot-old", "Home"],
                active=["Wired", "openhotspot-My Phone"])
    assert connector(nm).disconnect() is True
    assert sorted(nm.profiles.values()) == ["Home", "Wired"]
    assert nm.active == {"Wired"}


def test_disconnect_removes_an_inactive_leftover_profile():
    nm = FakeNm(profiles=["openhotspot-x"])
    assert connector(nm).disconnect(b"x") is True and not nm.profiles


def test_disconnect_when_there_is_nothing_to_do():
    nm = FakeNm(profiles=["Home"], active=["Home"])
    assert connector(nm).disconnect() is False
    assert not any(c[1:3] in (["connection", "down"], ["connection", "delete"]) for c in nm.calls)


def test_disconnect_handles_colons_in_ssids():
    nm = FakeNm(profiles=["openhotspot-a:b"])
    assert connector(nm).disconnect() is True and not nm.profiles


def test_disconnect_reports_a_failing_nmcli():
    def broken(argv):
        return done(argv, 8, err="Error: NetworkManager is not running.")
    with pytest.raises(WifiError, match="NetworkManager"):
        NmcliConnector(broken).disconnect()


def test_missing_binary(monkeypatch):
    def boom(*a, **k):
        raise FileNotFoundError
    monkeypatch.setattr(subprocess, "run", boom)
    with pytest.raises(WifiError, match="nmcli not found"):
        NmcliConnector().connect(b"net", "s3cretpass")


# ---- connectivity -------------------------------------------------------------------------------

def nm_answers(state="connected", conn="full"):
    def run(argv):
        if "STATE" in argv:
            return done(argv, out=state + "\n")
        if "CONNECTIVITY" in argv:
            return done(argv, out=conn + "\n")
        raise AssertionError(argv)
    return run


@pytest.mark.parametrize("state, expected", [
    ("connected", Connectivity.FULL), ("connected (site only)", Connectivity.LIMITED),
    ("disconnected", Connectivity.NONE), ("asleep", Connectivity.NONE), ("surprise", Connectivity.UNKNOWN)])
def test_polling_uses_the_general_state_only(state, expected):
    calls = []
    def run(argv):
        calls.append(argv)
        return nm_answers(state)(argv)
    assert NmConnectivity(run).check() == expected
    assert len(calls) == 1  # no active probing while polling


@pytest.mark.parametrize("conn, expected", [("full", Connectivity.FULL), ("limited", Connectivity.LIMITED),
                                            ("portal", Connectivity.LIMITED), ("none", Connectivity.NONE)])
def test_probe_asks_networkmanager(conn, expected):
    assert NmConnectivity(nm_answers("connected", conn)).check(probe=True) == expected


def test_probe_falls_back_to_state_when_nm_checking_is_off():
    assert NmConnectivity(nm_answers("connected", "unknown")).check(probe=True) == Connectivity.FULL


def test_probe_url_must_also_answer():
    class Resp:
        status = 204
        def __enter__(self): return self
        def __exit__(self, *a): return False
    ok = NmConnectivity(nm_answers(), probe_url="http://x/", opener=lambda *a, **k: Resp())
    assert ok.check(probe=True) == Connectivity.FULL

    def refuse(*a, **k):
        raise OSError("unreachable")
    assert NmConnectivity(nm_answers(), probe_url="http://x/", opener=refuse).check(probe=True) == Connectivity.LIMITED
    assert NmConnectivity(nm_answers(), probe_url="http://x/", opener=refuse).check() == Connectivity.FULL  # polling never probes


def test_missing_nmcli_is_unknown_not_a_crash():
    def gone(argv):
        raise FileNotFoundError
    assert NmConnectivity(gone).check() == Connectivity.UNKNOWN


# ---- notifications ------------------------------------------------------------------------------

def test_notify_send_builds_a_safe_command():
    calls = []
    n = NotifySend(runner=calls.append, which=lambda name: "/usr/bin/notify-send")
    n.notify("-rf title", "body", urgent=True)
    assert calls == [["notify-send", "--app-name=OpenHotspot", "--urgency=critical",
                      "--icon=network-wireless", "--", "-rf title", "body"]]


def test_notify_send_is_silent_when_missing_or_failing():
    NotifySend(runner=lambda a: 1 / 0, which=lambda n: None).notify("x")  # not installed: no call at all
    def fail(argv):
        raise subprocess.TimeoutExpired(argv, 5)
    NotifySend(runner=fail, which=lambda n: "/x").notify("x")
