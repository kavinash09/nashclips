#!/usr/bin/env python3
"""Seed selected regressions in isolated source copies and require assertion failures.

This is a targeted sensitivity check, not an exhaustive mutation-coverage score.
Compilation errors, crashes without named failures, and timeouts are infrastructure failures.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = [
    ("clip-boundary", "HistoryPolicy", "text.length() <= MAX_TEXT_UNITS", "text.length() < MAX_TEXT_UNITS", "SettingsPolicyTests"),
    ("sixth-pin", "HistoryPolicy", "MAX_PINNED = 5", "MAX_PINNED = 6", "DataValidationTests"),
    ("ordinary-limit", "HistoryPolicy", "regular >= Math.min(limit, MAX_ENTRIES)", "false", "SettingsPolicyTests"),
    ("wrong-window-paste", "PasteService", "if (!platform.isOriginalWindow(target.windowToken()))", "if (false)", "PasteServiceTests"),
    ("clipboard-race", "PasteService", 'if (!platform.clipboardMatches(expectedText)) return new Result(false, "Paste cancelled because the clipboard changed.");', "/* removed clipboard guard */", "PasteServiceTests"),
    ("target-release", "PasteService", "platform.release(target.windowToken());", "/* leaked frozen target */", "PasteServiceTests"),
]
SWIFT = [
    ("clip-boundary", "HistoryPolicy", "text.utf16.count <= maxTextUnits", "text.utf16.count < maxTextUnits", "StorageFeatureTests"),
    ("sixth-pin", "HistoryPolicy", "maxPinned = 5", "maxPinned = 6", "StorageValidationTests"),
    ("unicode-identity", "HistoryPolicy", "a.utf16.elementsEqual(b.utf16)", "a == b", "StorageFeatureTests"),
    ("ordinary-limit", "HistoryPolicy", "regular >= min(limit, maxEntries)", "false", "StorageFeatureTests"),
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report-dir", type=Path, default=ROOT / "test-results/mutations")
    args = parser.parse_args()
    report = args.report_dir.resolve(); report.mkdir(parents=True, exist_ok=True)
    results = []
    with tempfile.TemporaryDirectory(prefix="nashclip-mutations-") as temporary:
        work = Path(temporary)
        environment = os.environ.copy()
        environment.update(HOME=str(work), NASHCLIP_DATA_DIR=str(work / "data"))

        def command(label, cmd):
            result = subprocess.run(list(map(str, cmd)), capture_output=True, text=True, env=environment, timeout=90)
            output = result.stdout + result.stderr
            (report / (label + ".log")).write_text(output)
            return result.returncode, output

        for variant, mutations in (("swing", JAVA), ("fx", JAVA), ("swift", SWIFT)):
            source = ROOT / ({"swing": "java-swing/src", "fx": "java-fx/src/main/java/nashclip", "swift": "nashclip-swift/Sources"}[variant])
            ext = ".swift" if variant == "swift" else ".java"
            needed = ["Storage", "HistoryPolicy", "InstanceLock"] if variant == "swift" else ["HistoryPolicy", "HistoryStore", "AppSettings", "DataDirectory", "WindowSizeStore", "InstanceLock", "PasteService", "NativeFocus"]
            original = {name: (source / (name + ext)).read_text() for name in needed}
            suite_names = sorted({m[4] for m in mutations})
            # Run the same test programs against unchanged sources first.
            cases = [("control-" + suite, None, None, None, suite) for suite in suite_names] + mutations
            for name, file, before, after, suite in cases:
                label = variant + "-" + name
                folder = work / label; folder.mkdir()
                try:
                    files = []
                    for stem, content in original.items():
                        if stem == file:
                            if before not in content:
                                raise RuntimeError("Mutation no longer matches source; update the check")
                            content = content.replace(before, after)
                        path = folder / (stem + ext); path.write_text(content); files.append(path)
                    if variant == "swift":
                        sdk = os.environ.get("NASHCLIP_SDK", "/Library/Developer/CommandLineTools/SDKs/MacOSX26.5.sdk")
                        if not Path(sdk).exists():
                            sdk = subprocess.check_output(["xcrun", "--sdk", "macosx", "--show-sdk-path"], text=True).strip()
                        executable = folder / "test"
                        compile_cmd = ["swiftc", "-swift-version", "5", "-parse-as-library", "-sdk", sdk, "-module-cache-path", work / "modules",
                                       *files, ROOT / "nashclip-swift/Tests/TestSupport.swift", ROOT / "nashclip-swift/Tests" / (suite + ext), "-o", executable]
                        run_cmd = [executable, folder / "data"]
                    else:
                        for test in ["TestSupport", suite]:
                            path = folder / (test + ext)
                            path.write_text(("package nashclip;\n" if variant == "fx" else "") + (ROOT / "tests" / (test + ext)).read_text())
                            files.append(path)
                        classes = folder / "classes"
                        compile_cmd = ["javac", "--release", "17", "-d", classes, *files]
                        run_cmd = ["java", "-Duser.home=" + str(folder), "-cp", classes, ("nashclip." if variant == "fx" else "") + suite, folder / "data"]
                    code, output = command(label + "-compile", compile_cmd)
                    if code:
                        raise RuntimeError("Compilation failed; see log")
                    code, output = command(label, run_cmd)
                    passes = [line for line in output.splitlines() if line.startswith("TEST\tPASS\t")]
                    failures = [line for line in output.splitlines() if line.startswith("TEST\tFAIL\t")]
                    if file is None:
                        status = "control-passed" if code == 0 and passes and not failures else "error"
                    else:
                        status = "detected" if code != 0 and failures else ("survived" if code == 0 else "error")
                    detail = "; ".join(line.split("\t")[2] for line in failures)
                except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
                    status, detail = "error", str(error)
                results.append(dict(variant=variant, mutation=name, status=status, detail=detail))
                print(f"{status.upper():14} {label}: {detail}", flush=True)
    (report / "summary.json").write_text(json.dumps(results, indent=2) + "\n")
    return 1 if any(r["status"] not in ("control-passed", "detected") for r in results) else 0


if __name__ == "__main__":
    raise SystemExit(main())
