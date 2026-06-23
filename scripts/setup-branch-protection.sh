#!/usr/bin/env bash
set -euo pipefail

OWNER="Labushuya"
REPO="hushd"
BRANCH="main"

gh api -X PUT "repos/${OWNER}/${REPO}/branches/${BRANCH}/protection" \
  -H "Accept: application/vnd.github+json" \
  -F required_status_checks.strict=true \
  -F 'required_status_checks.contexts[]=Lint (ktlint + detekt + android-lint)' \
  -F 'required_status_checks.contexts[]=Unit tests' \
  -F 'required_status_checks.contexts[]=Assemble debug APK' \
  -F 'required_status_checks.contexts[]=Dependency review' \
  -F enforce_admins=true \
  -F required_pull_request_reviews.dismiss_stale_reviews=true \
  -F required_pull_request_reviews.require_code_owner_reviews=true \
  -F required_pull_request_reviews.required_approving_review_count=1 \
  -F required_pull_request_reviews.require_last_push_approval=true \
  -F required_signatures=true \
  -F required_linear_history=true \
  -F required_conversation_resolution=true \
  -F allow_force_pushes=false \
  -F allow_deletions=false \
  -F block_creations=false \
  -F lock_branch=false \
  -F allow_fork_syncing=false \
  -f restrictions=null

# Tag-Protection für vX.Y.Z
gh api -X POST "repos/${OWNER}/${REPO}/tags/protection" \
  -H "Accept: application/vnd.github+json" \
  -f pattern='v*.*.*' || true

# Environment "production" anlegen (idempotent)
gh api -X PUT "repos/${OWNER}/${REPO}/environments/production" \
  -H "Accept: application/vnd.github+json" \
  -F "wait_timer=0" \
  -F "deployment_branch_policy[protected_branches]=false" \
  -F "deployment_branch_policy[custom_branch_policies]=true"

# Reviewer für production-Environment (User-IDs vorher via gh api /users/<login> ermitteln)
echo "Manueller Folge-Schritt: Settings → Environments → production → Required reviewers (>=1) hinzufügen"
echo "Manueller Folge-Schritt: Settings → Environments → production → Deployment branches:"
echo "   - Add rule: pattern 'main'"
echo "   - Add rule: pattern 'v*.*.*' (tag)"
