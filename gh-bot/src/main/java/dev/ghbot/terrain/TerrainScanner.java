package dev.ghbot.terrain;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.ChunkSnapshot;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 2 — the bot's "eyes" (your old scan.js, revived).
 * Reads the actual world: block counts, a surface heightmap, and sky-light
 * detection — no AI needed to "see". All calls are safe to run async.
 */
public final class TerrainScanner {

    /** Summary of a scanned area. Stored in bot.memory as scan context. */
    public static class TerrainSummary {
        public String world;
        public int minX, minY, minZ, maxX, maxY, maxZ;
        public int scannedBlocks;
        public int nonAir;
        public boolean water, lava;
        public final Map<String, Integer> topBlocks = new LinkedHashMap<>();   // block name -> count (desc)
        public final Map<Long, Integer> heightmap = new HashMap<>();        // (x,z) -> highest solid y
        public int surfaceMax;                                                  // max heightmap y
        public int surfaceMin;                                                  // min heightmap y

        public int surfaceDelta() { return Math.max(0, surfaceMax - surfaceMin); }
        public long heightmapKey(int x, int z) { return (long) x << 32 | (z & 0xffffffffL); }
        public Integer height(int x, int z) { return heightmap.get(heightmapKey(x, z)); }

        /** Convert to a plain map so it persists cleanly through Bukkit YAML sessions. */
        public java.util.Map<String, Object> toMap() {
            java.util.Map<String, Object> m = new LinkedHashMap<>();
            m.put("world", world);
            m.put("minX", minX); m.put("minY", minY); m.put("minZ", minZ);
            m.put("maxX", maxX); m.put("maxY", maxY); m.put("maxZ", maxZ);
            m.put("scannedBlocks", scannedBlocks);
            m.put("nonAir", nonAir);
            m.put("water", water);
            m.put("lava", lava);
            m.put("topBlocks", new LinkedHashMap<>(topBlocks));
            m.put("surfaceMax", surfaceMax);
            m.put("surfaceMin", surfaceMin);
            return m;
        }

        /** Compact one-line terrain summary (used in chat replies + AI context). */
        public String toLine() {
            StringBuilder sb = new StringBuilder();
            sb.append("scan ").append(minX).append("..").append(maxX)
              .append(" y").append(minY).append("..").append(maxY)
              .append(" z").append(minZ).append("..").append(maxZ)
              .append(" — ").append(nonAir).append("/").append(scannedBlocks).append(" blocks");
            if (water) sb.append(", water");
            if (lava) sb.append(", lava");
            if (surfaceDelta() > 2) sb.append(", surface varies ").append(surfaceMin).append("-").append(surfaceMax);
            else if (surfaceMin > 0) sb.append(", ground ~").append(surfaceMin);
            String top = topBlocks.entrySet().stream().limit(3)
                    .map(e -> e.getKey() + ":" + e.getValue()).reduce((a, b) -> a + " " + b).orElse("");
            if (!top.isEmpty()) sb.append(" — top: ").append(top);
            return sb.toString();
        }
    }

    /** Scan a cubic region around center (±radius on X/Z, full column in Y up to world height). */
    public static TerrainSummary scan(Location center, int radius) {
        World w = center.getWorld();
        TerrainSummary s = new TerrainSummary();
        if (w == null) return s;
        s.world = w.getName();
        int cx = center.getBlockX(), cz = center.getBlockZ();
        int minX = cx - radius, maxX = cx + radius;
        int minZ = cz - radius, maxZ = cz + radius;
        s.minX = minX; s.maxX = maxX;
        s.minZ = minZ; s.maxZ = maxZ;
        s.minY = Math.max(w.getMinHeight(), 0);
        s.maxY = w.getMaxHeight() - 1;

        // v0.21.9 — thread-safe: grab chunk snapshots on the main thread, then
        // iterate the snapshot data (safe from any thread).
        java.util.Map<Long, ChunkSnapshot> snaps = captureSnapshots(w, minX, minZ, maxX, maxZ);
        try {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    ChunkSnapshot sn = snapAt(snaps, x, z);
                    if (sn == null) continue; // chunk not loaded — skip
                    int highest = -1;
                    for (int y = s.maxY; y >= s.minY; y--) {
                        Material m = sn.getBlockType(x & 15, y, z & 15);
                        s.scannedBlocks++;
                        if (m == null || m.isAir()) continue;
                        s.nonAir++;
                        if (m == Material.WATER || m == Material.SEAGRASS
                                || m == Material.TALL_SEAGRASS || m == Material.KELP_PLANT) {
                            s.water = true;
                            continue; // don't count water as ground surface
                        }
                        if (m == Material.LAVA) { s.lava = true; continue; }
                        // first solid from top = surface
                        if (highest == -1) {
                            highest = y;
                            s.heightmap.put(s.heightmapKey(x, z), y);
                            s.topBlocks.merge(m.name(), 1, Integer::sum);
                            if (s.surfaceMax < y) s.surfaceMax = y;
                            if (s.surfaceMin == 0 || y < s.surfaceMin) s.surfaceMin = y;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            s.world = s.world + " (partial: " + t.getMessage() + ")";
        }
        // sort topBlocks desc
        List<Map.Entry<String, Integer>> es = new ArrayList<>(s.topBlocks.entrySet());
        es.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        s.topBlocks.clear();
        for (var e : es) s.topBlocks.put(e.getKey(), e.getValue());
        return s;
    }

    /** Capture chunk snapshots for the (x0,z0)-(x1,z1) area — must run on the main thread.
     *  v0.21.10: getChunkAt FORCE-LOADS unloaded chunks (like the old getBlockAt code did),
     *  so scans see real terrain even with 0 players online. Chunk loads are sync-on-main. */
    public static java.util.Map<Long, ChunkSnapshot> captureSnapshots(World w, int x0, int z0, int x1, int z1) {
        return dev.ghbot.core.MainThread.call(() -> {
            java.util.Map<Long, ChunkSnapshot> snaps = new java.util.HashMap<>();
            for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
                for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                    try {
                        snaps.put(((long) cx << 32) | (cz & 0xffffffffL),
                                w.getChunkAt(cx, cz).getChunkSnapshot(true, false, false));
                    } catch (Throwable ignored) {}
                }
            }
            return snaps;
        });
    }

    private static ChunkSnapshot snapAt(java.util.Map<Long, ChunkSnapshot> snaps, int x, int z) {
        return snaps.get(((long) (x >> 4) << 32) | ((z >> 4) & 0xffffffffL));
    }

    /** Find all blocks of a type within a cube around center (capped). */
    public static List<Block> findBlocks(Location center, Material mat, int radius, int maxResults) {
        List<Block> out = new ArrayList<>();
        World w = center.getWorld();
        if (w == null) return out;
        int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();
        int x0 = cx - radius, x1 = cx + radius, z0 = cz - radius, z1 = cz + radius;
        int y0 = Math.max(w.getMinHeight(), cy - radius);
        int y1 = Math.min(w.getMaxHeight() - 1, cy + radius);
        // v0.21.9 — thread-safe snapshots (captured on main thread)
        java.util.Map<Long, ChunkSnapshot> snaps = captureSnapshots(w, x0, z0, x1, z1);
        outer:
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    ChunkSnapshot sn = snapAt(snaps, x, z);
                    if (sn == null) continue;
                    if (sn.getBlockType(x & 15, y, z & 15) == mat) {
                        out.add(w.getBlockAt(x, y, z)); // Block is a data holder; getters safe
                        if (out.size() >= maxResults) break outer;
                    }
                }
            }
        }
        return out;
    }

    /** Return the surface block directly under a location, or null. */
    public static Block surfaceBelow(Location loc) {
        World w = loc.getWorld();
        if (w == null) return null;
        int x = loc.getBlockX(), z = loc.getBlockZ();
        int y = Math.min(loc.getBlockY(), w.getMaxHeight() - 1);
        for (int yy = y; yy >= w.getMinHeight(); yy--) {
            Block b = w.getBlockAt(x, yy, z);
            if (!b.getType().isAir() && b.getType() != Material.WATER && b.getType() != Material.LAVA) {
                return b;
            }
        }
        return null;
    }

    /** Block immediately in front of the location facing, or null. */
    public static Block blockInFront(Location loc, int dist) {
        var dir = loc.getDirection().clone().normalize().multiply(dist);
        return loc.getWorld() == null ? null
                : loc.getWorld().getBlockAt(loc.clone().add(dir));
    }

    public static String faceName(BlockFace f) {
        return switch (f) {
            case NORTH -> "north"; case SOUTH -> "south";
            case EAST -> "east"; case WEST -> "west";
            case UP -> "up"; case DOWN -> "down";
            default -> f.name().toLowerCase();
        };
    }
}
