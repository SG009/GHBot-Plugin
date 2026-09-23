package dev.ghbot.schematic;

import dev.ghbot.builder.VoxelModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Importers — read schematic files back into a {@link VoxelModel}.
 * Supports: Sponge v2/v3 (.schem), Classic (.schematic), Vanilla structure (.nbt),
 * Litematica, Bedrock {@code .mcstructure} (v0.27.1 — little-endian).
 */
public final class SchematicImporter {

    private SchematicImporter() {}

    /** Import any supported format by sniffing the root keys. Returns null if unsupported. */
    public static VoxelModel importFile(byte[] data) throws Exception {
        // v0.27.1 — Bedrock .mcstructure is little-endian uncompressed NBT.
        // The Java NbtReader would misread name lengths (BE vs LE) and throw.
        if (LeNbtWriter.looksLittleEndian(data)) {
            try {
                NbtReader.Result le = LeNbtWriter.read(data);
                if (le.root().containsKey("format_version") && le.root().containsKey("structure")) {
                    return importMcstructure(le.root());
                }
            } catch (Exception ignored) {
                // fall through to the Java/gzip reader
            }
        }
        NbtReader.Result r = NbtReader.read(data);
        Map<String, Object> root = r.root();

        // v0.21.46 — Litematica (Version=6 + Regions) must be checked BEFORE the Sponge
        // "Version" branch (litematic root also has Version).
        if (root.containsKey("Regions") && root.get("Regions") instanceof Map<?, ?>) {
            return importLitematica(root);
        }
        if (root.containsKey("Version")) {
            Object v = root.get("Version");
            int version = v instanceof Integer ? (Integer) v : (v instanceof Byte ? (Byte) v : 0);
            if (version == 2) return importSponge(root, 2);
            if (version == 3) return importSponge(root, 3);
        }
        if (root.containsKey("Blocks") && root.containsKey("Width") && root.containsKey("Materials")) {
            return importClassic(root);
        }
        if (root.containsKey("palette") && root.containsKey("blocks") && root.containsKey("size")) {
            return importVanilla(root);
        }
        return null;
    }

    /** v0.21.45 — "minecraft:oak_stairs[facing=north]" → "oak_stairs" (drop namespace + properties). */
    private static String stripProps(String name) {
        if (name == null) return null;
        String s = name.trim().replace("minecraft:", "");
        int b = s.indexOf('[');
        if (b >= 0) s = s.substring(0, b);
        return s;
    }

    /**
     * v0.21.46 — Litematica (.litematic). Root: Version=6, Metadata, Regions{ name: {
     * Position int[3], Size int[3], BlockStatePalette (list of {Name,Properties?}),
     * BlockStates (packed long[]), BlockStatePaletteSize } }.
     * Packing: index = (y*d+z)*w+x, bits per block inferred from the array length.
     */
    private static VoxelModel importLitematica(Map<String, Object> root) {
        Map<?, ?> regions = (Map<?, ?>) root.get("Regions");
        if (regions == null || regions.isEmpty()) return null;
        Object first = regions.values().iterator().next();
        if (!(first instanceof Map<?, ?> region)) return null;

        // v0.21.46 — return RELATIVE coordinates (drop Position): the paste command anchors
        // the model at its own origin, so world-space offsets from the file are not applied.
        int[] size = region.get("Size") instanceof int[] s0 ? s0 : new int[]{0, 0, 0};
        // v0.22.2 — FIX: Litematica Size components are SIGNED (a region can extend in
        // the negative direction); the BlockStates array is always sized |w|·|h|·|d|.
        // Feeding negative values into the bit-length inference and loops decoded 0
        // blocks for many real-world .litematic files (AUDIT P1-2).
        int w = Math.abs(size[0]), h = Math.abs(size[1]), d = Math.abs(size[2]);
        List<?> pal = region.get("BlockStatePalette") instanceof List<?> pl ? pl : List.of();
        Object statesObj = region.get("BlockStates");
        if (!(statesObj instanceof long[] states)) return null;

        // palette index → block name (index 0 is conventionally air in litematica)
        java.util.Map<Integer, String> palette = new java.util.HashMap<>();
        for (int i = 0; i < pal.size(); i++) {
            if (pal.get(i) instanceof Map<?, ?> e) {
                Object name = e.get("Name");
                if (name != null) palette.put(i, stripProps(String.valueOf(name)));
            } else if (pal.get(i) instanceof String s) {
                palette.put(i, stripProps(s));
            }
        }

        // infer bits-per-block from the number of longs (robust for our exports AND real files)
        int total = w * h * d;
        int bits = 4;
        if (states.length > 0) {
            for (int b = 2; b <= 32; b++) {   // v0.21.46 — scan the full range (palettes can need 9..15 bits)
                int perLong = 64 / b;
                if ((total + perLong - 1) / perLong == states.length) { bits = b; break; }
            }
        }
        long mask = (1L << bits) - 1;
        int perLong = 64 / bits;

        VoxelModel m = new VoxelModel();
        for (int y = 0; y < h; y++)
            for (int z = 0; z < d; z++)
                for (int x = 0; x < w; x++) {
                    int idx = (y * d + z) * w + x;
                    int longIdx = idx / perLong;
                    if (longIdx >= states.length) continue;
                    int bit = (idx % perLong) * bits;
                    int pid = (int) ((states[longIdx] >>> bit) & mask);
                    String block = palette.get(pid);
                    if (block == null || block.equals("air")) continue;
                    m.set(x, y, z, block);
                }
        return m;
    }

    private static VoxelModel importSponge(Map<String, Object> root, int version) {
        int w = ((Number) root.get("Width")).intValue();
        int h = ((Number) root.get("Height")).intValue();
        int d = ((Number) root.get("Length")).intValue();
        // Sponge palette → index → block name:
        //  v2: Palette = LIST of compounds {Name, Properties}  (index = position in list)
        //  v3: Palette = COMPOUND { blockstate-string : int palette-id }  (v0.21.46 — REAL format)
        java.util.Map<Integer, String> palette = new java.util.HashMap<>();
        Object palObj = root.get("Palette");
        if (palObj instanceof java.util.List<?> palList) {
            for (int i = 0; i < palList.size(); i++) {
                Object e = palList.get(i);
                if (e instanceof java.util.Map<?, ?> em) {
                    Object name = em.get("Name");
                    if (name != null) palette.put(i, stripProps(String.valueOf(name)));
                } else if (e instanceof String s) {
                    palette.put(i, stripProps(s));
                }
            }
        } else if (palObj instanceof java.util.Map<?, ?> palMap) {
            for (var en : palMap.entrySet()) {
                String blockState = String.valueOf(en.getKey());   // key = blockstate string
                if (!(en.getValue() instanceof Number n)) continue;
                palette.put(n.intValue(), stripProps(blockState));
            }
        }

        byte[] blockData = (byte[]) root.get("BlockData");
        if (blockData == null) blockData = new byte[0];
        // v0.21.46 — Sponge v3 BlockData is VARINT-encoded palette ids (v2 is plain bytes)
        int[] ids = (version >= 3) ? readVarints(blockData, w * h * d) : plainBytes(blockData, w * h * d);

        VoxelModel m = new VoxelModel();
        // canonical Sponge order (research PDF): i = x + z*Width + y*Width*Length (X fastest)
        for (int y = 0; y < h; y++)
            for (int z = 0; z < d; z++)
                for (int x = 0; x < w; x++) {
                    int idx = x + z * w + y * w * d;
                    if (idx >= ids.length) continue;
                    String block = palette.get(ids[idx]);
                    if (block == null || block.equals("air")) continue;
                    m.set(x, y, z, block);
                }
        return m;
    }

    /** v0.21.46 — decode a varint array (Sponge v3 BlockData). */
    private static int[] readVarints(byte[] data, int expected) {
        int[] out = new int[expected];
        int oi = 0, i = 0;
        while (i < data.length && oi < expected) {
            int value = 0, shift = 0;
            byte b;
            do {
                if (i >= data.length) break;
                b = data[i++];
                value |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);
            out[oi++] = value;
        }
        return out;
    }

    private static int[] plainBytes(byte[] data, int expected) {
        int[] out = new int[expected];
        for (int i = 0; i < expected && i < data.length; i++) out[i] = data[i] & 0xFF;
        return out;
    }

    /** Classic: y,z,x order, Blocks byte[], Data byte[], Materials="Alpha". */
    private static VoxelModel importClassic(Map<String, Object> root) {
        int w = ((Number) root.get("Width")).intValue();
        int h = ((Number) root.get("Height")).intValue();
        int d = ((Number) root.get("Length")).intValue();
        byte[] blocks = (byte[]) root.get("Blocks");
        if (blocks == null) blocks = new byte[w * h * d];

        VoxelModel m = new VoxelModel();
        for (int y = 0; y < h; y++)
            for (int z = 0; z < d; z++)
                for (int x = 0; x < w; x++) {
                    int idx = (y * d + z) * w + x;
                    if (idx >= blocks.length) continue;
                    int id = blocks[idx] & 0xFF;
                    if (id == 0) continue;
                    String name = BlockIdMap.name(id);
                    if (name == null || name.equals("air")) continue;
                    m.set(x, y, z, name);
                }
        return m;
    }

    /**
     * v0.27.1 — Bedrock .mcstructure. Primary layer only (secondary is waterlogged);
     * {@code -1} and air skipped; palette names reverse-mapped to Java.
     */
    static VoxelModel importMcstructure(Map<String, Object> root) {
        if (root == null) return null;
        Object sizeObj = root.get("size");
        int[] size = xyz3(sizeObj);
        int w = Math.abs(size[0]), h = Math.abs(size[1]), d = Math.abs(size[2]);
        if (w == 0 || h == 0 || d == 0) return new VoxelModel();
        Object structureObj = root.get("structure");
        if (!(structureObj instanceof Map<?, ?> structure)) return null;
        Object layersObj = structure.get("block_indices");
        if (!(layersObj instanceof List<?> layers) || layers.isEmpty()) return null;
        Object primaryObj = layers.get(0);
        if (!(primaryObj instanceof List<?> primary)) return null;

        List<String> palette = new ArrayList<>();
        Object palRoot = structure.get("palette");
        if (palRoot instanceof Map<?, ?> palMap) {
            Object def = palMap.get("default");
            if (def instanceof Map<?, ?> defMap) {
                Object bp = defMap.get("block_palette");
                if (bp instanceof List<?> list) {
                    for (Object e : list) {
                        if (e instanceof Map<?, ?> em) {
                            Object name = em.get("name");
                            palette.add(name == null ? "air" : McstructureCodec.javaName(String.valueOf(name)));
                        } else {
                            palette.add("air");
                        }
                    }
                }
            }
        }

        VoxelModel m = new VoxelModel();
        int total = w * h * d;
        int n = Math.min(primary.size(), total);
        for (int i = 0; i < n; i++) {
            Object cell = primary.get(i);
            if (!(cell instanceof Number num)) continue;
            int pid = num.intValue();
            if (pid < 0 || pid >= palette.size()) continue;
            String block = palette.get(pid);
            if (block == null || McstructureCodec.isAir(block)) continue;
            int x = d == 0 || h == 0 ? 0 : i / (d * h);
            int rem = d == 0 || h == 0 ? i : i % (d * h);
            int y = d == 0 ? 0 : rem / d;
            int z = d == 0 ? 0 : rem % d;
            m.set(x, y, z, block);
        }
        return m;
    }

    /** size / origin: TAG_List of 3 ints (correct) or TAG_Int_Array (malformed-but-parseable). */
    private static int[] xyz3(Object o) {
        if (o instanceof int[] a && a.length >= 3) return new int[]{a[0], a[1], a[2]};
        if (o instanceof List<?> l && l.size() >= 3
                && l.get(0) instanceof Number && l.get(1) instanceof Number && l.get(2) instanceof Number) {
            return new int[]{((Number) l.get(0)).intValue(),
                    ((Number) l.get(1)).intValue(),
                    ((Number) l.get(2)).intValue()};
        }
        return new int[]{0, 0, 0};
    }

    /** Vanilla structure: palette (list of {Name}), blocks (list of {state, pos[3]}), size. */
    private static VoxelModel importVanilla(Map<String, Object> root) {
        List<?> palette = (List<?>) root.get("palette");
        List<?> blocks = (List<?>) root.get("blocks");
        VoxelModel m = new VoxelModel();
        for (Object b : blocks) {
            if (!(b instanceof Map<?, ?> bm)) continue;
            Object state = bm.get("state");
            Object pos = bm.get("pos");
            if (!(state instanceof Number st) || !(pos instanceof int[] p)) continue;
            int idx = st.intValue();
            if (idx < 0 || idx >= palette.size()) continue;
            Map<?, ?> entry = (Map<?, ?>) palette.get(idx);
            Object name = entry.get("Name");
            if (name == null) continue;
            String block = String.valueOf(name).replace("minecraft:", "");
            if (block.equals("air")) continue;
            m.set(p[0], p[1], p[2], block);
        }
        return m;
    }
}
