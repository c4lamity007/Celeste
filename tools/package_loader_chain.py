from __future__ import annotations

import argparse
import json
from pathlib import Path

from package_final_release import (
    configure,
    installer_compatibility_digests,
    launcher_channel,
    release_descriptor,
    sha256,
)


def main() -> None:
    parser = argparse.ArgumentParser(description="Refresh the protected loader chain without repacking payload chunks.")
    parser.add_argument("--server-releases", required=True, type=Path)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--server-key", required=True)
    parser.add_argument("--client-version", required=True)
    parser.add_argument("--bootstrap-input", required=True, type=Path)
    parser.add_argument("--bootstrap-output", required=True, type=Path)
    parser.add_argument("--bootstrap-id", required=True)
    parser.add_argument("--stage-two", required=True, type=Path)
    parser.add_argument("--stage-two-id", required=True)
    parser.add_argument("--stage-three", required=True, type=Path)
    parser.add_argument("--stage-three-id", required=True)
    parser.add_argument("--stage-four", required=True, type=Path)
    parser.add_argument("--stage-four-id", required=True)
    args = parser.parse_args()

    manifest_path = args.server_releases / "release-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    release_id = str(manifest["release"])
    digest = str(manifest["protectedDigest"])
    installer_channel_id, installer_channel_version, _ = launcher_channel()
    installer_compatibility = installer_compatibility_digests(args.server_releases, digest)
    descriptor = release_descriptor(
        args.base_url,
        args.server_key,
        release_id,
        digest,
        args.client_version,
    )
    configure(args.bootstrap_input, args.bootstrap_output, descriptor)

    stages = []
    for source, filename, loader_id in (
        (args.stage_two, "stage-two.bin", args.stage_two_id),
        (args.stage_three, "stage-three.bin", args.stage_three_id),
        (args.stage_four, "stage-four.bin", args.stage_four_id),
    ):
        target = args.server_releases / filename
        target.write_bytes(source.read_bytes())
        stages.append(
            {
                "id": loader_id,
                "file": filename,
                "sha256": sha256(target),
                "size": target.stat().st_size,
            }
        )

    manifest["bootstrap"] = {
        "id": args.bootstrap_id,
        "file": args.bootstrap_output.name,
        "sha256": sha256(args.bootstrap_output),
        "size": args.bootstrap_output.stat().st_size,
    }
    manifest["loader"] = stages[0]
    manifest["loaderThree"] = stages[1]
    manifest["loaderFour"] = stages[2]
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    chunks = manifest["chunks"]
    environment = (
        f"CELESTE_RELEASE_ID={release_id}\n"
        f"CELESTE_RELEASE_SHA256={digest}\n"
        f"CELESTE_INSTALLER_CHANNEL_ID={installer_channel_id}\n"
        f"CELESTE_INSTALLER_CHANNEL_VERSION={installer_channel_version}\n"
        f"CELESTE_INSTALLER_COMPAT_SHA256={','.join(installer_compatibility)}\n"
        f"CELESTE_CLIENT_VERSION={args.client_version}\n"
        f"CELESTE_CHUNK_A_ID={chunks[0]['id']}\n"
        f"CELESTE_CHUNK_B_ID={chunks[1]['id']}\n"
        f"CELESTE_CHUNK_A_SHA256={chunks[0]['sha256']}\n"
        f"CELESTE_CHUNK_B_SHA256={chunks[1]['sha256']}\n"
        f"CELESTE_BOOTSTRAP_ID={args.bootstrap_id}\n"
        f"CELESTE_BOOTSTRAP_SHA256={manifest['bootstrap']['sha256']}\n"
        f"CELESTE_BOOTSTRAP_FILENAME={manifest['bootstrap']['file']}\n"
        f"CELESTE_LOADER_ID={stages[0]['id']}\n"
        f"CELESTE_LOADER_SHA256={stages[0]['sha256']}\n"
        f"CELESTE_LOADER_FILENAME={stages[0]['file']}\n"
        f"CELESTE_LOADER_THREE_ID={stages[1]['id']}\n"
        f"CELESTE_LOADER_THREE_SHA256={stages[1]['sha256']}\n"
        f"CELESTE_LOADER_THREE_FILENAME={stages[1]['file']}\n"
        f"CELESTE_LOADER_FOUR_ID={stages[2]['id']}\n"
        f"CELESTE_LOADER_FOUR_SHA256={stages[2]['sha256']}\n"
        f"CELESTE_LOADER_FOUR_FILENAME={stages[2]['file']}\n"
    )
    (args.server_releases / "release.env").write_text(environment, encoding="ascii")
    print(json.dumps(manifest, separators=(",", ":")))


if __name__ == "__main__":
    main()
