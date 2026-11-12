"""Primitives from PROTOCOL.md section 1, backed by the `cryptography` package."""
from __future__ import annotations

import hashlib
import hmac
import os

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

from .constants import NONCE_LEN
from .errors import AuthenticationError


def random_bytes(n: int) -> bytes:
    return os.urandom(n)


def random_nonce() -> bytes:
    return os.urandom(NONCE_LEN)


def x25519_generate() -> bytes:
    """Return a fresh 32-byte private key."""
    return os.urandom(32)


def x25519_public(private: bytes) -> bytes:
    return X25519PrivateKey.from_private_bytes(private).public_key().public_bytes_raw()


def x25519_shared(private: bytes, peer_public: bytes) -> bytes:
    """X25519 shared secret. An all-zero result (low-order peer key) is rejected."""
    try:
        shared = X25519PrivateKey.from_private_bytes(private).exchange(
            X25519PublicKey.from_public_bytes(peer_public)
        )
    except ValueError as exc:  # bad length, or cryptography's own all-zero check
        raise AuthenticationError(f"X25519 failed: {exc}") from exc
    if hmac.compare_digest(shared, bytes(32)):
        raise AuthenticationError("X25519 produced an all-zero shared secret")
    return shared


def hkdf(salt: bytes, ikm: bytes, info: bytes, length: int = 32) -> bytes:
    """HKDF-SHA256 (RFC 5869). An empty salt means 32 zero bytes."""
    return HKDF(algorithm=hashes.SHA256(), length=length, salt=salt or None, info=info).derive(ikm)


def hmac_sha256(key: bytes, msg: bytes) -> bytes:
    return hmac.new(key, msg, hashlib.sha256).digest()


def aead_seal(key: bytes, nonce: bytes, aad: bytes, plaintext: bytes) -> bytes:
    """ChaCha20-Poly1305; returns ciphertext || 16-byte tag."""
    if len(nonce) != NONCE_LEN:
        raise ValueError("nonce must be 12 bytes")
    return ChaCha20Poly1305(key).encrypt(nonce, plaintext, aad)


def aead_open(key: bytes, nonce: bytes, aad: bytes, ciphertext_and_tag: bytes) -> bytes:
    try:
        return ChaCha20Poly1305(key).decrypt(nonce, ciphertext_and_tag, aad)
    except InvalidTag as exc:
        raise AuthenticationError("AEAD authentication failed") from exc
