#!/usr/bin/env python3
"""Isolated storage/process checks. Does not access the real clipboard or user data."""
from pathlib import Path
import os
import subprocess
import tempfile
import sys

ROOT = Path(__file__).resolve().parents[1]
def run(*args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, timeout=90, **kwargs)

with tempfile.TemporaryDirectory(prefix="nashclip-audit-") as temporary:
    work = Path(temporary)
    java_commands = []
    for variant, source in [("swing", ROOT / "java-swing/src"), ("fx", ROOT / "java-fx/src/main/java/nashclip")]:
        classes = work / variant
        classes.mkdir()
        test = classes / "AuditChecks.java"
        test.write_text(("package nashclip;\n" if variant == "fx" else "") + (ROOT / "tests/AuditChecks.java").read_text())
        sources = [source / (name + ".java") for name in ["HistoryPolicy", "HistoryStore", "AppSettings", "WindowSizeStore", "InstanceLock", "CommandRunner", "RejectedClipTracker"]]
        run("javac", "-d", classes, *sources, test)
        command = ["java", "-Xmx128m", "-cp", str(classes), "nashclip.AuditChecks" if variant == "fx" else "AuditChecks"]
        java_commands.append(command)
        run(*command, "test", work / (variant + "-data"))
    sdk = Path(os.environ.get("NASHCLIP_SDK", "/Library/Developer/CommandLineTools/SDKs/MacOSX26.5.sdk"))
    if not sdk.exists():
        sdk = Path(subprocess.check_output(["xcrun", "--sdk", "macosx", "--show-sdk-path"], text=True).strip())
    swift = work / "storage-checks"
    run("swiftc", "-swift-version", "5", "-parse-as-library", "-sdk", sdk,
        "-module-cache-path", work / "modules", ROOT / "nashclip-swift/Sources/Storage.swift",
        ROOT / "nashclip-swift/Sources/HistoryPolicy.swift", ROOT / "nashclip-swift/Sources/InstanceLock.swift",
        ROOT / "tests/StorageChecks.swift", "-o", swift)
    run(swift, "test", work / "swift-data")
    if "--lifecycle" in sys.argv:
        lifecycle = work / "lifecycle-checks"
        run("swiftc", "-swift-version", "5", "-parse-as-library", "-sdk", sdk,
            "-module-cache-path", work / "modules", "-framework", "AppKit", "-framework", "Combine",
            ROOT / "nashclip-swift/Sources/Storage.swift", ROOT / "nashclip-swift/Sources/HistoryPolicy.swift",
            ROOT / "nashclip-swift/Sources/ClipboardModel.swift", ROOT / "tests/SwiftLifecycleChecks.swift",
            "-o", lifecycle)
        run(lifecycle, work / "lifecycle-data")
    shared = work / "shared"
    run(*java_commands[0], "emit", shared)
    run(swift, "verify", shared)
    run(swift, "emit", shared)
    for command in java_commands:
        run(*command, "verify", shared)
    run(*java_commands[0], "emitPins", shared)
    run(swift, "verifyPins", shared)
    run(swift, "emitPins", shared)
    for command in java_commands:
        run(*command, "verifyPins", shared)
    print("PASS: Java ↔ Swift pinned clips, pin order, and Unicode content")
    # Verify record locks across distinct processes and languages, in both directions.
    commands = java_commands + [[str(swift)]]
    for holder_command in commands:
        holder = subprocess.Popen(holder_command + ["hold", str(shared)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        try:
            assert holder.stdout.readline().strip() == "LOCKED"
            for command in commands:
                result = run(*command, "probe", shared, capture_output=True, text=True)
                assert result.stdout.strip() == "BLOCKED", (holder_command, command, result.stdout)
        finally:
            holder.communicate("\n", timeout=5)
        for command in commands:
            result = run(*command, "probe", shared, capture_output=True, text=True)
            assert result.stdout.strip() == "ACQUIRED"
    print("PASS: Java ↔ Swift Unicode history exchange and all 9 cross-process lock combinations")
