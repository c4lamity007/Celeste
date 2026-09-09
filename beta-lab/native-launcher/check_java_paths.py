"""Offline Windows JVM path probe. Creates only its fixture/JAR/report under build/module-qa.

No Attach API, target process, credentials, game files, network or application UI.
Exit 0: ordinary paths pass; 2: only non-native-codepage Unicode launch is unsupported;
1: baseline spaces/Latin/long ordinary launch or NIO identity check failed.
Verbatim-path launch failures are observations, not baseline failures.
"""
from __future__ import annotations

import argparse
import base64
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile


SOURCE = r'''
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.jar.JarFile;

public final class OfflinePathProbe {
    private static void text(String key, String value) {
        System.out.println(key + "=" + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected ordinary and verbatim fixture JAR paths");
        System.out.println("PROBE_OK");
        text("JAVA_VERSION", System.getProperty("java.version"));
        text("NATIVE_ENCODING", System.getProperty("native.encoding"));
        for (int i = 0; i < args.length; i++) args[i] = new String(Base64.getDecoder().decode(args[i]), StandardCharsets.UTF_8);
        for (int i = 0; i < args.length; i++) {
            Path path = Path.of(args[i]);
            Path real = path.toRealPath();
            text("PATH_" + i, path.toString());
            text("REAL_" + i, real.toString());
            try (JarFile jar = new JarFile(real.toFile())) {
                if (jar.getEntry("OfflinePathProbe.class") == null) throw new IllegalStateException("Fixture missing");
            }
        }
        if (!Files.isSameFile(Path.of(args[0]), Path.of(args[1]))) throw new IllegalStateException("Different file identity");
        System.out.println("SAME_FILE=true");
    }
}
'''


def extended(path: Path) -> str:
    text = str(path.absolute())
    if text.startswith("\\\\?\\"):
        return text
    if text.startswith("\\\\"):
        return "\\\\?\\UNC\\" + text[2:]
    return "\\\\?\\" + text


def run(args: list[str], timeout: int = 30) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, stdin=subprocess.DEVNULL, capture_output=True, encoding="utf-8", errors="replace", timeout=timeout, check=False)


def decode(stdout: str) -> dict[str, str]:
    result = {}
    for line in stdout.splitlines():
        if line.startswith(("JAVA_VERSION=", "NATIVE_ENCODING=", "PATH_", "REAL_")):
            name, encoded = line.split("=", 1)
            result[name] = base64.b64decode(encoded).decode("utf-8")
    return result


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8", errors="backslashreplace")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, default=Path(r"C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"))
    parser.add_argument("--project-root", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    if os.name != "nt":
        parser.error("This is a Windows-specific path regression probe")
    project = args.project_root.resolve(strict=True)
    destination = project / "build" / "module-qa" / ("java-paths-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ"))
    if project not in destination.parents:
        raise RuntimeError("Output must stay under the selected project")
    destination.mkdir(parents=True, exist_ok=False)
    source = destination / "OfflinePathProbe.java"
    source.write_text(SOURCE, encoding="utf-8")
    compiled = destination / "classes"
    compiled.mkdir()
    javac, java = args.java_home / "bin" / "javac.exe", args.java_home / "bin" / "java.exe"
    compilation = run([str(javac), "--release", "21", "-encoding", "UTF-8", "-Xlint:all", "-Werror", "-d", str(compiled), str(source)])
    (destination / "compile.log").write_text(compilation.stdout + compilation.stderr, encoding="utf-8")
    if compilation.returncode:
        print(compilation.stderr)
        return 1
    cases = [("spaces", destination / "ordinary path with spaces" / "fixture jar.jar"),
             ("unicode_latin", destination / "café" / "fixture é.jar"),
             ("unicode", destination / "café 日本語 Ω" / "fixture é.jar")]
    long_path = destination / "long ordinary path"
    while len(str(long_path / "fixture long.jar")) <= 315:
        long_path /= "segment with spaces ASCII 0123456789"
    cases.append(("long", long_path / "fixture long.jar"))
    class_bytes = (compiled / "OfflinePathProbe.class").read_bytes()
    rows = []
    for name, jar in cases:
        os.makedirs(extended(jar.parent), exist_ok=False)
        with zipfile.ZipFile(extended(jar), "w", compression=zipfile.ZIP_STORED) as package:
            package.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nMain-Class: OfflinePathProbe\r\n\r\n")
            package.writestr("OfflinePathProbe.class", class_bytes)
        ordinary, verbatim = str(jar), extended(jar)
        encoded = [base64.b64encode(value.encode("utf-8")).decode("ascii") for value in (ordinary, verbatim)]
        for style, launch_path in (("ordinary", ordinary), ("verbatim", verbatim), ("nio_via_ascii_launcher", str(cases[0][1]))):
            command = [str(java), "-jar", launch_path, *encoded]
            process = run(command)
            detail = decode(process.stdout)
            passed = process.returncode == 0 and "PROBE_OK" in process.stdout and "SAME_FILE=true" in process.stdout
            row = {"case": name, "style": style, "jar_path_chars": len(ordinary), "exit_code": process.returncode,
                   "passed": passed, "jar": ordinary, "nio": detail, "stdout": process.stdout, "stderr": process.stderr}
            rows.append(row)
            (destination / f"{name}-{style}.log").write_text(process.stdout + process.stderr, encoding="utf-8")
            print(f"{name}/{style}: {'PASS' if passed else 'FAIL'}; ordinary path length={len(ordinary)}; exit={process.returncode}")
    report = {"created_at": datetime.now(timezone.utc).isoformat(), "java_home": str(args.java_home),
              "scope": "Offline fixture only; no attach. UNC requires a real authorized share and is not tested here.", "cases": rows}
    report_path = destination / "results.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Report: {report_path}")
    for row in rows:
        if row["style"] == "nio_via_ascii_launcher" and row["nio"]:
            print(f"{row['case']} NIO Path.of(verbatim): {row['nio'].get('PATH_1')}")
            print(f"{row['case']} NIO toRealPath(verbatim): {row['nio'].get('REAL_1')}")
    if any(not row["passed"] for row in rows if row["style"] == "nio_via_ascii_launcher"
           or row["style"] == "ordinary" and row["case"] != "unicode"):
        return 1
    if any(not row["passed"] for row in rows if row["style"] == "ordinary"):
        print("PARTIAL: ordinary spaces/Latin/328+ character paths and all NIO identity probes pass; non-native-codepage Unicode -jar is unsupported.")
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
