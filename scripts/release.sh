#!/usr/bin/env bash
set -euo pipefail

if [ $# -ne 1 ]; then
  echo "Usage: $0 <new-version>" >&2
  exit 1
fi

VERSION="$1"

if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "Error: working tree has uncommitted changes. Commit or stash first." >&2
  exit 1
fi

echo "Bumping version to $VERSION..."
mvn versions:set -DnewVersion="$VERSION" -DgenerateBackupPoms=false -q

echo "Building..."
mvn clean package -q

echo "Committing..."
git add pom.xml
git commit -m "Release v$VERSION"

echo "Tagging v$VERSION..."
git tag -a "v$VERSION" -m "Release v$VERSION"

echo "Pushing..."
git push origin HEAD
git push origin "v$VERSION"

echo "Done. GitHub Actions will create the release for v$VERSION."