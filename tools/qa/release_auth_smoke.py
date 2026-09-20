"""Exercise packaged Celeste/Celeste+ artifacts against a disposable auth service.

This creates a temporary SQLite database and synthetic installation keys. It
never writes to the supplied release directories or contacts the live server.
Run from the repository root with auth-service/.venv/Scripts/python.exe.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import tempfile
import time
from dataclasses import replace
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "auth-service"))
sys.path.insert(0, str(ROOT / "auth-service" / "tests"))

from app.main import create_app  # noqa: E402
from app.products import PLUS  # noqa: E402
from app.security import b64d, b64e  # noqa: E402
from dpop_client import TestClient, proof  # noqa: E402
from test_protocol import create_license, enroll, hardware, release, settings  # noqa: E402


def checked(response, label: str, code: int = 200):
    if response.status_code != code:
        raise AssertionError(f"{label}: HTTP {response.status_code}: {response.text[:250]}")
    return response


def checked_artifact(response, label: str, entry: dict) -> None:
    checked(response, label)
    if len(response.content) != entry["size"]:
        raise AssertionError(f"{label}: size mismatch")
    if hashlib.sha256(response.content).hexdigest() != entry["sha256"]:
        raise AssertionError(f"{label}: digest mismatch")


def signed_complete(client, endpoint: str, body: dict, install_key, token_key) -> str:
    started = checked(client.post(f"/v1/{endpoint}/start", json=body), f"{endpoint} start").json()
    message = b64d(started["message"])
    completed = checked(
        client.post(
            f"/v1/{endpoint}/complete",
            json={
                "challenge_id": started["challenge_id"],
                "install_signature": b64e(install_key.sign(message)),
                "token_signature": b64e(token_key.sign(message)),
            },
        ),
        f"{endpoint} complete",
    )
    return completed.json()["grant"]


def run(normal_dir: Path, plus_dir: Path, launcher_digest: str, channel_id: str, channel_version: str) -> dict:
    normal = json.loads((normal_dir / "release-manifest.json").read_text(encoding="utf-8"))
    plus = json.loads((plus_dir / "beta-release.json").read_text(encoding="utf-8"))
    if normal["release"] == plus["release_id"]:
        raise AssertionError("Normal and Plus release IDs must differ")
    if launcher_digest == normal["protectedDigest"]:
        raise AssertionError("Smoke launcher digest must be distinct from the runtime digest")

    with tempfile.TemporaryDirectory(prefix="celeste-dpop-auth-smoke-") as temp:
        base = settings(Path(temp))
        config = replace(
            base,
            artifact_dir=str(normal_dir.resolve()),
            release_id=normal["release"],
            release_sha256=normal["protectedDigest"],
            client_version="1.0.0",
            chunk_a_id=normal["chunks"][0]["id"],
            chunk_b_id=normal["chunks"][1]["id"],
            chunk_a_sha256=normal["chunks"][0]["sha256"],
            chunk_b_sha256=normal["chunks"][1]["sha256"],
            bootstrap_id=normal["bootstrap"]["id"],
            bootstrap_sha256=normal["bootstrap"]["sha256"],
            bootstrap_filename=normal["bootstrap"]["file"],
            loader_id=normal["loader"]["id"],
            loader_sha256=normal["loader"]["sha256"],
            loader_filename=normal["loader"]["file"],
            loader_three_id=normal["loaderThree"]["id"],
            loader_three_sha256=normal["loaderThree"]["sha256"],
            loader_three_filename=normal["loaderThree"]["file"],
            loader_four_id=normal["loaderFour"]["id"],
            loader_four_sha256=normal["loaderFour"]["sha256"],
            loader_four_filename=normal["loaderFour"]["file"],
            installer_channel_id=channel_id,
            installer_channel_version=channel_version,
            installer_compatible_sha256=frozenset({launcher_digest}),
            installer_allow_runtime_digest=False,
            plus_enabled=True,
            beta_release_dir=str(plus_dir.resolve()),
        )
        app = create_app(config)
        license_key = "CST-SMOKE-ABCDE-FGHIJ-KLMNO-PQRST-UVWXY"
        uid = create_license(config, license_key)
        with app.state.database.connect() as connection:
            connection.execute(
                "INSERT INTO license_entitlements(license_uid,product,created_at) VALUES(?,?,1)",
                (uid, PLUS),
            )

        runtime = {
            **release(),
            "release_id": config.release_id,
            "client_version": config.client_version,
            "client_sha256": config.release_sha256,
        }
        installer = {
            **release(),
            "release_id": channel_id,
            "client_version": channel_version,
            "client_sha256": launcher_digest,
        }
        with TestClient(app, base_url="https://testserver") as client:
            denied = client.post(
                "/v1/enroll/start",
                json={**installer, "client_sha256": config.release_sha256, "license_key": license_key,
                      "install_signing_key": b64e(bytes(32)), "install_encryption_key": b64e(bytes(32)),
                      "hardware": hardware()},
            )
            checked(denied, "runtime digest denied", 409)

            enrolled, install_key, token_key = enroll(
                client, license_key, launcher_digest, channel_id, channel_version
            )
            if enrolled["uid"] != uid:
                raise AssertionError("enrollment UID mismatch")
            device_id = enrolled["device_id"]

            normal_installer_grant = signed_complete(
                client, "installer", {**installer, "product": "celeste", "device_id": device_id,
                                      "hardware": hardware()}, install_key, token_key
            )
            bootstrap = checked(
                client.get(f"/v1/bootstrap/{config.bootstrap_id}",
                           headers={"Authorization": f"Bearer {normal_installer_grant}"}),
                "normal bootstrap",
            )
            checked_artifact(bootstrap, "normal bootstrap", normal["bootstrap"])
            checked(
                client.post("/v1/installer/consume", json={},
                            headers={"Authorization": f"Bearer {normal_installer_grant}"}),
                "installer consume",
            )

            normal_body = {**runtime, "device_id": device_id, "hardware": hardware()}
            grant = signed_complete(client, "auth", normal_body, install_key, token_key)
            stages = [
                (grant, normal["loader"]),
                (signed_complete(client, "stage2", normal_body, install_key, token_key), normal["loaderThree"]),
                (signed_complete(client, "stage3", normal_body, install_key, token_key), normal["loaderFour"]),
            ]
            for stage_grant, entry in stages:
                response = client.get(f"/v1/loader/{entry['id']}",
                                      headers={"Authorization": f"Bearer {stage_grant}"})
                checked_artifact(response, entry["file"], entry)
            final_grant = signed_complete(client, "stage4", normal_body, install_key, token_key)
            for entry in normal["chunks"]:
                response = client.get(f"/v1/blob/{entry['id']}",
                                      headers={"Authorization": f"Bearer {final_grant}"})
                checked_artifact(response, entry["file"], entry)

            target = f"/v1/loader/{normal['loader']['id']}"
            replay_proof = proof(install_key, "GET", "https://testserver" + target, grant)
            replay_headers = {"Authorization": f"DPoP {grant}", "DPoP": replay_proof}
            checked(client.get(target, headers=replay_headers), "first replay probe")
            checked(client.get(target, headers=replay_headers), "proof replay denied", 401)

            nonce = "release_smoke_nonce_0001"
            timestamp = int(time.time())
            message = (
                f"celeste-session-v1\n{device_id}\n{timestamp}\n{nonce}\n"
                f"{config.release_sha256}\n{config.release_sha256}\nheartbeat\n"
                f"{runtime['game_uuid']}\n{runtime['game_name']}"
            ).encode()
            heartbeat = client.post(
                "/v1/session/check",
                json={**normal_body, "timestamp": timestamp, "nonce": nonce,
                      "observed_sha256": config.release_sha256, "event": "heartbeat",
                      "install_signature": b64e(install_key.sign(message)),
                      "token_signature": b64e(token_key.sign(message))},
            )
            checked(heartbeat, "session heartbeat")
            checked(client.get("/v1/configs", headers={"Authorization": f"Bearer {grant}"}),
                    "cloud config list")

            plus_grant = signed_complete(
                client, "installer", {**installer, "product": PLUS, "device_id": device_id,
                                      "hardware": hardware()}, install_key, token_key
            )
            plus_headers = {"Authorization": f"Bearer {plus_grant}"}
            manifest_response = checked(client.get("/v1/beta/manifest", headers=plus_headers), "plus manifest")
            catalog = manifest_response.json()
            signed_catalog = app.state.signer.verify(catalog["manifest"])
            if signed_catalog is None or signed_catalog["release_id"] != plus["release_id"]:
                raise AssertionError("plus catalog release mismatch")
            plus_headers["X-Celeste-Beta-Manifest"] = catalog["manifest"]
            for entry in plus["artifacts"]:
                response = client.get(
                    f"/v1/beta/artifacts/{plus['release_id']}/{entry['id']}",
                    headers=plus_headers,
                )
                checked_artifact(response, entry["filename"], entry)

        return {
            "normal_release": normal["release"],
            "normal_artifacts_checked": 6,
            "plus_release": plus["release_id"],
            "plus_artifacts_checked": 4,
            "enroll_installer_auth_stages_session_cloud": "passed",
            "dpop_replay": "rejected",
            "runtime_digest_installer": "rejected",
        }


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--normal-dir", type=Path, required=True)
    parser.add_argument("--plus-dir", type=Path, required=True)
    parser.add_argument("--launcher-digest", required=True)
    parser.add_argument("--channel-id", required=True)
    parser.add_argument("--channel-version", required=True)
    args = parser.parse_args()
    print(json.dumps(run(args.normal_dir, args.plus_dir, args.launcher_digest,
                         args.channel_id, args.channel_version), sort_keys=True))
