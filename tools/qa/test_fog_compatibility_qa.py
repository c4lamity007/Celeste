"""Renderer compatibility workflow planning; no Gradle, game, or network access."""
from contextlib import redirect_stdout
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import module_qa as qa


class FogCompatibilityWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.project = self.root / "project"
        self.project.mkdir()
        (self.project / "gradlew.bat").write_text("fixture", encoding="utf-8")

    def arguments(self, extra=()):
        return qa.parser().parse_args([
            "check", "--project", str(self.project), "--module", "Custom Fog",
            "--suite", "fog-compatibility", "--java-home", str(self.root / "jdk 21"), *extra])

    def planned(self, failure=None, extra=()):
        def result(run, name, command, cwd, env, timeout):
            return {"name": name, "status": "fail" if name == failure else "pass", "log": name + ".log"}
        args = self.arguments(extra)
        with redirect_stdout(io.StringIO()), patch.object(qa, "run_step", side_effect=result) as steps:
            code = args.func(args)
        return code, steps.call_args_list, qa.load(steps.call_args_list[0].args[0])

    def test_focused_checks_never_infer_runtime_pass(self):
        code, calls, data = self.planned()
        self.assertEqual(code, 0)
        self.assertEqual([call.args[1] for call in calls], ["build", "atmosphere", "fog-compatibility", "shader-fog"])
        self.assertEqual(data["status"], "checks-passed")
        self.assertEqual(data["runtime"]["status"], "pending")
        self.assertEqual(data["evidence"], [])
        for call in calls[1:]:
            command = call.args[2]
            self.assertEqual(command[command.index("-JdkHome") + 1], str((self.root / "jdk 21").resolve()))

    def test_selected_sodium_artifact_only_reaches_compatibility_runner(self):
        artifact = self.root / "installed sodium.jar"
        code, calls, _ = self.planned(extra=["--sodium-jar", str(artifact)])
        self.assertEqual(code, 0)
        self.assertNotIn("-SodiumJar", calls[1].args[2])
        self.assertNotIn("-SodiumJar", calls[-1].args[2])
        command = calls[2].args[2]
        self.assertEqual(Path(command[command.index("-File") + 1]).name, "run-fog-compatibility.ps1")
        self.assertEqual(command[command.index("-SodiumJar") + 1], str(artifact.resolve()))

    def test_failure_stops_checks_and_reports_failure(self):
        names = ["build", "atmosphere", "fog-compatibility", "shader-fog"]
        for failed in names:
            with self.subTest(failed=failed):
                code, calls, data = self.planned(failed)
                self.assertEqual(code, 1)
                self.assertEqual([call.args[1] for call in calls], names[:names.index(failed) + 1])
                self.assertEqual(data["status"], "fail")

    def test_requires_explicit_jdk_before_starting(self):
        args = self.arguments()
        args.java_home = None
        with patch.object(qa, "run_step") as steps:
            with self.assertRaisesRegex(ValueError, "fog-compatibility suite requires --java-home"):
                qa.check(args)
            steps.assert_not_called()


if __name__ == "__main__":
    unittest.main()
