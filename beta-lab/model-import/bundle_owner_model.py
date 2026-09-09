"""Add the personal Owner model to an isolated beta input, preserving the ordinary build."""
import argparse
import hashlib
import json
import struct
import zipfile
from pathlib import Path

RESOURCE = "assets/celeste/models/sahur.glb"
ATTRIBUTION = "assets/celeste/models/sahur-source.txt"
NOTICE = """Tung Tung Tung Sahur model and five animations by GipsoCartoon.
Source: https://www.gipsocartoon.com/product/109
Original archive: 1745664523977-TungTungTungSahur_Pack.zip
Original SHA-256: 0ad5c2893a4ac8012656c7ade9f733e37117b39913f1ac7040ab091cf523f016
Converted from the creator's free download for the Owner's personal beta test.
No general redistribution license was supplied in the archive. Keep this asset
out of public/general-user builds until appropriate permission is obtained.
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--client", required=True, type=Path)
    parser.add_argument("--model", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if args.output.exists() or args.output.resolve() in {args.client.resolve(), args.model.resolve()}:
        parser.error("Use a fresh output file separate from the source build and model")
    if not 20 <= args.model.stat().st_size <= 8 * 1024 * 1024:
        parser.error("Model must fit the payload's 8 MiB member limit")
    model = args.model.read_bytes()
    if struct.unpack_from("<III", model) != (0x46546C67, 2, len(model)):
        parser.error("Input is not a complete GLB 2 file")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.client) as source:
        if RESOURCE in source.namelist() or ATTRIBUTION in source.namelist():
            parser.error("Source build already includes the Owner model")
        with zipfile.ZipFile(args.output, "x", compression=zipfile.ZIP_DEFLATED) as target:
            for entry in source.infolist():
                target.writestr(entry, source.read(entry.filename))
            target.writestr(RESOURCE, model)
            target.writestr(ATTRIBUTION, NOTICE)
    with zipfile.ZipFile(args.output) as output, zipfile.ZipFile(args.client) as source:
        if output.testzip() is not None:
            raise ValueError("Bundled archive CRC check failed")
        for name in source.namelist():
            if output.read(name) != source.read(name):
                raise ValueError("Original client entry changed: " + name)
        if output.read(RESOURCE) != model:
            raise ValueError("Bundled model differs from the converted file")
    with args.output.open("rb") as stream:
        client_digest = hashlib.file_digest(stream, "sha256").hexdigest()
    report = {"output": str(args.output.resolve()), "model_bytes": len(model),
              "model_sha256": hashlib.sha256(model).hexdigest(),
              "client_sha256": client_digest}
    print(json.dumps(report))


if __name__ == "__main__":
    main()
