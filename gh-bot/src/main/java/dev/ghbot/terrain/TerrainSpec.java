package dev.ghbot.terrain;

import dev.ghbot.builder.JsonBuildSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v0.22.1 — "eyes as data". scan / find / look now emit the SAME contract
 * shape as a JSON build spec ({name, palette, blocks[]}) but in ABSOLUTE world
 * coordinates with an {@code origin} anchor, so the Technician sees the world
 * as block data it can act on directly (edit/set/replace/terraform/undo) and
 * the data round-trips into the build path via {@link #toBuildSpec()}.
 *
 * Full fidelity goes to bot memory + logs/eyes/&lt;name&gt;.json; the chat/AI
 * context gets a bounded inline JSON ({@link #INLINE_MAX} blocks) so a 100k
 * scan never blows up the context window on the 6 GB phone.
 */
public final class TerrainSpec {

    /** D2 — inline token budget: how many blocks the chat/AI context gets (owner: 150). */
    public static final int INLINE_MAX = 150;

    public String name = "unnamed";
    public String kind = "scan";    // scan | find | look
    public int ox, oy, oz;          // origin (absolute world coords)

    /** id -> "minecraft:name" (dense ids so big scans stay compact). */
    public final Map<String, String> palette = new LinkedHashMap<>();
    /** absolute world coords; block = palette id OR a raw name/blockstate. */
    public final List<Block> blocks = new ArrayList<>();
    private final Map<String, String> reverse = new LinkedHashMap<>();   // normalized name -> id

    public record Block(int x, int y, int z, String block) {}

    public int size() { return blocks.size(); }

    /** Normalize a block name: lowercase, drop "minecraft:", strip [state] props. */
    public static String normalize(String name) {
        if (name == null) return "air";
        String n = name.replace("minecraft:", "").trim();
        int b = n.indexOf('[');
        if (b >= 0) n = n.substring(0, b).trim();
        n = n.toLowerCase();
        return n.isEmpty() ? "air" : n;
    }

    /** Assign a dense palette id for a block name; reuse existing ids. */
    public String idFor(String blockName) {
        String n = normalize(blockName);
        String id = reverse.get(n);
        if (id == null) {
            id = String.valueOf(palette.size());
            palette.put(id, "minecraft:" + n);
            reverse.put(n, id);
        }
        return id;
    }

    /** Add a block at absolute coords (normalized into a palette id). */
    public void add(int x, int y, int z, String blockName) {
        blocks.add(new Block(x, y, z, idFor(blockName)));
    }

    /** Add a block keeping the RAW name/blockstate (for `look`, which preserves state). */
    public void addRaw(int x, int y, int z, String rawName) {
        blocks.add(new Block(x, y, z, rawName));
    }

    /** Full JSON (no truncation). */
    public String toJson() { return toJson(Integer.MAX_VALUE, false); }

    /** Bounded inline JSON for the chat/AI context (truncated flag + total). */
    public String toJsonInline(int max) { return toJson(max, true); }

    private String toJson(int maxBlocks, boolean bounded) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"name\":\"").append(jsonEsc(name)).append('"');
        sb.append(",\"kind\":\"").append(jsonEsc(kind)).append('"');
        sb.append(",\"origin\":{\"x\":").append(ox).append(",\"y\":").append(oy).append(",\"z\":").append(oz).append('}');
        sb.append(",\"palette\":{");
        boolean first = true;
        for (Map.Entry<String, String> e : palette.entrySet()) {
            if (!first) sb.append(',');
            sb.append('"').append(e.getKey()).append("\":\"").append(jsonEsc(e.getValue())).append('"');
            first = false;
        }
        sb.append("},\"blocks\":[");
        int n = Math.min(blocks.size(), maxBlocks);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            Block b = blocks.get(i);
            sb.append("{\"x\":").append(b.x()).append(",\"y\":").append(b.y())
              .append(",\"z\":").append(b.z()).append(",\"block\":\"").append(jsonEsc(b.block())).append("\"}");
        }
        sb.append(']');
        if (bounded && blocks.size() > maxBlocks) {
            sb.append(",\"truncated\":true,\"total\":").append(blocks.size());
        }
        sb.append('}');
        return sb.toString();
    }

    /** Human one-line summary (shown alongside the inline JSON). */
    public String toLine() {
        return kind + " " + name + " — " + blocks.size() + " block(s), " + palette.size() + " type(s)";
    }

    /**
     * Round-trip into the build path: translate absolute → relative via the
     * origin anchor, then parse + validate through JsonBuildSpec (which now
     * enforces the vanilla block constraint, v0.22.1). Returns null if the
     * translation produced nothing parseable.
     */
    public JsonBuildSpec toBuildSpec() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"name\":\"").append(jsonEsc(name)).append('"');
        sb.append(",\"palette\":{");
        boolean first = true;
        for (Map.Entry<String, String> e : palette.entrySet()) {
            if (!first) sb.append(',');
            sb.append('"').append(e.getKey()).append("\":\"").append(jsonEsc(e.getValue())).append('"');
            first = false;
        }
        sb.append("},\"blocks\":[");
        for (int i = 0; i < blocks.size(); i++) {
            if (i > 0) sb.append(',');
            Block b = blocks.get(i);
            sb.append("{\"x\":").append(b.x() - ox)
              .append(",\"y\":").append(b.y() - oy)
              .append(",\"z\":").append(b.z() - oz)
              .append(",\"block\":\"").append(jsonEsc(b.block())).append("\"}");
        }
        sb.append("]}");
        return JsonBuildSpec.parse(sb.toString());
    }

    private static String jsonEsc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
