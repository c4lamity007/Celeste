"""Print/check an offline golden vector using the actual auth-service code.

Run from the repository root with the auth-service Python environment:
    auth-service/.venv/Scripts/python.exe beta-lab/delivery-client/generate_fixture.py
    auth-service/.venv/Scripts/python.exe beta-lab/delivery-client/generate_fixture.py --check

This uses no environment settings, actual credentials, database, or network.
Only temporary synthetic artifact files are written; stdout is the vector.
The deterministic seeds below are PUBLIC TEST MATERIAL, never deployment keys.
The installation seed is emitted solely so clients can create offline proofs.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
from unittest.mock import patch


REPOSITORY = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPOSITORY / "auth-service"))

from starlette.requests import Request  # noqa: E402
from fastapi import HTTPException  # noqa: E402
from cryptography.hazmat.primitives import serialization  # noqa: E402
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey  # noqa: E402

from app.beta_delivery import (  # noqa: E402
    ARTIFACT_FILENAMES,
    ARTIFACT_ROLES,
    RELEASE_SCHEMA,
    _read_release,
    create_beta_router,
)
from app.security import GrantSigner, b64e, canonical_json  # noqa: E402


NOW = 1_800_000_000
LICENSE_UID = 42
DEVICE = "00000000-0000-0000-0000-000000000042"
RELEASE_ID = "owner-beta-golden-1"
CLIENT_VERSION = '1.0.0-beta.<>&"\\'
VECTOR_PATH = Path(__file__).resolve().parent / "fixtures" / "server-schema2-golden.json"


class FixtureEntitledRecord:
    """Explicit device/entitlement query stub; no database file or legacy UID grant."""

    entitled = True
    entitlement_queries = 0
    row = None

    def connect(self):
        return self

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def execute(self, statement, parameters):
        if "SELECT d.license_uid" in statement:
            self.row = {
                "license_uid": LICENSE_UID,
                "device_status": "active",
                "license_status": "active",
                "expires_at": None,
            } if parameters == (DEVICE, LICENSE_UID) else None
        elif "SELECT 1 FROM license_entitlements" in statement:
            self.entitlement_queries += 1
            self.row = {"1": 1} if self.entitled and parameters == (LICENSE_UID, "celeste_plus") else None
        else:
            raise AssertionError(f"Unexpected fixture query: {statement}")
        return self

    def fetchone(self):
        return self.row


def generate() -> dict:
    # A reproducible public test seed, not a credential and never emitted.
    signer = GrantSigner(hashlib.sha256(b"CELESTE PUBLIC OFFLINE GOLDEN TEST SEED v1").digest())
    # This public, deterministic installation seed exists only for offline proof checks.
    installation_seed = hashlib.sha256(b"CELESTE PUBLIC OFFLINE DPOP INSTALLATION SEED v1").digest()
    installation_key = Ed25519PrivateKey.from_private_bytes(installation_seed)
    installation_public = installation_key.public_key().public_bytes(
        serialization.Encoding.Raw, serialization.PublicFormat.Raw
    )
    jwk = {"crv": "Ed25519", "kty": "OKP", "x": b64e(installation_public)}
    confirmation = {"jkt": b64e(hashlib.sha256(canonical_json(jwk)).digest())}
    artifact_bytes = {
        artifact_id: b"CELESTE OFFLINE FIXTURE ONLY\x00" + artifact_id.encode("ascii") + b"\n\xff"
        for artifact_id in ARTIFACT_ROLES
    }
    installer_claims = {
        "aud": "celeste-installer",
        "product": "celeste_plus",
        "release": "production-channel",
        "client_sha256": "a" * 64,
        "payload_release": "production-release",
        "payload_sha256": "b" * 64,
        "bootstrap": "production-bootstrap",
        "bootstrap_sha256": "c" * 64,
        "sub": str(LICENSE_UID),
        "device": DEVICE,
        "jti": "offline-golden-installer-ticket-42",
        "iat": NOW - 30,
        "nbf": NOW - 35,
        "exp": NOW + 600,
        "cnf": confirmation,
    }
    installer_token = signer.sign(installer_claims)
    with tempfile.TemporaryDirectory(prefix="celeste-offline-golden-") as temporary:
        root = Path(temporary).resolve()
        beta_root = root / "beta"
        beta_root.mkdir()
        staged_catalog = {
            "schema": RELEASE_SCHEMA,
            "release_id": RELEASE_ID,
            "client_version": CLIENT_VERSION,
            "runtime_sha256": "d" * 64,
            "artifacts": [],
        }
        # Intentionally reversed input: the server must sort its public projection.
        for artifact_id in reversed(tuple(ARTIFACT_ROLES)):
            content = artifact_bytes[artifact_id]
            filename = ARTIFACT_FILENAMES[artifact_id]
            (beta_root / filename).write_bytes(content)
            staged_catalog["artifacts"].append({
                "id": artifact_id,
                "role": ARTIFACT_ROLES[artifact_id],
                "filename": filename,
                "sha256": hashlib.sha256(content).hexdigest(),
                "size": len(content),
            })
        (beta_root / "beta-release.json").write_bytes(canonical_json(staged_catalog))
        settings = SimpleNamespace(
            plus_enabled=True,
            beta_release_dir=str(beta_root),
            artifact_dir=str(root / "production-unused"),
            release_id="production-release",
            release_sha256="b" * 64,
            installer_channel_id="production-channel",
            installer_compatible_sha256=frozenset({"a" * 64}),
            bootstrap_id="production-bootstrap",
            bootstrap_sha256="c" * 64,
        )
        catalog, _ = _read_release(settings)
        assert [entry["id"] for entry in catalog["artifacts"]] == sorted(ARTIFACT_ROLES)
        for artifact_id, expected in artifact_bytes.items():
            selected_catalog, selected_bytes = _read_release(settings, artifact_id)
            assert selected_catalog == catalog and selected_bytes == expected

        database = FixtureEntitledRecord()
        router = create_beta_router(
            settings, database, signer,
            lambda *_: DEVICE, lambda *_: None,
        )
        manifest_endpoint = next(route.endpoint for route in router.routes if route.path == "/v1/beta/manifest")

        def request_for(token):
            return Request({
                "type": "http", "method": "GET", "scheme": "https",
                "path": "/v1/beta/manifest", "query_string": b"",
                "server": ("offline.invalid", 443), "client": ("127.0.0.1", 0),
                "headers": [(b"authorization", f"DPoP {token}".encode("ascii"))],
            })
        # Direct endpoint invocation, not TestClient, HTTP, or an actual server.
        with patch("time.time", return_value=NOW):
            response = manifest_endpoint(request_for(installer_token))
            assert response.status_code == 200
            manifest_token = json.loads(response.body)["manifest"]
            manifest_claims = signer.verify(manifest_token)
            assert signer.verify(installer_token) == installer_claims
            assert manifest_claims["sub"] == "42" and manifest_claims["product"] == "celeste_plus"
            assert database.entitlement_queries == 2
            # Even correctly signed claims cannot cross product, user, or device boundaries.
            for changed in (
                {**installer_claims, "product": "celeste"},
                {key: value for key, value in installer_claims.items() if key != "product"},
                {**installer_claims, "sub": "7"},
                {**installer_claims, "sub": "43"},
                {**installer_claims, "sub": "042"},
                {**installer_claims, "sub": 42},
                {**installer_claims, "device": "00000000-0000-0000-0000-000000000043"},
            ):
                try:
                    manifest_endpoint(request_for(signer.sign(changed)))
                except HTTPException as error:
                    assert error.status_code in (401, 403)
                else:
                    raise AssertionError("Server accepted mismatched Celeste+ grant")
            database.entitled = False
            try:
                manifest_endpoint(request_for(installer_token))
            except HTTPException as error:
                assert error.status_code == 403
            else:
                raise AssertionError("Server admitted an active device without Celeste+ entitlement")
        catalog_json = canonical_json(catalog).decode("utf-8")
        catalog_sha256 = hashlib.sha256(catalog_json.encode("utf-8")).hexdigest()
        assert manifest_claims["catalog_sha256"] == catalog_sha256
        assert manifest_claims["exp"] == NOW + 120
        assert manifest_claims["installer_sha256"] == hashlib.sha256(installer_token.encode("ascii")).hexdigest()
        return {
            "fixture_schema": "celeste-beta-delivery-golden-1",
            "now": NOW,
            "public_key": signer.public_key_b64(),
            "installation_test_seed": b64e(installation_seed),
            "installation_public_key": b64e(installation_public),
            "expected_uid": LICENSE_UID,
            "expected_device": DEVICE,
            "installer_token": installer_token,
            "manifest_token": manifest_token,
            "installer_claims": installer_claims,
            "manifest_claims": manifest_claims,
            "canonical_catalog": catalog_json,
            "catalog_sha256": catalog_sha256,
            "artifact_bytes": {key: b64e(value) for key, value in artifact_bytes.items()},
        }


def main() -> None:
    encoded = json.dumps(generate(), indent=2, sort_keys=True) + "\n"
    if sys.argv[1:] == ["--check"]:
        if VECTOR_PATH.read_text(encoding="utf-8") != encoded:
            raise SystemExit("Golden vector does not match current server output")
        print("Golden vector matches actual server signing, projection, and manifest endpoint.")
    elif sys.argv[1:]:
        raise SystemExit("Usage: generate_fixture.py [--check]")
    else:
        sys.stdout.write(encoded)


if __name__ == "__main__":
    main()
