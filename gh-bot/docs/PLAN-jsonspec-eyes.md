# PLAN v2 — Pillar-2 "Eyes as Data" (jsonspec) + Hardening pass

Status: **proposal v2 — awaiting final GO before any code changes.**
Prepared: 2026-08-20 (Asia/Jakarta) · Branch: `arena/01a01f32-ghbot-plugin` · Base: v0.22.0 (9838db9)

---

## 0. Decision log (owner decisions folded in)

| # | Decision | Owner's call | Where it lands |
|---|---|---|---|
| D1 | Scan depth | **Surface-only by default + `scan --full [depth]`** | A2 |
| D2 | Inline token budget | **150 blocks** (was 400) — single tunable constant | A5 |
| D3 | Find/look routing | **Memory + `logs/eyes/` file + bounded inline JSON** | A2–A4 |
| D4 | Shelved code | **Freeze + document + hard-block** (flipped from "delete") | B6 |
| — | Scan semantics | **Box, not circle**: ±radius on X/Z around origin, full Y column read; data = surface snapshot | A2 |
| — | Vanilla constraints | **Enforce at the translation/validation layer** | B8 |

---

## 1. Part A — Eyes as data (the feature)

### A1. New `TerrainSpec` (same contract shape as `JsonBuildSpec`, absolute coords)

```json
{
  "name":   "scan@r20@86,64,262",
  "kind":   "scan",                          // scan | find | look
  "origin": { "x": 86, "y": 64, "z": 262 },  // absolute anchor (world coords)
  "palette": { "0": "minecraft:grass_block", "1": "minecraft:oak_log" },
  "blocks":  [ { "x": 86, "y": 64, "z": 262, "block": "0" } ]
}
```

- **Absolute world coordinates**; `origin` lets a later tool re-anchor/translate into a relative build spec.
- Dense integer `palette` keys; `blocks[]` entries accept a palette id **or** a raw block name (same rule as `JsonBuildSpec`).
- `toJson()` (hand-rolled, no Gson) + `toLine()` (the human summary we already have) + `toMap()`/`fromMap()` (Bukkit-YAML-safe persistence).

### A2. `scan` emits it — box semantics, surface data

- Geometry unchanged: **square box** ±radius on X/Z around the resolved origin, full column read top→bottom.
- Emitted data = **surface snapshot**: the top solid block per (x,z) column (the heightmap we already compute).
- `scan --full [depth]` → bounded full-column snapshot for `edit` planning (default depth 6, hard cap 64 — never the whole 384-tall column).
- Routing: bot memory (`terrain.spec`), file `logs/eyes/<name>.json` (full data), chat/tool reply = human `toLine()` + **bounded inline JSON** (A5).

### A3. `find` emits it

- `find <block>` → `TerrainSpec(kind:"find")` whose `blocks[]` are the found positions (existing 20-result cap).
- Same memory + file + bounded-inline routing. This is the enabler for exact edits: `find oak_log` → `replace` on exactly those coords.

### A4. `look` emits it

- `look at <x,y,z>` → `TerrainSpec(kind:"look")`, single-block `blocks[]`, and the full blockstate
  (`minecraft:oak_stairs[facing=north]`) kept as the palette value so orientation isn't lost.

### A5. AI surface stays token-safe (150-block cap)

- `ToolProtocol.helpText()` + `AutoTools` teach: "scan/find/look return compact JSON world data — use its coordinates directly, don't re-guess."
- **Inline cap = 150 blocks** (single constant `TerrainSpec.INLINE_MAX = 150`, trivially tunable), then
  `"truncated": true, "total": 1681, "file": "logs/eyes/…"`. Full fidelity on disk; bounded digest in context.

### A6. Round-trip with `JsonBuildSpec`

- `TerrainSpec.toBuildSpec()`: translate absolute→relative via `origin`, strip blockstate properties
  (same rule as `SchematicImporter`), drop any non-vanilla name (B8). Output feeds the existing
  ghost/`edit`/`replace` machinery — closing the "see → act" loop.
- Smoke: `TerrainSpec.toJson()` → `JsonBuildSpec.parse()` → same block count + coordinates.

### A7. Smoke tests (headless)

- shape round-trip; palette-id + raw-name both accepted; absolute↔relative translation via `origin`;
  bounded-inline truncation math (149/150/151 blocks); `--full` depth cap; `find`/`look` emit the shape.

---

## 2. Part B — Hardening

### B1. Fix `scan at <x> <y> <z>` (verified broken)
`TerrainCommands.scan` treats `at` as the "where" and `86` as the *radius*; `AutoTools` mangles the arg
vector too. Fix via the shared normalizer (B4) so `scan 100 at 86 86 262`, `scan 86 86 262`, and
`scan at 86,86,262` all land on the same coordinate.

### B2. `find that` / `find this` pronoun filter (verified broken)
`Material.matchMaterial("that")` → null → error. Fix: strip stopwords (`that|this|it|here|the`) from
the block arg; if still not a material, fall back to the **dominant top block from the last scan
context** and say so — no bare error.

### B3. Persist the heightmap (verified gap)
`TerrainSummary.toMap()` drops the heightmap (keeps only `topBlocks` + min/max). Fix: serialize a
(possibly downsampled) heightmap + `fromMap()` reader, so "foundation follows the ground" survives reload.

### B4. One coordinate parser for `set`/`replace`/`terraform`/`scan` (AI `at x y z` gap)
`CoordResolver.parseWhere(args…)` accepting `at <x> <y> <z>`, bare `<x> <y> <z>`, `x,y,z`, `here`,
player names — used by scan/look/find/set/replace/terraform. Smoke each form.

### B5. `ToolBridge` single source of truth
Remove the dead `@Deprecated LEGACY_ALLOWED` (still lists shelved commands). Smoke: `ALLOWED == CATALOG.keySet()`.

### B6. Shelved code → freeze + document + hard-block (NOT delete)
Per D4: `// SHELVED v0.22.0` header on each shelved class, one documented revive path, and a
`ShelvedSurface` smoke check asserting: shelved commands are absent from `CATALOG`/`toolSheet()`/
`AutoTools` **and** dispatch still hard-blocks every one of them.

### B7. Guardrail regression checks (verify, don't trust)
Add/confirm smoke checks: non-OP senders blocked at dispatch (admin-only); systemic `cmd`
(stop/reload/op/…) mints a `CONF-…` token instead of running; shelved commands rejected.

### B8. Vanilla block constraints (owner feedback — verified real gap)
- `JsonBuildSpec.validate()` + `parseWithDiagnostics()` now resolve every block name through
  `Material.matchMaterial` and report a diagnostic for any non-vanilla name (currently they only
  check coordinates + non-blank + count, so hallucinated names pass validation then get **silently
  skipped** at placement).
- `TerrainSpec.toBuildSpec()` strips properties + drops non-vanilla names defensively.
- Smoke: valid names pass; `"minecraft:not_a_block"` / gibberish produce a diagnostic;
  `toBuildSpec` never emits a non-vanilla name. (`Material.matchMaterial` is already used headless
  by the smoke suite, so no server needed.)

---

## 3. Order of work (each step green before the next)

1. **B-hardening** (B1–B5, B8) — small, safe, immediately useful to the owner's phone batch tests.
2. **A-feature** (A1–A7) on the now-normalized coordinate layer.
3. **B6/B7 guards + changelog**: bump `build.gradle.kts` version, §17 entry, full 384+ smoke green, ship jar to `releases/`.

---

## 4. Out of scope (this pass)
- Rendering eyes-specs in the 3D viewer (`view file …` already handles build specs — separate follow-up).
- FAWE fast-paste, Litematica import, `.mcstructure` export, viewer offline mode (existing backlog).
- Re-enabling any shelved feature.

---

## 5. Open question (only one)
- **D2 exact number:** default 150 inline blocks is fine, or do you want 200? (I'll use a single constant either way.)
