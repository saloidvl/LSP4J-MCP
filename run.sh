#!/bin/bash
# Run the LSP4J MCP launcher
#
# Usage: ./run.sh <workspace-path>
# Example: ./run.sh /Users/me/projects/my-service

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERSION="$(awk '
    /<artifactId>lsp4j-mcp<\/artifactId>/ { in_project = 1; next }
    in_project && /<version>/ {
        gsub(/.*<version>|<\/version>.*/, "", $0)
        print
        exit
    }
' "$SCRIPT_DIR/pom.xml")"
JAR_FILE="$SCRIPT_DIR/dist/lsp4j-mcp-$VERSION.jar"

# Default JDTLS command - adjust for your system
JDTLS_CMD="${JDTLS_CMD:-jdtls}"

if [ -z "$1" ]; then
    echo "Usage: $0 <workspace-path>"
    echo "Example: $0 /Users/me/projects/my-service"
    exit 1
fi

if [ ! -f "$JAR_FILE" ]; then
    echo "Missing published artifact: $JAR_FILE"
    echo "Run ./scripts/bump-and-publish.sh <new-version> first."
    exit 1
fi

WORKSPACE_PATH="$1"

# The shaded jar now starts LauncherMain. The launcher reuses a shared
# per-repository worker and JDTLS instance when one is already running.
exec java -jar "$JAR_FILE" "$WORKSPACE_PATH" "$JDTLS_CMD"
