# AUDIT — Pillar 1 (Build) & Pillar 2 (Eyes+Edit) go/no-go before Pillar 3

Status: **audit complete — verdict: GO (with 2 mandatory small fixes bundled into v0.22.2).**
Prepared: 2026-08-23 (Asia/Jakarta) · Base: v0.22.1 + doc-sync (`c6139c2`).
Method: systematic code review of the Pillar-1/2 surface (~7k LOC), tracing every code path against
docs claims and hunting the same class of "invisible path" bug found in the Pillar-3 research.

---

## Verdict

| Pillar | Verdict | Why |
|---|---|---|
| **1 — Build** | **GO, after P1-1 + P1-2 fixes** (small, bundled in v0.22.2) | Core pipeline (parse→stage→review→place, cancel-safe, tick-budgeted, bounded caps) is solid. One real HIGH bug in the no-AI template fallback + one real-world Litematica bug. |
| **2 — Eyes+Edit** | **GO** | Scan/scanSpec/find/set/replace sound, radius-clamped, snapshot-threaded. Drift guard exists for `edit` but **not** for `undo` (open question Q1). One doc drift (terraform). |

Nothing architectural blocks Pillar 3. The web-console **has-no-auth posture** (0.0.0.0:8580) is the
one systemic note — Pillar 3 makes server output MORE visible, so a config opt-in token is
recommended as a separate decision (Q3).

---

## Pillar 1 findings

### P1-1 — HIGH · template-edit bbox index transposition (bug confirmed live)
`EditCommands.templateEditSpec` treats the bbox array as `{minX, minZ, minY, maxX, maxZ, maxY}`,
but `bbox()` returns `{minX, minY, minZ, maxX, maxY, maxZ}` (indices 1↔2 and 4↔5 swapped). Every
bbox-anchored template op (`columns`, `roof`, `door`, `window`, `tree`) misplaces the Y/Z extents
whenever the structure's height ≠ depth. Verified numerically (9w×6h×7d house → roof at y=7 instead
of 6+1; roof center z=2.5 vs 3; columns at z=5 instead of 6).
**Why smoke never caught it:** the fixtures are Y/Z-symmetric (`drifted` = 10×10×10 cube → swap is
an identity; the other fixture is a 3×1×1 line). The swap is invisible to all existing checks.
**Impact path:** no-AI fallback (rule-based) — i.e. the default when no AI key is configured.
**Fix (v0.22.2):** correct the index mapping; add asymmetric fixtures (9×6×7) with position
assertions to SmokeTest.

### P1-2 — MEDIUM · Litematica import breaks on negative `Size`
Real Litematica regions frequently carry NEGATIVE Size components. `importLitematica` uses raw
`w*h*d` for bit-length inference and loop bounds → negative sizes import 0 blocks / mis-decode.
Also: backlog text ("paste of .litematic still unsupported") is **stale** — single-region positive
import shipped in v0.21.46 (bit-index order and LSB-first packing verified correct vs the format).
**Fix (v0.22.2):** `Math.abs` on all three components + negative-size smoke case. Multi-region
stays documented simplification.

### P1-3 — MEDIUM-LOW · `paste`/`schem import` path traversal surface
`schematics.dir().resolve(file)` with no normalize/confine — `../../…` escapes the library.
Admin-only + unauthenticated web console ⇒ any device on the network can attempt arbitrary local
file reads (impact limited: only a successful schematic parse yields data back).
**Fix (v0.22.2):** normalize + confine to the dir + extension allowlist; smoke rejects `../`.

### P1-4 — LOW · `/upload` size guard after full read
25 MB cap is applied AFTER `readAllBytes()` buffers the whole body — unbounded-body OOM window on
the phone. **Fix (v0.22.2):** stream-bounded read (fail at cap+1 bytes).

### P1-5 — LOW · viewer `data.json` re-serialized per request
Each GET re-renders the JSON (100k blocks ≈ MBs). One-shot viewer loads make this tolerable;
cache string per job when easy. (Backlog note, not blocking.)

### P1-6 — VERIFIED HEALTHY (recorded so they don't get re-audited)
- `JsonBuildSpec`: hard block cap (100k) with explicit errors; vanilla-material constraint (v0.22.1).
- Ghost/Build services: tick-budgeted placement, cancel flags working, `ConcurrentHashMap` state,
  TPS auto-pause exists; undo stack bounded (50 snapshots, config).
- PreviewRegistry: max-jobs eviction ✓. Export names sanitized (`[^A-Za-z0-9_-]` → `_`) ✓.
- `/upload`: 25 MB cap ✓ (see P1-4 for the read-order nit), `.json` staged without AI ✓,
  vision chain reports honest provider errors ✓.

## Pillar 2 findings

### P2-1 — MEDIUM · `undo` has NO drift guard (open question Q1)
`edit` fingerprints the region before/after planning (v0.21) — but `undo` replays
`setType(oldType)` blindly over positions whose *current* block may have been re-placed by a player
after the operation. Sequence: bot replaces A→B, player builds C at the spot, `undo` destroys C.
Options: (a) keep as-is (owner can inspect `logs/edits.log`), (b) skip-and-report positions whose
current ≠ op's newType. **Recorded as Q1 — default (b) recommended.**

### P2-2 — MEDIUM-LOW · undo fidelity is type-only
`Change` stores `Material` (no BlockData) → blockstates (stairs facing, wall sign text) and
container contents are not restored by undo (contents pop to items when replaced — vanilla physics).
Acceptable for terraform-level ops; **document** in COMMANDS.md.

### P2-3 — DOC DRIFT · terraform modes promised but not implemented
COMMANDS.md advertises `terraform <smooth|flatten|raise|lower>`; code implements
`[on|off|flatten <radius> [block]|status]` only. **Fix (v0.22.2):** COMMANDS.md tells the truth
(smooth/raise/lower → backlog if wanted).

### P2-4 — LOW · region scans on the caller thread
`replace` candidate-gather + `EditService.snapshot` + `scanSpec` iterate `getBlockAt` on the calling
thread (async for chat-event paths). Paper tolerates async reads on loaded chunks; chunks are
force-loaded via `captureSnapshots` first. Working as designed; keep radius caps as the guard.

### P2-5 — LOW · undo stack map vs deque race
`UndoManager` uses ConcurrentHashMap but plain `ArrayDeque` per bot — two concurrent undos could
interleave. Admin single-user ⇒ recorded, no fix.

### P2-6 — VERIFIED HEALTHY
- Radius clamps everywhere: scan/find ≤ 200, edit ≤ 60, replace/terraform ≤ `max-region: 20000`.
- Scan snapshot pipeline: main-thread `ChunkSnapshot` capture → async-safe iteration ✓
  (the correct Paper pattern).
- Heightmap persistence (v0.22.1) round-trips; downsample bound keeps session YAML sane.
- `TerrainSpec` INLINE_MAX cap + eyes routing (memory/file/digest) as documented.
- `CoordResolver` unified parsing; pronoun fallback present.

## Open questions (owner decisions, NOT blocking v0.22.2)

| # | Question | Recommendation |
|---|---|---|
| Q1 | `undo` drift-guard: skip positions changed since the op (report skipped count)? | Yes (b) — but it's a behavior change; owner confirms |
| Q2 | Terraform `smooth/raise/lower`: implement, or doc-truth (COMMANDS.md → flatten only)? | Doc-truth now; implement later if wanted |
| Q3 | Web console auth token (config opt-in) given 0.0.0.0 + richer output after Pillar 3 | Recommend opt-in `web.token` check on all routes |

## Bundled into v0.22.2 (with Pillar 3 capture)
P1-1 fix + asymmetric smoke fixtures · P1-2 abs + negative-size smoke · P1-3 paste/import
confinement + smoke · P1-4 bounded upload read · P2-3 doc-truth (COMMANDS.md) · P2-2 doc note ·
stale backlog line corrected (Litematica import) · then Pillar 3 per PLAN doc (D1 full scope,
D2 later, D3 budgets as proposed, D4 no settle, D5 capture confirmed).
