"""Stub FastMCP so server.py imports without the real `mcp` package.

The real FastMCP registers functions as MCP tools and runs a stdio server.
For tests we only need the decorated functions to remain callable as plain
Python functions, so .tool() returns a passthrough decorator and .run() is a
no-op. server.py is imported for its build_model / run_helper core, never run
as a server here.
"""


class FastMCP:
    def __init__(self, name: str = ""):
        self.name = name

    def tool(self):
        def deco(fn):
            return fn
        return deco

    def run(self, *a, **k):
        raise RuntimeError("stub FastMCP.run() called — tests must not start the server")