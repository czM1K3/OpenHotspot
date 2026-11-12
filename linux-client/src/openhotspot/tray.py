"""Optional tray icon (`pip install 'openhotspot[tray]'`, which adds pystray and Pillow).

The menu logic lives in ``TrayModel`` and needs neither library, so it is unit tested; only
``run_tray`` touches pystray. On GNOME the icon needs the AppIndicator/KStatusNotifierItem
shell extension; KDE, XFCE, Cinnamon, MATE and most others show it out of the box.
"""
from __future__ import annotations

import logging
import signal
from collections.abc import Callable
from dataclasses import dataclass
from functools import partial

from .daemon import Activity, Daemon, Mode
from .errors import OpenHotspotError

log = logging.getLogger(__name__)

COLORS: dict[Activity, tuple[int, int, int]] = {
    Activity.ONLINE: (46, 160, 67),
    Activity.CONNECTED: (46, 160, 67),
    Activity.CONNECTING: (31, 111, 235),
    Activity.PHONE_NEARBY: (210, 153, 34),
    Activity.SEARCHING: (110, 118, 129),
    Activity.COOLDOWN: (110, 118, 129),
    Activity.OFF: (72, 79, 88),
    Activity.ERROR: (218, 54, 51),
}


class TrayUnavailable(OpenHotspotError):
    """pystray/Pillow are not installed, or there is no usable tray in this session."""


@dataclass
class MenuEntry:
    label: str
    action: Callable[[], None] | None = None
    checked: bool | None = None  # radio-style marker
    enabled: bool = True


class TrayModel:
    """What the tray shows and what its entries do, independent of any GUI toolkit."""

    def __init__(self, daemon: Daemon, quit_: Callable[[], None]) -> None:
        self.daemon, self._quit = daemon, quit_

    def title(self) -> str:
        d = self.daemon
        detail = f": {d.detail}" if d.detail else ""
        return f"OpenHotspot, {d.activity.value}{detail}"[:120]

    def color(self) -> tuple[int, int, int]:
        return COLORS[self.daemon.activity]

    def entries(self) -> list[MenuEntry]:
        d = self.daemon
        items = [
            MenuEntry(self.title(), enabled=False),
            MenuEntry("Connect now", d.request_connect, enabled=d.activity != Activity.CONNECTING),
        ]
        for mode, label in ((Mode.AUTO, "Auto: connect by itself"), (Mode.MANUAL, "Manual: only notify"),
                            (Mode.OFF, "Off")):
            items.append(MenuEntry(label, partial(d.set_mode, mode), checked=d.mode == mode))
        items.append(MenuEntry("Quit", self._quit))
        return items


def draw_icon(color: tuple[int, int, int]):  # pragma: no cover - needs Pillow
    from PIL import Image, ImageDraw

    size = 64
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse((2, 2, size - 2, size - 2), fill=color + (255,))
    for r in (10, 20, 30):  # three arcs and a dot: the usual Wi-Fi glyph
        d.arc((size // 2 - r, size // 2 + 14 - r, size // 2 + r, size // 2 + 14 + r), 225, 315, fill=(255, 255, 255, 255), width=4)
    d.ellipse((size // 2 - 4, size // 2 + 10, size // 2 + 4, size // 2 + 18), fill=(255, 255, 255, 255))
    return img


def run_tray(daemon: Daemon) -> None:  # pragma: no cover - needs a graphical session
    """Show the tray icon and block until the user chooses Quit (or SIGINT/SIGTERM arrives)."""
    try:
        import pystray
    except Exception as exc:  # ImportError, or pystray finding no backend ("no display")
        raise TrayUnavailable(
            f"the tray needs the optional extra (pip install 'openhotspot[tray]') and a graphical session: {exc}"
        ) from exc

    holder: list = []

    def self_stop() -> None:
        daemon.stop()
        if holder:
            holder[0].stop()

    model = TrayModel(daemon, self_stop)

    def action_for(fn: Callable[[], None] | None):
        return (lambda icon, item: fn()) if fn else (lambda icon, item: None)  # pystray counts parameters

    def checked_for(value: bool | None):
        return None if value is None else (lambda item: value)

    def build_menu() -> pystray.Menu:
        return pystray.Menu(*[
            pystray.MenuItem(e.label, action_for(e.action), checked=checked_for(e.checked),
                             radio=e.checked is not None, enabled=e.enabled)
            for e in model.entries()
        ])

    icon = pystray.Icon("openhotspot", draw_icon(model.color()), model.title(), build_menu())
    holder.append(icon)

    def refresh(_activity: Activity, _detail: str) -> None:
        icon.icon, icon.title, icon.menu = draw_icon(model.color()), model.title(), build_menu()  # menu setter refreshes

    daemon.on_change(refresh)
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, lambda *_: model._quit())
    try:
        icon.run()
    except Exception as exc:
        raise TrayUnavailable(f"the tray icon could not be shown: {exc}") from exc
