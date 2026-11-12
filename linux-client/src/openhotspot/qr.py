"""Turning the `pair` argument into a QR string: a literal, or an image decoded with `zbarimg`."""
from __future__ import annotations

import subprocess
from collections.abc import Callable
from pathlib import Path

from .constants import QR_PREFIX
from .errors import PairingError


def decode_qr_image(path: str) -> str:
    """Decode a QR image using the `zbarimg` CLI (ZBar, LGPL, used as an external tool only)."""
    try:
        result = subprocess.run(["zbarimg", "--raw", "-q", path], capture_output=True, text=True, timeout=30, check=False)
    except FileNotFoundError as exc:
        raise PairingError("zbarimg not found; install zbar-tools or pass the QR string instead") from exc
    for line in result.stdout.splitlines():
        if line.strip().startswith(QR_PREFIX):
            return line.strip()
    raise PairingError(f"no OpenHotspot QR code found in {path}")


def resolve_qr_argument(arg: str, decoder: Callable[[str], str] = decode_qr_image) -> str:
    if arg.strip().startswith(QR_PREFIX):
        return arg.strip()
    if Path(arg).is_file():
        return decoder(arg)
    raise PairingError("argument is neither an openhotspot://pair/... string nor an image file")
