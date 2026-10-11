#!/usr/bin/env python3
"""Desktop-session regression checks. Uses private pasteboards; never sends keystrokes."""
from pathlib import Path
import subprocess
import tempfile
import os
import platform

ROOT = Path(__file__).resolve().parents[1]
def run(*args, **kwargs):
    subprocess.run([str(arg) for arg in args], check=True, timeout=150, **kwargs)

with tempfile.TemporaryDirectory(prefix="nashclip-gui-audit-") as temporary:
    work = Path(temporary)
    for variant, source in [("java-swing", ROOT / "java-swing/src"), ("java-fx", ROOT / "java-fx/src/main/java/nashclip")]:
        run("sh", ROOT / variant / "native/build.sh")
        classes = work / variant
        classes.mkdir()
        test = classes / "NativeFocusChecks.java"
        test.write_text(("package nashclip;\n" if variant == "java-fx" else "") + (ROOT / "tests/NativeFocusChecks.java").read_text())
        run("javac", "-d", classes, source / "NativeFocus.java", test)
        run("java", "-Xcheck:jni", "-cp", classes,
            "nashclip.NativeFocusChecks" if variant == "java-fx" else "NativeFocusChecks", cwd=ROOT / variant)
    swing = work / "swing-tests"
    run("javac", "-d", swing, *[ROOT / "java-swing/src" / (name + ".java") for name in
        ["ClipboardWatcher", "HistoryPolicy", "RejectedClipTracker"]], ROOT / "tests/ClipboardWatcherChecks.java")
    run("java", "-cp", swing, "ClipboardWatcherChecks")
    run("javac", "-d", swing, *[ROOT / "java-swing/src" / (name + ".java") for name in
        ["EdgeDrawer", "WindowSizeStore", "HistoryPolicy", "ClipboardTheme", "SizeNotice"]], ROOT / "tests/SwingDrawerChecks.java")
    run("java", "-cp", swing, "SwingDrawerChecks")
    classifier = "mac-aarch64" if platform.machine() == "arm64" else "mac"
    jars = sorted((ROOT / "java-fx/target/m2/org/openjfx").glob(f"*/*/*-{classifier}.jar"))
    if not jars:
        raise RuntimeError("Build the JavaFX version first so its platform jars are cached")
    libraries = os.pathsep.join(map(str, jars))
    fx = work / "fx-tests"
    sources = [ROOT / "java-fx/src/main/java/nashclip" / (name + ".java") for name in
               ["EdgeDrawer", "WindowSizeStore", "DrawerPositionStore", "HistoryPolicy", "SizeNotice"]]
    run("javac", "-cp", libraries, "-d", fx, *sources, ROOT / "tests/DrawerChecks.java")
    run("java", "--module-path", libraries, "--add-modules", "javafx.controls", "-cp",
        os.pathsep.join([str(fx), str(ROOT / "java-fx/src/main/resources")]), "nashclip.DrawerChecks")
    sdk = Path(os.environ.get("NASHCLIP_SDK", "/Library/Developer/CommandLineTools/SDKs/MacOSX26.5.sdk"))
    if not sdk.exists():
        sdk = Path(subprocess.check_output(["xcrun", "--sdk", "macosx", "--show-sdk-path"], text=True).strip())
    swift = work / "swift-drawer-checks"
    sources = [ROOT / "nashclip-swift/Sources" / (name + ".swift") for name in
               ["Storage", "HistoryPolicy", "ClipboardModel", "Views", "DrawerController"]]
    run("swiftc", "-swift-version", "5", "-parse-as-library", "-sdk", sdk,
        "-module-cache-path", work / "modules", "-framework", "AppKit", "-framework", "SwiftUI",
        *sources, ROOT / "tests/SwiftDrawerChecks.swift", "-o", swift)
    run(swift, work / "swift-data")
