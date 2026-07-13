#!/usr/bin/env python3
"""Thin client for Type-B regression tests (build_model path).

Imports server.py via a stubbed `mcp` package (tests/_mcp_stub) so the real
mcp transport isn't needed, then calls the REAL server.build_model with kwargs
read from argv[1] (a JSON object). Zero duplication: this exercises the actual
build_model code path — _BUILD_TEMPLATE (tryStmt wrapper + auditMesh inject),
javac compile with _parse_javac_errors, comsol batch run, mesh_audit parsing.

Usage:
    _build_client.py '<json kwargs>' --expect '<python expr over r>'
    _build_client.py '<json kwargs>' --expect '<expr>' --id '#7'

The --expect expression is eval'd against the build_model result dict `r`.
Exits 0 (prints "#<id> PASS ...") if the expression is truthy, else 1 with
the result summary.

Env: shares server.py's COMSOL_BIN / COMSOL_MCP_DIR defaults; no MCP restart
needed (we call the function directly, not through the transport).
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "_mcp_stub"))  # stub mcp package
sys.path.insert(0, os.path.dirname(HERE))            # so `import server` finds .comsol-mcp/server.py

import server  # noqa: E402


def main():
    kwargs = json.loads(sys.argv[1])
    expect = ""
    tid = ""
    for i, a in enumerate(sys.argv[2:]):
        if a == "--expect":
            expect = sys.argv[2 + i + 1]
        elif a == "--id":
            tid = sys.argv[2 + i + 1]
    r = server.build_model(**kwargs)
    try:
        ok = bool(eval(expect, {"r": r, "len": len, "any": any, "all": all}))
    except Exception as e:
        print(f"{tid} FAIL: expect expression error: {e}")
        print(json.dumps(r, indent=2, default=str)[:2000])
        sys.exit(1)
    if ok:
        # short success summary (single-line: sanitize newlines/tabs so verify.sh
        # tail -1 grabs the whole PASS line, not a fragment of a multi-line error)
        def oneline(s):
            return str(s).replace("\n", " ").replace("\t", " ")[:80]
        summary = []
        if "compiled" in r:
            summary.append(f"compiled={r['compiled']}")
        if "compile_diagnostics" in r:
            summary.append(f"diagnostics={len(r['compile_diagnostics'])}")
        if "java_warnings" in r:
            summary.append(f"warnings={len(r['java_warnings'])}")
        if "mesh_empty_selections" in r:
            summary.append(f"mesh_empty={len(r['mesh_empty_selections'])}")
        if "error" in r:
            summary.append(f"error={oneline(r['error'])}")
        print(f"{tid} PASS ({', '.join(summary) if summary else 'ok'})")
        sys.exit(0)
    print(f"{tid} FAIL: expect not satisfied: {expect}")
    print(json.dumps(r, indent=2, default=str)[:2500])
    sys.exit(1)


if __name__ == "__main__":
    main()