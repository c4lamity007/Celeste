"""Generate fresh, isolated obfuscation configs from the reviewed beta profiles."""
from pathlib import Path
import argparse
import json
import re


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-id", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,79}", args.release_id):
        parser.error("Use an alphanumeric release ID with hyphens/underscores")
    root = Path(__file__).resolve().parents[2]
    out = root / "build/module-qa/obfuscation" / args.release_id
    out.mkdir(parents=True, exist_ok=False)
    source = Path(__file__).parent / "profiles"
    for name, stage, input_name, output_name in (
        ("04-payload-protected-v2.json", "payload-stage", "payload-thin.jar", "payload-implementation.jar"),
        ("02-bootstrap-a-implementation.json", "a-stage", "bootstrap-a-thin.jar", "bootstrap-a-implementation.jar"),
        ("03-bootstrap-b-compatible.json", None, "bootstrap-b.bin", "bootstrap-b.bin"),
    ):
        config = json.loads((source / name).read_text(encoding="utf-8"))
        config["inputJar"] = str(out / stage / input_name) if stage else str(root / "build/module-qa/bootstrap-b/bootstrap-b.bin")
        config["outputJar"] = str(out / output_name)
        config["dependencyJars"] = [str((source / value).resolve()) for value in config["dependencyJars"]]
        config["dependencyJars"][0] = str(out / (stage or "a-stage") / ("payload-stable.jar" if stage == "payload-stage" else "bootstrap-a-stable.jar"))
        (out / name).write_text(json.dumps(config, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": "configured", "directory": str(out)}))


if __name__ == "__main__":
    main()
