"""Create and verify an exact combined auth-service Docker context; never deploy."""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PUBLIC_ORIGIN = "https://qb0f90cf11f4d1d52.fly.dev"


def sha(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def checked(root: Path, name: str, digest: str, size: int):
    require(Path(name).name == name and "\\" not in name, "Invalid artifact filename")
    path = root / name
    require(path.is_file() and not path.is_symlink(), f"Missing/linked artifact: {name}")
    require(path.stat().st_size == size and sha(path) == digest, f"Artifact mismatch: {name}")
    return path


def catalogs(standard: Path, plus: Path):
    normal = json.loads((standard / "release-manifest.json").read_text(encoding="utf-8"))
    beta = json.loads((plus / "beta-release.json").read_text(encoding="utf-8"))
    require(normal["protocol"] == "cst-auth-1", "Invalid normal protocol")
    require(beta["schema"] == "celeste-beta-release-2", "Invalid plus schema")
    import re
    for identity in (normal["release"], beta["release_id"]):
        require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,79}", identity), "Invalid release identity")
    entries = [normal[key] for key in ("bootstrap", "loader", "loaderThree", "loaderFour")]
    require(len(normal["chunks"]) == 2 and len({c["id"] for c in normal["chunks"]}) == 2,
            "Normal must have two distinct payload endpoints")
    entries += normal["chunks"]
    normal_files = [checked(standard, e["file"], e["sha256"], e["size"]) for e in entries]
    normal_files += [checked(standard, "celeste-client-1.0.0-configured.jar", normal["jarSha256"], normal["jarSize"])]
    roles = {"bootstrap-a": "bootstrap-a.jar", "bootstrap-b": "bootstrap-b.bin",
             "payload-a": "payload-a.bin", "payload-b": "payload-b.bin"}
    require(len(beta["artifacts"]) == 4 and {e["id"] for e in beta["artifacts"]} == set(roles),
            "Plus must have two bootstraps and two payload endpoints")
    beta_files = []
    for entry in beta["artifacts"]:
        require(entry["filename"] == roles[entry["id"]], "Invalid plus artifact filename")
        require(entry["role"] == ("bootstrap" if entry["id"].startswith("bootstrap") else "payload"), "Invalid plus artifact role")
        beta_files.append(checked(plus, roles[entry["id"]], entry["sha256"], entry["size"]))
    return normal, beta, normal_files, beta_files


def verify(context: Path):
    receipt = json.loads((context / "staging-report.json").read_text(encoding="utf-8"))
    actual = {str(p.relative_to(context)).replace("\\", "/") for p in context.rglob("*") if p.is_file()}
    require(actual == set(receipt["files"]) | {"staging-report.json"}, "Context file inventory changed")
    for name, digest in receipt["files"].items():
        path = context / name
        require(not path.is_symlink() and sha(path) == digest, f"Staged file changed: {name}")
    require("app/dpop.py" in receipt["files"], "DPoP verifier missing from Docker context")
    config = tomllib.loads((context / "fly.toml").read_text(encoding="utf-8"))
    require(config["app"] == "qb0f90cf11f4d1d52" and config["env"]["CELESTE_PUBLIC_ORIGIN"] == PUBLIC_ORIGIN,
            "Wrong app/origin")
    standard = context / "celeste" / receipt["standard_release"]
    plus = context / "celeste-plus" / receipt["plus_release"]
    catalogs(standard, plus)
    return receipt


def stage(standard: Path, plus: Path, context: Path):
    normal, beta, normal_files, beta_files = catalogs(standard, plus)
    require(context.resolve().is_relative_to((ROOT / "build/module-qa").resolve()), "Context must be in ignored QA directory")
    context.mkdir(parents=True, exist_ok=False)
    app = ROOT / "auth-service/app"
    for source in app.rglob("*"):
        if source.is_file() and source.suffix in {".py", ".html", ".css"}:
            require(not source.is_symlink() and "__pycache__" not in source.parts, "Unexpected app source")
            target = context / "app" / source.relative_to(app)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
    for source_dir, prefix, identity, files, manifest_name in (
        (standard, "celeste", normal["release"], normal_files, "release-manifest.json"),
        (plus, "celeste-plus", beta["release_id"], beta_files, "beta-release.json"),
    ):
        target = context / prefix / identity
        target.mkdir(parents=True)
        for source in files + [source_dir / manifest_name]:
            shutil.copy2(source, target / source.name)
    for name in ("requirements.txt", "entrypoint.sh"):
        shutil.copy2(ROOT / "auth-service" / name, context / name)
    docker = (ROOT / "auth-service/Dockerfile").read_text(encoding="utf-8")
    require(docker.count("COPY releases ./releases") == 1, "Unrecognized Dockerfile artifact copy")
    docker = docker.replace("COPY releases ./releases", "COPY celeste ./celeste\nCOPY celeste-plus ./celeste-plus")
    (context / "Dockerfile").write_text(docker, encoding="utf-8")
    config = (ROOT / "auth-service/fly.toml").read_text(encoding="utf-8")
    require(config.count('  CELESTE_ARTIFACT_DIR = "/srv/auth/releases"') == 1, "Unrecognized artifact config")
    config = config.replace('  CELESTE_ARTIFACT_DIR = "/srv/auth/releases"',
        f'  CELESTE_ARTIFACT_DIR = "/srv/auth/celeste/{normal["release"]}"\n'
        f'  CELESTE_BETA_RELEASE_DIR = "/srv/auth/celeste-plus/{beta["release_id"]}"\n'
        f'  CELESTE_PUBLIC_ORIGIN = "{PUBLIC_ORIGIN}"\n'
        '  CELESTE_PLUS_ENABLED = "true"\n  CELESTE_INSTALLER_ALLOW_RUNTIME_DIGEST = "false"')
    (context / "fly.toml").write_text(config, encoding="utf-8")
    names = sorted(str(p.relative_to(context)).replace("\\", "/") for p in context.rglob("*") if p.is_file())
    ignore = "**\n" + "\n".join("!" + n for n in ["app/", "app/static/", "app/templates/", "celeste/",
        "celeste/" + normal["release"] + "/", "celeste-plus/", "celeste-plus/" + beta["release_id"] + "/", *names]) + "\n"
    (context / ".dockerignore").write_text(ignore, encoding="utf-8")
    names.append(".dockerignore")
    receipt = {"status": "verified-local-not-deployed", "standard_release": normal["release"],
               "plus_release": beta["release_id"], "files": {n: sha(context / n) for n in names}}
    (context / "staging-report.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    return verify(context)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=["stage", "verify"])
    parser.add_argument("--context", type=Path, required=True)
    parser.add_argument("--standard", type=Path)
    parser.add_argument("--plus", type=Path)
    args = parser.parse_args()
    if args.operation == "stage":
        require(args.standard is not None and args.plus is not None, "Both products are required")
        result = stage(args.standard, args.plus, args.context)
    else:
        result = verify(args.context)
    print(json.dumps({key: value for key, value in result.items() if key != "files"}))


if __name__ == "__main__":
    main()
