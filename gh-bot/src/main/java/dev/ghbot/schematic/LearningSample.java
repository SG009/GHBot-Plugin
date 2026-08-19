package dev.ghbot.schematic;

import dev.ghbot.builder.VoxelModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A learning sample — a design summary extracted from a schematic, stored in
 * the dataset so retrieval-augmented design (Phase 12) can ground on it.
 * Includes palette, size, block distribution, inferred style tags.
 */
public class LearningSample {

    public String name;
    public String file;
    public String format;
    public int width, height, length;
    public int blocks;
    public final Map<String, Integer> palette = new TreeMap<>();   // block -> count (desc)
    public final List<String> styleTags = new ArrayList<>();
    public String sourceUrl = "";
    /**
     * v0.21 — compressed structural fingerprint: foundation/roof dominant
     * materials, density, bbox, tallest point. Keeps RAG prompts tiny instead
     * of leaking full voxel matrices into the context window.
     */
    public String structure = "unknown";

    public int volume() { return width * height * length; }

    /** Solid block density (0..1) — "airy pavilion" vs "solid keep". */
    public double density() { return volume() <= 0 ? 0.0 : (double) blocks / volume(); }

    public static LearningSample from(VoxelModel model, String name, String file, String format) {
        LearningSample s = new LearningSample();
        s.name = name;
        s.file = file;
        s.format = format;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        Map<String, Integer> counts = new TreeMap<>();
        Map<String, Integer> base = new TreeMap<>();   // materials on the bottom layer
        Map<String, Integer> roof = new TreeMap<>();   // materials on the top layer
        for (Map.Entry<Long, String> e : model.entries()) {
            int x = VoxelModel.xOf(e.getKey()), y = VoxelModel.yOf(e.getKey()), z = VoxelModel.zOf(e.getKey());
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            counts.merge(e.getValue(), 1, Integer::sum);
        }
        for (Map.Entry<Long, String> e : model.entries()) {
            int x = VoxelModel.xOf(e.getKey()), y = VoxelModel.yOf(e.getKey()), z = VoxelModel.zOf(e.getKey());
            if (y == minY) base.merge(e.getValue(), 1, Integer::sum);
            if (y == maxY) roof.merge(e.getValue(), 1, Integer::sum);
        }
        s.width = minX == Integer.MAX_VALUE ? 0 : maxX - minX + 1;
        s.height = minY == Integer.MAX_VALUE ? 0 : maxY - minY + 1;
        s.length = minZ == Integer.MAX_VALUE ? 0 : maxZ - minZ + 1;
        s.blocks = model.size();
        // sort counts desc
        List<Map.Entry<String, Integer>> es = new ArrayList<>(counts.entrySet());
        es.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (var e : es) s.palette.put(e.getKey(), e.getValue());

        // v0.21 — compressed structural fingerprint (context-window friendly)
        if (s.width > 0 && s.height > 0 && s.length > 0) {
            String foundation = dominant(base);
            String roofMat = dominant(roof);
            s.structure = "foundation:" + (foundation == null ? "air" : foundation)
                    + " roof:" + (roofMat == null ? "air" : roofMat)
                    + " density:" + String.format("%.2f", s.density())
                    + " bbox:" + s.width + "x" + s.height + "x" + s.length
                    + " tallest:" + s.height
                    + " solid:" + s.blocks + "/" + s.volume();
        }

        // infer style tags
        if (counts.containsKey("prismarine") || counts.containsKey("dark_prismarine")
                || counts.containsKey("purpur_block")) s.styleTags.add("fantasy");
        if (counts.containsKey("cobblestone") || counts.containsKey("stone_bricks")
                || counts.containsKey("dark_oak_planks")) s.styleTags.add("medieval");
        if (counts.containsKey("quartz_block") || counts.containsKey("white_concrete")
                || counts.containsKey("smooth_quartz")) s.styleTags.add("modern");
        if (counts.containsKey("spruce_planks") || counts.containsKey("stripped_spruce_log")) s.styleTags.add("rustic");
        if (counts.containsKey("glass") || counts.containsKey("glass_pane")) s.styleTags.add("glassy");
        if (counts.containsKey("oak_leaves") || counts.containsKey("jungle_leaves")
                || counts.containsKey("spruce_leaves")) s.styleTags.add("nature");
        if (s.width > 30 || s.length > 30) s.styleTags.add("large");
        if (s.height > 15) s.styleTags.add("tall");
        if (counts.containsKey("redstone_wire") || counts.containsKey("repeater")
                || counts.containsKey("piston")) s.styleTags.add("redstone");
        if (s.styleTags.isEmpty()) s.styleTags.add("generic");
        return s;
    }

    public String oneLine() {
        return name + " · " + width + "×" + height + "×" + length + " · " + blocks + " blk · "
                + String.join(",", styleTags) + " · top: " + topPalette(3);
    }

    /**
     * v0.21 — the line injected into RAG prompts. Deliberately compact:
     * name, bbox, density, foundation/roof materials, style, top palette.
     * No voxel matrices — the LLM gets proportions + materials to riff on.
     */
    public String compactLine() {
        return name + " · " + width + "×" + height + "×" + length
                + " · density " + String.format("%.2f", density())
                + " · " + structure
                + " · styles " + String.join(",", styleTags)
                + " · palette " + topPalette(3);
    }

    private static String dominant(Map<String, Integer> counts) {
        String best = null; int bestN = -1;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestN) { best = e.getKey(); bestN = e.getValue(); }
        }
        return best;
    }

    public String topPalette(int n) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (Map.Entry<String, Integer> e : palette.entrySet()) {
            if (i++ >= n) break;
            if (sb.length() > 0) sb.append(" ");
            sb.append(e.getKey()).append(":").append(e.getValue());
        }
        return sb.toString();
    }
}
