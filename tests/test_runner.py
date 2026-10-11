"""Regression tests for false passes, reporting, selection-independent runner behavior."""
import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from test import Runner
from coverage_tools import Coverage, metric


class RunnerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.runner = Runner(SimpleNamespace(report_dir=root / "report", coverage=False), root / "work")

    def command(self, code, **kwargs):
        with contextlib.redirect_stdout(io.StringIO()):
            return self.runner.command("fixture", "program", [sys.executable, "-c", code], **kwargs)

    def test_named_pass(self):
        self.assertTrue(self.command("print('TEST\\tPASS\\tcase\\t0.01')"))
        self.assertEqual(self.runner.cases[0]["name"], "case")

    def test_reported_failure_with_zero_exit_is_failure(self):
        self.assertFalse(self.command("print('TEST\\tFAIL\\tcase\\t0.01')"))

    def test_crash_after_passing_case_is_failure(self):
        self.assertFalse(self.command("print('TEST\\tPASS\\tcase\\t0.01');raise SystemExit(7)"))
        self.assertEqual([c["status"] for c in self.runner.cases], ["passed", "failed"])

    def test_silent_success_is_not_test_success(self):
        self.assertFalse(self.command("pass"))

    def test_build_is_not_counted_as_test(self):
        self.assertTrue(self.command("pass", parse=False))
        self.assertEqual(self.runner.cases[0]["kind"], "build")

    def test_malformed_result_cannot_pass(self):
        self.assertFalse(self.command("print('TEST\\tPASS\\tcase\\tnot-a-duration')"))

    def test_invalid_record_after_valid_record_is_failure(self):
        self.assertFalse(self.command("print('TEST\\tPASS\\tok\\t0');print('TEST\\tPASS\\tbad\\tnan')"))

    def test_negative_and_nonfinite_duration_cannot_pass(self):
        for duration in ('-1', 'nan', 'inf'):
            with self.subTest(duration=duration):
                self.assertFalse(self.command(f"print('TEST\\tPASS\\tcase\\t{duration}')"))

    def test_empty_case_name_cannot_pass(self):
        self.assertFalse(self.command("print('TEST\\tPASS\\t\\t0')"))

    def test_coverage_does_not_reuse_previous_profiles(self):
        coverage = Coverage(self.runner.report)
        stale = coverage.profiles / 'stale.profraw'
        stale.write_bytes(b'old coverage')
        Coverage(self.runner.report)
        self.assertFalse(stale.exists())

    def test_coverage_totals_include_unexecuted_files(self):
        result = Coverage.summarize([
            {'file': 'Core.java', 'lines': metric(9, 10)},
            {'file': 'Startup.java', 'lines': metric(0, 90)}], {'Core.java'})
        self.assertEqual(result['core']['lines']['percent'], 90)
        self.assertEqual(result['overall']['lines']['percent'], 9)

    def test_coverage_gate_fails_missing_variant_or_below_threshold(self):
        coverage = Coverage(self.runner.report)
        self.assertTrue(all(not check[2] for check in coverage.check(['swing', 'fx', 'swift'])))
        coverage.results = {'swing': {'core': {'lines': metric(89, 100), 'branches': metric(85, 100)}, 'overall': {'lines': metric(62, 100)}}}
        self.assertEqual([c[2] for c in coverage.check(['swing'])], [False, True, True])

    def test_timeout_is_failure(self):
        self.assertFalse(self.command("import time;time.sleep(10)", timeout=0.1))
        self.assertIn("Timed out", self.runner.cases[0]["detail"])

    def test_missing_executable_is_failure(self):
        with contextlib.redirect_stdout(io.StringIO()):
            result = self.runner.command("fixture", "missing", [str(Path(self.temp.name) / "absent")])
        self.assertFalse(result)

    def test_json_xml_counts_and_escaping(self):
        with contextlib.redirect_stdout(io.StringIO()):
            self.runner.record("fixture", "manual.unperformed", "skipped", detail="must run")
            self.runner.record("fixture", "bad<&", "failed", detail="expected < 5 & actual > 5")
            self.assertEqual(self.runner.write_reports(), 1)
        doc = json.loads((self.runner.report / "summary.json").read_text())
        self.assertEqual(doc["by_kind"]["test"]["failed"], 1)
        self.assertEqual(doc["by_kind"]["manual"]["skipped"], 1)
        xml = ET.parse(self.runner.report / "junit.xml")
        self.assertEqual(len(xml.findall(".//failure")), 1)
        self.assertEqual(len(xml.findall(".//skipped")), 1)


if __name__ == "__main__":
    unittest.main()
