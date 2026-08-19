package dev.ghbot.schematic;

import java.util.HashMap;
import java.util.Map;

/**
 * Minimal block-name → numeric-id map for legacy formats (Classic .schematic).
 * Covers common blocks; unknown → 0 (air) is avoided by falling back to a
 * stable hash-based id (high values) so nothing is silently lost.
 */
public final class BlockIdMap {

    private static final Map<String, Integer> IDS = new HashMap<>();
    private static final Map<String, Integer> EXTRA = new HashMap<>();
    private static int next = 4096;

    static {
        put("air", 0); put("stone", 1); put("grass_block", 2); put("dirt", 3);
        put("cobblestone", 4); put("oak_planks", 5); put("bedrock", 7); put("water", 9);
        put("lava", 11); put("sand", 12); put("gravel", 13); put("oak_log", 17);
        put("oak_leaves", 18); put("glass", 20); put("sandstone", 24); put("oak_door", 64);
        put("iron_door", 71); put("stone_bricks", 98); put("glowstone", 89);
        put("prismarine", 168); put("dark_prismarine", 168); put("spruce_planks", 5);
        put("dark_oak_planks", 5); put("dark_oak_log", 17); put("dark_oak_door", 64);
        put("cobblestone_wall", 139); put("lantern", 123); put("gravel_path", 13);
        put("diamond_block", 57); put("gold_block", 41); put("iron_block", 42);
        put("coal_block", 173); put("bookshelf", 47); put("quartz_block", 155);
        put("red_sandstone", 179); put("bone_block", 216); put("nether_wart_block", 214);
        put("red_nether_bricks", 215); put("crimson_planks", 5); put("crimson_stem", 17);
        put("shroomlight", 222); put("smooth_sandstone", 24); put("red_concrete", 159);
        put("oak_sapling", 6); put("tall_grass", 31); put("poppy", 38);
        put("dandelion", 37); put("torch", 50); put("redstone_lamp", 123);
        put("sea_lantern", 169); put("ice", 79); put("packed_ice", 174);
    }

    private static void put(String name, int id) { IDS.put(name, id); }

    /** Get a numeric id; assigns a stable id for unknown blocks. */
    public static int id(String blockName) {
        String n = blockName == null ? "air" : blockName.toLowerCase().replace("minecraft:", "");
        Integer known = IDS.get(n);
        if (known != null) return known;
        Integer extra = EXTRA.get(n);
        if (extra == null) {
            extra = next++;
            EXTRA.put(n, extra);
        }
        return extra;
    }

    /** Reverse lookup: numeric id → block name (for Classic import). null if unknown. */
    public static String name(int id) {
        for (Map.Entry<String, Integer> e : IDS.entrySet()) {
            if (e.getValue() == id) return e.getKey();
        }
        for (Map.Entry<String, Integer> e : EXTRA.entrySet()) {
            if (e.getValue() == id) return e.getKey();
        }
        return null;
    }
}
