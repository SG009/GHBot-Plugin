# Research — `.mcstructure` export + FAWE fast-paste (Phase E item 3)

Date: 2026-09-23. Decision: **ship `.mcstructure` export in GHBot; do NOT depend on FAWE.**

## `.mcstructure` (Bedrock structure block)

Sources: Bedrock wiki `nbt/mcstructure.md` (Bedrock-OSS), tryashtar gist, Minecraft Wiki "Bedrock Edition level format/Other data format".

| | Bedrock `.mcstructure` | Java `.nbt` |
|---|---|---|
| Endianness | **Little-endian** | Big-endian |
| Compression | **None** | Gzip typical |
| Size field | **TAG_List of 3 ints** | TAG_Int_Array or list |
| Blocks | 2 palette-index layers, **ZYX** (`i = SZ·SY·X + SZ·Y + Z`) | list of `{pos, state}` |
| Empty cell | index **-1** (structure void — existing block kept) | omitted |
| Palette | `{name, states{}, version}` under `palette.default` | `{Name, Properties?}` |

`format_version` is currently always `1`. Block `version` is a packed game version; 18168865 = 1.21.60.33 (wiki 2025). Out-of-range palette indexes become air on load; missing `default` palette places nothing. Vanilla save limit is 64×256×64 but larger files still load.

**Java→Bedrock names:** 1.21 flattening made most identifiers match (`oak_planks`, stairs, …). Persistent divergences we remap: `grass_block→grass`, `dirt_path→grass_path`, `cobweb→web`, `melon→melon_block`, `bricks→brick_block`, `nether_bricks→nether_brick`, `red_nether_bricks→red_nether_brick`, `magma_block→magma`, `spawner→mob_spawner`. Unknown names are emitted as `minecraft:<java>` — Bedrock places air if it does not know them. GHBot voxels have no blockstates, so `states` is `{}` and the waterlogged second layer is all `-1`.

**Why a new writer:** `NbtWriter` is big-endian (and gzip-optional). Bedrock rejects BE files even uncompressed. `size`/`structure_world_origin` MUST be TAG_List — TAG_Int_Array is a silent load failure.

**Import:** GHBot can round-trip its own exports (LE reader + reverse remap) so `paste foo.mcstructure` on the Java server restages the ghost. A file produced by a Bedrock structure block with Bedrock-only blocks / extra NBT (block entities, pending ticks, entities) will lose that extra data — honest.

## FAWE fast-paste — research only, no code

FAWE (IntellectualSites) is a WorldEdit *replacement* (do not run both). Speed comes from an async streaming queue: blocks dispatch as they become available instead of buffering the whole edit + undo history in RAM (vanilla WorldEdit's OOM path). Queue knobs (`max-wait-ms`, etc.) trade latency vs memory. The public API is the WorldEdit API used asynchronously; "additional formats (e.g. Structure Blocks)" means **Java** structure blocks, not Bedrock `.mcstructure`.

**Why GHBot will not call FAWE on this server:**

1. **Hardware.** Owner's box is a 6 GB phone (Termux/proot, aarch64) already running Paper 1.21.11 + Geyser + Floodgate + Via* + DiscordSRV + Essentials + Vault. FAWE's queue still needs headroom; Geyser is the hungry one. Adding FAWE is an OOM risk, not a speedup.
2. **Undo / review bypass.** GHBot pastes go through ghost → approve → `BlockEditService` (capped placer, undo snapshots, v0.27.0 drift-guard). Routing through FAWE would skip all of that.
3. **We already speak FAWE's language.** `export` writes Sponge v2/v3 `.schem` — the file FAWE/`//schematic paste` already eats. If the owner later runs a dedicated creative box *with* FAWE, they paste our `.schem` there. No API bridge required.
4. **Optional future (not this ship):** detect FAWE and offer an expert `fawe paste` that **warns** it bypasses undo/ghost. Not on a phone.

**Recommendation:** keep GHBot's own capped placer. Do not add FAWE as a dependency, softdepend, or classpath probe in v0.27.1.
