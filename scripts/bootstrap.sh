#!/usr/bin/env bash
# Fetch the third-party pieces the build needs but that we do NOT commit:
#   - Google's WFF XSD schemas (validation target)
#   - Xerces + XPath2 jars (XSD 1.1 support; the JDK only does 1.0)
#   - the Gradle wrapper jar
#
# These belong to Google and Apache, not to this repo. Fetching keeps the
# licensing clean and the repo small, and means you always validate against
# a current schema.
#
# Windows: run this from WSL or Git Bash.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
WFF_REF="${WFF_REF:-main}"

echo "==> fetching google/watchface @ $WFF_REF"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
git clone --depth 1 --branch "$WFF_REF" https://github.com/google/watchface.git "$TMP/watchface"

SPEC="$TMP/watchface/third_party/wff/specification"

echo "==> installing WFF v2 schema -> generator/src/test/resources/wff-schema"
mkdir -p generator/src/test/resources
rm -rf generator/src/test/resources/wff-schema
cp -r "$SPEC/documents/2" generator/src/test/resources/wff-schema

echo "==> installing XSD 1.1 jars -> generator/libs"
mkdir -p generator/libs
cp "$SPEC/validator/libs/"*.jar generator/libs/

echo "==> mirroring schema for the standalone template build"
mkdir -p watchface-template/tools
rm -rf watchface-template/tools/wff-schema watchface-template/tools/libs
cp -r "$SPEC/documents/2" watchface-template/tools/wff-schema
mkdir -p watchface-template/tools/libs
cp "$SPEC/validator/libs/"*.jar watchface-template/tools/libs/

echo "==> compiling the standalone schema validator"
if command -v javac >/dev/null; then
  javac -nowarn -cp "$(ls watchface-template/tools/libs/*.jar | tr '\n' ':')" \
        -d watchface-template/tools watchface-template/tools/V.java
else
  echo "    javac not found -- skipping. Install a JDK to use watchface-template/build.sh"
fi

if [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
  echo "==> generating the Gradle wrapper"
  if command -v gradle >/dev/null; then
    gradle wrapper --gradle-version 8.10
  else
    echo "    No system Gradle. Either install one and re-run, or open the project"
    echo "    in Android Studio / IntelliJ, which will generate the wrapper for you."
  fi
fi

echo
echo "done. next:"
echo "    ./gradlew :generator:test"
