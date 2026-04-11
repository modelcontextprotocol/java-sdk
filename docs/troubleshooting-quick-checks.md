# Troubleshooting Quick Checks

Quick checks when local setup fails:

1. Verify toolchain versions (`java -version`, `node -v`, `python --version`) match project docs.
2. Confirm required services/ports are available before startup.
3. Re-run dependency restore with a clean cache when artifacts are corrupted.
4. Ensure API keys are exported in the same shell/session that starts the app.
