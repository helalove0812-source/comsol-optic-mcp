#!/usr/bin/env python3
"""comsol-skill-factory — propose+generate mechanism (the "generate" half of
hermes three-stage, comsol-flavored).

Stage 1 (Observe) is already done by the existing evolution mechanism:
journal.md (KEPT/PENDING), principles.md (pitfall library), verify.sh
(regression gate). This script only adds Stage 2 (Propose) + Stage 3
(Generate): given a journal entry range (or a task dir), extract the
reusable-simulation pattern (build body path, extraction helper, principles
section, figures, criterion), fill SKILL_TEMPLATE, write a DRAFT skill to
.comsol-mcp/skills/<proposed>/, symlink it via install.sh, and print a
proposal summary for human review. **Propose-not-autosave**: a human must
eyeball the draft and keep or delete it — auto-generated skills are junk
until reviewed (see EVOLUTION.md).

A "reusable simulation class" = a KEPT cluster that has at least:
  - a build body (java/*_body.java or *_body.java.body)
  - an extraction helper (java/Eval*.java) or a get_* tool
  - a success criterion (R_min<..., Q match, dip, visionpro-verified figure)
If those are present, the cluster is a skill candidate.

Usage:
  # propose from the latest N KEPT entries not yet sedimented into a skill
  python3 scripts/skill_factory.py --latest-kept 4

  # propose from an explicit journal entry range (#24-#26)
  python3 scripts/skill_factory.py --from 24 --to 26

  # propose from a task/repro dir (find the body + figures referenced inside)
  python3 scripts/skill_factory.py --task-dir /home/pengyk/Project/Proscia2023_MIM_Lcavity

  # dry run (no file writes, just the proposal summary)
  python3 scripts/skill_factory.py --from 24 --to 26 --dry-run

Sediment tracking: a `.comsol-mcp/.skill_factory_state.json` records which
journal entry ids have already been turned into skills (by name). Re-running
over the same range reports "already sedimented" and skips, so the factory
is idempotent across sessions.

Exit 0 with a proposal summary on stdout; non-zero only on hard error
(not on "no candidate found" — that's a normal empty result, printed).
"""
import argparse
import json
import os
import re
import sys
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
MCP = os.path.dirname(HERE)                       # .comsol-mcp
JOURNAL = os.path.join(MCP, "journal.md")
PRINCIPLES = os.path.join(MCP, "principles.md")
RESULTS = os.path.join(MCP, "results.tsv")
TEMPLATE = os.path.join(MCP, "templates", "SKILL_TEMPLATE.md")
SKILLS_DIR = os.path.join(MCP, "skills")
STATE = os.path.join(MCP, ".skill_factory_state.json")
INSTALL = os.path.join(SKILLS_DIR, "install.sh")

JOURNAL_ENTRY_RE = re.compile(r"^##\s*(#\d+)\s*[—-]\s*(.+)$", re.MULTILINE)


def load_state():
    if os.path.exists(STATE):
        try:
            return json.load(open(STATE))
        except Exception:
            return {"sedimented": {}}            # {entry_id: [skill_name,...]}
    return {"sedimented": {}}


def save_state(st):
    with open(STATE, "w") as f:
        json.dump(st, f, indent=2, ensure_ascii=False)


def parse_journal():
    """Return list of {id, title, body} for every ## #N entry, in order."""
    if not os.path.exists(JOURNAL):
        return []
    txt = open(JOURNAL, encoding="utf-8").read()
    # split on "## #N —" headers
    parts = JOURNAL_ENTRY_RE.split(txt)
    # parts: [pre, id1, title1, body1, id2, title2, body2, ...]
    entries = []
    for i in range(1, len(parts), 3):
        entries.append({"id": parts[i].strip(), "title": parts[i + 1].strip(),
                        "body": parts[i + 2]})
    return entries


def extract_artifacts(body):
    """Heuristically find the build body, extraction helper, figures, criterion,
    principles refs inside a journal entry body. Returns a dict (any key may be None)."""
    art = {"build_bodies": [], "helpers": [], "tools": [], "figures": [],
           "principle_codes": set(), "criterion": None, "base_model": None,
           "tests": []}
    # file paths
    for m in re.finditer(r"`([^`]+\.(?:java|java\.body))`", body):
        p = m.group(1)
        if "body" in p.lower() or "_body" in p.lower():
            art["build_bodies"].append(p)
        elif p.startswith("Eval") or "Reflection" in p or "Aggregate" in p:
            art["helpers"].append(p)
    for m in re.finditer(r"`(runs/[^`]+\.(?:png|dat|csv))`", body):
        art["figures"].append(m.group(1))
    for m in re.finditer(r"`(tests/#\d+[^`/]*)`", body):
        art["tests"].append(m.group(1))
    for m in re.finditer(r"`(0[0-9]_models/[^`]+\.mph)`", body):
        art["base_model"] = m.group(1)
    # tools named like get_reflection_2d / export_slice_3d / add_dispersion_material
    for m in re.finditer(r"`(get_\w+|export_\w+|add_\w+|param_sweep|run_study|build_model|eval_aggregate)`", body):
        if m.group(1) not in art["tools"]:
            art["tools"].append(m.group(1))
    # principle codes A1-F4
    for m in re.finditer(r"\b([A-F][1-4])\b", body):
        art["principle_codes"].add(m.group(1))
    # success criterion: R_min<..., Q match, visionpro verified
    cm = re.search(r"R_min[<＝=]\s*([0-9.]+)", body)
    if cm:
        art["criterion"] = f"R_min<{cm.group(1)}"
    elif re.search(r"visionpro\s*验", body):
        art["criterion"] = "visionpro-verified figure"
    elif re.search(r"Q[≈=]\s*\d+", body):
        art["criterion"] = "Q-factor match"
    return art


def is_reusable(art):
    """A KEPT cluster is a skill candidate iff it has a build body AND an
    extraction (helper or get_* tool) AND a criterion."""
    return bool(art["build_bodies"]) and (bool(art["helpers"]) or
               any(t.startswith("get_") for t in art["tools"])) and art["criterion"]


def group_clusters(entries, id_range):
    """Group consecutive entries into clusters that share a phase header.
    id_range is a set of ids to consider. Returns list of clusters (lists of entries)."""
    clusters, cur, cur_phase = [], [], None
    for e in entries:
        if e["id"] not in id_range:
            continue
        # detect phase header: a "# Phase N:" line before this entry
        clusters and None
        cur.append(e)
    # simplistic: one cluster per contiguous run in id_range (good enough for now)
    return [entries] if False else _contiguous(cur)


def _contiguous(entries):
    out, cur = [], []
    for e in entries:
        m = re.match(r"#(\d+)", e["id"])
        if not m:
            continue
        n = int(m.group(1))
        if cur and n == cur[-1][0] + 1:
            cur.append((n, e))
        else:
            if cur:
                out.append(cur)
            cur = [(n, e)]
    if cur:
        out.append(cur)
    return [[e for _, e in c] for c in out]


def fill_template(skill_name, desc, triggers, dimensionality, cluster, art):
    tmpl = open(TEMPLATE, encoding="utf-8").read()
    rng = f"{cluster[0]['id']}-{cluster[-1]['id']}"
    fill = {
        "{{skill_name}}": skill_name,
        "{{one-two sentences: dimensionality + physics + COMSOL method + what it crystallizes; mention \"driven headless via comsol-mcp\" and the triggering physics keywords}}": desc,
        "{{trigger regex group 1}}": triggers[0] if len(triggers) > 0 else "",
        "{{trigger regex group 2}}": triggers[1] if len(triggers) > 1 else "",
        "{{trigger regex group 3}}": triggers[2] if len(triggers) > 2 else "",
        "{{dimensionality}}": dimensionality,
        "{{2-3 sentences: what this skill crystallizes — which reproduction / paper, the method pipeline, and via which comsol_* MCP tools it executes hard ops.}}": desc,
        "{{what resonances/structures this dimensionality sees; when to route to comsol-1d/2d/3d-ewfd instead}}": "(填:本维度能看什么谐振;何时转 1d/2d/3d-ewfd)",
        "{{principle codes}}": ", ".join(sorted(art["principle_codes"])) or "A1-F4",
        "{{journal entry range}}": rng,
        "{{build_body_path}}": ("`\n  - 建模 body：`".join(art["build_bodies"])) if art["build_bodies"] else "(填)",
        "{{extraction_helper_path}}": ("`\n  - 提取 helper：`".join(art["helpers"])) if art["helpers"] else "(填)",
        "{{figure/data paths}}": "\n  - ".join(art["figures"]) or "(填)",
        "{{base_model_path}}": art["base_model"] or "(填)",
        "{{test paths}}": "\n  - ".join(art["tests"]) or "(填)",
        "{{which sections}}": ", ".join(sorted(art["principle_codes"])) or "A-F",
    }
    for k, v in fill.items():
        tmpl = tmpl.replace(k, v)
    return tmpl, rng


def propose_cluster(cluster, dry_run):
    """cluster = list of entry dicts. Returns (skill_name, draft_path, summary)."""
    merged_body = "\n".join(e["body"] for e in cluster)
    art = extract_artifacts(merged_body)
    if not is_reusable(art):
        return None, None, ("  (cluster %s-%s: NOT a skill candidate — missing "
                             "build body / extraction / criterion)" %
                            (cluster[0]["id"], cluster[-1]["id"]))
    # derive a skill name from the first entry title
    t = cluster[0]["title"].lower()
    m = re.search(r"(1d|2d|3d)\b", t)
    dim = m.group(1) if m else ""
    # pick a slug from keywords
    kw = re.search(r"(pa|perfect absorber|patch|mim|fabry|perovskite|fp|cavity|metasurface|polariton)", t)
    slug = (dim + "-" + (kw.group(1).replace(" ", "-") if kw else "ewfd")) if dim or kw else "ewfd-sim"
    name = "comsol-" + re.sub(r"[^a-z0-9-]", "", slug.replace(" ", "-")).strip("-")
    desc = (f"COMSOL {dim.upper() or ''} ewfd simulation crystallized from journal "
            f"{cluster[0]['id']}-{cluster[-1]['id']} ({cluster[0]['title']}). "
            f"Build body: {art['build_bodies'][0]}; extraction: "
            f"{art['helpers'][0] if art['helpers'] else art['tools'][0]}; "
            f"criterion: {art['criterion']}.")
    triggers = [t for t in [dim + " ewfd", art['criterion'] or "", "comsol " + dim] if t]
    # Resolve naming collisions BEFORE fill_template so the frontmatter `name:`
    # matches the on-disk directory (otherwise frontmatter keeps the pre-collision
    # name while the dir is renamed to <name>-draft — a mismatch that breaks loading).
    draft_path = os.path.join(SKILLS_DIR, name, "SKILL.md")
    collision = os.path.exists(draft_path)
    if collision:
        # don't clobber a hand-written skill — propose a -draft sibling for review
        name = name + "-draft"
        draft_path = os.path.join(SKILLS_DIR, name, "SKILL.md")
    draft, rng = fill_template(name, desc, triggers, dim or "?D", cluster, art)
    summary = (f"  PROPOSED skill: {name}\n"
               f"    journal: {rng}  entries: {len(cluster)}\n"
               f"    build body: {art['build_bodies']}\n"
               f"    extraction: {art['helpers'] or art['tools']}\n"
               f"    criterion : {art['criterion']}\n"
               f"    principles: {sorted(art['principle_codes'])}\n"
               f"    figures  : {art['figures']}\n"
               f"    tests    : {art['tests']}")
    if dry_run:
        summary += f"\n    (dry-run; would write DRAFT to {draft_path})"
        if collision:
            summary += f"\n    ⚠ name collided with existing skill -> renamed to {name} (-draft) to avoid clobber"
        return name, None, summary
    os.makedirs(os.path.dirname(draft_path), exist_ok=True)
    with open(draft_path, "w", encoding="utf-8") as f:
        f.write(draft)
    summary += f"\n    DRAFT written -> {draft_path}  (HUMAN REVIEW: keep or rm -rf)"
    return name, draft_path, summary


def main():
    ap = argparse.ArgumentParser(description="comsol-skill-factory propose+generate")
    g = ap.add_mutually_exclusive_group()
    g.add_argument("--from", dest="frm", type=int, help="journal entry id start (inclusive)")
    g.add_argument("--latest-kept", type=int, help="consider the latest N KEPT entries")
    ap.add_argument("--to", type=int, help="journal entry id end (inclusive)")
    ap.add_argument("--task-dir", help="propose from a repro task dir")
    ap.add_argument("--dry-run", action="store_true", help="no file writes")
    args = ap.parse_args()

    entries = parse_journal()
    if not entries:
        print("no journal entries found"); return 0
    st = load_state()

    # determine id set
    if args.frm or args.to:
        lo = args.frm or 0
        hi = args.to or 9999
        id_range = {f"#{n}" for n in range(lo, hi + 1)}
    elif args.latest_kept:
        kept = [e for e in entries if "[KEPT]" in e["body"]]
        id_range = {e["id"] for e in kept[-args.latest_kept:]}
    elif args.task_dir:
        # find journal entries whose body mentions this task dir (by full path,
        # basename, or any hyphen/underscore-separated keyword token of it)
        td = os.path.basename(os.path.normpath(args.task_dir))
        tokens = set(re.split(r"[-_/]+", td)) | {td, args.task_dir}
        tokens.discard("")
        id_range = {e["id"] for e in entries
                    if any(tok and tok in e["body"] for tok in tokens)}
        if not id_range:
            print(f"no journal entries reference task dir {args.task_dir}"); return 0
    else:
        # default: latest 4 KEPT not yet sedimented
        sed_ids = set()
        for ids in st["sedimented"].values():
            sed_ids.update(ids)
        kept = [e for e in entries if "[KEPT]" in e["body"] and e["id"] not in sed_ids]
        id_range = {e["id"] for e in kept[-4:]}

    selected = [e for e in entries if e["id"] in id_range]
    if not selected:
        print("no entries matched the given range"); return 0

    print(f"comsol-skill-factory: scanning journal entries {sorted(id_range)}")
    clusters = _contiguous(selected)
    proposed = []
    for c in clusters:
        name, path, summary = propose_cluster(c, args.dry_run)
        print(summary)
        if name:
            proposed.append((name, c[0]["id"], c[-1]["id"]))

    # record sedimentation for proposed (only if not dry-run)
    if not args.dry_run:
        for name, a, b in proposed:
            rng_ids = [f"#{n}" for n in range(int(a[1:]), int(b[1:]) + 1)]
            st["sedimented"].setdefault(name, []).extend(rng_ids)
        save_state(st)

    if not proposed:
        print("\n(no skill candidates found in this range — that's fine; not every KEPT cluster is a reusable sim class)")
    else:
        print(f"\n{len(proposed)} skill(s) PROPOSED. Human: review each DRAFT under .comsol-mcp/skills/<name>/SKILL.md,")
        print("fill the (填) placeholders, then run `bash .comsol-mcp/skills/install.sh` to symlink it into ~/.claude/skills/.")
        print("The factory does NOT auto-install — propose-not-autosave, a human keeps the gate. Delete drafts that are junk.")
    return 0


if __name__ == "__main__":
    sys.exit(main())