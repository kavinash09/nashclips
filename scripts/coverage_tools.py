"""Coverage of production files, including unexecuted startup/UI/OS adapters."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
VERSION = "0.8.15"
ARTIFACTS = {
    "org.jacoco.agent-0.8.15-runtime.jar": "fb5b0036a0899ea97edfa0fc2c7985b55f3f7c5695028163e5e60d4f3cf6075d",
    "org.jacoco.cli-0.8.15-nodeps.jar": "d2b74b20b415163c1f53261e7c5ef4445b788327094208ff821e0c2baf9bc8f1",
}
# Core = capture, retained-data policy, file validation/persistence, preferences,
# migration and locking. UI, app orchestration and OS adapters remain in overall.
CORE_JAVA = {"AppSettings.java", "ClipboardWatcher.java", "DataDirectory.java", "HistoryPolicy.java", "HistoryStore.java", "InstanceLock.java", "RejectedClipTracker.java", "WindowSizeStore.java", "DrawerPositionStore.java"}
CORE_SWIFT = {"Storage.swift", "HistoryPolicy.swift", "ClipboardModel.swift", "InstanceLock.swift"}


def tool(name):
    path = ROOT / ".test-tools" / ("jacoco-" + VERSION) / name
    if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != ARTIFACTS[name]:
        raise RuntimeError("Missing or altered coverage tool. Run python3 scripts/setup-coverage.py")
    return path


def metric(covered, total):
    return {"covered": covered, "total": total, "percent": 100 * covered / total if total else None}


class Coverage:
    def __init__(self, report):
        self.root = report / "coverage"
        # A repeated run must not inherit executed lines or classes from an old run.
        if self.root.is_symlink():
            raise RuntimeError("Coverage output directory must not be a symbolic link")
        if self.root.exists():
            shutil.rmtree(self.root)
        self.root.mkdir(parents=True, exist_ok=True)
        self.java_inputs = {}
        self.swift_objects = []
        self.results = {}
        self.profiles = self.root / "swift/profiles"
        self.profiles.mkdir(parents=True, exist_ok=True)

    def environment(self):
        return {"LLVM_PROFILE_FILE": str(self.profiles / "%m-%p.profraw")}

    def java_agent(self, variant, classes, source):
        agent = tool("org.jacoco.agent-0.8.15-runtime.jar")
        folder = self.root / variant
        folder.mkdir(parents=True, exist_ok=True)
        data = folder / "execution.exec"
        data.unlink(missing_ok=True)
        self.java_inputs[variant] = (classes, source, folder, data)
        names = sorted(p.stem for p in source.glob("*.java"))
        prefix = "nashclip." if variant == "fx" else ""
        includes = ":".join(prefix + name + "*" for name in names)
        return f"-javaagent:{agent}=destfile={data},append=true,includes={includes}"

    def generate(self):
        for variant, (classes, source, folder, data) in self.java_inputs.items():
            production = folder / "classes"
            production.mkdir(exist_ok=True)
            names = {p.stem for p in source.glob("*.java")}
            for file in classes.rglob("*.class"):
                if file.stem.split("$")[0] in names:
                    target = production / file.relative_to(classes)
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(file, target)
            cli = tool("org.jacoco.cli-0.8.15-nodeps.jar")
            subprocess.run(["java", "-jar", str(cli), "report", str(data), "--classfiles", str(production),
                            "--sourcefiles", str(source if variant == "swing" else source.parent),
                            "--html", str(folder / "html"), "--xml", str(folder / "jacoco.xml"), "--quiet"], check=True, timeout=60)
            files = []
            for item in ET.parse(folder / "jacoco.xml").findall(".//sourcefile"):
                counters = {c.get("type"): metric(int(c.get("covered")), int(c.get("covered")) + int(c.get("missed"))) for c in item.findall("counter")}
                files.append({"file": item.get("name"), "lines": counters["LINE"], "branches": counters.get("BRANCH", metric(0, 0)),
                              "uncovered_lines": [int(l.get("nr")) for l in item.findall("line") if int(l.get("ci")) == 0]})
            if {f["file"] for f in files} != {p.name for p in source.glob("*.java")}:
                raise RuntimeError("Java coverage omitted production files")
            self.results[variant] = self.summarize(files, CORE_JAVA)
        if self.swift_objects:
            raw = sorted(self.profiles.glob("*.profraw"))
            if not raw:
                raise RuntimeError("Swift produced no coverage profiles")
            profile = self.root / "swift/merged.profdata"
            subprocess.run(["xcrun", "llvm-profdata", "merge", "-sparse", *map(str, raw), "-o", str(profile)], check=True, timeout=60)
            objects = [str(self.swift_objects[0])]
            for obj in self.swift_objects[1:]:
                objects += ["-object", str(obj)]
            sources = sorted((ROOT / "nashclip-swift/Sources").glob("*.swift"))
            args = [*objects, "-instr-profile=" + str(profile), *map(str, sources)]
            exported = subprocess.check_output(["xcrun", "llvm-cov", "export", *args], timeout=60)
            (self.root / "swift/llvm.json").write_bytes(exported)
            files = []
            allowed = {str(p) for p in sources}
            for datum in json.loads(exported)["data"]:
                for item in datum["files"]:
                    if item["filename"] not in allowed:
                        continue
                    lines = item["summary"]["lines"]
                    regions = item["summary"]["regions"]
                    files.append({"file": Path(item["filename"]).name, "lines": metric(lines["covered"], lines["count"]),
                                  "regions": metric(regions["covered"], regions["count"])})
            if {f["file"] for f in files} != {p.name for p in sources}:
                raise RuntimeError("Swift coverage omitted production files; cannot report whole-app coverage")
            if len(files) != len(sources):
                raise RuntimeError("Swift coverage contains duplicate production-file summaries")
            subprocess.run(["xcrun", "llvm-cov", "show", *args, "-format=html", "-output-dir=" + str(self.root / "swift/html")], check=True, timeout=60, stdout=subprocess.DEVNULL)
            self.results["swift"] = self.summarize(files, CORE_SWIFT)
        (self.root / "summary.json").write_text(json.dumps(self.results, indent=2) + "\n")
        return self.results

    def check(self, selected):
        policy = json.loads((ROOT / "tests/coverage-policy.json").read_text())
        checks = []
        for variant in selected:
            for name, minimum in policy["minimums"][variant].items():
                scope, counter = name.split(".")
                value = self.results.get(variant, {}).get(scope, {}).get(counter, {}).get("percent")
                checks.append((variant, name, value is not None and value >= minimum,
                               f"Measured {value}; required >= {minimum}%"))
        return checks

    @staticmethod
    def summarize(files, core):
        def total(selected, key):
            return metric(sum(f.get(key, {}).get("covered", 0) for f in selected), sum(f.get(key, {}).get("total", 0) for f in selected))
        return {"overall": {k: total(files, k) for k in ("lines", "branches", "regions")},
                "core": {k: total([f for f in files if f["file"] in core], k) for k in ("lines", "branches", "regions")},
                "core_files": sorted(core & {f["file"] for f in files}), "files": sorted(files, key=lambda f: f["file"])}
