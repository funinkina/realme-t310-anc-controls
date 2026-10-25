#!/bin/bash

# @raycast.schemaVersion 1
# @raycast.title Buds: Normal (ANC off)
# @raycast.mode silent
# @raycast.icon 🎧
# @raycast.packageName Realme Buds

cd "$(dirname "$0")/.." || exit 1
out=$(.venv/bin/python realme_anc.py normal 2>&1) || { echo "$out" | grep -m1 -E "failed|Missing" || echo "Failed"; exit 1; }
echo "🎧 Normal (ANC off)"
