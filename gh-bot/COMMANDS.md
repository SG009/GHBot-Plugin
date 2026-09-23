# GH-Bot — Command Reference (v0.27.2 · JARVIS-FOR-ADMIN)

Source of truth: `BotCommands.CATALOG` (also feeds `/gh help`, `/api/tools`, the AI tool sheet).
Prefix in-game: `@GH000 <cmd>` · from console: `gh <cmd>` · web console: type it or use `/cmd`.
**Admin-only:** console + OP players. Non-ops are blocked at dispatch.

**The 4 pillars:** 1 Build · 2 Passive eyes + edit · 3 Manage server · 4 Interact.

---

## Pillar 1 — Build (design → stage → review → place / schem import|paste)
| Command | What it does |
|---|---|
| `build <prompt> [--direct] [at <where>]` | Design + stage a build (ghost → review) |
| `plan <prompt>` | Text-only design preview (no blocks) |
| `edit <target\|here> <instruction>` | Modify an existing build by instruction |
| `schem <name> <prompt> [format\|all]` | Design + export a schematic. **v0.27.1:** format `mcstructure` |
| `schem import <file> [name]` | Import a schematic file (Sponge v2/v3, Classic, Vanilla .nbt, Litematic, Bedrock `.mcstructure`) |
| `paste <file> [where]` | Paste a schematic in-world (stages a ghost → review). **v0.22.2:** `<file>` must live inside the schematics library (traversal rejected); negative-`Size` `.litematic` regions import correctly |
| `library` | Browse the schematic library |
| `export <name> [format\|all]` | Export the staged build as schematics |
| `approve` / `deny` / `redo` | Review the staged build |
| `cancel` | Stop the current job |
| `view [jobId] \| view file <name>` | Open a build in the browser 3D viewer (`scan` checkbox renders the last terrain scan) |
| `teach <name> [staged] [gold]` | Teach a library file / staged build as a learning sample — `gold` makes it a verbatim few-shot exemplar (≤80 blocks) · revived v0.25.0 |
| `dataset list\|remove <name>\|clear` | Browse/manage the build-learning dataset (`[gold]` markers) · revived v0.25.0 |

## Pillar 2 — Passive eyes + world edit
| Command | What it does |
|---|---|
| `scan [radius] [where\|here\|me\|player\|at x y z] [--full [depth]]` | Terrain summary + **jsonspec** (surface by default; `--full` for depth) · **v0.25.0:** per-column `x,z: y material` lattice (r≤32; full grid in `logs/scan/`, viewer `scan` toggle) |
| `look at <x, y, z\|here>` | What block is here? (+ single-block jsonspec w/ blockstate) |
| `find <block> [radius]` | Find blocks of a type (+ jsonspec of the found coords) |
| `set <block> at <x y z\|here\|me>` · `set <where> to <block>` | Precision placement (v0.25.0 look-then-set loop; feeds `undo`) |
| `replace <from> <to> [radius]` | Swap block types in a region |
| `terraform [on\|off\|flatten <radius> [block]\|status]` | Surface flatten (only `flatten` is implemented — `smooth`/`raise`/`lower` are backlog, v0.22.2 doc truth) |
| `undo [minutes\|confirm [minutes]]` | Undo last edit / revert recent edits. **v0.27.0 drift-guard:** refuses (non-destructively) when the edited area changed since the edit — `undo confirm` forces (audit-logged). **Type-fidelity only:** blockstates (stairs facing, sign text) and container contents are NOT restored |

**v0.22.1 — eyes-as-data:** `scan`/`find`/`look` now also emit a **TerrainSpec**
(`{name,palette,blocks[]}` in absolute coords + origin) to bot memory (`eyes.spec`),
`logs/eyes/<name>.json` (full data), and a bounded 150-block inline JSON digest in the reply.
`TerrainSpec.toBuildSpec()` round-trips it back into the build/edit path.

## Pillar 3 — Manage the server
| Command | What it does |
|---|---|
| `status` | Server + bot status (TPS/CPU/RAM/tier) |
| `cap` / `device-info` | Capability report / one-shot stats |
| `provider list\|set <name>` | AI backend per bot |
| `refresh` | Re-learn the server's command catalog |
| `confirm <CONF-token>` | Confirm a blocked systemic command |
| `audit [updates\|show <n>\|fix <n>\|reload\|clear\|selftest]` | **v0.24.0/v0.26.0** — server-console audit: numbered WARN/ERROR digest per plugin (attribution + one practical hint per group) · `show <n>` browses FULL lines+stacks of group #n · `fix <n>` hands the practical fix from **plugins/GHBot/audit-fixes.yml** (owner rules beat the 19 built-ins; clearly-labeled AI guess when nothing matches) · `reload` re-reads the KB without a restart · `updates` = instant table + check age + pre-release risk notes |
| `webtoken` | Regenerate/show the web-console login token (`WEB-…`) — op-only; new token prints to the op AND the server console; kills all web sessions (v0.23.0) |
| `admin <read\|set\|backup\|restore\|rollback\|reload\|menu> …` | Safe config editing (backup+validate+rollback) |
| `cmd <command> [; command; …]` | Run server commands as console (audited; systemic → CONF token). **v0.22.2:** the reply includes the command's **real output** — sender-feed (legacy/Adventure/bungee all captured) + bounded `console-log:` lines for plugins that log instead of replying; long output truncates inline → full text in `logs/cmd/<file>.log`. **v0.22.3:** dispatch goes through Paper's `FeedbackForwardingSender` (the only sender type 1.21's dispatcher accepts userdata for) — commands actually **run** on Paper 1.21 and failures carry the reason (`✗ failed: stip — unknown to the server …`). Confirm runs the command (no more CONF loop). Note: `/gh <botcommand>` dispatched through web `/cmd` executes + audits but its reply text races the HTTP response (async-by-design) — use chat or `/GH000 …` in-game for those |

## Pillar 4 — Interact
| Surface | How |
|---|---|
| Web chat-console | Natural language ("scan 20", "build a house", "change motd to Welcome!") |
| In-game | `@GH000 <command>` · `@GH000 chat "<prompt>"` talks to the Technician |
| Plain auto-tools | `scan`, `find`, `look`, `build`, `plan`, `edit`, `status`, `approve`/`deny`/`redo`, `admin …`, `cmd …`, `confirm CONF-…`, `undo`, `cancel`, `set/replace/terraform/schem/paste` |

---

## Shelved (v0.22.0 — blocked + hidden, kept in code/git for revival)
`avatar` · `marker` · `workers` · `deploy` · `undeploy` · `where` · `save-location` ·
`list-locations` · `delete-location` · `critique` · `design` · `image` ·
`memory` · `debuglog` · `animate` · `add` · `editspec` · `schem download`

These return `§7… shelved in v0.22.0 (not part of the admin surface).` if invoked, and are
absent from help/tools/auto-detect. Remove them from `BotCommands.SHELVED` + re-add to CATALOG
to revive. **(v0.25.0 revived `teach` + `dataset` this way — they power the gold build pack.)**

## In progress (next)
- **✅ Pillar 2 upgrade SHIPPED (v0.22.1):** `scan`/`find`/`look` emit **jsonspec**
  (`{name,palette,blocks[]}`) so the bot sees the world as data → better
  `edit`/`set`/`replace`/`terraform`/`undo`.
- **✅ Pillar 3 upgrade SHIPPED (v0.22.2):** `cmd` results captured into every surface —
  all-surfaces `CapturingSender` (legacy/Adventure/bungee) + session JUL handler for
  log-not-reply plugins, bounded inline + `logs/cmd/*.log`. Plan + dependency evidence:
  `docs/PLAN-pillar3-cmd-output-capture.md`.
- **✅ Live-batch regression sweep SHIPPED (v0.22.3):** cmd dispatch fixed on Paper 1.21
  (FeedbackForwarding route), confirm actually executes, admin server-file reads fixed,
  `scan` sees below y=0. Root causes + probe evidence + live validation matrix:
  `docs/FIX-0.22.3-cmd-dispatch.md`.
- **✅ Version-stamp fix SHIPPED (v0.22.4):** the 0.22.3 jar was stamped `0.22.2` (Gradle
  `processResources` expand map isn't an up-to-date input → stale resources after the
  bump), so `version GHBot` / `plugins` / the load banner reported the old version.
  Fixed via `inputs.property` + smoke + `check-docs.sh` guards:
  `docs/FIX-0.22.4-version-stamp.md`.
- **✅ Web-console login token SHIPPED (v0.23.0 — Q3):** :8580 now requires login. At
  startup GHBot mints `WEB-########` (SecureRandom) and prints it ONCE in the server
  console / latest.log (admin-only eyes — the owner's design); every route redirects
  unauthenticated browsers to `/login` (401 JSON for API paths). Login → HttpOnly
  session cookie (12 h sliding, in-memory). Brute-force: 5 wrong/min per IP → 10 min
  lockout (audit-logged, token never logged). **`webtoken`** bot command: op-only
  regen — logs all sessions out and prints the new token to the op AND the server
  console (the console-log-rescue line, so a web-only owner can't lock themselves
  out). Optional fixed token: `server.web.token:` in config.yml. Every boot mints a
  FRESH token (old ones die). Batch plan: `docs/PLAN-next-batch-v0.23-v0.27.md`.
  **v0.23.1:** login returns you to the page you asked for (`?next=`), defaulting to
  `/console`; the status page now links the console; open-redirect safe.
- **✅ Console-log auditor SHIPPED (v0.24.0 — Phase B) + fix-advisor (v0.26.0 — Phase E2):** `audit [updates|show <n>|fix <n>|reload|clear|selftest]`
  reads the server's OWN log (reflection-only log4j2 root appender; zero deps, clean
  detach) — WARN/ERROR/FATAL ring (200, ×N collapse, `<ip>` strip) → per-plugin digest
  (attribution: logger prefix → stack frames → server core → thread-name fallback) with
  ONE practical hint per group + an installed-only **update radar** (Paper fill v3,
  Essentials GitHub, Modrinth for Geyser/floodgate/Via*/LuckPerms; quiet-once notify,
  first pass +60 s then daily). Chat phrases "any errors?" / "check for updates" route
  here. Owner design: an AUDITOR, not a second console — "why we need 2 server logs".
- **Next backlog:** Phase E queue complete (v0.27.0–v0.27.2). Optional: owner-tuned vision model
  (Gemini 2.5-flash or Ollama llava/qwen2-vl) + `build.verify-vision: true`.
  **Shipped:** Phase C · Phase D skipped · `.mcstructure` (v0.27.1) · vision auto-verify (v0.27.2,
  opt-in) · undo drift-guard (v0.27.0).
