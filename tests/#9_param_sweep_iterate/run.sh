#!/bin/bash
# #9 — param_sweep iterate mode (KEPT 2026-07-11)
# Repro: ParamSweep on FP_ahn_conv_M0.mph (has Frequency sweep) with mode=auto,
# expect auto-detection picks iterate (not parametric) and the run completes
# without Invalid_property_value. This is the bug that bit twice (#3 then #9):
# Parametric feature stacked on Frequency sweep → Invalid_property_value.
# If a refactor regresses iterate auto-detection, this catches it.
#
# Slow (~20-30s: 2 solves + load). verify.sh runs it by default; set
# COMSOL_FAST=1 to skip slow tests for a quick pre-commit gate.
source "$(dirname "$0")/../_lib.sh"
[ "${COMSOL_FAST:-0}" = 1 ] && { echo "#9 SKIP (COMSOL_FAST=1)"; exit 0; }
MPH="/home/pengyk/Project/Fabry–Pérot 微腔/01_models/FP_ahn_conv_M0.mph"
out="$(run_helper ParamSweep "model=$MPH" "study=std1" "pname=tAu" "plistarr=0.006[um],0.008[um]" "mode=auto")"
# Expect RUNNING section to print mode=iterate and the run to reach END
if echo "$out" | grep -qE '^mode	iterate' && echo "$out" | grep -q '<<<END>>>'; then
  echo "#9 PASS (auto → iterate on freq-sweep model, no Invalid_property_value)"
  exit 0
fi
echo "#9 FAIL — expected RUNNING 'mode<TAB>iterate' + END marker:"
echo "$out" | grep -E 'mode	|existing_sweeps	|Invalid_property|<<<END' | head
echo "--- raw tail ---"
echo "$out" | tail -25
exit 1