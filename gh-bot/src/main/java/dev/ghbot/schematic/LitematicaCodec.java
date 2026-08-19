package dev.ghbot.schematic;

import dev.ghbot.builder.VoxelModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Litematica (.litematic) — the structure-sharing standard. Root:
 *  Version=6, Metadata (Author, Name, ...), Regions { <name>: {
 *  Position int[3], Size int[3], BlockStatePalette (list of {Name,
 *  Properties?}), BlockStates (packed long[]), BlockStatePaletteSize,
 *  TileEntityList, EntityList } }. Gzip NBT.
 */
public class LitematicaCodec implements SchematicCodec {

    @Override public String formatName() { return "Litematica"; }
    @Override public String fileExtension() { return ".litematic"; }

    @Override
    public byte[] export(Map<Long, String> voxels, int minX, int minY, int minZ,
                         int w, int h, int d) throws Exception {
        List<String> paletteOrder = new ArrayList<>();
        Map<String, Integer> palette = new LinkedHashMap<>();
        for (Map.Entry<Long, String> e : voxels.entrySet()) {
            if (!palette.containsKey(e.getValue())) {
                palette.put(e.getValue(), palette.size());
                paletteOrder.add(e.getValue());
            }
        }

        // pack palette ids into longs (Litematica packs N ids of B bits each)
        int paletteSize = Math.max(1, palette.size());
        int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
        int perLong = 64 / bits;
        int total = w * h * d;
        int longsNeeded = (total + perLong - 1) / perLong;
        long[] blockStates = new long[longsNeeded];
        for (Map.Entry<Long, String> e : voxels.entrySet()) {
            int x = VoxelModel.xOf(e.getKey()) - minX;
            int y = VoxelModel.yOf(e.getKey()) - minY;
            int z = VoxelModel.zOf(e.getKey()) - minZ;
            if (x < 0 || x >= w || y < 0 || y >= h || z < 0 || z >= d) continue;
            int idx = (y * d + z) * w + x; // y,z,x order
            int longIdx = idx / perLong;
            int bit = (idx % perLong) * bits;
            blockStates[longIdx] |= ((long) palette.get(e.getValue()) & ((1L << bits) - 1)) << bit;
        }

        List<Object> paletteNbt = new ArrayList<>();
        for (String name : paletteOrder) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("Name", "minecraft:" + name);
            paletteNbt.add(entry);
        }

        Map<String, Object> region = new LinkedHashMap<>();
        region.put("Position", new int[]{minX, minY, minZ});
        region.put("Size", new int[]{w, h, d});
        region.put("BlockStatePalette", paletteNbt);
        region.put("BlockStates", blockStates);
        region.put("BlockStatePaletteSize", palette.size());
        region.put("TileEntityList", new ArrayList<>());
        region.put("EntityList", new ArrayList<>());
        region.put("PendingBlockTicks", new ArrayList<>());
        region.put("PendingFluidTicks", new ArrayList<>());

        Map<String, Object> regions = new LinkedHashMap<>();
        regions.put("gh-bot", region);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("Author", "gh-bot");
        metadata.put("Name", "gh-bot build");
        metadata.put("RegionCount", 1);
        metadata.put("TotalBlocks", voxels.size());
        metadata.put("TotalVolume", total);
        metadata.put("Description", "");
        metadata.put("TimeCreated", System.currentTimeMillis());
        metadata.put("TimeModified", System.currentTimeMillis());
        metadata.put("EnclosingSize", new int[]{w, h, d});

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("Version", 6);
        root.put("Metadata", metadata);
        root.put("Regions", regions);

        return NbtWriter.writeRoot("", root, true);
    }
}
