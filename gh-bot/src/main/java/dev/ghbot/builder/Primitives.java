package dev.ghbot.builder;

import java.util.Map;

/**
 * Primitive build operations. Each draws into a {@link VoxelModel}.
 * Supported ops: set, floor, box, wall, cylinder, tower, dome, cone, column,
 * ring, window_row, door, path, tree.
 */
public final class Primitives {

    private Primitives() {}

    public static void apply(DesignSpec.Op op, VoxelModel v) {
        Map<String, String> p = op.params();
        switch (op.type()) {
            case "set" -> v.set(i(p, "x", 0), i(p, "y", 0), i(p, "z", 0), m(p, "mat", "stone_bricks"));
            case "floor" -> floor(v, p);
            case "box" -> box(v, p);
            case "wall" -> wall(v, p);
            case "cylinder", "tower" -> cylinder(v, p, true);
            case "dome" -> dome(v, p);
            case "cone" -> cone(v, p);
            case "column" -> column(v, p);
            case "ring" -> ring(v, p);
            case "window_row" -> windowRow(v, p);
            case "door" -> door(v, p);
            case "path" -> path(v, p);
            case "tree" -> tree(v, p);
            default -> { }
        }
    }

    private static int i(Map<String, String> p, String k, int dflt) {
        String s = p.get(k);
        return s == null ? dflt : Integer.parseInt(s);
    }
    private static String m(Map<String, String> p, String k, String dflt) {
        String s = p.get(k);
        return s == null || s.isBlank() ? dflt : s.toLowerCase().replace("minecraft:", "");
    }

    private static void floor(VoxelModel v, Map<String, String> p) {
        int cx = i(p, "cx", 0), cz = i(p, "cz", 0), w = i(p, "w", 9), d = i(p, "d", 9), y = i(p, "y", 0);
        String mat = m(p, "mat", "oak_planks");
        for (int x = cx - w / 2; x <= cx + (w - 1) / 2; x++)
            for (int z = cz - d / 2; z <= cz + (d - 1) / 2; z++)
                v.set(x, y, z, mat);
    }

    private static void box(VoxelModel v, Map<String, String> p) {
        int cx = i(p, "cx", 0), cz = i(p, "cz", 0);
        int w = i(p, "w", 7), h = i(p, "h", 5), d = i(p, "d", 7);
        int y0 = i(p, "y", 0);
        String mat = m(p, "mat", "stone_bricks");
        boolean isSolid = p.getOrDefault("solid", "false").equals("true");
        int x0 = cx - w / 2, x1 = cx + (w - 1) / 2;
        int z0 = cz - d / 2, z1 = cz + (d - 1) / 2;
        int y1 = y0 + h - 1;
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++) {
                    boolean edge = x == x0 || x == x1 || z == z0 || z == z1 || y == y0 || y == y1;
                    if (isSolid || edge) v.set(x, y, z, mat);
                }
    }

    private static void wall(VoxelModel v, Map<String, String> p) {
        int x0 = i(p, "x0", 0), z0 = i(p, "z0", 0), x1 = i(p, "x1", 4), z1 = i(p, "z1", 0);
        int y0 = i(p, "y0", 0), y1 = i(p, "y1", 4);
        String mat = m(p, "mat", "stone_bricks");
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++)
                for (int y = y0; y <= y1; y++)
                    v.set(x, y, z, mat);
    }

    private static void cylinder(VoxelModel v, Map<String, String> p, boolean hollow) {
        int cx = i(p, "cx", 0), cz = i(p, "cz", 0);
        int r = i(p, "radius", 3), h = i(p, "height", 5);
        int y0 = i(p, "y", 0);
        String mat = m(p, "mat", "stone_bricks");
        for (int y = y0; y < y0 + h; y++)
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    boolean edge = dist > r - 1.0 && dist <= r + 0.6;
                    boolean inside = dist <= r + 0.6;
                    if (inside && (hollow ? edge : true)) v.set(cx + dx, y, cz + dz, mat);
                }
    }

    private static void dome(VoxelModel v, Map<String, String> p) {
        int cx = i(p, "cx", 0), cy = i(p, "cy", 5), cz = i(p, "cz", 0);
        int r = i(p, "radius", 3);
        String mat = m(p, "mat", "prismarine");
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++)
                for (int dy = 0; dy <= r; dy++) {
                    double dist = Math.sqrt(dx * dx + dz * dz + dy * dy);
                    if (dist > r - 0.8 && dist <= r + 0.6) v.set(cx + dx, cy + dy, cz + dz, mat);
                }
    }

    private static void cone(VoxelModel v, Map<String, String> p) {
        int cx = i(p, "cx", 0), cz = i(p, "cz", 0);
        int r = i(p, "radius", 3), h = i(p, "height", 3);
        int y0 = i(p, "y", 5);
        String mat = m(p, "mat", "spruce_planks");
        for (int y = 0; y < h; y++) {
            int rr = Math.max(0, r - y);
            for (int dx = -rr; dx <= rr; dx++)
                for (int dz = -rr; dz <= rr; dz++) {
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    if (dist > rr - 0.8 && dist <= rr + 0.6) v.set(cx + dx, y0 + y, cz + dz, mat);
                }
        }
    }

    private static void column(VoxelModel v, Map<String, String> p) {
        int x = i(p, "x", 0), z = i(p, "z", 0);
        int y0 = i(p, "y0", 0), y1 = i(p, "y1", 4);
        String mat = m(p, "mat", "stone_bricks");
        for (int y = y0; y <= y1; y++) v.set(x, y, z, mat);
    }

    private static void ring(VoxelModel v, Map<String, String> p) {
        int cx = i(p, "cx", 0), cz = i(p, "cz", 0), y = i(p, "y", 0);
        int r = i(p, "radius", 3);
        String mat = m(p, "mat", "stone_bricks");
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > r - 0.8 && dist <= r + 0.6) v.set(cx + dx, y, cz + dz, mat);
            }
    }

    private static void windowRow(VoxelModel v, Map<String, String> p) {
        int cx = i(p, "cx", 0), cz = i(p, "cz", 0), y = i(p, "y", 2);
        int count = i(p, "count", 3), spacing = i(p, "spacing", 2);
        String face = p.getOrDefault("face", "north");
        String frame = m(p, "frame", "dark_oak_planks");
        String glass = m(p, "glass", "glass");
        for (int k = 0; k < count; k++) {
            int off = (k - count / 2) * spacing;
            int wx = cx, wz = cz;
            if (face.equals("north") || face.equals("south")) wx = cx + off;
            else wz = cz + off;
            v.set(wx, y, wz, glass);
            v.set(wx, y + 1, wz, glass);
            v.set(wx, y - 1, wz, frame);
            v.set(wx, y + 2, wz, frame);
        }
    }

    private static void door(VoxelModel v, Map<String, String> p) {
        int cx = i(p, "cx", 0), cz = i(p, "cz", 0), y = i(p, "y", 0);
        String face = p.getOrDefault("face", "south");
        String frame = m(p, "frame", "dark_oak_planks");
        String mat = m(p, "mat", "dark_oak_door");
        int wx = cx, wz = cz;
        if (face.equals("north")) wz += 1;
        else if (face.equals("south")) wz -= 1;
        else if (face.equals("east")) wx += 1;
        else wx -= 1;
        v.set(wx, y, wz, mat);
        v.set(wx, y + 1, wz, mat);
        v.set(cx, y, cz, frame);
        v.set(cx, y + 1, cz, frame);
    }

    private static void path(VoxelModel v, Map<String, String> p) {
        int x0 = i(p, "x0", 0), z0 = i(p, "z0", 0), x1 = i(p, "x1", 8), z1 = i(p, "z1", 0);
        int y = i(p, "y", 0);
        String mat = m(p, "mat", "gravel");
        int minX = Math.min(x0, x1), maxX = Math.max(x0, x1);
        int minZ = Math.min(z0, z1), maxZ = Math.max(z0, z1);
        for (int x = minX; x <= maxX; x++)
            for (int z = minZ; z <= maxZ; z++)
                v.set(x, y, z, mat);
    }

    private static void tree(VoxelModel v, Map<String, String> p) {
        int x = i(p, "x", 0), z = i(p, "z", 0), y = i(p, "y", 1);
        int h = i(p, "height", 4);
        String log = m(p, "log", "oak_log");
        String leaves = m(p, "leaves", "oak_leaves");
        for (int yy = 0; yy < h; yy++) v.set(x, y + yy, z, log);
        for (int yy = h - 2; yy <= h; yy++)
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0 && yy == h) continue;
                    v.set(x + dx, y + yy, z + dz, leaves);
                }
    }
}
