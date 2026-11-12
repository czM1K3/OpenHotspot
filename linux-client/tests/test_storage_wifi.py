import os
import stat

import pytest

from openhotspot.errors import KeyStoreError
from openhotspot.pairing import PairedPhone
from openhotspot.storage import FileKeyStore, KeyringKeyStore, open_keystore


def phone(pid="aabbccdd", counter=0):
    return PairedPhone(pid, bytes(range(32)), bytes(range(32, 64)), "laptop", counter, 1234)


# ---- key storage -----------------------------------------------------------------------------

def test_file_store_roundtrip_and_permissions(tmp_path):
    store = FileKeyStore(tmp_path / "openhotspot")
    assert store.list() == []
    store.save(phone())
    assert stat.S_IMODE(store.path.stat().st_mode) == 0o600
    assert stat.S_IMODE(store.directory.stat().st_mode) == 0o700
    assert store.get("aabbccdd") == phone()
    store.save(phone(counter=5))  # update in place
    assert store.get("aabbccdd").counter == 5 and len(store.list()) == 1
    assert stat.S_IMODE(store.path.stat().st_mode) == 0o600
    assert [p.name for p in store.directory.iterdir()] == ["phones.json"]  # no temp files left behind


def test_file_store_refuses_a_world_readable_key_file(tmp_path):
    store = FileKeyStore(tmp_path)
    store.save(phone())
    os.chmod(store.path, 0o644)
    with pytest.raises(KeyStoreError, match="chmod 600"):
        store.list()


def test_file_store_corrupt_file(tmp_path):
    store = FileKeyStore(tmp_path)
    store.save(phone())
    store.path.write_text("{not json")
    with pytest.raises(KeyStoreError):
        store.list()


def test_default_dir_honours_xdg(monkeypatch, tmp_path):
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    assert FileKeyStore().directory == tmp_path / "openhotspot"


def test_resolve_rules(tmp_path):
    store = FileKeyStore(tmp_path)
    with pytest.raises(KeyStoreError, match="no paired phone"):
        store.resolve()
    store.save(phone("aaaa1111")); store.save(phone("bbbb2222"))
    with pytest.raises(KeyStoreError, match="several"):
        store.resolve()
    assert store.resolve("bbbb").phone_id == "bbbb2222"
    with pytest.raises(KeyStoreError):
        store.resolve("zzzz")
    assert store.delete("aaaa1111") and not store.delete("aaaa1111")
    assert store.resolve().phone_id == "bbbb2222"


class FakeKeyring:
    def __init__(self):
        self.items = {}

    def get_password(self, s, u):
        return self.items.get((s, u))

    def set_password(self, s, u, p):
        self.items[(s, u)] = p


def test_keyring_store():
    kr = FakeKeyring()
    store = KeyringKeyStore(kr)
    store.save(phone())
    assert store.get("aabbccdd") == phone()
    assert list(kr.items) == [("openhotspot", "phones")]
    assert KeyringKeyStore(kr).list() == [phone()]  # persisted in the keyring, not on disk


def test_keyring_errors_are_wrapped():
    class Broken(FakeKeyring):
        def get_password(self, s, u):
            raise RuntimeError("locked")
    with pytest.raises(KeyStoreError, match="locked"):
        KeyringKeyStore(Broken()).list()


def test_open_keystore_kinds(tmp_path):
    assert isinstance(open_keystore("file", tmp_path), FileKeyStore)
    with pytest.raises(KeyStoreError):
        open_keystore("bogus")
