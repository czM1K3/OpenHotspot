"""The hardware probe (tools/attack_probe.py) run against the in-process FakePhone, so the tool itself is trusted."""
import importlib.util
import sys
from pathlib import Path

import pytest

from .test_e2e import pair

_spec = importlib.util.spec_from_file_location("attack_probe", Path(__file__).resolve().parents[1] / "tools" / "attack_probe.py")
attack_probe = importlib.util.module_from_spec(_spec)
sys.modules["attack_probe"] = attack_probe  # dataclasses needs the module registered
_spec.loader.exec_module(attack_probe)


@pytest.fixture
def probe(make_client, phone, clock):
    client, transport, store = make_client()
    paired = pair(phone, client)
    return attack_probe.Probe(transport, client, store.resolve(paired.phone_id), clock=clock)


@pytest.mark.parametrize("scenario", ["replay", "stale", "tamper", "version"])
async def test_probe_scenarios_pass_against_a_correct_phone(probe, scenario):
    (result,) = await attack_probe.run(probe, scenario)
    assert result.ok, str(result)


async def test_probe_lockout_reaches_rate_limited(probe):
    (result,) = await attack_probe.run(probe, "lockout")
    assert result.ok, str(result)
