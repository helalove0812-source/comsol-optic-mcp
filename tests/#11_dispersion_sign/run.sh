#!/bin/bash
# #11 — add_dispersion_material sign convention (KEPT 2026-07-13)
# Repro: build a passive Lorentz material, assert the tool's SANITY self-check
# reports passive_check=PASS (Im(eps)<=0 under COMSOL e^{+iwt}) AND that the
# expression actually written to COMSOL carries the FIXED Lorentz sign
# (+ i*w*gamma), not the old gain sign (- i*w*gamma => R>1).
#
# Double guard (journal #11 lesson): the SANITY section is a Java
# reimplementation of the formula; to catch drift between that and the written
# expr, we ALSO string-check the real n_expr/ki_expr. Either reverting the
# formula OR the expr flips this FAIL.
source "$(dirname "$0")/../_lib.sh"
MPH="/home/pengyk/Project/Fabry–Pérot 微腔/01_models/FP_ahn_conv_M0.mph"
out="$(run_helper DispMaterial "model=$MPH" "comp=comp1" "tag=matT" \
  "kind=lorentz" "epsinf=5" \
  "lorentz=1[THz],12,0.05[THz];1.9[THz],8,0.1[THz]" "domains=2")"

ok=1
# 1) SANITY passive_check = PASS
echo "$out" | grep -q '^passive_check	PASS$' || { echo "#11 FAIL: passive_check not PASS"; ok=0; }
# 2) worst_Im_eps <= 0 (real value, not stale 0)
wim="$(echo "$out" | sed -n 's/^worst_Im_eps\t//p')"
[ -n "$wim" ] && awk -v v="$wim" 'BEGIN{exit !(v<=0)}' || { echo "#11 FAIL: worst_Im_eps missing or >0 ($wim)"; ok=0; }
# 3) actual written expr carries FIXED Lorentz sign (+ i*w*gamma), NOT the old - i
echo "$out" | grep -E '^(n_expr|ki_expr)	' | grep -q '+ i\*(2\*pi\*freq)' \
  || { echo "#11 FAIL: written expr missing fixed Lorentz sign '+ i*(2*pi*freq)'"; ok=0; }
echo "$out" | grep -E '^(n_expr|ki_expr)	' | grep -q -- '- i\*(2\*pi\*freq)' \
  && { echo "#11 FAIL: written expr has '- i*(2*pi*freq)' (old gain sign or Drude leaked into lorentz-only)"; ok=0; }
# 4) run completed
echo "$out" | grep -q '<<<END>>>' || { echo "#11 FAIL: no END marker"; ok=0; }

if [ $ok -eq 1 ]; then
  echo "#11 PASS (passive Lorentz: Im(eps)=$wim<=0, written expr sign +i fixed)"
  exit 0
fi
echo "--- SANITY block ---"
sed -n '/<<<SANITY>>>/,/<<<PARAMS>>>/p' <<<"$out"
exit 1