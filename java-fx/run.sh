#!/bin/sh
set -eu

cd "$(dirname "$0")"
sh native/build.sh
exec mvn -s maven-settings.xml -Dmaven.repo.local=target/m2 -q javafx:run
