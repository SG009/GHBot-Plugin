# GH-Bot — Full Command Reference (v0.21.43)

Source of truth: `BotCommands.CATALOG` (also feeds `/gh help`, `/api/tools`, and the AI tool sheet).
Prefix in-game: `@GH000 <cmd>` · from console: `gh <cmd>` · in web console: type it or use `/cmd`.

> **Status legend:** ✓ = verified live in batch tests · ✗ = known broken/stub · — = not yet tested
>
> **How to use this sheet:** every line is a real command. Mark `✓ tested` / `✗ broken` /
> `— untested` next to each as you batch-test, so the "many ingredients" list becomes a
> proven-workflow map. The core loop that's already proven: **upload → stage → review → approve/deny/export**.

---

## 1. Core & info (13)
| Command | What it does | Usage |
|---|---|---|
| `help` | All commands + usage | `help` |
| `chat` | Free conversation with GH-bot | `chat <msg>` |
| `design` | Guided conversational design session | `design <topic\|answer\|done>` |
| `view` | Open a build in the browser 3D viewer | `view [jobId] \| view file <name>` |
| `image` | Toggle AI concept-image preview | `image on\|off` |
| `status` ✓ | Server + bot status (TPS/CPU/RAM/tier) | `status` |
| `cap` | Capability report — what this device can handle | `cap` |
| `device-info` | One-shot stats & capability report | `device-info` |
| `memory` | Manage session memory | `memory clear` |
| `debuglog` | Toggle per-bot debug logs | `debuglog show\|hide` |
| `provider` | AI backend per bot | `provider list\|set <name>` |
| `refresh` | Re-learn the server's command catalog | `refresh commands` |
| `confirm` ✓ | Confirm a blocked systemic command by token | `confirm <CONF-token>` |

## 2. Terrain eyes (7)
| Command | What it does | Usage |
|---|---|---|
| `scan` ✓ | Terrain summary (ground, heightmap, blocks, water) | `scan <where\|here\|me> [radius]` |
| `look` ✓ | What block is here? | `look at <x, y, z\|here>` |
| `find` ✓ | Find blocks of a type | `find <block> [radius]` |
| `where` | Coordinates of a saved location | `where <name>` |
| `save-location` ⚠️ | Save the current spot as a named location | `save-location <name>` |
| `list-locations` | All saved locations | `list-locations` |
| `delete-location` | Remove a saved location | `delete-location <name>` |

## 3. Building (13)
| Command | What it does | Usage |
|---|---|---|
| `plan` ⚠️ | Text-only design preview (no blocks) | `plan <prompt>` |
| `build` | Design + stage a build (ghost, then approve) | `build <prompt> [--direct] [at <where>]` |
| `edit` | Modify an existing build by instruction | `edit <target\|here> <instruction>` |
| `editspec` | Preview the edit ops an instruction would generate | `editspec <target> <instruction>` |
| `schem` | Design + export a schematic | `schem <name> <prompt> [format\|all]` |
| `schem download` | Download a schematic from the internet | `schem download <name> <url>` |
| `schem import` ✓ (v0.21.45) | Import a schematic file into the dataset | `schem import <file> [name]` |
| `paste` ✓ (v0.21.45) | Paste a schematic in-world | `paste <file> [where]` |
| `library` ✓ | Browse the schematic library | `library` |
| `export` | Export the staged build as schematics | `export <name> [format\|all]` |
| `teach` | Add a library file or staged build to the dataset | `teach <name> [staged]` |
| `dataset` | Manage the learning dataset | `dataset list\|remove <name>\|clear` |
| `critique` | Ask GH-bot to critique the staged build | `critique` |

## 4. Review (4)
| Command | What it does | Usage |
|---|---|---|
| `approve` | Approve the staged build | `approve` |
| `deny` | Clear/deny the staged build | `deny` |
| `redo` | Re-stage the build (cleared + re-staged) | `redo` |
| `animate` | Toggle cinematic build pass | `animate on\|off` |

## 5. World editing (5)
| Command | What it does | Usage |
|---|---|---|
| `set` ⚠️ | Set a region of blocks | `set <block> <radius>` |
| `replace` ⚠️ | Swap block types in a region | `replace <from> <to> [radius]` |
| `terraform` | Terrain edits (smooth/flatten/raise/lower) | `terraform <smooth\|flatten\|raise\|lower> <radius>` |
| `undo` | Undo last edit / revert recent edits | `undo [minutes]` |
| `cancel` | Stop the current job | `cancel` |

## 6. Admin (3)
| Command | What it does | Usage |
|---|---|---|
| `admin` | Safe config editing (backup+validate+rollback) | `admin <read\|set\|backup\|restore\|rollback\|reload\|menu> …` |
| `cmd` ✓ | Run server commands as console (multi via `;`, systemic need confirm) | `cmd <command> [; command; …]` |
| `add` | Natural-language admin task (NPC, sign, …) | `add <thing> at <where>` |

## 7. Crews / avatar (5)
| Command | What it does | Usage |
|---|---|---|
| `deploy` | Deploy a new worker bot at runtime | `deploy <id> [role]` |
| `undeploy` | Remove a worker bot | `undeploy <id>` |
| `workers` | List all bots/workers and their roles | `workers` |
| `avatar` | Toggle the Enderman avatar | `avatar on\|off` |
| `marker` | Place a waypoint marker | `marker <name>` |

**Total: 50 entries (44 unique + sub-forms).**

---

## Web console surface (not slash-commands, still real actions)
- **📎 Upload** — `.json` build spec → staged directly; image → vision → spec → staged.
- **Secretary** — on-device local AI drafts a request → 📨 send to technician (executes).
- **`/cmd` bar** — run any guarded command from the browser.
- **Viewer buttons** — Approve / Deny / Export on the 3D preview (logged back into the console feed).
- **Quick chips** — status / scan / build / undo / tools (execute on the server directly).

## Plain-language auto-tools (type it, it runs — no command prefix needed)
`scan <r>`, `find <block> <r>`, `look <x y z>`, `build <prompt>`, `plan <p>`, `edit <t> <i>`, `status`,
`players`, `worlds`, `catalog <kw>`, `where <name>`, `list-locations`, `save-location <name>`,
`deploy/undeploy/workers`, `marker <name>`, `avatar`, `critique`, `undo`, `cancel`, `admin <op> …`,
`cmd <line>`, `confirm <CONF-token>`, plain words `deny` / `approve` / `redo`, `replace …`, `set …`,
`terraform …`, `schem …`, `paste …`.
