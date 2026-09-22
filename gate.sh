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
# So this is the one definition for a person, and it cds to itself first. The
# pre-commit hook execs the runner directly (git runs it at the root, so nothing
# there is cwd-relative). Arguments are passed through, so
# `./gate.sh --only ktlint` and friends still work.
#
# `?ref=HEAD`, matching the other repositories' hooks: a plain path builds the
# NEIGHBOUR'S WORKING TREE, so a session mid-edit in dev-lint fails this gate for
# a reason no commit here explains, and the failure names this repository. The
# reasoning is written out at `withTestDb` in dev-lint/gate/schema.dhall.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
exec nix run "git+file:../dev-lint?ref=HEAD#gate" -- . gate.json "$@"
