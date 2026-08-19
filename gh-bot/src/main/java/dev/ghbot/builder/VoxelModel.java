package dev.ghbot.builder;

import java.util.HashMap;
import java.util.Map;

/**
 * Voxel model — a sparse 3D grid of block names produced by primitives.
 */
public class VoxelModel {

    private final Map<Long, String> blocks = new HashMap<>();

    public static long key(int x, int y, int z) {
        return ((long) x & 0xFFFFFF) << 40 | ((long) y & 0xFFFFFF) << 16 | (z & 0xFFFF);
    }

    public void set(int x, int y, int z, String block) { blocks.put(key(x, y, z), block); }
    public String get(int x, int y, int z) { return blocks.get(key(x, y, z)); }
    public boolean has(int x, int y, int z) { return blocks.containsKey(key(x, y, z)); }
    public int size() { return blocks.size(); }
    public Iterable<Map.Entry<Long, String>> entries() { return blocks.entrySet(); }

    public Map<Long, String> entriesMapSafe() { return new HashMap<>(blocks); }

    public static int xOf(long k) { return (int) (k >> 40); }

    public static int yOf(long k) {
        int y = (int) ((k >> 16) & 0xFFFFFF);
        if ((y & 0x800000) != 0) y |= ~0xFFFFFF; // sign-extend 24-bit
        return y;
    }

    public static int zOf(long k) { return (int) (short) (k & 0xFFFF); }
}
