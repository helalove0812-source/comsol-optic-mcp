#!/bin/bash
# tests/_lib.sh — shared comsol-batch runner for regression tests.
# Source from a test's run.sh:  source "$(dirname "$0")/../_lib.sh"
# Then:  out=$(run_helper <ClassName> "prop1=val1" "prop2=val2" ...)
# Prints helper stdout to stdout. Caller greps it for assertions.
#
# Mirrors server.py::run_helper but without the `mcp` package dependency,
# so tests run with plain bash + comsol batch — no Claude, no MCP server.
# That's the point: regression tests must run unattended (cron, pre-commit).

COMSOL_BIN="${COMSOL_BIN:-/home/pengyk/comsol64/bin/comsol}"
BRIDGE_DIR="${COMSOL_MCP_DIR:-/home/pengyk/Project/.comsol-mcp}"
CLASSES="$BRIDGE_DIR/classes"
PREFS="$BRIDGE_DIR/prefs"

# Per-test runtime dir (mktemp -d => unique even if tests run in parallel)
REGDIR="$(mktemp -d -t comsol_regr_XXXXXX)"
export REGDIR

run_helper() {
  local class="$1"; shift
  mkdir -p "$REGDIR/tmp" "$REGDIR/recov" "$REGDIR/conf"
  printf '%s\n' "$@" > "$REGDIR/input.properties"
  COMSOL_BRIDGE_INPUT="$REGDIR/input.properties" \
    "$COMSOL_BIN" batch \
    -inputfile "$CLASSES/$class.class" \
    -tmpdir "$REGDIR/tmp" -recoverydir "$REGDIR/recov" \
    -configuration "$REGDIR/conf" -prefsdir "$PREFS" \
    -nosave -batchlogout -batchlog "$REGDIR/batch.log" 2>&1
}
trap 'rm -rf "$REGDIR"' EXIT