"""Packaging guards; macOS signing commands are mocked, not certified by these tests."""

import hashlib
import importlib.util
from pathlib import Path
import plistlib
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "package_homebrew", Path(__file__).resolve().parents[1] / "scripts/package-homebrew.py")
PACK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PACK)


class PackageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.app = root / "nashclip.app"
        (self.app / "Contents/MacOS").mkdir(parents=True)
        executable = self.app / "Contents/MacOS/nashclip"
        executable.write_bytes(b"fixture")
        executable.chmod(0o755)
        resources = self.app / "Contents/Resources"
        resources.mkdir()
        for name in ("LICENSE", "NOTICE"):
            shutil.copy2(PACK.ROOT / name, resources / name)
        self.info = self.app / "Contents/Info.plist"
        self.info.write_bytes(plistlib.dumps({
            "CFBundleShortVersionString": "1.0",
            "CFBundleVersion": "1",
            "CFBundlePackageType": "APPL",
            "CFBundleIdentifier": "dev.nashclip.native",
            "CFBundleExecutable": "nashclip",
            "LSMinimumSystemVersion": "13.0",
        }))
        self.output = root / "release"
        self.arch = "arm64"
        self.signature = "Authority=Developer ID Application: Example\nCodeDirectory v=20400 flags=0x10000(runtime)\nTeamIdentifier=TEAM123456"
        self.slice_signatures = {}
        self.load_commands = "Load command 1\ncmd LC_BUILD_VERSION\nplatform 1\nminos 13.0\n"
        self.failed_tool = None
        self.calls = []
        self.mock = patch.object(PACK, "run", side_effect=self.run_tool)
        self.mock.start()
        self.addCleanup(self.mock.stop)

    def run_tool(self, *args):
        self.calls.append(args)
        if args[0] == self.failed_tool:
            raise subprocess.CalledProcessError(1, args, output="rejected")
        if args[0] == "ditto":
            if "-c" in args:
                self.assertTrue((Path(args[-2]) / "LICENSE").is_file())
                self.assertTrue((Path(args[-2]) / "NOTICE").is_file())
                Path(args[-1]).write_bytes(b"archive fixture")
            else:
                shutil.copytree(args[1], args[2])
        if args[0] == "lipo":
            return self.arch
        if args[0] == "otool":
            return self.load_commands
        if args[:2] == ("codesign", "--display"):
            return self.slice_signatures.get(args[args.index("--architecture") + 1], self.signature)
        return ""

    def release(self):
        architecture = "universal" if " " in self.arch else self.arch
        return PACK.package(self.app, self.output,
                            f"https://github.com/test-fixture/nashclip/releases/download/v1.0/nashclip-1.0-macos-{architecture}.zip",
                            "https://github.com/test-fixture/nashclip", team_id="TEAM123456")

    def test_release_has_checksum_arch_and_os_and_preserves_data(self):
        self.release()
        cask = (self.output / "Casks/nashclip.rb").read_text()
        self.assertIn(hashlib.sha256(b"archive fixture").hexdigest(), cask)
        self.assertIn("depends_on arch: :arm64", cask)
        self.assertIn('depends_on macos: :ventura', cask)
        self.assertIn('app "nashclip.app"', cask)
        self.assertTrue((self.output / "nashclip-1.0-macos-arm64.zip").is_file())
        self.assertNotIn("zap", cask)
        self.assertIn(("xcrun", "stapler", "validate"), [c[:3] for c in self.calls])
        self.assertTrue(any(c[0] == "spctl" for c in self.calls))

    def test_preview_is_marked_and_has_no_installable_cask(self):
        PACK.package(self.app, self.output, preview=True)
        self.assertTrue((self.output / "PREVIEW-ONLY.txt").is_file())
        self.assertFalse((self.output / "Casks/nashclip.rb").exists())
        self.assertEqual(len(list(self.output.glob("*-preview.zip"))), 1)
        self.assertFalse(any(c[0] in ("spctl", "xcrun") for c in self.calls))

    def test_unsigned_release_leaves_no_output(self):
        self.signature = "Signature=adhoc"
        with self.assertRaisesRegex(ValueError, "Developer ID"):
            self.release()
        self.assertFalse(self.output.exists())

    def test_failed_distribution_checks_leave_no_output(self):
        for tool in ("codesign", "xcrun", "spctl"):
            with self.subTest(tool=tool):
                self.failed_tool = tool
                with self.assertRaises(subprocess.CalledProcessError):
                    self.release()
                self.assertFalse(self.output.exists())

    def test_missing_urls_and_existing_output_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "requires"):
            PACK.package(self.app, self.output)
        self.output.mkdir()
        marker = self.output / "keep"
        marker.write_text("keep me")
        with self.assertRaisesRegex(ValueError, "already exists"):
            self.release()
        self.assertEqual(marker.read_text(), "keep me")

    def test_changed_minimum_os_is_not_silently_mislabelled(self):
        info = plistlib.loads(self.info.read_bytes())
        info["LSMinimumSystemVersion"] = "14.0"
        self.info.write_bytes(plistlib.dumps(info))
        with self.assertRaisesRegex(ValueError, "minimum OS"):
            self.release()

    def test_architecture_detection(self):
        for architecture, expected in (("x86_64", ":x86_64"), ("arm64 x86_64", None)):
            with self.subTest(architecture=architecture):
                self.arch = architecture
                self.output = self.output.parent / architecture.replace(" ", "-")
                self.release()
                cask = (self.output / "Casks/nashclip.rb").read_text()
                if expected:
                    self.assertIn(f"depends_on arch: {expected}", cask)
                else:
                    self.assertNotIn("depends_on arch:", cask)
        self.arch = "i386"
        self.output = self.output.parent / "unsupported"
        with self.assertRaisesRegex(ValueError, "Unsupported architectures"):
            self.release()

    def test_url_validation_and_ruby_interpolation(self):
        for url in ("http://example.org", "https://user:secret@example.org", "https://", "https://a/#x", "https://a/\nx",
                    "https://example.invalid/x", "https://github.com/OWNER/PROJECT/x",
                    "https://github.com/test-fixture/x?token=secret", "https://github.com:bad/x"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                PACK.https_url(url)
        self.assertEqual(PACK.ruby_string('#{bad} #@bad #$bad'), '"\\#{bad} \\#@bad \\#$bad"')

    def test_late_write_failure_leaves_no_output(self):
        original = Path.write_text
        for name in ("SHA256SUMS", "nashclip.rb", "package.json"):
            def fail(path, *args, **kwargs):
                if path.name == name:
                    raise OSError("simulated disk failure")
                return original(path, *args, **kwargs)
            with self.subTest(name=name), patch.object(Path, "write_text", fail):
                with self.assertRaises(OSError):
                    self.release()
                self.assertFalse(self.output.exists())

    def test_missing_embedded_license_rejected(self):
        (self.app / "Contents/Resources/LICENSE").unlink()
        with self.assertRaisesRegex(ValueError, "embedded LICENSE"):
            self.release()

    def test_wrong_team_and_missing_hardened_runtime_rejected(self):
        self.signature = self.signature.replace("TEAM123456", "OTHER12345")
        with self.assertRaisesRegex(ValueError, "TeamIdentifier"):
            self.release()
        self.signature = self.signature.replace("(runtime)", "")
        with self.assertRaisesRegex(ValueError, "hardened runtime"):
            self.release()

    def test_binary_metadata_rejects_newer_os_and_local_libraries(self):
        for commands, message in (
            ("cmd LC_BUILD_VERSION\nplatform 1\nminos 14.0", "newer macOS"),
            ("cmd LC_BUILD_VERSION\nplatform 2\nminos 13.0", "target macOS"),
            ("", "minimum macOS"),
            (self.load_commands + "Load command 2\ncmd LC_LOAD_DYLIB\nname /opt/homebrew/lib/libtest.dylib (offset 24)", "Non-system"),
            (self.load_commands + "Load command 2\ncmd LC_RPATH\npath /Users/builder/lib (offset 12)", "search path"),
        ):
            with self.subTest(commands=commands):
                self.load_commands = commands
                with self.assertRaisesRegex(ValueError, message):
                    self.release()

    def test_nonexecutable_and_symlink_bundle_rejected(self):
        executable = self.app / "Contents/MacOS/nashclip"
        executable.chmod(0o644)
        with self.assertRaisesRegex(ValueError, "not executable"):
            self.release()
        executable.chmod(0o755)
        (self.app / "unexpected").symlink_to(self.info)
        with self.assertRaisesRegex(ValueError, "symlink"):
            self.release()

    def test_output_cannot_modify_source_or_follow_dangling_symlink(self):
        self.output = self.app / "release"
        with self.assertRaisesRegex(ValueError, "outside"):
            self.release()
        self.output = self.app.parent / "release-link"
        self.output.symlink_to(self.app.parent / "absent")
        with self.assertRaisesRegex(ValueError, "already exists"):
            self.release()

    def test_mismatched_archive_url_rejected(self):
        with self.assertRaisesRegex(ValueError, "archive name"):
            PACK.package(self.app, self.output, "https://github.com/test-fixture/nashclip/wrong.zip",
                         "https://github.com/test-fixture/nashclip", team_id="TEAM123456")

    def test_universal_checks_signing_identity_and_runtime_of_each_slice(self):
        self.arch = "arm64 x86_64"
        for invalid in (self.signature.replace("TEAM123456", "OTHER12345"),
                        self.signature.replace("0x10000(runtime)", "0x0(none)")):
            with self.subTest(signature=invalid):
                self.slice_signatures["x86_64"] = invalid
                with self.assertRaisesRegex(ValueError, "x86_64"):
                    self.release()
                self.assertFalse(self.output.exists())
        self.slice_signatures.clear()
        self.release()
        displayed = {call[call.index("--architecture") + 1]
                     for call in self.calls if call[:2] == ("codesign", "--display")}
        self.assertEqual(displayed, {"arm64", "x86_64"})

    def test_invalid_plist_shape_and_nonstring_build_version(self):
        original = plistlib.loads(self.info.read_bytes())
        self.info.write_bytes(plistlib.dumps([]))
        with self.assertRaisesRegex(ValueError, "dictionary"):
            self.release()
        original["CFBundleVersion"] = 1
        self.info.write_bytes(plistlib.dumps(original))
        with self.assertRaisesRegex(ValueError, "version string"):
            self.release()


if __name__ == "__main__":
    unittest.main()
