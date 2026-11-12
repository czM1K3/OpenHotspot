"""TOML configuration (`~/.config/openhotspot/config.toml`). Unknown keys are errors, so typos show up."""
from __future__ import annotations

import os
import tomllib
from dataclasses import dataclass, field, fields
from pathlib import Path
from typing import Any

from .errors import ConfigError

MODES = ("auto", "manual", "off")


def default_config_path() -> Path:
    base = os.environ.get("XDG_CONFIG_HOME") or str(Path.home() / ".config")
    return Path(base) / "openhotspot" / "config.toml"


def default_state_path() -> Path:
    base = os.environ.get("XDG_STATE_HOME") or str(Path.home() / ".local" / "state")
    return Path(base) / "openhotspot" / "state.json"


@dataclass(frozen=True)
class DaemonConfig:
    mode: str = "auto"  # auto: connect by itself; manual: only notify; off: do nothing
    scan_interval_s: float = 60.0  # pause between scans while offline and the phone is not seen
    max_scan_interval_s: float = 300.0  # the pause grows towards this while the phone stays away
    scan_duration_s: float = 6.0  # one scan lasts at most this long (radio duty cycle = duration / interval)
    idle_interval_s: float = 30.0  # how often to check the connection state while online (no scanning)
    cooldown_s: float = 300.0  # after a successful connect, do not trigger again for this long
    failure_cooldown_s: float = 120.0  # after a failed attempt
    disconnect_pause_s: float = 3600.0  # after `openhotspot disconnect`, stay quiet this long
    manual_notify_interval_s: float = 1800.0  # manual mode: at most one 'phone nearby' notice per this long


@dataclass(frozen=True)
class BleConfig:
    adapter: str = ""  # e.g. "hci1"; empty = BlueZ default
    passive_scan: bool = False  # BlueZ advertisement monitor; may need `bluetoothd --experimental`
    connect_timeout_s: float = 15.0
    attempts: int = 2  # tries per request when the link drops or BlueZ is briefly busy


@dataclass(frozen=True)
class ConnectConfig:
    auto_off_s: int = 0  # ask the phone to turn the hotspot off after this long (0 = phone default)
    verify: bool = True  # check that the internet works after joining
    verify_timeout_s: float = 30.0
    probe_url: str = ""  # optional URL that must answer 2xx (e.g. http://connectivitycheck.gstatic.com/generate_204)


@dataclass(frozen=True)
class NotificationsConfig:
    enabled: bool = True


@dataclass(frozen=True)
class Config:
    phone: str = ""  # phone id (prefix); needed only if several phones are paired
    daemon: DaemonConfig = field(default_factory=DaemonConfig)
    ble: BleConfig = field(default_factory=BleConfig)
    connect: ConnectConfig = field(default_factory=ConnectConfig)
    notifications: NotificationsConfig = field(default_factory=NotificationsConfig)


def _build(cls: type, data: Any, where: str) -> Any:
    if not isinstance(data, dict):
        raise ConfigError(f"[{where}] must be a table")
    known = {f.name: f for f in fields(cls)}
    unknown = sorted(set(data) - set(known))
    if unknown:
        raise ConfigError(f"unknown key(s) in [{where}]: {', '.join(unknown)} (allowed: {', '.join(sorted(known))})")
    out: dict[str, Any] = {}
    default = cls()
    for name, value in data.items():
        want = type(getattr(default, name))
        if isinstance(value, bool) != (want is bool) or (want is not bool and not isinstance(value, (int, float, str))):
            raise ConfigError(f"[{where}] {name} must be a {want.__name__}")
        if want is float and isinstance(value, int):
            value = float(value)
        if not isinstance(value, want):
            raise ConfigError(f"[{where}] {name} must be a {want.__name__}")
        if want in (int, float) and value < 0:
            raise ConfigError(f"[{where}] {name} must not be negative")
        out[name] = value
    return cls(**out)


def parse_config(text: str, source: str = "config") -> Config:
    try:
        data = tomllib.loads(text)
    except tomllib.TOMLDecodeError as exc:
        raise ConfigError(f"{source}: invalid TOML: {exc}") from exc
    try:
        data = dict(data)
        sections = {
            "daemon": _build(DaemonConfig, data.pop("daemon", {}), "daemon"),
            "ble": _build(BleConfig, data.pop("ble", {}), "ble"),
            "connect": _build(ConnectConfig, data.pop("connect", {}), "connect"),
            "notifications": _build(NotificationsConfig, data.pop("notifications", {}), "notifications"),
        }
        phone = data.pop("phone", "")
        if not isinstance(phone, str):
            raise ConfigError("phone must be a string")
        if data:
            raise ConfigError(f"unknown top-level key(s): {', '.join(sorted(data))}")
        cfg = Config(phone=phone, **sections)
        d = cfg.daemon
        if d.mode not in MODES:
            raise ConfigError(f"[daemon] mode must be one of {', '.join(MODES)} (got '{d.mode}')")
        if d.scan_interval_s < 5 or d.idle_interval_s < 5:
            raise ConfigError("[daemon] scan_interval_s and idle_interval_s must be at least 5 seconds")
        if not 1 <= d.scan_duration_s <= d.scan_interval_s:
            raise ConfigError("[daemon] scan_duration_s must be between 1 and scan_interval_s")
        if d.max_scan_interval_s < d.scan_interval_s:
            raise ConfigError("[daemon] max_scan_interval_s must be at least scan_interval_s")
        if cfg.ble.attempts < 1:
            raise ConfigError("[ble] attempts must be at least 1")
        return cfg
    except ConfigError as exc:
        raise ConfigError(f"{source}: {exc}") from None


def load_config(path: Path | None = None) -> Config:
    """An explicit ``path`` must exist; the default path may be absent (defaults apply)."""
    explicit = path is not None
    path = path or default_config_path()
    try:
        text = path.read_text()
    except FileNotFoundError:
        if explicit:
            raise ConfigError(f"config file {path} does not exist") from None
        return Config()
    except OSError as exc:
        raise ConfigError(f"cannot read {path}: {exc}") from exc
    return parse_config(text, str(path))
