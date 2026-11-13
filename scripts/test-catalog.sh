#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT
javac --release 21 -d "$BUILD_DIR" src/main/java/kz/zavod/catalog/*.java src/test/java/kz/zavod/catalog/*.java
java -cp "$BUILD_DIR" kz.zavod.catalog.CatalogSmokeTest
