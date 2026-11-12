#!/usr/bin/env python3
"""End-to-end demo: the real `openhotspot` CLI against an in-process FakePhone (no BLE, no Wi-Fi).

    python examples/demo.py

Runs `pair`, `connect`, `status`, `disconnect` and `unpair` through openhotspot.cli.main with
injected dependencies: an in-memory transport (MTU 23, so every frame is chunked), a throwaway
key store in a temp directory, and a Wi-Fi connector that only prints what nmcli would be asked to do.
"""
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))  # makes `tests.fake_phone` importable

from openhotspot.cli import Deps, main  # noqa: E402
from openhotspot.constants import Security  # noqa: E402
from openhotspot.storage import FileKeyStore  # noqa: E402
from openhotspot.transport import InMemoryTransport  # noqa: E402
from openhotspot.wifi import NmcliConnector, WifiConnector  # noqa: E402
from tests.fake_phone import FakePhone  # noqa: E402


class PrintingWifi(WifiConnector):
    """Shows the nmcli command line the real connector would run (passphrase masked)."""

    def __init__(self) -> None:
        self.commands: list[list[str]] = []
        self._real = NmcliConnector(self._record)

    def _record(self, argv):
        import subprocess
        shown = ["***" if a == self.secret else a for a in argv]
        print(f"   [wifi] would run: {' '.join(shown)}")
        return subprocess.CompletedProcess(argv, 0, "", "")

    def connect(self, ssid: bytes, passphrase: str, security: Security = Security.WPA2_PSK) -> None:
        self.secret = passphrase
        self._real.connect(ssid, passphrase, security)

    def disconnect(self, ssid=None) -> bool:
        print("   [wifi] would run: nmcli connection down id openhotspot-<ssid>")
        return True


def step(deps: Deps, *argv: str) -> None:
    print(f"\n$ openhotspot {' '.join(argv)}")
    code = main(list(argv), deps)
    print(f"   (exit {code})")
    if code != 0:
        raise SystemExit(f"demo step failed: {argv}")


def banner(text: str) -> None:
    print(f"\n=== {text} " + "=" * (60 - len(text)))


def demo() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        phone = FakePhone(time.time)
        deps = Deps(FileKeyStore(Path(tmp) / "openhotspot"), InMemoryTransport(phone, mtu=23), PrintingWifi(),
                    hostname="demo-laptop")

        banner("On the phone: open the pairing screen (QR code)")
        qr = phone.open_pairing()
        print(f"QR payload: {qr[:44]}...")
        step(deps, "pair", qr)

        banner("Pairing result")
        (rec,) = phone.clients.values()
        stored = deps.store.resolve()
        print(f"phone stored client '{rec.label}'; client stored phone {stored.phone_id}")
        print(f"LTKs match: {rec.ltk == stored.ltk}   key file mode: {oct(deps.store.path.stat().st_mode & 0o777)}")

        banner("Turn the hotspot on and join it")
        step(deps, "connect")
        print(f"phone hotspot on: {phone.hotspot_on}; requests sent so far: {deps.store.resolve().counter}")

        banner("Status")
        step(deps, "status")

        banner("Leave and turn the hotspot off")
        step(deps, "disconnect")
        print(f"phone hotspot on: {phone.hotspot_on}")

        banner("Forget the phone")
        step(deps, "unpair")
        print(f"phone still has clients: {bool(phone.clients)}; client still has keys: {bool(deps.store.list())}")

    print("\nDemo finished: pair, connect, status, disconnect and unpair all worked in-process.")


if __name__ == "__main__":
    demo()
