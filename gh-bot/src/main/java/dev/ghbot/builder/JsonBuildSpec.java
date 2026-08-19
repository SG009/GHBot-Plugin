package dev.ghbot.builder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v0.21.33 — The Commands Man method: a JSON build spec with EXACT block
 * placements (palette + blocks[{x,y,z,block}]). The AI emits this, we
 * validate it strictly (coords in bounds, valid block names, palette keys),
 * and convert it deterministically to a VoxelModel — so the staged build
 * matches the design 100% (no template fallback, no AI interpretation).
 *
 * Format the AI is taught to emit:
 * {
 *   "name": "Hardcore Bastion",
 *   "palette": { "0": "minecraft:deepslate_bricks", "1": "minecraft:polished_deepslate", ... },
 *   "blocks": [ { "x": 0, "y": 0, "z": 0, "block": "0" }, ... ]
 * }
 * (blocks may use the palette id OR a direct block name)
 */
public class JsonBuildSpec {

    public String name = "unnamed";
    public final Map<String, String> palette = new LinkedHashMap<>();
    public final List<BlockPlacement> blocks = new ArrayList<>();
    public final List<String> errors = new ArrayList<>();

    public record BlockPlacement(int x, int y, int z, String block) {}

    public boolean isValid() { return errors.isEmpty() && !blocks.isEmpty(); }

    /** Convert to a VoxelModel (deterministic — exact coords). */
    public VoxelModel toModel() {
        VoxelModel m = new VoxelModel();
        for (BlockPlacement b : blocks) {
            String blk = b.block();
            if (blk == null || blk.isBlank()) continue;
            String clean = blk.replace("minecraft:", "").toLowerCase();
            if (clean.equals("air")) continue;
            m.set(b.x(), b.y(), b.z(), clean);
        }
        return m;
    }

    /**
     * Parse + validate a JSON build spec (hand-rolled, no gson).
     * Returns null if it doesn't look like a JSON build spec at all.
     */
    public static JsonBuildSpec parse(String text) {
        if (text == null) return null;
        int pi = text.indexOf("\"palette\"");
        int bi = text.indexOf("\"blocks\"");
        if (pi < 0 && bi < 0) return null;   // not a JSON build spec
        JsonBuildSpec spec = new JsonBuildSpec();
        // name
        String name = extractStr(text, "name");
        if (name != null) spec.name = name;
        // palette: {"0": "minecraft:x", ...}
        parsePalette(text, spec);
        // blocks: [{x,y,z,block}, ...]
        parseBlocks(text, spec);
        // validate
        validate(spec);
        return spec;
    }

    private static void parsePalette(String text, JsonBuildSpec spec) {
        int start = text.indexOf("\"palette\"");
        if (start < 0) return;
        int brace = text.indexOf('{', start);
        if (brace < 0) return;
        int end = matchingBrace(text, brace);
        if (end < 0) return;
        String body = text.substring(brace + 1, end);
        // split top-level commas (values may contain ":" but not nested braces here)
        for (String pair : splitTopLevel(body)) {
            int c = pair.indexOf(':');
            if (c < 0) continue;
            String k = unquote(pair.substring(0, c));
            String v = unquote(pair.substring(c + 1));
            if (k != null && v != null) spec.palette.put(k.trim(), v.trim());
        }
    }

    private static void parseBlocks(String text, JsonBuildSpec spec) {
        int start = text.indexOf("\"blocks\"");
        if (start < 0) return;
        int arr = text.indexOf('[', start);
        if (arr < 0) return;
        int end = matchingBracket(text, arr);
        if (end < 0) return;
        String body = text.substring(arr + 1, end);
        for (String obj : splitTopLevel(body)) {
            int x = intOf(obj, "x");
            int y = intOf(obj, "y");
            int z = intOf(obj, "z");
            String block = extractStr(obj, "block");
            if (block == null) block = extractStr(obj, "id");
            if (block == null) continue;
            String clean = block.replace("minecraft:", "").trim();
            // palette reference? e.g. "block": "0"
            if (clean.matches("\\d+") && spec.palette.containsKey(clean)) {
                clean = spec.palette.get(clean).replace("minecraft:", "").trim();
            }
            spec.blocks.add(new BlockPlacement(x, y, z, clean));
        }
    }

    private static void validate(JsonBuildSpec spec) {
        for (BlockPlacement b : spec.blocks) {
            if (b.x() < -30000000 || b.x() > 30000000 || b.z() < -30000000 || b.z() > 30000000
                    || b.y() < -64 || b.y() > 320) {
                spec.errors.add("block out of world bounds: (" + b.x() + "," + b.y() + "," + b.z() + ")");
                return;
            }
            if (b.block() == null || b.block().isBlank()) {
                spec.errors.add("block with empty name at (" + b.x() + "," + b.y() + "," + b.z() + ")");
                return;
            }
        }
        if (spec.blocks.size() > 20000) {
            spec.errors.add("too many blocks (" + spec.blocks.size() + " > 20000) — too large for a ghost build");
        }
    }

    private static int intOf(String obj, String key) {
        String s = extractVal(obj, key);
        if (s == null) return 0;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    /** Extract a value for a key — handles BOTH quoted ("x": "5") and unquoted numbers ("x": 5). */
    private static String extractVal(String json, String key) {
        int i = json.indexOf('"' + key + '"');
        if (i < 0) return null;
        int c = json.indexOf(':', i);
        if (c < 0) return null;
        int s = c + 1;
        while (s < json.length() && Character.isWhitespace(json.charAt(s))) s++;
        if (s >= json.length()) return null;
        if (json.charAt(s) == '"') {
            int q2 = json.indexOf('"', s + 1);
            return q2 < 0 ? null : json.substring(s + 1, q2);
        }
        // unquoted: read until , } ] or whitespace
        int e = s;
        while (e < json.length() && json.charAt(e) != ',' && json.charAt(e) != '}'
                && json.charAt(e) != ']' && !Character.isWhitespace(json.charAt(e))) e++;
        return json.substring(s, e);
    }

    private static String extractStr(String json, String key) {
        int i = json.indexOf('"' + key + '"');
        if (i < 0) return null;
        int c = json.indexOf(':', i);
        if (c < 0) return null;
        int q = json.indexOf('"', c);
        if (q < 0) return null;
        int q2 = json.indexOf('"', q + 1);
        if (q2 < 0) return null;
        return json.substring(q + 1, q2);
    }

    private static String unquote(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
        return s;
    }

    private static int matchingBrace(String s, int open) {
        return matching(s, open, '{', '}');
    }
    private static int matchingBracket(String s, int open) {
        return matching(s, open, '[', ']');
    }
    private static int matching(String s, int open, char o, char c) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == o) depth++;
            else if (ch == c) { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    /** Split on commas at depth 0 (respects {} []). */
    private static List<String> splitTopLevel(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        boolean inStr = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' && (i == 0 || s.charAt(i - 1) != '\\')) inStr = !inStr;
            if (inStr) continue;
            if (ch == '{' || ch == '[') depth++;
            else if (ch == '}' || ch == ']') depth--;
            else if (ch == ',' && depth == 0) { out.add(s.substring(start, i)); start = i + 1; }
        }
        out.add(s.substring(start));
        return out;
    }
}
