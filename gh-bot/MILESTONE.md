# GH-Bot Milestone — "Cook the Goal"

Synced 2026-08-12 (Asia/Jakarta). This is the **contract**: what "GHBot is done" means, in
testable terms, and the ONE real project we cook first. Everything else is support.

## The definition of "done" (user's words, locked)

- **BOTH:** (a) the feature checklist in `gh-bot/README.md` verified green on the phone server
  (zero crashes, clean logs), AND (b) **one real project end-to-end**: the GH-Lounge spawn hub.
- **Primary use:** designing / building / editing in the world (the in-game half).
- **Autonomy:** **approve everything important** — builds AND admin changes staged for the user's
  confirmation. Nothing big happens silently.
- **Viewer / logs / 3D:** the minimal-cost shulkr-style 3D viewer is the review surface; the
  WIB-timestamped logs (`logs/*`) are the tracking + debugging surface.

## Milestone 1 — GH-Lounge spawn hub (the proof)

### Context (researched)
- Current world: **Forest BedWars** by BreadBuilds (minecraft-schematics #18008; planetminecraft
  project). Forest-themed **floating islands**, 200×200×100, 4 team colors, VoxelSniper+FAWE build,
  nature-heavy.
- So the hub must feel at home: **forest palette** (oak/dark-oak logs, spruce, leaves, moss, stone,
  paths, lanterns), **island-friendly layout** (it can sit on/merge with an island), **natural
  terrain adaptation** (scan → foundation follows the ground), NOT a floating modern box.

### The job (one command, user-facing)
```
@GH000 build the GH-Lounge spawn hub at <hub spot>
```
or via the web console / secretary:
> "draft: build a forest-style GH-Lounge hub with a central plaza, lounge seating, a welcome
> sign, and lantern-lit paths — on the forest island at spawn."

### Done criteria (all must hold, else we debug)
1. **Design**: GH-bot produces a DesignSpec with a forest palette + a coherent layout
   (plaza/center, seating, sign, paths). Showed to the user as text plan + **3D preview** (`@GH000 view`).
2. **Approve**: user approves from the **browser viewer** (or `approve` in-game). No silent building.
3. **Build**: blocks placed tick-budgeted on the phone, TPS stays playable, progress shown.
4. **Result check**: user walks/orbits the finished hub; if wrong → `edit <instruction>` fixes it
   (drift guard + anchors active), then re-approve.
5. **Export**: `@GH000 export gh-lounge all` writes the schematic formats (Sponge v2/v3, Classic,
   Litematica, Vanilla .nbt) to `schematics/`.
6. **Teach**: `@GH000 teach gh-lounge` adds it to the learning dataset (so future builds match style).
7. **Logs clean**: `logs/edits.log`, `logs/commands.log`, `logs/admin.log` show token-tracked,
   reversible entries; no exceptions in `latest.txt`.
8. **Phone-safe**: stays within the capability estimate (build in phases/sections if the hub is big —
   plan supports sections; keep per-job blocks phone-friendly with TPS auto-pause).

### How we'll execute (when user says GO)
1. Pick the hub spot in-game / via `@GH000 scan here` on the island; save it (`save-location hub`).
2. First build is a **small footprint hub** (~within phone budget) to prove the loop end-to-end,
   then iterate bigger via `edit` (add wings, paths, lanterns, a tower) — each change previewed + approved.
3. Any failure = debug from `latest.txt` + screenshots, phase-by-phase, before continuing.

## Milestone 2 — Server admin runbook (after hub is green)

A scripted, user-approved admin sequence, all staged for confirmation (matches "approve everything"):
1. **MOTD** → `admin set server.properties motd "…"` → token → user confirms → restart note.
2. **LuckPerms ranks** → `cmd lp creategroup PRO; …; lp group PRO meta addprefix …` (multi-step,
   each line audited; user confirms the batch).
3. **DeluxeMenus menu** → `admin menu shop "&aShop"` → `/dm reload` (guarded, auto-rollback on fail).
4. **NauticalRank resource pack** → `admin set server.properties resource-pack <url>` + rank prefix
   badges — user supplies the exact symbol codes from the pack docs (AI never invents them).
5. Every step: backup + token + rollback-able; a one-command undo trail end-to-end.

## "Approve everything" implementation note (v0.21.7+ spec)
- Builds: already staged (ghost + web approve). ✅
- Admin: currently auto-applies with rollback tokens. To honor "approve everything important",
  add a **staged-admin mode** (`admin.confirm: true`): `admin set` prints a token and waits for
  `@GH000 confirm <token>` instead of applying instantly. Builds after Milestone 1 proves the loop.

## Not in scope for these milestones (keep on the shelf)
- FAWE fast-paste hook, Litematica import, `.mcstructure` export, viewer local-mode — later.
- Physical-bot features (farm/smelt/wander) — retired by design (§13 backlog).
