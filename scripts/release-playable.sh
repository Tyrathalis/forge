#!/usr/bin/env bash
# Assemble a playable-fork release: the launcher jar, per-file delta manifest,
# and the version/build stamps the in-app updater checks. Everything is derived
# from the built jar itself, so the published pair can never drift from what
# users actually run.
#
# Usage (from the repo root, on the playable-qol/playable branch):
#   mvn -pl forge-gui-mobile-dev -am package -DskipTests
#   scripts/release-playable.sh
#
# Publishing is a deliberate human step (needs gh auth on Tyrathalis/forge).
# ALWAYS pin -R: this clone's gh default repo is Card-Forge/forge (for PR
# work), so bare release commands target UPSTREAM's daily-snapshots release:
#   gh release create daily-snapshots -R Tyrathalis/forge --prerelease --title "Playable snapshots" --notes "Rolling playable build" || true
#   gh release upload daily-snapshots target/playable-release/* --clobber -R Tyrathalis/forge
#
# The res/ delta needs no uploading at all: the updater fetches changed res
# files from raw.githubusercontent.com at the commit recorded in the manifest,
# so the only requirement is that this commit is PUSHED to the fork.

set -euo pipefail
cd "$(dirname "$0")/.."

JAR=forge-gui-mobile-dev/target/forge-gui-mobile-dev-*-jar-with-dependencies.jar
# Newest by mtime: an alphabetical glob picks a STALE jar when target/ holds
# builds from two version eras (2.0.14 sorts before 2.0.15 — bit us 2026-08-21).
JAR=$(ls -t $JAR 2>/dev/null | head -1) || { echo "Build the jar first (mvn -pl forge-gui-mobile-dev -am package)"; exit 1; }
[ -f "$JAR" ] || { echo "Build the jar first (mvn -pl forge-gui-mobile-dev -am package)"; exit 1; }

COMMIT=$(git rev-parse HEAD)
if ! git diff --quiet HEAD -- forge-gui/res; then
    echo "ERROR: forge-gui/res has uncommitted changes; the manifest must describe a pushed commit." >&2
    exit 1
fi

OUT=target/playable-release
rm -rf "$OUT"
mkdir -p "$OUT"

# Version and build stamp come out of the jar so they always match the binary.
VERSION=$(unzip -p "$JAR" META-INF/MANIFEST.MF | sed -n 's/^Implementation-Version: *//p' | tr -d '\r' | head -1)
[ -n "$VERSION" ] || VERSION="unknown"
unzip -p "$JAR" build.txt > "$OUT/build.txt"
printf '%s' "$VERSION" > "$OUT/version.txt"

JAR_ASSET=forge-playable.jar
cp "$JAR" "$OUT/$JAR_ASSET"

echo "Hashing res tree + jar for the manifest (this takes a minute)..."
MANIFEST="$OUT/manifest.txt"
# Bridge format (the 07.31 URL-encoding incident): parsers shipped through
# 07.29 never percent-encoded raw-file URLs, so any delta touching a path with
# a space aborts on them. Plain entries therefore list ONLY the jar (a release
# asset - its URL needs no encoding), which legacy clients can always apply;
# the full file list rides on "#2 " lines those parsers skip as unknown
# headers and fixed parsers prefer. A legacy client hops the jar, restarts,
# and the fixed jar's self-heal pass completes res/ with encoded URLs.
{
    echo "#forge-playable-manifest v1"
    echo "#version $VERSION"
    echo "#commit $COMMIT"
    echo "#jar $JAR_ASSET"
    sha=$(sha256sum "$OUT/$JAR_ASSET" | cut -d' ' -f1)
    size=$(stat -c%s "$OUT/$JAR_ASSET")
    printf '%s\t%s\t%s\n' "$sha" "$size" "$JAR_ASSET"
    printf '#2 %s\t%s\t%s\n' "$sha" "$size" "$JAR_ASSET"
    (cd forge-gui && find res -type f | sort | while read -r f; do
        sha=$(sha256sum "$f" | cut -d' ' -f1)
        size=$(stat -c%s "$f")
        printf '#2 %s\t%s\t%s\n' "$sha" "$size" "$f"
    done)
} > "$MANIFEST"

echo
echo "Release assembled in $OUT:"
ls -la "$OUT"
echo
echo "Manifest: $(grep -c '^#2 ' "$MANIFEST") files (+ legacy jar-only entry), commit $COMMIT"
echo "Publish with (-R is REQUIRED: this clone's gh default repo is Card-Forge/forge):"
echo "  gh release create daily-snapshots -R Tyrathalis/forge --prerelease --title \"Playable snapshots\" --notes \"Rolling playable build\" || true"
echo "  gh release upload daily-snapshots $OUT/* --clobber -R Tyrathalis/forge"
echo "Remember: the manifest commit must be pushed to Tyrathalis/forge for res delta fetches to resolve."
