#!/usr/bin/env bash
# Build the playable-fork Android APK + the asset bundle the app downloads on
# first boot. Output lands in forge-gui-android/target/android-release/ named
# exactly as AssetsDownloader expects (forge-android-<version>-signed-aligned.apk,
# assets.zip, version.txt, build.txt).
#
# Toolchain pins (all deliberate, mirroring upstream CI for the android module):
#   - Maven 3.8.1        — android-maven-plugin 4.6.2 breaks on Maven 3.9+
#   - JDK 17 (Temurin)   — proguard 7.6 reads <java.home>/jmods as library jars
#                          and rejects class files newer than Java 23
#   - android-maven-plugin 4.6.2 is a Card-Forge custom build, NOT on Central;
#     fetched once into ~/.m2 from Card-Forge/android-maven-plugin releases
#   - Android SDK: platform 35 + build-tools 35.0.0 (sdkmanager-installed)
#   - package renamed to forge.app.playable at aapt time so the fork installs
#     ALONGSIDE stock Forge (own icon, own Android/obb data dir). Resources
#     stay compiled under forge.app; Main.resId() has the fallback.
#   - signed with ~/.android/debug.keystore via tools/uber-apk-signer.jar
#     (v2+v3). KEEP THAT KEYSTORE: the phone only accepts updates signed by
#     the same key. The in-process jarsigner path is disabled in the pom
#     (sun.security.pkcs is sealed on JDK 17+).
#
# Usage (from the repo root, on the playable branch):
#   scripts/build-android.sh
#
# Publishing is a deliberate human step (needs gh auth on Tyrathalis/forge).
# ALWAYS pin -R (this clone's gh default repo is Card-Forge/forge):
#   gh release upload daily-snapshots forge-gui-android/target/android-release/* --clobber -R Tyrathalis/forge
#
# NOTE version.txt/build.txt are shared with the desktop channel at the same
# URL. Desktop clients tolerate an android-only bump: the delta updater prices
# the update exactly and a 0-file plan reads as "silently current". Still,
# prefer publishing android + desktop together (build the jar first, run
# release-playable.sh, then this, then upload both).

set -euo pipefail
cd "$(dirname "$0")/.."

MVN=${MVN:-$HOME/.local/opt/apache-maven-3.8.1/bin/mvn}
JDK17=${JDK17:-$HOME/.local/opt/temurin17}
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
KS=$HOME/.android/debug.keystore

[ -x "$MVN" ] || { echo "Maven 3.8.1 not found at $MVN" >&2; exit 1; }
[ -x "$JDK17/bin/java" ] || { echo "Temurin 17 not found at $JDK17" >&2; exit 1; }
[ -d "$SDK/build-tools/35.0.0" ] || { echo "Android SDK build-tools 35.0.0 not found under $SDK" >&2; exit 1; }
[ -f "$KS" ] || { echo "Debug keystore missing at $KS — updates would change signature; refusing" >&2; exit 1; }
[ -f "$HOME/.m2/repository/com/simpligility/maven/plugins/android-maven-plugin/4.6.2/android-maven-plugin-4.6.2.jar" ] || {
    echo "android-maven-plugin 4.6.2 missing from ~/.m2 — fetch jar+pom from" >&2
    echo "https://github.com/Card-Forge/android-maven-plugin/releases/tag/4.6.2" >&2; exit 1; }

echo "== Building APK (this takes a few minutes: proguard + d8) =="
JAVA_HOME=$JDK17 ANDROID_HOME=$SDK nice -n 19 "$MVN" -P android-debug \
    -pl forge-gui-android -am package \
    -Dandroid.sdk.path="$SDK" -Dandroid.buildToolsVersion=35.0.0 \
    -Dandroid.renameManifestPackage=forge.app.playable \
    -Dmaven.test.skip=true -q

cd forge-gui-android
APK=$(ls target/forge-android-*.apk | grep -v signed | grep -v aligned | head -1)
VERSION=$(cat target/classes/assets/version.txt)
echo "== Signing $APK (version $VERSION) =="
rm -f "${APK%.apk}"-*signed*.apk
"$JDK17/bin/java" -jar tools/uber-apk-signer.jar -a "$APK" \
    --ks "$KS" --ksAlias androiddebugkey --ksPass android --ksKeyPass android \
    | grep -E "sign success|signature verified|error" || true
SIGNED="${APK%.apk}-signed-aligned.apk"
[ -f "$SIGNED" ] || { echo "Signing failed — $SIGNED missing" >&2; exit 1; }

echo "== Building assets.zip (release-profile recipe) =="
STAGE=target/assets-stage
rm -rf "$STAGE" target/assets.zip
mkdir -p "$STAGE"
cp ../forge-gui/LICENSE.txt \
   ../forge-gui/release-files/CONTRIBUTORS.txt \
   ../forge-gui/release-files/INSTALLATION.txt \
   ../forge-gui/release-files/ISSUES.txt \
   ../forge-gui-mobile-dev/sentry.properties "$STAGE/"
cp ../forge-gui-desktop/target/CHANGES.txt "$STAGE/" 2>/dev/null || true
rsync -a --exclude cardsfolder --exclude '*.xcf' ../forge-gui/res "$STAGE/"
cp target/classes/assets/build.txt "$STAGE/res/"
mkdir -p "$STAGE/res/cardsfolder"
python3 - "$STAGE" <<'EOF'
import sys, zipfile, os
base = sys.argv[1]

# GuiDownloadZipService.extract() only mkdirs on explicit DIRECTORY entries
# (parents-first, the way ant writes them) and per-file failures are silently
# counted, not fatal — a zip without dir entries extracts 0 files and the app
# loops on the download prompt. Emit dir entries exactly like ant does.
def zipdir(zpath, srcdir):
    with zipfile.ZipFile(zpath, 'w', zipfile.ZIP_DEFLATED, compresslevel=1) as z:
        for root, dirs, files in os.walk(srcdir):
            dirs.sort(); files.sort()
            for d in dirs:
                rel = os.path.relpath(os.path.join(root, d), srcdir)
                z.writestr(zipfile.ZipInfo(rel + '/'), b'')
            for f in files:
                p = os.path.join(root, f)
                z.write(p, os.path.relpath(p, srcdir))

zipdir(base + '/res/cardsfolder/cardsfolder.zip', '../forge-gui/res/cardsfolder')
zipdir('target/assets.zip', base)
EOF
#published standalone too: the in-app res delta refreshes cards as this one
#asset (~17MB) instead of the full assets.zip when any card script changed
cp "$STAGE/res/cardsfolder/cardsfolder.zip" target/
rm -rf "$STAGE"

echo "== Staging android-release/ =="
OUT=target/android-release
rm -rf "$OUT"; mkdir -p "$OUT"
cp "$SIGNED" target/assets.zip target/cardsfolder.zip target/classes/assets/version.txt target/classes/assets/build.txt "$OUT/"
ls -lh "$OUT"
echo
echo "Publish (deliberate step — note -R pin):"
echo "  gh release upload daily-snapshots forge-gui-android/target/android-release/* --clobber -R Tyrathalis/forge"
echo "Sideload copy:"
echo "  cp forge-gui-android/target/android-release/*.apk ~/Everything/Sync/Other/"
