import asyncio
import inspect
import json
from pathlib import Path

import pytest

from openhotspot.client import HotspotClient
from openhotspot.transport import InMemoryTransport
from tests.fake_phone import FakePhone

VECTORS_PATH = Path(__file__).resolve().parents[2] / "docs" / "test-vectors.json"


class FakeClock:
    """Shared by client and phone; advance it manually."""

    def __init__(self, now: float = 1_800_000_000.0) -> None:
        self.now = now

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


@pytest.fixture(scope="session")
def vectors() -> dict:
    return json.loads(VECTORS_PATH.read_text())


@pytest.fixture
def clock() -> FakeClock:
    return FakeClock()


@pytest.fixture
def phone(clock) -> FakePhone:
    return FakePhone(clock)


@pytest.fixture
def make_client(clock, phone, tmp_path):
    """Factory: a client wired to the fake phone with an in-memory transport and a file key store."""
    from openhotspot.storage import FileKeyStore

    def _make(mtu: int = 247, phone_=None):
        transport = InMemoryTransport(phone_ or phone, mtu=mtu)
        store = FileKeyStore(tmp_path / "cfg")
        return HotspotClient(transport, store, clock=clock, monotonic=clock), transport, store

    return _make


# Run `async def` tests with asyncio.run, so no pytest plugin (and no extra dependency) is needed.
@pytest.hookimpl(tryfirst=True)
def pytest_pyfunc_call(pyfuncitem):
    if inspect.iscoroutinefunction(pyfuncitem.obj):
        kwargs = {name: pyfuncitem.funcargs[name] for name in pyfuncitem._fixtureinfo.argnames}
        asyncio.run(pyfuncitem.obj(**kwargs))
        return True
    return None
