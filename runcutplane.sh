#!/bin/bash
# Run ExportCutPlane.class via comsol batch -3drend sw with a properties file.
# Usage: runcutplane.sh <props.properties>
set -e
COMSOL="${COMSOL_ROOT:-/home/pengyk/comsol64}"
HERE="$(cd "$(dirname "$0")" && pwd)"
CLASS="$HERE/classes/ExportCutPlane.class"
JOB=$(date +%s)_$RANDOM
RDIR="$HERE/runtimes/cp_$JOB"
TMP="$RDIR/tmp"; RECOV="$RDIR/recov"; CONF="$RDIR/conf"
mkdir -p "$TMP" "$RECOV" "$CONF"
export COMSOL_BRIDGE_INPUT="$1"
"$COMSOL/bin/comsol" batch -3drend sw \
  -inputfile "$CLASS" -tmpdir "$TMP" -recoverydir "$RECOV" \
  -configuration "$CONF" -prefsdir "$HERE/prefs" -nosave \
  -batchlogout -batchlog "$RDIR/batch.log" 2>&1 | grep -v "^--------\|^Memory:\|Matrix factor\|Current Progress\|^Iter \|^---\|Stationary Solver\|Solving linear\|Updating plots\|Physical memory\|Virtual memory\|^Ended at\|^Solution time\|comsolbatch\|^\*\*\*" | head -60
echo "[run done $JOB]"