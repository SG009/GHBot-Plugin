package dev.ghbot.builder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DesignSpec — a compact, structured design plan that the AI emits and the
 * builder executes. Line-based "key=value" format (LLM-friendly, no brittle
 * JSON). Each "op=" line is one primitive operation.
 */
public class DesignSpec {

    public record Op(String type, Map<String, String> params) {}

    public String name = "unnamed";
    public String style = "generic";
    public final List<String> palette = new ArrayList<>();
    public final List<Op> ops = new ArrayList<>();
    public final List<String> rawLines = new ArrayList<>();

    public static DesignSpec parse(String text) {
        DesignSpec spec = new DesignSpec();
        if (text == null) return spec;
        for (String line : text.split("\\r?\\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            spec.rawLines.add(t);
            int eq = t.indexOf('=');
            if (eq <= 0) continue;
            String key = t.substring(0, eq).trim().toLowerCase();
            String value = t.substring(eq + 1).trim();
            switch (key) {
                case "name" -> spec.name = value;
                case "style" -> spec.style = value;
                case "palette" -> {
                    spec.palette.clear();
                    for (String p : value.split(",")) {
                        String pp = p.trim();
                        if (!pp.isEmpty()) spec.palette.add(pp);
                    }
                }
                case "op" -> {
                    String[] parts = value.split("\\s+");
                    if (parts.length == 0) break;
                    Map<String, String> params = new LinkedHashMap<>();
                    for (int i = 1; i < parts.length; i++) {
                        int pEq = parts[i].indexOf('=');
                        if (pEq > 0) {
                            params.put(parts[i].substring(0, pEq).trim().toLowerCase(),
                                    parts[i].substring(pEq + 1).trim());
                        }
                    }
                    spec.ops.add(new Op(parts[0].toLowerCase(), params));
                }
                default -> { }
            }
        }
        return spec;
    }

    public boolean isValid() { return !ops.isEmpty(); }

    /** v0.21.33 — build a DesignSpec from a JSON build spec (exact block placements → set ops). */
    public static DesignSpec fromJson(dev.ghbot.builder.JsonBuildSpec jspec) {
        DesignSpec s = new DesignSpec();
        s.name = jspec.name;
        s.style = "json-exact";
        for (String pal : jspec.palette.values()) {
            String c = pal.replace("minecraft:", "").toLowerCase();
            if (!s.palette.contains(c)) s.palette.add(c);
        }
        for (dev.ghbot.builder.JsonBuildSpec.BlockPlacement b : jspec.blocks) {
            String blk = b.block().replace("minecraft:", "").toLowerCase();
            if (blk.equals("air")) continue;
            java.util.Map<String, String> p = new java.util.LinkedHashMap<>();
            p.put("x", String.valueOf(b.x()));
            p.put("y", String.valueOf(b.y()));
            p.put("z", String.valueOf(b.z()));
            p.put("mat", blk);
            s.ops.add(new Op("set", p));
        }
        return s;
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append(" · ").append(style);
        if (!palette.isEmpty()) sb.append(" · palette: ").append(String.join(", ", palette));
        sb.append(" · ").append(ops.size()).append(" op(s)");
        return sb.toString();
    }

    public String planText() {
        StringBuilder sb = new StringBuilder();
        sb.append("§e[").append(name).append("] DesignSpec (plan):");
        sb.append("\n§f  style §7").append(style);
        if (!palette.isEmpty()) sb.append("\n§f  palette §7").append(String.join(", ", palette));
        for (Op op : ops) {
            sb.append("\n§f  ▸ §a").append(op.type());
            if (!op.params().isEmpty()) {
                sb.append(" §7");
                op.params().forEach((k, v) -> sb.append(k).append("=").append(v).append(" "));
            }
        }
        return sb.toString();
    }
}
