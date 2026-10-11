#!/bin/sh
set -eu
exec python3 "$(dirname "$0")/../scripts/test.py" --variant swift "$@"
