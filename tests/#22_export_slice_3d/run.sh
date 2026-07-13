#!/bin/bash
# #22 — export_slice_3d / ExportCutPlane helper (3D Slice plot, batch -3drend sw).
# Regression for journal #22: Slice plot feature sidesteps batch's result-context
# geometry block (no cut-plane dataset, no plot-feature selection) and renders a
# colored 3D cut plane. A wireframe-only render (quickznumber not set) is ~22KB;
# a real colored slice is >50KB — assert that.
#
# Needs a SOLVED 3D .mph with dset1. Set COMSOL_3D_MODEL to its path. If unset,
# SKIP (no small 3D test model lives in-repo yet; the real proof is the Proscia
# mim_v38.mph repro — visionpro-verified, see manual.md).
#
# Exit 0=PASS / 1=FAIL / 0 with "#22 SKIP ..." = skipped.
set -u
COMSOL_BIN="${COMSOL_BIN:-/home/pengyk/comsol64/bin/comsol}"
BRIDGE_DIR="${COMSOL_MCP_DIR:-/home/pengyk/Project/.comsol-mcp}"
CLASSES="$BRIDGE_DIR/classes"; PREFS="$BRIDGE_DIR/prefs"

MODEL="${COMSOL_3D_MODEL:-}"
if [ -z "$MODEL" ] || [ ! -f "$MODEL" ]; then
  echo "#22 SKIP  (set COMSOL_3D_MODEL=<solved 3D .mph with dset1> to run)"
  exit 0
fi

REG="$(mktemp -d -t comsol_regr_XXXXXX)"
trap 'rm -rf "$REG"' EXIT
PNG="$REG/slice.png"
cat > "$REG/input.properties" <<EOF
model=$MODEL
png=$PNG
plotype=slice
expr=ewfd.normE
descr=Electric field norm
title=#22 regression slice
solnum=1
logscale=on
quickplane=xy
quickz=0
quickn=1
view=iso
width=800
height=600
EOF

# NOTE: -3drend sw is mandatory for image export in headless batch (ogl crashes).
COMSOL_BRIDGE_INPUT="$REG/input.properties" \
  "$COMSOL_BIN" batch -3drend sw \
  -inputfile "$CLASSES/ExportCutPlane.class" \
  -tmpdir "$REG/tmp" -recoverydir "$REG/recov" \
  -configuration "$REG/conf" -prefsdir "$PREFS" \
  -nosave -batchlogout -batchlog "$REG/batch.log" > "$REG/out.txt" 2>&1 \
  || { echo "#22 FAIL  comsol batch nonzero exit"; sed -n '1,20p' "$REG/out.txt"; exit 1; }

grep -q '<<<END>>>' "$REG/out.txt" || { echo "#22 FAIL  no END marker"; tail -15 "$REG/out.txt"; exit 1; }
grep -q 'pngExists[[:space:]]*true' "$REG/out.txt" || { echo "#22 FAIL  png not written"; tail -15 "$REG/out.txt"; exit 1; }
sz=$(grep -oP 'pngSize\t\K[0-9]+' "$REG/out.txt" | tail -1)
# colored slice > 50KB; wireframe-only (bug: quickznumber unset) ~20-25KB.
if [ -z "$sz" ] || [ "$sz" -lt 50000 ]; then
  echo "#22 FAIL  pngSize=$sz (<50000 => wireframe/not colored; check quickznumber)"; exit 1
fi
echo "#22 PASS  pngSize=$sz (colored 3D slice rendered)"
exit 0