#!/bin/bash
# Quick standalone DebugRun/DumpVars launcher (mirrors server.run_helper).
# Usage: ./dbgrun.sh <HelperClass> key=val key=val ...
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
COMSOL="${COMSOL_BIN:-/home/pengyk/comsol64/bin/comsol}"
CLASSES="$HERE/classes"
PREFS="$HERE/prefs"
JOB="$(uuidgen 2>/dev/null | tr -d '-' | cut -c1-12 || echo dbg$RANDOM)"
RDIR="$HERE/runtimes/$JOB"
TMP="$RDIR/tmp"; RECOV="$RDIR/recov"; CONF="$RDIR/conf"
IN="$RDIR/input.properties"
mkdir -p "$TMP" "$RECOV" "$CONF"
CLS="$1"; shift
for kv in "$@"; do echo "$kv" >> "$IN"; done
env COMSOL_BRIDGE_INPUT="$IN" "$COMSOL" batch \
  -inputfile "$CLASSES/$CLS.class" \
  -tmpdir "$TMP" -recoverydir "$RECOV" -configuration "$CONF" \
  -prefsdir "$PREFS" -nosave -batchlogout -batchlog "$RDIR/batch.log" 2>&1
rm -rf "$RDIR"