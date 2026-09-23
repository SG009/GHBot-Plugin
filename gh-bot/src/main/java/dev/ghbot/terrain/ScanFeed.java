package dev.ghbot.terrain;

import java.util.Map;

/**
 * v0.25.0 — Phase C scan layer: serves the last TerrainSummary to the web
 * viewer as JSON. Pure string building (no Bukkit), so SmokeTest pins it
 * headlessly; {@link dev.ghbot.web.WebStatusServer} just calls {@link #toJson}.
 *
 * Shape mirrors the build data.json the viewer already renders
 * ({name, palette, blocks:[{x,y,z,block}]}) so the SAME renderer draws the
 * scan — blocks are re-based around the scan min bounds (viewer convention).
 */
public final class ScanFeed {

    private ScanFeed() {}

    /** JSON for the viewer: origin+bounds metadata + the full column lattice
     *  re-based to (0,*,0). Returns null when there is nothing to serve. */
    public static String toJson(TerrainScanner.TerrainSummary s, int[] origin, int radius) {
        if (s == null || s.heightmap.isEmpty()) return null;
        StringBuilder sb = new StringBuilder(64 + s.heightmap.size() * 28);
        sb.append("{\"name\":\"scan r").append(radius).append(" @ ")
          .append(origin[0]).append(',').append(origin[1]).append(',').append(origin[2]).append("\",")
          .append("\"origin\":[").append(origin[0]).append(',').append(origin[1]).append(',').append(origin[2]).append("],")
          .append("\"radius\":").append(radius).append(',')
          .append("\"bounds\":{").append("\"minX\":").append(s.minX).append(",\"maxX\":").append(s.maxX)
          .append(",\"minZ\":").append(s.minZ).append(",\"maxZ\":").append(s.maxZ)
          .append(",\"surfaceMin\":").append(s.surfaceMin).append(",\"surfaceMax\":").append(s.surfaceMax).append("},")
          .append("\"blocks\":[");
        boolean first = true;
        for (Map.Entry<Long, Integer> e : s.heightmap.entrySet()) {
            int x = xOf(e.getKey()), z = zOf(e.getKey());
            String mat = s.topMaterials.get(e.getKey());
            if (!first) sb.append(',');
            first = false;
            // re-based to the scan's min bounds (viewer renders model-relative grids)
            sb.append("{\"x\":").append(x - s.minX)
              .append(",\"y\":").append(e.getValue() - s.surfaceMin)
              .append(",\"z\":").append(z - s.minZ)
              .append(",\"name\":\"").append(esc(mat == null ? "stone" : mat)).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static int xOf(long key) { return (int) (key >> 32); }
    private static int zOf(long key) { return (int) (key & 0xffffffffL); }
    private static String esc(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }
}
