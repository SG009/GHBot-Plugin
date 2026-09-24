# Changelog

All notable changes to GHBot-Plugin are documented in this file.

## [v0.28.0] - 2026-09-24

### Command-Script Upload (📎 .txt/.cmd/.mcfunction PREVIEW first)

**New CommandScript class**: parses uploaded text files into Line records (number, step, section, command, sensitive flag, placeholders). Parser is the CONTRACT — same idea as a pasted JSON build spec. The Technician never invents extra server commands.

- 📎 button accepts `.txt`/`.cmd`/`.mcfunction` alongside `.json` and images
- Preview first, never auto-run on upload
- Fill placeholders via chat: "my name is .SerthGembel009" fills YOURNAME
- Skip via chat: "skip step 6" / "skip the boss" / "skip the starter weapon"
- Drop via chat: "drop the script" forgets the pending file
- CONF guards still apply: op/stop/reload/whitelist mint tokens at run-time
- Dispatches through cmd's per-line capture for real output + logs/cmd/*.log
- AutoTools.detectScript runs BEFORE detect but only matches when pending
- `script` command added to CATALOG (run|status|drop|preview|execute)
- CapabilityGuide + ToolProtocol tell the AI not to cmd the file itself

**Smoke**: 642/642 (+22 over v0.27.2). Mutations G/H/I each killed exactly their pins.

---

## [v0.27.2] - 2026-09-23

### Vision Auto-Verify (opt-in `build.verify-vision`, 1 repair pass)

- Post-stage vision checklist (requires Gemini 2.5-flash or Ollama llava/qwen2-vl)
- 1 repair pass for AI-generated builds when vision says `ok=false`
- Pasted JSON specs are the contract (notes only, never rewritten)
- Default OFF to prevent surprise token spend
- Honest provider gate: text-only Ollama models skipped even if transport accepts images

**Smoke**: 620/620 (+13). Mutations G/H/I validated.

---

## [v0.27.1] - 2026-09-23

### Bedrock `.mcstructure` Export + FAWE Research

- New `McstructureCodec` + `LeNbtWriter` (little-endian uncompressed NBT)
- Spec-accurate: `format_version=1`, `size` as TAG_List (not Int_Array), ZYX indexing
- Java→Bedrock name remaps (grass_block→grass, cobweb→web, dirt_path→grass_path)
- `export <name> mcstructure` (alias `bedrock`) writes alongside 5 Java formats
- Round-trip import: `paste foo.mcstructure` restages on Java
- FAWE: research only, no dependency (would bypass undo/ghost/drift-guard)

**Smoke**: 607/607 (+18). Mutations G/H/I validated.

---

## [v0.27.0] - 2026-09-23

### Phase E Start — Undo Drift-Guard + Small-Batch Safety

- **Undo drift-guard**: exact per-position check before restoration; non-destructive refusal via `peekForUndo`; `undo confirm` forces with audit log
- **Web loopback bypass**: opt-in `server.web.local-bypass` for owner on the server device
- **Paste-ambiguity**: asks with real candidates instead of guessing
- **Catalog auto-refresh**: on empty at boot+2s (live: 213 commands)

**Smoke**: 589/589 (+10). Mutations G/H/I validated.

---

## [v0.26.0] - 2026-09-23

### Audit Fix-Advisor (Phase E2, owner-proposed)

- Numbered digest groups → `audit show <n>` browses full lines+stacks
- `audit fix <n>` answers from hand-editable `audit-fixes.yml` (owner rules win)
- 19 built-in rules + AI-guess fallback (clearly labeled)
- Instant update tables with pre-release risk notes

**Smoke**: 579/579 (+24). Mutations D/E/F validated.

---

## [v0.25.0] - 2026-09-23

### Eyes Lattice & Good-Result Build Pack (Phase C)

- **Lattice scan**: per-column `x,z: y material` grid (stride tiers, budget-capped)
- **Viewer scan layer**: checkbox renders last scan in 3D viewer
- **Look-then-set**: AI scans first, calculates offsets, places with `set`
- **Gold exemplars**: tagged builds synthesize few-shot exemplars (≤80 blocks)
- **Style sheets**: hand-editable `styles.yml` (abandoned/medieval/modern/rustic)
- **Two-pass generation**: plan → per-part specs → per-part validation with retry

**Smoke**: 555/555 (+22). Mutations validated.

---

## [v0.24.0] - 2026-09-23

### Console-Log Auditor (Phase B)

- Reflection-only log4j2 root appender (zero deps, clean detach)
- WARN/ERROR/FATAL ring (200 entries, ×N collapse, IP strip)
- Per-plugin digest with attribution (logger prefix → stack frames → server core)
- Suggestion rules: class-not-found, java-version, enable-fail, OOM, network, TLS, AI-401
- Update radar: Paper fill v3, Essentials GitHub, Modrinth for Geyser/floodgate/Via*/LuckPerms

**Smoke**: 533/533 (+33). DiscordSRV-validated design.

---

## [v0.23.1] - 2026-09-01

### Login UX: Return-to-Destination

- Login redirects to the page you asked for (`?next=`)
- Default landing: `/console` (not the status page)
- Open-redirect guard: same-site absolute paths only

**Smoke**: 500/500 (+7).

---

## [v0.23.0] - 2026-09-01

### Web-Console Login Token (Q3)

- `WEB-########` token minted at startup, printed to server console
- Every route requires login (except `/login` and `/api/login`)
- 12h sliding session cookie, brute-force guard (5 wrong/min → 10 min lockout)
- `/gh webtoken` regenerates token (op-only)

**Smoke**: 493/493 (+20).

---

## [v0.22.4] - 2026-08-20

### Jar Version-Stamp Fix

- v0.22.3 jar was stamped `0.22.2` (Gradle `processResources` expand map isn't an up-to-date input)
- Fixed via `inputs.property("version", project.version)` + smoke + check-docs.sh guards

**Smoke**: 473/473 (+2).

---

## [v0.22.3] - 2026-08-20

### Live-Batch Regression Sweep

- `cmd` ACTUALLY RUNS on Paper 1.21 (FeedbackForwardingSender)
- `confirm` actually executes (no more CONF loop)
- `admin read` works on relative world containers
- `scan` sees below y=0 (pre-1.18 clamp removed)

**Smoke**: 471/471 (+17).

---

## [v0.22.2] - 2026-08-20

### Pillar-3 Cmd Output Capture + Pillar-1/2 Audit Fixes

- **Cmd output**: all-surfaces CapturingSender (legacy/Adventure/bungee) + session JUL handler
- **P1-1**: template-edit bbox Y/Z transposition fixed
- **P1-2**: Litematica import handles negative `Size`
- **P1-3**: paste/schem import filenames confined to library dir
- **P1-4**: /upload rejects oversized bodies DURING read (25 MB + 1 byte)

**Smoke**: 454/454 (+34).

---

## [v0.22.1] - 2026-08-20

### Eyes-as-Data (scan/find/look → jsonspec)

- TerrainSpec: `{name,palette,blocks[]}` in absolute coords + origin
- Round-trip: `TerrainSpec.toBuildSpec()` feeds build/edit path
- Vanilla block constraint: `Material.matchMaterial` + blockstate strip
- Heightmap persistence across reloads
- Coordinate parsing unified: `CoordResolver.scanTarget()`

**Smoke**: 420/420 (+36).

---

## [v0.22.0] - 2026-08-20

### JARVIS-FOR-ADMIN (4-pillar reset)

- **Pillar 1 Build**: build, plan, edit, schem, paste, library, export, approve/deny/redo
- **Pillar 2 Passive eyes + edit**: scan, find, look, set, replace, terraform, undo
- **Pillar 3 Manage**: status, cap, device-info, provider, refresh, confirm, admin, cmd
- **Pillar 4 Interact**: natural language web chat, @GH000 in-game
- Admin-only: non-OP players blocked at dispatch
- Shelved: avatar, marker, workers, deploy, where, save-location, teach, dataset, critique, design, image, memory, debuglog, animate, add, editspec, schem download

**Smoke**: 384/384.

---

## Earlier Versions

See [ai-builder-bot-plan.md §17](ai-builder-bot-plan.md) for the full v0.21.33 → v0.21.45 changelog (JSON method evolution, hardening, upload, vision, stop button, Sponge v3 import).
