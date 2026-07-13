#!/bin/bash
# #26 — 2D PA build (compile + mesh-audit gate, no solve).
# Quick gate: build pa2d_body on the FP base and assert it compiles, solves-free
# (exit_code present means run_study ran; here we DON'T run_study, so we assert
# compiled + mesh_empty_selections empty). Catches the "#Undefined material
# property n" regression at BUILD time (compile OK is not enough — the malformed
# RefractiveIndex group only blows up at solve; but a build that re-introduces a
# bad material.duplicate call shows up as compile/diagnostic errors here).
#
# Fast (~10-15s, no solve). Runs in both full and COMSOL_FAST modes.
# Exit 0=PASS / 1=FAIL / 0 with "#26 SKIP ..." = skipped.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
BRIDGE_DIR="${COMSOL_MCP_DIR:-/home/pengyk/Project/.comsol-mcp}"
BASE="/home/pengyk/Project/Fabry–Pérot 微腔/01_models/FP_ahn_conv_M2_L744.mph"
BODY="$BRIDGE_DIR/java/pa2d_body.java"
[ -f "$BASE" ] || { echo "#26 SKIP  (FP base not found: $BASE)"; exit 0; }
[ -f "$BODY" ] || { echo "#26 FAIL  pa2d body not found: $BODY"; exit 1; }

REG="$(mktemp -d -t comsol_regr_XXXXXX)"
trap 'rm -rf "$REG"' EXIT
OUT="$REG/pa2d.mph"
KW=$(python3 - "$BODY" "$BASE" "$OUT" <<'PY'
import json, sys
print(json.dumps({
  "java_source": open(sys.argv[1]).read(),
  "base_model": sys.argv[2],
  "save_path": sys.argv[3],
}))
PY
)
# compiled true, zero diagnostics, zero java_warnings, no empty mesh selections,
# GEOM section present (geometry built) — the build is structurally sound.
out=$(python3 "$HERE/../_build_client.py" "$KW" \
  --expect "r.get('compiled') and not r.get('compile_diagnostics') and not r.get('java_warnings') and not r.get('mesh_empty_selections') and 'GEOM' in r.get('sections',{})" \
  --id '#26' 2>&1) \
  || { echo "#26 FAIL  build:"; echo "$out" | tail -25; exit 1; }
echo "$out" | grep -q '#26 PASS' && { echo "$out" | grep '#26 PASS'; exit 0; }
echo "#26 FAIL"; echo "$out" | tail -25; exit 1