#!/bin/bash
# #7 — compile_diagnostics: javac errors with line number + ±5 line snippet (KEPT 2026-07-10)
# Type B→A via _build_client.py (calls real server.build_model through mcp stub).
# Repro: build_model with a Java syntax error (9[um] is illegal in raw Java).
# javac fails BEFORE comsol batch runs => fast. Expect compiled=False +
# compile_diagnostics with a line number. Guards the line-number diagnostic
# (opt #7) so a missing `;` / typo is located, not buried in a wall of text.
HERE="$(cd "$(dirname "$0")/.." && pwd)"
out="$(python3 "$HERE/_build_client.py" \
  '{"java_source": "model.component(\"comp1\").geom(\"geom1\").create(\"blk1\",\"Block\").set(\"size_y\", 9[um]);", "save_path": "/tmp/_b7.mph"}' \
  --expect "not r.get('compiled', True) and 'compile_diagnostics' in r and r['compile_diagnostics'] and 'line' in r['compile_diagnostics'][0]" \
  --id "#7" 2>&1)"
echo "$out"
echo "$out" | grep -q '^#7 PASS' && exit 0
exit 1