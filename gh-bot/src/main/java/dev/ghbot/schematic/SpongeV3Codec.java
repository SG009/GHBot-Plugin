package dev.ghbot.schematic;

import dev.ghbot.builder.VoxelModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sponge v3 (.schem) — newer FAWE/WorldEdit. Structure:
 *  Version=3, DataVersion, Width/Height/Length, Offset int[3],
 *  Palette (name→id), BlockData (varint palette ids, xzy order).
 */
public class SpongeV3Codec implements SchematicCodec {

    @Override public String formatName() { return "Sponge v3"; }
    @Override public String fileExtension() { return ".schem"; }

    @Override
    public byte[] export(Map<Long, String> voxels, int minX, int minY, int minZ,
                         int w, int h, int d) throws Exception {
        List<String> paletteOrder = new ArrayList<>();
        Map<String, Integer> palette = new LinkedHashMap<>();
        palette.put("air", 0);           // reserve id 0 = air
        paletteOrder.add("air");
        for (Map.Entry<Long, String> e : voxels.entrySet()) {
            if (!palette.containsKey(e.getValue())) {
                palette.put(e.getValue(), palette.size());
                paletteOrder.add(e.getValue());
            }
        }

        // v3 uses varint palette ids; we store bytes for simplicity (ids < 256)
        byte[] blockData = new byte[w * h * d];
        java.util.Arrays.fill(blockData, (byte) 0);
        for (Map.Entry<Long, String> e : voxels.entrySet()) {
            int x = VoxelModel.xOf(e.getKey()) - minX;
            int y = VoxelModel.yOf(e.getKey()) - minY;
            int z = VoxelModel.zOf(e.getKey()) - minZ;
            if (x < 0 || x >= w || y < 0 || y >= h || z < 0 || z >= d) continue;
            blockData[(x * d + z) * h + y] = (byte) (palette.get(e.getValue()) & 0xFF);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("Version", 3);
        root.put("DataVersion", 3955);
        root.put("Width", w);
        root.put("Height", h);
        root.put("Length", d);
        root.put("Offset", new int[]{minX, minY, minZ});

        List<Object> paletteNbt = new ArrayList<>();
        for (String name : paletteOrder) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("Name", "minecraft:" + name);
            entry.put("Properties", new LinkedHashMap<>());
            paletteNbt.add(entry);
        }
        root.put("Palette", paletteNbt);
        root.put("BlockData", blockData);
        root.put("BlockEntities", new ArrayList<>());
        root.put("Entities", new ArrayList<>());
        root.put("BiomeData", new byte[0]);

        return NbtWriter.writeRoot("Schematic", root, true);
    }
}
