#!/usr/bin/env python3
"""COMSOL MCP server.

Exposes COMSOL Multiphysics operations as MCP tools by driving `comsol batch`
with precompiled Java helper classes. Each tool call spawns one batch run
(COMSOL loads its own native libs / OSGi runtime / license), so calls are
robust but take ~5-60s depending on whether a study is solved.

Requires the helper .class files in CLASSES_DIR (compiled from java/*.java)
and a bridge-specific prefs dir with security.external.filepermission=full
(see prefs/comsol.prefs). The user's normal COMSOL Desktop prefs are untouched.
"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import tempfile
import uuid
from dataclasses import dataclass, field

from mcp.server.fastmcp import FastMCP

# ---------------------------------------------------------------------------
# Configuration (this machine's COMSOL 6.2 install; override via env if needed)
# ---------------------------------------------------------------------------
BRIDGE_DIR = os.environ.get("COMSOL_MCP_DIR", "/home/pengyk/Project/.comsol-mcp")
COMSOL_BIN = os.environ.get("COMSOL_BIN", "/home/pengyk/comsol64/bin/comsol")
CLASSES_DIR = os.path.join(BRIDGE_DIR, "classes")
PREFS_DIR = os.environ.get("COMSOL_MCP_PREFS", os.path.join(BRIDGE_DIR, "prefs"))
RUNTIME_ROOT = os.environ.get("COMSOL_MCP_RUNTIMES", os.path.join(BRIDGE_DIR, "runtimes"))
TIMEOUT_S = int(os.environ.get("COMSOL_MCP_TIMEOUT", "900"))
COMSOL_ROOT = os.environ.get("COMSOL_ROOT", "/home/pengyk/comsol64")
JAVAC = os.path.join(COMSOL_ROOT, "java/glnxa64/jre/bin/javac")


def comsol_classpath() -> str:
    """All COMSOL jars (plugins + apiplugins) + the bridge classes dir."""
    import glob
    jars = glob.glob(os.path.join(COMSOL_ROOT, "plugins", "*.jar"))
    jars += glob.glob(os.path.join(COMSOL_ROOT, "apiplugins", "*.jar"))
    jars.append(CLASSES_DIR)
    return ":".join(jars)


mcp = FastMCP("comsol")


@dataclass
class SectionedOutput:
    sections: dict[str, list[str]] = field(default_factory=dict)
    error: str | None = None


def parse_sections(stdout: str) -> SectionedOutput:
    """Parse `<<<SECTION>>>` / `<<<END>>>` markers emitted by the Java helpers."""
    out = SectionedOutput()
    current: str | None = None
    buf: list[str] = []
    for line in stdout.splitlines():
        m = re.fullmatch(r"<<<([A-Z_]+)>>>", line.strip())
        if m:
            name = m.group(1)
            if current is not None:
                out.sections[current] = buf
            if name == "END":
                break
            current = name
            buf = []
        elif current is not None:
            buf.append(line)
    if current is not None and current not in out.sections:
        out.sections[current] = buf
    if "ERROR" in out.sections:
        out.error = "\n".join(out.sections["ERROR"]).strip()
    return out


def run_helper(helper_name: str, props: dict[str, str]) -> tuple[SectionedOutput, str]:
    """Run a compiled helper class via `comsol batch` with the given input props.

    Returns (parsed sections, raw stdout). Raises RuntimeError on hard failure.
    """
    if not shutil.which(COMSOL_BIN) and not os.path.exists(COMSOL_BIN):
        raise RuntimeError(f"comsol binary not found: {COMSOL_BIN}")

    class_file = os.path.join(CLASSES_DIR, helper_name + ".class")
    if not os.path.exists(class_file):
        raise RuntimeError(f"helper class not found: {class_file} (recompile java sources)")

    job = uuid.uuid4().hex[:12]
    rdir = os.path.join(RUNTIME_ROOT, job)
    tmpdir = os.path.join(rdir, "tmp")
    recov = os.path.join(rdir, "recov")
    conf = os.path.join(rdir, "conf")
    for d in (tmpdir, recov, conf):
        os.makedirs(d, exist_ok=True)
    input_file = os.path.join(rdir, "input.properties")

    # write input properties (UTF-8; COMSOL Properties.load reads UTF-8)
    with open(input_file, "w", encoding="utf-8") as f:
        for k, v in props.items():
            f.write(f"{k}={v}\n")

    env = dict(os.environ)
    env["COMSOL_BRIDGE_INPUT"] = input_file

    cmd = [
        COMSOL_BIN, "batch",
        "-inputfile", class_file,
        "-tmpdir", tmpdir,
        "-recoverydir", recov,
        "-configuration", conf,
        "-prefsdir", PREFS_DIR,
        "-nosave",
        "-batchlogout",
        "-batchlog", os.path.join(rdir, "batch.log"),
    ]
    try:
        proc = subprocess.run(
            cmd, env=env, capture_output=True, text=True,
            timeout=TIMEOUT_S,
        )
    except subprocess.TimeoutExpired:
        raise RuntimeError(f"comsol batch timed out after {TIMEOUT_S}s")
    finally:
        # best-effort cleanup of the per-job runtime dir (keep batch.log on failure)
        shutil.rmtree(rdir, ignore_errors=True)
        # comsolbatch writes <inputfile>.status/.recovery next to the .class; remove
        for ext in (".status", ".recovery"):
            p = class_file + ext
            if os.path.exists(p):
                try: os.remove(p)
                except OSError: pass

    stdout = proc.stdout
    parsed = parse_sections(stdout)
    # comsol batch returns rc=0 even when run() throws; the reliable success
    # signal is the <<<END>>> marker. Treat its absence as an error and surface
    # the comsolbatch error block (or stderr) so callers can fix the Java.
    if "<<<END>>>" not in stdout:
        err = parsed.error or _extract_comsol_error(stdout)
        tail = stdout[-1500:] if stdout else proc.stderr[-1500:]
        raise RuntimeError(f"COMSOL helper error: {err or 'no END marker'}\n--- output tail ---\n{tail}")
    if parsed.error:
        raise RuntimeError(f"COMSOL helper error: {parsed.error}")
    return parsed, stdout


# ---------------------------------------------------------------------------
# Helpers to structure parsed sections
# ---------------------------------------------------------------------------
def _rows(lines: list[str]) -> list[list[str]]:
    return [ln.split("\t") for ln in lines if ln.strip()]


def structure_inspect(sec: SectionedOutput) -> dict:
    # MCP opt #8: parse FILE_INFO section first so the user can see
    # mtime / size_mb / has_sol / per-study status without scanning the model.
    file_info: dict = {}
    file_info_rows = sec.sections.get("FILE_INFO", [])
    for r in _rows(file_info_rows):
        if not r:
            continue
        k = r[0]
        v = r[1] if len(r) > 1 else ""
        if k in ("path", "size_mb", "mtime", "has_sol"):
            file_info[k] = v
        elif k == "study_status" and len(r) >= 3:
            file_info.setdefault("study_status", []).append({"study": r[1], "status": r[2]})

    # MCP opt #5: parse PARAMS with unit detection. Values like "1.5[um]" or
    # "53.96e12[Hz]" carry a unit; values like "1.5" or "0.9" do not. We
    # expose both forms: `params` (raw, backwards-compatible) and
    # `params_units` ({name: unit_str or ""}).
    params: dict[str, str] = {}
    params_units: dict[str, str] = {}
    _PARAM_VAL = re.compile(r"^\s*[-+]?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?\s*\[([^\]]*)\]\s*$")
    for r in _rows(sec.sections.get("PARAMS", [])):
        if len(r) >= 2:
            name, raw_val = r[0], r[1]
            params[name] = raw_val
            m = _PARAM_VAL.match(raw_val)
            if m:
                params_units[name] = m.group(1)
            else:
                # heuristic: if param name contains freq/Hz/wn, flag a unit hint
                lname = name.lower()
                if "freq" in lname or lname.endswith("hz"):
                    params_units[name] = "(Hz?)"
                elif "wn" in lname or "wavenumber" in lname:
                    params_units[name] = "(1/cm?)"
                else:
                    params_units[name] = ""

    components = []
    cur: dict | None = None
    for r in _rows(sec.sections.get("COMPONENTS", [])):
        if not r:
            continue
        if r[0] == "comp":
            cur = {"tag": r[1], "physics": [], "materials": []}
            components.append(cur)
        elif r[0] == "physics" and cur is not None:
            cur["physics"].append({"tag": r[2], "type": r[3] if len(r) > 3 else ""})
        elif r[0] == "material" and cur is not None:
            cur["materials"].append({"tag": r[2], "label": r[3] if len(r) > 3 else ""})
    studies = []
    cur_s: dict | None = None
    for r in _rows(sec.sections.get("STUDIES", [])):
        if not r:
            continue
        if r[0] == "study":
            cur_s = {"tag": r[1], "label": r[2] if len(r) > 2 else "", "features": []}
            studies.append(cur_s)
        elif r[0] == "feat" and cur_s is not None:
            cur_s["features"].append({"tag": r[2], "type": r[3] if len(r) > 3 else ""})
    datasets = [{"tag": r[1], "label": r[2] if len(r) > 2 else ""}
                for r in _rows(sec.sections.get("DATASETS", [])) if r and r[0] == "dset"]
    numerical = [{"tag": r[1], "label": r[2] if len(r) > 2 else ""}
                 for r in _rows(sec.sections.get("NUMERICAL", [])) if r and r[0] == "num"]
    return {
        "file_info": file_info,
        "params": params,
        "params_units": params_units,
        "components": components,
        "studies": studies,
        "datasets": datasets,
        "numerical_nodes": numerical,
    }


# ---------------------------------------------------------------------------
# MCP tools
# ---------------------------------------------------------------------------
@mcp.tool()
def inspect_model(model_path: str) -> dict:
    """Inspect a COMSOL .mph model: list parameters, components/physics/materials,
    studies/features, datasets, and existing numerical evaluation nodes.
    Use this first to learn a model's parameter tags and study tags before
    running studies or extracting results. ~5-10s per call."""
    props = {"model": os.path.expanduser(model_path)}
    sec, _ = run_helper("Inspect", props)
    return structure_inspect(sec)


@mcp.tool()
def run_study(
    model_path: str,
    study: str = "",
    params: dict[str, str] = {},
    output_path: str = "",
) -> dict:
    """Load a COMSOL model, set parameters, run a study, and optionally save the
    solved model. `study` is a study tag (e.g. 'std1'); omit/empty to use the first
    study. `params` maps parameter tags to expression strings (e.g. {'lda0':'1.5[um]'}).
    `output_path` is where the solved .mph is saved; omit to run without saving.
    Returns the params that were set, the study tag used, solve time (ms), and
    saved path. Solve time depends on the model (seconds to minutes)."""
    props: dict[str, str] = {"model": os.path.expanduser(model_path)}
    if study:
        props["study"] = study
    if output_path:
        props["output"] = os.path.expanduser(output_path)
    for k, v in params.items():
        props[f"param.{k}"] = v
    sec, _ = run_helper("RunStudy", props)
    set_params = {r[0]: r[1] for r in _rows(sec.sections.get("PARAMS_SET", [])) if len(r) >= 2}
    # RUNNING section contains free-form progress lines interleaved with our
    # metadata (study/done_ms/dofs/exit_code). We just scan every line and
    # pick up the metadata keys; everything else is ignored.
    _RUNNING_META_KEYS = {"study", "done_ms", "dofs", "exit_code"}
    running = sec.sections.get("RUNNING", [])
    used_study = None
    done_ms = None
    dofs = None
    exit_code = None
    for raw_ln in running:
        r = raw_ln.strip().split("\t")
        if len(r) < 2 or r[0] not in _RUNNING_META_KEYS:
            continue
        if r[0] == "study":
            used_study = r[1]
        elif r[0] == "done_ms":
            done_ms = r[1]
        elif r[0] == "dofs":
            dofs = r[1]
        elif r[0] == "exit_code":
            exit_code = r[1]
    if used_study is None:
        used_study = study
    saved = _rows(sec.sections.get("SAVED", []))
    saved_path = saved[0][1] if saved and len(saved[0]) > 1 else None
    # MCP opt #5: same unit detection as structure_inspect, applied to
    # params that were just set (so the caller can see "L=0.85[um]" nicely)
    _PARAM_VAL = re.compile(r"^\s*[-+]?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?\s*\[([^\]]*)\]\s*$")
    params_units: dict[str, str] = {}
    for k, v in set_params.items():
        m = _PARAM_VAL.match(v)
        if m:
            params_units[k] = m.group(1)
        else:
            lname = k.lower()
            if "freq" in lname or lname.endswith("hz"):
                params_units[k] = "(Hz?)"
            elif "wn" in lname or "wavenumber" in lname:
                params_units[k] = "(1/cm?)"
            else:
                params_units[k] = ""
    return {
        "params_set": set_params,
        "params_units": params_units,
        "study": used_study,
        "solve_ms": int(done_ms) if done_ms and done_ms.isdigit() else done_ms,
        "dofs": dofs,
        "exit_code": exit_code,
        "saved": saved_path,
    }


@mcp.tool()
def get_result(
    model_path: str,
    exprs: list[str],
    units: list[str] = [],
) -> dict:
    """Evaluate global expressions on a (solved) COMSOL model and return a table.
    `exprs` is a list of COMSOL expressions (e.g. ['ewfd.neff', 'ewfd.Ez']).
    `units` is an optional parallel list of unit strings. Returns columns
    <expr>_real and <expr>_imag for each swept point. ~5-10s per call."""
    props: dict[str, str] = {"model": os.path.expanduser(model_path)}
    props["expr"] = ",".join(exprs)
    for i, u in enumerate(units or []):
        props[f"unit.{i}"] = u
    sec, _ = run_helper("GetResult", props)
    lines = sec.sections.get("EVAL", [])
    if not lines:
        return {"columns": [], "rows": []}
    header = lines[0].split("\t")
    rows = [ln.split("\t") for ln in lines[1:] if ln.strip()]
    return {"columns": header, "rows": rows}


# ---------------------------------------------------------------------------
# build_model: compile + run user-provided model-construction Java
# ---------------------------------------------------------------------------
_BUILD_TEMPLATE = """\
import com.comsol.model.*;
import com.comsol.model.util.ModelUtil;
import java.util.*;

public class {cls} {{
  public static Model run() throws Exception {{
    Properties in = BridgeUtil.loadInput();
    String baseModel = in.getProperty("base_model");
    Model model = (baseModel != null && !baseModel.isEmpty())
        ? ModelUtil.load("Model", baseModel)
        : ModelUtil.create("Model");
    // ---- user body (operates on `model`) ----
    // MCP opt #1 (2026-07-10): wrap user body in tryStmt so a swallowed
    // Throwable in user code is surfaced as a [WARN] line + rethrown,
    // instead of being silently lost. To opt out per-statement, write your
    // own try/catch; the wrapper only fires for uncaught Throwables.
    try {{
{body}
    }} catch (Throwable t) {{
      String msg = (t.getMessage() == null || t.getMessage().isEmpty())
          ? t.getClass().getSimpleName() : t.getMessage();
      BridgeUtil.section("ERROR");
      System.out.println("body_aborted\t" + t.getClass().getName() + ": " + msg);
      throw t;
    }}
    // ---- end user body ----
    String runStudy = in.getProperty("run_study");
    if (runStudy != null && !runStudy.isEmpty()) {{
      BridgeUtil.section("RUNNING");
      System.out.println("study\\t" + runStudy);
      long t0 = System.currentTimeMillis();
      String exitCode = "0";
      try {{
        model.study(runStudy).run();
      }} catch (Throwable t) {{
        exitCode = "1";
        System.out.println("run_err\\t" + t.getMessage());
      }}
      System.out.println("done_ms\\t" + (System.currentTimeMillis() - t0));
      System.out.println("exit_code\\t" + exitCode);
    }}
    String savePath = in.getProperty("save_path");
    if (savePath != null && !savePath.isEmpty()) {{
      model.save(savePath);
      BridgeUtil.section("SAVED");
      System.out.println("output\\t" + savePath);
    }}
    return model;
  }}
  public static void main(String[] a) throws Exception {{
    run();
    ModelUtil.remove("Model");
    BridgeUtil.end();
  }}
}}
"""

# allowed chars in generated class name
_BAD_NAME = re.compile(r"[^A-Za-z0-9]")


@mcp.tool()
def build_model(
    java_source: str,
    save_path: str,
    base_model: str = "",
    run_study: str = "",
) -> dict:
    """Build or modify a COMSOL model from Java and save it as .mph.

    `java_source` is the BODY of run() — Java statements that operate on a
    pre-existing variable `model` (a com.comsol.model.Model). If `base_model`
    is given, `model` is loaded from that .mph; otherwise `model` is a freshly
    created empty model. Add geometry, materials, physics, BCs, mesh, studies
    via the COMSOL Java API, operating on `model`. Do NOT return it (the
    template does). You may print with System.out.println and emit
    <<<SECTION>>> markers via BridgeUtil.section(name).

    `save_path` is where the resulting .mph is written (required).
    `run_study` optionally runs a study (by tag) before saving.

    This is the same paradigm as COMSOL's 'File > Export Model to Java'. Compile
    and runtime errors are returned so the Java can be fixed and rerun.

    Returns: saved path, whether a study ran + its time, and any sections the
    body emitted."""
    save_path = os.path.expanduser(save_path)
    base = os.path.expanduser(base_model) if base_model else ""
    job = uuid.uuid4().hex[:12]
    cls = "UserBuild" + job
    src_file = os.path.join(CLASSES_DIR, cls + ".java")
    class_file = os.path.join(CLASSES_DIR, cls + ".class")

    # indent the user body by 8 spaces so it sits inside the tryStmt wrapper
    body_lines = [ln if ln.startswith("        ") else ("        " + ln if ln.strip() else ln)
                  for ln in java_source.splitlines()]
    src = _BUILD_TEMPLATE.format(cls=cls, body="\n".join(body_lines))
    with open(src_file, "w", encoding="utf-8") as f:
        f.write(src)

    # compile against COMSOL jars + classes dir (BridgeUtil co-located)
    cp = comsol_classpath()
    compile = subprocess.run(
        [JAVAC, "-cp", cp, "-d", CLASSES_DIR, src_file],
        capture_output=True, text=True,
    )
    if compile.returncode != 0:
        err = compile.stderr.strip() or compile.stdout.strip()
        # MCP opt #7: parse javac stderr into per-line diagnostics with
        # ±N lines of source context, so the user sees the actual broken line
        # instead of a wall of text.
        diagnostics = _parse_javac_errors(err, src)
        _cleanup_userbuild(src_file, class_file)
        result = {
            "compiled": False,
            "compile_error": err,
            "java_source": src,
        }
        if diagnostics:
            result["compile_diagnostics"] = diagnostics
        return result

    # run via comsol batch; the template saves the model itself (model.save)
    props = {"base_model": base, "run_study": run_study, "save_path": save_path}
    rdir = os.path.join(RUNTIME_ROOT, job)
    tmpdir = os.path.join(rdir, "tmp"); recov = os.path.join(rdir, "recov")
    conf = os.path.join(rdir, "conf")
    for d in (tmpdir, recov, conf):
        os.makedirs(d, exist_ok=True)
    input_file = os.path.join(rdir, "input.properties")
    with open(input_file, "w", encoding="utf-8") as f:
        for k, v in props.items():
            f.write(f"{k}={v}\n")
    env = dict(os.environ); env["COMSOL_BRIDGE_INPUT"] = input_file

    cmd = [
        COMSOL_BIN, "batch",
        "-inputfile", class_file,
        "-tmpdir", tmpdir, "-recoverydir", recov, "-configuration", conf,
        "-prefsdir", PREFS_DIR, "-nosave",
        "-batchlogout", "-batchlog", os.path.join(rdir, "batch.log"),
    ]
    try:
        proc = subprocess.run(cmd, env=env, capture_output=True, text=True, timeout=TIMEOUT_S)
    except subprocess.TimeoutExpired:
        return {"compiled": True, "error": f"comsol batch timed out after {TIMEOUT_S}s"}
    finally:
        shutil.rmtree(rdir, ignore_errors=True)
        _cleanup_userbuild(src_file, class_file)

    stdout = proc.stdout
    parsed = parse_sections(stdout)
    # comsol batch returns rc=0 even when run() throws; the only reliable
    # success signal is the <<<END>>> marker printed by main() on normal exit.
    if "<<<END>>>" not in stdout:
        err = parsed.error or _extract_comsol_error(stdout) or "run() failed (no END marker)"
        return {
            "compiled": True,
            "error": err,
            "stdout_tail": stdout[-2000:],
            # MCP opt #1: surface the structured <<<ERROR>>> body so silent
            # try/catch losses become visible
            "java_warnings": _collect_java_warnings(parsed.sections),
        }
    _RUNNING_META_KEYS = {"study", "done_ms", "dofs", "exit_code"}
    running_lines = parsed.sections.get("RUNNING", [])
    study_ran = None
    done_ms = None
    exit_code = None
    for raw_ln in running_lines:
        r = raw_ln.strip().split("\t")
        if len(r) < 2 or r[0] not in _RUNNING_META_KEYS:
            continue
        if r[0] == "study":
            study_ran = r[1]
        elif r[0] == "done_ms":
            done_ms = r[1]
        elif r[0] == "exit_code":
            exit_code = r[1]
    saved_rows = _rows(parsed.sections.get("SAVED", []))
    saved = saved_rows[0][1] if saved_rows and len(saved_rows[0]) > 1 else None
    return {
        "compiled": True,
        "saved": saved or (save_path if os.path.exists(save_path) else None),
        "study_ran": study_ran or None,
        "solve_ms": int(done_ms) if done_ms and done_ms.isdigit() else done_ms,
        "exit_code": exit_code,
        "sections": parsed.sections,
        "java_warnings": _collect_java_warnings(parsed.sections),
    }


def _collect_java_warnings(sections: dict[str, list[str]]) -> list[str]:
    """MCP opt #1: collect [WARN] lines + <<<ERROR>>> section bodies that the
    Java body emitted, so silent try/catch losses become visible. Looks for:
      - any line starting with '[WARN] ' (from BridgeUtil.tryStmt)
      - any 'body_aborted\t<message>' line (from the tryStmt wrapper)
      - the full text of any section named 'ERROR' or 'WARNINGS'
    """
    out: list[str] = []
    for name, lines in sections.items():
        if name in ("ERROR", "WARNINGS"):
            out.extend(f"[{name}] " + ln for ln in lines if ln.strip())
        else:
            for ln in lines:
                if ln.startswith("[WARN] "):
                    out.append(ln)
                elif ln.startswith("body_aborted\t"):
                    out.append(ln)
    return out


def _cleanup_userbuild(src_file: str, class_file: str) -> None:
    for p in (src_file, class_file):
        if os.path.exists(p):
            try: os.remove(p)
            except OSError: pass
    # comsolbatch status/recovery artifacts next to the .class
    for ext in (".status", ".recovery"):
        p = class_file + ext
        if os.path.exists(p):
            try: os.remove(p)
            except OSError: pass


def _extract_comsol_error(stdout: str) -> str:
    """Pull the message out of comsolbatch's localized error block:
        /***************/
        /*****错误********/   (or /*****Error********/)
        /***************/
        <message lines>
    """
    lines = stdout.splitlines()
    for i, ln in enumerate(lines):
        if "*****错误" in ln or "*****Error" in ln or "*****error" in ln:
            # skip the closing /*****.../ fence, collect non-empty msg lines
            msg = []
            for m in lines[i + 1:]:
                if m.startswith("总时间") or m.startswith("Total time"):
                    break
                if m.strip():
                    msg.append(m.strip(" -"))
            return " ".join(msg).strip() if msg else None
    return None


# MCP opt #7: parse javac stderr to extract per-line error info + source
# context. The classic javac error format is:
#   /path/to/File.java:LINE: error: <message>
#   <source line with caret pointing at the bad column>
#   1 error
_JAVAC_ERR = re.compile(
    r"^(?P<file>[^:\s]+):(?P<line>\d+):\s*(?:error:\s*)?(?P<msg>.*)$"
)


def _parse_javac_errors(stderr: str, src: str) -> list[dict]:
    """Return a list of {file, line, column?, message, snippet_5lines} for each
    javac error found in stderr. `src` is the full generated source so we can
    pull the offending line ± 5 lines of context.
    """
    src_lines = src.splitlines()
    out: list[dict] = []
    for ln in stderr.splitlines():
        m = _JAVAC_ERR.match(ln.strip())
        if not m or m.group("msg").startswith("error:"):
            # the "1 error" summary line; skip
            continue
        try:
            line_no = int(m.group("line"))
        except ValueError:
            continue
        snippet_lo = max(0, line_no - 6)  # 5 lines before
        snippet_hi = min(len(src_lines), line_no + 5)  # 5 lines after
        snippet: list[str] = []
        for i in range(snippet_lo, snippet_hi):
            marker = ">> " if (i + 1) == line_no else "   "
            snippet.append(f"{marker}{i+1:4d} | {src_lines[i]}")
        out.append({
            "file": m.group("file"),
            "line": line_no,
            "message": m.group("msg").strip(),
            "snippet_5lines": "\n".join(snippet),
        })
    return out


@mcp.tool()
def get_volume(
    model_path: str,
    exprs: list[str],
    domains: list[int],
    units: list[str] = [],
) -> dict:
    """Volume-integrate expressions over given domains on a (solved) model, across
    all sweep points, and return the per-point integrals plus the frequency axis.
    Use this to extract e.g. stored energy in a cavity region vs frequency:
    get_volume(path, ['ewfd.Weav+ewfd.Wmav'], [3]) integrates total EM energy
    density over domain 3 for every frequency step. Returns columns
    <expr>_real/<expr>_imag and a parallel 'freq' (Hz) list. ~8s per call."""
    props: dict[str, str] = {"model": os.path.expanduser(model_path)}
    props["expr"] = ",".join(exprs)
    props["domains"] = ",".join(str(d) for d in domains)
    for i, u in enumerate(units or []):
        props[f"unit.{i}"] = u
    sec, _ = run_helper("EvalVolume", props)
    lines = sec.sections.get("EVAL", [])
    freqs = [r[1] for r in _rows(sec.sections.get("FREQ", [])) if len(r) > 1]
    if not lines:
        return {"columns": [], "rows": [], "freq": []}
    header = lines[0].split("\t")
    rows = [ln.split("\t") for ln in lines[1:] if ln.strip()]
    return {"columns": header, "rows": rows, "freq": freqs}


@mcp.tool()
def eval_aggregate(
    model_path: str,
    expr: str,
    domains: list[int] = [],
    boundaries: list[int] = [],
    aggregate: str = "integral",
    unit: str = "",
    outer: str = "",
    outer_value: str = "",
    dset: str = "",
) -> dict:
    """Dimension-aware aggregate (max/min/avg/integral) of an expression over
    domains or boundaries on a SOLVED ewfd model, correctly resolving parametric
    sweeps. This is the sweep-aware successor to get_volume/get_result: it finds
    the parametric dataset, iterates its outer solution tags, and emits one
    curve per distinct swept value (deduping duplicate/zero solutions), so
    e.g. a sweep over L={0.78,0.9} x 121 freq returns two separate 121-point
    curves rather than two identical copies of one L.

    `outer` is the swept parameter name (e.g. 'L'); when given, curves are
    grouped and deduped by its value. `outer_value` (e.g. '0.9') restricts
    output to the single curve whose outer ~= that value. `aggregate` is one
    of max|min|avg|integral. Use domains=[] for whole-model, or boundaries=[5]
    for a boundary aggregate. 1D out-of-plane geometry is handled (geomLevel 2).

    Returns: {meta:{...}, curves: {<outer_value or soltag>: {freq_Hz:[...],
    wavenumber_cm1:[...], value:[...]}}, rows:[raw tsv rows]}.
    ~10-15s per call."""
    props: dict[str, str] = {"model": os.path.expanduser(model_path), "expr": expr}
    props["aggregate"] = aggregate
    if domains:
        props["domains"] = ",".join(str(d) for d in domains)
    if boundaries:
        props["boundaries"] = ",".join(str(b) for b in boundaries)
    if unit:
        props["unit"] = unit
    if outer:
        props["outer"] = outer
    if outer_value:
        props["outer_value"] = outer_value
    if dset:
        props["dset"] = dset
    sec, _ = run_helper("EvalAggregate", props)
    meta_lines = sec.sections.get("META", [])
    meta = {}
    for ln in meta_lines:
        parts = ln.split("\t")
        if len(parts) >= 2:
            meta[parts[0]] = parts[1]
    rows = _rows(sec.sections.get("EVAL", []))
    # header is row 0: soltag, outer, freq_Hz, wavenumber_cm-1, value
    curves: dict[str, dict[str, list]] = {}
    raw = []
    for r in rows[1:]:
        if len(r) < 5:
            continue
        soltag, outer_v, freq_h, wn, val = r[0], r[1], r[2], r[3], r[4]
        key = outer_v if outer_v and outer_v != "nan" else soltag
        c = curves.setdefault(key, {"freq_Hz": [], "wavenumber_cm1": [], "value": []})
        c["freq_Hz"].append(freq_h)
        c["wavenumber_cm1"].append(wn)
        c["value"].append(val)
        raw.append(r)
    return {"meta": meta, "curves": curves, "rows": raw}


@mcp.tool()
def get_reflection(
    model_path: str,
    refl_bnd: list[int] = [5],
    si_dom: list[int] = [3, 4],
    liq_dom: list[int] = [2],
    ca_dom: list[int] = [1],
) -> dict:
    """For 1D out-of-plane ewfd models: compute R(omega)=<|relE|^2/|E_inc|^2>
    averaged over the sctr_in boundary (refl_bnd), and integrated |E|^2
    over Si / Liquid / CaF2 / all domains. Returns sections REFL, E_SI,
    E_LIQ, E_CA, E_ALL, plus the FREQ axis. Use the REFL section to
    Lorentzian-fit the resonance and extract line width kappa_c and Q."""
    props: dict[str, str] = {"model": os.path.expanduser(model_path)}
    props["refl_bnd"] = ",".join(str(x) for x in refl_bnd)
    props["si_dom"]   = ",".join(str(x) for x in si_dom)
    props["liq_dom"]  = ",".join(str(x) for x in liq_dom)
    props["ca_dom"]   = ",".join(str(x) for x in ca_dom)
    sec, _ = run_helper("EvalReflection", props)
    out: dict = {}
    for s in ("REFL", "E_SI", "E_LIQ", "E_CA", "E_ALL", "FREQ"):
        rows = _rows(sec.sections.get(s, []))
        if rows and s != "FREQ":
            header = rows[0]
            data = rows[1:]
            out[s] = {"columns": header, "rows": data}
        elif rows and s == "FREQ":
            out["freq_Hz"] = [r[1] for r in rows if len(r) > 1]
    return out


@mcp.tool()
def dump_model(model_path: str) -> dict:
    """Detailed structural dump of a COMSOL .mph: per component — geometry features,
    physics interfaces + their features (BCs/sources/ports with selections),
    materials + property groups, mesh features; plus studies and their solver
    features with sweep settings. Use this to learn an existing model's exact
    setup (conventions, BCs, ports, sweeps) before reproducing or modifying it.
    ~6-10s per call."""
    props = {"model": os.path.expanduser(model_path)}
    sec, _ = run_helper("Dump", props)
    # return the raw sectioned lines so the caller sees the full tree
    return {k: v for k, v in sec.sections.items()}


@mcp.tool()
def probe_feature(
    feature_type: str,
    properties: list[str],
    kind: str = "geom",
    dim: int = 2,
    feat_type: str = "",
) -> dict:
    """Discover valid property names for a COMSOL geometry feature or physics
    interface — use this to draft correct build_model Java without guessing API
    names. `feature_type` is a COMSOL type string (e.g. 'Rectangle', 'Block',
    'ElectricCurrents', 'CoefficientFormPDE'). `properties` is a list of
    candidate property names to test. `kind`='geom' creates the feature in a
    fresh geometry; 'physics' creates the physics interface and lists its
    default features (feat tags + types) and tests properties on the first one
    — unless `feat_type` is given, in which case it creates that feature type
    inside the interface and tests properties on IT (use to probe a specific
    BC type like 'PeriodicCondition' or 'Scattering' under an ewfd interface).
    `dim` is 2 or 3. Returns: for physics, the feature list; for both, a
    property report (ok/no + allowed values). ~6s per call."""
    props = {
        "kind": kind, "dim": str(dim), "type": feature_type,
        "props": ",".join(properties),
    }
    if feat_type:
        props["feat_type"] = feat_type
    sec, _ = run_helper("Probe", props)
    feats = []
    for r in _rows(sec.sections.get("PHYSICS_FEATURES", [])):
        if r and r[0] == "feat":
            feats.append({"tag": r[1], "type": r[2] if len(r) > 2 else ""})
    created = _rows(sec.sections.get("CREATED", []))
    created_feat = _rows(sec.sections.get("CREATED_FEAT", []))
    prop_report = []
    for r in _rows(sec.sections.get("PROP_PROBE", [])):
        if not r:
            continue
        prop_report.append({
            "property": r[1] if len(r) > 1 else "",
            "valid": r[0] == "ok",
            "allowed": r[2] if len(r) > 2 else "",
        })
    return {
        "kind": kind,
        "feature_type": feature_type,
        "physics_features": feats,
        "created_type": created[0][1] if created and len(created[0]) > 1 else None,
        "created_feat": created_feat[0] if created_feat else None,
        "properties": prop_report,
    }


@mcp.tool()
def set_param(
    model_path: str,
    params: dict[str, str],
    output: str = "",
) -> dict:
    """Set one or more model parameters and save the .mph WITHOUT re-solving.
    Lightweight param-edit for the participatory workflow: tweak geometry /
    symbolic params (e.g. {'L':'0.85[um]', 'wn':'2260[1/cm]'}) and persist so a
    later run_study or build_model picks up the new values.

    NOTE: changing a param on a SOLVED model does NOT change the already-computed
    fields. To get new physics results for the new value, call run_study (which
    sets params AND re-solves). Use set_param only to edit + save (prepare a
    sweep, update geometry before meshing).

    `output` is the save path; if omitted the changes are NOT persisted (the
    returned PARAMS table still shows what would change). Returns
    {params: [{name,old,new}], saved: path or None}. ~8s per call."""
    props: dict[str, str] = {"model": os.path.expanduser(model_path)}
    if output:
        props["output"] = os.path.expanduser(output)
    for i, (name, val) in enumerate(params.items()):
        props[f"param.{i}.name"] = name
        props[f"param.{i}.value"] = val
    sec, _ = run_helper("SetParam", props)
    rows = _rows(sec.sections.get("PARAMS", []))
    changed = []
    for r in rows[1:]:  # skip header
        if len(r) >= 3:
            changed.append({"name": r[0], "old": r[1], "new": r[2]})
    saved = None
    if "SAVED" in sec.sections and sec.sections["SAVED"]:
        srows = _rows(sec.sections["SAVED"])
        if srows and len(srows[0]) > 1:
            saved = srows[0][1]
    return {"params": changed, "saved": saved}


@mcp.tool()
def physics_feature(
    model_path: str,
    physics: str,
    action: str = "list",
    target: str = "feature",
    feat_type: str = "",
    feat_tag: str = "",
    selection: list[int] = [],
    sel_dim: int = 2,
    props: dict[str, str] = {},
    output: str = "",
) -> dict:
    """Raw COMSOL physics-feature API pass-through — set ANY feature property
    (e.g. port1.E0i='0,0,1', sctr_in selection) without the wrapper knowing the
    name in advance. This bypasses the '未知参数' errors that generic helpers hit
    when they hard-code only known properties.

    action:
      - 'list': enumerate all features (tag+type) of the physics interface.
      - 'create': create feat_tag of feat_type (e.g. feat_type='Port'), then
        apply props.
      - 'update': open existing feat_tag, apply props (and selection if given).
      - 'delete': remove feat_tag.
      - 'setselection': set feat_tag.selection to `selection` at geom level
        sel_dim (1D out-of-plane ewfd uses sel_dim=2 for domains, 1 for edges).
    target='interface' applies props to the physics interface itself (e.g.
    ewfd wavelength) instead of a feature.

    `props` maps property names to values; each is set best-effort with type
    fallback (scalar -> comma-split array -> int -> double) and reported
    per-property (ok_scalar/ok_array_split/ERR...). When a name is rejected,
    use probe_feature to discover the correct one. `output` saves the edited
    .mph; if omitted changes are NOT persisted. Returns {action, features or
    prop_results, saved}. ~8s per call."""
    p: dict[str, str] = {"model": os.path.expanduser(model_path), "physics": physics,
                        "action": action, "target": target}
    if feat_type:
        p["feat_type"] = feat_type
    if feat_tag:
        p["feat_tag"] = feat_tag
    if selection:
        p["selection"] = ",".join(str(x) for x in selection)
        p["sel_dim"] = str(sel_dim)
    for i, (name, val) in enumerate(props.items()):
        p[f"prop.{i}.name"] = name
        p[f"prop.{i}.value"] = val
    if output:
        p["output"] = os.path.expanduser(output)
    sec, _ = run_helper("PhysicsFeature", p)
    features = []
    for r in _rows(sec.sections.get("ACTION", [])):
        if r and r[0] == "feat":
            features.append({"tag": r[1], "type": r[2] if len(r) > 2 else ""})
    prop_results = []
    for sec_name in ("FEAT_PROPS", "INTERFACE_PROPS"):
        for r in _rows(sec.sections.get(sec_name, [])):
            # rows are name/value/kind/result (skip the header row starting with 'name')
            if len(r) >= 4 and r[0] != "name":
                prop_results.append({"name": r[0], "value": r[1], "kind": r[2], "result": r[3]})
    # capture selection + deleted lines from ACTION section
    sel_line = None
    deleted = None
    for r in _rows(sec.sections.get("ACTION", [])):
        if r and r[0] == "selection" and len(r) > 1:
            sel_line = r
        if r and r[0] == "deleted" and len(r) > 1:
            deleted = r[1]
    saved = None
    if "SAVED" in sec.sections and sec.sections["SAVED"]:
        srows = _rows(sec.sections["SAVED"])
        if srows and len(srows[0]) > 1:
            saved = srows[0][1]
    return {
        "action": action, "physics": physics, "feat_tag": feat_tag,
        "features": features, "prop_results": prop_results,
        "selection": sel_line, "deleted": deleted, "saved": saved,
    }


# ---------------------------------------------------------------------------
# MCP opt #2: material_info — reveal propertyGroup identifiers and values,
# so users don't have to dump + unzip the .mph to discover that a freshly
# created RefractiveIndex group has identifier "rg1" (not "rfi").
# ---------------------------------------------------------------------------
@mcp.tool()
def material_info(
    model_path: str,
    material: str = "",
) -> dict:
    """Enumerate a model's materials and their propertyGroup identifiers.

    For each material, returns the list of (groupname, identifier) pairs
    plus a best-effort dump of each group's property values. Use this before
    `build_model` to discover the correct identifier for `propertyGroup(...)`
    calls — `material.duplicate(matX, refMat)` preserves the original
    identifier, but `propertyGroup().create(tag, "RefractiveIndex")` does
    NOT (it uses your `tag` as the identifier, which is the most common
    source of "Undefined n" errors).

    `material` filters to a single material tag; empty = all materials.
    ~5-10s per call."""
    props: dict[str, str] = {"model": os.path.expanduser(model_path)}
    if material:
        props["material"] = material
    sec, _ = run_helper("MaterialInfo", props)
    summary: list[dict] = []
    for r in _rows(sec.sections.get("MATERIALS", [])):
        if r and r[0] != "comp":  # skip header
            summary.append({
                "comp": r[0], "tag": r[1] if len(r) > 1 else "",
                "label": r[2] if len(r) > 2 else "",
                "ngroups": r[3] if len(r) > 3 else "",
                "first_id_format": r[4] if len(r) > 4 else "",
            })
    groups: dict[str, list[dict]] = {}
    for sec_name, lines in sec.sections.items():
        if not sec_name.startswith("MATGROUP_"):
            continue
        # sec_name is "MATGROUP_<comp>_<tag>" — use the full name as the key
        entries: list[dict] = []
        current_group: dict | None = None
        for ln in lines:
            parts = ln.split("\t")
            if not parts:
                continue
            if parts[0] == "groupname" and len(parts) > 2:
                current_group = {
                    "groupname": parts[1], "identifier": parts[2],
                    "properties": [],
                }
                entries.append(current_group)
            elif parts[0] == "prop" and current_group is not None and len(parts) >= 4:
                current_group["properties"].append({
                    "name": parts[1], "kind": parts[2], "value": parts[3],
                })
            elif parts[0] == "prop_err" and current_group is not None:
                current_group.setdefault("probe_errors", []).append(parts[1] if len(parts) > 1 else "")
            elif parts[0] == "group_err":
                entries.append({"probe_error": parts[1] if len(parts) > 1 else ""})
        groups[sec_name] = entries
    return {"summary": summary, "groups": groups}


# ---------------------------------------------------------------------------
# MCP opt #3: param_sweep — one-call parametric sweep over a study.
# Replaces set_param+run_study N times, and avoids the cryptic
# "Parameter names not consistent with number of parameter lists" error
# from hand-rolling Parametric features.
# ---------------------------------------------------------------------------
@mcp.tool()
def param_sweep(
    model_path: str,
    study: str,
    param_name: str,
    values: list[str],
    output_path: str = "",
) -> dict:
    """Run a one-shot parametric sweep over a study.

    Internally creates a Parametric feature on the study with the given
    `param_name` and `values`, runs the study (COMSOL sweeps all outer
    values internally), and returns per-value wall-time estimates plus
    a saved swept model (if `output_path` given).

    `values` is a list of COMSOL expression strings, e.g.
    `['6[nm]', '10[nm]', '18[nm]', '25[nm]']` (the same syntax you'd pass
    to `set_param` for that parameter). Solve time is the wall-clock
    divided by N (rough; COMSOL doesn't expose per-outer timings).

    ~ (N × single-solve time) per call; for the FP cavity on this machine
    that's ~5s × N."""
    if not values:
        raise RuntimeError("param_sweep: `values` is empty")
    props: dict[str, str] = {
        "model": os.path.expanduser(model_path),
        "study": study,
        "pname": param_name,
        "plistarr": ",".join(values),
    }
    if output_path:
        props["output"] = os.path.expanduser(output_path)
    sec, _ = run_helper("ParamSweep", props)
    param_meta = {}
    for r in _rows(sec.sections.get("PARAM", [])):
        if r and len(r) >= 2:
            param_meta[r[0]] = r[1] if len(r) == 2 else {r[i]: r[i+1] for i in range(1, len(r)-1, 2)}
    plist = []
    for r in _rows(sec.sections.get("PLIST", [])):
        if r and r[0] != "index":
            plist.append({"index": r[0], "value": r[1] if len(r) > 1 else ""})
    running = _rows(sec.sections.get("RUNNING", []))
    study_used = None
    total_ms = None
    per_value_ms = None
    for r in running:
        if not r:
            continue
        if r[0] == "study" and len(r) > 1:
            study_used = r[1]
        elif r[0] == "total_ms" and len(r) > 1:
            total_ms = r[1]
        elif r[0] == "per_value_ms" and len(r) > 1:
            per_value_ms = r[1]
    per_val = []
    for r in _rows(sec.sections.get("PER_VAL", [])):
        if r and r[0] != "index":
            per_val.append({
                "index": r[0], "value": r[1] if len(r) > 1 else "",
                "solve_ms": r[2] if len(r) > 2 else "",
                "has_sol": r[3] if len(r) > 3 else "",
            })
    saved = None
    srows = _rows(sec.sections.get("SAVED", []))
    if srows and len(srows[0]) > 1:
        saved = srows[0][1]
    return {
        "param": param_meta,
        "plist": plist,
        "study": study_used,
        "total_ms": int(total_ms) if total_ms and total_ms.isdigit() else total_ms,
        "per_value_ms": int(per_value_ms) if per_value_ms and per_value_ms.isdigit() else per_value_ms,
        "per_value": per_val,
        "saved": saved,
    }


if __name__ == "__main__":
    mcp.run()