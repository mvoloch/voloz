# Voloz — instructions for agents

Read @VOLOZ.md first: it holds the project's current state, decisions,
measurements and next steps. Update it when any of those change.

Rules:

- **Never commit secrets.** API keys, tokens, passwords and the updater
  signing key live outside the repo (GB4: `~/.config/wisprclone/`). This
  repository is public. Before every push, scan new commits for `sk-or-`,
  `sk-ant-`, `PRIVATE KEY` and `rsign encrypted secret key`.
- **Always keep the credits to Voicebox and Jamie Pine** (README, About page,
  LICENSE notice). Removing Voicebox *branding* is fine; removing *credit*
  never is.
- Keep `feat/*` branches upstream-clean (they may become PRs to
  jamiepine/voicebox). Fork-only config (updater, release workflow, branding)
  belongs on `wisprclone`.
- Every fix or improvement that is not Voloz-specific gets a row in
  @UPSTREAM.md. Do not open PRs on jamiepine/voicebox without the owner's go;
  the list is how we give the work back later.
- Match the surrounding code: docstrings, error messages that name the
  endpoint, `Optional[...]` in Python files that already use it.
- Record a test baseline before changing code and report the delta
  against it. Several upstream tests fail for environment reasons
  (ffmpeg, ROCm, HF cache); name a failure as pre-existing only after
  comparing with a clean checkout.
- On GB4, source `~/.config/voloz/dev-env.sh` first; the root partition is
  nearly full, so caches and toolchains go to `/mnt/data`.
