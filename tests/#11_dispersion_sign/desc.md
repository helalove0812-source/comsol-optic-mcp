#11 — add_dispersion_material sign convention (e^{+iwt} passive)

- **Journal:** #11 (KEPT 2026-07-13) — was the highest-priority PENDING
- **Type:** A — fixed helper (DispMaterial.class), comsol-batch direct, unattended
- **The bug it guards:** COMSOL uses e^{+iωt}; passive loss needs Im(ε)≤0. The old signs
  (Lorentz `-i·wγ`, Drude `+i·wγD`) gave Im(ε)>0 = gain → reflection R>1 (unphysical,
  only surfaced as impossible results). Fixed: Lorentz `+i·wγ`, Drude `-i·wγD`.
- **Tool hardening (not just a sign flip):** `DispMaterial` now emits a `<<<SANITY>>>`
  section sampling Re/Im(ε) at the resonances and asserting `passive_check` (Im≤0).
  A gain material now fails loud — the bridge carries a physical-reality assertion
  (journal #11 lesson: "符号约定错是最隐蔽的 bug，bridge 必须有物理合理性断言").
- **Model:** FP_ahn_conv_M0.mph (carrier; SANITY computed in Java, independent of model physics)
- **Assertions (double guard against formula↔expr drift):**
  1. `passive_check PASS`
  2. `worst_Im_eps ≤ 0` (real sampled max, not stale 0)
  3. written `n_expr`/`ki_expr` contains `+ i*(2*pi*freq)` (fixed Lorentz) and NOT `- i*(2*pi*freq)` (old gain sign / Drude leaked into lorentz-only)
- **To extend:** a Drude-only variant (#11b) and a true end-to-end R≤1 reflection test (build slab → sweep → get_reflection) would guard the written expr even if the Java SANITY formula drifts; tracked as future hardening.