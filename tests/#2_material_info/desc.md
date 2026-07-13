#2 — material_info exposes propertyGroup identifier

- **Journal:** #2 (KEPT 2026-07-10)
- **Type:** A — fixed helper (MaterialInfo.class), comsol-batch direct, unattended
- **What it guards:** the `material_info` tool must report each propertyGroup's real identifier. The trap it exposes: `propertyGroup().create("rg1","RefractiveIndex")` sets identifier=`rg1` (your tag), not the reference material's `rfi` — ewfd then can't find `n` → "Undefined n" at solve time, with no upstream error. If a refactor stops reporting identifier, users hit the silent "Undefined n" again.
- **Model:** FP_ahn_conv_M0.mph (mat1 = Au, has RefractiveIndex group with identifier rfi)
- **Assertion:** stdout contains `groupname<TAB>RefractiveIndex<TAB>rfi` and `<<<END>>>`.