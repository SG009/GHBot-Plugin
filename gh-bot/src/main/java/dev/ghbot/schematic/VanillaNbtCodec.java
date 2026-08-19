package dev.ghbot.schematic;

import dev.ghbot.builder.VoxelModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Vanilla structure block format (.nbt) — usable in-game with structure
 * blocks / /structure load, no mods. Root: { author, size[3], palette
 * (list of {Name, Properties?}), blocks (list of {state, pos[3], nbt?}) }.
 * Uncompressed NBT (structure files are typically uncompressed).
 */
public class VanillaNbtCodec implements SchematicCodec {

    @Override public String formatName() { return "Vanilla structure"; }
    @Override public String fileExtension() { return ".nbt"; }

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

        List<Object> paletteNbt = new ArrayList<>();
        for (String name : paletteOrder) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("Name", "minecraft:" + name);
            paletteNbt.add(entry);
        }

        List<Object> blocks = new ArrayList<>();
        for (Map.Entry<Long, String> e : voxels.entrySet()) {
            int x = VoxelModel.xOf(e.getKey()) - minX;
            int y = VoxelModel.yOf(e.getKey()) - minY;
            int z = VoxelModel.zOf(e.getKey()) - minZ;
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("state", palette.get(e.getValue()));
            b.put("pos", new int[]{x, y, z});
            blocks.add(b);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("author", "gh-bot");
        root.put("size", new int[]{w, h, d});
        root.put("palette", paletteNbt);
        root.put("blocks", blocks);
        root.put("entities", new ArrayList<>());

        return NbtWriter.writeRoot("", root, false);
    }
}
