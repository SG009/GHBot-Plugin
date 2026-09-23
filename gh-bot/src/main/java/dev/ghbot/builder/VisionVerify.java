package dev.ghbot.builder;

import dev.ghbot.ai.AIClient;
import dev.ghbot.ai.OllamaClient;
import dev.ghbot.ai.ProviderRegistry;
import dev.ghbot.config.PluginConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * v0.27.2 — Phase E item 2: vision auto-verify (checklist, not a redesign).
 *
 * <p>After a build is staged we MAY send the isometric preview PNG to a
 * vision-capable provider and ask "does this match the intended spec?".
 * One repair pass on {@code ok:false} for AI-generated builds. Pasted JSON
 * specs are the contract — notes only, never rewritten.
 *
 * <p>All methods are headless-pure so SmokeTest can pin parse/skip/repair
 * without a live model. See {@code docs/RESEARCH-vision-verify.md}.
 */
public final class VisionVerify {

    private VisionVerify() {}

    public record Verdict(boolean ok, String reason, List<String> notes, boolean parsed) {
        public boolean needsRepair() { return parsed && !ok; }
    }

    /** System prompt: JSON checklist only — never a new build spec. */
    public static final String VERIFY_SYSTEM = """
            You are checking a Minecraft isometric preview against the builder's intended spec.
            Reply with ONLY JSON (no markdown, no code fence, no build spec):
            {"ok": true, "reason": "one sentence", "notes": []}
            Rules:
            - ok=true if the preview reasonably matches the intended name, palette and scale.
            - ok=false if something important is missing or wrong (roof, door, palette, size).
            - Do NOT invent a new design. Do NOT output a jsonspec / blocks array.
            - If you cannot see the image, reply {"ok": true, "reason": "cannot see image", "notes": []}
              so we do not "repair" blindly.""";

    public static boolean enabled(PluginConfig cfg) {
        return cfg != null && cfg.buildVerifyVision();
    }

    /**
     * True when at least one *configured* provider can actually see an image.
     * Ollama is included only when the model name looks multimodal — the
     * HTTP transport accepts {@code images:[]} for every model, but qwen2.5
     * will not.
     */
    public static boolean hasVision(ProviderRegistry pr) {
        if (pr == null) return false;
        for (AIClient c : pr.allConfigured()) {
            if (c == null || !c.isConfigured() || !c.supportsVision()) continue;
            if (c instanceof OllamaClient oc && !ollamaModelLooksMultimodal(oc.model())) continue;
            return true;
        }
        return false;
    }

    public static boolean ollamaModelLooksMultimodal(String model) {
        if (model == null || model.isBlank()) return false;
        String m = model.toLowerCase(Locale.ROOT);
        return m.contains("llava") || m.contains("vision") || m.contains("minicpm")
                || m.contains("bakllava") || m.contains("moondream")
                || m.contains("qwen2-vl") || m.contains("qwen2.5-vl") || m.contains("qwen-vl")
                || m.contains("minimax");
    }

    /** {@code null} = should run. {@code "off"} / {@code "no-provider"} = skip. */
    public static String skipReason(boolean flag, boolean hasVision) {
        if (!flag) return "off";
        if (!hasVision) return "no-provider";
        return null;
    }

    /** {@code null} when the skip is silent (flag off). */
    public static String skipMessage(String reason) {
        if (reason == null || reason.equals("off")) return null;
        if (reason.equals("no-provider")) {
            return "vision verify skipped — no vision-capable provider "
                    + "(enable Gemini 2.5-flash, or a multimodal Ollama like llava / qwen2-vl / minimax; "
                    + "text-only models cannot see the preview)";
        }
        return "vision verify skipped";
    }

    public static String intendedSummary(String name, VoxelModel model) {
        String n = (name == null || name.isBlank()) ? "(unnamed)" : name.trim();
        if (model == null || model.size() == 0) {
            return "name=" + n + " blocks=0";
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        java.util.TreeSet<String> pal = new java.util.TreeSet<>();
        for (Map.Entry<Long, String> e : model.entries()) {
            int x = VoxelModel.xOf(e.getKey()), y = VoxelModel.yOf(e.getKey()), z = VoxelModel.zOf(e.getKey());
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            if (e.getValue() != null) pal.add(e.getValue());
        }
        int w = maxX - minX + 1, h = maxY - minY + 1, d = maxZ - minZ + 1;
        StringBuilder sb = new StringBuilder();
        sb.append("name=").append(n)
          .append(" blocks=").append(model.size())
          .append(" bbox=").append(w).append('x').append(h).append('x').append(d)
          .append(" palette=");
        int i = 0;
        for (String p : pal) {
            if (i++ > 0) sb.append(',');
            if (i > 8) { sb.append("…"); break; }
            sb.append(p);
        }
        return sb.toString();
    }

    public static String verifyUserPrompt(String summary) {
        return "Intended build:\n" + (summary == null ? "" : summary)
                + "\nLook at the attached isometric preview. Does it match? JSON only.";
    }

    /**
     * Parse a vision reply. Garbage / missing JSON → {@code parsed=false} and
     * {@code ok=true} so we NEVER repair on an unreadable answer.
     */
    public static Verdict parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Verdict(true, "empty reply", List.of(), false);
        }
        String s = raw.trim();
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        if (a < 0 || b <= a) return new Verdict(true, "not json", List.of(), false);
        String json = s.substring(a, b + 1);
        try {
            Object doc = new org.yaml.snakeyaml.Yaml().load(json);
            if (!(doc instanceof Map<?, ?> m)) return new Verdict(true, "not a map", List.of(), false);
            if (!m.containsKey("ok")) return new Verdict(true, "missing ok", List.of(), false);
            boolean ok = truthy(m.get("ok"));
            String reason = m.get("reason") == null ? "" : String.valueOf(m.get("reason")).trim();
            List<String> notes = new ArrayList<>();
            Object n = m.get("notes");
            if (n instanceof List<?> nl) {
                for (Object o : nl) if (o != null && notes.size() < 8) notes.add(String.valueOf(o));
            }
            return new Verdict(ok, reason, List.copyOf(notes), true);
        } catch (Throwable t) {
            return new Verdict(true, "unparseable", List.of(), false);
        }
    }

    private static boolean truthy(Object o) {
        if (o instanceof Boolean b) return b;
        if (o instanceof Number n) return n.intValue() != 0;
        if (o == null) return false;
        String s = String.valueOf(o).trim().toLowerCase(Locale.ROOT);
        return s.equals("true") || s.equals("yes") || s.equals("ok");
    }

    public static String repairPrompt(String originalPrompt, Verdict v) {
        String base = originalPrompt == null ? "" : originalPrompt.trim();
        StringBuilder sb = new StringBuilder(base);
        sb.append("\n\nVISION VERIFY found problems: ")
          .append(v == null || v.reason().isBlank() ? "preview did not match the intended spec" : v.reason());
        if (v != null) {
            for (String n : v.notes()) sb.append("\n- ").append(n);
        }
        sb.append("\nFix ONLY those and re-emit the JSON build spec (no markdown, no apology).");
        return sb.toString();
    }

    public static String reportLine(String botId, Verdict v, boolean contract, boolean repaired) {
        String id = botId == null ? "GH000" : botId;
        if (v == null || !v.parsed()) {
            return "§7[" + id + "] 👁 vision verify: unreadable reply — keeping the staged build (no repair)";
        }
        if (v.ok()) {
            return "§a[" + id + "] 👁 vision verify: PASS"
                    + (v.reason().isBlank() ? "" : " — " + v.reason());
        }
        if (contract) {
            return "§e[" + id + "] 👁 vision verify: notes only (pasted spec is the contract) — "
                    + (v.reason().isBlank() ? "mismatch" : v.reason());
        }
        if (repaired) {
            return "§a[" + id + "] 👁 vision verify: issues — "
                    + (v.reason().isBlank() ? "mismatch" : v.reason())
                    + " → 1 repair pass restaged";
        }
        return "§e[" + id + "] 👁 vision verify: issues — "
                + (v.reason().isBlank() ? "mismatch" : v.reason())
                + " (repair pass produced nothing usable — keeping original)";
    }
}
