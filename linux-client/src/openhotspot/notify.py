"""Desktop notifications through `notify-send` (optional; silently does nothing if it is missing)."""
from __future__ import annotations

import logging
import shutil
import subprocess
from abc import ABC, abstractmethod
from collections.abc import Callable, Sequence

log = logging.getLogger(__name__)


class Notifier(ABC):
    @abstractmethod
    def notify(self, title: str, body: str = "", *, urgent: bool = False) -> None: ...


class NullNotifier(Notifier):
    def notify(self, title: str, body: str = "", *, urgent: bool = False) -> None:
        return None


class NotifySend(Notifier):
    """Never raises: a missing or failing notification daemon must not break a connection."""

    def __init__(self, runner: Callable[[Sequence[str]], object] | None = None, which: Callable[[str], str | None] = shutil.which) -> None:
        self._run = runner or self._default_run
        self._which = which
        self._warned = False

    @staticmethod
    def _default_run(argv: Sequence[str]) -> object:
        return subprocess.run(list(argv), capture_output=True, timeout=5, check=False)

    def notify(self, title: str, body: str = "", *, urgent: bool = False) -> None:
        if self._which("notify-send") is None:
            if not self._warned:
                log.info("notify-send not found; desktop notifications are disabled")
                self._warned = True
            return
        argv = ["notify-send", "--app-name=OpenHotspot", f"--urgency={'critical' if urgent else 'normal'}",
                "--icon=network-wireless", "--", title]
        if body:
            argv.append(body)
        try:
            self._run(argv)
        except (OSError, subprocess.SubprocessError) as exc:
            log.debug("notify-send failed: %s", exc)
