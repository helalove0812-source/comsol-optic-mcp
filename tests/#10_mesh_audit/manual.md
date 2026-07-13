#10 — build_model mesh audit (mesh_empty_selections / n=0 detection)

- **Journal:** #10 (KEPT 2026-07-11)
- **Type:** B — build_model path; NOT yet automated (see "blocker" below)
- **What it guards:** `build_model`'s injected `auditMesh` must surface a mesh
  feature whose selection resolved to zero entities (`mesh_empty_selections`),
  so a silently-empty mesh selection (no refinement, no solve-time error) is
  caught at build time instead of producing a wrong field silently.
- **Manual repro (via Claude/MCP or _build_client.py):**
  1. `build_model` a body with a mesh feature whose selection is bad.
  2. Expect response `mesh_audit` lists that feature with `n=0`, and
     `mesh_empty_selections` includes it.
  3. Positive control: valid selection → `n>0`, empty `mesh_empty_selections`.
- **Blocker to automating (2026-07-13 finding, COMSOL 6.4):** the "obvious"
  repro — `.selection().geom("geom1",2).set(new int[]{999})` on a nonexistent
  domain — does NOT silently empty; it THROWS `Illegal input vector, illegal
  entity number detected` (surfaced via tryStmt, visible, not the silent bug).
  And `.set(int[])` WITHOUT `.geom(dim)` throws "No entity dimension specified"
  (also visible). So the genuinely-silent n=0 case is narrower than the docs
  implied: it requires a feature whose selection object exists but resolves to
  empty WITHOUT throwing (e.g. a stale selection after geometry rebuild, or a
  wrong-dim bind). Nailing that exact scenario synthetically needs more COMSOL
  selection-API exploration than was warranted here. `auditMesh` itself is
  verified (valid features report n>0 / -1); only the n=0 trigger case is open.
- **To automate later:** find the silent-empty scenario (try a selection set
  then `geom.run()` rebuild that orphans it; or a `Map` feature with a
  domain-selection on a 1D geom where the dim bind is wrong). Then a `run.sh`
  via `_build_client.py` with `--expect "bool(r.get('mesh_empty_selections'))"`
  moves this to Type A.
- **principles A2 updated** with the 6.4 "nonexistent entity throws" nuance.