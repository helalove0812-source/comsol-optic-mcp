#9 — param_sweep iterate mode (auto-detect on Frequency-sweep model)

- **Journal:** #9 (KEPT 2026-07-11)
- **Type:** A — fixed helper (ParamSweep.class), comsol-batch direct, unattended (slow ~20-30s)
- **What it guards:** `param_sweep(mode=auto)` must detect an existing Frequency sweep and pick `iterate` (loop param().set+study.run), NOT `parametric` (which stacks a Parametric feature on Frequency → `Invalid_property_value`). This bug bit twice (#3 first attempt, #9 refined run). If auto-detection regresses, users fall back to N×run_study again.
- **Model:** FP_ahn_conv_M0.mph (study std1 has freq Frequency feature, param tAu exists)
- **Assertion:** stdout RUNNING section contains `mode<TAB>iterate` and `<<<END>>>` (run completed, no Invalid_property_value).
- **Skip:** set `COMSOL_FAST=1` for a quick pre-commit gate (skips slow solve tests).