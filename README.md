# COMSOL MCP Bridge

Connects Claude Code to COMSOL Multiphysics 6.2 on this machine via an MCP server.

## How it works

`server.py` is a stdio MCP server (FastMCP). Each tool call spawns one
`comsol batch` run that executes a precompiled Java helper class
(`classes/Inspect.class`, `RunStudy.class`, `GetResult.class`). COMSOL's own
launcher handles native libs, the OSGi runtime, and license checkout — so we
never run a plain `java -cp` (that fails because COMSOL's API needs the Eclipse
OSGi runtime that only the equinox launcher starts).

Helpers read their input from a properties file whose path is passed in the
`COMSOL_BRIDGE_INPUT` env var, and emit `<<<SECTION>>>` markers on stdout that
`server.py` parses into structured results.

## Security

COMSOL's `security.external.filepermission=limited` (the default) blocks Java
code from reading `.mph` files, which would make `ModelUtil.load()` fail. The
bridge uses a **separate prefs dir** (`prefs/comsol.prefs`) with
`security.external.filepermission=full` (plus runtime/property permission so
`System.getenv` works), passed via `comsol batch -prefsdir`. Your normal COMSOL
Desktop prefs (`~/Library/Preferences/COMSOL/v62/`) are untouched and stay
`limited`.

## Tools

Core helpers (general-purpose):
- `inspect_model(model_path)` — parameters, components/physics/materials,
  studies/features, datasets, numerical eval nodes. Also reports
  `file_info` (mtime / size / has_sol) so you can spot stale dirty .mph
  files. Returns `params_units` so `[um]` / `[Hz]` are surfaced.
- `run_study(model_path, study="", params={}, output_path="")` — set params,
  run a study, optionally save the solved `.mph`. Returns
  `solve_ms` / `dofs` / `exit_code` so you can see what actually happened.
- `get_result(model_path, exprs, units=[])` — evaluate global expressions on a
  solved model; returns a table with `<expr>_real` / `<expr>_imag` columns.
- `get_volume(model_path, exprs, domains, units=[])` — volume-integrate
  expressions over given domains, across all sweep points.
- `eval_aggregate(...)` — sweep-aware max/min/avg/integral on domains or
  boundaries; correctly resolves parametric sweeps into one curve per
  distinct swept value.
- `get_reflection(model_path, ...)` — for 1D out-of-plane ewfd models: R(ω)
  and integrated |E|² over Si / Liquid / CaF₂ / all.
- `dump_model(model_path)` — detailed structural dump (per-component
  geometry / physics / materials / mesh + study sweeps). Use this to learn
  an existing model's exact setup before reproducing.
- `probe_feature(feature_type, properties, kind="geom", dim=2)` — discover
  valid property names for a COMSOL geometry feature or physics interface.
- `physics_feature(model_path, physics, action, ...)` — raw pass-through to
  set any feature property (e.g. port1.E0i) without wrapper knowing the
  name in advance.
- `set_param(model_path, params, output="")` — light-weight param edit +
  save (no re-solve). Use to prepare a sweep or tweak geometry before
  meshing.
- `build_model(java_source, save_path, base_model="", run_study="")` — build
  or modify a model from Java. `java_source` is the body of `run()`,
  operating on a pre-existing variable `model` (loaded from `base_model` if
  given, else a fresh empty model). Add geometry/materials/physics/BCs/
  mesh/studies via the COMSOL Java API. Same paradigm as COMSOL's 'File >
  Export Model to Java'. Body is wrapped in an automatic tryStmt that
  surfaces swallowed Throwables into the `java_warnings` response field;
  javac errors are returned as structured `compile_diagnostics` with
  per-line snippets.

Optics-flavoured helpers (added 2026-07-10):
- `material_info(model_path, material="")` — enumerate a model's materials
  and their **propertyGroup identifiers** (the string you pass to
  `propertyGroup(...)` and `propertyGroup(identifier).set(...)`). Solves
  the common footgun where a freshly-created `RefractiveIndex` group has
  identifier `"rg1"` instead of the canonical `"rfi"`, leading to a silent
  "Undefined n" later. For each material returns `(groupname, identifier,
  properties)` pairs.
- `param_sweep(model_path, study, param_name, values, output_path="")` —
  one-shot parametric sweep over a study. Internally creates a
  `Parametric` feature with the given name + values, runs the study
  (COMSOL sweeps all outer values internally), and returns per-value
  wall-time estimates + a saved swept model. Avoids the cryptic
  "Parameter names not consistent with number of parameter lists" error
  from hand-rolling Parametric features.

## Participatory modeling workflow

The point is the user stays in the loop, not hands-off automation:

1. User says what to model (physics, geometry, goals).
2. Claude probes the relevant physics/geometry API with `probe_feature`.
3. Claude drafts the `build_model` Java, explaining each step (geometry /
   material / physics / BCs / mesh / study).
4. User reviews, edits parameters/BCs, approves.
5. Claude runs `build_model` → `.mph`, then `run_study` + `get_result`.
6. User interprets results, requests changes, iterate.

## Files

- `java/*.java` — helper sources. `BridgeUtil.java` is shared by all.
- `classes/*.class` — compiled helpers (produced by `build.sh`).
- `prefs/comsol.prefs` — bridge-only relaxed security prefs. **Not in git**;
  regenerated on first run (see "First-time setup" below).
- `server.py` — the MCP server.
- `.venv/` — Python 3.12 venv with the `mcp` SDK.

## First-time setup (after `git clone`)

1. Recompile helpers: `bash build.sh` (writes `classes/*.class`).
2. Create the bridge prefs dir with relaxed security:
   ```bash
   mkdir -p prefs
   cat > prefs/comsol.prefs <<'EOF'
   security.external.filepermission=full
   security.apicore.filepermission=full
   security.apicore.propertypermission=full
   security.apicore.runtimepermission=full
   EOF
   ```
   (Without this, COMSOL blocks Java from reading `.mph` files and
   `ModelUtil.load()` fails.)
3. Create the venv and install the `mcp` SDK: `python3.12 -m venv .venv && .venv/bin/pip install mcp`.
4. Register with Claude Code (see below).

## Rebuild after editing Java

    bash .comsol-mcp/build.sh

## Register with Claude Code

The project `.mcp.json` already points at this server. Restart Claude Code (or
reconnect MCP servers) so it picks up `.mcp.json`; approve the `comsol` server
when prompted. Then the three tools are available as MCP tools.

## Notes

- COMSOL 6.2 paths are hardcoded in `server.py` (`COMSOL_BIN`) and `build.sh`
  for this install (`/Applications/COMSOL62`). Override via `COMSOL_BIN` /
  `COMSOL_MCP_DIR` env vars if the install moves.
- Each call boots a fresh COMSOL JVM (~5-10s for inspect/get_result; longer for
  run_study which actually solves). This is robust but not latency-optimized.