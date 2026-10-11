#!/usr/bin/env python3
"""Fetch pinned, SHA-256-verified test-only JaCoCo artifacts from Maven Central."""
import hashlib
import urllib.request
from coverage_tools import ROOT, VERSION, ARTIFACTS

folder = ROOT / ".test-tools" / ("jacoco-" + VERSION)
folder.mkdir(parents=True, exist_ok=True)
for name, expected in ARTIFACTS.items():
    target = folder / name
    if target.is_file() and hashlib.sha256(target.read_bytes()).hexdigest() == expected:
        print("Verified", name)
        continue
    artifact = name.split("-" + VERSION)[0]
    url = f"https://repo.maven.apache.org/maven2/org/jacoco/{artifact}/{VERSION}/{name}"
    with urllib.request.urlopen(url, timeout=30) as response:
        data = response.read(8 * 1024 * 1024)
    if hashlib.sha256(data).hexdigest() != expected:
        raise SystemExit("Checksum mismatch: " + name)
    temporary = target.with_suffix(".tmp")
    temporary.write_bytes(data)
    temporary.replace(target)
    print("Installed", name)
