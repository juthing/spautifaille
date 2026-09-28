#!/bin/bash
# Installe le SDK Android dans les sessions Claude Code on the web (conteneur éphémère).
set -euo pipefail
[ "${CLAUDE_CODE_REMOTE:-}" = "true" ] || exit 0

SDK="$HOME/android-sdk"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  mkdir -p "$SDK/cmdline-tools"
  tmp=$(mktemp -d)
  curl -sSfL -o "$tmp/cmd.zip" https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip
  unzip -q "$tmp/cmd.zip" -d "$tmp"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
fi
export ANDROID_HOME="$SDK"
if [ ! -d "$SDK/platforms/android-37.0" ] || [ ! -d "$SDK/build-tools/36.0.0" ]; then
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null 2>&1 || true
  "$SDK/cmdline-tools/latest/bin/sdkmanager" "platforms;android-37.0" "build-tools;36.0.0" "platform-tools" > /dev/null
fi
echo "sdk.dir=$SDK" > "$CLAUDE_PROJECT_DIR/local.properties"
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  echo "export ANDROID_HOME=$SDK" >> "$CLAUDE_ENV_FILE"
fi
