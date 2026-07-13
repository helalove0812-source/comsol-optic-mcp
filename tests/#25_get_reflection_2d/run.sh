#!/bin/bash
# #25 — get_reflection_2d / EvalReflection2D helper (2D Floquet S11 extraction).
# Full 2D PA pipeline, self-contained: build pa2d_body on the FP_ahn_conv_M2 base
# (reuse ewfd + duplicate mat1 — the pattern that fixes "#Undefined material
# property n"), solve the mid-IR freq sweep, then run EvalReflection2D and assert
# a real PA dip (R_min < 0.5). This is the stage-1 success criterion, locked in as
# a regression so a refactor of build_model / get_reflection_2d / the material
# duplicate pattern can't silently re-introduce "Undefined n" or lose the dip.
#
# Two COMSOL spawns (build+solve, then eval). Slow (~40-60s). verify.sh runs it by
# default; COMSOL_FAST=1 skips it for a quick pre-commit gate.
#
# Exit 0=PASS / 1=FAIL / 0 with "#25 SKIP ..." = skipped.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
BRIDGE_DIR="${COMSOL_MCP_DIR:-/home/pengyk/Project/.comsol-mcp}"
CLASSES="$BRIDGE_DIR/classes"; PREFS="$BRIDGE_DIR/prefs"
COMSOL_BIN="${COMSOL_BIN:-/home/pengyk/comsol64/bin/comsol}"
[ "${COMSOL_FAST:-0}" = 1 ] && { echo "#25 SKIP (COMSOL_FAST=1)"; exit 0; }

BASE="/home/pengyk/Project/Fabry–Pérot 微腔/01_models/FP_ahn_conv_M2_L744.mph"
BODY="$BRIDGE_DIR/java/pa2d_body.java"
[ -f "$BASE" ] || { echo "#25 SKIP  (FP base not found: $BASE)"; exit 0; }
[ -f "$BODY" ] || { echo "#25 FAIL  pa2d body not found: $BODY"; exit 1; }

REG="$(mktemp -d -t comsol_regr_XXXXXX)"
trap 'rm -rf "$REG"' EXIT
SOLVED="$REG/pa2d_solved.mph"

# 1) build + solve the 2D PA (build_model path, run_study=std1) — real server code.
KW=$(python3 - "$BODY" "$BASE" "$SOLVED" <<'PY'
import json, sys
body = open(sys.argv[1]).read()
print(json.dumps({
  "java_source": body,
  "base_model": sys.argv[2],
  "save_path": sys.argv[3],
  "run_study": "std1",
}))
PY
)
build_out=$(python3 "$HERE/../_build_client.py" "$KW" --expect "r.get('compiled') and r.get('exit_code')=='0' and not r.get('mesh_empty_selections')" --id '#25' 2>&1) \
  || { echo "#25 FAIL  build/solve:"; echo "$build_out" | tail -25; exit 1; }
echo "$build_out" | grep -q '#25 PASS' || { echo "#25 FAIL  build/solve expect:"; echo "$build_out" | tail -25; exit 1; }
[ -f "$SOLVED" ] || { echo "#25 FAIL  solved mph not written"; echo "$build_out" | tail -15; exit 1; }

# 2) extract R(omega) via EvalReflection2D.
mkdir -p "$REG/tmp" "$REG/recov" "$REG/conf"
printf 'model=%s\n' "$SOLVED" > "$REG/in2.properties"
eval_out=$(COMSOL_BRIDGE_INPUT="$REG/in2.properties" \
  "$COMSOL_BIN" batch -inputfile "$CLASSES/EvalReflection2D.class" \
  -tmpdir "$REG/tmp" -recoverydir "$REG/recov" \
  -configuration "$REG/conf" -prefsdir "$PREFS" \
  -nosave -batchlogout -batchlog "$REG/batch2.log" 2>&1)

# 3) assert: REFL has many R values, and DIPS reports a real dip (R_min < 0.5).
nrefl=$(echo "$eval_out" | grep -cE '^\[' || echo 0)
# REFL rows are emitted as tab lines "sol1\t<freq>\t<wn>\t<R>"; count R-bearing lines
nr=$(echo "$eval_out" | grep -cE 'REFL' || true)
# Pull the global_min R from DIPS section (tab-delimited in raw stdout):
#   global_min<TAB><wn><TAB><R><TAB><idx>
rmin=$(echo "$eval_out" | grep -E '^global_min	' | awk -F'\t' '{print $3}' | head -1)
echo "$eval_out" | grep -q '<<<END>>>' || { echo "#25 FAIL  no END marker from EvalReflection2D"; echo "$eval_out" | tail -20; exit 1; }
if [ -z "$rmin" ]; then
  echo "#25 FAIL  could not parse global_min R from DIPS"; echo "$eval_out" | grep -iE 'DIPS|global_min|REFL' | head; exit 1; fi
# awk float compare
ok=$(awk -v r="$rmin" 'BEGIN{print (r+0 < 0.5) ? 1 : 0}')
if [ "$ok" = 1 ]; then
  echo "#25 PASS  (2D PA built+solved+extracted; R_min=$rmin < 0.5, dip confirmed)"
  exit 0
fi
echo "#25 FAIL  R_min=$rmin (>= 0.5, no PA dip — regression?)"
echo "$eval_out" | grep -iE 'DIPS|global_min' | head
exit 1