#!/usr/bin/env bash
# Builds the daemon with the HOST toolchain (no NDK required) so its protocol
# and parsing logic can be integration-tested on any Linux machine or CI
# runner. The Android build itself is unaffected (CMake/NDK path).
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build
g++ -std=c++17 -O2 -Wall -Wextra -o build/taskmanagerd_host ../src/main/cpp/taskmanagerd.cpp
echo "Built build/taskmanagerd_host"
