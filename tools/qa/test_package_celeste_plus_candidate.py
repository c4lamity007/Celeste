import json
import plistlib
import struct
import tarfile
import tempfile
import unittest
from pathlib import Path
import sys
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
from package_celeste_plus_candidate import executable_format, package


class CandidatePackageTest(unittest.TestCase):
    def test_formats(self):
        elf = bytearray(20)
        elf[:6] = b"\x7fELF\x02\x01"
        struct.pack_into("<H", elf, 18, 62)
        self.assertEqual(executable_format(elf), "elf-x86_64")
        self.assertEqual(
            executable_format(b"\xcf\xfa\xed\xfe" + struct.pack("<I", 0x0100000C)),
            "macho-arm64",
        )
        self.assertEqual(
            executable_format(b"\xcf\xfa\xed\xfe" + struct.pack("<I", 0x01000007)),
            "macho-x86_64",
        )
        pe = bytearray(72)
        pe[:2] = b"MZ"
        struct.pack_into("<I", pe, 0x3C, 64)
        pe[64:68] = b"PE\0\0"
        struct.pack_into("<H", pe, 68, 0x8664)
        self.assertEqual(executable_format(pe), "pe-x86_64")
        with self.assertRaises(ValueError):
            executable_format(b"MZ")

    def test_package_preserves_executable_mode_and_manifest(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            binary = root / "CelesteOwnerBeta"
            value = bytearray(24)
            value[:6] = b"\x7fELF\x02\x01"
            struct.pack_into("<H", value, 18, 62)
            binary.write_bytes(value)
            report = root / "abi.txt"
            report.write_text("maximum referenced GLIBC symbol: GLIBC_2.35\n")
            result = package(
                binary,
                "linux-x64",
                "x86_64-unknown-linux-gnu",
                root / "candidate",
                report,
            )
            archive = Path(result["archive"])
            with tarfile.open(archive, "r:gz") as bundled:
                executable = bundled.getmember("CelesteOwnerBeta")
                self.assertEqual(executable.mode, 0o755)
                embedded = json.load(bundled.extractfile("build-manifest.json"))
                self.assertEqual(bundled.extractfile("native-abi.txt").read(), report.read_bytes())
            external = json.loads((archive.parent / "build-manifest.json").read_text())
            self.assertEqual(embedded, external)
            self.assertEqual(embedded["distribution_status"], "unsigned-test-candidate")
            self.assertEqual(embedded["executable_format"], "elf-x86_64")

    def test_platform_target_and_binary_must_agree(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            binary = root / "candidate"
            binary.write_bytes(b"\xcf\xfa\xed\xfe" + struct.pack("<I", 0x0100000C))
            with self.assertRaises(ValueError):
                package(binary, "macos-arm64", "x86_64-apple-darwin", root / "wrong-target")
            with self.assertRaises(ValueError):
                package(binary, "macos-x64", "x86_64-apple-darwin", root / "wrong-format")

    def test_macos_candidate_is_an_app_bundle(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            binary = root / "CelesteOwnerBeta"
            binary.write_bytes(b"\xcf\xfa\xed\xfe" + struct.pack("<I", 0x0100000C))
            result = package(
                binary,
                "macos-arm64",
                "aarch64-apple-darwin",
                root / "candidate",
            )
            with tarfile.open(result["archive"], "r:gz") as bundled:
                executable = bundled.getmember(
                    "Celeste+.app/Contents/MacOS/CelesteOwnerBeta"
                )
                self.assertEqual(executable.mode, 0o755)
                plist = plistlib.loads(
                    bundled.extractfile("Celeste+.app/Contents/Info.plist").read()
                )
                self.assertEqual(plist["CFBundleExecutable"], "CelesteOwnerBeta")
                self.assertEqual(plist["CFBundleIdentifier"], "dev.celeste.client.plus")
                embedded = json.load(
                    bundled.extractfile(
                        "Celeste+.app/Contents/Resources/build-manifest.json"
                    )
                )
                self.assertEqual(embedded["artifact"], "Celeste+.app")
                self.assertEqual(embedded["executable"], "CelesteOwnerBeta")

    def test_windows_candidate_uses_zip(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            binary = root / "CelesteOwnerBeta.exe"
            value = bytearray(72)
            value[:2] = b"MZ"
            struct.pack_into("<I", value, 0x3C, 64)
            value[64:68] = b"PE\0\0"
            struct.pack_into("<H", value, 68, 0x8664)
            binary.write_bytes(value)
            result = package(
                binary,
                "windows-x64",
                "x86_64-pc-windows-msvc",
                root / "candidate",
            )
            self.assertTrue(result["archive"].endswith(".zip"))
            with zipfile.ZipFile(result["archive"]) as bundled:
                self.assertEqual(
                    json.loads(bundled.read("build-manifest.json"))["artifact"],
                    "CelesteOwnerBeta.exe",
                )


if __name__ == "__main__":
    unittest.main()
