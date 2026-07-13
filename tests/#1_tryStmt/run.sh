#!/bin/bash
# #1 — tryStmt: build_model doesn't swallow Throwable (KEPT 2026-07-10)
# Type B→A via _build_client.py (calls real server.build_model through mcp stub).
# Repro: build_model with a body that references comp1 on a FRESH (empty) model —
# throws "Unknown model. - Tag: comp1". The template's tryStmt wrapper must
# surface it (java_warnings / error), NOT swallow it into a silent success.
# This is the exact verified case from memory (opt #1). Slow (~10s, comsol batch).
HERE="$(cd "$(dirname "$0")/.." && pwd)"
[ "${COMSOL_FAST:-0}" = 1 ] && { echo "#1 SKIP (COMSOL_FAST=1)"; exit 0; }
out="$(python3 "$HERE/_build_client.py" \
  '{"java_source": "model.component(\"comp1\").geom(\"geom1\").create(\"blk1\",\"Block\");", "save_path": "/tmp/_b1.mph"}' \
  --expect "bool(r.get('java_warnings') or r.get('error')) and not r.get('saved')" \
  --id "#1" 2>&1)"
echo "$out"
echo "$out" | grep -q '^#1 PASS' && exit 0
exit 1