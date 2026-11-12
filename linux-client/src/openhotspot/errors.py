"""Exception hierarchy."""
from __future__ import annotations

from .constants import ErrorCode


class OpenHotspotError(Exception):
    """Base class for all errors raised by this package."""


class ProtocolError(OpenHotspotError):
    """Malformed or invalid protocol data (carries the error code to report)."""

    def __init__(self, code: ErrorCode, message: str = "") -> None:
        super().__init__(message or code.name)
        self.code = code


class AuthenticationError(OpenHotspotError):
    """AEAD authentication failed or a key-confirmation check did not match."""


class PairingError(OpenHotspotError):
    """Pairing could not be completed."""


class PhoneError(OpenHotspotError):
    """The phone answered with an ERROR frame.

    ``authenticated`` is False for plaintext errors, which anyone can forge: callers
    must treat those as hints only (PROTOCOL.md section 5).
    """

    def __init__(self, code: ErrorCode | int, retry_after_s: int = 0, *, authenticated: bool) -> None:
        try:
            self.code = ErrorCode(code)
            name = self.code.name
        except ValueError:
            self.code = code  # type: ignore[assignment]
            name = f"0x{code:02x}"
        super().__init__(f"phone reported {name}" + (f" (retry in {retry_after_s}s)" if retry_after_s else ""))
        self.retry_after_s = retry_after_s
        self.authenticated = authenticated


class TransportError(OpenHotspotError):
    """The BLE (or in-memory) transport failed."""


class TransportTimeout(TransportError):
    pass


class PhoneNotFound(TransportError):
    """No matching advertisement was seen before the scan timeout."""


class WifiError(OpenHotspotError):
    pass


class KeyStoreError(OpenHotspotError):
    pass


class NotPairedError(KeyStoreError):
    """No paired phone, or the choice of phone is ambiguous."""
