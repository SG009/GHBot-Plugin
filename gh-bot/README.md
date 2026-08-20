# GH-Bot — AI Builder & Admin Agent for PaperMC

**Status: ALL PHASES COMPLETE (0–16) + 9b v2 Web Console + v0.21 Hardening + v0.21.39 pasted-spec direct-execute + v0.21.40 📎 upload & vision + v0.21.41 session eviction & thread safety + v0.21.42 mega builds (100k cap) & viewer action feed + v0.21.43 reload-reset & vision retry + v0.21.44 Stop button + v0.21.45 stop actually aborts + real paste & Sponge v3 string-palette import · smoke **377/377 PASS** · jar `GHBot-0.21.45.jar`**

> **Goal:** a hands-off AI-bot that replaces you (the admin) for managing anything related to the
> Minecraft server and/or in-game designs — while you can't play the game or handle the server.

---

## GHBot v0.21.45 — current state & agent handoff brief (READ THIS FIRST)

> If you're a **NEW agent (Claude / OpenClaw / any other model)** taking over this project:
> read this section first. It is the verified truth as of **2026-08-19**. The rest of the
> README is the full feature catalogue; `ai-builder-bot-plan.md` §17 is the complete
> per-version changelog.

### What's new in v0.21.45 (Stop ACTUALLY aborts + real Paste + Sponge v3 string-palette import)
From the owner's v0.21.44 re-test — Stop was pressed (10× "stop requested" logged) yet the
`plan` call still ran its full 2-min timeout, and import/paste were still broken:
- **Stop now actually stops.** Root cause was a **session-key mismatch**: the chat flow keys
  sessions `"GH000|<session>"`, but `/api/cancel` stored the stop flag under the raw
  `<session>` — so the flag never matched, and the in-flight client lookup also missed.
  Fixed: `ChatService` now maps raw→full keys, tracks the **executing thread** per session,
  and `requestStop` marks both keys + **interrupts the thread** (all AI clients' `sendCall`
  now also abort on `InterruptedException`, clearing the interrupt flag for the pooled
  thread). So a hung `plan`/`build`/`generateSpec` is aborted in ~a second, not 2 minutes —
  no more token burn. The console button also now disables into "Stopping…" after one press
  (no more spam of duplicate "Stopped" bubbles).
- **`paste` actually pastes** (was a stub): it now reads the file, decodes it via
  `SchematicImporter.importFile` (Sponge v2/v3, Classic, Vanilla .nbt, Litematica), and
  **stages it as a ghost** — same approve/deny/redo/export review flow as `build`, with the
  3D viewer URL + inline `PREVIEW_IMG` in the reply. Target = player location / bot origin /
  world spawn.
- **Sponge v3 import decodes real files:** official `.schem` v3 palette values are blockstate
  **strings** (`"0": "minecraft:stone_bricks"`), not compounds — the importer only handled
  compounds, so real files imported as **0 blocks**. Now handles strings AND strips
  properties (`minecraft:oak_stairs[facing=north]` → `oak_stairs`).
- Smoke **377 checks** (new: paste file-resolution + parse, Sponge v3 string palette + props).

### What's new in v0.21.44 (Stop button + Sponge v3 import fix + batch-test fixes)
- **⏹ Stop button (the big one):** while a request is running, the **Send button turns into
  STOP** (like ChatGPT/Claude). Pressing it: (1) POSTs `/api/cancel?session=…` → the server
  aborts the in-flight AI HTTP request (`AIClient.cancelActiveCall()` on Gemini/Ollama/OpenAI
  via `sendAsync` + future cancel) so the provider **stops generating → tokens are saved**
  instead of waiting out the timeout, and (2) aborts the SSE fetch client-side. Works even
  for hanging `plan`/`build` calls (the `generateSpec exception from ollama: request timed
  out` 2-minute hang — that was burning the owner's Ollama usage). Closing the tab also
  auto-cancels (the SSE chunk callback detects the disconnect and stops the AI call).
- **Sponge v3 import fixed:** `schem import capital-de-wano.schem` crashed with
  `LinkedHashMap cannot be cast to List` — Sponge v3 stores `Palette` as a compound map,
  the importer only handled the v2 list. Now handles both. (Still open: `paste` importer
  stub — Litematica/`paste` backlog.)
- **Batch-test notes (2026-08-20):** `plan` works but slow provider hangs now stoppable;
  `set`/`replace`/`save-location` with coordinates still reject the AI's
  `at <x> <y> <z>` syntax (tool-surface gap — see backlog); `find that` parsed "that" as a
  block (AI phrasing); guardrails re-verified (`stop` → CONF token → confirm).
- Smoke **372 checks** (new: `/api/cancel`, `cancelActiveCall` idle-safe, Sponge v3
  map-palette import).

### What's new in v0.21.43 (from the live v0.21.42 evidence review)
- **`/api/events` reload-reset fix** — the review feed is in-memory, so a plugin reload
  resets it while the browser cursor kept counting → new Deny/Approve/Export bubbles were
  silently skipped until a page refresh. The endpoint now returns `"reset":true` when the
  cursor is ahead of the feed and the console drops its cursor without rendering the
  pre-reload replay — events keep showing across reloads.
- **Vision: one transient-failure retry for Gemini** — the live test hit Gemini's free-tier
  `503 UNAVAILABLE` ("high demand… usually temporary") and an Ollama timeout. `imageToSpec`
  now retries Gemini once after 5s on 503/429/RESOURCE_EXHAUSTED (Ollama timeouts are not
  retried — they're already slow). Live finding: Gemini free tier 503s are transient; retry
  the image upload if it fails.
- **Boot notice no longer reads like a 500-block cap** — the tier-1 phone message now says
  "est. smooth ~N blocks/job (uploaded JSON specs accepted up to 100k blocks — bigger places
  slower)" instead of "I can build up to ~500 blocks/job".
- Smoke **369 checks** (new: `/api/events` reset flag).

### What's new in v0.21.42 (mega builds + viewer action feed)
- **Build-spec cap raised 20 000 → 100 000 blocks** (`JsonBuildSpec.MAX_BLOCKS`) — huge
  uploaded specs (megastructures, armies, cities) are accepted; the hard limit is now only
  the practical one (ghost placement is tick-budgeted, so 100k blocks place over a little
  while on the phone).
- **Review-activity feed in the chat console:** pressing **Approve / Deny / Export** in the
  3D viewer now posts a message bubble into `/console` (`✅ Approved "Name" (N blocks)`,
  `❌ Denied …`, `📦 Exported … (N file(s))`). Server-side: `WebStatusServer.recordReview()`
  appends to a small feed (capped 200) + echoes to `chat-console.log`; the console polls
  `/api/events?after=<cursor>` alongside `/api/status` and renders new events as centered
  dashed bubbles. Works across tabs (viewer + console open at once).
- Smoke grew to **368 checks** (cap boundary 25k-accept / 100 001-reject, events feed + cursor).

### What's new in v0.21.41 (session eviction + thread safety + init-order fix)
- `ChatService.sessions` → `ConcurrentHashMap` with **TTL eviction** (30 min stale, cap 100
  sessions) — prevents unbounded memory growth on long-running servers (the 6 GB phone).
  A cleanup task sweeps every 5 min (async-safe); the web status sidebar now shows a
  **Sessions** count for monitoring.
- `BuildService.running`, `GhostService.staged`, `UndoManager.stacks/open`, `BotRegistry.bots`
  → `ConcurrentHashMap` — safe against async chat events / concurrent access.
- `JsonBuildSpec.parseWithDiagnostics()` — parse failures now explain WHY (missing
  `palette`/`blocks`, empty arrays, malformed JSON) instead of a bare null; used by the
  vision fallback chain for clearer errors.
- **Init-ordering fix:** `avatarService` / `schematics` are now constructed BEFORE they're
  wired into the ghost/build services (previously the wiring passed null).
- Smoke suite grew to **363 checks** (session eviction + diagnostics).

### What it is (v0.21.45)
GH-Bot is a **PaperMC plugin** (Java 21, Paper API 1.21.11, single jar, no external deps —
even JSON is hand-rolled) that turns a Minecraft server into a **hands-off AI builder + admin
agent**. The owner runs it on a **phone** (Termux, 6 GB RAM, Paper 1.21.11 + Geyser/Floodgate
for Bedrock players, Auto-MCS). Default bot id **`GH000`**; owner's Bedrock player is
`.SerthGembel009`. Admin-only server. The owner batch-tests from the phone web console
(`http://<server-ip>:8580/console`) and pastes **evidence** (`latest.txt` +
`chat-console.txt` + `commands.txt` + screenshots) for the agent to debug.

### The core mechanic (understand this first)
**The JSON build spec is the CONTRACT.** Since v0.21.39, if a build prompt contains a complete
JSON build spec (`{"name":…, "palette":{…}, "blocks":[{x,y,z,block},…]}`), GHBot parses it
**directly and stages EXACTLY those blocks** — the AI is never asked to re-interpret it (that
was the historical source of drift; see §17 v0.21.33–0.21.38). "100% faithful" means:
**staged build == JSON spec**, byte-for-byte. JSON specs can be huge (owner's dragon is
**141 KB / 1,852 blocks**) — hence the **📎 upload button** (v0.21.40) so a 140 KB spec never
has to be pasted into a chat bubble.

### Feature surface at v0.21.45
- **Builder:** `build <prompt>` → JSON spec or DesignSpec primitives → ghost stage → `approve/deny/redo/export`; `--direct`; `plan` (text-only); `cancel`; `critique`. Pasting a JSON spec executes it directly (v0.21.39). No template fallback — failures are reported with a logged reason (v0.21.38).
- **Review surfaces:** 3D web viewer `/view/<id>` (voxel data.json, camera centers the build's true vertical midpoint v0.21.31) + **server-side isometric preview image** `/view/<id>/preview.png` (v0.21.34) rendered **inline in the chat bubble** (v0.21.35) + in-game ghost.
- **Web console `/console`:** SSE streaming chat (quoted-JSON chunks v0.21.25), provider-agnostic tool protocol accepting BOTH `⟦tool:…⟧` and `[tool:…]` markers (v0.21.37), auto-tools for plain-language commands (`scan …`, `find …`, `build …`, `deny`, `approve`, `admin …`, `confirm CONF-…`, …), on-device secretary (WebLLM/Transformers.js, optional), **📎 upload button** (v0.21.40): `.json` → staged directly; image → vision → JSON spec → staged.
- **Admin (Pillar J):** `admin read/set/backup/restore/rollback/reload/menu` on any server file (two-stage YAML/`.properties` validation, backups, `ADM-…` rollback tokens, reload health-check auto-rollback); `cmd <line>` console gateway with hard guardrails (`stop/restart/op/ban/…` blocked → `CONF-…` token + `confirm`), multi-step `;` batches.
- **Terrain/edit/schematic:** `scan/look/find` (TerrainScanner), `set/replace/terraform/undo` (drift-guarded edits with structural anchors), 5 schematic formats (Sponge v2/v3, Classic, Litematica, Vanilla .nbt), build-learning dataset (RAG).
- **Crews/avatar:** `deploy <id> [role]` worker bots, Enderman avatar, markers, `teach`/`critique`.
- **AI providers:** Gemini (free tier) / Ollama (incl. `minimax-m3:cloud`) / OpenAI-compatible extras (Pollinations etc.) / rule-based fallback; auto-failover chain on quota errors. Ollama JSON mode (`format:"json"`) + temp-0 for deterministic structured output (v0.21.36/38).

### Verified live (evidence 2026-08-17, v0.21.39 batch test)
- ✅ Fast-path confirmed in production log:
  `[GHBot] build: JSON build spec detected in prompt — staging 259 exact block(s) directly (no AI interpretation).`
  → Hardcore Fortress Estate staged with exactly **259 ops**, `json-exact`.
- ✅ 141 KB `ancient_dragon.json` parses in ~73 ms → **1,852 exact blocks** (regression-tested in smoke).
- ℹ️ Cozy Cabin 62-vs-65 blocks is **NOT a bug**: the ghost stager skips target positions that
  already hold the same material, so the change-count can under-report on pre-built terrain
  while the build itself is exact.
- ⚠️ Env quirks (documented, avoid re-discovering): Ollama cloud **403** = model needs Pro;
  Cerebras **404** = use `gpt-oss-120b` (not `gpt-oss:120b`); Pollinations = `gen.pollinations.ai/v1`
  (older `text.pollinations.ai`/`enter.pollinations.ai` endpoints are dead).

### Build, test, ship (IMPORTANT — sandbox rules)
The dev workspace is a sandbox that **resets between turns** (`/tmp` and `~/.gradle` are wiped).
Never assume tooling is present. Every turn that touches code:
1. `cd /home/user/gh-bot && bash tools/setup-build.sh clean build` — restores JDK 21 + Gradle 8.10.2 into `/tmp`, builds the jar to `build/libs/GHBot-<ver>.jar`.
2. Recompile + run the smoke suite (currently **377 checks**):
   ```bash
   CP="build/libs/GHBot-<ver>.jar:$(find /tmp/gradle-home/caches/modules-2/files-2.1 -name '*.jar' | grep -v sources | tr '\n' ':')"
   /tmp/jdk21/bin/javac -proc:none -cp "$CP" -d /tmp/smoke-classes tools/SmokeTest.java
   /tmp/jdk21/bin/java -Djava.io.tmpdir=/tmp/javatmp -cp "/tmp/smoke-classes:$CP" dev.ghbot.SmokeTest
   ```
3. Keep the suite green **before** shipping; add a smoke check for every fix.
4. Ship: `cp build/libs/GHBot-<ver>.jar /home/user/releases/GHBot-<ver>.jar` (keep only the newest; older jars are removed after the owner confirms the new one).
5. Never bump versions backwards; bump `build.gradle.kts` `version` each release.

Hard constraints:
- Workspace snapshot **excludes any path segment named `build`** → the package is `dev.ghbot.builder` (never create `dev.ghbot.build`); compiled jars live in `build/libs/` which is excluded, so always copy to `/home/user/releases/`.
- **Gson is blocked** on the mirror — JSON is hand-rolled (`ai/JsonUtil.java`, `builder/JsonBuildSpec.java`).
- A full Paper server **cannot boot in the sandbox** (Paper libs are stubbed) → verification = headless smoke + the owner's live logs/screenshots. Never claim a phase works without a smoke test.
- Server must bind **0.0.0.0** (phone web console), and browser-facing pages must not call `localhost` — use relative URLs (web console and viewer do this already).

### Backlog / known issues (next-agent TODO)
1. **Vision quality tuning** — v0.21.40 image→spec works mechanically (Gemini `inline_data`, Ollama `images` array) but needs real-world iteration: does the model's JSON spec actually match the owner's reference image? Tune `ChatService.VISION_SYSTEM` (schema-in-prompt + temp-0, same lesson as v0.21.38). v0.21.41 added `parseWithDiagnostics()` for better error reporting when vision returns garbage. Optional auto-verify loop: render preview → diff vs uploaded image → feed score back.
2. **Upload UX** — the Technician may echo the whole pasted spec back into chat (`⟦tool:build …⟧` with the raw JSON). Works but noisy; optional: dedupe/summarize when the user's message already IS the spec (upload already summarizes via `summarizeBuild`).
3. **Schematic backlog** — Litematica import, `.mcstructure` (Bedrock) export, FAWE fast-paste, viewer local-mode (offline) still open.
4. **Staging scale** — 1,852-block dragons place over a few seconds (tick-budgeted ghost placement). If too heavy on the 6 GB phone, consider a scale-down/decimation option for huge specs.
5. ~~**Session memory leak**~~ — ✅ fixed in v0.21.41 (TTL eviction + ConcurrentHashMap).
6. ~~**Init ordering bug**~~ — ✅ fixed in v0.21.41 (avatarService/schematics now created before wiring).

---

## All phases — done

| # | Phase | Delivered |
|---|---|---|
| 0 | Foundation | config.yml (mirrors old settings.json), bot registry (GH000+), command registry + dynamic help, WIB logging, `/gh` console, `@GH000` chat parser, sessions. **Server-verified.** |
| 1 | Core Brain | live CPU/RAM/TPS/uptime (silent sampler), Capability Estimator (P16), `/gh device-info`, `@GH000 cap`. |
| 2 | Terrain Eyes | `scan` (you/player/coords, async), `look`, `find`, heightmap, `CoordResolver` (`.Bedrock` names). **Server-verified.** |
| 3 | Block Editing | `set`/`replace`/`terraform`/`undo [min]` — tick-budgeted, audit → `logs/edits.log`. **Server-verified.** |
| 4 | Locations & Web | `save-location`/`where`/… (persistent) + web status page (0.0.0.0:8580). |
| 5 | AI + Chat | Gemini free / Ollama / OpenAI / rule-based fallback; `chat`, `design`, `provider`. |
| 6 | Builder | DesignSpec + 12 primitives + templates; `build`/`plan`/`cancel`; progress, stages (P6), TPS-pause (P3), undo. |
| 7 | Ghost Review | build stages in-place → `approve`/`deny`/`redo`/`export` + `animate`; `--direct`. |
| 8 | Schematics | 5 formats (Sponge v2/v3, Classic, Litematica, Vanilla .nbt) via hand-rolled NBT writer. |
| 8b | Build-Learning Dataset | NBT reader, importers, downloader (25 MB cap), LearningSample (palette/style tags), persistent dataset — NotebookLM-for-builds. |
| 9 | Web 3D Preview | **your viewer served at `/view/<job>`** — real data.json, browser Approve/Deny/Export, `@GH000 view`. |
| 9b | Web Console (v2) | **agent console** at `/console`: **SSE streaming chat** (Gemini/Ollama/OpenAI stream), **tool calling** (`scan`/`build`/`cmd`/`admin read`/`look`/`undo`/`status` via a provider-agnostic protocol), command bar (`/cmd`), live status sidebar (`/api/status`), and **on-device local AI (WebLLM/MLCEngine)** — no cloud, no API key, runs in your browser via WebGPU. Model picker: **Qwen3.5 0.8B / 2B / 4B** (2B = sweet spot for 6 GB), first download ~0.5–2.6 GB then cached + offline, thinking-mode toggle. **v0.21.2 — Secretary Mode:** the local AI is promoted from brainstorm-buddy to **secretary** — it pulls the technician's capability sheet (`/api/tools`), keeps real conversation history, and wraps its proposals in `⟦draft⟧…⟦/draft⟧` → the console shows an editable draft card with a **📨 Send to technician** button that routes the draft through the server brain. Quick chips always run on the server. |
| 10 | Structure Editing | `edit <target> <instruction>` — snapshot → EditSpec (AI or template) → apply → undo; `editspec` preview. |
| 11 | Command Learning | `refresh` catalog, `cmd <line>` as console (full trust **with guardrails** — systemic commands blocked → `confirm <CONF-token>`; multi-step via `;`; audit → `logs/commands.log`), `add <thing>` keyword match. |
| 11b | Admin Operations | `admin read/set/backup/restore/rollback/reload/menu` — safe YAML + **`.properties` editing** w/ backups + validation + `ADM-…` rollback tokens, **server files** (`server.properties` motd/resource-pack, `bukkit.yml`, `spigot.yml`, `paper-global.yml`), **DeluxeMenus menu creation**, audit → `logs/admin.log`. |
| 12 | Avatar & Markers | Enderman statue (AI-disabled) at build sites, `avatar on/off`; `marker <name>` waypoints; auto-save approved builds. |
| 13 | Archetypes | house/hut/mansion/tower/castle/ship/lighthouse/windmill/barn/fountain/bridge/gateway/tree/path/plaza. |
| 14 | Mega + crews | `deploy <id> [role]` / `undeploy` / `workers` — runtime worker bots, roles (architect/builder/admin). |
| 15 | Teach + RAG | `teach <name>` → dataset; **retrieval-augmented design** (dataset references in the AI prompt); `critique`. |
| 16 | Toggles & polish | `image on/off` (ai-image-preview stretch toggle); final polish; dual-version ready (1.21.x API, runs on 26.x). |

---

## v0.21 — Edge-case hardening (your list, all in)

| # | Watchout | Fix shipped |
|---|---|---|
| 1 | **State drift during edits** — blocks moved between snapshot & apply | `edit` now fingerprints the live region (FNV-1a) before planning **and** before applying. On mismatch → warn + re-scan + regenerate the edit plan. |
| 2 | **YAML hot-fix corruption** — LLM writes bad YAML, plugin fails silently | `admin set` is **two-stage**: parse current file (refuse if already broken) + round-trip the exact bytes before writing. Every edit keeps a backup + a persisted rollback token. |
| 3 | **Context blowup from big build RAG** | Dataset refs in prompts are now **compact structural fingerprints** (bbox, density, foundation/roof materials, top palette) — never voxel matrices. |
| 4 | **Full-trust command guardrails** | `stop`/`restart`/`reload`/`op`/`deop`/`ban`/`pardon`/`whitelist`/`rm -rf` etc. are **blocked by default** — they mint a `CONF-…` token you confirm with `confirm <token>` (even when the AI asks). Non-bypassable, 5-min expiry. |
| 5 | **Structural anchors in edits** | AI + templates get a named anchor map (`foundation_base`, `roof_center`, `north_wall`…) — roofs/windows/doors/columns are now sized & placed from the **real bbox**, not hardcoded. |
| 6 | **Audit indexing + rollback** | `logs/admin.log` and `logs/edits.log` carry `[token=ADM-…]` / `[token=EDT-…]`. `/gh admin rollback [token]` reverts config edits in one command (indexed across restarts). Reloads are health-checked: if a plugin dies on reload, the last admin edit **auto-rolls back**. |
| 7 | **Higher-spec scaling** | Capability estimator now has a **tier 4 GIGA-CHAD (32 GB+)** — 10-bot crews, 100k+ block jobs, big local AI. Low spec still gets the full feature set. |
| 8 | **v0.21.3 — Generic admin ops (NOT just DeluxeMenus)** | The technician edits **any** server file: `admin set server.properties motd "…"` (or `resource-pack`), `bukkit.yml`, `spigot.yml`, `paper-global.yml` — same backup/validate/rollback safety, `.properties` format-aware (comments preserved). And `cmd` now runs **multi-step batches** with `;` (e.g. LuckPerms rank setup), each line audited + guarded. DeluxeMenus was the example, never the limit. |

## Quick start (batch-test checklist)

1. Drop `GHBot-0.21.45.jar` into `plugins/` → restart. Console: `/gh status`, `/gh device-info`.
2. In-game: `@GH000 help` (lists ~27 commands) · `@GH000 scan here` · `@GH000 build a house` → walk the ghost → `approve`/`deny`/`redo`.
3. **Browser review:** `server.web.enabled: true` → restart → `@GH000 build a tower` → `@GH000 view` → open URL → orbit → **Approve**.
4. **Web chat:** open `http://<phone-ip>:8580/chat` (old page) or `/console` (agent console). **Secretary (no cloud):** click **Load secretary** — needs Chrome/Edge on Android. WebGPU requires a secure context, so open `http://127.0.0.1:8580/console` on the phone itself (or via an https tunnel) — plain `http://<LAN-ip>` won't expose WebGPU. Pick **2B** for a 6 GB phone, **4B** for max quality when the server is idle. **Workflow:** ask the secretary to draft a request → edit its `⟦draft⟧` card → **Send to technician** (chips still execute on the server directly). **Upload (v0.21.40):** the **📎** button next to the input — pick a `.json` build spec (staged directly, no paste) or an image (GH-bot sees it → generates the build).
5. **AI:** `ai.providers.gemini.enabled: true` + free key (or Ollama) → `@GH000 chat hi`.
6. **Dataset:** `@GH000 schem download myhouse <url>` → `@GH000 dataset list` → `@GH000 build a castle` (now style-aware).
7. **Editing:** `@GH000 build a house` → `@GH000 edit here replace oak_planks with spruce_planks` → `@GH000 undo`.
8. **Admin:** `@GH000 admin menu shop "&aShop"` → `/dm reload` → open `shop` in-game.
9. **Server files (v0.21.3):** `@GH000 admin set server.properties motd "WELCOME TO GH-LOUNGE!"` → backed up + token shown → `@GH000 admin rollback <ADM-…>` reverts. (MOTD applies after restart.)
10. **Multi-step commands (v0.21.3):** `@GH000 cmd lp creategroup PRO; lp creategroup PREMIUM; lp group PRO parent add default` — each line audited; systemic ones still blocked with `CONF-…`.
11. **Crews:** `@GH000 deploy GH002 builder` → `@GH000 workers` → `@GH000 GH002 build a barn` (parallel crew).
12. **Avatar/markers:** `@GH000 avatar on` → build → watch the Enderman; `@GH000 marker spawn` → `@GH000 marker list`.

### v0.21 specific tests (new)
- **Drift guard:** `@GH000 edit here add a roof` — while it plans, break/place a block in the region → you should see "Region changed while planning — re-scanning".
- **Guardrail:** `@GH000 cmd stop` → should be **blocked** with a `CONF-…` token, not run. Then `@GH000 confirm CONF-…` (only do this if you actually want to test stop!).
- **Rollback:** `@GH000 admin set DeluxeMenus/config.yml some-key test` → note the token → `@GH000 admin rollback <ADM-token>` → file restored.
- **GIGA-CHAD:** on your phone it'll say tier 1 — the tier-4 path is smoke-tested for higher-spec boxes.

## Layout

```
gh-bot/
├── build.gradle.kts                  (Java 21, Paper API 1.21.11; version = current jar)
├── src/main/resources/ plugin.yml · config.yml · web/viewer.html · web/chat.html · web/console.html
├── src/main/java/dev/ghbot/  (19 packages, 81 files)
│   ai/ admin/ agent/ avatar/ bot/ builder/ chat/ command/ config/ core/ edit/
│   location/ log/ review/ schematic/ session/ terrain/ web/   (+ GHBotPlugin.java)
└── tools/SmokeTest.java              (377 checks, all passing) · setup-build.sh (sandbox toolchain restore)
releases/GHBot-0.21.45.jar            (current ship; old jars removed once confirmed)
ai-builder-bot-plan.md                (master plan + §17 full per-version changelog — lives at repo root: /home/user/ai-builder-bot-plan.md)
```

### v0.21.39 / v0.21.40 specific tests (new)
- **Pasted JSON spec (v0.21.39):** paste `build <entire JSON spec>` → console log must contain
  `JSON build spec detected in prompt — staging N exact block(s) directly (no AI interpretation).`
  and the reply `Design: <name> · json-exact · … N op(s)`. No AI re-interpretation.
- **📎 Upload (v0.21.40):** in `/console`, tap the paperclip → pick `ancient_dragon.json`
  (141 KB / 1,852 blocks) → expect `📦 Staged from upload: … (1852 blocks)` + 3D viewer link +
  inline preview image. No paste needed.
- **👁️ Vision (v0.21.40):** upload a `.png` reference of a build → GHBot sends it to a
  vision provider (Gemini 2.5 Flash or a multimodal Ollama model) → returns a JSON build spec →
  staged. Compare the preview against the uploaded image.

