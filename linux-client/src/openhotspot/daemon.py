"""Background mode: when the laptop is offline and the paired phone is near, bring the hotspot up.

Low power by construction:
* While the laptop is online the daemon does not scan at all, it only polls NetworkManager's state.
* While offline it scans for ``scan_duration_s`` every ``scan_interval_s``; the pause grows
  towards ``max_scan_interval_s`` while the phone stays away, and resets when it is seen.
* After an attempt (success or failure) and after a manual `disconnect` it stays quiet for a cooldown.
"""
from __future__ import annotations

import asyncio
import logging
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass
from enum import Enum

from .client import HotspotClient
from .config import Config
from .connectivity import ConnectivityChecker
from .errors import BluetoothError, ConnectivityError, OpenHotspotError, PhoneError, PhoneNotFound, WifiError
from .explain import describe_error
from .flow import connect_flow
from .notify import Notifier, NullNotifier
from .state import StateFile
from .storage import KeyStore
from .wifi import WifiConnector

log = logging.getLogger(__name__)


class Mode(str, Enum):
    AUTO = "auto"
    MANUAL = "manual"
    OFF = "off"


class Activity(str, Enum):
    """What the daemon is doing, for the tray icon and logs."""

    OFF = "off"
    ONLINE = "online"
    SEARCHING = "searching"
    PHONE_NEARBY = "phone nearby"
    CONNECTING = "connecting"
    CONNECTED = "connected"
    COOLDOWN = "waiting (cooldown)"
    ERROR = "error"


@dataclass
class TickResult:
    delay: float
    activity: Activity
    detail: str = ""


class Daemon:
    def __init__(
        self, client: HotspotClient, store: KeyStore, wifi: WifiConnector, connectivity: ConnectivityChecker,
        config: Config, *, notifier: Notifier | None = None, state: StateFile | None = None,
        clock: Callable[[], float] = time.time,
    ) -> None:
        self.client, self.store, self.wifi, self.connectivity = client, store, wifi, connectivity
        self.cfg, self.notifier = config, notifier or NullNotifier()
        self.state, self.clock = state, clock
        self._last_problem = ""
        self.mode = Mode(config.daemon.mode)
        self.activity, self.detail = Activity.OFF, ""
        self._misses = 0
        self._quiet_until = 0.0  # cooldown
        self._last_manual_notice = -1e18
        self._connect_now = False
        self._wake: asyncio.Event | None = None
        self._loop: asyncio.AbstractEventLoop | None = None
        self._stop = False
        self._listeners: list[Callable[[Activity, str], None]] = []
        self._lock = threading.Lock()

    # ---- control (thread-safe, used by the tray) ------------------------------------------------

    def on_change(self, listener: Callable[[Activity, str], None]) -> None:
        self._listeners.append(listener)

    def _set(self, activity: Activity, detail: str = "") -> None:
        if (activity, detail) != (self.activity, self.detail):
            self.activity, self.detail = activity, detail
            for fn in self._listeners:
                try:
                    fn(activity, detail)
                except Exception:  # a broken tray must not stop the daemon
                    log.exception("status listener failed")

    def _poke(self) -> None:
        if self._loop is not None and self._wake is not None:
            self._loop.call_soon_threadsafe(self._wake.set)

    def set_mode(self, mode: Mode) -> None:
        with self._lock:
            self.mode = mode
        log.info("mode is now %s", mode.value)
        self._poke()

    def request_connect(self) -> None:
        """Connect as soon as possible, whatever the mode and cooldown (the tray's 'Connect now')."""
        with self._lock:
            self._connect_now = True
        self._poke()

    def stop(self) -> None:
        self._stop = True
        self._poke()

    # ---- main loop ------------------------------------------------------------------------------

    async def run(self, stop: asyncio.Event | None = None) -> None:
        self._loop = asyncio.get_running_loop()
        self._wake = asyncio.Event()
        log.info("daemon started (mode %s)", self.mode.value)
        while not self._stop and not (stop and stop.is_set()):
            try:
                result = await self.tick()
            except Exception as exc:  # the daemon must outlive any single bad iteration
                log.exception("unexpected error in the daemon loop")
                result = TickResult(60.0, Activity.ERROR, f"{type(exc).__name__}: {exc}")
            self._set(result.activity, result.detail)
            waiters = [asyncio.ensure_future(self._wake.wait())]
            if stop is not None:
                waiters.append(asyncio.ensure_future(stop.wait()))
            try:
                await asyncio.wait(waiters, timeout=result.delay, return_when=asyncio.FIRST_COMPLETED)
            finally:
                for w in waiters:
                    w.cancel()
            self._wake.clear()
        log.info("daemon stopped")

    async def tick(self) -> TickResult:
        d = self.cfg.daemon
        now = self.clock()
        with self._lock:
            forced, self._connect_now = self._connect_now, False
            mode = self.mode
        if mode == Mode.OFF and not forced:
            return TickResult(d.idle_interval_s, Activity.OFF)

        try:
            phone = self.store.resolve(self.cfg.phone or None)
        except OpenHotspotError as exc:
            self._problem(str(exc))
            return TickResult(max(d.idle_interval_s, 60.0), Activity.ERROR, str(exc))

        if not forced:
            if await asyncio.to_thread(self.connectivity.online):
                self._misses = 0
                return TickResult(d.idle_interval_s, Activity.ONLINE)
            quiet_until = max(self._quiet_until, self.state.suppress_until() if self.state else 0.0)
            if now < quiet_until:
                return TickResult(min(quiet_until - now, d.scan_interval_s), Activity.COOLDOWN)

        # Offline (or told to connect): look for the phone.
        try:
            adv = await self.client.find_phone(phone, d.scan_duration_s if not forced else self.client.scan_timeout)
        except BluetoothError as exc:
            # Bluetooth off, no adapter, bluetoothd down: say it once per change, back off hard.
            self._problem(f"cannot scan: {exc}")
            self._misses += 1
            return TickResult(self._backoff(), Activity.ERROR, str(exc))
        except OpenHotspotError as exc:
            self._problem(f"scan failed: {exc}")
            self._misses += 1
            return TickResult(self._backoff(), Activity.ERROR, str(exc))

        if adv is None:
            self._misses += 1
            if forced:
                self.notifier.notify("OpenHotspot", "Phone not found nearby.")
            return TickResult(self._backoff(), Activity.SEARCHING)
        self._misses = 0
        self._last_problem = ""

        if mode != Mode.AUTO and not forced:
            if now - self._last_manual_notice >= d.manual_notify_interval_s:
                self._last_manual_notice = now
                self.notifier.notify("Phone nearby", "No internet here. Run `openhotspot connect` or use the tray icon.")
            return TickResult(d.scan_interval_s, Activity.PHONE_NEARBY)

        return await self._connect(phone, adv)

    def _problem(self, text: str) -> None:
        """Log a recurring problem once, not every scan."""
        (log.debug if text == self._last_problem else log.warning)("%s", text)
        self._last_problem = text

    def _backoff(self) -> float:
        d = self.cfg.daemon
        # five scans at the base interval, then doubling up to the maximum
        steps = max(0, self._misses - 5)
        return min(d.scan_interval_s * (2 ** steps), d.max_scan_interval_s)

    async def _connect(self, phone, adv) -> TickResult:
        d, c = self.cfg.daemon, self.cfg.connect
        self._set(Activity.CONNECTING)
        # Set the cooldown before trying so that neither a crash nor a failure can cause a retry storm.
        self._quiet_until = self.clock() + d.failure_cooldown_s
        log.info("phone in range and no internet: connecting")
        try:
            info = await connect_flow(
                self.client, phone, self.wifi, self.connectivity, adv=adv, auto_off_s=c.auto_off_s,
                verify=c.verify, verify_timeout_s=c.verify_timeout_s, say=lambda m: log.info("%s", m),
            )
        except ConnectivityError as exc:
            # Joined, but no internet. A network that goes nowhere is worse than none (it can stop
            # the laptop from joining a real one), so undo it.
            log.warning("%s", exc)
            await self._undo()
            self.notifier.notify("Hotspot has no internet", str(exc), urgent=True)
            return TickResult(d.failure_cooldown_s, Activity.ERROR, str(exc))
        except (PhoneError, PhoneNotFound, WifiError, OpenHotspotError) as exc:
            message = describe_error(exc)
            log.warning("connect failed: %s", message)
            if isinstance(exc, PhoneError) and exc.retry_after_s:
                self._quiet_until = max(self._quiet_until, self.clock() + exc.retry_after_s)
            self.notifier.notify("Could not use the phone's hotspot", message, urgent=True)
            return TickResult(d.failure_cooldown_s, Activity.ERROR, message)
        self._quiet_until = self.clock() + d.cooldown_s
        ssid = info.ssid.decode(errors="replace")
        log.info("connected to '%s'", ssid)
        self.notifier.notify("Connected to your phone's hotspot", ssid)
        return TickResult(d.idle_interval_s, Activity.CONNECTED, ssid)

    async def _undo(self) -> None:
        try:
            await asyncio.to_thread(self.wifi.disconnect, None)
        except WifiError as exc:
            log.warning("could not remove the Wi-Fi connection: %s", exc)
        try:
            await self.client.disable(self.store.resolve(self.cfg.phone or None))
        except OpenHotspotError as exc:
            log.warning("could not ask the phone to turn its hotspot off: %s", exc)


__all__ = ["Daemon", "Mode", "Activity", "TickResult"]
