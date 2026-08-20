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
        // v0.21.46 — write the REAL Sponge v3 format (was writing v2-shaped output):
        //   Palette = compound { "minecraft:blockstate" : int palette-id }
        //   BlockData = VARINT palette ids, xzy order.
        Map<String, Integer> palette = new LinkedHashMap<>();
        palette.put("air", 0);           // reserve id 0 = air
        for (Map.Entry<Long, String> e : voxels.entrySet()) {
            if (!palette.containsKey(e.getValue())) palette.put(e.getValue(), palette.size());
        }

        // varint-encode the palette ids (xzy order, index = (x*d+z)*h+y)
        int[] ids = new int[w * h * d];
        java.util.Arrays.fill(ids, 0);
        for (Map.Entry<Long, String> e : voxels.entrySet()) {
            int x = VoxelModel.xOf(e.getKey()) - minX;
            int y = VoxelModel.yOf(e.getKey()) - minY;
            int z = VoxelModel.zOf(e.getKey()) - minZ;
            if (x < 0 || x >= w || y < 0 || y >= h || z < 0 || z >= d) continue;
            ids[x + z * w + y * w * d] = palette.get(e.getValue());   // canonical: i = x + z*W + y*W*L
        }
        byte[] blockData = writeVarints(ids);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("Version", 3);
        root.put("DataVersion", 3955);
        root.put("Width", w);
        root.put("Height", h);
        root.put("Length", d);
        root.put("Offset", new int[]{minX, minY, minZ});

        Map<String, Object> paletteNbt = new LinkedHashMap<>();
        for (var en : palette.entrySet()) paletteNbt.put("minecraft:" + en.getKey(), en.getValue());
        root.put("Palette", paletteNbt);
        root.put("BlockData", blockData);
        root.put("BlockEntities", new ArrayList<>());
        root.put("Entities", new ArrayList<>());
        root.put("BiomeData", new byte[0]);

        return NbtWriter.writeRoot("Schematic", root, true);
    }

    /** v0.21.46 — varint-encode ints (Sponge v3 BlockData). */
    private static byte[] writeVarints(int[] ids) {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(ids.length);
        for (int v : ids) {
            while ((v & ~0x7F) != 0) { bos.write((v & 0x7F) | 0x80); v >>>= 7; }
            bos.write(v);
        }
        return bos.toByteArray();
    }
}
