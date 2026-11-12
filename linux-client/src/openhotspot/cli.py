"""Command line interface: pair, connect, status, disconnect, unpair, daemon, doctor."""
from __future__ import annotations

import argparse
import asyncio
import dataclasses
import logging
import signal
import socket
import sys
import threading
import time
from collections.abc import Callable, Sequence
from dataclasses import dataclass, field
from pathlib import Path

from . import __version__
from .ble import BleakTransport
from .client import HotspotClient
from .config import Config, default_state_path, load_config
from .connectivity import ConnectivityChecker, NmConnectivity
from .daemon import Daemon
from .doctor import FAIL, Check, format_checks, run_checks
from .errors import ConfigError, KeyStoreError, NotPairedError, OpenHotspotError, PhoneError, PhoneNotFound
from .explain import describe_error
from .flow import connect_flow, describe_hotspot
from .notify import Notifier, NotifySend, NullNotifier
from .pairing import PairingQr
from .qr import decode_qr_image, resolve_qr_argument
from .state import StateFile
from .storage import KeyStore, open_keystore
from .transport import Transport
from .tray import run_tray
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
    connectivity: ConnectivityChecker = field(default_factory=NmConnectivity)
    notifier: Notifier = field(default_factory=NullNotifier)
    config: Config = field(default_factory=Config)
    state: StateFile | None = None  # None: do not write the shared state file (tests)
    checks: Callable[[KeyStore], list[Check]] = run_checks
    tray: Callable[[Daemon], None] = run_tray


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="openhotspot", description="Turn on a paired Android phone's hotspot over BLE.")
    p.add_argument("--version", action="version", version=f"openhotspot {__version__}")
    p.add_argument("--keystore", choices=["auto", "file", "keyring"], default="auto",
                   help="where pairing keys are stored (default: keyring if available, else ~/.config/openhotspot)")
    p.add_argument("--config-dir", type=Path, help="directory for the file key store")
    p.add_argument("--config", type=Path, help="TOML config file (default: ~/.config/openhotspot/config.toml)")
    p.add_argument("-v", "--verbose", action="store_true", help="log what is happening (to stderr)")
    sub = p.add_subparsers(dest="command", required=True)

    s = sub.add_parser("pair", help="pair with a phone using the QR code it shows")
    s.add_argument("qr", help="the openhotspot://pair/... string, or an image file containing the QR code")
    s.add_argument("--label", help="name shown on the phone (default: this host's name)")

    for name, text in (("connect", "turn on the phone's hotspot and join it"),
                       ("status", "show the paired phones and the hotspot state"),
                       ("disconnect", "leave the hotspot, remove its Wi-Fi connection and turn it off"),
                       ("unpair", "forget a phone (also asks it to forget us)")):
        s = sub.add_parser(name, help=text)
        s.add_argument("--phone", help="phone id (needed only if several are paired)")
        if name == "connect":
            s.add_argument("--auto-off", type=int, default=None, metavar="SECONDS", help="ask the phone to turn the hotspot off after this long")
            s.add_argument("--no-verify", action="store_true", help="do not check the internet connection after joining")
        if name == "status":
            s.add_argument("--show-password", action="store_true", help="print the hotspot passphrase")
        if name == "disconnect":
            s.add_argument("--keep-hotspot", action="store_true", help="only leave the Wi-Fi network; do not ask the phone to turn it off")

    s = sub.add_parser("daemon", help="run in the background: connect by itself when offline and the phone is near")
    s.add_argument("--mode", choices=["auto", "manual", "off"], help="override the config file's mode")
    s.add_argument("--once", action="store_true", help="do one check, print what happened, and exit")
    s.add_argument("--tray", action="store_true", help="show a tray icon (needs: pip install 'openhotspot[tray]')")
    sub.add_parser("doctor", help="check Bluetooth, NetworkManager, clock and pairing prerequisites")
    return p


_describe = describe_hotspot


async def _run(args: argparse.Namespace, deps: Deps) -> int:
    cfg = deps.config
    client = HotspotClient(deps.transport, deps.store, clock=deps.clock, attempts=cfg.ble.attempts)
    out = print

    if args.command == "doctor":
        checks = deps.checks(deps.store)
        out(format_checks(checks))
        return EXIT_ERROR if any(c.status == FAIL for c in checks) else EXIT_OK

    if args.command == "pair":
        qr = PairingQr.parse(resolve_qr_argument(args.qr, deps.qr_decoder))
        phone = await client.pair(qr, args.label or deps.hostname[:32])
        out(f"Paired with phone {phone.phone_id}. Run `openhotspot connect` to use it.")
        return EXIT_OK

    if args.command == "daemon":  # not being paired yet is reported by the daemon itself, not fatal
        return await _daemon(args, deps, client)

    phone = deps.store.resolve(args.phone or cfg.phone or None)

    if args.command == "connect":
        await connect_flow(
            client, phone, deps.wifi, deps.connectivity,
            auto_off_s=cfg.connect.auto_off_s if args.auto_off is None else args.auto_off,
            verify=cfg.connect.verify and not args.no_verify, verify_timeout_s=cfg.connect.verify_timeout_s,
            say=out,
        )
        out("Connected." + ("" if args.no_verify or not cfg.connect.verify else " The internet is reachable."))
        return EXIT_OK

    if args.command == "status":
        for p in deps.store.list():
            out(f"phone {p.phone_id}{' (selected)' if p.phone_id == phone.phone_id else ''}: paired as '{p.label}', {p.counter} requests sent")
        out(f"this laptop's connectivity: {(await asyncio.to_thread(deps.connectivity.check)).value}")
        status = await client.read_status(phone)
        out(f"status (unauthenticated hint): ready={status.ready} pairing_open={status.pairing_open} hotspot_active={status.hotspot_active}")
        info = await client.query(phone)
        out(f"hotspot: {_describe(info)}")
        if args.show_password and info.passphrase:
            out(f"passphrase: {info.passphrase}")
        return EXIT_OK

    if args.command == "disconnect":
        left = await asyncio.to_thread(deps.wifi.disconnect, None)
        out("Left the hotspot network and removed its Wi-Fi connection." if left else "Was not connected to an OpenHotspot network.")
        if deps.state is not None:  # tell a running daemon not to jump straight back in
            deps.state.suppress(deps.clock() + cfg.daemon.disconnect_pause_s)
        if not args.keep_hotspot:
            await client.disable(phone)
            out("Asked the phone to turn the hotspot off.")
        return EXIT_OK

    if args.command == "unpair":
        acked = await client.unpair(phone)
        out(f"Forgot phone {phone.phone_id}." + ("" if acked else " The phone could not be reached, so remove this laptop in the phone app too."))
        return EXIT_OK

    return EXIT_USAGE  # unreachable: argparse requires a command


def _make_daemon(args: argparse.Namespace, deps: Deps, client: HotspotClient) -> Daemon:
    cfg = deps.config
    if args.mode:
        cfg = dataclasses.replace(cfg, daemon=dataclasses.replace(cfg.daemon, mode=args.mode))
    return Daemon(client, deps.store, deps.wifi, deps.connectivity, cfg, notifier=deps.notifier,
                  state=deps.state, clock=deps.clock)


async def _daemon(args: argparse.Namespace, deps: Deps, client: HotspotClient) -> int:
    daemon = _make_daemon(args, deps, client)
    if args.once:
        result = await daemon.tick()
        print(f"{result.activity.value}" + (f": {result.detail}" if result.detail else ""))
        return EXIT_ERROR if result.activity.value == "error" else EXIT_OK
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, stop.set)
    await daemon.run(stop)
    return EXIT_OK


def _daemon_with_tray(args: argparse.Namespace, deps: Deps) -> int:
    """The asyncio daemon runs in a worker thread; the tray owns the main thread (GUI toolkits insist)."""
    client = HotspotClient(deps.transport, deps.store, clock=deps.clock, attempts=deps.config.ble.attempts)
    daemon = _make_daemon(args, deps, client)
    worker = threading.Thread(target=lambda: asyncio.run(daemon.run()), name="daemon", daemon=True)
    worker.start()
    try:
        deps.tray(daemon)  # blocks until the user chooses Quit
    finally:
        daemon.stop()
        worker.join(15)
    return EXIT_OK


def _setup_logging(verbose: bool, command: str) -> None:
    level = logging.DEBUG if verbose else (logging.INFO if command == "daemon" else logging.WARNING)
    logging.basicConfig(level=level, format="%(levelname)s %(name)s: %(message)s", stream=sys.stderr, force=True)
    logging.getLogger("bleak").setLevel(logging.INFO if not verbose else logging.DEBUG)
    logging.getLogger("dbus_fast").setLevel(logging.WARNING)


def build_deps(args: argparse.Namespace) -> Deps:
    config = load_config(args.config)
    ble = config.ble
    transport = BleakTransport(adapter=ble.adapter, passive=ble.passive_scan, connect_timeout=ble.connect_timeout_s,
                               connect_attempts=ble.attempts)
    notifier: Notifier = NotifySend() if config.notifications.enabled else NullNotifier()
    return Deps(open_keystore(args.keystore, args.config_dir), transport, NmcliConnector(),
                connectivity=NmConnectivity(probe_url=config.connect.probe_url), notifier=notifier, config=config,
                state=StateFile(default_state_path()))


def main(argv: Sequence[str] | None = None, deps: Deps | None = None) -> int:
    args = build_parser().parse_args(argv)
    _setup_logging(args.verbose, args.command)
    try:
        if deps is None:
            deps = build_deps(args)
        if args.command == "daemon" and args.tray and not args.once:
            return _daemon_with_tray(args, deps)
        return asyncio.run(_run(args, deps))
    except ConfigError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return EXIT_USAGE
    except NotPairedError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return EXIT_NOT_PAIRED
    except (PhoneNotFound, PhoneError) as exc:
        print(f"error: {describe_error(exc)}", file=sys.stderr)
        return EXIT_ERROR
    except KeyboardInterrupt:
        return EXIT_ERROR
    except (KeyStoreError, OpenHotspotError) as exc:  # includes Wifi, Bluetooth, Pairing, Connectivity errors
        print(f"error: {exc}", file=sys.stderr)
        return EXIT_ERROR


if __name__ == "__main__":
    sys.exit(main())
