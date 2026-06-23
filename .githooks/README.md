# Git hooks

This repo uses `.githooks/` instead of the default `.git/hooks/`.
After cloning, run:

```bash
git config core.hooksPath .githooks
```

(The script `scripts/init-repo.sh` does this automatically.)

## Hooks

- `pre-push` — refuses non-fast-forward pushes to `main`.
  Bypass once with `git push --no-verify` if you really mean it.
