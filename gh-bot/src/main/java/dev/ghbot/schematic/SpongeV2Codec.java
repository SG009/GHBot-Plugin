package dev.ghbot.schematic;

import dev.ghbot.builder.VoxelModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sponge v2 (.schem) — the WorldEdit/FAWE standard. Structure:
 *  Version=2, DataVersion, Width/Height/Length, Palette (name→id),
 *  BlockData (palette ids, xzy order), BlockEntities, Entities.
 * Gzip-compressed NBT.
 */
public class SpongeV2Codec implements SchematicCodec {

    @Override public String formatName() { return "Sponge v2"; }
    @Override public String fileExtension() { return ".schem"; }

    @Override
    public byte[] export(Map<Long, String> voxels, int minX, int minY, int minZ,
                         int w, int h, int d) throws Exception {
        // Palette: name -> id (sorted by first occurrence, stable)
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

        byte[] blockData = new byte[w * h * d];
        java.util.Arrays.fill(blockData, (byte) 0);
        // xzy order: index = (x * d + z) * h + y
        for (Map.Entry<Long, String> e : voxels.entrySet()) {
            int x = VoxelModel.xOf(e.getKey()) - minX;
            int y = VoxelModel.yOf(e.getKey()) - minY;
            int z = VoxelModel.zOf(e.getKey()) - minZ;
            if (x < 0 || x >= w || y < 0 || y >= h || z < 0 || z >= d) continue;
            int idx = (x * d + z) * h + y;
            blockData[idx] = (byte) (palette.get(e.getValue()) & 0xFF);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("Version", 2);
        root.put("DataVersion", 3955); // 1.21.11-ish
        root.put("Width", w);
        root.put("Height", h);
        root.put("Length", d);

        List<Object> paletteNbt = new ArrayList<>();
        for (String name : paletteOrder) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("Name", "minecraft:" + name);
            paletteNbt.add(entry);
        }
        root.put("Palette", paletteNbt);
        root.put("BlockData", blockData);
        root.put("BlockEntities", new ArrayList<>());
        root.put("Entities", new ArrayList<>());

        return NbtWriter.writeRoot("Schematic", root, true);
    }
}
