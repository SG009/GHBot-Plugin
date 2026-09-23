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
        /** v0.25.0 — (x,z) -> the surface block name at that column (same keys as heightmap). */
        public final Map<Long, String> topMaterials = new LinkedHashMap<>();
        /** Retention cap for topMaterials (radius ~100+ scans would bloat the phone otherwise). */
        public static final int MAX_TOP_MATERIALS = 60_000;
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
            // v0.22.1 — persist the heightmap too (was dropped before, so
            // "foundation follows the ground" lost its data across a reload).
            // Serialize as "x,z" -> y strings (Bukkit-YAML-safe keys); downsample
            // huge scans so the phone's session YAML stays small.
            java.util.Map<String, Integer> hm = new LinkedHashMap<>();
            if (!heightmap.isEmpty()) {
                int stride = 1;
                int cap = 8192;
                if (heightmap.size() > cap) stride = (int) Math.ceil(Math.sqrt((double) heightmap.size() / cap));
                int i = 0;
                for (java.util.Map.Entry<Long, Integer> e : heightmap.entrySet()) {
                    if (stride > 1 && (i++ % stride) != 0) continue;
                    hm.put(xOfKey(e.getKey()) + "," + zOfKey(e.getKey()), e.getValue());
                }
            }
            m.put("heightmap", hm);
            return m;
        }

        /**
         * v0.22.1 — reconstruct a TerrainSummary from a persisted map (the inverse
         * of {@link #toMap()}), including the heightmap, so terrain context that was
         * saved to a session can be restored verbatim after a reload.
         */
        public static TerrainSummary fromMap(java.util.Map<String, Object> m) {
            TerrainSummary s = new TerrainSummary();
            if (m == null) return s;
            s.world = str(m.get("world"));
            s.minX = i(m.get("minX")); s.minY = i(m.get("minY")); s.minZ = i(m.get("minZ"));
            s.maxX = i(m.get("maxX")); s.maxY = i(m.get("maxY")); s.maxZ = i(m.get("maxZ"));
            s.scannedBlocks = i(m.get("scannedBlocks"));
            s.nonAir = i(m.get("nonAir"));
            s.water = bool(m.get("water")); s.lava = bool(m.get("lava"));
            Object tb = m.get("topBlocks");
            if (tb instanceof java.util.Map<?, ?> tbm) {
                for (var e : tbm.entrySet()) {
                    if (e.getKey() == null) continue;
                    s.topBlocks.put(String.valueOf(e.getKey()), i(e.getValue()));
                }
            }
            s.surfaceMax = i(m.get("surfaceMax"));
            s.surfaceMin = i(m.get("surfaceMin"));
            Object hm = m.get("heightmap");
            if (hm instanceof java.util.Map<?, ?> hmm) {
                for (var e : hmm.entrySet()) {
                    String k = String.valueOf(e.getKey());
                    int c = k.indexOf(',');
                    if (c < 0) continue;
                    try {
                        int x = Integer.parseInt(k.substring(0, c).trim());
                        int z = Integer.parseInt(k.substring(c + 1).trim());
                        s.heightmap.put(s.heightmapKey(x, z), i(e.getValue()));
                    } catch (NumberFormatException ignored) {}
                }
            }
            return s;
        }

        private static long xOfKey(long key) { return key >> 32; }
        private static long zOfKey(long key) { return (int) (key & 0xffffffffL); }
        private static String str(Object o) { return o == null ? null : String.valueOf(o); }
        private static int i(Object o) {
            if (o == null) return 0;
            if (o instanceof Number n) return n.intValue();
            try { return Integer.parseInt(String.valueOf(o).trim()); } catch (NumberFormatException e) { return 0; }
        }
        private static boolean bool(Object o) {
            if (o == null) return false;
            if (o instanceof Boolean b) return b;
            return Boolean.parseBoolean(String.valueOf(o));
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

        /* ── v0.25.0 — eyes lattice (Phase C): the scan text the AI reads. Same
         *  scanning work as before, but the per-column knowledge survives
         *  serialization (radius-tiered so text + memory stay phone-sized). ── */

        /** Scan radius derived from the bounds (0 when unset). */
        public int radius() {
            return Math.max((maxX - minX) / 2, (maxZ - minZ) / 2);
        }

        /** Column sampling stride per radius tier: ≤8 every column, 9–32 every 2nd
         *  (+ exact center), >32 counts-only (historic toLine() format). */
        public static int strideForRadius(int radius) {
            if (radius <= 8) return 1;
            if (radius <= 32) return 2;
            return 0;
        }

        /** Sampled lattice lines, absolute coords: "x,z: y material". Empty when
         *  the tier is counts-only or nothing was scanned. */
        public List<String> latticeLines() {
            List<String> out = new ArrayList<>();
            int stride = strideForRadius(radius());
            if (stride == 0 || heightmap.isEmpty()) return out;
            int cx = (minX + maxX) / 2, cz = (minZ + maxZ) / 2;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Integer y = height(x, z);
                    if (y == null) continue;
                    boolean center = x == cx && z == cz;
                    if (!center && stride > 1
                            && (((x - minX) % stride) != 0 || ((z - minZ) % stride) != 0)) continue;
                    String mat = topMaterials.get(heightmapKey(x, z));
                    out.add(x + "," + z + ": " + y + " " + (mat == null ? "?" : mat));
                }
            }
            return out;
        }

        /** Budget-bounded lattice text; overflow is marked truthfully (`… +N column(s)`). */
        public String latticeText(int budgetChars) {
            List<String> lines = latticeLines();
            if (lines.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            int i = 0;
            for (; i < lines.size(); i++) {
                String l = lines.get(i);
                if (sb.length() + l.length() + 14 > budgetChars) break; // reserve for the "+N" line
                if (i > 0) sb.append('\n');
                sb.append(l);
            }
            if (i < lines.size()) sb.append("\n… +").append(lines.size() - i).append(" column(s) trimmed");
            return sb.toString();
        }

        /** Full untruncated grid — routed to logs/scan/*.log by the caller. */
        public String fullLattice() {
            return String.join("\n", latticeLines());
        }
    }

    /** v0.22.3 — scan column floor: the WORLD's min height, never clamped to 0
     *  (1.18+ worlds go down to -64; smoke-pinned so the y<0 blindness can't return).
     *  Public for SmokeTest. */
    public static int columnMinY(int worldMinHeight) { return worldMinHeight; }

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
        // v0.22.3 — was Math.max(minHeight, 0): a pre-1.18 assumption that made scans
        // blind to everything below y=0 (owner's hub ground is at y=-1 → scan found 0 blocks).
        s.minY = columnMinY(w.getMinHeight());
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
                            // v0.25.0 — eyes lattice: keep the per-column surface material too
                            // (heightmap alone told us WHERE the top is, not WHAT it is)
                            if (s.topMaterials.size() < TerrainSummary.MAX_TOP_MATERIALS) {
                                s.topMaterials.put(s.heightmapKey(x, z),
                                        m.name().toLowerCase(java.util.Locale.ROOT));
                            }
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

    /**
     * v0.22.1 — build a TerrainSpec (eyes-as-data) for a scan box (±radius on
     * X/Z around center). depthBelow = 0 → surface snapshot (top solid block per
     * column, the default — cheap + phone-safe); depthBelow = N → also capture N
     * solid blocks below the surface per column (capped at 64), for edit planning.
     * Returns null when there's no world (headless smoke safety).
     */
    public static TerrainSpec scanSpec(Location center, int radius, int depthBelow) {
        World w = center == null ? null : center.getWorld();
        if (w == null) return null;
        TerrainSpec spec = new TerrainSpec();
        spec.kind = "scan";
        int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();
        spec.ox = cx; spec.oy = cy; spec.oz = cz;
        spec.name = "scan@r" + radius + "@" + cx + "," + cy + "," + cz;
        int minX = cx - radius, maxX = cx + radius;
        int minZ = cz - radius, maxZ = cz + radius;
        int minY = columnMinY(w.getMinHeight());  // v0.22.3 — include below-zero layers (1.18+)
        int maxY = w.getMaxHeight() - 1;
        int depth = Math.max(0, Math.min(64, depthBelow));
        java.util.Map<Long, ChunkSnapshot> snaps = captureSnapshots(w, minX, minZ, maxX, maxZ);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                ChunkSnapshot sn = snapAt(snaps, x, z);
                if (sn == null) continue;
                int found = 0;
                for (int y = maxY; y >= minY && found <= depth; y--) {
                    Material m = sn.getBlockType(x & 15, y, z & 15);
                    if (m == null || m.isAir()) continue;
                    if (m == Material.WATER || m == Material.SEAGRASS
                            || m == Material.TALL_SEAGRASS || m == Material.KELP_PLANT
                            || m == Material.LAVA) continue;
                    spec.add(x, y, z, m.name());
                    found++;
                }
            }
        }
        return spec;
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
