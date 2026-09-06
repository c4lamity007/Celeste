"""Atmosphere runner integration without starting Gradle, Java, or a game."""
import argparse
from contextlib import redirect_stdout
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import module_qa as qa


class AtmosphereWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.project = self.root / "project"
        self.project.mkdir()
        (self.project / "gradlew.bat").write_text("fixture", encoding="utf-8")
        self.outputs = self.root / "runs"

    def planned(self, failure=None):
        args = qa.parser().parse_args([
            "check", "--project", str(self.project), "--output", str(self.outputs),
            "--module", "Skybox", "--module", "Custom Fog", "--module", "Ambient Effects",
            "--suite", "atmosphere", "--java-home", str(self.root / "jdk 21")])

        def result(run, name, command, cwd, env, timeout):
            return {"name": name, "status": "fail" if name == failure else "pass", "log": name + ".log"}

        with redirect_stdout(io.StringIO()), patch.object(qa, "run_step", side_effect=result) as steps:
            code = args.func(args)
        calls = steps.call_args_list
        return code, calls, qa.load(calls[0].args[0])

    def test_inherits_player_visuals_once_and_never_claims_game_pass(self):
        code, calls, data = self.planned()
        names = [call.args[1] for call in calls]
        self.assertEqual(code, 0)
        self.assertEqual(names, ["build", "visuals", "chams", "prism", "unified-volume", "local-damage", "atmosphere"])
        self.assertEqual(len(names), len(set(names)))
        self.assertEqual(data["status"], "checks-passed")
        self.assertEqual(data["runtime"]["status"], "pending")
        self.assertEqual(data["evidence"], [])
        command = calls[-1].args[2]
        self.assertEqual(Path(command[command.index("-File") + 1]).name, "run-atmosphere.ps1")
        self.assertEqual(command[command.index("-ProjectRoot") + 1], str(self.project.resolve()))
        self.assertEqual(command[command.index("-JdkHome") + 1], str((self.root / "jdk 21").resolve()))

    def test_fail_fast_including_new_runner(self):
        names = ["build", "visuals", "chams", "prism", "unified-volume", "local-damage", "atmosphere"]
        for failure in ("build", "chams", "atmosphere"):
            with self.subTest(failure=failure):
                code, calls, data = self.planned(failure)
                self.assertEqual(code, 1)
                self.assertEqual([call.args[1] for call in calls], names[:names.index(failure) + 1])
                self.assertEqual(data["status"], "fail")

    def test_explicit_jdk_required_before_any_work(self):
        args = argparse.Namespace(project=self.project, suite="atmosphere", java_home=None)
        with patch.object(qa, "run_step") as steps:
            with self.assertRaisesRegex(ValueError, "atmosphere suite requires --java-home"):
                qa.check(args)
            steps.assert_not_called()
        self.assertFalse(self.outputs.exists())


if __name__ == "__main__":
    unittest.main()
