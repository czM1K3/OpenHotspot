"""A fake bleak/BlueZ stack in front of FakePhone, so BleakTransport runs its real code paths."""
from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
from typing import Any

from openhotspot.constants import CHAR_REQUEST_UUID, CHAR_RESPONSE_UUID, CHAR_STATUS_UUID, SERVICE_UUID
from tests.fake_phone import FakePhone


@dataclass
class FakeDevice:
    address: str


@dataclass
class FakeAdvData:
    service_data: dict[str, bytes]
    rssi: int = -60


class FakeChar:
    """The property is read live, like BlueZ's, so it can change after the connection is up."""

    def __init__(self, ble: FakeBle) -> None:
        self._ble = ble

    @property
    def max_write_without_response_size(self) -> int:
        return self._ble.next_char_size()


class FakeServices:
    def __init__(self, ble: FakeBle) -> None:
        self._ble = ble

    def get_service(self, uuid: str) -> Any:
        return object() if self._ble.has_service and uuid == SERVICE_UUID else None

    def get_characteristic(self, uuid: str) -> Any:
        if uuid != CHAR_REQUEST_UUID or not self._ble.has_request_char:
            return None
        return FakeChar(self._ble)


@dataclass
class FakeBle:
    """Knobs for the tests, plus the factories handed to BleakTransport."""

    phone: FakePhone
    mtu: int = 247
    has_service: bool = True
    has_request_char: bool = True
    char_sizes: list[int] = field(default_factory=list)  # successive max_write_without_response_size values
    connect_errors: list[BaseException] = field(default_factory=list)  # raised by successive connect() calls
    scan_errors: list[BaseException] = field(default_factory=list)  # raised by successive scanner.start() calls
    drop_after_writes: int | None = None  # simulate the phone dropping the link after N writes (once)
    swallow_notifications: bool = False
    extra_adverts: list[tuple[str, dict[str, bytes]]] = field(default_factory=list)
    clients: list[FakeClient] = field(default_factory=list)
    scanners: list[FakeScanner] = field(default_factory=list)
    connect_calls: int = 0

    def next_char_size(self) -> int:
        if self.char_sizes:
            return self.char_sizes.pop(0) if len(self.char_sizes) > 1 else self.char_sizes[0]
        return self.mtu - 3

    def scanner_factory(self, callback, passive: bool) -> FakeScanner:
        scanner = FakeScanner(self, callback, passive)
        self.scanners.append(scanner)
        return scanner

    def client_factory(self, target, disconnected_callback, timeout) -> FakeClient:
        client = FakeClient(self, target, disconnected_callback)
        self.clients.append(client)
        return client


class FakeScanner:
    def __init__(self, ble: FakeBle, callback, passive: bool) -> None:
        self.ble, self.callback, self.passive = ble, callback, passive
        self.started = self.stopped = False

    async def start(self) -> None:
        if self.ble.scan_errors:
            raise self.ble.scan_errors.pop(0)
        self.started = True
        loop = asyncio.get_running_loop()
        for addr, sd in self.ble.extra_adverts:
            loop.call_soon(self.callback, FakeDevice(addr), FakeAdvData(sd))
        for adv in self.ble.phone.advertisements():
            loop.call_soon(self.callback, FakeDevice(adv.address), FakeAdvData({SERVICE_UUID: adv.service_data}))

    async def stop(self) -> None:
        self.stopped = True


class FakeClient:
    def __init__(self, ble: FakeBle, target, disconnected_callback) -> None:
        self.ble, self.target, self._on_disconnect = ble, target, disconnected_callback
        self.services = FakeServices(ble)
        self.connected = False
        self.notify_cb = None
        self.writes = 0
        self.link = None

    async def connect(self) -> None:
        self.ble.connect_calls += 1
        if self.ble.connect_errors:
            raise self.ble.connect_errors.pop(0)
        self.link = self.ble.phone.open_link(self.ble.mtu)
        self.connected = True

    async def start_notify(self, uuid: str, cb) -> None:
        assert uuid == CHAR_RESPONSE_UUID
        self.notify_cb = cb

    async def stop_notify(self, uuid: str) -> None:
        self.notify_cb = None

    async def write_gatt_char(self, uuid: str, data: bytes, response: bool = True) -> None:
        assert uuid == CHAR_REQUEST_UUID and response is True  # PROTOCOL.md: never Write Without Response
        if not self.connected:
            from bleak.exc import BleakError
            raise BleakError("Not connected")
        self.writes += 1
        notes = self.link.on_write(bytes(data))
        if self.ble.drop_after_writes is not None and self.writes >= self.ble.drop_after_writes:
            self.ble.drop_after_writes = None
            self.connected = False
            asyncio.get_running_loop().call_soon(self._on_disconnect, self)
            return  # the phone handled the write, but its notifications never arrive
        if not self.ble.swallow_notifications:
            loop = asyncio.get_running_loop()
            for n in notes:
                loop.call_soon(self.notify_cb, None, bytearray(n))

    async def read_gatt_char(self, uuid: str) -> bytearray:
        assert uuid == CHAR_STATUS_UUID
        return bytearray(self.link.read_status())

    async def disconnect(self) -> None:
        if self.link is not None:
            self.link.close()
        self.connected = False
