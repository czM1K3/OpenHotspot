"""Real BLE transport: bleak on BlueZ (Linux).

Everything that touches bleak goes through two small factories so the unit tests can replace
BlueZ with a fake. What the fakes cannot prove (BlueZ behaviour, MTU, real radio timing) is
listed in docs/TESTING.md.
"""
from __future__ import annotations

import asyncio
import logging
from collections.abc import Callable
from typing import Any

from bleak import BleakClient, BleakScanner
from bleak.args.bluez import OrPattern
from bleak.assigned_numbers import AdvertisementDataType
from bleak.exc import (
    BleakBluetoothNotAvailableError,
    BleakBluetoothNotAvailableReason,
    BleakDBusError,
    BleakDeviceNotFoundError,
    BleakError,
)

from .advertisement import Advertisement
from .constants import (
    CHAR_REQUEST_UUID,
    CHAR_RESPONSE_UUID,
    CHAR_STATUS_UUID,
    MIN_MTU,
    SERVICE_UUID,
    SERVICE_UUID_BYTES,
)
from .errors import BluetoothError, TransportError, TransportTimeout
from .transport import Connection, Transport

log = logging.getLogger(__name__)

MAX_ATT_MTU = 517

# D-Bus error name -> (summary, hint, retryable)
_DBUS_ERRORS: dict[str, tuple[str, str, bool]] = {
    "org.freedesktop.DBus.Error.ServiceUnknown": (
        "the Bluetooth service (bluetoothd) is not running",
        "start it with `sudo systemctl enable --now bluetooth`", False),
    "org.freedesktop.DBus.Error.NameHasNoOwner": (
        "the Bluetooth service (bluetoothd) is not running",
        "start it with `sudo systemctl enable --now bluetooth`", False),
    "org.freedesktop.DBus.Error.AccessDenied": (
        "D-Bus refused access to BlueZ",
        "run from a normal logged-in session (not su/cron/ssh without a session), or allow your user in "
        "/etc/dbus-1/system.d/bluetooth.conf; see docs/SETUP.md, Linux, troubleshooting", False),
    "org.bluez.Error.NotReady": (
        "the Bluetooth adapter is not ready",
        "run `rfkill unblock bluetooth` and `bluetoothctl power on`", False),
    "org.bluez.Error.NotPermitted": (
        "BlueZ refused the operation (not permitted)",
        "check that no other program is holding the adapter and that your user may use Bluetooth", False),
    "org.bluez.Error.NotAuthorized": (
        "BlueZ refused the operation (not authorized)",
        "check that your user may use Bluetooth; see docs/SETUP.md", False),
    "org.bluez.Error.NotSupported": (
        "BlueZ does not support this operation",
        "your BlueZ or adapter is too old or lacks a feature (see docs/SETUP.md for BlueZ versions)", False),
    "org.bluez.Error.InProgress": (
        "BlueZ is busy with another Bluetooth operation", "it will be retried", True),
    "org.bluez.Error.AlreadyConnected": ("already connected to the phone", "", True),
    "org.bluez.Error.Failed": ("BlueZ could not complete the operation", "", True),
    "org.bluez.Error.ConnectionAttemptFailed": ("could not connect to the phone", "is it in range?", True),
}

_REASON_HINTS = {
    BleakBluetoothNotAvailableReason.NO_BLUETOOTH: (
        "no Bluetooth adapter found", "check `bluetoothctl list`, `rfkill list` and that the driver is loaded"),
    BleakBluetoothNotAvailableReason.NO_BLE_CENTRAL_ROLE: (
        "the Bluetooth adapter cannot act as a BLE central", "it needs Bluetooth Low Energy support"),
    BleakBluetoothNotAvailableReason.POWERED_OFF: (
        "Bluetooth is turned off", "run `rfkill unblock bluetooth` and `bluetoothctl power on`"),
}


def translate_error(exc: BaseException) -> TransportError:
    """Turn any bleak, D-Bus or asyncio failure into a ``TransportError`` with a clear message."""
    if isinstance(exc, TransportError):
        return exc
    if isinstance(exc, (asyncio.TimeoutError, TimeoutError)):
        return BluetoothError("the Bluetooth operation timed out", "is the phone in range?", retryable=True)
    if isinstance(exc, BleakBluetoothNotAvailableError):
        summary, hint = _REASON_HINTS.get(exc.reason, (str(exc.args[0]), ""))
        return BluetoothError(summary, hint)
    if isinstance(exc, BleakDBusError):
        known = _DBUS_ERRORS.get(exc.dbus_error)
        if known is not None:
            summary, hint, retryable = known
            detail = exc.dbus_error_details
            if exc.dbus_error == "org.bluez.Error.Failed" and detail:
                summary = f"{summary} ({detail})"
            return BluetoothError(summary, hint, retryable=retryable)
        return BluetoothError(f"BlueZ error {exc}", retryable=False)
    if isinstance(exc, BleakDeviceNotFoundError):
        return BluetoothError("BlueZ no longer knows the phone (it forgot the device)", "it will be rescanned",
                              retryable=True)
    if isinstance(exc, BleakError):
        text = str(exc)
        if "BlueZ >=" in text:
            return BluetoothError(text, "upgrade BlueZ (docs/SETUP.md lists the minimum version)")
        if "not connected" in text.lower() or "disconnected" in text.lower():
            return TransportError("the phone disconnected")
        return BluetoothError(f"Bluetooth error: {text}", retryable=False)
    if isinstance(exc, (FileNotFoundError, ConnectionRefusedError, ConnectionResetError, EOFError)):
        return BluetoothError(
            "cannot talk to the system D-Bus", "is D-Bus running? (`systemctl status dbus`)", retryable=False)
    if isinstance(exc, PermissionError):
        return BluetoothError("permission denied talking to D-Bus", "see docs/SETUP.md, Linux, troubleshooting")
    return TransportError(f"unexpected BLE failure: {type(exc).__name__}: {exc}")


class BleakConnection(Connection):
    """One GATT connection. Notifications land in a queue; a disconnect wakes any waiting reader."""

    def __init__(self, client: Any, mtu: int, write_timeout: float) -> None:
        self._client = client
        self.mtu = mtu
        self._write_timeout = write_timeout
        self._queue: asyncio.Queue[bytes] = asyncio.Queue()
        self._gone = asyncio.Event()

    # callbacks (run on the event loop)
    def on_notify(self, _characteristic: Any, data: bytearray) -> None:
        self._queue.put_nowait(bytes(data))

    def on_disconnect(self, _client: Any = None) -> None:
        self._gone.set()

    async def write_request(self, chunk: bytes) -> None:
        if self._gone.is_set():
            raise TransportError("the phone disconnected")
        try:
            await asyncio.wait_for(
                self._client.write_gatt_char(CHAR_REQUEST_UUID, chunk, response=True), self._write_timeout)
        except Exception as exc:
            raise translate_error(exc) from exc

    async def read_response_chunk(self, timeout: float) -> bytes:
        if not self._queue.empty():
            return self._queue.get_nowait()
        if self._gone.is_set():
            raise TransportError("the phone disconnected before answering")
        getter = asyncio.ensure_future(self._queue.get())
        gone = asyncio.ensure_future(self._gone.wait())
        try:
            done, _ = await asyncio.wait({getter, gone}, timeout=timeout, return_when=asyncio.FIRST_COMPLETED)
        finally:
            for fut in (getter, gone):
                if not fut.done():
                    fut.cancel()
        if getter in done:
            return getter.result()
        if gone in done:
            raise TransportError("the phone disconnected before answering")
        raise TransportTimeout("no response notification before the timeout")

    async def read_status(self) -> bytes:
        try:
            return bytes(await asyncio.wait_for(self._client.read_gatt_char(CHAR_STATUS_UUID), self._write_timeout))
        except Exception as exc:
            raise translate_error(exc) from exc

    async def close(self) -> None:
        try:
            if not self._gone.is_set():
                try:
                    await asyncio.wait_for(self._client.stop_notify(CHAR_RESPONSE_UUID), 3)
                except Exception:
                    pass  # best effort; the disconnect below drops the subscription anyway
            await asyncio.wait_for(self._client.disconnect(), 5)
        except Exception as exc:  # never let cleanup hide the real result
            log.debug("ignoring error while disconnecting: %s", exc)


ScannerFactory = Callable[..., Any]
ClientFactory = Callable[..., Any]


class BleakTransport(Transport):
    """Scans for the OpenHotspot service data and talks GATT through bleak/BlueZ.

    * ``scan`` filters on the service-data UUID in Python (BlueZ's UUID filter is not documented to
      cover service data keys) and stops as soon as ``matcher`` accepts one advertisement.
    * ``passive=True`` uses a BlueZ advertisement monitor (needs ``bluetoothd --experimental`` on
      older BlueZ) and falls back to active scanning if BlueZ refuses.
    * ``connect`` retries transient connection failures, reads the MTU BlueZ negotiated from the
      characteristic's ``MTU`` property (BlueZ >= 5.62; otherwise it stays at 23) and subscribes to Response.
    """

    def __init__(
        self, *, adapter: str | None = None, passive: bool = False, connect_timeout: float = 15.0,
        write_timeout: float = 10.0, connect_attempts: int = 2, retry_delay: float = 1.0,
        mtu_settle_s: float = 1.0, scanner_factory: ScannerFactory | None = None,
        client_factory: ClientFactory | None = None,
        sleep: Callable[[float], Any] = asyncio.sleep,
    ) -> None:
        self.adapter, self.passive = adapter or None, passive
        self.connect_timeout, self.write_timeout = connect_timeout, write_timeout
        self.connect_attempts, self.retry_delay, self.mtu_settle_s = max(1, connect_attempts), retry_delay, mtu_settle_s
        self._scanner_factory = scanner_factory or self._default_scanner
        self._client_factory = client_factory or self._default_client
        self._sleep = sleep
        self._seen: dict[str, Any] = {}  # address -> BLEDevice from the scan (avoids a second discovery)

    # ---- bleak factories ----------------------------------------------------------------------

    def _default_scanner(self, callback: Callable[..., None], passive: bool) -> Any:
        bluez: dict[str, Any] = {}
        if self.adapter:
            bluez["adapter"] = self.adapter
        if passive:
            bluez["or_patterns"] = [OrPattern(0, AdvertisementDataType.SERVICE_DATA_UUID128, SERVICE_UUID_BYTES[::-1])]
        return BleakScanner(callback, scanning_mode="passive" if passive else "active", bluez=bluez)

    def _default_client(self, target: Any, disconnected_callback: Callable[[Any], None], timeout: float) -> Any:
        kwargs: dict[str, Any] = {"disconnected_callback": disconnected_callback, "timeout": timeout}
        if self.adapter:
            kwargs["bluez"] = {"adapter": self.adapter}
        return BleakClient(target, **kwargs)

    # ---- scanning -----------------------------------------------------------------------------

    async def scan(self, matcher: Callable[[Advertisement], bool], timeout: float) -> Advertisement | None:
        try:
            if self.passive:
                try:
                    return await self._scan_once(matcher, timeout, passive=True)
                except BleakError as exc:
                    log.warning("passive scan refused (%s); falling back to an active scan", exc)
            return await self._scan_once(matcher, timeout, passive=False)
        except Exception as exc:
            raise translate_error(exc) from exc

    async def _scan_once(self, matcher: Callable[[Advertisement], bool], timeout: float, *, passive: bool) -> Advertisement | None:
        found: list[Advertisement] = []
        event = asyncio.Event()

        def on_detect(device: Any, data: Any) -> None:
            payload = (data.service_data or {}).get(SERVICE_UUID)
            if payload is None or found:
                return
            adv = Advertisement(device.address, bytes(payload), getattr(data, "rssi", None))
            if matcher(adv):
                self._seen[adv.address] = device
                found.append(adv)
                event.set()

        scanner = self._scanner_factory(on_detect, passive)
        await scanner.start()
        try:
            try:
                await asyncio.wait_for(event.wait(), timeout)
            except asyncio.TimeoutError:
                pass
        finally:
            try:
                await scanner.stop()
            except Exception as exc:  # a failed stop must not hide a result we already have
                log.debug("ignoring error while stopping the scan: %s", exc)
        return found[0] if found else None

    # ---- connecting ---------------------------------------------------------------------------

    async def connect(self, adv: Advertisement) -> BleakConnection:
        last: TransportError | None = None
        for attempt in range(1, self.connect_attempts + 1):
            conn: BleakConnection | None = None
            client = None
            try:
                holder: list[BleakConnection] = []
                client = self._client_factory(
                    self._seen.get(adv.address, adv.address),
                    disconnected_callback=lambda c: holder and holder[0].on_disconnect(c),
                    timeout=self.connect_timeout,
                )
                await asyncio.wait_for(client.connect(), self.connect_timeout + 5)
                if client.services.get_service(SERVICE_UUID) is None:
                    raise BluetoothError(
                        "the device does not offer the OpenHotspot service",
                        "BlueZ may have cached an old device; run `bluetoothctl remove <address>` and retry")
                mtu = await self._negotiated_mtu(client)
                conn = BleakConnection(client, mtu, self.write_timeout)
                holder.append(conn)
                await client.start_notify(CHAR_RESPONSE_UUID, conn.on_notify)
                log.debug("connected, MTU %d", mtu)
                return conn
            except Exception as exc:
                last = translate_error(exc)
                if conn is not None:
                    await conn.close()
                elif client is not None:
                    try:
                        await asyncio.wait_for(client.disconnect(), 5)
                    except Exception:
                        pass
                retryable = getattr(last, "retryable", False)
                log.debug("connect attempt %d/%d failed: %s", attempt, self.connect_attempts, last)
                if not retryable or attempt == self.connect_attempts:
                    raise last from exc
                await self._sleep(self.retry_delay * attempt)
        raise last or TransportError("connect failed")  # pragma: no cover (loop always raises)

    async def _negotiated_mtu(self, client: Any) -> int:
        """ATT MTU from the Request characteristic's BlueZ ``MTU`` property.

        bleak's public ``mtu_size`` is hard-wired to 23 on BlueZ, but a characteristic reports
        ``MTU - 3`` as ``max_write_without_response_size`` (BlueZ >= 5.62; 20 means 'unknown').
        The property can lag behind the connection, so wait briefly for it to rise above 20.
        """
        try:
            char = client.services.get_characteristic(CHAR_REQUEST_UUID)
            if char is None:
                raise BluetoothError("the OpenHotspot service has no Request characteristic",
                                     "the phone app is probably an incompatible version")
            waited = 0.0
            size = char.max_write_without_response_size
            while size <= MIN_MTU - 3 and waited < self.mtu_settle_s:
                await self._sleep(0.1)
                waited += 0.1
                size = char.max_write_without_response_size
            return max(MIN_MTU, min(size + 3, MAX_ATT_MTU))
        except BluetoothError:
            raise
        except Exception as exc:  # unknown stack quirk: the minimum MTU is always safe (PROTOCOL.md R13)
            log.debug("could not read the MTU (%s); using %d", exc, MIN_MTU)
            return MIN_MTU
