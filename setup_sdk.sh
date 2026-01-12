#!/bin/bash
set -e

SDK_DIR="$HOME/Android/Sdk"
cd "$SDK_DIR"

# Organize cmdline-tools directory structure
if [ -d "cmdline-tools" ] && [ ! -d "cmdline-tools/latest" ]; then
    mkdir -p cmdline-tools/latest
    if [ -d "cmdline-tools/bin" ]; then
        mv cmdline-tools/bin cmdline-tools/lib cmdline-tools/NOTICE.txt cmdline-tools/source.properties cmdline-tools/latest/ 2>/dev/null || true
    fi
fi

# Accept licenses and install required SDK components
export ANDROID_HOME="$SDK_DIR"
export PATH="$PATH:$SDK_DIR/cmdline-tools/latest/bin:$SDK_DIR/platform-tools"

yes | cmdline-tools/latest/bin/sdkmanager --licenses || true
cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"

echo "SDK setup complete"

