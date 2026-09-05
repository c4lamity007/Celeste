from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import secrets
import struct
import tempfile
import zipfile
from pathlib import Path


AUTH_RESOURCE = "META-INF/.r/4d7a9c2e"
LEGACY_AUTH_RESOURCE = "celeste-auth.properties"
DESCRIPTOR_MAGIC = 0x6DCA24F1
DESCRIPTOR_VERSION = 1
DESCRIPTOR_CONTEXT = b"cst-release-v1"
DESCRIPTOR_KEY = bytes.fromhex("8e25a9734dc15fb837e4620bd67914ac51f2069b3c68dd42b10e75ca29935aef")
LAUNCHER_CHANNEL_FILE = Path(__file__).resolve().parent.parent / "native-verifier" / "launcher-channel.json"


def protected(name: str) -> bool:
    # Obfuscation relocates classes, packages, and optionally assets. Protect the
    # complete post-obfuscation payload instead of relying on a source package
    # prefix. The packed descriptor is excluded because this packager writes the
    # resulting digest into that resource afterward. The legacy resource is
    # ignored only while packaging so stale build inputs are stripped safely.
    return name not in {AUTH_RESOURCE, LEGACY_AUTH_RESOURCE}


def protected_digest(path: Path) -> str:
    digest = hashlib.sha256()
    with zipfile.ZipFile(path, "r") as archive:
        names = sorted(name for name in archive.namelist() if not name.endswith("/") and protected(name))
        for name in names:
            content = archive.read(name)
            encoded_name = name.encode("utf-8")
            digest.update(struct.pack(">I", len(encoded_name)))
            digest.update(encoded_name)
            digest.update(struct.pack(">Q", len(content)))
            digest.update(content)
    return digest.hexdigest()


def protected_digest_many(paths: list[Path]) -> str:
    digest = hashlib.sha256()
    entries: dict[str, bytes] = {}
    for path in paths:
        with zipfile.ZipFile(path, "r") as archive:
            for name in archive.namelist():
                if name.endswith("/") or not protected(name):
                    continue
                if name in entries:
                    raise SystemExit(f"Duplicate protected entry across payload parts: {name}")
                entries[name] = archive.read(name)
    for name in sorted(entries):
        content = entries[name]
        encoded_name = name.encode("utf-8")
        digest.update(struct.pack(">I", len(encoded_name)))
        digest.update(encoded_name)
        digest.update(struct.pack(">Q", len(content)))
        digest.update(content)
    return digest.hexdigest()


def configure(input_jar: Path, output_jar: Path, descriptor: bytes) -> None:
    output_jar.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(delete=False, dir=output_jar.parent, suffix=".jar") as temporary:
        temporary_path = Path(temporary.name)
    try:
        with zipfile.ZipFile(input_jar, "r") as source, zipfile.ZipFile(
            temporary_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9
        ) as target:
            for info in source.infolist():
                if info.filename in {AUTH_RESOURCE, LEGACY_AUTH_RESOURCE}:
                    continue
                target.writestr(info, source.read(info.filename))
            target.writestr(AUTH_RESOURCE, descriptor)
        temporary_path.replace(output_jar)
    finally:
        temporary_path.unlink(missing_ok=True)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def decode_base64url(value: str) -> bytes:
    try:
        return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))
    except (ValueError, TypeError) as exception:
        raise SystemExit("Pinned server key is not valid base64url") from exception


def pack_text(value: str, field: str) -> bytes:
    encoded = value.strip().encode("utf-8")
    if not encoded or len(encoded) > 0xFFFF:
        raise SystemExit(f"{field} has an invalid length")
    return struct.pack(">H", len(encoded)) + encoded


def xor_stream(value: bytes, salt: bytes) -> bytes:
    output = bytearray(len(value))
    offset = 0
    counter = 0
    while offset < len(value):
        block = hmac.new(
            DESCRIPTOR_KEY,
            DESCRIPTOR_CONTEXT + salt + struct.pack(">I", counter),
            hashlib.sha256,
        ).digest()
        length = min(len(block), len(value) - offset)
        for index in range(length):
            output[offset + index] = value[offset + index] ^ block[index]
        offset += length
        counter += 1
    return bytes(output)


def release_descriptor(base_url: str, server_key: str, release_id: str, digest: str, client_version: str) -> bytes:
    key = decode_base64url(server_key)
    if len(key) != 32:
        raise SystemExit("Pinned server key must be 32 bytes")
    try:
        digest_bytes = bytes.fromhex(digest)
    except ValueError as exception:
        raise SystemExit("Release digest must be hexadecimal") from exception
    if len(digest_bytes) != 32:
        raise SystemExit("Release digest must be 32 bytes")

    clear = (
        pack_text(base_url.rstrip("/"), "Base URL")
        + key
        + pack_text(release_id, "Release ID")
        + digest_bytes
        + pack_text(client_version, "Client version")
    )
    salt = secrets.token_bytes(16)
    ciphertext = xor_stream(clear, salt)
    header = struct.pack(">IB16sI", DESCRIPTOR_MAGIC, DESCRIPTOR_VERSION, salt, len(ciphertext))
    tag = hmac.new(DESCRIPTOR_KEY, header + ciphertext, hashlib.sha256).digest()[:16]
    return header + ciphertext + tag


def split_jar(source: Path, first: Path, second: Path) -> None:
    first.parent.mkdir(parents=True, exist_ok=True)
    pinned_first = {
        "META-INF/MANIFEST.MF",
        "fabric.mod.json",
        "celeste.mixins.json",
        AUTH_RESOURCE,
    }
    with zipfile.ZipFile(source, "r") as archive, zipfile.ZipFile(
        first, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9
    ) as part_a, zipfile.ZipFile(second, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as part_b:
        for info in archive.infolist():
            if info.is_dir():
                continue
            name = info.filename
            goes_first = (
                name in pinned_first
                or name.startswith("META-INF/")
                or hashlib.sha256(name.encode("utf-8")).digest()[0] < 128
            )
            target = part_a if goes_first else part_b
            target.writestr(info, archive.read(name))


def launcher_channel() -> tuple[str, str, set[str]]:
    try:
        channel = json.loads(LAUNCHER_CHANNEL_FILE.read_text(encoding="utf-8"))
        channel_id = str(channel["channel_id"]).strip()
        channel_version = str(channel["channel_version"]).strip()
        digests = {str(value).strip().lower() for value in channel["compatible_digests"]}
    except (OSError, KeyError, TypeError, ValueError) as exception:
        raise SystemExit(f"Invalid launcher channel file: {LAUNCHER_CHANNEL_FILE}") from exception
    if not channel_id or len(channel_id) > 80 or not channel_version or len(channel_version) > 40:
        raise SystemExit("Launcher channel identity is invalid")
    for digest in digests:
        if len(digest) != 64 or any(character not in "0123456789abcdef" for character in digest):
            raise SystemExit("Launcher channel contains an invalid SHA-256 identity")
    if not digests:
        raise SystemExit("Launcher channel must contain at least one compatible identity")

    launcher_source = LAUNCHER_CHANNEL_FILE.parent / "src" / "release.rs"
    try:
        source_text = launcher_source.read_text(encoding="utf-8")
    except OSError as exception:
        raise SystemExit(f"Native launcher release source is missing: {launcher_source}") from exception
    required_constants = (
        f'pub const LAUNCHER_CHANNEL_ID: &str = "{channel_id}";',
        f'pub const LAUNCHER_PROTOCOL_VERSION: &str = "{channel_version}";',
    )
    if any(constant not in source_text for constant in required_constants) or not any(
        f'pub const LAUNCHER_CHANNEL_DIGEST: &str = "{digest}";' in source_text
        for digest in digests
    ):
        raise SystemExit("Native launcher constants do not match launcher-channel.json")
    return channel_id, channel_version, digests


def installer_compatibility_digests(server_releases: Path, current_digest: str) -> list[str]:
    _, _, configured_digests = launcher_channel()
    digests: set[str] = set(configured_digests)
    # Preserve explicitly approved historical launcher identities, but never
    # turn every old payload digest into a launcher identity automatically.
    environment_path = server_releases / "release.env"
    if environment_path.is_file():
        for line in environment_path.read_text(encoding="ascii", errors="ignore").splitlines():
            if not line.startswith("CELESTE_INSTALLER_COMPAT_SHA256="):
                continue
            for digest in line.partition("=")[2].split(","):
                digest = digest.strip().lower()
                if len(digest) == 64 and all(character in "0123456789abcdef" for character in digest):
                    digests.add(digest)
    digests.discard(current_digest)
    return sorted(digests)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Apply auth metadata to the final post-obfuscation JAR and create its two protected delivery chunks."
    )
    parser.add_argument("--input", required=True, type=Path, help="Final obfuscated JAR")
    parser.add_argument("--output", required=True, type=Path, help="Configured release JAR")
    parser.add_argument("--server-releases", required=True, type=Path)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--server-key", required=True)
    parser.add_argument("--release-id", required=True)
    parser.add_argument("--client-version", required=True)
    parser.add_argument("--chunk-a-id", required=True)
    parser.add_argument("--chunk-b-id", required=True)
    parser.add_argument("--stage-two", required=True, type=Path, help="Built second-stage loader JAR")
    parser.add_argument("--stage-three", required=True, type=Path, help="Built third-stage loader JAR")
    parser.add_argument("--stage-four", required=True, type=Path, help="Built fourth-stage loader JAR")
    parser.add_argument("--loader-id", required=True)
    parser.add_argument("--loader-three-id", required=True)
    parser.add_argument("--loader-four-id", required=True)
    parser.add_argument("--bootstrap-id", required=True)
    parser.add_argument("--bootstrap-input", required=True, type=Path, help="Built mods-folder bootstrap JAR")
    parser.add_argument("--bootstrap-output", required=True, type=Path, help="Configured bootstrap JAR for distribution")
    args = parser.parse_args()

    if not args.input.is_file():
        raise SystemExit(f"Input JAR does not exist: {args.input}")
    if not args.stage_two.is_file():
        raise SystemExit(f"Second-stage loader does not exist: {args.stage_two}")
    if not args.stage_three.is_file():
        raise SystemExit(f"Third-stage loader does not exist: {args.stage_three}")
    if not args.stage_four.is_file():
        raise SystemExit(f"Fourth-stage loader does not exist: {args.stage_four}")
    if not args.bootstrap_input.is_file():
        raise SystemExit(f"Bootstrap loader does not exist: {args.bootstrap_input}")
    digest = protected_digest(args.input)
    installer_channel_id, installer_channel_version, _ = launcher_channel()
    installer_compatibility = installer_compatibility_digests(args.server_releases, digest)
    descriptor = release_descriptor(
        args.base_url,
        args.server_key,
        args.release_id,
        digest,
        args.client_version,
    )
    configure(args.input, args.output, descriptor)
    configure(args.bootstrap_input, args.bootstrap_output, descriptor)
    if protected_digest(args.output) != digest:
        raise SystemExit("Protected digest changed while applying auth metadata")

    args.server_releases.mkdir(parents=True, exist_ok=True)
    chunk_a = args.server_releases / "chunk-a.bin"
    chunk_b = args.server_releases / "chunk-b.bin"
    split_jar(args.output, chunk_a, chunk_b)
    if protected_digest_many([chunk_a, chunk_b]) != digest:
        raise SystemExit("Split payload digest does not match the configured release")
    stage_two = args.server_releases / "stage-two.bin"
    stage_two.write_bytes(args.stage_two.read_bytes())
    stage_three = args.server_releases / "stage-three.bin"
    stage_three.write_bytes(args.stage_three.read_bytes())
    stage_four = args.server_releases / "stage-four.bin"
    stage_four.write_bytes(args.stage_four.read_bytes())
    manifest = {
        "protocol": "cst-auth-1",
        "release": args.release_id,
        "protectedDigest": digest,
        "jarSha256": sha256(args.output),
        "jarSize": args.output.stat().st_size,
        "bootstrap": {
            "id": args.bootstrap_id,
            "file": args.bootstrap_output.name,
            "sha256": sha256(args.bootstrap_output),
            "size": args.bootstrap_output.stat().st_size,
        },
        "loader": {
            "id": args.loader_id,
            "file": stage_two.name,
            "sha256": sha256(stage_two),
            "size": stage_two.stat().st_size,
        },
        "loaderThree": {
            "id": args.loader_three_id,
            "file": stage_three.name,
            "sha256": sha256(stage_three),
            "size": stage_three.stat().st_size,
        },
        "loaderFour": {
            "id": args.loader_four_id,
            "file": stage_four.name,
            "sha256": sha256(stage_four),
            "size": stage_four.stat().st_size,
        },
        "chunks": [
            {"id": args.chunk_a_id, "file": chunk_a.name, "sha256": sha256(chunk_a), "size": chunk_a.stat().st_size},
            {"id": args.chunk_b_id, "file": chunk_b.name, "sha256": sha256(chunk_b), "size": chunk_b.stat().st_size},
        ],
    }
    manifest_path = args.server_releases / "release-manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    environment = (
        f"CELESTE_RELEASE_ID={args.release_id}\n"
        f"CELESTE_RELEASE_SHA256={digest}\n"
        f"CELESTE_INSTALLER_CHANNEL_ID={installer_channel_id}\n"
        f"CELESTE_INSTALLER_CHANNEL_VERSION={installer_channel_version}\n"
        f"CELESTE_INSTALLER_COMPAT_SHA256={','.join(installer_compatibility)}\n"
        f"CELESTE_CLIENT_VERSION={args.client_version}\n"
        f"CELESTE_CHUNK_A_ID={args.chunk_a_id}\n"
        f"CELESTE_CHUNK_B_ID={args.chunk_b_id}\n"
        f"CELESTE_CHUNK_A_SHA256={manifest['chunks'][0]['sha256']}\n"
        f"CELESTE_CHUNK_B_SHA256={manifest['chunks'][1]['sha256']}\n"
        f"CELESTE_BOOTSTRAP_ID={args.bootstrap_id}\n"
        f"CELESTE_BOOTSTRAP_SHA256={manifest['bootstrap']['sha256']}\n"
        f"CELESTE_BOOTSTRAP_FILENAME={manifest['bootstrap']['file']}\n"
        f"CELESTE_LOADER_ID={args.loader_id}\n"
        f"CELESTE_LOADER_SHA256={manifest['loader']['sha256']}\n"
        f"CELESTE_LOADER_FILENAME={manifest['loader']['file']}\n"
        f"CELESTE_LOADER_THREE_ID={args.loader_three_id}\n"
        f"CELESTE_LOADER_THREE_SHA256={manifest['loaderThree']['sha256']}\n"
        f"CELESTE_LOADER_THREE_FILENAME={manifest['loaderThree']['file']}\n"
        f"CELESTE_LOADER_FOUR_ID={args.loader_four_id}\n"
        f"CELESTE_LOADER_FOUR_SHA256={manifest['loaderFour']['sha256']}\n"
        f"CELESTE_LOADER_FOUR_FILENAME={manifest['loaderFour']['file']}\n"
    )
    (args.server_releases / "release.env").write_text(environment, encoding="ascii")
    print(json.dumps(manifest, separators=(",", ":")))


if __name__ == "__main__":
    main()
