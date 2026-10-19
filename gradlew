#!/usr/bin/env bash
set -euo pipefail

PROPERTIES_FILE="$(cd "$(dirname "$0")" && pwd)/gradle/wrapper/gradle-wrapper.properties"
DIST_URL="$(sed -n 's/^distributionUrl=//p' "$PROPERTIES_FILE" | sed 's/\\:/:/g')"
if [[ -z "$DIST_URL" ]]; then
  echo "Missing distributionUrl in $PROPERTIES_FILE" >&2
  exit 1
fi

if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi

DIST_FILENAME="${DIST_URL##*/}"
DIST_NAME="${DIST_FILENAME%.zip}"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
DIST_DIR="$GRADLE_USER_HOME/wrapper/manual/$DIST_NAME"
GRADLE_BIN="$DIST_DIR/bin/gradle"

if [[ ! -x "$GRADLE_BIN" ]]; then
  mkdir -p "$GRADLE_USER_HOME/wrapper/manual"
  ZIP_FILE="$GRADLE_USER_HOME/wrapper/manual/$DIST_FILENAME"
  curl --fail --location --retry 3 --output "$ZIP_FILE" "$DIST_URL"
  mkdir -p "$DIST_DIR"
  unzip -q -o "$ZIP_FILE" -d "$GRADLE_USER_HOME/wrapper/manual"
  rm -f "$ZIP_FILE"
  if [[ ! -x "$GRADLE_BIN" ]]; then
    echo "Gradle distribution did not contain $GRADLE_BIN" >&2
    exit 1
  fi
fi

exec "$GRADLE_BIN" "$@"
