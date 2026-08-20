package dev.ghbot.schematic;

import dev.ghbot.builder.VoxelModel;

import java.util.List;
import java.util.Map;

/**
 * Importers — read schematic files back into a {@link VoxelModel}.
 * Supports: Sponge v2/v3 (.schem), Classic (.schematic), Vanilla structure (.nbt).
 * (Litematica reader is more involved — lands later.)
 */
public final class SchematicImporter {

    private SchematicImporter() {}

    /** Import any supported format by sniffing the root keys. Returns null if unsupported. */
    public static VoxelModel importFile(byte[] data) throws Exception {
        NbtReader.Result r = NbtReader.read(data);
        Map<String, Object> root = r.root();

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

    /** Sponge v2/v3: Width/Height/Length, Palette (list), BlockData (byte[] palette ids, xzy). */
    private static VoxelModel importSponge(Map<String, Object> root, int version) {
        int w = ((Number) root.get("Width")).intValue();
        int h = ((Number) root.get("Height")).intValue();
        int d = ((Number) root.get("Length")).intValue();
        // v0.21.44 — Sponge v2 stores Palette as a LIST, v3 as a COMPOUND (index → blockstate).
        // The old code only handled the list and threw ClassCastException on .schem (v3) imports.
        java.util.Map<Integer, String> palette = new java.util.HashMap<>();
        Object palObj = root.get("Palette");
        if (palObj instanceof java.util.List<?> palList) {
            for (int i = 0; i < palList.size(); i++) {
                Object e = palList.get(i);
                if (e instanceof java.util.Map<?, ?> em) {
                    Object name = em.get("Name");
                    if (name != null) palette.put(i, String.valueOf(name).replace("minecraft:", ""));
                }
            }
        } else if (palObj instanceof java.util.Map<?, ?> palMap) {
            for (var en : palMap.entrySet()) {
                int idx;
                if (en.getKey() instanceof Number n) idx = n.intValue();
                else if (en.getKey() instanceof String s) {
                    try { idx = Integer.parseInt(s.trim()); } catch (NumberFormatException nfe) { continue; }
                } else continue;
                Object v = en.getValue();
                if (v instanceof java.util.Map<?, ?> vm) {
                    Object name = vm.get("Name");
                    if (name != null) palette.put(idx, String.valueOf(name).replace("minecraft:", ""));
                }
            }
        }
        byte[] blockData = (byte[]) root.get("BlockData");
        if (blockData == null) blockData = new byte[w * h * d];

        VoxelModel m = new VoxelModel();
        for (int x = 0; x < w; x++)
            for (int z = 0; z < d; z++)
                for (int y = 0; y < h; y++) {
                    int idx = (x * d + z) * h + y;
                    if (idx >= blockData.length) continue;
                    int pid = blockData[idx] & 0xFF;
                    String block = palette.get(pid);
                    if (block == null || block.equals("air")) continue;
                    m.set(x, y, z, block);
                }
        return m;
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
