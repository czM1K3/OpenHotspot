"""One-line, user-facing explanations of failures (shared by the CLI, the daemon and notifications)."""
from __future__ import annotations

from .constants import ErrorCode
from .errors import PhoneError, PhoneNotFound

_PHONE_HINTS: dict[ErrorCode, str] = {
    ErrorCode.SHIZUKU_UNAVAILABLE: "Shizuku is not running on the phone; start it (see docs/SETUP.md) and try again",
    ErrorCode.PERMISSION_DENIED: "Shizuku has not granted OpenHotspot permission; open the phone app and grant it",
    ErrorCode.HOTSPOT_FAILED: "the phone could not start its hotspot; check the phone app's log",
    ErrorCode.STALE_TIMESTAMP: "the laptop and phone clocks differ by more than a minute; enable time sync "
                               "(`timedatectl set-ntp true`) on both",
    ErrorCode.AUTH_FAILED: "the phone does not recognise this laptop; the pairing may have been removed, so pair again",
    ErrorCode.REPLAY: "the phone rejected the request counter; try again",
    ErrorCode.BUSY: "the phone is busy with another request; try again in a moment",
    ErrorCode.RATE_LIMITED: "the phone is rate limiting requests; wait and retry",
    ErrorCode.UNSUPPORTED_VERSION: "the phone app speaks another protocol version; update both apps",
}


def describe_error(exc: BaseException) -> str:
    if isinstance(exc, PhoneNotFound):
        return f"{exc}. Is the phone nearby with Bluetooth on and the app running?"
    if isinstance(exc, PhoneError):
        hint = _PHONE_HINTS.get(exc.code, "") if isinstance(exc.code, ErrorCode) else ""
        warn = "" if exc.authenticated else " (unauthenticated: could be a forged message)"
        return f"{exc}{warn}" + (f": {hint}" if hint else "") + "."
    return str(exc)
