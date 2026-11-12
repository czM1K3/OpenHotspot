"""SECURITY.md section 5: keys, SSIDs and passphrases never reach logs or reprs."""
import logging

from openhotspot.constants import HotspotState
from openhotspot.messages import HotspotInfo
from openhotspot.pairing import PairedPhone, derive_session_keys

from .test_daemon import env, tick  # noqa: F401


def test_reprs_hide_secrets():
    info = HotspotInfo(HotspotState.ON, ssid=b"MySecretNet", passphrase="hunter2hunter2")
    assert "hunter2" not in repr(info)
    phone = PairedPhone("abcd1234", bytes(range(32)), bytes(32))
    keys = derive_session_keys(phone.ltk)
    for obj in (phone, keys):
        text = repr(obj)
        assert phone.ltk.hex() not in text and repr(phone.ltk) not in text
        assert keys.k_req.hex() not in text and repr(keys.k_req) not in text


def test_daemon_logs_contain_no_credentials(env, caplog):
    caplog.set_level(logging.DEBUG)
    r = tick(env.make())
    assert r.activity.value == "connected"
    logs = caplog.text
    assert env.phone.passphrase not in logs
    assert env.phone.ssid.decode() not in logs
