# GH-Bot — Command Reference (v0.22.3 · JARVIS-FOR-ADMIN)

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
| `schem <name> <prompt> [format\|all]` | Design + export a schematic |
| `schem import <file> [name]` | Import a schematic file (Sponge v2/v3, Classic, Vanilla .nbt, Litematic) |
| `paste <file> [where]` | Paste a schematic in-world (stages a ghost → review). **v0.22.2:** `<file>` must live inside the schematics library (traversal rejected); negative-`Size` `.litematic` regions import correctly |
| `library` | Browse the schematic library |
| `export <name> [format\|all]` | Export the staged build as schematics |
| `approve` / `deny` / `redo` | Review the staged build |
| `cancel` | Stop the current job |
| `view [jobId] \| view file <name>` | Open a build in the browser 3D viewer |

## Pillar 2 — Passive eyes + world edit
| Command | What it does |
|---|---|
| `scan [radius] [where\|here\|me\|player\|at x y z] [--full [depth]]` | Terrain summary + **jsonspec** (surface by default; `--full` for depth) |
| `look at <x, y, z\|here>` | What block is here? (+ single-block jsonspec w/ blockstate) |
| `find <block> [radius]` | Find blocks of a type (+ jsonspec of the found coords) |
| `set <block> <radius>` | Set a region of blocks |
| `replace <from> <to> [radius]` | Swap block types in a region |
| `terraform [on\|off\|flatten <radius> [block]\|status]` | Surface flatten (only `flatten` is implemented — `smooth`/`raise`/`lower` are backlog, v0.22.2 doc truth) |
| `undo [minutes]` | Undo last edit / revert recent edits. **Type-fidelity only:** blockstates (stairs facing, sign text) and container contents are NOT restored |

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
`list-locations` · `delete-location` · `teach` · `dataset` · `critique` · `design` · `image` ·
`memory` · `debuglog` · `animate` · `add` · `editspec` · `schem download`

These return `§7… shelved in v0.22.0 (not part of the admin surface).` if invoked, and are
absent from help/tools/auto-detect. Remove them from `BotCommands.SHELVED` + re-add to CATALOG
to revive.

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
- **Next backlog:** render eyes-specs in the 3D viewer · ambient console-tail feed (needs
  the log4j-core dependency question resolved) · vision quality auto-verify loop ·
  `.mcstructure` Bedrock export · FAWE fast-paste · undo drift-guard (Q1) · web-console
  auth token (Q3).
