"""Is the laptop online? Answered by NetworkManager, optionally confirmed by an HTTP probe."""
from __future__ import annotations

import subprocess
import urllib.error
import urllib.request
from abc import ABC, abstractmethod
from collections.abc import Callable, Sequence
from enum import Enum


class Connectivity(Enum):
    NONE = "none"
    LIMITED = "limited"  # a link without a route to the internet, or a captive portal
    FULL = "full"
    UNKNOWN = "unknown"


class ConnectivityChecker(ABC):
    @abstractmethod
    def check(self, probe: bool = False) -> Connectivity:
        """``probe=False``: cheap, local state only (used by the daemon's polling).
        ``probe=True``: really test reachability (used to verify a fresh connection)."""

    def online(self, probe: bool = False) -> bool:
        return self.check(probe) == Connectivity.FULL


Runner = Callable[[Sequence[str]], "subprocess.CompletedProcess[str]"]


def _run(argv: Sequence[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(list(argv), capture_output=True, text=True, timeout=30, check=False)


_STATE_MAP = {
    "connected": Connectivity.FULL,
    "connected (site only)": Connectivity.LIMITED,
    "connected (local only)": Connectivity.LIMITED,
    "connecting": Connectivity.NONE,
    "disconnecting": Connectivity.NONE,
    "disconnected": Connectivity.NONE,
    "asleep": Connectivity.NONE,
}


class NmConnectivity(ConnectivityChecker):
    """`nmcli general` state for polling; `nmcli networking connectivity check` (NetworkManager's own
    probe) when ``probe`` is set; if ``probe_url`` is given, that URL must also answer with 2xx."""

    def __init__(self, runner: Runner = _run, probe_url: str = "", opener: Callable[..., object] | None = None) -> None:
        self._run, self._url = runner, probe_url
        self._open = opener or urllib.request.urlopen

    def _nmcli(self, *args: str) -> str | None:
        try:
            res = self._run(["nmcli", "-t", *args])
        except (FileNotFoundError, subprocess.TimeoutExpired):
            return None
        return res.stdout.strip().lower() if res.returncode == 0 else None

    def _http_ok(self) -> bool:
        try:
            with self._open(urllib.request.Request(self._url, headers={"User-Agent": "openhotspot"}), timeout=8) as r:  # type: ignore[operator]
                return 200 <= r.status < 300  # type: ignore[attr-defined]
        except (urllib.error.URLError, OSError, ValueError):
            return False

    def check(self, probe: bool = False) -> Connectivity:
        state = _STATE_MAP.get(self._nmcli("-g", "STATE", "general") or "", Connectivity.UNKNOWN)
        if probe:
            nm = self._nmcli("-g", "CONNECTIVITY", "networking", "connectivity", "check")
            try:
                state = Connectivity(nm) if nm else state
            except ValueError:  # 'portal'
                state = Connectivity.LIMITED
            if state == Connectivity.UNKNOWN:  # NM's connectivity checking is off: trust its general state
                state = _STATE_MAP.get(self._nmcli("-g", "STATE", "general") or "", Connectivity.UNKNOWN)
        if probe and self._url and state in (Connectivity.FULL, Connectivity.UNKNOWN):
            return Connectivity.FULL if self._http_ok() else Connectivity.LIMITED
        return state
