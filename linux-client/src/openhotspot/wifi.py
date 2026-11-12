"""Wi-Fi connector interface and the NetworkManager (`nmcli`) implementation."""
from __future__ import annotations

import subprocess
from abc import ABC, abstractmethod
from collections.abc import Callable, Sequence

from .constants import Security
from .errors import WifiError

PROFILE_PREFIX = "openhotspot-"

Runner = Callable[[Sequence[str]], "subprocess.CompletedProcess[str]"]


def _default_runner(argv: Sequence[str]) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(list(argv), capture_output=True, text=True, timeout=60, check=False)
    except FileNotFoundError as exc:
        raise WifiError("nmcli not found; is NetworkManager installed?") from exc
    except subprocess.TimeoutExpired as exc:
        raise WifiError("nmcli timed out") from exc


class WifiConnector(ABC):
    @abstractmethod
    def connect(self, ssid: bytes, passphrase: str, security: Security = Security.WPA2_PSK) -> None: ...

    @abstractmethod
    def disconnect(self, ssid: bytes | None = None) -> bool:
        """Drop the connection made by ``connect``; returns True if something was disconnected."""


class NmcliConnector(WifiConnector):
    """Uses `nmcli device wifi connect`. NetworkManager picks WPA2/WPA3 from the scan result.

    Known limitation: nmcli takes the passphrase as an argument, so it is briefly visible in
    the process list to other local users. See docs/DECISIONS.md.
    """

    def __init__(self, runner: Runner = _default_runner) -> None:
        self._run = runner

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

    def connect(self, ssid: bytes, passphrase: str, security: Security = Security.WPA2_PSK) -> None:
        text = self._text_ssid(ssid)
        argv = ["nmcli", "--wait", "30", "device", "wifi", "connect", text,
                "password", passphrase, "name", self.profile_name(text)]
        result = self._run(argv)
        if result.returncode != 0:
            msg = (result.stderr or result.stdout or "").strip().replace(passphrase, "***")
            raise WifiError(f"nmcli connect failed ({result.returncode}): {msg}")

    def disconnect(self, ssid: bytes | None = None) -> bool:
        if ssid is not None:
            names = [self.profile_name(self._text_ssid(ssid))]
        else:
            result = self._run(["nmcli", "-t", "-f", "NAME", "connection", "show", "--active"])
            if result.returncode != 0:
                raise WifiError(f"nmcli failed ({result.returncode}): {(result.stderr or '').strip()}")
            names = [n for n in result.stdout.splitlines() if n.startswith(PROFILE_PREFIX)]
        done = False
        for name in names:
            result = self._run(["nmcli", "connection", "down", "id", name])
            if result.returncode == 0:
                done = True
            elif "not an active connection" not in (result.stderr or ""):
                raise WifiError(f"nmcli down failed ({result.returncode}): {(result.stderr or '').strip()}")
        return done
