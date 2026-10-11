#!/bin/sh
set -eu

cd "$(dirname "$0")"
mkdir -p out
javac -encoding UTF-8 -d out src/*.java
sh native/build.sh
