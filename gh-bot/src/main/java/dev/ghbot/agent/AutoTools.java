package dev.ghbot.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v0.21.10 — Server-side AUTO-TOOL detection. The Technician brain (especially
 * cloud models like MiniMax) sometimes replies as a plain chatbot instead of
 * emitting ⟦tool:…⟧, so nothing actually runs (hallucinated scans/results).
 * When the user's message is clearly an imperative — "scan 100 at 86 86 262",
 * "find diamond_ore", "build a house", "status", "edit …", "admin set motd …" —
 * WE detect it here and run the tool on the server, then feed the REAL result
 * to the model to summarize. Deterministic: the eyes actually see.
 */
public final class AutoTools {

    public record Call(String name, String[] args, String display) {}

    private AutoTools() {}

    /** Detect an imperative tool request in a chat message, or null. */
    public static Call detect(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty()) return null;
        String low = t.toLowerCase();

        // ── server command catalog ──
        if (low.matches(".*(what commands|list commands|command list|show commands|available commands|what can (you|i) do|help me).*")
                && !low.contains("my tools")) {
            return new Call("catalog", new String[0], "catalog");
        }

        // ── status ──
        if (low.startsWith("status") || low.matches(".*(how is the server|report server|server status|server condition|server health|how\\s+is\\s+the\\s+server).*")) {
            return new Call("status", new String[0], "status");
        }
        // ── players ──
        if (low.matches(".*(who'?s? online|players? online|online players|list players).*")) {
            return new Call("players", new String[0], "players");
        }
        // ── worlds ──
        if (low.matches("^(worlds?|list worlds|which worlds).*")) {
            return new Call("worlds", new String[0], "worlds");
        }

        // ── scan: "scan [radius] [x y z]" / "scan here/me" ──
        if (low.startsWith("scan")) {
            List<Integer> nums = ints(t);
            String[] args;
            if (nums.isEmpty()) args = new String[]{"50"};
            else if (nums.size() == 1) args = new String[]{String.valueOf(nums.get(0))};
            else if (nums.size() == 2) args = new String[]{String.valueOf(nums.get(0))};
            else if (nums.size() == 3) args = new String[]{"50", String.valueOf(nums.get(0)), String.valueOf(nums.get(1)), String.valueOf(nums.get(2))};
            else args = new String[]{String.valueOf(nums.get(0)), String.valueOf(nums.get(1)), String.valueOf(nums.get(2)), String.valueOf(nums.get(3))};
            return new Call("scan", args, "scan " + String.join(" ", args));
        }

        // ── find: "find <block> [radius]" ──
        Matcher mf = Pattern.compile("find\\s+([a-z0-9_:]+)(?:\\s+(\\d+))?").matcher(low);
        if (low.startsWith("find") && mf.find()) {
            String[] args = mf.group(2) != null ? new String[]{mf.group(1), mf.group(2)} : new String[]{mf.group(1)};
            return new Call("find", args, "find " + String.join(" ", args));
        }

        // ── look: "look [at] x, y, z" ──
        Matcher ml = Pattern.compile("look(?:\\s+at)?\\s*(-?\\d+)[,\\s]+(-?\\d+)[,\\s]+(-?\\d+)").matcher(low);
        if (ml.find()) {
            return new Call("look", new String[]{ml.group(1), ml.group(2), ml.group(3)},
                    "look " + ml.group(1) + " " + ml.group(2) + " " + ml.group(3));
        }

        // ── where / locations ──
        Matcher mw = Pattern.compile("^where\\s+(\\S+)").matcher(low);
        if (mw.find()) return new Call("where", new String[]{mw.group(1)}, "where " + mw.group(1));
        if (low.matches(".*(list(-| )locations|where are (my )?saved locations).*")) {
            return new Call("list-locations", new String[0], "list-locations");
        }
        Matcher ms = Pattern.compile("^save(-| )location\\s+(\\S+)").matcher(low);
        if (ms.find()) return new Call("save-location", new String[]{ms.group(2)}, "save-location " + ms.group(2));

        // ── workers / deploy / undeploy ──
        if (low.matches("^(workers|list bots|list workers).*")) return new Call("workers", new String[0], "workers");
        Matcher md = Pattern.compile("^deploy\\s+(\\S+)(?:\\s+(\\S+))?").matcher(low);
        if (md.find()) {
            String[] args = md.group(2) != null ? new String[]{md.group(1), md.group(2)} : new String[]{md.group(1)};
            return new Call("deploy", args, "deploy " + String.join(" ", args));
        }
        Matcher mu = Pattern.compile("^undeploy\\s+(\\S+)").matcher(low);
        if (mu.find()) return new Call("undeploy", new String[]{mu.group(1)}, "undeploy " + mu.group(1));

        // ── review: deny / approve / redo (staged build) — plain words trigger them ──
        boolean isQuestion = low.contains("?") || low.contains("how do i") || low.contains("how to")
                || low.startsWith("can you") || low.contains("what is");
        if (low.matches(".*\\bdeny\\b.*") && !isQuestion) {
            return new Call("deny", new String[0], "deny");
        }
        if (low.matches(".*\\bapprove\\b.*") && !isQuestion) {
            return new Call("approve", new String[0], "approve");
        }
        if (low.matches(".*\\bredo\\b.*") && !isQuestion) {
            return new Call("redo", new String[0], "redo");
        }
        if (low.matches("^(i don'?t like (it|that|this)|i hate it|not a fan|remove the build|remove it|clear it|take it down|nope|no good|undo the build|dismiss)$")) {
            return new Call("deny", new String[0], "deny");
        }
        if (low.matches("^(i like it|looks? good|i love it|go ahead|yes build it|perfect|looks great|great job)$")) {
            return new Call("approve", new String[0], "approve");
        }

        // ── marker / avatar / critique / cancel / undo ──
        Matcher mm = Pattern.compile("^marker\\s+(\\S+)").matcher(low);
        if (mm.find()) return new Call("marker", new String[]{mm.group(1)}, "marker " + mm.group(1));
        if (low.matches("^avatar\\s+(on|off).*")) return new Call("avatar", new String[]{low.matches(".*\\bon\\b.*") ? "on" : "off"}, "avatar " + (low.matches(".*\\bon\\b.*") ? "on" : "off"));
        if (low.startsWith("critique")) return new Call("critique", new String[0], "critique");
        if (low.matches("^undo.*")) return new Call("undo", new String[0], "undo");
        if (low.matches("^cancel.*")) return new Call("cancel", new String[0], "cancel");

        // ── admin: "admin set motd …" / "admin read …" / "admin …" ──
        if (low.startsWith("admin ")) {
            String[] parts = t.split("\\s+");
            int start = 1;
            // v0.21.31 — drop a leading player-name token the model may have prefixed
            // (e.g. "admin .SerthGembel009 server.properties read" → "admin server.properties read")
            if (parts.length > 2 && parts[1].startsWith(".")) start = 2;
            String[] args = new String[parts.length - start];
            System.arraycopy(parts, start, args, 0, parts.length - start);
            return new Call("admin", args, "admin " + String.join(" ", args));
        }

        // ── confirm: a CONF-… token → confirm tool (actually runs the blocked command)
        if (low.contains("conf-") || low.matches(".*\\bconfirm\\b.*conf-.*")) {
            Matcher mc = Pattern.compile("(?i)conf-?\\d+-\\d+").matcher(t);
            if (mc.find()) return new Call("confirm", new String[]{mc.group()}, "confirm " + mc.group());
        }

        // ── cmd ──
        if (low.startsWith("cmd ")) {
            return new Call("cmd", new String[]{t.substring(4).trim()}, "cmd " + t.substring(4).trim());
        }

        // ── terraform / set / replace / paste / schem ──
        if (low.startsWith("terraform ")) return new Call("terraform", t.split("\\s+", 2)[1].split("\\s+"), "terraform …");
        if (low.startsWith("set ")) return new Call("set", t.split("\\s+", 2)[1].split("\\s+"), "set …");
        if (low.startsWith("replace ")) return new Call("replace", t.split("\\s+", 2)[1].split("\\s+"), "replace …");
        if (low.startsWith("paste ")) return new Call("paste", t.split("\\s+", 2)[1].split("\\s+"), "paste …");
        if (low.startsWith("schem ")) return new Call("schem", t.split("\\s+", 2)[1].split("\\s+"), "schem …");

        // ── plan / edit / build (need content after the verb) ──
        if (low.startsWith("plan ") && low.length() > 5) return new Call("plan", new String[]{t.substring(5).trim()}, "plan " + t.substring(5).trim());
        if (low.startsWith("edit ") && low.length() > 5) return new Call("edit", t.split("\\s+", 2)[1].split("\\s+"), "edit …");
        if (low.startsWith("build ") && low.length() > 6) return new Call("build", new String[]{t.substring(6).trim()}, "build " + t.substring(6).trim());

        return null;
    }

    /** All integers in a string (in order). */
    private static List<Integer> ints(String s) {
        List<Integer> out = new ArrayList<>();
        Matcher m = Pattern.compile("-?\\d+").matcher(s);
        while (m.find()) {
            try { out.add(Integer.parseInt(m.group())); } catch (NumberFormatException ignored) {}
        }
        return out;
    }
}
