#!/bin/sh
set -eu

cd "$(dirname "$0")"
destination="$PWD/build/nashclip.app"
if [ -L build ]; then
    echo "Refusing to build through a symlink at $PWD/build" >&2
    exit 1
fi
mkdir -p build
lock="$PWD/build/.nashclip-build.lock"
if ! mkdir "$lock" 2>/dev/null; then
    echo "Another build owns $lock. Retry after it finishes. Remove a stale lock only after confirming no build is running." >&2
    exit 1
fi
staging=""
installed=0
cleanup() {
    build_status=$?
    trap - EXIT INT TERM
    if [ -n "$staging" ]; then
        if [ "$installed" -eq 0 ] && [ -d "$staging/previous.app" ]; then
            if [ -e "$destination" ] || [ -L "$destination" ] || ! mv "$staging/previous.app" "$destination"; then
                echo "Previous build preserved at $staging/previous.app; restore it manually after resolving the destination error." >&2
                rmdir "$lock"
                exit 1
            fi
        fi
        rm -rf "$staging"
    fi
    rmdir "$lock"
    exit "$build_status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
staging="$(mktemp -d "$PWD/build/.nashclip-build.XXXXXX")"
app="$staging/nashclip.app"
architecture="$(uname -m)"
sdk="${NASHCLIP_SDK:-$(xcrun --sdk macosx --show-sdk-path)}"
# This CLT installation's default 27.0 SDK is newer than its Swift compiler.
if [ -z "${NASHCLIP_SDK:-}" ] && [ -d /Library/Developer/CommandLineTools/SDKs/MacOSX26.5.sdk ]; then
    sdk=/Library/Developer/CommandLineTools/SDKs/MacOSX26.5.sdk
fi
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources" build/nashclip-module-cache/clang
cp Info.plist "$app/Contents/Info.plist"
cp ../LICENSE ../NOTICE "$app/Contents/Resources/"
swiftc -swift-version 5 -parse-as-library -O \
    -target "$architecture-apple-macos13.0" \
    -sdk "$sdk" \
    -module-cache-path "$PWD/build/nashclip-module-cache" \
    -Xcc -fmodules-cache-path="$PWD/build/nashclip-module-cache/clang" \
    -framework AppKit -framework SwiftUI -framework Carbon \
    -framework ApplicationServices \
    Sources/*.swift -o "$app/Contents/MacOS/nashclip"
# Seal the local bundle, including Info.plist. Distribution still needs Developer ID signing.
codesign --force --sign - "$app"
codesign --verify --deep --strict "$app"
# Assemble from scratch so removed resources and local files cannot enter a release.
if [ -L "$destination" ]; then
    echo "Refusing to replace a symlink at $destination" >&2
    exit 1
fi
if [ -e "$destination" ] && [ ! -d "$destination" ]; then
    echo "Refusing to replace a non-directory at $destination" >&2
    exit 1
fi
if [ -e "$destination" ]; then
    mv "$destination" "$staging/previous.app"
fi
mv "$app" "$destination"
installed=1
echo "$destination"
