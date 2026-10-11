#!/bin/sh
set -eu
cd "$(dirname "$0")"
/usr/bin/xcrun clang -Wall -Wextra -O2 -fblocks -framework Carbon -framework AppKit hotkey.m -o nashclip-hotkey

nashclip_jdk="${JAVA_HOME:-$(/usr/libexec/java_home)}"
/usr/bin/xcrun clang -Wall -Wextra -Wno-deprecated-declarations -O2 -fobjc-arc -dynamiclib \
    -I"$nashclip_jdk/include" -I"$nashclip_jdk/include/darwin" \
    -framework AppKit -framework ApplicationServices focus.m -o libnashclip-focus.dylib
