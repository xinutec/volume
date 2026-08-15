#!/usr/bin/env bash
# Point git at the version-controlled hooks (one-time, per clone).
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
git config core.hooksPath scripts/githooks
echo "git hooks installed: core.hooksPath = scripts/githooks (pre-commit runs gate.json)"
