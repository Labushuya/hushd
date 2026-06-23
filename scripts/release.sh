#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<EOF
Usage: $0 <patch|minor|major|X.Y.Z>

Performs:
  1. Verify clean working tree, on main, up-to-date with origin/main
  2. Compute next version from latest tag (or use explicit X.Y.Z)
  3. Update CHANGELOG.md (move 'Unreleased' to '[X.Y.Z] - YYYY-MM-DD')
  4. Commit: "chore(release): vX.Y.Z"
  5. Tag (signed if user.signingkey set): vX.Y.Z
  6. Push branch and tag, which triggers .github/workflows/release.yml
EOF
  exit 1
}

[[ $# -eq 1 ]] || usage
BUMP="$1"

# --- Pre-flight -----------------------------------------------------------
[[ -z "$(git status --porcelain)" ]] || { echo "ERROR: working tree dirty"; exit 1; }
BRANCH=$(git rev-parse --abbrev-ref HEAD)
[[ "$BRANCH" == "main" ]] || { echo "ERROR: not on main (on $BRANCH)"; exit 1; }
git fetch origin main --tags --quiet
LOCAL=$(git rev-parse HEAD)
REMOTE=$(git rev-parse origin/main)
[[ "$LOCAL" == "$REMOTE" ]] || { echo "ERROR: local main not in sync with origin/main"; exit 1; }

# --- Compute next version --------------------------------------------------
LATEST=$(git describe --tags --abbrev=0 --match 'v*.*.*' 2>/dev/null || echo "v0.0.0")
LATEST="${LATEST#v}"
IFS='.' read -r MA MI PA <<< "$LATEST"

case "$BUMP" in
  patch) NEW="$MA.$MI.$((PA+1))" ;;
  minor) NEW="$MA.$((MI+1)).0"   ;;
  major) NEW="$((MA+1)).0.0"     ;;
  [0-9]*.[0-9]*.[0-9]*) NEW="$BUMP" ;;
  *) usage ;;
esac

TAG="v$NEW"
echo "Releasing $TAG (previous: v$LATEST)"

# --- Conventional commit lint (last 20 commits since last tag) ------------
if command -v npx >/dev/null; then
  if [[ -f .commitlintrc.json || -f commitlint.config.js ]]; then
    git log "v$LATEST..HEAD" --pretty=format:'%s' | while read -r SUBJ; do
      echo "$SUBJ" | npx --yes commitlint || { echo "ERROR: commitlint failed on: $SUBJ"; exit 1; }
    done
  fi
fi

# --- CHANGELOG ------------------------------------------------------------
DATE=$(date -u +%Y-%m-%d)
if grep -qE '^##\s+\[?Unreleased\]?' CHANGELOG.md; then
  # Linux/macOS sed-compat: write to temp then mv
  awk -v ver="$NEW" -v date="$DATE" '
    /^##[[:space:]]+\[?Unreleased\]?/ {
      print
      print ""
      print "## [" ver "] - " date
      next
    }
    { print }
  ' CHANGELOG.md > CHANGELOG.md.tmp && mv CHANGELOG.md.tmp CHANGELOG.md
else
  echo "WARN: CHANGELOG.md has no '## Unreleased' section — adding one retroactively"
  printf "## [%s] - %s\n\n%s" "$NEW" "$DATE" "$(cat CHANGELOG.md)" > CHANGELOG.md.tmp
  mv CHANGELOG.md.tmp CHANGELOG.md
fi

git add CHANGELOG.md
git commit -m "chore(release): $TAG"

# --- Tag (signed if available) --------------------------------------------
if git config --get user.signingkey >/dev/null; then
  git tag -s "$TAG" -m "Release $TAG"
else
  echo "WARN: no user.signingkey set — creating unsigned tag"
  git tag -a "$TAG" -m "Release $TAG"
fi

# --- Confirm + push -------------------------------------------------------
echo
echo "About to push:  $BRANCH  +  tag $TAG"
read -r -p "Continue? [y/N] " ans
[[ "$ans" == "y" || "$ans" == "Y" ]] || { echo "Aborted. Undo with: git reset --hard HEAD~1 && git tag -d $TAG"; exit 1; }

git push origin "$BRANCH"
git push origin "$TAG"

echo
echo "Pushed. Release workflow will trigger on tag $TAG."
echo "Approve in: https://github.com/$(git config --get remote.origin.url | sed -E 's#.*github.com[:/](.+)\.git#\1#')/actions"
