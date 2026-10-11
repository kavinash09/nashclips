"""Build staging regressions with fake compiler/signing commands, no app launch."""

import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[1]


class SwiftBuildTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.swift = self.root / "nashclip-swift"
        (self.swift / "Sources").mkdir(parents=True)
        (self.swift / "Sources/Main.swift").write_text("// fixture")
        for name in ("build.sh", "Info.plist"):
            shutil.copy2(ROOT / "nashclip-swift" / name, self.swift / name)
        for name in ("LICENSE", "NOTICE"):
            shutil.copy2(ROOT / name, self.root / name)
        self.bundle = self.swift / "build/nashclip.app"
        self.bundle.mkdir(parents=True)
        (self.bundle / "old-resource").write_text("synthetic old content")
        commands = self.root / "commands"
        self.commands = commands
        commands.mkdir()
        for name, content in {
            "swiftc": '#!/bin/sh\n[ -z "$BUILD_TEST_FAIL" ] || exit 1\nif [ -n "$BUILD_TEST_READY" ]; then touch "$BUILD_TEST_READY"; while [ ! -e "$BUILD_TEST_RELEASE" ]; do sleep 0.05; done; fi\nfor last do :; done\nprintf fresh > "$last"\nchmod +x "$last"\n',
            "codesign": '#!/bin/sh\nexit 0\n',
        }.items():
            command = commands / name
            command.write_text(content)
            command.chmod(0o755)
        self.env = dict(os.environ, PATH=str(commands) + os.pathsep + os.environ["PATH"],
                        NASHCLIP_SDK=str(self.root), BUILD_TEST_FAIL="", BUILD_TEST_READY="")

    def build(self):
        return subprocess.run(["sh", str(self.swift / "build.sh")], env=self.env,
                              capture_output=True, text=True, timeout=20)

    def test_clean_assembly_excludes_stale_files(self):
        result = self.build()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse((self.bundle / "old-resource").exists())
        self.assertEqual((self.bundle / "Contents/Resources/LICENSE").read_bytes(), (ROOT / "LICENSE").read_bytes())
        self.assertFalse(list((self.swift / "build").glob(".nashclip-build.*")))

    def test_failed_build_preserves_previous_bundle(self):
        self.env["BUILD_TEST_FAIL"] = "1"
        self.assertNotEqual(self.build().returncode, 0)
        self.assertEqual((self.bundle / "old-resource").read_text(), "synthetic old content")
        self.assertFalse(list((self.swift / "build").glob(".nashclip-build.*")))

    def test_symlink_destination_not_overwritten(self):
        target = self.root / "protected.app"
        self.bundle.rename(target)
        self.bundle.symlink_to(target, target_is_directory=True)
        result = self.build()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("symlink", result.stderr)
        self.assertEqual((target / "old-resource").read_text(), "synthetic old content")

    def test_overlapping_build_is_rejected_and_lock_is_released(self):
        ready, release = self.root / "ready", self.root / "release"
        environment = dict(self.env, BUILD_TEST_READY=str(ready), BUILD_TEST_RELEASE=str(release))
        first = subprocess.Popen(["sh", str(self.swift / "build.sh")], env=environment,
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                                 start_new_session=True)
        try:
            deadline = time.monotonic() + 5
            while not ready.exists() and time.monotonic() < deadline:
                time.sleep(0.02)
            self.assertTrue(ready.exists(), "first build did not reach the compiler")
            second = self.build()
            self.assertNotEqual(second.returncode, 0)
            self.assertIn("Another build owns", second.stderr)
            release.touch()
            _, error = first.communicate(timeout=10)
            self.assertEqual(first.returncode, 0, error)
            self.assertFalse((self.swift / "build/.nashclip-build.lock").exists())
            self.assertFalse((self.bundle / "nashclip.app").exists())
        finally:
            if first.poll() is None:
                os.killpg(first.pid, signal.SIGKILL)
                first.communicate()

    def failing_install_command(self, restore_fails=False):
        command = self.commands / "mv"
        restore = '*/previous.app) exit 1;;' if restore_fails else ''
        command.write_text('#!/bin/sh\ncase "$1" in\n*/.nashclip-build.*/nashclip.app) exit 1;;\n'
                           + restore + '\nesac\nexec /bin/mv "$@"\n')
        command.chmod(0o755)

    def test_failed_install_restores_previous_bundle(self):
        self.failing_install_command()
        self.assertNotEqual(self.build().returncode, 0)
        self.assertEqual((self.bundle / "old-resource").read_text(), "synthetic old content")
        self.assertFalse((self.swift / "build/.nashclip-build.lock").exists())
        self.assertFalse(list((self.swift / "build").glob(".nashclip-build.*")))

    def test_failed_restore_preserves_recoverable_backup(self):
        self.failing_install_command(restore_fails=True)
        result = self.build()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Previous build preserved", result.stderr)
        backups = list((self.swift / "build").glob(".nashclip-build.*/previous.app/old-resource"))
        self.assertEqual(len(backups), 1)
        self.assertEqual(backups[0].read_text(), "synthetic old content")
        self.assertFalse((self.swift / "build/.nashclip-build.lock").exists())


if __name__ == "__main__":
    unittest.main()
