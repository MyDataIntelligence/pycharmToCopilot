#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
./gradlew buildPlugin --no-daemon
echo "Plugin ZIP: $(find build/distributions -name '*.zip' -type f | head -n 1)"
echo "Install it through PyCharm: Settings > Plugins > gear icon > Install Plugin from Disk."
