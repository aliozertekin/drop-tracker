#!/usr/bin/env sh
set -eu
GRADLE_VERSION="8.10.2"
if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi
CACHE_ROOT="${GRADLE_USER_HOME:-$HOME/.gradle}/drop-tracker-bootstrap"
DIST_DIR="$CACHE_ROOT/gradle-$GRADLE_VERSION"
ZIP_PATH="$CACHE_ROOT/gradle-$GRADLE_VERSION-bin.zip"
if [ ! -x "$DIST_DIR/bin/gradle" ]; then
  mkdir -p "$CACHE_ROOT"
  if [ ! -f "$ZIP_PATH" ]; then
    echo "Gradle not installed; downloading Gradle $GRADLE_VERSION from services.gradle.org..."
    if command -v curl >/dev/null 2>&1; then
      curl --fail --location --retry 3 "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$ZIP_PATH"
    else
      echo "Please install Gradle $GRADLE_VERSION or install curl and retry." >&2
      exit 1
    fi
  fi
  EXPECTED_SHA256="31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26"
  ACTUAL_SHA256="$(sha256sum "$ZIP_PATH" | awk '{print $1}')"
  if [ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]; then
    rm -f "$ZIP_PATH"
    echo "Gradle distribution SHA-256 mismatch; refusing to execute it." >&2
    exit 1
  fi
  if ! command -v unzip >/dev/null 2>&1; then
    echo "Please install unzip, then run this command again." >&2
    exit 1
  fi
  unzip -q -o "$ZIP_PATH" -d "$CACHE_ROOT"
fi
exec "$DIST_DIR/bin/gradle" "$@"
