"""Command line interface: `openhotspot pair | connect | status | disconnect | unpair`."""
from __future__ import annotations

import argparse
import asyncio
import socket
import sys
import time
from collections.abc import Callable, Sequence
from dataclasses import dataclass, field
from pathlib import Path

from . import __version__
from .client import HotspotClient
from .constants import ErrorCode, HotspotState
from .errors import KeyStoreError, NotPairedError, OpenHotspotError, PairingError, PhoneError, PhoneNotFound, WifiError
from .pairing import PairingQr
from .qr import decode_qr_image, resolve_qr_argument
from .storage import KeyStore, open_keystore
from .transport import BleTransport, Transport
from .wifi import NmcliConnector, WifiConnector

EXIT_OK, EXIT_ERROR, EXIT_USAGE, EXIT_NOT_PAIRED = 0, 1, 2, 3


@dataclass
class Deps:
    """Everything the CLI touches outside itself, so tests and the demo can inject fakes."""

    store: KeyStore
    transport: Transport
    wifi: WifiConnector
    clock: Callable[[], float] = time.time
    qr_decoder: Callable[[str], str] = decode_qr_image
    hostname: str = field(default_factory=socket.gethostname)


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="openhotspot", description="Turn on a paired Android phone's hotspot over BLE.")
    p.add_argument("--version", action="version", version=f"openhotspot {__version__}")
    p.add_argument("--keystore", choices=["auto", "file", "keyring"], default="auto",
                   help="where pairing keys are stored (default: keyring if available, else ~/.config/openhotspot)")
    p.add_argument("--config-dir", type=Path, help="directory for the file key store")
    sub = p.add_subparsers(dest="command", required=True)

    s = sub.add_parser("pair", help="pair with a phone using the QR code it shows")
    s.add_argument("qr", help="the openhotspot://pair/... string, or an image file containing the QR code")
    s.add_argument("--label", help="name shown on the phone (default: this host's name)")

    for name, text in (("connect", "turn on the phone's hotspot and join it"),
                       ("status", "show the paired phones and the hotspot state"),
                       ("disconnect", "leave the hotspot and turn it off"),
                       ("unpair", "forget a phone (also asks it to forget us)")):
        s = sub.add_parser(name, help=text)
        s.add_argument("--phone", help="phone id (needed only if several are paired)")
        if name == "connect":
            s.add_argument("--auto-off", type=int, default=0, metavar="SECONDS", help="ask the phone to turn the hotspot off after this long")
        if name == "status":
            s.add_argument("--show-password", action="store_true", help="print the hotspot passphrase")
        if name == "disconnect":
            s.add_argument("--keep-hotspot", action="store_true", help="only leave the Wi-Fi network; do not ask the phone to turn it off")
    return p


def _describe(info) -> str:
    ssid = info.ssid.decode(errors="replace")
    return f"{info.state.name}, ssid '{ssid}', {info.security.name}, {info.band.name}" if info.ssid else info.state.name


async def _run(args: argparse.Namespace, deps: Deps) -> int:
    client = HotspotClient(deps.transport, deps.store, clock=deps.clock)
    out = print

    if args.command == "pair":
        qr = PairingQr.parse(resolve_qr_argument(args.qr, deps.qr_decoder))
        phone = await client.pair(qr, args.label or deps.hostname[:32])
        out(f"Paired with phone {phone.phone_id}. Run `openhotspot connect` to use it.")
        return EXIT_OK

    phone = deps.store.resolve(args.phone)

    if args.command == "connect":
        info = await client.enable(phone, args.auto_off)
        if info.state != HotspotState.ON:
            for _ in range(5):  # STARTING: poll briefly
                await asyncio.sleep(1)
                info = await client.query(phone)
                if info.state == HotspotState.ON:
                    break
        if info.state != HotspotState.ON or not info.ssid:
            out(f"Hotspot did not come up (state {info.state.name}).", file=sys.stderr)
            return EXIT_ERROR
        out(f"Hotspot on: {_describe(info)}. Joining...")
        await asyncio.to_thread(deps.wifi.connect, info.ssid, info.passphrase, info.security)
        out("Connected.")
        return EXIT_OK

    if args.command == "status":
        for p in deps.store.list():
            out(f"phone {p.phone_id}{' (selected)' if p.phone_id == phone.phone_id else ''}: paired as '{p.label}', {p.counter} requests sent")
        status = await client.read_status(phone)
        out(f"status (unauthenticated hint): ready={status.ready} pairing_open={status.pairing_open} hotspot_active={status.hotspot_active}")
        info = await client.query(phone)
        out(f"hotspot: {_describe(info)}")
        if args.show_password and info.passphrase:
            out(f"passphrase: {info.passphrase}")
        return EXIT_OK

    if args.command == "disconnect":
        left = await asyncio.to_thread(deps.wifi.disconnect, None)
        out("Left the hotspot network." if left else "Was not connected to an OpenHotspot network.")
        if not args.keep_hotspot:
            await client.disable(phone)
            out("Asked the phone to turn the hotspot off.")
        return EXIT_OK

    if args.command == "unpair":
        acked = await client.unpair(phone)
        out(f"Forgot phone {phone.phone_id}." + ("" if acked else " The phone could not be reached, so remove this laptop in the phone app too."))
        return EXIT_OK
    return EXIT_USAGE  # unreachable: argparse requires a command


def main(argv: Sequence[str] | None = None, deps: Deps | None = None) -> int:
    args = build_parser().parse_args(argv)
    if deps is None:
        deps = Deps(open_keystore(args.keystore, args.config_dir), BleTransport(), NmcliConnector())
    try:
        return asyncio.run(_run(args, deps))
    except NotPairedError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return EXIT_NOT_PAIRED
    except KeyStoreError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return EXIT_ERROR
    except PhoneNotFound as exc:
        print(f"error: {exc}. Is the phone nearby with Bluetooth on and the app running?", file=sys.stderr)
        return EXIT_ERROR
    except PhoneError as exc:
        hint = "" if exc.authenticated else " (unauthenticated: could be a forged message)"
        extra = " Wait and retry." if exc.code == ErrorCode.RATE_LIMITED else ""
        print(f"error: {exc}{hint}.{extra}", file=sys.stderr)
        return EXIT_ERROR
    except NotImplementedError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return EXIT_ERROR
    except (PairingError, WifiError, OpenHotspotError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return EXIT_ERROR


if __name__ == "__main__":
    sys.exit(main())
