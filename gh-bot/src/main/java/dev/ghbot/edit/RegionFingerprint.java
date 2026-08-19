package dev.ghbot.edit;

import dev.ghbot.builder.VoxelModel;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Phase 10 — state-drift guard. A cheap FNV-1a hash over the block types
 * inside a bounding box (or over a voxel model). Before an EditSpec is
 * applied, the region hash is recomputed; if it differs from the hash at
 * snapshot time, the world changed while the AI was planning, so we warn
 * the user and re-scan before touching a single block.
 */
public final class RegionFingerprint {

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private RegionFingerprint() {}

    /** Hash a live world region (air blocks skipped, deterministic order). */
    public static long of(World w, int x0, int y0, int z0, int x1, int y1, int z1) {
        long h = FNV_OFFSET;
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++) {
                    Material m;
                    try {
                        m = w.getBlockAt(x, y, z).getType();
                    } catch (Throwable t) {
                        m = Material.AIR;
                    }
                    if (m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR) continue;
                    h = mix(h, m.ordinal());
                    h = mix(h, m.name().hashCode());
                }
        return h;
    }

    /** Hash a voxel model (headless-safe; deterministic regardless of map order). */
    public static long of(VoxelModel m) {
        List<Map.Entry<Long, String>> es = new ArrayList<>();
        m.entries().forEach(es::add);
        es.sort(Comparator.comparingLong(Map.Entry::getKey));
        long h = FNV_OFFSET;
        for (var e : es) {
            h = mix(h, (int) (e.getKey() ^ (e.getKey() >>> 32)));
            for (int i = 0; i < e.getValue().length(); i++) h = mix(h, e.getValue().charAt(i));
        }
        return h;
    }

    /** FNV-1a over the 4 bytes of an int. */
    private static long mix(long h, int b) {
        h ^= (b & 0xFF); h *= FNV_PRIME;
        h ^= ((b >>> 8) & 0xFF); h *= FNV_PRIME;
        h ^= ((b >>> 16) & 0xFF); h *= FNV_PRIME;
        h ^= ((b >>> 24) & 0xFF); h *= FNV_PRIME;
        return h;
    }
}
