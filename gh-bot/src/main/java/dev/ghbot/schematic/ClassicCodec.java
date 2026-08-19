package dev.ghbot.schematic;

import dev.ghbot.builder.VoxelModel;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Classic .schematic (MCEdit/legacy WorldEdit). Format:
 *  short Width, Height, Length, String Materials ("Alpha"), byte[] Blocks,
 *  byte[] Data, List<Map> Entities, List<Map> TileEntities. Gzip NBT.
 */
public class ClassicCodec implements SchematicCodec {

    @Override public String formatName() { return "Classic"; }
    @Override public String fileExtension() { return ".schematic"; }

    @Override
    public byte[] export(Map<Long, String> voxels, int minX, int minY, int minZ,
                         int w, int h, int d) throws Exception {
        byte[] blocks = new byte[w * h * d];
        byte[] data = new byte[w * h * d];
        java.util.Arrays.fill(blocks, (byte) 0);

        for (Map.Entry<Long, String> e : voxels.entrySet()) {
            int x = VoxelModel.xOf(e.getKey()) - minX;
            int y = VoxelModel.yOf(e.getKey()) - minY;
            int z = VoxelModel.zOf(e.getKey()) - minZ;
            if (x < 0 || x >= w || y < 0 || y >= h || z < 0 || z >= d) continue;
            int idx = (y * d + z) * w + x; // classic order: y,z,x
            int id = BlockIdMap.id(e.getValue());
            blocks[idx] = (byte) (id & 0xFF);
            data[idx] = (byte) ((id >> 8) & 0xFF);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("Width", (short) w);
        root.put("Height", (short) h);
        root.put("Length", (short) d);
        root.put("Materials", "Alpha");
        root.put("Blocks", blocks);
        root.put("Data", data);
        root.put("Entities", new java.util.ArrayList<>());
        root.put("TileEntities", new java.util.ArrayList<>());

        return NbtWriter.writeRoot("Schematic", root, true);
    }
}
