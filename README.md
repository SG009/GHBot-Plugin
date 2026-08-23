# GH-Bot — AI Builder & Admin Agent for PaperMC

**Status: ALL PHASES COMPLETE (0–16) + 9b v2 Web Console + v0.21 Hardening + v0.22.0 JARVIS-FOR-ADMIN + v0.22.1 Eyes-as-Data + v0.22.2 Pillar-3 cmd output capture & audit fixes · smoke 454/454 PASS · jar `GHBot-0.22.2.jar`**

> A hands-off AI-bot that replaces the admin for managing a Minecraft server and in-game designs.

## Quick pointers

- **Start here for the full picture:** [`gh-bot/README.md`](gh-bot/README.md) — contains the
  **agent handoff brief** (current state at v0.22.2: what the plugin is, the JSON-spec contract,
  build/test/ship commands, sandbox constraints, open backlog).
- **Full history:** [`ai-builder-bot-plan.md`](ai-builder-bot-plan.md) — master plan + §17
  per-version changelog (v0.21.33 → v0.21.41: JSON method evolution + hardening).
- **Plugin source:** [`gh-bot/`](gh-bot/) — Java 21, Paper API 1.21.11, package `dev.ghbot.*`
  (never `dev.ghbot.build`), hand-rolled JSON (no Gson).
- **Smoke suite:** `gh-bot/tools/SmokeTest.java` (454 checks) + `setup-build.sh` (restores the
  JDK21/Gradle toolchain into /tmp — the dev sandbox resets every turn). For sandboxes where
  only GitHub/PyPI/npm egress is allowed (no Gradle/Maven/adoptium), `gh-bot/tools/sandbox-build.sh`
  reproduces the same compile+smoke+jar using a PyPI JDK21 runtime + Eclipse ECJ + a public repo's
  committed paper-api dependency cache.
- **Reference build spec fixture:** `gh-bot/tools/fixtures/ancient_dragon.json` (141 KB / 1,852 blocks).
- **Releases:** `releases/GHBot-<ver>.jar` (current ship: v0.22.2; owner policy: the previous version is ALWAYS deleted at ship time — `releases/` holds only the newest).
- **Old project:** `uploads/` — the owner's previous mineflayer GH-series bot (kept for reference).

## The one core mechanic

**The JSON build spec is the CONTRACT.** Since v0.21.39, a pasted JSON spec is parsed and staged
directly — the AI is never asked to re-interpret it. "100% faithful" = staged build == spec.
Since v0.21.40 the web console has a **📎 upload button** (`.json` → staged directly, no pasting
140 KB specs; image → vision provider → JSON spec → staged) and **vision** (Gemini `inline_data`
/ Ollama `images` array).

## Quick start

Drop `GHBot-<ver>.jar` into `plugins/` → restart. Web console at `http://<server-ip>:8580/console`.
Full batch-test checklist in `gh-bot/README.md`.
