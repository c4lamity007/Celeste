"""Read-only post-deploy check for the Celeste Fly auth app.

Reads only allowlisted release settings, artifact hashes, the public DPoP module
hash and the SQLite replay-table name. It never reads credentials or license data.
The current Windows Fly CLI can print "The handle is invalid" after successful
SSH output; that known close error is accepted only when a complete JSON result
was received.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import subprocess
from pathlib import Path


REMOTE_CHECK = """\
import hashlib, json, os, sqlite3
from pathlib import Path
from urllib.parse import quote

allowed = [
    'CELESTE_RELEASE_ID', 'CELESTE_RELEASE_SHA256', 'CELESTE_CLIENT_VERSION',
    'CELESTE_INSTALLER_CHANNEL_ID', 'CELESTE_INSTALLER_CHANNEL_VERSION',
    'CELESTE_INSTALLER_COMPAT_SHA256', 'CELESTE_INSTALLER_ALLOW_RUNTIME_DIGEST',
    'CELESTE_BOOTSTRAP_ID', 'CELESTE_BOOTSTRAP_FILENAME', 'CELESTE_BOOTSTRAP_SHA256',
    'CELESTE_LOADER_ID', 'CELESTE_LOADER_FILENAME', 'CELESTE_LOADER_SHA256',
    'CELESTE_LOADER_THREE_ID', 'CELESTE_LOADER_THREE_FILENAME', 'CELESTE_LOADER_THREE_SHA256',
    'CELESTE_LOADER_FOUR_ID', 'CELESTE_LOADER_FOUR_FILENAME', 'CELESTE_LOADER_FOUR_SHA256',
    'CELESTE_CHUNK_A_ID', 'CELESTE_CHUNK_A_SHA256', 'CELESTE_CHUNK_B_ID', 'CELESTE_CHUNK_B_SHA256',
    'CELESTE_ARTIFACT_DIR', 'CELESTE_BETA_RELEASE_DIR', 'CELESTE_PLUS_ENABLED',
    'CELESTE_PUBLIC_ORIGIN', 'CELESTE_DATABASE_PATH', 'CELESTE_CLOUD_CONFIG_DIR',
]
env = {name: os.environ.get(name) for name in allowed}
names = json.loads(__import__('base64').b64decode('__FILENAMES_B64__'))
normal = Path(env['CELESTE_ARTIFACT_DIR'] or '')
plus = Path(env['CELESTE_BETA_RELEASE_DIR'] or '')
if not normal.is_absolute() or not plus.is_absolute():
    raise RuntimeError('release directories must be absolute')

def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()

hashes = {}
for label, (kind, filename) in names.items():
    if Path(filename).name != filename:
        raise RuntimeError('artifact filename is not a basename')
    hashes[label] = digest((normal if kind == 'normal' else plus) / filename)
dpop_module = Path('/srv/auth/app/dpop.py')
db_path = env['CELESTE_DATABASE_PATH'] or ''
db_uri = 'file:' + quote(db_path, safe='/') + '?mode=ro'
db = sqlite3.connect(db_uri, uri=True)
replay_table = db.execute(
    "SELECT name FROM sqlite_master WHERE type='table' AND name='dpop_replays'"
).fetchone() is not None
db.close()
print(json.dumps({
    'env': env, 'hashes': hashes,
    'dpop_module_sha256': digest(dpop_module),
    'replay_table_present': replay_table,
}, sort_keys=True))
"""


def _sha(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(chunk)
    return hasher.hexdigest()


def _checked_manifest(path: Path) -> dict:
    result = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(result, dict):
        raise AssertionError(f"invalid manifest: {path}")
    return result


def _ssh_result(app: str, machine: str, filenames: dict) -> dict:
    encoded_names = base64.b64encode(json.dumps(filenames, sort_keys=True).encode()).decode()
    code = REMOTE_CHECK.replace("__FILENAMES_B64__", encoded_names)
    encoded_code = base64.b64encode(code.encode()).decode()
    command = f'python -c \'import base64;exec(base64.b64decode("{encoded_code}"))\''
    process = subprocess.run(
        ["fly", "ssh", "console", "-a", app, "--machine", machine, "-C", command],
        capture_output=True, text=True, timeout=120,
    )
    lines = (process.stdout + "\n" + process.stderr).splitlines()
    unexpected_errors = [line for line in lines if line.startswith("Error:")
                         and "The handle is invalid." not in line]
    payloads = [json.loads(line) for line in lines if line.startswith("{") and line.endswith("}")]
    if unexpected_errors or len(payloads) != 1:
        raise AssertionError("Fly SSH release check failed: " + " | ".join(lines[-5:]))
    return payloads[0]


def check(app: str, machine: str, normal_dir: Path, plus_dir: Path, before_path: Path,
          origin: str) -> dict:
    normal = _checked_manifest(normal_dir / "release-manifest.json")
    plus = _checked_manifest(plus_dir / "beta-release.json")
    before = _checked_manifest(before_path)
    filenames = {
        "normal_manifest": ("normal", "release-manifest.json"),
        "plus_manifest": ("plus", "beta-release.json"),
        "normal_jar": ("normal", "celeste-client-1.0.0-configured.jar"),
        "normal_bootstrap": ("normal", normal["bootstrap"]["file"]),
        "normal_stage2": ("normal", normal["loader"]["file"]),
        "normal_stage3": ("normal", normal["loaderThree"]["file"]),
        "normal_stage4": ("normal", normal["loaderFour"]["file"]),
        "normal_chunk_a": ("normal", normal["chunks"][0]["file"]),
        "normal_chunk_b": ("normal", normal["chunks"][1]["file"]),
        **{f"plus_{entry['id'].replace('-', '_')}": ("plus", entry["filename"])
           for entry in plus["artifacts"]},
    }
    remote = _ssh_result(app, machine, filenames)
    env = remote["env"]
    expected_env = {
        "CELESTE_RELEASE_ID": normal["release"],
        "CELESTE_RELEASE_SHA256": normal["protectedDigest"],
        "CELESTE_CLIENT_VERSION": "1.0.0",
        "CELESTE_BOOTSTRAP_ID": normal["bootstrap"]["id"],
        "CELESTE_BOOTSTRAP_FILENAME": normal["bootstrap"]["file"],
        "CELESTE_BOOTSTRAP_SHA256": normal["bootstrap"]["sha256"],
        "CELESTE_LOADER_ID": normal["loader"]["id"],
        "CELESTE_LOADER_FILENAME": normal["loader"]["file"],
        "CELESTE_LOADER_SHA256": normal["loader"]["sha256"],
        "CELESTE_LOADER_THREE_ID": normal["loaderThree"]["id"],
        "CELESTE_LOADER_THREE_FILENAME": normal["loaderThree"]["file"],
        "CELESTE_LOADER_THREE_SHA256": normal["loaderThree"]["sha256"],
        "CELESTE_LOADER_FOUR_ID": normal["loaderFour"]["id"],
        "CELESTE_LOADER_FOUR_FILENAME": normal["loaderFour"]["file"],
        "CELESTE_LOADER_FOUR_SHA256": normal["loaderFour"]["sha256"],
        "CELESTE_CHUNK_A_ID": normal["chunks"][0]["id"],
        "CELESTE_CHUNK_A_SHA256": normal["chunks"][0]["sha256"],
        "CELESTE_CHUNK_B_ID": normal["chunks"][1]["id"],
        "CELESTE_CHUNK_B_SHA256": normal["chunks"][1]["sha256"],
        "CELESTE_INSTALLER_CHANNEL_ID": before["release_env"]["CELESTE_INSTALLER_CHANNEL_ID"],
        "CELESTE_INSTALLER_CHANNEL_VERSION": before["release_env"]["CELESTE_INSTALLER_CHANNEL_VERSION"],
        "CELESTE_INSTALLER_COMPAT_SHA256": before["release_env"]["CELESTE_INSTALLER_COMPAT_SHA256"],
        "CELESTE_INSTALLER_ALLOW_RUNTIME_DIGEST": "false",
        "CELESTE_PLUS_ENABLED": "true",
        "CELESTE_PUBLIC_ORIGIN": origin,
        "CELESTE_DATABASE_PATH": before["config_env"]["CELESTE_DATABASE_PATH"],
        "CELESTE_CLOUD_CONFIG_DIR": before["config_env"]["CELESTE_CLOUD_CONFIG_DIR"],
    }
    for name, expected in expected_env.items():
        if env.get(name) != expected:
            raise AssertionError(f"post-deploy setting mismatch: {name}")
    if not (env["CELESTE_ARTIFACT_DIR"] or "").endswith("/" + normal["release"]):
        raise AssertionError("normal artifact directory does not point to new release")
    if not (env["CELESTE_BETA_RELEASE_DIR"] or "").endswith("/" + plus["release_id"]):
        raise AssertionError("Plus artifact directory does not point to new release")

    expected_hashes = {label: _sha((normal_dir if kind == "normal" else plus_dir) / name)
                       for label, (kind, name) in filenames.items()}
    for label, expected in expected_hashes.items():
        if remote["hashes"].get(label) != expected:
            raise AssertionError(f"post-deploy artifact mismatch: {label}")
    if remote["dpop_module_sha256"] != _sha(normal_dir.parents[3] / "auth-service" / "app" / "dpop.py"):
        raise AssertionError("deployed DPoP source differs from checked source")
    if remote["replay_table_present"] is not True:
        raise AssertionError("DPoP replay table missing")

    return {"status": "passed", "normal_release": normal["release"],
            "plus_release": plus["release_id"], "artifacts": len(expected_hashes),
            "dpop_module": "present and matched", "replay_table": "present",
            "installer_digest": "unchanged", "database_and_cloud_paths": "unchanged"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--app", required=True)
    parser.add_argument("--machine", required=True)
    parser.add_argument("--normal-dir", type=Path, required=True)
    parser.add_argument("--plus-dir", type=Path, required=True)
    parser.add_argument("--before", type=Path, required=True)
    parser.add_argument("--origin", required=True)
    args = parser.parse_args()
    print(json.dumps(check(args.app, args.machine, args.normal_dir, args.plus_dir,
                           args.before, args.origin), sort_keys=True))
