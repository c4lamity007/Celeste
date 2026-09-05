from __future__ import annotations

import base64
import hashlib
import hmac
import json
import secrets
import time
from typing import Any

from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerifyMismatchError
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ed25519, x25519
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF


PASSWORD_HASHER = PasswordHasher(time_cost=3, memory_cost=65536, parallelism=2)


def b64e(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def b64d(value: str, expected: int | None = None) -> bytes:
    try:
        decoded = base64.urlsafe_b64decode(value + "===")
    except (ValueError, TypeError) as exc:
        raise ValueError("invalid base64url") from exc
    if expected is not None and len(decoded) != expected:
        raise ValueError("invalid decoded length")
    return decoded


def canonical_json(value: dict[str, Any]) -> bytes:
    return json.dumps(value, separators=(",", ":"), sort_keys=True).encode("utf-8")


def digest_hex(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def keyed_digest(pepper: bytes, domain: str, value: str) -> bytes:
    return hmac.new(pepper, domain.encode() + b"\x00" + value.encode(), hashlib.sha256).digest()


def normalize_license(value: str) -> str:
    return "".join(character for character in value.upper() if character.isalnum())


def hash_license(pepper: bytes, value: str) -> bytes:
    normalized = normalize_license(value)
    if len(normalized) < 20 or len(normalized) > 64:
        raise ValueError("invalid license")
    return keyed_digest(pepper, "license-v1", normalized)


def verify_admin_password(encoded_hash: str, password: str) -> bool:
    try:
        return PASSWORD_HASHER.verify(encoded_hash, password)
    except (VerifyMismatchError, InvalidHashError):
        return False


def verify_ed25519(public_key: bytes, message: bytes, signature: str) -> bool:
    try:
        ed25519.Ed25519PublicKey.from_public_bytes(public_key).verify(b64d(signature, 64), message)
        return True
    except (ValueError, TypeError):
        return False


def issue_token_seed(client_encryption_key: bytes, salt: bytes, aad: bytes) -> tuple[bytes, dict[str, str]]:
    token_seed = secrets.token_bytes(32)
    server_ephemeral = x25519.X25519PrivateKey.generate()
    shared = server_ephemeral.exchange(x25519.X25519PublicKey.from_public_bytes(client_encryption_key))
    wrapping_key = HKDF(
        algorithm=hashes.SHA256(),
        length=32,
        salt=salt,
        info=b"celeste-install-token-v1",
    ).derive(shared)
    nonce = secrets.token_bytes(12)
    ciphertext = AESGCM(wrapping_key).encrypt(nonce, token_seed, aad)
    ephemeral_public = server_ephemeral.public_key().public_bytes(
        serialization.Encoding.Raw,
        serialization.PublicFormat.Raw,
    )
    return token_seed, {
        "ephemeral_key": b64e(ephemeral_public),
        "nonce": b64e(nonce),
        "ciphertext": b64e(ciphertext),
    }


class GrantSigner:
    def __init__(self, private_seed: bytes) -> None:
        self._private = ed25519.Ed25519PrivateKey.from_private_bytes(private_seed)

    def public_key_b64(self) -> str:
        value = self._private.public_key().public_bytes(
            serialization.Encoding.Raw,
            serialization.PublicFormat.Raw,
        )
        return b64e(value)

    def sign(self, claims: dict[str, Any]) -> str:
        header = {"alg": "EdDSA", "kid": "celeste-release-1", "typ": "CST"}
        first = b64e(canonical_json(header))
        second = b64e(canonical_json(claims))
        signed = f"{first}.{second}".encode("ascii")
        return f"{first}.{second}.{b64e(self._private.sign(signed))}"

    def verify(self, token: str) -> dict[str, Any] | None:
        try:
            first, second, signature = token.split(".", 2)
            signed = f"{first}.{second}".encode("ascii")
            self._private.public_key().verify(b64d(signature, 64), signed)
            header = json.loads(b64d(first))
            claims = json.loads(b64d(second))
            now = int(time.time())
            if header.get("alg") != "EdDSA" or header.get("typ") != "CST":
                return None
            if int(claims.get("nbf", 0)) > now + 5 or int(claims.get("exp", 0)) <= now:
                return None
            return claims
        except (ValueError, TypeError, json.JSONDecodeError):
            return None


def session_cookie(secret: bytes, session_id: str, issued_at: int) -> str:
    body = f"{session_id}.{issued_at}"
    signature = hmac.new(secret, body.encode(), hashlib.sha256).digest()
    return f"{body}.{b64e(signature)}"


def parse_session_cookie(secret: bytes, value: str, max_age: int = 3600) -> str | None:
    try:
        session_id, issued_text, signature = value.split(".", 2)
        issued_at = int(issued_text)
    except (ValueError, AttributeError):
        return None
    now = int(time.time())
    if issued_at > now + 5 or now - issued_at > max_age:
        return None
    expected = hmac.new(secret, f"{session_id}.{issued_at}".encode(), hashlib.sha256).digest()
    try:
        actual = b64d(signature, 32)
    except ValueError:
        return None
    return session_id if hmac.compare_digest(expected, actual) else None


def csrf_token(secret: bytes, session_id: str) -> str:
    return b64e(hmac.new(secret, b"csrf\x00" + session_id.encode(), hashlib.sha256).digest())


def valid_csrf(secret: bytes, session_id: str, candidate: str) -> bool:
    return hmac.compare_digest(csrf_token(secret, session_id), candidate or "")
