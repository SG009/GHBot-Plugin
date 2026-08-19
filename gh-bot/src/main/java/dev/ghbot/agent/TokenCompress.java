package dev.ghbot.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v0.21.17 — RTK-style token compression (deterministic, no external deps).
 * Tool results fed back into the AI context are the biggest token cost (a full
 * config read, a long catalog/find/scan output can be thousands of tokens).
 * This compresses long results BEFORE they enter history — keeping the
 * structure (head + informative lines) and dropping bulk, so the AI still has
 * what it needs at a fraction of the tokens. Rough char≈token for English.
 */
public final class TokenCompress {

    private TokenCompress() {}

    /** Default max chars of a tool result kept in AI context. */
    public static final int DEFAULT_BUDGET = 1200;

    /**
     * Compress a tool result to ~budget chars, RTK-style:
     *  - short results pass through untouched;
     *  - repeated consecutive lines are collapsed;
     *  - the head (structure/intro) is always kept;
     *  - the remaining budget is filled with the most informative lines
     *    (unique content, coordinates, block names, commands);
     *  - a clear marker shows what was cut.
     * Returns [compressed, originalLength] via a record for logging savings.
     */
    public static Result compress(String text, int budget) {
        if (text == null || text.isEmpty()) return new Result("", 0);
        int orig = text.length();
        if (orig <= budget) return new Result(text, orig);

        String[] lines = text.split("\n");
        List<String> head = new ArrayList<>();
        List<String> body = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (i < 6) head.add(lines[i]);        // keep the intro/structure
            else body.add(lines[i]);
        }

        // collapse consecutive duplicates
        List<String> uniq = new ArrayList<>();
        String prev = null;
        for (String l : body) {
            if (!l.equals(prev)) uniq.add(l);
            prev = l;
        }

        // score lines: prefer lines with digits/coords/block names/commands, shorter lines
        Map<String, Integer> score = new LinkedHashMap<>();
        for (String l : uniq) {
            String t = l.trim();
            if (t.isEmpty()) continue;
            int s = 0;
            if (t.matches(".*-?\\d+.*")) s += 3;                    // coords/counts
            if (t.matches(".*[a-z_]+_\\w+.*")) s += 2;              // block names
            if (t.startsWith("- ") || t.startsWith("/")) s += 2;   // list items / commands
            if (t.length() < 60) s += 1;
            score.put(l, s);
        }
        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(score.entrySet());
        ranked.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        // build the compressed body within the budget
        int used = 0;
        for (String h : head) used += h.length() + 1;
        List<String> picked = new ArrayList<>();
        int cut = 0;
        for (var e : ranked) {
            int add = e.getKey().length() + 1;
            if (used + add > budget - 40) { cut++; continue; }
            picked.add(e.getKey());
            used += add;
        }
        if (picked.size() < ranked.size()) cut += ranked.size() - picked.size();

        StringBuilder sb = new StringBuilder();
        for (String h : head) sb.append(h).append('\n');
        sb.append("… [compressed ").append(orig).append(" chars → ").append(used)
          .append("; cut ").append(cut).append(" line(s)]\n");
        for (String p : picked) sb.append(p).append('\n');
        return new Result(sb.toString().trim(), orig);
    }

    public record Result(String text, int originalLength) {
        /** Percent of chars saved (0..100). */
        public int savedPercent() {
            return originalLength <= 0 ? 0 : Math.min(100, (int) (((long) originalLength - text.length()) * 100 / originalLength));
        }
    }
}
