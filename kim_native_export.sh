#!/bin/bash
# Native COMSOL image export for the Kim perovskite model (headless, NO OpenGL).
#
# Key: COMSOL batch defaults to -3drend ogl, whose render pipeline native-crashes
# at "Evaluating 26%" in headless batch mode (no GL context; xvfb/Mesa AND real
# nvidia GLX both crash identically — the GL backend is NOT the variable).
# -3drend sw forces the CPU software rasterizer, which needs no GL at all and
# produces a real PNG. This is the only working native-export path on this box.
#
# Usage:
#   ./kim_native_export.sh <mph> <out.png> <solnum> [expr] [width] [height] [imagetype] [exptype]
# Defaults: expr=ewfd.normE width=1200 height=800 imagetype=png exptype=Image2D
set -e
MPH="${1:?mph path}"; PNG="${2:?out png}"; SOLN="${3:?solnum}"
EXPR="${4:-ewfd.normE}"; W="${5:-1200}"; H="${6:-800}"; IT="${7:-png}"; ET="${8:-Image2D}"
cd /home/pengyk/Project/.comsol-mcp
COMSOL=/home/pengyk/comsol64/bin/comsol
PROPS=$(mktemp /tmp/kimexp.XXXXXX.props)
cat > "$PROPS" <<EOF
model=$MPH
png=$PNG
solnum=$SOLN
expr=$EXPR
data=dset1
width=$W
height=$H
imagetype=$IT
exptype=$ET
EOF
export COMSOL_BRIDGE_INPUT="$PROPS"
T=$(mktemp -d /tmp/comsol_t.XXXXX); R=$(mktemp -d /tmp/comsol_r.XXXXX); C=$(mktemp -d /tmp/comsol_c.XXXXX)
# DISPLAY optional with sw renderer; keep :2 in case COMSOL ever wants it
DISPLAY=:2 XAUTHORITY=/run/user/1001/gdm/Xauthority "$COMSOL" batch -3drend sw \
  -inputfile classes/KimExport.class \
  -tmpdir "$T" -recoverydir "$R" -configuration "$C" -prefsdir "$PWD/prefs" \
  -nosave -batchlogout 2>&1 | sed -n '/<<<PRE>>>/,/<<<END>>>/p'
rc=${PIPESTATUS[0]}
rm -rf "$T" "$R" "$C" "$PROPS"
echo "rc=$rc png=$PNG $(stat -c '%s bytes' "$PNG" 2>/dev/null || echo MISSING)"