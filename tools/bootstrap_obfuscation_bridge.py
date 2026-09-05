#!/usr/bin/env python3
"""Isolate bundled MixinExtras while Celeste's bootstrap/auth classes are obfuscated."""

from __future__ import annotations

import argparse
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo


MIXINEXTRAS_PREFIX = "com/llamalad7/mixinextras/"


def write_entry(target: ZipFile, info: ZipInfo, data: bytes) -> None:
    clone = ZipInfo(info.filename, info.date_time)
    clone.comment = info.comment
    clone.extra = info.extra
    clone.create_system = info.create_system
    clone.create_version = info.create_version
    clone.extract_version = info.extract_version
    clone.flag_bits = info.flag_bits
    clone.external_attr = info.external_attr
    clone.internal_attr = info.internal_attr
    clone.volume = info.volume
    clone.compress_type = info.compress_type if not info.is_dir() else ZIP_DEFLATED
    target.writestr(clone, data)


def prepare(source: Path, thin: Path) -> None:
    thin.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(source, "r") as original, ZipFile(thin, "w") as output:
        for info in original.infolist():
            if info.filename.startswith(MIXINEXTRAS_PREFIX):
                continue
            write_entry(output, info, original.read(info.filename))


def merge(source: Path, protected_thin: Path, output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    names: set[str] = set()
    with ZipFile(protected_thin, "r") as protected, ZipFile(source, "r") as original, ZipFile(output, "w") as merged:
        for info in protected.infolist():
            write_entry(merged, info, protected.read(info.filename))
            names.add(info.filename)
        for info in original.infolist():
            if not info.filename.startswith(MIXINEXTRAS_PREFIX) or info.filename in names:
                continue
            write_entry(merged, info, original.read(info.filename))


def main() -> None:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    prepare_parser = subparsers.add_parser("prepare")
    prepare_parser.add_argument("--source", type=Path, required=True)
    prepare_parser.add_argument("--thin", type=Path, required=True)
    merge_parser = subparsers.add_parser("merge")
    merge_parser.add_argument("--source", type=Path, required=True)
    merge_parser.add_argument("--protected-thin", type=Path, required=True)
    merge_parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    if arguments.command == "prepare":
        prepare(arguments.source, arguments.thin)
    else:
        merge(arguments.source, arguments.protected_thin, arguments.output)


if __name__ == "__main__":
    main()
