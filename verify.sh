#!/bin/bash
# verify.sh — comsol-mcp regression gate.
# Runs every tests/#N/run.sh (Type A, comsol-batch-direct). Each run.sh exits
# 0=PASS / 1=FAIL / 0 with "#N SKIP" for skipped. Type B tests (build_model
# path) have only a manual.md — listed as MANUAL, not run.
#
# Usage:
#   ./verify.sh              # full (incl. slow solve tests)
#   COMSOL_FAST=1 ./verify.sh  # quick gate, skips slow solve tests
#
# Exit 0 only if all run tests PASS. Manual tests are reported but don't fail
# the gate (they're tracked separately; the goal is to migrate B→A over time).

set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"

pass=0; fail=0; skip=0; manual=()
failed=()

for d in tests/*/; do
  n="$(basename "$d")"
  if [ -x "$d/run.sh" ]; then
    log="$d/stdout.log"
    if "$d/run.sh" > "$log" 2>&1; then
      line="$(tail -1 "$log")"
      case "$line" in
        *SKIP*) echo "SKIP  $n  ($line)"; skip=$((skip+1));;
        *) echo "PASS  $n  ($line)"; pass=$((pass+1));;
      esac
    else
      echo "FAIL  $n  (see $d/stdout.log)"; fail=$((fail+1)); failed+=("$n")
    fi
  elif [ -f "$d/manual.md" ]; then
    manual+=("$n")
  fi
done

echo "────────────────────────────────────────"
echo "PASS=$pass  FAIL=$fail  SKIP=$skip  MANUAL=${#manual[@]}"
[ ${#manual[@]} -gt 0 ] && echo "MANUAL (not automated, build_model path): ${manual[*]}"
[ ${#failed[@]} -gt 0 ] && echo "FAILED: ${failed[*]}"
echo
echo "Rule: a change to server.py / java/*.java must keep FAIL=0 before tagging KEPT."
echo "Run COMSOL_FAST=1 ./verify.sh for a quick pre-commit gate (skips slow solves)."

[ $fail -eq 0 ]