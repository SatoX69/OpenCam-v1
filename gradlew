#!/usr/bin/env sh
set -eu
GRADLE_VERSION="8.11.1"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
CACHE_DIR="$GRADLE_USER_HOME/openlens-distributions/gradle-$GRADLE_VERSION"
GRADLE_BIN="$CACHE_DIR/gradle-$GRADLE_VERSION/bin/gradle"
if [ ! -x "$GRADLE_BIN" ]; then
  mkdir -p "$CACHE_DIR"
  ZIP="$CACHE_DIR/gradle.zip"
  if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 3 "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$ZIP"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$ZIP" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
  else
    echo "curl or wget is required to download Gradle." >&2
    exit 1
  fi
  EXPECTED_SHA256="f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6"
  if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL_SHA256="$(sha256sum "$ZIP" | awk '{print $1}')"
  elif command -v shasum >/dev/null 2>&1; then
    ACTUAL_SHA256="$(shasum -a 256 "$ZIP" | awk '{print $1}')"
  else
    echo "sha256sum or shasum is required to verify the Gradle download." >&2
    rm -f "$ZIP"
    exit 1
  fi
  if [ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]; then
    echo "Gradle distribution checksum mismatch; refusing to execute it." >&2
    rm -f "$ZIP"
    exit 1
  fi
  if command -v unzip >/dev/null 2>&1; then
    unzip -q -o "$ZIP" -d "$CACHE_DIR"
  else
    echo "unzip is required to unpack Gradle." >&2
    exit 1
  fi
  rm -f "$ZIP"
fi
exec "$GRADLE_BIN" "$@"
