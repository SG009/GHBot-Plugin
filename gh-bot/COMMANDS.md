# GH-Bot — Command Reference (v0.22.0 · JARVIS-FOR-ADMIN)

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
| `paste <file> [where]` | Paste a schematic in-world (stages a ghost → review) |
| `library` | Browse the schematic library |
| `export <name> [format\|all]` | Export the staged build as schematics |
| `approve` / `deny` / `redo` | Review the staged build |
| `cancel` | Stop the current job |
| `view [jobId] \| view file <name>` | Open a build in the browser 3D viewer |

## Pillar 2 — Passive eyes + world edit
| Command | What it does |
|---|---|
| `scan <where\|here\|me> [radius]` | Terrain summary (ground, heightmap, blocks, water) |
| `look at <x, y, z\|here>` | What block is here? |
| `find <block> [radius]` | Find blocks of a type |
| `set <block> <radius>` | Set a region of blocks |
| `replace <from> <to> [radius]` | Swap block types in a region |
| `terraform <smooth\|flatten\|raise\|lower> <radius>` | Terrain edits |
| `undo [minutes]` | Undo last edit / revert recent edits |

## Pillar 3 — Manage the server
| Command | What it does |
|---|---|
| `status` | Server + bot status (TPS/CPU/RAM/tier) |
| `cap` / `device-info` | Capability report / one-shot stats |
| `provider list\|set <name>` | AI backend per bot |
| `refresh` | Re-learn the server's command catalog |
| `confirm <CONF-token>` | Confirm a blocked systemic command |
| `admin <read\|set\|backup\|restore\|rollback\|reload\|menu> …` | Safe config editing (backup+validate+rollback) |
| `cmd <command> [; command; …]` | Run server commands as console (audited; systemic → CONF token) |

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
- **Pillar 2 upgrade:** `scan`/`find`/`look` emit **jsonspec** (`{name,palette,blocks[]}`) so the
  bot sees the world as data → better `edit`/`set`/`replace`/`terraform`/`undo`.
- **Pillar 3 upgrade:** `cmd` results captured from the server console into the chat-console.
