# comsol-optic-mcp

> MCP bridge connecting Claude Code to **COMSOL Multiphysics 6.x** via `comsol batch`.
> Designed for optics / photonics / plasmonics / IR spectroscopy simulations.

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Python 3.12+](https://img.shields.io/badge/python-3.12+-blue.svg)](https://www.python.org/)
[![COMSOL 6.x](https://img.shields.io/badge/COMSOL-6.x-orange.svg)](https://www.comsol.com/)
[![MCP](https://img.shields.io/badge/MCP-compatible-green.svg)](https://modelcontextprotocol.io/)

Expose your COMSOL model tree, parameter sets, study results, and physics
features as **MCP tools** that Claude can call directly. Iterate on
simulations in a conversational loop instead of clicking through the GUI.

## What you can do

```text
You:  "Inspect /home/pengyk/Project/微腔/01_models/FP_ahn.mph and tell me
       what physics + materials it uses, plus the parameter list."

Claude:  [calls mcp__comsol__inspect_model]
         → returns file_info (size/mtime/has_sol), params (with units
           auto-detected), components, physics, materials, studies, datasets.

You:  "What's the identifier of the RefractiveIndex group on mat1?"

Claude:  [calls mcp__comsol__material_info(material="mat1")]
         → {"groupname": "RefractiveIndex", "identifier": "rfi", ...}
           (so you can write `material("mat1").propertyGroup("rfi").set("n", "3.6")`
           in build_model and have it actually take effect)

You:  "Sweep tAu from 6nm to 30nm in 5 steps and save the swept model."

Claude:  [calls mcp__comsol__param_sweep(study="std1", param_name="tAu",
                                          values=["6[nm]","10[nm]","18[nm]",
                                                  "25[nm]","30[nm]"],
                                          output_path=".../sweep.mph")]
         → returns per-value wall-time, total solve time, saved path.
```

## Features

- **14 tools** covering inspect / run_study / build_model / extract results
  / probe features / set params / parametric sweep / reflection / structural
  dump.
- **Sweep-aware evaluation** — `eval_aggregate` correctly resolves
  `Parametric` sweeps into one curve per distinct swept value (the
  classic "two identical L curves" bug is fixed).
- **Optics-specific helpers**:
  - `material_info` — discover `propertyGroup` identifiers so your
    `RefractiveIndex` group actually links to the physics interface
    (solves the silent "Undefined n" / "rfi vs rg1" footgun).
  - `param_sweep` — one-call parametric sweep (avoids the cryptic
    "Parameter names not consistent with number of parameter lists"
    error from hand-rolling `Parametric` features).
- **Self-describing failures**:
  - `build_model` body is auto-wrapped in `tryStmt`; swallowed
    `Throwable`s surface in the `java_warnings` response field.
  - javac errors return structured `compile_diagnostics` with
    per-line `±5` source snippets and a `>>` pointer to the broken line.
  - `run_study` returns `solve_ms` / `dofs` / `exit_code` so you can see
    what actually happened.
- **File hygiene** — `inspect_model` reports `mtime` / `size_mb` /
  `has_sol` / per-study status, so you can spot stale 1GB dirty `.mph`
  files from abandoned runs.
- **Unit-aware** — params with `[um]`, `[Hz]`, `[1/cm]` are surfaced
  via `params_units` so you don't have to guess.

## Quick start (after `git clone`)

### 1. Install COMSOL prerequisites

You need a working COMSOL Multiphysics 6.x install on this machine.
The bridge spawns `comsol batch` as a subprocess; it needs the COMSOL
JRE (for `javac`) and the COMSOL API jars (in `plugins/` and
`apiplugins/`).

### 2. Compile helpers and set up the venv

```bash
cd .comsol-mcp
bash build.sh                  # javac → classes/*.class
python3.12 -m venv .venv
.venv/bin/pip install mcp
```

### 3. Create the bridge prefs (relaxed security)

The default COMSOL prefs deny Java from reading `.mph` files. The bridge
needs a **separate** prefs dir with `security.external.filepermission=full`:

```bash
mkdir -p prefs
cat > prefs/comsol.prefs <<'EOF'
security.external.filepermission=full
security.apicore.filepermission=full
security.apicore.propertypermission=full
security.apicore.runtimepermission=full
EOF
```

(Your normal COMSOL Desktop prefs are untouched — only the bridge uses
this folder via `comsol batch -prefsdir prefs/`.)

### 4. Register with Claude Code

In `.mcp.json` (project root or `~/.claude/`):

```json
{
  "mcpServers": {
    "comsol": {
      "command": "/home/pengyk/Project/.comsol-mcp/.venv/bin/python",
      "args": ["/home/pengyk/Project/.comsol-mcp/server.py"],
      "env": {
        "COMSOL_BIN": "/home/pengyk/comsol64/bin/comsol",
        "COMSOL_ROOT": "/home/pengyk/comsol64"
      }
    }
  }
}
```

Restart Claude Code (or `/mcp` to reconnect). Approve the `comsol`
server when prompted. The 14 `mcp__comsol__*` tools become available.

### 5. Try it

Ask Claude:
> "Inspect `/home/pengyk/Project/微腔/01_models/FP_ahn.mph` and report
> its parameters, materials, and file status."

## Tools

### Core (general-purpose)

| Tool | Purpose | Latency |
|---|---|---|
| `inspect_model(path)` | Parameters, components, physics, materials, studies, datasets, **file_info**, **params_units** | ~5-10s |
| `run_study(path, study, params, output_path)` | Set params + run study + save solved model; returns **solve_ms / dofs / exit_code** | seconds-minutes |
| `get_result(path, exprs, units)` | Evaluate global expressions on solved model; `<expr>_real/_imag` columns | ~5-10s |
| `get_volume(path, exprs, domains)` | Volume-integrate expressions over given domains, per sweep point | ~8s |
| `eval_aggregate(path, expr, domains, aggregate)` | Sweep-aware max/min/avg/integral; one curve per distinct swept value | ~10-15s |
| `get_reflection(path, ...)` | 1D out-of-plane ewfd: R(ω) + ∫\|E\|² over Si/Liquid/CaF₂/all | ~5-10s |
| `dump_model(path)` | Per-component geometry/physics/materials/mesh + study sweeps | ~6-10s |
| `probe_feature(type, props, kind, dim)` | Discover valid property names for a geometry feature or physics interface | ~6s |
| `physics_feature(path, physics, action, ...)` | Raw pass-through to set any feature property | ~8s |
| `set_param(path, params, output)` | Light-weight param edit + save (no re-solve) | ~8s |
| `build_model(java_source, save_path, base_model, run_study)` | Build/modify a model from Java; **java_warnings** + **compile_diagnostics** | seconds-minutes |

### Optics-flavoured

| Tool | Solves |
|---|---|
| `material_info(path, material="")` | Discover propertyGroup identifiers (solves the "rg1 vs rfi" footgun). Returns `(groupname, identifier, properties)` for each material. |
| `param_sweep(path, study, param_name, values, output_path)` | One-shot Parametric sweep; returns per-value wall-time + saved swept model. Avoids the cryptic "Parameter names not consistent" error. |

## Architecture

```
┌─────────────────┐  stdio   ┌──────────────────┐
│  Claude Code    │ ◄──────► │  server.py       │
│  (MCP client)   │          │  (FastMCP)       │
└─────────────────┘          └────────┬─────────┘
                                      │ spawn per call
                                      ▼
                            ┌──────────────────┐
                            │  comsol batch    │
                            │  -inputfile      │
                            │  X.class         │
                            └────────┬─────────┘
                                     │ loads
                                     ▼
                  ┌────────────────────────────────┐
                  │ java/X.java → X.class          │
                  │ - reads  env COMSOL_BRIDGE_INPUT│
                  │ - writes <<<SECTION>>> markers  │
                  │ - BridgeUtil.section/end/fail   │
                  └────────────────────────────────┘
```

Each tool call:

1. `server.py` writes a properties file with the tool's args to `runtimes/<job>/input.properties`.
2. Spawns `comsol batch -inputfile classes/X.class -prefsdir prefs/ -tmpdir runtimes/<job>/tmp ...`.
3. The Java helper loads the `.mph` (or creates a new one), runs the operation, prints `<<<SECTION>>>` markers.
4. `server.py` parses the sectioned stdout into a structured `dict` and returns it to Claude.

Each call boots a fresh COMSOL JVM (~5-10s overhead per call). This is
robust but not latency-optimized.

## How it works (the security model)

COMSOL's default `security.external.filepermission=limited` blocks Java
from reading `.mph` files, which would make `ModelUtil.load()` fail. The
bridge uses a **separate** prefs dir (`prefs/comsol.prefs`) with
`filepermission=full` (plus `runtime/property` permission so
`System.getenv` works), passed via `comsol batch -prefsdir`. Your
normal COMSOL Desktop prefs (`~/Library/Preferences/COMSOL/v62/`) are
untouched and stay `limited`.

## Development

### Editing a Java helper

```bash
# 1. edit java/YourHelper.java
# 2. recompile
bash build.sh
# 3. test
.venv/bin/python -c "import server; print(server.run_helper('YourHelper', {...}))"
# 4. restart MCP (in Claude Code: /mcp)
```

### Adding a new tool

1. Write `java/YourTool.java` extending the `BridgeUtil` / section-marker
   pattern (see `java/Inspect.java` or `java/EvalVolume.java` for the
   minimal template).
2. Add `"$HERE/java/YourTool.java"` to the `javac` line in `build.sh`.
3. Add a `@mcp.tool()` wrapper to `server.py` that calls
   `run_helper("YourTool", {...})` and parses the sectioned output.
4. Rebuild and `/mcp` to reload.

### Repository layout

```
.comsol-mcp/
├── LICENSE                  MIT
├── README.md                this file
├── build.sh                 javac helper compilation
├── .gitignore               excludes .class, .mph, runtimes/, .venv, prefs/
├── server.py                FastMCP stdio server (14 tools)
├── java/                    17 helper sources
│   ├── BridgeUtil.java      shared I/O + tryStmt + clearSelection
│   ├── Inspect.java         model + file_info + params_units
│   ├── RunStudy.java        set params + run + solve_ms/dofs/exit_code
│   ├── GetResult.java       global expressions
│   ├── EvalVolume.java      volume/surface integration
│   ├── EvalAggregate.java   sweep-aware aggregate
│   ├── EvalReflection.java  R(ω) + |E|² over regions
│   ├── MaterialInfo.java    propertyGroup identifier discovery
│   ├── ParamSweep.java      one-call Parametric sweep
│   ├── Probe.java           property name probe
│   ├── ProbeFields.java
│   ├── PhysicsFeature.java  raw property/selection pass-through
│   ├── ProbeSweep.java      legacy, do not use
│   ├── SetParam.java        param edit + save (no re-solve)
│   ├── Dump.java            full structural dump
│   ├── ExportJava.java
│   └── plain_fp_body.java   saved build_model body template
├── classes/                 compiled .class (gitignored, rebuilt)
├── prefs/                   bridge-specific relaxed-security prefs (gitignored)
├── runtimes/                per-call tmp dirs (gitignored)
└── .venv/                   Python 3.12 venv (gitignored)
```

## Limits

- **Parametric Sweep on complex models** — `param_sweep` works on simple
  models. On a complex `ewfd` model (e.g. the Fabry-Pérot cavity with
  port BCs), COMSOL's solver needs extra setup that the helper doesn't
  yet provide; in that case it returns
  `Invalid_property_value`. Fallback: call `set_param` + `run_study` N
  times yourself, or call `build_model` to inject the extra solver
  config.
- **`study_status` heuristic** — `inspect_model`'s per-study status
  field is currently inferred from the existence of a `solX` dataset
  tag, which can mismatch. A more accurate probe would query
  `model.hasSolution()` / `model.sol().isEmpty()` (planned).
- **dofs is `"?"`** — the API to read dofs is unstable across COMSOL
  versions. `solve_ms` is the more reliable timing signal.
- **No JVM caching** — every call boots a fresh JVM (~5-10s). A
  long-running REPL-style server (one JVM, multiple requests) would
  eliminate this overhead; planned for v2.

## Roadmap

- [ ] Parametric Sweep solver-config auto-setup (cross-link with Frequency node)
- [ ] Accurate `study_status` via `model.hasSolution()`
- [ ] Real dofs readout via `model.sol().dofInfo()`
- [ ] Long-running REPL server (eliminate per-call JVM startup)
- [ ] Material templates: `create_ri_material(name, n_expr, k_expr)` shortcut
- [ ] Frequency / wavenumber range string parsing
  (`run_study(params={"wn_range": "1800-2600 cm⁻¹ @ 4 cm⁻¹"})`)

## Citation

If you use this in academic work, please cite the underlying COMSOL
Multiphysics and the MCP protocol. The bridge itself is a thin wrapper
and probably doesn't need a separate citation, but if you do reference
it:

```bibtex
@software{comsol_optic_mcp_2026,
  title  = {comsol-optic-mcp: MCP bridge for COMSOL Multiphysics},
  author = {Hela},
  year   = {2026},
  url    = {https://github.com/helalove0812-source/comsol-optic-mcp}
}
```

## License

MIT — see [LICENSE](LICENSE). Your normal COMSOL license governs your
use of COMSOL itself; this bridge is just a transport.

## Acknowledgements

Built for use with Claude Code (Anthropic). The MCP protocol is
[modelcontextprotocol.io](https://modelcontextprotocol.io/).
