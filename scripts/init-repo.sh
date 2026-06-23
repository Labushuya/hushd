#!/usr/bin/env bash
# One-shot setup after `git clone` — configures the local repo for development.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
git config core.hooksPath .githooks
echo "OK: git hooks pointing at .githooks/"
echo "    (override per-clone with `git config --unset core.hooksPath`)"
