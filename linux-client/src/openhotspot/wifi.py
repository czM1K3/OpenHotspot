"""Wi-Fi connector interface and the NetworkManager (`nmcli`) implementation."""
from __future__ import annotations

import logging
import re
import subprocess
import time
from abc import ABC, abstractmethod
from collections.abc import Callable, Sequence

from .constants import Band, Security
from .errors import WifiError

log = logging.getLogger(__name__)

PROFILE_PREFIX = "openhotspot-"

Runner = Callable[[Sequence[str]], "subprocess.CompletedProcess[str]"]


def _default_runner(argv: Sequence[str]) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(list(argv), capture_output=True, text=True, timeout=60, check=False)
    except FileNotFoundError as exc:
        raise WifiError("nmcli not found; install NetworkManager (the `nmcli` tool)") from exc
    except subprocess.TimeoutExpired as exc:
        raise WifiError("nmcli timed out; is NetworkManager responding? (`systemctl status NetworkManager`)") from exc


class WifiConnector(ABC):
    @abstractmethod
    def connect(self, ssid: bytes, passphrase: str, security: Security = Security.WPA2_PSK,
                band: Band = Band.UNKNOWN) -> None: ...

    @abstractmethod
    def disconnect(self, ssid: bytes | None = None) -> bool:
        """Leave the network made by ``connect`` and remove its profile; True if anything was removed."""


# (substring of nmcli's message, lower case) -> hint. The first match wins.
_NM_HINTS: list[tuple[str, str]] = [
    ("networkmanager is not running", "start it with `sudo systemctl enable --now NetworkManager`"),
    ("not authorized", "NetworkManager's polkit policy refused this; run from a local desktop session, "
                       "or allow your user (see docs/SETUP.md, Linux, troubleshooting)"),
    ("insufficient privileges", "NetworkManager's polkit policy refused this; run from a local desktop session, "
                                "or allow your user (see docs/SETUP.md, Linux, troubleshooting)"),
    ("wi-fi is disabled", "turn it on with `nmcli radio wifi on` (and `rfkill unblock wifi`)"),
    ("wifi is disabled", "turn it on with `nmcli radio wifi on` (and `rfkill unblock wifi`)"),
    ("wireless is disabled", "turn it on with `nmcli radio wifi on` (and `rfkill unblock wifi`)"),
    ("no wi-fi device found", "this machine has no Wi-Fi adapter NetworkManager can use (`nmcli device`)"),
    ("secrets were required", "the Wi-Fi password was rejected: the phone's hotspot may use WPA3 only and this adapter "
                              "or driver may not support it, or the hotspot password just changed (retry)"),
    ("no suitable device found", "no Wi-Fi device is available; is Wi-Fi switched off or the adapter busy?"),
    ("ip configuration could not be reserved", "the hotspot did not hand out an address (DHCP); retry, and check "
                                               "that the phone's hotspot is really on"),
    ("timeout expired", "the association timed out; move closer, or check the band (see docs/SETUP.md)"),
]


class NmcliConnector(WifiConnector):
    """Uses `nmcli device wifi connect`. NetworkManager picks WPA2/WPA3 from the scan result.

    Known limitation: nmcli takes the passphrase as an argument, so it is briefly visible in
    the process list to other local users. See docs/DECISIONS.md (D16).

    The protocol carries no 'hidden SSID' flag, so if the network is not in the scan results
    the connector rescans and finally tries ``hidden yes``.
    """

    def __init__(self, runner: Runner = _default_runner, sleep: Callable[[float], None] = time.sleep) -> None:
        self._run = runner
        self._sleep = sleep

    @staticmethod
    def profile_name(ssid: str) -> str:
        return PROFILE_PREFIX + ssid

    @staticmethod
    def _text_ssid(ssid: bytes) -> str:
        try:
            text = ssid.decode()
        except UnicodeDecodeError as exc:
            raise WifiError("SSID is not valid UTF-8; nmcli cannot take it as an argument") from exc
        if not text or text.startswith("-"):
            raise WifiError("refusing an empty SSID or one starting with '-'")
        return text

    # ---- connect ------------------------------------------------------------------------------

    def _error(self, rc: int, raw: str, passphrase: str, band: Band) -> WifiError:
        msg = raw.strip().replace(passphrase, "***")
        low = msg.lower()
        if rc == 8 and "running" not in low:
            low += " networkmanager is not running"
        hint = next((h for needle, h in _NM_HINTS if needle in low), "")
        if not hint and "no network with ssid" in low:
            hint = self._not_visible_hint(band)
        text = f"could not join the hotspot (nmcli exit {rc}): {msg}"
        return WifiError(f"{text}. {hint[:1].upper()}{hint[1:]}" if hint else text)

    @staticmethod
    def _not_visible_hint(band: Band) -> str:
        parts = ["the hotspot is not visible to this laptop: is it in range, and is the phone's hotspot really on?"]
        if band in (Band.GHZ_5, Band.GHZ_6):
            parts.append(f"the phone uses {band.name.replace('GHZ_', '').replace('_', '.')} GHz, which this adapter "
                         "or its regulatory domain may not support (`iw reg get`); set the phone's hotspot band "
                         "to 2.4 GHz")
        parts.append("hidden SSIDs were tried as well")
        return "; ".join(parts)

    def _delete_profiles(self, only: str | None = None) -> list[str]:
        """Remove our saved profiles (all, or the one named ``only``); returns the deleted names."""
        result = self._run(["nmcli", "-t", "-f", "UUID,NAME", "connection", "show"])
        if result.returncode != 0:
            raise WifiError(self._plain_error(result, "nmcli could not list connections"))
        deleted = []
        for uuid, name in (_split_terse(line) for line in result.stdout.splitlines() if line):
            if not name.startswith(PROFILE_PREFIX) or (only is not None and name != only):
                continue
            res = self._run(["nmcli", "connection", "delete", "uuid", uuid])
            if res.returncode != 0:
                raise WifiError(self._plain_error(res, f"could not remove the saved connection '{name}'"))
            deleted.append(name)
        return deleted

    @staticmethod
    def _plain_error(result: subprocess.CompletedProcess[str], prefix: str) -> str:
        text = (result.stderr or result.stdout or "").strip()
        low = text.lower()
        hint = next((h for needle, h in _NM_HINTS if needle in low), "")
        if result.returncode == 8:
            hint = hint or "start it with `sudo systemctl enable --now NetworkManager`"
        return f"{prefix} (nmcli exit {result.returncode}): {text}" + (f". {hint[:1].upper()}{hint[1:]}" if hint else "")

    def connect(self, ssid: bytes, passphrase: str, security: Security = Security.WPA2_PSK,
                band: Band = Band.UNKNOWN) -> None:
        text = self._text_ssid(ssid)
        name = self.profile_name(text)
        # A profile left from an earlier session may hold an old passphrase: always start clean.
        self._delete_profiles(only=name)

        def attempt(hidden: bool) -> subprocess.CompletedProcess[str]:
            argv = ["nmcli", "--wait", "30", "device", "wifi", "connect", text, "password", passphrase, "name", name]
            if hidden:
                argv += ["hidden", "yes"]
            return self._run(argv)

        result = attempt(False)
        if result.returncode != 0 and "no network with ssid" in (result.stderr or result.stdout or "").lower():
            log.info("SSID not in the scan results; rescanning")
            self._run(["nmcli", "device", "wifi", "rescan"])  # may fail with 'scanning not allowed'; harmless
            self._sleep(3)
            result = attempt(False)
            if result.returncode != 0 and "no network with ssid" in (result.stderr or result.stdout or "").lower():
                log.info("still not visible; trying it as a hidden network")
                result = attempt(True)
        if result.returncode != 0:
            raise self._error(result.returncode, result.stderr or result.stdout or "", passphrase, band)

    # ---- disconnect ---------------------------------------------------------------------------

    def disconnect(self, ssid: bytes | None = None) -> bool:
        only = self.profile_name(self._text_ssid(ssid)) if ssid is not None else None
        active = self._run(["nmcli", "-t", "-f", "NAME", "connection", "show", "--active"])
        if active.returncode != 0:
            raise WifiError(self._plain_error(active, "nmcli could not list active connections"))
        was_active = False
        for line in active.stdout.splitlines():
            name = _unescape(line)
            if not name.startswith(PROFILE_PREFIX) or (only is not None and name != only):
                continue
            res = self._run(["nmcli", "connection", "down", "id", name])
            if res.returncode == 0:
                was_active = True
            elif "not an active connection" not in (res.stderr or ""):
                raise WifiError(self._plain_error(res, "could not leave the hotspot network"))
        removed = self._delete_profiles(only=only)
        return was_active or bool(removed)


def _unescape(text: str) -> str:
    """nmcli -t escapes ':' and '\\' with a backslash."""
    return re.sub(r"\\(.)", r"\1", text)


def _split_terse(line: str) -> tuple[str, str]:
    uuid, _, name = line.partition(":")  # a UUID never contains ':'
    return uuid, _unescape(name)
