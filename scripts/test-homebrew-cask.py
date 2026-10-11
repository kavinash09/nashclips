#!/usr/bin/env python3
"""Exercise the real Homebrew checker with a valid package and deliberately bad casks.

No download/install/uninstall is performed. Requires macOS, Homebrew, and a generated package.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("package", type=Path)
    args = parser.parse_args()
    environment = dict(os.environ, HOMEBREW_NO_AUTO_UPDATE="1",
                       HOMEBREW_NO_ANALYTICS="1", HOMEBREW_DEVELOPER="1")
    with tempfile.TemporaryDirectory(prefix="nashclip-cask-regressions-") as temporary:
        package = Path(temporary) / "package"
        shutil.copytree(args.package, package)
        metadata = json.loads((package / "package.json").read_text())
        definition = package / "Casks" / ("nashclip.rb.example" if metadata["preview"] else "nashclip.rb")
        original = definition.read_text()

        def check(label, source, expected_error=None):
            definition.write_text(source)
            result = subprocess.run(
                ["brew", "ruby", str(ROOT / "scripts/check-homebrew-cask.rb"), str(package)],
                env=environment, text=True, capture_output=True, timeout=120)
            if expected_error is None:
                passed = result.returncode == 0 and "PASS:" in result.stdout
            else:
                passed = result.returncode != 0 and expected_error in result.stdout + result.stderr
            if not passed:
                raise RuntimeError(f"{label}: unexpected checker result\n{result.stdout}\n{result.stderr}")
            print(f"PASS: {label}", flush=True)

        check("valid package", original)
        mutations = [
            ("wrong app path", original.replace('app "nashclip.app"', 'app "missing.app"'), "Unexpected install/uninstall"),
            ("wrong download URL", original.replace('  url "https://', '  url "https://wrong.invalid/'), "Download URL mismatch"),
            ("wrong homepage", original.replace('  homepage "https://', '  homepage "https://wrong.invalid/'), "Homepage mismatch"),
            ("wrong minimum OS", original.replace(":ventura", ":sonoma"), "Minimum macOS mismatch"),
            ("destructive uninstall", original.replace('uninstall quit: "dev.nashclip.native"',
                'uninstall quit: "dev.nashclip.native", delete: "~/SYNTHETIC-NOT-EXECUTED"'), "Unexpected install/uninstall"),
            ("extra zap", original.replace('\nend\n', '\n  zap trash: "~/SYNTHETIC-NOT-EXECUTED"\nend\n'), "Unexpected install/uninstall"),
        ]
        if metadata["architecture"] == "universal":
            wrong_arch = original.replace("  depends_on macos:", "  depends_on arch: :arm64\n  depends_on macos:")
        else:
            current = metadata["architecture"]
            other = "x86_64" if current == "arm64" else "arm64"
            wrong_arch = original.replace(f"depends_on arch: :{current}", f"depends_on arch: :{other}")
        mutations.append(("wrong architecture", wrong_arch, "Architecture mismatch"))
        for label, source, expected in mutations:
            if source == original:
                raise RuntimeError(f"{label}: fixture was not changed")
            check(label, source, expected)
        check("restored control", original)


if __name__ == "__main__":
    main()
