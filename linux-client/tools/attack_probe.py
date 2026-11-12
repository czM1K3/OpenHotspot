#!/usr/bin/env python3
"""Send deliberately bad requests to a REAL paired phone and check that it refuses them correctly.

For the manual hardware checklist (docs/TESTING.md, "Security scenarios"). Every scenario prints
PASS or FAIL against the behaviour required by docs/PROTOCOL.md and docs/SECURITY.md.

    python tools/attack_probe.py replay          # same QUERY twice: second must be REPLAY (authenticated)
    python tools/attack_probe.py stale           # timestamp 2 minutes old: STALE_TIMESTAMP (authenticated)
    python tools/attack_probe.py tamper          # one flipped bit: AUTH_FAILED (plain), no effect
    python tools/attack_probe.py version         # version byte 2: UNSUPPORTED_VERSION (plain)
    python tools/attack_probe.py hold            # connect and stay silent: phone must drop us after ~10 s
    python tools/attack_probe.py lockout --yes   # 6 junk frames: RATE_LIMITED. LOCKS THE PHONE FOR 30 s+

It uses the real key store and BLE stack, so run it where `openhotspot connect` works. It advances
the stored request counter like a normal client, so it does not cause REPLAY errors afterwards. The
`lockout` scenario also makes your own clients fail for 30 s: wait it out.
"""
from __future__ import annotations

import argparse
import asyncio
import os
import sys
import time
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from openhotspot.chunking import Reassembler, chunk_frame  # noqa: E402
from openhotspot.client import HotspotClient  # noqa: E402
from openhotspot.constants import ErrorCode, MsgType  # noqa: E402
from openhotspot.errors import OpenHotspotError, PhoneError, TransportError  # noqa: E402
from openhotspot.framing import Frame  # noqa: E402
from openhotspot.session import build_request, parse_response  # noqa: E402
from openhotspot.transport import Transport  # noqa: E402

REPLY_TIMEOUT_S = 8.0


@dataclass
class Result:
    scenario: str
    ok: bool
    detail: str

    def __str__(self) -> str:
        return f"{'PASS' if self.ok else 'FAIL'}  {self.scenario}: {self.detail}"


async def _send_raw(transport: Transport, adv, raw: bytes, timeout: float = REPLY_TIMEOUT_S) -> Frame | None:
    """One fresh connection: write a raw frame, return the reply frame (None if the phone stays silent or hangs up)."""
    conn = await transport.connect(adv)
    try:
        for chunk in chunk_frame(raw, conn.mtu):
            await conn.write_request(chunk)
        reasm = Reassembler()
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            data = await conn.read_response_chunk(max(0.1, end - time.monotonic()))
            frame = reasm.feed(data)
            if frame is not None:
                return Frame.decode(frame)
    except TransportError:
        return None
    finally:
        await conn.close()
    return None


def _outcome(frame: Frame | None, keys, request: tuple[int, int, bytes, int] | None) -> tuple[str, bool]:
    """Classify a reply as (name, authenticated). request = (type, msg_id, nonce, counter) for decryption."""
    if frame is None:
        return "no reply", False
    if frame.type == MsgType.ERROR and frame.plaintext:
        from openhotspot.messages import unpack_error_plain

        code, retry = unpack_error_plain(frame.body)
        return f"{ErrorCode(code).name}{f' retry {retry}s' if retry else ''} (plain)", False
    if request is None:
        return f"type 0x{frame.type:02x} (not decrypted)", True
    rtype, msg_id, nonce, counter = request
    try:
        parse_response(keys.k_resp, frame, request_type=rtype, request_msg_id=msg_id, request_nonce=nonce, request_counter=counter)
    except PhoneError as exc:
        return f"{exc.code.name if hasattr(exc.code, 'name') else exc.code} (authenticated)", True
    except OpenHotspotError as exc:
        return f"undecodable: {exc}", False
    return "OK (authenticated response)", True


class Probe:
    def __init__(self, transport: Transport, client: HotspotClient, phone, clock=time.time) -> None:
        self.transport, self.client, self.phone, self.clock = transport, client, phone, clock

    async def _adv(self):
        adv = await self.client.find_phone(self.phone, 15.0)
        if adv is None:
            raise OpenHotspotError("phone not found nearby; wake it (screen on, service running)")
        return adv

    def _next_counter(self) -> int:
        self.phone.counter += 1
        self.client.store.save(self.phone)  # persisted before sending, like a real client
        return self.phone.counter

    def _request(self, ts_ms: int | None = None) -> tuple[bytes, tuple[int, int, bytes, int]]:
        counter = self._next_counter()
        msg_id = int.from_bytes(os.urandom(2), "big")
        ts = int(self.clock() * 1000) if ts_ms is None else ts_ms
        frame, nonce = build_request(self.phone.keys.k_req, MsgType.QUERY, msg_id, ts, counter)
        return frame.encode(), (MsgType.QUERY, msg_id, nonce, counter)

    async def replay(self) -> Result:
        adv = await self._adv()
        raw, req = self._request()
        first, _ = _outcome(await _send_raw(self.transport, adv, raw), self.phone.keys, req)
        second, auth = _outcome(await _send_raw(self.transport, adv, raw), self.phone.keys, req)
        ok = first.startswith("OK") and second.startswith("REPLAY") and auth
        return Result("replay", ok, f"first send: {first}; identical re-send: {second}")

    async def stale(self) -> Result:
        adv = await self._adv()
        raw, req = self._request(ts_ms=int((self.clock() - 120) * 1000))
        out, auth = _outcome(await _send_raw(self.transport, adv, raw), self.phone.keys, req)
        return Result("stale timestamp", out.startswith("STALE_TIMESTAMP") and auth, out)

    async def tamper(self) -> Result:
        adv = await self._adv()
        raw, req = self._request()
        bad = bytearray(raw)
        bad[-1] ^= 0x01
        out, auth = _outcome(await _send_raw(self.transport, adv, bytes(bad)), self.phone.keys, req)
        return Result("tampered ciphertext", out.startswith("AUTH_FAILED") and not auth, out)

    async def version(self) -> Result:
        adv = await self._adv()
        raw, req = self._request()
        out, auth = _outcome(await _send_raw(self.transport, adv, bytes([2]) + raw[1:]), self.phone.keys, req)
        return Result("wrong version byte", out.startswith("UNSUPPORTED_VERSION") and not auth, out)

    async def hold(self) -> Result:
        """An unauthenticated peer that sends nothing must be dropped about 10 s after connecting (SECURITY.md section 3)."""
        adv = await self._adv()
        conn = await self.transport.connect(adv)
        start = time.monotonic()
        try:
            await conn.read_response_chunk(25.0)
            dropped = False  # got data we did not ask for
        except TransportError as exc:
            dropped = "timeout" not in str(exc).lower() and "no response" not in str(exc).lower()
        finally:
            elapsed = time.monotonic() - start
            await conn.close()
        ok = dropped and 8.0 <= elapsed <= 14.0
        return Result("silent unauthenticated peer", ok, f"disconnected by the phone after {elapsed:.1f} s (expected about 10 s)" if dropped
                      else f"still connected after {elapsed:.1f} s: the 10 s deadline is NOT enforced")

    async def lockout(self, count: int = 6) -> Result:
        adv = await self._adv()
        seen: list[str] = []
        for _ in range(count):
            junk = Frame(MsgType.ENABLE, 1, os.urandom(45)).encode()
            out, _ = _outcome(await _send_raw(self.transport, adv, junk, timeout=5.0), self.phone.keys, None)
            seen.append(out)
        ok = any(s.startswith("RATE_LIMITED") for s in seen)
        return Result("lockout after repeated failures", ok, "; ".join(seen) + (" (now locked out: wait for retry_after)" if ok else ""))


SCENARIOS = ("replay", "stale", "tamper", "version", "hold", "lockout")


async def run(probe: Probe, scenario: str) -> list[Result]:
    names = SCENARIOS[:-1] if scenario == "all" else (scenario,)
    return [await getattr(probe, n)() for n in names]


def main(argv: list[str] | None = None) -> int:
    from openhotspot.ble import BleakTransport
    from openhotspot.storage import open_keystore

    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("scenario", choices=(*SCENARIOS, "all"), help="'all' runs everything except lockout")
    p.add_argument("--phone", help="phone id (if several are paired)")
    p.add_argument("--keystore", default="auto", choices=("auto", "file", "keyring"))
    p.add_argument("--yes", action="store_true", help="required for 'lockout': it blocks the phone for 30 s or more")
    args = p.parse_args(argv)
    if args.scenario == "lockout" and not args.yes:
        p.error("lockout makes the phone refuse everyone for 30 s or more: add --yes if you mean it")
    store = open_keystore(args.keystore)
    phone = store.resolve(args.phone)
    transport = BleakTransport()
    probe = Probe(transport, HotspotClient(transport, store), phone)
    try:
        results = asyncio.run(run(probe, args.scenario))
    except OpenHotspotError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    for r in results:
        print(r)
    return 0 if all(r.ok for r in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
