"""Tiny shared state between the CLI and the daemon: 'stay quiet until T' after a manual disconnect."""
from __future__ import annotations

import json
import os
import tempfile
from pathlib import Path


class StateFile:
    def __init__(self, path: Path) -> None:
        self.path = path

    def _read(self) -> dict:
        try:
            data = json.loads(self.path.read_text())
            return data if isinstance(data, dict) else {}
        except (OSError, ValueError):
            return {}  # missing or corrupt state is harmless: it only affects politeness

    def suppress_until(self) -> float:
        value = self._read().get("suppress_until", 0)
        return float(value) if isinstance(value, (int, float)) else 0.0

    def suppress(self, until: float) -> None:
        data = self._read()
        data["suppress_until"] = until
        try:
            self.path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            fd, tmp = tempfile.mkstemp(dir=self.path.parent, prefix=".state-", suffix=".tmp")
            with os.fdopen(fd, "w") as f:
                json.dump(data, f)
            os.replace(tmp, self.path)
        except OSError:
            pass
