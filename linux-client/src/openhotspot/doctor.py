"""`openhotspot doctor`: check the Linux prerequisites and say what to do about each problem."""
from __future__ import annotations

import importlib.util
import re
import shutil
import subprocess
from collections.abc import Callable, Sequence
from dataclasses import dataclass

from .storage import KeyStore

OK, WARN, FAIL = "ok", "warn", "FAIL"

Runner = Callable[[Sequence[str]], "subprocess.CompletedProcess[str]"]

MIN_BLUEZ_BLEAK = (5, 55)  # bleak refuses older BlueZ
MIN_BLUEZ_MTU = (5, 62)  # GattCharacteristic1.MTU, needed to use more than the 23-byte default MTU


@dataclass
class Check:
    name: str
    status: str
    message: str


def _default_runner(argv: Sequence[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(list(argv), capture_output=True, text=True, timeout=6, check=False)


def _try(run: Runner, *argv: str) -> subprocess.CompletedProcess[str] | None:
    try:
        return run(argv)
    except (FileNotFoundError, subprocess.TimeoutExpired):
        return None


def check_bluez(run: Runner) -> list[Check]:
    res = _try(run, "bluetoothctl", "--version")
    out: list[Check] = []
    if res is None or res.returncode != 0:
        out.append(Check("BlueZ", WARN, "`bluetoothctl` not found, so the BlueZ version could not be checked "
                                        "(install the `bluez` / `bluez-utils` package)"))
    else:
        m = re.search(r"(\d+)\.(\d+)", res.stdout)
        ver = (int(m.group(1)), int(m.group(2))) if m else None
        if ver is None:
            out.append(Check("BlueZ", WARN, f"could not parse the version from '{res.stdout.strip()}'"))
        elif ver < MIN_BLUEZ_BLEAK:
            out.append(Check("BlueZ", FAIL, f"BlueZ {ver[0]}.{ver[1]} is too old; bleak needs 5.55 or newer"))
        elif ver < MIN_BLUEZ_MTU:
            out.append(Check("BlueZ", WARN, f"BlueZ {ver[0]}.{ver[1]} works, but cannot report the negotiated MTU: "
                                            "transfers use 20-byte chunks (slower, still correct). 5.62+ is better"))
        else:
            out.append(Check("BlueZ", OK, f"version {ver[0]}.{ver[1]}"))
    show = _try(run, "bluetoothctl", "show")
    if show is None:
        if res is not None:
            out.append(Check("Bluetooth adapter", FAIL, "bluetoothd did not answer; start it with "
                                                        "`sudo systemctl enable --now bluetooth`"))
    elif "no default controller" in (show.stdout + show.stderr).lower():
        out.append(Check("Bluetooth adapter", FAIL, "no Bluetooth adapter; check `rfkill list` and your driver"))
    elif re.search(r"Powered:\s*no", show.stdout):
        out.append(Check("Bluetooth adapter", FAIL, "the adapter is off; run `rfkill unblock bluetooth` and "
                                                    "`bluetoothctl power on`"))
    elif show.returncode == 0:
        out.append(Check("Bluetooth adapter", OK, "present and powered"))
    return out


def check_networkmanager(run: Runner) -> list[Check]:
    running = _try(run, "nmcli", "-t", "-g", "RUNNING", "general")
    if running is None:
        return [Check("NetworkManager", FAIL, "`nmcli` not found or not answering; install NetworkManager")]
    if running.stdout.strip() != "running":
        return [Check("NetworkManager", FAIL, "NetworkManager is not running; "
                                              "`sudo systemctl enable --now NetworkManager`")]
    out = [Check("NetworkManager", OK, "running")]
    radio = _try(run, "nmcli", "radio", "wifi")
    if radio is not None and radio.stdout.strip() == "disabled":
        out.append(Check("Wi-Fi radio", FAIL, "Wi-Fi is switched off; `nmcli radio wifi on` (and `rfkill unblock wifi`)"))
    devices = _try(run, "nmcli", "-t", "-f", "TYPE,STATE", "device")
    if devices is not None:
        wifi = [line for line in devices.stdout.splitlines() if line.startswith("wifi:")]
        if not wifi:
            out.append(Check("Wi-Fi device", FAIL, "NetworkManager sees no Wi-Fi adapter (`nmcli device`)"))
        elif all(line.split(":", 1)[1] in ("unavailable", "unmanaged") for line in wifi):
            out.append(Check("Wi-Fi device", FAIL, f"the Wi-Fi adapter is {wifi[0].split(':', 1)[1]}; "
                                                   "is Wi-Fi off, or is another tool (iwd, wpa_supplicant) managing it?"))
        else:
            out.append(Check("Wi-Fi device", OK, "available"))
    return out


def run_checks(store: KeyStore, run: Runner = _default_runner, which: Callable[[str], str | None] = shutil.which,
               find_spec: Callable[[str], object | None] = importlib.util.find_spec) -> list[Check]:
    checks = check_bluez(run) + check_networkmanager(run)
    ntp = _try(run, "timedatectl", "show", "-p", "NTPSynchronized", "--value")
    if ntp is not None and ntp.returncode == 0:
        if ntp.stdout.strip() == "yes":
            checks.append(Check("Clock", OK, "synchronised (the protocol needs the clocks within a minute)"))
        else:
            checks.append(Check("Clock", WARN, "not NTP-synchronised; if requests fail with STALE_TIMESTAMP run "
                                               "`timedatectl set-ntp true`"))
    try:
        phones = store.list()
        checks.append(Check("Pairing", OK if phones else WARN,
                            f"{len(phones)} paired phone(s)" if phones else "no paired phone; run `openhotspot pair <qr>`"))
    except Exception as exc:
        checks.append(Check("Pairing", FAIL, str(exc)))
    checks.append(Check("notify-send", OK if which("notify-send") else WARN,
                        "found" if which("notify-send") else "not installed: no desktop notifications (optional; `libnotify`)"))
    tray_ok = find_spec("pystray") is not None and find_spec("PIL") is not None
    checks.append(Check("Tray extra", OK if tray_ok else WARN,
                        "installed" if tray_ok else "not installed (optional): pip install 'openhotspot[tray]'"))
    return checks


def format_checks(checks: Sequence[Check]) -> str:
    return "\n".join(f"[{c.status:>4}] {c.name}: {c.message}" for c in checks)
