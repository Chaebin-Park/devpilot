#!/bin/bash
# DevPilot launcher
# Usage: ./devpilot.sh [--web] [-d=/path/to/project]
#
# Build fat JAR first: ./gradlew shadowJar
# Output: build/libs/devpilot.jar

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SCRIPT_DIR/build/libs/devpilot.jar"

if [ ! -f "$JAR" ]; then
    echo "devpilot.jar not found. Building..."
    cd "$SCRIPT_DIR" && ./gradlew shadowJar
fi

exec java --enable-native-access=ALL-UNNAMED -jar "$JAR" "$@"
