#!/bin/bash
# install.sh — bridge repo-local skills into Claude's global skill discovery.
#
# Skill SOURCES live in this repo (<repo>/skills/<name>/SKILL.md) so they
# version with the MCP and push to GitHub. Claude Code auto-discovers skills
# only from ~/.claude/skills/, so we symlink each repo skill there.
#
# Idempotent: if ~/.claude/skills/<name> is a real dir (not our symlink), back
# it up to <name>.bak.<ts> before replacing. If it's already our symlink, leave
# it. Run after `git pull` or after adding a skill dir.
set -euo pipefail

REPO_SKILLS="$(cd "$(dirname "$0")" && pwd)"          # .../.comsol-mcp/skills
GLOBAL_SKILLS="${HOME}/.claude/skills"
mkdir -p "$GLOBAL_SKILLS"

echo "install.sh: bridging $REPO_SKILLS -> $GLOBAL_SKILLS"
for d in "$REPO_SKILLS"/*/; do
  name="$(basename "$d")"
  [ -f "$d/SKILL.md" ] || { echo "  $name: SKIP (no SKILL.md)"; continue; }
  target="$GLOBAL_SKILLS/$name"
  if [ -L "$target" ]; then
    cur="$(readlink -f "$target")"
    if [ "$cur" = "$(readlink -f "$d")" ]; then
      echo "  $name: OK (symlink already points here)"
      continue
    fi
    rm "$target"   # stale symlink -> replace
  elif [ -e "$target" ]; then
    ts="$(date +%s 2>/dev/null || echo bak)"
    mv "$target" "$target.bak.$ts"
    echo "  $name: backed up existing real dir -> $target.bak.$ts"
  fi
  ln -s "$d" "$target"
  echo "  $name: linked -> $target"
done
echo "install.sh: done. Skills discoverable: $(ls "$GLOBAL_SKILLS" | tr '\n' ' ')"