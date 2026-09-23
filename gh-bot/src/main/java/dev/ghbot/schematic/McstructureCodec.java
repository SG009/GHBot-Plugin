package dev.ghbot.schematic;

import dev.ghbot.builder.VoxelModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Bedrock Edition {@code .mcstructure} — uncompressed little-endian NBT.
 *
 * <p>Spec (Bedrock wiki / tryashtar gist, cross-checked 2026-09):
 * <ul>
 *   <li>{@code format_version}=1</li>
 *   <li>{@code size} = LIST of 3 ints (NOT TAG_Int_Array — Bedrock refuses those)</li>
 *   <li>{@code structure.block_indices} = two layers of palette indexes, ZYX
 *       ({@code i = SZ·SY·X + SZ·Y + Z}); {@code -1} = structure void</li>
 *   <li>{@code structure.palette.default.block_palette} = list of
 *       {@code {name, states{}, version}}</li>
 *   <li>{@code structure_world_origin} = LIST of 3 ints</li>
 * </ul>
 *
 * <p>Java→Bedrock name remaps cover the well-known persistent divergences
 * (grass_block→grass, cobweb→web, …). 1.21 Bedrock flattening means most
 * other names ({@code oak_planks}, stairs, …) already match. Unknown names
 * are emitted as {@code minecraft:&lt;java&gt;} — Bedrock will place air if
 * it does not recognise them. Block states are not carried (GHBot voxels
 * are material names only); waterlogged second-layer is left all {@code -1}.
 *
 * <p>v0.27.1 — Phase E item 3.
 */
public class McstructureCodec implements SchematicCodec {

    /** Packed Bedrock 1.21.60.33 (wiki 2025: 18168865 = 0x01153C21). */
    public static final int BLOCK_VERSION = 18168865;
    public static final int FORMAT_VERSION = 1;

    @Override public String formatName() { return "mcstructure"; }
    @Override public String fileExtension() { return ".mcstructure"; }

    /** ZYX index (Z fastest) as Bedrock stores {@code block_indices}. */
    public static int index(int x, int y, int z, int w, int h, int d) {
        return d * h * x + d * y + z;
    }

    /**
     * Java 1.13+ material name → Bedrock identifier (no namespace).
     * Strips {@code minecraft:} and {@code [blockstate]}.
     */
    public static String bedrockName(String javaName) {
        String s = strip(javaName);
        return switch (s) {
            case "grass_block" -> "grass";
            case "dirt_path" -> "grass_path";
            case "cobweb" -> "web";
            case "melon" -> "melon_block";
            case "bricks" -> "brick_block";
            case "nether_bricks" -> "nether_brick";
            case "red_nether_bricks" -> "red_nether_brick";
            case "magma_block" -> "magma";
            case "spawner" -> "mob_spawner";
            default -> s;
        };
    }

    /** Inverse of {@link #bedrockName} so a round-trip paste on Java lands. */
    public static String javaName(String bedrockName) {
        String s = strip(bedrockName);
        return switch (s) {
            case "grass" -> "grass_block";
            case "grass_path" -> "dirt_path";
            case "web" -> "cobweb";
            case "melon_block" -> "melon";
            case "brick_block" -> "bricks";
            case "nether_brick" -> "nether_bricks";
            case "red_nether_brick" -> "red_nether_bricks";
            case "magma" -> "magma_block";
            case "mob_spawner" -> "spawner";
            default -> s;
        };
    }

    static boolean isAir(String name) {
        String s = strip(name);
        return s.isEmpty() || s.equals("air") || s.equals("cave_air")
                || s.equals("void_air") || s.equals("structure_void");
    }

    private static String strip(String name) {
        if (name == null) return "air";
        String s = name.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("minecraft:")) s = s.substring("minecraft:".length());
        int b = s.indexOf('[');
        if (b >= 0) s = s.substring(0, b);
        return s;
    }

    @Override
    public byte[] export(Map<Long, String> voxels, int minX, int minY, int minZ,
                         int w, int h, int d) throws Exception {
        if (w <= 0 || h <= 0 || d <= 0) {
            throw new IllegalArgumentException("mcstructure size must be positive");
        }
        long totalL = (long) w * h * d;
        if (totalL > LeNbtWriter.MAX_LIST) {
            throw new IllegalArgumentException("mcstructure too large: " + totalL + " cells");
        }
        int total = (int) totalL;

        TreeSet<String> names = new TreeSet<>();
        if (voxels != null) {
            for (String raw : voxels.values()) {
                if (isAir(raw)) continue;
                names.add(bedrockName(raw));
            }
        }
        Map<String, Integer> palette = new LinkedHashMap<>();
        for (String n : names) palette.put(n, palette.size());

        List<Integer> primary = new ArrayList<>(total);
        List<Integer> secondary = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            primary.add(-1);
            secondary.add(-1);
        }
        if (voxels != null) {
            for (Map.Entry<Long, String> e : voxels.entrySet()) {
                if (isAir(e.getValue())) continue;
                int x = VoxelModel.xOf(e.getKey()) - minX;
                int y = VoxelModel.yOf(e.getKey()) - minY;
                int z = VoxelModel.zOf(e.getKey()) - minZ;
                if (x < 0 || x >= w || y < 0 || y >= h || z < 0 || z >= d) continue;
                Integer pid = palette.get(bedrockName(e.getValue()));
                if (pid == null) continue;
                primary.set(index(x, y, z, w, h, d), pid);
            }
        }

        List<Object> blockPalette = new ArrayList<>();
        for (String n : palette.keySet()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", "minecraft:" + n);
            entry.put("states", new LinkedHashMap<String, Object>());
            entry.put("version", BLOCK_VERSION);
            blockPalette.add(entry);
        }

        Map<String, Object> defPal = new LinkedHashMap<>();
        defPal.put("block_palette", blockPalette);
        defPal.put("block_position_data", new LinkedHashMap<String, Object>());

        Map<String, Object> paletteRoot = new LinkedHashMap<>();
        paletteRoot.put("default", defPal);

        List<Object> layers = new ArrayList<>(2);
        layers.add(primary);
        layers.add(secondary);

        Map<String, Object> structure = new LinkedHashMap<>();
        structure.put("block_indices", layers);
        structure.put("entities", new ArrayList<>());
        structure.put("palette", paletteRoot);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format_version", FORMAT_VERSION);
        root.put("size", List.of(w, h, d));                          // LIST, not int[]
        root.put("structure", structure);
        root.put("structure_world_origin", List.of(0, 0, 0));        // LIST, not int[]

        return LeNbtWriter.writeRoot("", root);
    }
}
