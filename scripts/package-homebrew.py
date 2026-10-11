#!/usr/bin/env python3
"""Archive an existing Swift app and generate a Homebrew cask; never publish it."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import shutil
import subprocess
import tempfile
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]


def run(*args):
    result = subprocess.run(args, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, check=True, timeout=180)
    return result.stdout


def https_url(value):
    parsed = urlsplit(value)
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username
            or parsed.password or parsed.fragment or parsed.query
            or "\\" in value or parsed.port not in (None, 443)
            or any(char.isspace() or ord(char) < 32 for char in value)):
        raise ValueError("Use permanent HTTPS URLs without credentials, queries, fragments, or custom ports")
    host = parsed.hostname.lower()
    if (host in ("localhost", "example.com", "example.org", "example.net")
            or host.endswith((".invalid", ".localhost", ".local", ".test"))
            or any(part.upper() in ("OWNER", "PROJECT", "YOUR_GITHUB_NAME")
                   for part in unquote(parsed.path).split("/"))):
        raise ValueError("Replace placeholder/local URLs with public release URLs")
    return value


def verify_binary(executable, architectures):
    """This app ships one Mach-O executable and uses only system libraries."""
    if not executable.is_file() or not os.access(executable, os.X_OK):
        raise ValueError("Bundle executable is missing or is not executable")
    for architecture in sorted(architectures):
        commands = run("otool", "-arch", architecture, "-l", str(executable))
        versions = []
        for command in re.split(r"Load command \d+", commands):
            if re.search(r"\bcmd LC_BUILD_VERSION\b", command):
                platform = re.search(r"\bplatform (\S+)", command)
                minimum = re.search(r"\bminos (\S+)", command)
                if not platform or platform[1] not in ("1", "MACOS") or not minimum:
                    raise ValueError("Executable must target macOS")
                versions.append(minimum[1])
            if re.search(r"\bcmd LC_VERSION_MIN_MACOSX\b", command):
                minimum = re.search(r"\bversion (\S+)", command)
                if minimum:
                    versions.append(minimum[1])
            if re.search(r"\bcmd LC_(?:LOAD|LOAD_WEAK|REEXPORT|LOAD_UPWARD)_DYLIB\b", command):
                library = re.search(r"\bname (.+) \(offset \d+\)", command)
                if not library or not library[1].startswith(("/usr/lib/", "/System/Library/")):
                    raise ValueError("Non-system library dependency needs an explicit bundling review")
            if re.search(r"\bcmd LC_RPATH\b", command):
                path = re.search(r"\bpath (.+) \(offset \d+\)", command)
                if not path or path[1] != "/usr/lib/swift":
                    raise ValueError("Unexpected executable runtime search path")
        if len(versions) != 1 or not re.fullmatch(r"\d+(?:\.\d+){1,2}", versions[0]):
            raise ValueError("Could not verify executable minimum macOS version")
        minimum = tuple(map(int, versions[0].split(".")))
        if (minimum + (0, 0))[:3] > (13, 0, 0):
            raise ValueError("Executable requires newer macOS than the plist/cask declares")


def ruby_string(value):
    # JSON quoting covers quotes/backslashes; Ruby also needs interpolation escaped.
    return json.dumps(value).replace("#{", r"\#{").replace("#$", r"\#$").replace("#@", r"\#@")


def cask(version, checksum, url, homepage, architecture):
    arch_line = "" if architecture == "universal" else f"  depends_on arch: :{architecture}\n"
    return (
        'cask "nashclip" do\n'
        f'  version {ruby_string(version)}\n'
        f'  sha256 "{checksum}"\n\n'
        f'  url {ruby_string(url)}\n'
        '  name "nashclip"\n'
        '  desc "Clipboard history with a sliding drawer and pinned clips"\n'
        f'  homepage {ruby_string(homepage)}\n\n'
        f'{arch_line}'
        '  depends_on macos: :ventura\n\n'
        '  app "nashclip.app"\n\n'
        '  uninstall quit: "dev.nashclip.native"\n'
        'end\n'
    )


def package(app, output, url=None, homepage=None, preview=False, team_id=None):
    app, output = Path(app).absolute(), Path(output).absolute()
    if output.exists() or output.is_symlink():
        raise ValueError(f"Output already exists; choose a new directory: {output}")
    if app.name != "nashclip.app" or not app.is_dir():
        raise ValueError("Build nashclip.app first with sh nashclip-swift/build.sh")
    if app.is_symlink() or any(path.is_symlink() for path in app.rglob("*")):
        raise ValueError("Unexpected symlink in this app bundle; build a fresh bundle")
    app, output = app.resolve(), output.resolve()
    if output.is_relative_to(app):
        raise ValueError("Output must be outside the source app bundle")
    if not preview and (not url or not homepage):
        raise ValueError("Release mode requires --url and --homepage")
    if not preview and (not team_id or not re.fullmatch(r"[A-Z0-9]{10}", team_id)):
        raise ValueError("Release mode requires --team-id with your 10-character Apple Team ID")
    for value in (url, homepage):
        if value:
            https_url(value)
    # A snapshot keeps the source build untouched, including its signature/ticket.
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".nashclip-package-", dir=output.parent) as temporary:
        staging = Path(temporary)
        payload = staging / "payload"
        payload.mkdir()
        bundle = payload / "nashclip.app"
        run("ditto", str(app), str(bundle))
        with (bundle / "Contents/Info.plist").open("rb") as stream:
            info = plistlib.load(stream)
        if not isinstance(info, dict):
            raise ValueError("Bundle Info.plist must contain a dictionary")
        version = info.get("CFBundleShortVersionString", "")
        if not isinstance(version, str) or not re.fullmatch(r"[0-9]+(?:\.[0-9]+){0,2}", version):
            raise ValueError("Set a numeric release version in Info.plist before building")
        if (info.get("CFBundleIdentifier") != "dev.nashclip.native"
                or info.get("CFBundleExecutable") != "nashclip"):
            raise ValueError("Unexpected app identity or executable")
        if info.get("LSMinimumSystemVersion") != "13.0":
            raise ValueError("Update the cask minimum OS mapping for this bundle before packaging")
        build_version = info.get("CFBundleVersion")
        if (info.get("CFBundlePackageType") != "APPL" or not isinstance(build_version, str)
                or not re.fullmatch(r"[0-9]+(?:\.[0-9]+){0,2}", build_version)):
            raise ValueError("Bundle must declare APPL and a numeric build version string")
        for name in ("LICENSE", "NOTICE"):
            embedded = bundle / "Contents/Resources" / name
            if not embedded.is_file() or embedded.read_bytes() != (ROOT / name).read_bytes():
                raise ValueError(f"Missing or stale embedded {name}; rebuild before signing")
        executable = bundle / "Contents/MacOS/nashclip"
        architectures = set(run("lipo", "-archs", str(executable)).split())
        if architectures == {"arm64", "x86_64"}:
            architecture = "universal"
        elif architectures in ({"arm64"}, {"x86_64"}):
            architecture = next(iter(architectures))
        else:
            raise ValueError(f"Unsupported architectures: {sorted(architectures)}")
        verify_binary(executable, architectures)
        suffix = "-preview" if preview else ""
        filename = f"nashclip-{version}-macos-{architecture}{suffix}.zip"
        if url and unquote(urlsplit(url).path).split("/")[-1] != filename:
            raise ValueError(f"Download URL must end with the generated archive name: {filename}")
        run("codesign", "--verify", "--all-architectures", "--deep", "--strict", str(bundle))
        if not preview:
            for slice_arch in sorted(architectures):
                signature = run("codesign", "--display", "--verbose=4",
                                "--architecture", slice_arch, str(bundle))
                if (not any(line.startswith("Authority=Developer ID Application:")
                            for line in signature.splitlines())
                        or not re.search(r"^CodeDirectory .*flags=.*\bruntime\b", signature, re.MULTILINE)):
                    raise ValueError(f"{slice_arch}: release needs Developer ID signing with hardened runtime; see docs/homebrew.md")
                if f"TeamIdentifier={team_id}" not in signature.splitlines():
                    raise ValueError(f"{slice_arch}: signing TeamIdentifier does not match --team-id")
            run("xcrun", "stapler", "validate", str(bundle))
            run("spctl", "--assess", "--type", "execute", "--verbose=4", str(bundle))
        for name in ("LICENSE", "NOTICE"):
            shutil.copy2(ROOT / name, payload / name)
        archive = staging / filename
        run("ditto", "-c", "-k", "--sequesterRsrc", str(payload), str(archive))
        checksum = hashlib.sha256(archive.read_bytes()).hexdigest()
        prepared = staging / "result"
        prepared.mkdir()
        shutil.move(archive, prepared / filename)
        (prepared / "SHA256SUMS").write_text(f"{checksum}  {filename}\n")
        casks = prepared / "Casks"
        casks.mkdir()
        cask_path = casks / ("nashclip.rb.example" if preview else "nashclip.rb")
        cask_url = url or f"https://example.invalid/{filename}"
        cask_homepage = homepage or "https://example.invalid/nashclip"
        cask_path.write_text(cask(
            version, checksum, cask_url, cask_homepage, architecture))
        (prepared / "package.json").write_text(json.dumps({
            "version": version, "architecture": architecture, "archive": filename,
            "sha256": checksum, "preview": preview, "url": url,
            "distribution_checks": "skipped" if preview else "passed",
            "published": False,
            "build_version": info["CFBundleVersion"], "team_id": team_id,
            "cask_url": cask_url, "homepage": cask_homepage,
        }, indent=2) + "\n")
        if preview:
            (prepared / "PREVIEW-ONLY.txt").write_text(
                "Local packaging preview. Signing/notarization/Gatekeeper distribution checks "
                "were skipped. Do not publish this as a release. The .rb.example is a draft.\n")
        # Reserve the name without overwriting any existing output, then replace
        # our empty directory with the complete result on the same filesystem.
        output.mkdir()
        try:
            prepared.replace(output)
        except OSError:
            output.rmdir()
            raise
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app", type=Path, default=ROOT / "nashclip-swift/build/nashclip.app")
    parser.add_argument("--output", type=Path, required=True, help="New output directory (never overwritten)")
    parser.add_argument("--url", help="Public HTTPS download URL for the exact resulting ZIP")
    parser.add_argument("--homepage", help="Public HTTPS project homepage")
    parser.add_argument("--team-id", help="Expected Apple Team ID (required for release mode)")
    parser.add_argument("--preview", action="store_true", help="Local draft only; skip distribution checks")
    args = parser.parse_args()
    try:
        output = package(args.app, args.output, args.url, args.homepage, args.preview, args.team_id)
    except (ValueError, OSError, plistlib.InvalidFileException, subprocess.SubprocessError) as error:
        if isinstance(error, subprocess.CalledProcessError):
            parser.exit(1, f"Packaging failed: {error.cmd}\n{error.stdout}\n")
        parser.exit(1, f"Packaging failed: {error}\n")
    print(f"{'PREVIEW ONLY' if args.preview else 'Release files prepared (not published)'}: {output}")


if __name__ == "__main__":
    main()
