#!/bin/bash

# @raycast.schemaVersion 1
# @raycast.title Buds: Noise Cancellation
# @raycast.mode silent
# @raycast.icon 🎧
# @raycast.packageName Realme Buds

cd "$(dirname "$0")/.." || exit 1
out=$(.venv/bin/python realme_anc.py anc 2>&1) || { echo "$out" | grep -m1 -E "failed|Missing" || echo "Failed"; exit 1; }
echo "🎧 Noise Cancellation"
