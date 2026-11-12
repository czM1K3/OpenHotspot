"""Transport interface, in-memory implementation, and the real-BLE stub (Batch 4)."""
from __future__ import annotations

import asyncio
from abc import ABC, abstractmethod
from collections.abc import Callable
from typing import Protocol

from .advertisement import Advertisement
from .errors import TransportError, TransportTimeout


class Connection(ABC):
    """One GATT connection to the phone. Chunk-level: framing/chunking is done by the caller."""

    mtu: int

    @abstractmethod
    async def write_request(self, chunk: bytes) -> None:
        """Write one chunk to the Request characteristic (Write With Response)."""

    @abstractmethod
    async def read_response_chunk(self, timeout: float) -> bytes:
        """Wait for the next Response notification. Raises TransportTimeout."""

    @abstractmethod
    async def read_status(self) -> bytes:
        """Read the 2-byte Status characteristic."""

    @abstractmethod
    async def close(self) -> None: ...


class Transport(ABC):
    @abstractmethod
    async def scan(self, matcher: Callable[[Advertisement], bool], timeout: float) -> Advertisement | None:
        """Scan for the service until ``matcher`` accepts an advertisement or ``timeout`` elapses."""

    @abstractmethod
    async def connect(self, adv: Advertisement) -> Connection:
        """Connect, negotiate the MTU, discover the service and enable notifications."""


# ---- in-memory ----------------------------------------------------------------------------

class PeripheralLink(Protocol):
    """Phone-side endpoint of one in-memory connection (implemented by the test FakePhone)."""

    closed: bool

    def on_write(self, chunk: bytes) -> list[bytes]:
        """Handle one Request write; returns the Response notifications to deliver, in order."""

    def read_status(self) -> bytes: ...

    def close(self) -> None: ...


class PhoneEndpoint(Protocol):
    def advertisements(self) -> list[Advertisement]: ...

    def open_link(self, mtu: int) -> PeripheralLink: ...


class InMemoryConnection(Connection):
    def __init__(self, link: PeripheralLink, mtu: int) -> None:
        self._link = link
        self.mtu = mtu
        self._queue: asyncio.Queue[bytes] = asyncio.Queue()
        self.written: list[bytes] = []  # every chunk written, for test inspection
        self.notified: list[bytes] = []  # every notification chunk delivered

    async def write_request(self, chunk: bytes) -> None:
        if self._link.closed:
            raise TransportError("connection closed by the phone")
        self.written.append(chunk)
        for note in self._link.on_write(chunk):
            self.notified.append(note)
            self._queue.put_nowait(note)

    async def read_response_chunk(self, timeout: float) -> bytes:
        if self._queue.empty() and self._link.closed:
            raise TransportError("connection closed by the phone")
        try:
            return await asyncio.wait_for(self._queue.get(), timeout)
        except asyncio.TimeoutError as exc:
            raise TransportTimeout("no response notification before the timeout") from exc

    async def read_status(self) -> bytes:
        if self._link.closed:
            raise TransportError("connection closed by the phone")
        return self._link.read_status()

    async def close(self) -> None:
        self._link.close()


class InMemoryTransport(Transport):
    def __init__(self, endpoint: PhoneEndpoint, mtu: int = 247) -> None:
        self._endpoint = endpoint
        self.mtu = mtu
        self.connections: list[InMemoryConnection] = []

    async def scan(self, matcher: Callable[[Advertisement], bool], timeout: float) -> Advertisement | None:
        return next((a for a in self._endpoint.advertisements() if matcher(a)), None)

    async def connect(self, adv: Advertisement) -> InMemoryConnection:
        conn = InMemoryConnection(self._endpoint.open_link(self.mtu), self.mtu)
        self.connections.append(conn)
        return conn


# ---- real BLE (Batch 4) -------------------------------------------------------------------

class BleTransport(Transport):
    """Placeholder for the BlueZ/bleak transport, planned for Batch 4."""

    async def scan(self, matcher: Callable[[Advertisement], bool], timeout: float) -> Advertisement | None:
        raise NotImplementedError("the real BLE transport is not implemented yet (Batch 4)")

    async def connect(self, adv: Advertisement) -> Connection:
        raise NotImplementedError("the real BLE transport is not implemented yet (Batch 4)")
