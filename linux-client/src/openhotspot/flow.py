"""The connect sequence shared by the CLI and the daemon: enable, wait for ON, join, verify."""
from __future__ import annotations

import asyncio
import time
from collections.abc import Awaitable, Callable

from .advertisement import Advertisement
from .client import HotspotClient
from .connectivity import Connectivity, ConnectivityChecker
from .constants import HotspotState
from .errors import ConnectivityError, HotspotNotUp
from .messages import HotspotInfo
from .pairing import PairedPhone
from .wifi import WifiConnector


def describe_hotspot(info: HotspotInfo) -> str:
    ssid = info.ssid.decode(errors="replace")
    return f"{info.state.name}, ssid '{ssid}', {info.security.name}, {info.band.name}" if info.ssid else info.state.name


async def connect_flow(
    client: HotspotClient, phone: PairedPhone, wifi: WifiConnector, connectivity: ConnectivityChecker, *,
    adv: Advertisement | None = None, auto_off_s: int = 0, verify: bool = True, verify_timeout_s: float = 30.0,
    poll_s: float = 2.0, say: Callable[[str], None] = lambda m: None,
    sleep: Callable[[float], Awaitable[None]] = asyncio.sleep, monotonic: Callable[[], float] = time.monotonic,
) -> HotspotInfo:
    """Raises ``HotspotNotUp`` if the phone's hotspot does not reach ON, ``WifiError`` if joining
    fails and ``ConnectivityError`` if the network was joined but the internet is not reachable
    (the connection is then left in place; callers decide what to do with it)."""
    info = await client.enable(phone, auto_off_s, adv=adv)
    for _ in range(5):  # STARTING: poll briefly
        if info.state == HotspotState.ON:
            break
        await sleep(1)
        info = await client.query(phone)
    if info.state != HotspotState.ON or not info.ssid:
        raise HotspotNotUp(f"Hotspot did not come up (state {info.state.name}).")
    say(f"Hotspot on: {describe_hotspot(info)}. Joining...")
    await asyncio.to_thread(wifi.connect, info.ssid, info.passphrase, info.security, info.band)
    if not verify:
        return info
    say("Joined. Checking the internet connection...")
    deadline = monotonic() + verify_timeout_s
    last = Connectivity.UNKNOWN
    while True:
        last = await asyncio.to_thread(connectivity.check, True)
        if last == Connectivity.FULL:
            return info
        if monotonic() >= deadline:
            break
        await sleep(poll_s)
    raise ConnectivityError(
        f"joined '{info.ssid.decode(errors='replace')}' but the internet is not reachable (state: {last.value}). "
        "The phone may have no mobile data, or its hotspot may be in a local-only mode.")
