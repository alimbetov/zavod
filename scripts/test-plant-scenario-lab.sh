#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
BIN="$(mktemp -d)"
trap 'rm -rf "$BIN"' EXIT
javac --release 21 -d "$BIN" src/main/java/kz/zavod/lab/*.java src/test/java/kz/zavod/lab/*.java
java -cp "$BIN" kz.zavod.lab.PlantScenarioLabTest
