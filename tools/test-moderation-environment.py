"""No provider calls: prove startup uses only environment and prebuilt artifacts."""
from contextlib import redirect_stderr
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("moderator", Path(__file__).with_name("run-moderation.py"))
moderator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(moderator)

class Environment(unittest.TestCase):
    def test_missing_environment_fails_without_a_secret_provider_or_network(self):
        with patch.dict("os.environ", {}, clear=True), patch.object(moderator.subprocess, "run") as process, patch.object(moderator.urllib.request, "build_opener") as opener, redirect_stderr(io.StringIO()):
            self.assertEqual(moderator.run(), 1)
            process.assert_not_called()
            opener.assert_not_called()

    def test_requires_a_prebuilt_runner_instead_of_building_at_startup(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict("os.environ", {"BFG_MODERATOR_TOKEN":"fixture-only"}, clear=True), patch.object(moderator, "ROOT", Path(directory)), patch.object(moderator.subprocess, "run") as process, redirect_stderr(io.StringIO()):
            self.assertEqual(moderator.run(), 1)
            process.assert_not_called()

    def test_launches_java_and_reports_its_actual_outcome(self):
        for exit_code in (0, 1):
            with self.subTest(exit_code=exit_code), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                compiled = root / "workbench/build"
                compiled.mkdir(parents=True)
                (compiled / "moderation-classpath.txt").write_text(str(compiled))
                opener = MagicMock()
                opener.open.return_value.__enter__.return_value.status = 200
                with patch.dict("os.environ", {"BFG_MODERATOR_TOKEN":"fixture-only"}, clear=True), patch.object(moderator, "ROOT", root), patch.object(moderator.subprocess, "run", return_value=MagicMock(returncode=exit_code)) as process, patch.object(moderator.urllib.request, "build_opener", return_value=opener):
                    self.assertEqual(moderator.run(), exit_code)
                    args = process.call_args.args[0]
                    self.assertEqual(args[0], "java")
                    self.assertEqual(args[-1], "com.bfg.watchfaces.workbench.Moderate")
                    self.assertNotIn("fixture-only", " ".join(args))
                    request = opener.open.call_args.args[0]
                    self.assertEqual(json.loads(request.data), {"success": exit_code == 0})

if __name__ == "__main__":
    unittest.main()
