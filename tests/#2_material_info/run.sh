#!/bin/bash
# #2 — material_info exposes propertyGroup identifier (KEPT 2026-07-10)
# Repro: Inspect mat1 (Au) on FP_ahn_conv_M0.mph, expect RefractiveIndex identifier=rfi.
# This is the trap: propertyGroup().create("rg1","RefractiveIndex") gives identifier="rg1"
# (= your tag), not "rfi". material_info was built to expose this. If a future change
# breaks identifier reporting, this test catches it before a user hits "Undefined n".
source "$(dirname "$0")/../_lib.sh"
MPH="/home/pengyk/Project/Fabry–Pérot 微腔/01_models/FP_ahn_conv_M0.mph"
out="$(run_helper MaterialInfo "model=$MPH" "material=mat1")"
# Output format under <<<MATGROUP_comp1_mat1>>>:
#   groupname	identifier      (header)
#   RefractiveIndex	rfi         (data line: <groupname>\t<identifier>)
if echo "$out" | grep -q '<<<MATGROUP_comp1_mat1>>>' && echo "$out" | grep -qE '^RefractiveIndex	rfi$' && echo "$out" | grep -q '<<<END>>>'; then
  echo "#2 PASS (mat1 Au RefractiveIndex identifier=rfi)"
  exit 0
fi
echo "#2 FAIL — expected MATGROUP_comp1_mat1 with 'RefractiveIndex<TAB>rfi', got:"
echo "$out" | awk '/^<<<MATGROUP_comp1_mat1>>>$/{f=1} /^<<<[A-Z]/{if(f&&NR>1)f=0} f' | head
echo "--- raw tail ---"
echo "$out" | tail -25
exit 1