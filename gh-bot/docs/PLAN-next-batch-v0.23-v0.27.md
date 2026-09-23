# PLAN — next batch (v0.23.0 → v0.27.x), owner-approved scope 2026-09-01

**Workflow rule (owner, 2026-09-01):** present the plan → owner says "go for it"
(or similar) → execute. One phase per approval unless owner says otherwise.
Each phase ships as its own release: build → smoke (+pins) → sandbox-local live
Paper check (owner build 1.21.11-132) → docs/check-docs → releases/ swap +
GitHub Release mirror. Owner answers from chat are quoted inline.

---

## Phase A — v0.23.0 · Web-console login token (SECURITY FIRST) — ✅ SHIPPED 2026-09-01 (smoke 493/493, live matrix on owner build, release mirrored)

> Owner: "create a login token like the confirm CONF-<Numbers> method and showed
> at server console… only real-admin could actually see the server console right?"

- **Token:** mint `WEB-########` (8 digits, CONF-style mint pattern from
  `CommandLearning`) at plugin enable. Printed ONCE at the server console /
  `latest.log` and retrievable in-game by ops via `/gh webtoken` (regen = new
  token, old sessions die). Optional fixed `server.web.token:` in config.yml
  (default "" = mint per boot).
- **Gate (single insertion point):** `WebStatusServer` wraps every context
  (`/`, `/chat`, `/console`, `/cmd`, `/upload`, `/api/*`, `/view…`) with an
  auth decorator. Only `/login` (small static page) + `/api/login` are public.
  Valid login → `Set-Cookie: ghbot_session=<128-bit random>`; in-memory session
  map, 12 h sliding expiry; unknown/expired → 302 → `/login` (401 for /api/*).
- **Brute-force guard:** 5 failed logins/min per IP → 10 min lockout; failures
  logged to the GHBot web log.
- **Smoke pins:** token format/mint; gate blocks no-cookie + accepts session;
  lockout trips; login page is the only public route; regen invalidates.
- **Live check:** sandbox Paper — curl no-cookie → redirect, wrong token ×5 →
  lockout, correct token → console loads, `/cmd` works.
- **Owner acceptance:** open :8580 → login page; paste WEB-token from
  server.log; everything works as before.

## Phase B — v0.24.0 · Console-log auditor ("GHBot audits FOR you") — ✅ SHIPPED (2026-09-23)

> **Shipped evidence:** sandbox live run on the owner build (Paper 1.21.11-132) — reflection-only
> log4j appender attached silently; digest live via AUTO-TOOL "any errors?": `• server (WARN ×6,
> 5 lines): "However, you are 4 release(s) behind the latest stable release (26.2)!" — server
> reports a newer release…` · `• GHBot (WARN ×5, 3 lines): "…HTTP 401…" — set a valid api-key…` ·
> `updates: all current (1 checked)` (132 == fill latest). selftest ×3 collapse + attribution ✓;
> auth matrix re-green ✓. **3 live-polish catches baked in:** thread-name fallback for nameless
> loggers (Paper's banner), truthful distinct-line rendering (no fake "×N" on banner frames),
> suggestion rules scan the whole group + AI-401 rule (polls now requires a key). Owner-suggested
> **DiscordSRV/JDAAppender deep-research** validated the design (same root-attach; GHBot keeps
> FATAL + bounded ring). Smoke **533/533** (+33, three mutation suites killed exactly their pins).
> Details → root/§17 changelog + `gh-bot/README.md` "What's new in v0.24.0". `GHBot-0.24.0.jar`
> in `releases/` (0.23.1 deleted); GitHub Release `v0.24.0` mirrors (0.23.1 release+tag deleted).



> Owner: "GHBot will only tell if there's error, warning and updates of any
> plugin or even the papermc… audit the server console logs then tell me what
> are those and suggests… Don't make the GHBot console agent also turn into
> server console. Why we need 2 server logs right?"

- **Listener (no second console):** tiny programmatic log4j2 appender attached
  to the root logger (`LoggerContext` → `config.getRootLogger().addAppender` +
  `ctx.updateLoggers()`; research-verified pattern — no XML/@Plugin processor
  needed). Runtime classes only: `compileOnly` log4j-core (match Paper 1.21.11's
  bundled 2.x), feature-detected; headless/Spigot → off with a one-line note.
  WARN+ERROR only, bounded ring (200), repeat-collapse (`… ×47`), strips IPs.
- **Speak only when useful:** `audit` bot command + AUTO-TOOL keywords
  ("audit", "any errors?", "server problems?") → AI digests the ring per plugin
  (stack-frame package → plugin attribution), explains each issue plainly and
  gives 1–3 concrete suggestions. Nothing is auto-spammed into chat.
- **Update radar (folded into audit):** on boot + once daily, one quiet pass:
  Modrinth API `/v2/project/{slug}/version` (LuckPerms, Geyser, floodgate,
  ViaVersion/-Backwards/-Rewind — all on Modrinth, research-verified), GitHub
  releases for EssentialsX, fill v3 for Paper builds. Tells you ONLY when
  behind ("Geyser 2.11.1 → 2.12.3"), each notice once until it changes.
  `audit updates` forces a re-check.
- **Smoke pins:** appender captures a fired WARN; dedup collapse; package→plugin
  attribution; version-compare vs fixture JSON (newer/same/behind/no-double-notice);
  offline → silent skip.
- **Live check:** sandbox Paper — provoke a WARN, `audit` shows it + suggestion;
  update pass vs real APIs prints true state (Paper build 132 vs latest).
- **Owner acceptance:** `GH000 audit` in chat → readable digest + update hints.

## Phase C — v0.25.0 · Eyes the AI can BUILD from + "Good-Result Grade" build pack  
**✅ SHIPPED 2026-09-23 — smoke 555/555, live-validated on sandbox Paper 1.21.11-132 (lattice + scan API + set/undo world-truth + style boot), one live-caught drift fixed pre-ship (toolScanReply now the single composer); deterministic-skeleton stretch DEFERRED (owner informed). Details: ai-builder-bot-plan.md §17 v0.25.0.**

> Owner: scan must stay box-shape radius so the AI understands what to offer for
> build/edit/add… "im still confuse… do deep research for me and tell me your ideas!"
> Owner follow-up: test real builds ("build an abandoned outpost"), make
> FREE-tier models produce good builds; wants a jsonSpec dataset-trained model
> but can't afford hosting a fine-tune.

**Answer (research-backed): fine-tuning weights is the wrong shape for us — the
dataset idea is RIGHT and already half-built.** Hosting a custom model is the
unaffordable part (free cloud tiers only serve their own models); GHBot already
HAS the machinery: `LearningDataset` (`teach`, `dataset`, `schem download/import`;
header comment: "Phase 12's retrieval-augmented design reads from here") and
`BuildCommands.generateSpec()` already injects up to 3 retrieved references
("match their proportions, materials and density") — retrieval-augmented
generation exists, it's just shallow (metadata `compactLine()` only) and the
owner's dataset is empty. Few-shot curation gets ~the same quality as a
fine-tune at $0, and the dataset stays owner-editable ("tweaked-cleaned").

**Good-Result Grade pack (new in this phase):**
1. **Gold exemplars in the prompt:** dataset entries can carry a small cleaned
   jsonspec snippet (20–60 ops, marked gold on `teach`) injected VERBATIM — the
   model imitates exact op usage/palette instead of inventing schema.
2. **Style sheets (hand-curated, zero AI needed):** keyword style → rules +
   palette hints ("abandoned" → mossy/cracked variants, ~15% removed blocks,
   vines/cobweb caps; "medieval" → timber framing…). Owner-tweakable YAML.
3. **Two-pass generation:** plan text (footprint/palette/parts — nearly any
   free model nails this) → per-part op batches, each validated by the existing
   `parseWithDiagnostics` with ONE auto-retry feeding the error back (retry
   pattern already proven in the vision path).
4. **Deterministic skeleton (stretch):** GHBot procedurally builds the boring
   70% (foundation/walls/roof), model only picks palette + ruin/scatter/detail —
   a weak model can no longer break the structure.

**Eyes part (unchanged, feeds generation):**

**Deep-research finding (code-verified):** `TerrainScanner.TerrainSummary`
ALREADY collects the per-column heightmap `(x,z)→topY` and top-block data — but
the text handed to the AI flattens it to counts. The owner's own batch proves
the cost: `scan … 1/645504 blocks — top: GRASS_BLOCK:1` tells the model "one
grass block exists SOMEWHERE in 645k blocks" — not that it's at (0,-1,0). Same
scanning work, knowledge thrown away at serialization. That, not the box shape,
is why precision asks degrade to "manually type setblock".

1. **Lattice scan output (`scan` text the AI reads):** radius ≤8 → every column
   line `x,z: y material`; 9–32 → sample every 2nd column + exact center column;
   >32 → counts + surface min/max/delta (today's format). Text capped ~1.5 KB;
   full grid always in `logs/scan/*.log` (existing file-routing).
2. **Advice loop in the prompt guide:** for precision asks ("add diamond above
   the dirt at 0 -1 0") the model first runs `look 0 -1 0` / micro-`scan 4 at …`,
   then proposes an EXACT jsonspec (new `set <block> <x> <y> <z>` op — 1-block,
   preview + approve flow as usual) instead of telling the owner to type
   setblock. The AI does the coordinate math (0,-1,0)+1=(0,0,0).
3. **3D viewer scan layer:** `/api/scan/last` serves the last TerrainSummary
   (heightmap+lattice+bounds, JSON); viewer.html gets a "scan" toggle rendering
   colored voxels at real coords over the same viewer renderer used for builds.
   You visually verify the bot measured the right area before approving.
- **Smoke pins:** lattice format + per-radius sampling + 1.5 KB cap; set-op
  jsonspec validate/undo; /api/scan/last round-trip; prompt-guide contains the
  look-then-set loop.
- **Live check:** flat world `scan 4 at 0 -1 0` → grid contains `0,-1: -1
  grass_block`; headless AI fixture maps the diamond-above-dirt ask → look +
  set-op spec; viewer serves the layer.
- **Owner acceptance:** chat "scan 8" then "add diamond block above the dirt
  block at 0 -1 0" → GHBot stages exactly 1 block at (0,0,0) for your approve.

## Phase C+ — v0.26.0 · Audit Fix-Advisor (owner-proposed mid-batch, greenlit by "Continue..") ✅ SHIPPED 2026-09-23
Owner: "this audit feature shouldn't just inform error events but hand the admin the advice on how/what it needs — correctly — to fix the issues." Shipped: numbered digest + `audit show <n>` full browse · `audit fix <n>` from hand-editable **audit-fixes.yml** (owner rules > 19 built-ins, `audit reload` live) · clearly-labeled AI-guess fallback · synchronous `audit updates` with check age + pre-release risk notes. Smoke 579/579 (+24, mutations D/E/F validated); live-verified incl. edit→reload→answer round-trip. Deferred inside: none.

## Phase D — ~~v0.26.0 · Terraform brushes~~ ❌ DROPPED BY OWNER 2026-09-23
*"skip the phase D and proceed the phase E, i dont think GHBot should have that feature anyway"* — `terraform` stays flatten-only; the brush scope below is kept for history, never scheduled.

## ~~Phase D~~ (dropped — see above)

> Owner: "if possible we could make this part to have the same feature as
> Worldpainter has. What do you think?"

My take: WorldPainter is a desktop editor — its value is the BRUSH CONCEPTS,
which fit GHBot perfectly if sized for a 6 GB phone (block caps, async jobs,
undo, dry-run). v1 subset over the existing flatten machinery:
- `terraform raise <radius> <amount> [at x z|here]` — push columns up, fill with
  the column's own surface block.
- `terraform lower <radius> <amount> [at …]` — dig down (floor at minHeight).
- `terraform smooth <radius> [strength 1-5] [at …]` — box-blur the heightmap,
  N iterations; soften cliffs.
- `flatten` stays as-is. All four: **dry-run estimate first** (blocks changed,
  est. seconds) → confirm (CONF token reuse), one undo per op, phone capability
  caps (est. ~8k blocks/job — large jobs chunk or refuse with advice).
- Stretch (separate approval later): noise/roughen brush, biome paint
  (`world.setBiome`), flood-fill pools.
- **Smoke pins:** raise/lower bounds; smooth converges without oscillation;
  dry-run == applied source-material fidelity; undo restores types.
- **Live check:** sandbox — raise 8 3 leaves a scan-visible bump; undo removes it.
- **Owner acceptance:** "terraform smooth 16 at 100 200" → dry-run numbers →
  confirm → terrain softened; "undo" restores.

## Phase E — v0.27.x queue (green-lit, in this order)

> Owner: "from 5 to 8 is good, nothing to adjust. Green light!"
> Phase D skipped by owner → Phase E started 2026-09-23.

1. **Q1 undo drift-guard** — undo verifies natural drift before restore.
   **✅ SHIPPED in v0.27.0** — exact per-position drift (`Change.newType` vs world);
   refusal is non-destructive (`peekForUndo`), `undo confirm [minutes]` forces with
   truthful audit; live-proven (drift→refuse→survive→confirm→revert).
2. **Vision auto-verify loop** — post-build vision check → self-repair pass. ⏳ next (v0.27.x)
3. **`.mcstructure` export** (+ FAWE fast-paste API research).
   **✅ SHIPPED in v0.27.1** — little-endian uncompressed NBT codec (`McstructureCodec` +
   `LeNbtWriter`); ZYX indices, `-1` voids, two layers, `size` as TAG_List (not Int_Array);
   Java→Bedrock remaps for the well-known divergences; round-trip import so `paste` restages
   on Java. FAWE: **research-only, no code** — do not depend on FAWE on the 6 GB phone
   (see `docs/RESEARCH-mcstructure-fawe.md`). Live: staged 3-block spec →
   `export mcs_live mcstructure` → 409-byte LE file with `format_version=1`.
4. Small batch: ✅ viewer local-mode (loopback bypass, opt-in) · ✅ catalog
   auto-refresh on empty (213 cmds live) · ✅ paste-ambiguity prompt (never
   guess-paste — asks with real candidates). **✅ SHIPPED in v0.27.0**

---
*Research evidence this plan leans on: TerrainScanner heightmap already in
memory (THROWN AWAY in AI text) — gh-bot/terrain/TerrainScanner.java:24-37;
web gate insertion point — web/WebStatusServer.java:66-231; CONF mint pattern —
command/CommandLearning.java:138; log4j2 programmatic appender pattern +
ClassLoaderContextSelector caveat (SO #70206808); Modrinth covers LuckPerms/
Geyser/floodgate/Via*; Geyser native API download.geysermc.org/v2; EssentialsX
via GitHub releases; Paper via fill v3.*
