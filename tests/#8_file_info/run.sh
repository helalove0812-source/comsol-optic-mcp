#!/bin/bash
# #8 — inspect_model reports file_info (size/mtime/has_sol) (KEPT 2026-07-10)
# Repro: Inspect FP_ahn_conv_M0.mph, expect <<<FILE_INFO>>> section with size_mb + has_sol.
# Guards against dirty/large .mph files going unnoticed (the 961MB unconverged-v1 case).
source "$(dirname "$0")/../_lib.sh"
MPH="/home/pengyk/Project/Fabry–Pérot 微腔/01_models/FP_ahn_conv_M0.mph"
out="$(run_helper Inspect "model=$MPH")"
# Extract FILE_INFO section body and check for size_mb + has_sol lines
body="$(echo "$out" | awk '/^<<<FILE_INFO>>>$/{f=1;next} /^<<<[A-Z]/{f=0} f')"
if echo "$body" | grep -q '^size_mb	' && echo "$body" | grep -q '^has_sol	' && echo "$out" | grep -q '<<<END>>>'; then
  echo "#8 PASS (file_info has size_mb + has_sol)"
  exit 0
fi
echo "#8 FAIL — FILE_INFO missing size_mb/has_sol:"
echo "$body"
exit 1