#!/usr/bin/env bash
# The health gate, runnable from any directory.
#
# ⚠ `nix run ../dev-lint#gate -- . gate.json` has THREE cwd-relative parts — the
# flake ref, the repo argument and the table — and getting the directory wrong does
# not report a wrong directory. `../dev-lint` resolves against wherever you happen
# to be and the run dies with "getting status of /Users/pippijn/dev-lint: No such
# file or directory", which reads as a broken flake. Copy-pasting the raw command
# out of the README is exactly how that happens.
#
# So this is the one definition, it cds to itself first, and the pre-commit hook
# calls it rather than repeating the invocation. Arguments are passed through, so
# `./gate.sh --only ktlint` and friends still work.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
exec nix run ../dev-lint#gate -- . gate.json "$@"
