"""Run the isolated owner-beta component checks; never launch Minecraft or publish."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone


ROOT = Path(__file__).resolve().parents[1]


def fingerprint() -> str:
    paths = [ROOT / "auth-service/app/config.py", ROOT / "auth-service/app/main.py",
             ROOT / "auth-service/app/beta_delivery.py", ROOT / "auth-service/tests/test_beta_delivery.py",
             ROOT / "auth-service/tests/test_beta_runtime.py"]
    paths.extend(path for path in (ROOT / "beta-lab").rglob("*")
                 if path.is_file() and not {"build", "__pycache__"}.intersection(path.relative_to(ROOT).parts))
    client_root = ROOT / "src/main/java/dev/c4lam/celeste"
    # Shared callers are part of the compatibility result as well as the adapters.
    paths.extend(client_root.rglob("*.java"))
    paths.extend((ROOT / "src/main/java/dev/celestebeta/host").glob("*.java"))
    digest = hashlib.sha256()
    for path in sorted(set(paths)):
        digest.update(path.relative_to(ROOT).as_posix().encode("utf-8") + b"\0")
        digest.update(hashlib.sha256(path.read_bytes()).digest())
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", default=os.getenv("JAVA_HOME", ""))
    parser.add_argument("--checks", nargs="+", choices=("attach", "minecraft-linkage", "memory", "memory-integrity", "integrity", "auth", "client-protocol", "delivery-client", "reload-hooks", "member-access", "visual-hooks", "interaction-hooks", "rotation-hooks", "atmosphere-hooks", "gameplay-callbacks", "fake-gamble-hooks", "combined-hooks", "bootstrap-a", "bootstrap-b", "runtime-lifecycle", "payload-session", "callbacks", "resources", "delivery"),
                        help="Run only the affected checks; omit to run every component")
    args = parser.parse_args()
    if not args.java_home or not (Path(args.java_home) / "bin/javac.exe").is_file():
        parser.error("--java-home must name a full Windows Java 21 JDK")
    powershell = shutil.which("pwsh") or shutil.which("powershell")
    if powershell is None:
        parser.error("PowerShell is required")
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    run = ROOT / "build/beta-lab" / (stamp + "-" + os.urandom(3).hex())
    run.mkdir(parents=True, exist_ok=False)
    before = fingerprint()
    commands = {
        "attach": ("attach-probe", ROOT, [powershell, "-NoProfile", "-NonInteractive", "-File",
            str(ROOT / "beta-lab/attach-probe/run.ps1"), "-JavaHome", args.java_home]),
        "minecraft-linkage": ("minecraft-linkage-fixture", ROOT, [powershell, "-NoProfile", "-NonInteractive", "-File",
            str(ROOT / "beta-lab/minecraft-linkage/build.ps1"), "-JdkHome", args.java_home, "-RunFixture"]),
        "memory": ("memory-runtime", ROOT, [powershell, "-NoProfile", "-NonInteractive", "-File",
            str(ROOT / "beta-lab/memory-runtime/run-tests.ps1"), "-JdkHome", args.java_home]),
        "integrity": ("integrity-adapter", ROOT, [powershell, "-NoProfile", "-NonInteractive", "-File",
            str(ROOT / "beta-lab/integrity-adapter/run-tests.ps1"), "-JdkHome", args.java_home]),
        "memory-integrity": ("memory-integrity", ROOT, [powershell, "-NoProfile", "-NonInteractive", "-File",
            str(ROOT / "beta-lab/memory-integrity/run-tests.ps1"), "-JdkHome", args.java_home]),
        "auth": ("auth-lifecycle", ROOT, [powershell, "-NoProfile", "-NonInteractive", "-File",
            str(ROOT / "beta-lab/auth-lifecycle/run-tests.ps1"), "-JdkHome", args.java_home]),
        "client-protocol": ("beta-client-protocol", ROOT,
            [str(ROOT / "gradlew.bat"), "--no-daemon", "--console=plain", "-I",
             "beta-lab/beta-client-protocol/check.init.gradle", "betaClientProtocolCheck"]),
        "delivery-client": ("beta-delivery-client", ROOT,
            [str(ROOT / "gradlew.bat"), "--no-daemon", "--console=plain", "-I",
             "beta-lab/delivery-client/check.init.gradle", "betaDeliveryClientCheck"]),
        "reload-hooks": ("beta-reload-hooks", ROOT,
            [str(ROOT / "gradlew.bat"), "--no-daemon", "--console=plain", "-I",
             "beta-lab/reload-hooks/check.init.gradle", "betaReloadHooksCheck"]),
        "member-access": ("beta-member-access", ROOT,
            [str(ROOT / "gradlew.bat"), "--no-daemon", "--console=plain", "-I",
             "beta-lab/member-access/check.init.gradle", "betaMemberAccessCheck"]),
        "visual-hooks": ("beta-visual-hooks", ROOT,
            [str(ROOT / "gradlew.bat"), "--no-daemon", "--console=plain", "-I",
             "beta-lab/visual-hooks/check.init.gradle", "betaVisualHooksCheck"]),
        "callbacks": ("beta-callbacks", ROOT,
            [str(ROOT / "gradlew.bat"), "--no-daemon", "--console=plain", "-I",
             "beta-lab/callbacks/check.init.gradle", "betaCallbacksCheck"]),
        "interaction-hooks": ("beta-interaction-hooks", ROOT,
            [str(ROOT / "gradlew.bat"), "--no-daemon", "--console=plain", "-I",
             "beta-lab/interaction-hooks/check.init.gradle", "betaInteractionHooksCheck"]),
        "rotation-hooks": ("beta-rotation-hooks", ROOT,
            [str(ROOT / "gradlew.bat"), "--no-daemon", "--console=plain", "-I",
             "beta-lab/rotation-hooks/check.init.gradle", "betaRotationHooksCheck"]),
        "atmosphere-hooks": ("beta-atmosphere-hooks", ROOT,
            [str(ROOT / "gradlew.bat"), "--no-daemon", "--console=plain", "-I",
             "beta-lab/atmosphere-hooks/check.init.gradle", "betaAtmosphereHooksCheck"]),
        "gameplay-callbacks": ("beta-gameplay-callbacks", ROOT,
            [str(ROOT / "gradlew.bat"), "--no-daemon", "--console=plain", "-I",
             "beta-lab/gameplay-callbacks/check.init.gradle", "betaGameplayCallbacksCheck"]),
        "fake-gamble-hooks": ("beta-fake-gamble-hooks", ROOT,
            [str(ROOT / "gradlew.bat"), "--no-daemon", "--console=plain", "-I",
             "beta-lab/fake-gamble-hooks/check.init.gradle", "betaFakeGambleHooksCheck"]),
        "combined-hooks": ("beta-combined-hooks", ROOT,
            [str(ROOT / "gradlew.bat"), "--no-daemon", "--console=plain", "-I",
             "beta-lab/combined-hooks/check.init.gradle", "betaCombinedHooksCheck"]),
        "bootstrap-a": ("beta-bootstrap-a", ROOT,
            [str(ROOT / "gradlew.bat"), "--no-daemon", "--console=plain", "-I",
             "beta-lab/bootstrap-a/check.init.gradle", "betaBootstrapACheck"]),
        "bootstrap-b": ("beta-bootstrap-b", ROOT,
            [str(ROOT / "gradlew.bat"), "--no-daemon", "--console=plain", "-I",
             "beta-lab/bootstrap-b/check.init.gradle", "betaBootstrapBCheck"]),
        "runtime-lifecycle": ("beta-runtime-lifecycle", ROOT,
            [str(ROOT / "gradlew.bat"), "--no-daemon", "--console=plain", "-I",
             "beta-lab/runtime-lifecycle/check.init.gradle", "betaRuntimeLifecycleCheck"]),
        "payload-session": ("beta-payload-session", ROOT,
            [str(ROOT / "gradlew.bat"), "--no-daemon", "--console=plain", "-I",
             "beta-lab/payload-session/check.init.gradle", "betaPayloadSessionCheck"]),
        "resources": ("build-and-internal-resources", ROOT,
            [str(ROOT / "gradlew.bat"), "--no-daemon", "--console=plain", "-I",
             "beta-lab/resource-provider/check.init.gradle", "build", "betaInternalResourceStorageCheck",
             "betaInternalResourceIntegrationCheck"]),
        "delivery": ("owner-delivery-and-production-regression", ROOT / "auth-service",
            [sys.executable, "-m", "pytest", "tests/test_beta_delivery.py", "tests/test_beta_runtime.py", "tests/test_protocol.py",
             "-q", "-rs", "--disable-warnings"]),
    }
    selected = list(dict.fromkeys(args.checks or commands))
    environment = os.environ.copy()
    environment["JAVA_HOME"] = args.java_home
    # One Gradle startup and shared incremental compilation for selected Minecraft
    # components. A failed task marks the entire group incomplete, never passed.
    gradle_checks = [check for check in selected if Path(commands[check][2][0]).name == "gradlew.bat"]
    executions = []
    grouped = False
    for check in selected:
        if check in gradle_checks:
            if grouped:
                continue
            grouped = True
            command = [str(ROOT / "gradlew.bat"), "--no-daemon", "--console=plain", "--offline"]
            tasks = []
            for member in gradle_checks:
                original = commands[member][2]
                init = original.index("-I")
                command.extend(original[init:init + 2])
                tasks.extend(original[init + 2:])
            command.extend(dict.fromkeys(tasks))
            executions.append(("minecraft-components", ROOT, command, gradle_checks))
        else:
            name, cwd, command = commands[check]
            executions.append((name, cwd, command, [check]))
    checks = []
    for name, cwd, command, components in executions:
        started = time.monotonic()
        try:
            completed = subprocess.run(command, cwd=cwd, text=True, encoding="utf-8", errors="replace",
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=240,
                env=environment,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0, check=False)
            output, exit_code = completed.stdout, completed.returncode
        except subprocess.TimeoutExpired as exc:
            output = (exc.stdout or b"").decode("utf-8", "replace") if isinstance(exc.stdout, bytes) else (exc.stdout or "")
            output += "\nComponent check timed out.\n"
            exit_code = 124
        (run / (name + ".log")).write_text(output, encoding="utf-8")
        checks.append({"name": name, "exit_code": exit_code, "seconds": round(time.monotonic() - started, 2),
                       "log": name + ".log", "components": components})
        print(f"{name}: {'pass' if exit_code == 0 else 'FAIL'}", flush=True)
    unchanged = before == fingerprint()
    passed = unchanged and all(check["exit_code"] == 0 for check in checks)
    result = {"schema": 1, "started_utc": stamp, "source_sha256": before, "source_unchanged": unchanged,
              "status": "components-passed-integration-pending" if passed else "failed",
              "product": "celeste_plus", "fixture_license_uid": 42, "checks": checks, "selected_checks": selected,
              "minecraft_integration": "bootstrap/lifecycle/resource/integrity components implemented; selected linkage captured separately; full feature installation and playable integration unverified",
              "live_beta_enabled": False, "published": False,
              "note": "Fixture attachment and memory-loader tests are not a playable Celeste beta. Check test logs for skipped cases."}
    (run / "result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": result["status"], "report": str(run / "result.json")}))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
