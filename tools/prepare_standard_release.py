"""Build a fresh protected portable release from already-built standard artifacts."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def sha(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-id", required=True)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--obfuscator", type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,60}", args.release_id):
        parser.error("Invalid release identity")
    out = ROOT / "build/module-qa" / args.release_id
    out.mkdir(parents=True, exist_ok=False)
    log_root = out / "logs"
    log_root.mkdir()

    def run(label, command):
        with (log_root / (label + ".log")).open("w", encoding="utf-8") as log:
            result = subprocess.run([str(v) for v in command], cwd=ROOT, stdout=log,
                                    stderr=subprocess.STDOUT,
                                    creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        if result.returncode:
            raise RuntimeError(f"{label} failed; see {log_root / (label + '.log')}")
        print(f"Passed {label}", flush=True)

    bootstrap = ROOT / "bootstrap-loader/build/libs/celeste-bootstrap-1.0.0.jar"
    run("bootstrap-thin", [sys.executable, ROOT / "tools/bootstrap_obfuscation_bridge.py", "prepare",
                            "--source", bootstrap, "--thin", out / "bootstrap-thin.jar"])
    prepared = {}
    inputs = {}
    profiles = ROOT / "obfuscation/release-profiles"
    # Preserve Minecraft/JNI/reflection boundaries while protecting all
    # implementation bodies. Framework auto-discovery can accidentally treat
    # archived releases in a build directory as external dependencies.
    payload_source = ROOT / "build/libs/celeste-client-1.0.0.jar"
    payload_stage = out / "payload-stage"
    payload_tool = ROOT / "beta-lab/obfuscation/prepare_obfuscation.py"
    run("payload-stage", [sys.executable, payload_tool, "stage-payload", "--source", payload_source,
                          "--output", payload_stage])
    payload_profiles = ROOT / "beta-lab/obfuscation/profiles"
    payload_profile = json.loads((payload_profiles / "04-payload-protected-v2.json").read_text(encoding="utf-8"))
    payload_profile["inputJar"] = str(payload_stage / "payload-thin.jar")
    payload_profile["outputJar"] = str(out / "payload-implementation.jar")
    payload_profile["dependencyJars"] = [str((payload_profiles / value).resolve()) for value in payload_profile["dependencyJars"]]
    payload_profile["dependencyJars"][0] = str(payload_stage / "payload-stable.jar")
    payload_config = out / "client.json"
    payload_config.write_text(json.dumps(payload_profile, indent=2) + "\n", encoding="utf-8")
    inputs["client"] = {"path": str(payload_source), "sha256": sha(payload_source), "profile_sha256": sha(payload_config)}
    run("protect-client", [args.java, "-Xmx3g", "-jar", args.obfuscator, "--config", payload_config])
    prepared["client"] = out / "client-protected.jar"
    run("merge-client", [sys.executable, payload_tool, "merge-payload", "--stage", payload_stage,
        "--protected", out / "payload-implementation.jar", "--mapping", out / "payload-implementation.jar.mapping.json",
        "--output", prepared["client"]])
    run("check-client-boundaries", [sys.executable, payload_tool, "check-payload-boundaries",
                                    "--source", payload_source, "--protected", prepared["client"]])
    require_source_digest = inputs["client"]["sha256"]
    if sha(payload_source) != require_source_digest:
        raise RuntimeError("Payload source changed during protection")
    for number, name, source in (
        ("02", "stage-two", ROOT / "stage-two-loader/build/libs/celeste-stage-two-1.0.0.jar"),
        ("03", "bootstrap-thin", out / "bootstrap-thin.jar"),
        ("04", "stage-three", ROOT / "stage-three-loader/build/libs/celeste-stage-three-1.0.0.jar"),
        ("05", "stage-four", ROOT / "stage-four-loader/build/libs/celeste-stage-four-1.0.0.jar"),
    ):
        profile_path, = profiles.glob(number + "-*.json")
        profile = json.loads(profile_path.read_text(encoding="utf-8"))
        target = out / (name + "-protected.jar")
        profile["inputJar"] = str(source)
        profile["outputJar"] = str(target)
        profile["dependencyJars"] = [str((profiles / value).resolve()) for value in profile["dependencyJars"]]
        for dependency in profile["dependencyJars"]:
            if not Path(dependency).is_file():
                raise RuntimeError(f"Missing obfuscator dependency: {dependency}")
        # The installed native compiler emits Windows x64 DLLs. This shared
        # Fabric release must execute on Windows, macOS and Linux.
        profile.update(nativeTranspile=False, nativeVirtualization=False,
                       targetedNativeVirtualization=False, virtualizeExistingNativeLibraries=False)
        staged_profile = out / (name + ".json")
        staged_profile.write_text(json.dumps(profile, indent=2) + "\n", encoding="utf-8")
        inputs[name] = {"path": str(source), "sha256": sha(source), "profile_sha256": sha(staged_profile)}
        run("protect-" + name, [args.java, "-Xmx3g", "-jar", args.obfuscator, "--config", staged_profile])
        if inputs[name]["sha256"] != sha(source):
            raise RuntimeError(f"Source changed during protection: {name}")
        prepared[name] = target
    protected_bootstrap = out / "bootstrap-protected.jar"
    run("bootstrap-merge", [sys.executable, ROOT / "tools/bootstrap_obfuscation_bridge.py", "merge",
                            "--source", bootstrap, "--protected-thin", prepared["bootstrap-thin"],
                            "--output", protected_bootstrap])
    release = out / "release"
    release.mkdir()
    public = (ROOT / "native-verifier/src/release.rs").read_text(encoding="utf-8")
    base = re.search(r'pub const BASE_URL: &str = "([^"]+)";', public).group(1)
    key = re.search(r'pub const SERVER_KEY: &str = "([^"]+)";', public).group(1)
    run("package", [sys.executable, ROOT / "tools/package_final_release.py",
        "--input", prepared["client"], "--output", release / "celeste-client-1.0.0-configured.jar",
        "--server-releases", release, "--base-url", base, "--server-key", key,
        "--release-id", args.release_id, "--client-version", "1.0.0",
        "--chunk-a-id", args.release_id + "-a", "--chunk-b-id", args.release_id + "-b",
        "--stage-two", prepared["stage-two"], "--loader-id", args.release_id + "-s2",
        "--stage-three", prepared["stage-three"], "--loader-three-id", args.release_id + "-s3",
        "--stage-four", prepared["stage-four"], "--loader-four-id", args.release_id + "-s4",
        "--bootstrap-input", protected_bootstrap, "--bootstrap-output", release / "Celeste.jar",
        "--bootstrap-id", args.release_id + "-bootstrap"])
    receipt = {"release": args.release_id, "status": "packaged-not-deployed", "inputs": inputs,
               "obfuscator_sha256": sha(args.obfuscator),
               "artifacts": {p.name: {"sha256": sha(p), "size": p.stat().st_size} for p in release.iterdir() if p.is_file()}}
    (out / "preparation.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": receipt["status"], "directory": str(release)}))


if __name__ == "__main__":
    main()
