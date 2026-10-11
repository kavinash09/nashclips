#!/usr/bin/env python3
"""Per-version suites. No third-party test runner, real clipboard reads, or synthetic keys.

Named cases emit TEST<TAB>PASS|FAIL<TAB>name<TAB>seconds. Older regression programs
are retained as grouped cases. Every subprocess has a deadline and an isolated HOME.
"""
import argparse
import fnmatch
import json
import math
import os
from pathlib import Path
import platform
import signal
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
from coverage_tools import Coverage

ROOT = Path(__file__).resolve().parents[1]
VARIANTS = {"swing": "java-swing", "fx": "java-fx", "swift": "nashclip-swift"}
MANUAL = {
    "paste.real-app-multiwindow-spaces": "Verify real focused fields, multiple windows, Spaces, and target exit with disposable text.",
    "permissions.denied-revoked-granted": "Verify Accessibility denial, revocation, granting and relaunch on a test account.",
    "hotkey.native-registration-conflict": "Verify Control+Option+V delivery, conflict reporting and shortcut release after Quit.",
    "tray.menu-and-quit": "Exercise the actual menu-bar menu, settings, pin, theme, Hide and Quit.",
    "dialogs.save-cancel-invalid": "Exercise modal Save/Cancel, invalid edited values and focus restoration.",
    "displays.drag-dock-unplug": "Drag from handle/header, dock both sides, unplug displays, test scaling/fullscreen and restore position where supported.",
    "accessibility.visual-keyboard-voiceover": "Check theme contrast, minimum-size layout, keyboard navigation and VoiceOver.",
    "soak.native-memory": "Profile sustained use with Instruments/JVM live-object measurements; short cycles do not prove leak freedom.",
    "release.clean-install": "Verify packaging, signing/notarization, installation and supported macOS/CPU/runtime combinations.",
}


class Runner:
    def __init__(self, args, work):
        self.args, self.work = args, work
        self.report = args.report_dir.resolve()
        self.report.mkdir(parents=True, exist_ok=True)
        self.cases, self.commands = [], {}
        self.test_programs = 0
        self.coverage = Coverage(self.report) if args.coverage else None

    def record(self, suite, name, status, seconds=0, detail="", log=None, kind="test"):
        if name.startswith("manual."):
            kind = "manual"
        self.cases.append(dict(suite=suite, name=name, status=status, seconds=seconds, detail=detail, log=log, kind=kind))
        print(f"{status.upper():7} {suite}: {name}" + (f" — {detail}" if status == "skipped" else ""), flush=True)

    def command(self, suite, name, command, cwd=None, timeout=180, parse=True, env=None, allow_silent=False, kind=None):
        if parse:
            self.test_programs += 1
        log = self.report / f"{len(self.cases):04d}-{suite}-{name}.log"
        home = self.work / suite / "process-home"
        home.mkdir(parents=True, exist_ok=True)
        environment = os.environ.copy()
        environment.update(HOME=str(home), NASHCLIP_DATA_DIR=str(home / ".nashclip"))
        if self.coverage:
            environment.update(self.coverage.environment())
        if env:
            environment.update(env)
        start = time.monotonic()
        error = ""
        print(f"RUN     {suite}: {name}", flush=True)
        with log.open("w") as output:
            try:
                process = subprocess.Popen(list(map(str, command)), cwd=cwd or ROOT, env=environment,
                                           stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
                try:
                    code = process.wait(timeout=timeout)
                except subprocess.TimeoutExpired:
                    error = f"Timed out after {timeout}s"
                    # Only this test's session, including disposable helper descendants.
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    process.wait()
                    code = -1
                except BaseException:
                    # Ctrl+C must not leave invisible test windows or helpers running.
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    process.wait()
                    raise
            except OSError as exception:
                code, error = -1, str(exception)
            output.write("\n" + error if error else "")
        content = log.read_text(errors="replace")
        records = []
        malformed = False
        if parse:
            for line in content.splitlines():
                fields = line.split("\t")
                if fields[0] != "TEST":
                    continue
                if len(fields) == 4 and fields[1] in ("PASS", "FAIL") and fields[2].strip():
                    try:
                        seconds = float(fields[3])
                        if not math.isfinite(seconds) or seconds < 0:
                            raise ValueError("invalid duration")
                        records.append((fields[2], "passed" if fields[1] == "PASS" else "failed", seconds))
                    except ValueError:
                        malformed = True
                else:
                    malformed = True
        if malformed:
            code, error = -1, "Malformed TEST record; results cannot be trusted."
        for case, status, seconds in records:
            self.record(suite, case, status, seconds, content[-12000:] if status == "failed" else "", str(log))
        if parse and not records and code == 0 and not allow_silent and not any(line.startswith("PASS:") for line in content.splitlines()):
            code = -1
            error = "No test results emitted; a silent exit is not a passing test suite."
        if not records or (code != 0 and not any(r[1] == "failed" for r in records)):
            self.record(suite, name, "passed" if code == 0 else "failed", time.monotonic() - start,
                        (error or content[-12000:]) if code != 0 else "", str(log), kind or ("test" if parse else "build"))
        if code != 0:
            print(content[-5000:], flush=True)
        return code == 0 and not any(r[1] == "failed" for r in records)

    def java(self, variant):
        folder = ROOT / VARIANTS[variant]
        source = folder / ("src" if variant == "swing" else "src/main/java/nashclip")
        work = self.work / variant
        generated = work / "test-sources"
        generated.mkdir(parents=True)
        common = ["TestSupport", "SettingsPolicyTests", "DataValidationTests", "PasteServiceTests", "HotkeyProtocolTests", "AuditChecks", "NativeFocusChecks"]
        tests = []
        for name in common:
            file = generated / (name + ".java")
            file.write_text(("package nashclip;\n" if variant == "fx" else "") + (ROOT / "tests" / (name + ".java")).read_text())
            tests.append(file)
        tests += list((folder / "tests").glob("*.java"))
        tests += [ROOT / "tests" / name for name in (["ClipboardWatcherChecks.java", "SwingDrawerChecks.java"] if variant == "swing" else ["DrawerChecks.java"])]
        classes = work / "classes"
        jars = []
        if variant == "fx":
            tree = ET.parse(folder / "pom.xml")
            version = tree.findtext("{*}properties/{*}javafx.version")
            classifier = "mac-aarch64" if platform.machine() == "arm64" else "mac"
            for module in ("base", "graphics", "controls"):
                jar = folder / f"target/m2/org/openjfx/javafx-{module}/{version}/javafx-{module}-{version}-{classifier}.jar"
                if not jar.exists():
                    self.record(variant, "prerequisites.javafx-cache", "failed", detail="Missing " + str(jar) + "; run the Maven compile command in docs/testing.md first.")
                    return
                jars.append(str(jar))
        libraries = os.pathsep.join(jars)
        compile_command = ["javac", "-g", "--release", "17", "-d", classes]
        if jars:
            compile_command += ["-cp", libraries]
        if not self.command(variant, "compile", compile_command + list(source.glob("*.java")) + tests, parse=False):
            self.record(variant, "remaining-tests", "skipped", detail="Compilation failed; see compile log.")
            return
        cp = str(classes)
        base = ["java", "-Xmx256m", "-Duser.home=" + str(work / "process-home")]
        if self.coverage:
            base.append(self.coverage.java_agent(variant, classes, source))
        if jars:
            cp += os.pathsep + str(folder / "src/main/resources")
            base += ["--module-path", libraries, "--add-modules", "javafx.controls"]
        base += ["-cp", cp]
        prefix = "nashclip." if variant == "fx" else ""
        def run(name, *args):
            if self.args.only and not fnmatch.fnmatchcase(name, self.args.only):
                self.record(variant, name, "skipped", detail="Excluded by --only")
                return False
            return self.command(variant, name, base + [prefix + name] + list(args), cwd=folder)
        self.commands[variant] = base + [prefix + "AuditChecks"]
        run("AuditChecks", "test", work / "audit-data")
        run("SettingsPolicyTests", work / "settings-data")
        run("DataValidationTests", work / "validation-data")
        run("PasteServiceTests")
        run("HotkeyProtocolTests", work / "helper-data")
        if variant == "fx":
            run("PositionTests", work / "position-data")
        if self.args.headless:
            self.record(variant, "desktop-and-native-tests", "skipped", detail="--headless selected")
            return
        if not self.args.only or fnmatch.fnmatchcase("NativeFocusChecks", self.args.only):
            if self.command(variant, "native-build", ["sh", "native/build.sh"], cwd=folder, parse=False):
                self.command(variant, "NativeFocusChecks", base[:1] + ["-Xcheck:jni"] + base[1:] + [prefix + "NativeFocusChecks"], cwd=folder)
        else:
            self.record(variant, "NativeFocusChecks", "skipped", detail="Excluded by --only")
        if variant == "swing":
            run("ClipboardWatcherChecks")
            run("SwingFeatureTests", work / "feature-data")
            run("SwingInteractionTests")
            run("SwingDrawerChecks")
        else:
            run("FxClipboardTests")
            run("FxFeatureTests", work / "feature-data")
            run("FxInteractionTests")
            run("DrawerChecks")

    def swift(self):
        work = self.work / "swift"
        work.mkdir(parents=True, exist_ok=True)
        folder = ROOT / "nashclip-swift"
        sdk = Path(os.environ.get("NASHCLIP_SDK", "/Library/Developer/CommandLineTools/SDKs/MacOSX26.5.sdk"))
        if not sdk.exists():
            sdk = Path(subprocess.check_output(["xcrun", "--sdk", "macosx", "--show-sdk-path"], text=True).strip())
        base = ["swiftc", "-swift-version", "5", "-parse-as-library", "-sdk", sdk,
                "-module-cache-path", work / "modules", "-framework", "AppKit", "-framework", "SwiftUI", "-framework", "Carbon"]
        if self.coverage:
            base += ["-module-name", "NashClip", "-profile-generate", "-profile-coverage-mapping"]
        def build_run(name, files, test, *args, support=False):
            if self.args.only and not fnmatch.fnmatchcase(name, self.args.only):
                self.record("swift", name, "skipped", detail="Excluded by --only")
                return None
            binary = work / name
            sources = [folder / "Sources" / (f + ".swift") for f in files]
            if support:
                sources.append(folder / "Tests/TestSupport.swift")
            if self.command("swift", "compile-" + name, base + sources + [test, "-o", binary], parse=False):
                if self.coverage:
                    self.coverage.swift_objects.append(binary)
                self.command("swift", name, [binary] + list(args))
                return binary
            self.record("swift", name, "skipped", detail="Compilation failed")
            return None
        core = ["Storage", "HistoryPolicy", "InstanceLock"]
        if not self.args.only:
            if self.command("swift", "app-compile", base + list((folder / "Sources").glob("*.swift")) + ["-o", work / "app-compile-check"], parse=False) and self.coverage:
                self.coverage.swift_objects.append(work / "app-compile-check")
        binary = build_run("StorageChecks", core, ROOT / "tests/StorageChecks.swift", "test", work / "audit-data")
        if binary:
            self.commands["swift"] = [str(binary)]
        build_run("StorageFeatureTests", core, folder / "Tests/StorageFeatureTests.swift", work / "feature-storage", support=True)
        build_run("StorageValidationTests", core, folder / "Tests/StorageValidationTests.swift", work / "validation-storage", support=True)
        if self.args.headless:
            self.record("swift", "desktop-tests", "skipped", detail="--headless selected")
            return
        model = core + ["ClipboardModel"]
        build_run("SwiftLifecycleChecks", model, ROOT / "tests/SwiftLifecycleChecks.swift", work / "lifecycle-data")
        build_run("ModelFeatureTests", model + ["PasteCoordinator", "Views", "DrawerController"], folder / "Tests/ModelFeatureTests.swift", work / "model-data", support=True)
        build_run("SwiftDrawerChecks", model + ["Views", "DrawerController"], ROOT / "tests/SwiftDrawerChecks.swift", work / "drawer-data")
        build_run("ViewDrawerFeatureTests", model + ["Views", "DrawerController"], folder / "Tests/ViewDrawerFeatureTests.swift", work / "view-data", support=True)

    def cross_version(self):
        if len(self.commands) != 3:
            self.record("shared", "cross-version", "skipped", detail="Requires all three compiled variants")
            return
        shared = self.work / "shared-data"
        for emitter, command in self.commands.items():
            for mode in ("", "Pins"):
                if not self.command("shared", emitter + "-emit" + mode, command + ["emit" + mode, str(shared)], parse=False, kind="fixture"):
                    continue
                for reader, reader_command in self.commands.items():
                    if reader != emitter:
                        self.command("shared", emitter + "-to-" + reader + mode, reader_command + ["verify" + mode, str(shared)], allow_silent=True)
        # A bounded readiness wait avoids a hanging lock fixture blocking the test runner.
        import selectors
        environment = os.environ.copy()
        if self.coverage:
            environment.update(self.coverage.environment())
        for holder_name, command in self.commands.items():
            holder = subprocess.Popen(command + ["hold", str(shared)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=environment)
            try:
                with selectors.DefaultSelector() as selector:
                    selector.register(holder.stdout, selectors.EVENT_READ)
                    if not selector.select(10) or holder.stdout.readline().strip() != "LOCKED":
                        raise RuntimeError("Holder failed to acquire lock")
                for reader, probe in self.commands.items():
                    result = subprocess.run(probe + ["probe", str(shared)], capture_output=True, text=True, timeout=10, env=environment)
                    self.record("shared", f"lock.{holder_name}.blocks.{reader}", "passed" if result.returncode == 0 and result.stdout.strip() == "BLOCKED" else "failed", detail=result.stderr)
            finally:
                try:
                    holder.communicate("\n", timeout=5)
                except (subprocess.TimeoutExpired, BrokenPipeError):
                    holder.kill(); holder.communicate()
            for reader, probe in self.commands.items():
                result = subprocess.run(probe + ["probe", str(shared)], capture_output=True, text=True, timeout=10, env=environment)
                self.record("shared", f"lock.{holder_name}.releases-to.{reader}", "passed" if result.returncode == 0 and result.stdout.strip() == "ACQUIRED" else "failed", detail=result.stderr)

    def write_reports(self):
        summary = {status: sum(c["status"] == status for c in self.cases) for status in ("passed", "failed", "skipped")}
        by_kind = {kind: {status: sum(c["kind"] == kind and c["status"] == status for c in self.cases) for status in summary} for kind in ("test", "build", "fixture", "manual")}
        (self.report / "summary.json").write_text(json.dumps({"summary": summary, "by_kind": by_kind, "cases": self.cases}, indent=2) + "\n")
        suites = ET.Element("testsuites")
        for name in dict.fromkeys(c["suite"] for c in self.cases):
            cases = [c for c in self.cases if c["suite"] == name]
            suite = ET.SubElement(suites, "testsuite", name=name, tests=str(len(cases)), failures=str(sum(c["status"] == "failed" for c in cases)), skipped=str(sum(c["status"] == "skipped" for c in cases)))
            for case in cases:
                element = ET.SubElement(suite, "testcase", name=case["name"], classname=name, time=f'{case["seconds"]:.3f}')
                if case["status"] == "failed":
                    ET.SubElement(element, "failure", message=case["detail"][:200]).text = case["detail"]
                elif case["status"] == "skipped":
                    ET.SubElement(element, "skipped", message=case["detail"])
                if case["log"]:
                    ET.SubElement(element, "system-out").text = case["log"]
        ET.indent(suites)
        ET.ElementTree(suites).write(self.report / "junit.xml", encoding="utf-8", xml_declaration=True)
        tests = by_kind['test']
        print(f"\nTests: {tests['passed']} passed, {tests['failed']} failed, {tests['skipped']} skipped."
              f"\nBuilds: {by_kind['build']}; fixtures: {by_kind['fixture']}; manual: {by_kind['manual']}."
              f"\nReports: {self.report}", flush=True)
        return 1 if summary["failed"] else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--variant", choices=["all", *VARIANTS], default="all")
    parser.add_argument("--headless", action="store_true", help="Run core suites; record desktop/native tests as skipped.")
    parser.add_argument("--list", action="store_true", help="List test groups and required manual checks without running.")
    parser.add_argument("--only", help="Run matching test program names, e.g. '*InteractionTests'; compilation still runs.")
    parser.add_argument("--coverage", action="store_true", help="Measure all production Java/Swift files; requires setup-coverage.py for Java.")
    parser.add_argument("--check-coverage", action="store_true", help="Measure coverage and enforce tests/coverage-policy.json; requires a complete desktop suite.")
    parser.add_argument("--report-dir", type=Path, default=ROOT / "test-results/latest")
    args = parser.parse_args()
    if args.check_coverage:
        args.coverage = True
        if args.only or args.headless:
            parser.error("--check-coverage requires a complete suite, without --only or --headless")
    if args.coverage and args.only:
        parser.error("--coverage requires the full production build; use --variant to select a variant")
    if args.list:
        print("Core: settings, policy, storage, pin persistence, sizes, migration, locks; Java paste decisions and helper protocol.\nDesktop: clipboard, actual tile actions, themes, layout, drawer cycles, shutdown, Java native context lifetime.\nAll variants: cross-language history/pins and nine lock holder/contender combinations.\nManual:\n" + "\n".join(f"  {name}: {detail}" for name, detail in MANUAL.items()))
        return 0
    selected = list(VARIANTS) if args.variant == "all" else [args.variant]
    with tempfile.TemporaryDirectory(prefix="nashclip-tests-") as temporary:
        runner = Runner(args, Path(temporary))
        try:
            if platform.system() != "Darwin":
                raise RuntimeError("These suites target macOS. Run on a macOS host with the documented build tools.")
            if not args.only:
                runner.command("runner", "self-tests", [sys.executable, ROOT / "tests/test_runner.py"], parse=False, kind="test")
            for variant in selected:
                try:
                    runner.swift() if variant == "swift" else runner.java(variant)
                except Exception as error:
                    runner.record(variant, "suite-infrastructure", "failed", detail=repr(error))
                for name, detail in MANUAL.items():
                    runner.record(variant, "manual." + name, "skipped", detail=detail)
            if args.variant == "all" and not args.only:
                runner.cross_version()
            if args.only and runner.test_programs == 0 and not any(c["status"] == "failed" for c in runner.cases):
                runner.record("runner", "selection", "failed", detail="No test programs matched --only with the selected variant/mode.")
            if runner.coverage:
                results = runner.coverage.generate()
                for variant, values in results.items():
                    print(f"COVERAGE {variant}: overall lines {values['overall']['lines']['percent']:.2f}%; core lines {values['core']['lines']['percent']:.2f}%", flush=True)
                if args.check_coverage:
                    for variant, name, passed, detail in runner.coverage.check(selected):
                        runner.record(variant, "coverage." + name, "passed" if passed else "failed", detail=detail)
        except KeyboardInterrupt:
            runner.record("runner", "interrupted", "failed", detail="Run interrupted; active test process group terminated.")
        except Exception as error:
            runner.record("runner", "infrastructure", "failed", detail=repr(error))
        finally:
            result = runner.write_reports()
    return result


if __name__ == "__main__":
    raise SystemExit(main())
