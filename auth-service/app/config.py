from __future__ import annotations

import base64
import os
from dataclasses import dataclass


def _required(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        raise RuntimeError(f"Missing required setting: {name}")
    return value


def _decode_32(name: str) -> bytes:
    try:
        value = base64.urlsafe_b64decode(_required(name) + "===")
    except ValueError as exc:
        raise RuntimeError(f"Invalid base64 setting: {name}") from exc
    if len(value) != 32:
        raise RuntimeError(f"{name} must decode to exactly 32 bytes")
    return value


@dataclass(frozen=True)
class Settings:
    database_path: str
    signing_private_key: bytes
    key_pepper: bytes
    session_secret: bytes
    admin_password_hash: str
    allowed_hosts: tuple[str, ...]
    release_id: str
    release_sha256: str
    client_version: str
    chunk_a_id: str
    chunk_b_id: str
    chunk_a_sha256: str
    chunk_b_sha256: str
    artifact_dir: str
    installer_channel_id: str = ""
    installer_channel_version: str = ""
    installer_compatible_sha256: frozenset[str] = frozenset()
    cloud_config_dir: str = "/data/cloud-configs"
    bootstrap_id: str = "bootstrap"
    bootstrap_sha256: str = "0" * 64
    bootstrap_filename: str = "celeste-bootstrap.jar"
    loader_id: str = "stage-two"
    loader_sha256: str = "0" * 64
    loader_filename: str = "stage-two.bin"
    loader_three_id: str = "stage-three"
    loader_three_sha256: str = "0" * 64
    loader_three_filename: str = "stage-three.bin"
    loader_four_id: str = "stage-four"
    loader_four_sha256: str = "0" * 64
    loader_four_filename: str = "stage-four.bin"
    grant_ttl_seconds: int = 600
    challenge_ttl_seconds: int = 90

    @classmethod
    def from_env(cls) -> "Settings":
        hosts = tuple(
            host.strip().lower()
            for host in _required("CELESTE_ALLOWED_HOSTS").split(",")
            if host.strip()
        )
        release_id = _required("CELESTE_RELEASE_ID")
        client_version = _required("CELESTE_CLIENT_VERSION")
        installer_channel_id = os.getenv("CELESTE_INSTALLER_CHANNEL_ID", release_id).strip() or release_id
        installer_channel_version = os.getenv("CELESTE_INSTALLER_CHANNEL_VERSION", client_version).strip() or client_version
        if len(installer_channel_id) > 80 or len(installer_channel_version) > 40:
            raise RuntimeError("Celeste installer channel identity is invalid")
        release_sha256 = _required("CELESTE_RELEASE_SHA256").lower()
        if len(release_sha256) != 64 or any(c not in "0123456789abcdef" for c in release_sha256):
            raise RuntimeError("CELESTE_RELEASE_SHA256 must be a lowercase SHA-256 hex digest")
        installer_compatible = {release_sha256}
        for digest in os.getenv("CELESTE_INSTALLER_COMPAT_SHA256", "").split(","):
            digest = digest.strip().lower()
            if not digest:
                continue
            if len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
                raise RuntimeError("CELESTE_INSTALLER_COMPAT_SHA256 contains an invalid SHA-256 digest")
            installer_compatible.add(digest)
        chunk_a_sha256 = _required("CELESTE_CHUNK_A_SHA256").lower()
        chunk_b_sha256 = _required("CELESTE_CHUNK_B_SHA256").lower()
        for name, digest in (("CELESTE_CHUNK_A_SHA256", chunk_a_sha256), ("CELESTE_CHUNK_B_SHA256", chunk_b_sha256)):
            if len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
                raise RuntimeError(f"{name} must be a lowercase SHA-256 hex digest")
        loader_sha256 = _required("CELESTE_LOADER_SHA256").lower()
        bootstrap_sha256 = _required("CELESTE_BOOTSTRAP_SHA256").lower()
        loader_three_sha256 = _required("CELESTE_LOADER_THREE_SHA256").lower()
        loader_four_sha256 = _required("CELESTE_LOADER_FOUR_SHA256").lower()
        for name, digest in (
            ("CELESTE_BOOTSTRAP_SHA256", bootstrap_sha256),
            ("CELESTE_LOADER_SHA256", loader_sha256),
            ("CELESTE_LOADER_THREE_SHA256", loader_three_sha256),
            ("CELESTE_LOADER_FOUR_SHA256", loader_four_sha256),
        ):
            if len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
                raise RuntimeError(f"{name} must be a lowercase SHA-256 hex digest")
        return cls(
            database_path=os.getenv("CELESTE_DATABASE_PATH", "/data/auth.sqlite3"),
            signing_private_key=_decode_32("CELESTE_SIGNING_PRIVATE_KEY"),
            key_pepper=_decode_32("CELESTE_KEY_PEPPER"),
            session_secret=_decode_32("CELESTE_SESSION_SECRET"),
            admin_password_hash=_required("CELESTE_ADMIN_PASSWORD_HASH"),
            allowed_hosts=hosts,
            release_id=release_id,
            release_sha256=release_sha256,
            client_version=client_version,
            chunk_a_id=_required("CELESTE_CHUNK_A_ID"),
            chunk_b_id=_required("CELESTE_CHUNK_B_ID"),
            chunk_a_sha256=chunk_a_sha256,
            chunk_b_sha256=chunk_b_sha256,
            artifact_dir=os.getenv("CELESTE_ARTIFACT_DIR", "/srv/auth/releases"),
            installer_channel_id=installer_channel_id,
            installer_channel_version=installer_channel_version,
            installer_compatible_sha256=frozenset(installer_compatible),
            cloud_config_dir=os.getenv("CELESTE_CLOUD_CONFIG_DIR", "/data/cloud-configs"),
            bootstrap_id=_required("CELESTE_BOOTSTRAP_ID"),
            bootstrap_sha256=bootstrap_sha256,
            bootstrap_filename=os.getenv("CELESTE_BOOTSTRAP_FILENAME", "celeste-bootstrap.jar"),
            loader_id=_required("CELESTE_LOADER_ID"),
            loader_sha256=loader_sha256,
            loader_filename=os.getenv("CELESTE_LOADER_FILENAME", "stage-two.bin"),
            loader_three_id=_required("CELESTE_LOADER_THREE_ID"),
            loader_three_sha256=loader_three_sha256,
            loader_three_filename=os.getenv("CELESTE_LOADER_THREE_FILENAME", "stage-three.bin"),
            loader_four_id=_required("CELESTE_LOADER_FOUR_ID"),
            loader_four_sha256=loader_four_sha256,
            loader_four_filename=os.getenv("CELESTE_LOADER_FOUR_FILENAME", "stage-four.bin"),
        )
