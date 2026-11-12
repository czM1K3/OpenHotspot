"""Key storage: a 0600 JSON file in ~/.config/openhotspot/ or the `keyring` library."""
from __future__ import annotations

import json
import os
import stat
import tempfile
from abc import ABC, abstractmethod
from pathlib import Path
from typing import Any

from .errors import KeyStoreError, NotPairedError
from .pairing import PairedPhone

KEYRING_SERVICE = "openhotspot"
KEYRING_USER = "phones"
FILE_NAME = "phones.json"


def default_config_dir() -> Path:
    base = os.environ.get("XDG_CONFIG_HOME") or str(Path.home() / ".config")
    return Path(base) / "openhotspot"


def _dump(phones: dict[str, PairedPhone]) -> str:
    return json.dumps(
        {
            "version": 1,
            "phones": {
                pid: {
                    "ltk": p.ltk.hex(), "phone_pub": p.phone_pub.hex(), "label": p.label,
                    "counter": p.counter, "paired_at": p.paired_at,
                }
                for pid, p in phones.items()
            },
        },
        indent=2,
    )


def _load(text: str) -> dict[str, PairedPhone]:
    try:
        data: Any = json.loads(text)
        if data["version"] != 1:
            raise KeyStoreError(f"unsupported key store version {data['version']}")
        return {
            pid: PairedPhone(
                phone_id=pid, ltk=bytes.fromhex(v["ltk"]), phone_pub=bytes.fromhex(v["phone_pub"]),
                label=v.get("label", ""), counter=int(v["counter"]), paired_at=int(v.get("paired_at", 0)),
            )
            for pid, v in data["phones"].items()
        }
    except (KeyError, TypeError, ValueError) as exc:
        raise KeyStoreError(f"corrupt key store: {exc}") from exc


class KeyStore(ABC):
    @abstractmethod
    def _read(self) -> str | None: ...

    @abstractmethod
    def _write(self, text: str) -> None: ...

    def list(self) -> list[PairedPhone]:
        text = self._read()
        return list(_load(text).values()) if text else []

    def get(self, phone_id: str) -> PairedPhone | None:
        return next((p for p in self.list() if p.phone_id == phone_id), None)

    def save(self, phone: PairedPhone) -> None:
        text = self._read()
        phones = _load(text) if text else {}
        phones[phone.phone_id] = phone
        self._write(_dump(phones))

    def delete(self, phone_id: str) -> bool:
        text = self._read()
        phones = _load(text) if text else {}
        if phones.pop(phone_id, None) is None:
            return False
        self._write(_dump(phones))
        return True

    def resolve(self, phone_id: str | None = None) -> PairedPhone:
        """The phone named by ``phone_id`` (a unique prefix is enough), or the only paired phone."""
        phones = self.list()
        if phone_id is not None:
            matches = [p for p in phones if p.phone_id.startswith(phone_id)]
            if len(matches) != 1:
                raise NotPairedError(f"no unique paired phone matches '{phone_id}'")
            return matches[0]
        if not phones:
            raise NotPairedError("no paired phone; run `openhotspot pair <qr>` first")
        if len(phones) > 1:
            ids = ", ".join(p.phone_id for p in phones)
            raise NotPairedError(f"several paired phones ({ids}); choose one with --phone")
        return phones[0]


class FileKeyStore(KeyStore):
    """JSON file, directory 0700, file 0600, written atomically. Refuses a group/world-accessible file."""

    def __init__(self, directory: Path | None = None) -> None:
        self.directory = directory or default_config_dir()
        self.path = self.directory / FILE_NAME

    def _read(self) -> str | None:
        try:
            st = self.path.stat()
        except FileNotFoundError:
            return None
        if st.st_mode & (stat.S_IRWXG | stat.S_IRWXO):
            raise KeyStoreError(f"{self.path} is accessible by other users; run: chmod 600 {self.path}")
        return self.path.read_text()

    def _write(self, text: str) -> None:
        self.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        fd, tmp = tempfile.mkstemp(dir=self.directory, prefix=".phones-", suffix=".tmp")  # created 0600
        try:
            with os.fdopen(fd, "w") as f:
                f.write(text)
                f.flush()
                os.fsync(f.fileno())
            os.replace(tmp, self.path)
        except BaseException:
            Path(tmp).unlink(missing_ok=True)
            raise


class KeyringKeyStore(KeyStore):
    """Stores the whole JSON blob as one item in the system keyring (e.g. Secret Service)."""

    def __init__(self, keyring_module: Any = None) -> None:
        if keyring_module is None:
            try:
                import keyring as keyring_module  # type: ignore[no-redef]
            except ImportError as exc:
                raise KeyStoreError("the `keyring` package is not installed (pip install 'openhotspot[keyring]')") from exc
        self._kr = keyring_module

    def _read(self) -> str | None:
        try:
            return self._kr.get_password(KEYRING_SERVICE, KEYRING_USER)
        except Exception as exc:  # keyring raises backend-specific errors
            raise KeyStoreError(f"keyring read failed: {exc}") from exc

    def _write(self, text: str) -> None:
        try:
            self._kr.set_password(KEYRING_SERVICE, KEYRING_USER, text)
        except Exception as exc:
            raise KeyStoreError(f"keyring write failed: {exc}") from exc


def keyring_usable(keyring_module: Any = None) -> bool:
    try:
        kr = keyring_module or __import__("keyring")
        backend = kr.get_keyring()
        return type(backend).__module__ != "keyring.backends.fail" and getattr(backend, "priority", 0) > 0
    except Exception:
        return False


def open_keystore(kind: str = "auto", directory: Path | None = None) -> KeyStore:
    """``file`` | ``keyring`` | ``auto`` (keyring if a real backend exists, else the file)."""
    if kind == "file":
        return FileKeyStore(directory)
    if kind == "keyring":
        return KeyringKeyStore()
    if kind == "auto":
        return KeyringKeyStore() if keyring_usable() else FileKeyStore(directory)
    raise KeyStoreError(f"unknown key store '{kind}'")
