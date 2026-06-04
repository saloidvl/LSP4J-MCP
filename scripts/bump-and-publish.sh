#!/usr/bin/env bash

set -euo pipefail

NEW_VERSION="${1:-}"
if [[ -z "$NEW_VERSION" ]]; then
    echo "Usage: $0 <new-version>" >&2
    exit 1
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ARTIFACT_ID="lsp4j-mcp"
DIST_JAR="dist/${ARTIFACT_ID}-${NEW_VERSION}.jar"

if [[ -e "$DIST_JAR" ]]; then
    echo "Version already published: $DIST_JAR" >&2
    exit 1
fi

mvn versions:set -DnewVersion="$NEW_VERSION" -DgenerateBackupPoms=false
mvn clean package

SOURCE_JAR="target/${ARTIFACT_ID}-${NEW_VERSION}.jar"
if [[ ! -f "$SOURCE_JAR" ]]; then
    echo "Built artifact not found: $SOURCE_JAR" >&2
    exit 1
fi

mkdir -p dist
cp "$SOURCE_JAR" "$DIST_JAR"
echo "Published $DIST_JAR"
