package dev.ghbot.command;

import dev.ghbot.bot.GHBot;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v0.28.0 — command-script upload (📎 .txt / .cmd / .mcfunction).
 *
 * Parser is the CONTRACT for the command lines (comments stripped, order kept),
 * same idea as a pasted JSON build spec. The Technician / admin prompt only
 * FILLS placeholders and SKIPS steps — it never invents extra server commands.
 *
 * Preview-first: upload never executes. Admin says {@code run} (or fills
 * YOURNAME / skip step N) afterwards. Systemic lines still mint CONF tokens.
 */
public final class CommandScript {

    public static final int MAX_BYTES = 64 * 1024;
    public static final int MAX_COMMANDS = 80;
    public static final long PENDING_TTL_MS = 30 * 60_000L;

    private CommandScript() {}

    public record Line(int number, int step, String section, String command,
                       boolean sensitive, List<String> placeholders) {
        public Line {
            if (section == null) section = "";
            if (command == null) command = "";
            if (placeholders == null) placeholders = List.of();
            else placeholders = List.copyOf(placeholders);
        }
        public boolean needsFill() { return !placeholders.isEmpty(); }
    }

    public record Parsed(String filename, String purpose, List<String> comments,
                         List<Line> commands, int commentLines, int truncated, String error) {
        public Parsed {
            if (filename == null) filename = "";
            if (purpose == null) purpose = "";
            if (comments == null) comments = List.of();
            else comments = List.copyOf(comments);
            if (commands == null) commands = List.of();
            else commands = List.copyOf(commands);
        }
        public boolean ok() { return error == null || error.isBlank(); }
    }

    public record Plan(Parsed parsed, String prompt, List<Line> toRun, List<Line> skipped,
                       List<Line> needFill, List<Line> willConfirm) {
        public Plan {
            if (prompt == null) prompt = "";
            if (toRun == null) toRun = List.of(); else toRun = List.copyOf(toRun);
            if (skipped == null) skipped = List.of(); else skipped = List.copyOf(skipped);
            if (needFill == null) needFill = List.of(); else needFill = List.copyOf(needFill);
            if (willConfirm == null) willConfirm = List.of(); else willConfirm = List.copyOf(willConfirm);
        }
    }

    public record Pending(Parsed parsed, String prompt, long atMs) {}

    private static final ConcurrentHashMap<String, Pending> PENDING = new ConcurrentHashMap<>();

    public static void resetForTests() { PENDING.clear(); }

    public static boolean isScriptFilename(String name) {
        if (name == null) return false;
        String l = name.toLowerCase(Locale.ROOT);
        return l.endsWith(".txt") || l.endsWith(".cmd") || l.endsWith(".mcfunction");
    }

    /** True when a .txt is actually a JSON build spec that should be renamed. */
    public static boolean looksLikeJsonSpec(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (!t.startsWith("{")) return false;
        String low = t.toLowerCase(Locale.ROOT);
        return low.contains("\"palette\"") && low.contains("\"blocks\"");
    }

    public static Parsed parse(String filename, byte[] body) {
        if (body == null || body.length == 0) return err(filename, "empty script");
        if (body.length > MAX_BYTES) return err(filename, "too large (max 64 KB)");
        return parse(filename, new String(body, StandardCharsets.UTF_8));
    }

    public static Parsed parse(String filename, String text) {
        String fn = filename == null ? "script.txt" : filename;
        if (text == null) return err(fn, "empty script");
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return err(fn, "too large (max 64 KB)");
        }
        if (looksLikeJsonSpec(text)) {
            return err(fn, "this looks like a JSON build spec — rename to .json and upload again");
        }
        String[] rawLines = text.split("\\r?\\n", -1);
        List<Line> commands = new ArrayList<>();
        List<String> comments = new ArrayList<>();
        String purpose = "";
        int step = 0;
        String section = "";
        int truncated = 0;
        Pattern sectionPat = Pattern.compile("^\\s*#\\s*(\\d+)\\)\\s*(.+)$");

        for (int i = 0; i < rawLines.length; i++) {
            String trimmed = rawLines[i] == null ? "" : rawLines[i].trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("#") || trimmed.startsWith("//")) {
                comments.add(trimmed);
                Matcher sm = sectionPat.matcher(trimmed);
                if (sm.find()) {
                    try { step = Integer.parseInt(sm.group(1)); } catch (NumberFormatException ignored) {}
                    section = sm.group(2).trim();
                }
                if (purpose.isEmpty()) {
                    String p = stripBanner(trimmed);
                    if (p.length() >= 8) purpose = p;
                }
                continue;
            }
            String cmd = trimmed;
            if (cmd.startsWith("/")) cmd = cmd.substring(1).trim();
            int trail = cmd.indexOf(" #");
            if (trail >= 0) cmd = cmd.substring(0, trail).trim();
            if (cmd.isEmpty()) continue;
            if (commands.size() >= MAX_COMMANDS) { truncated++; continue; }
            commands.add(new Line(i + 1, step, section, cmd,
                    CommandLearning.isSensitive(cmd), placeholders(cmd)));
        }
        if (commands.isEmpty() && comments.isEmpty()) return err(fn, "empty script");
        if (commands.isEmpty()) return err(fn, "no commands (only comments)");
        return new Parsed(fn, purpose, comments, commands, comments.size(), truncated, null);
    }

    private static Parsed err(String fn, String msg) {
        return new Parsed(fn, "", List.of(), List.of(), 0, 0, msg);
    }

    static String stripBanner(String comment) {
        if (comment == null) return "";
        String t = comment.replaceFirst("^#+\\s*", "").replaceFirst("^/+\\s*", "").trim();
        if (t.matches("^[=*#._-]{3,}$")) return "";
        t = t.replaceAll("^-+\\s*", "").replaceAll("\\s*-+$", "").trim();
        if (t.matches("^[=*#._-]{3,}$")) return "";
        return t;
    }

    public static List<String> placeholders(String cmd) {
        List<String> out = new ArrayList<>();
        if (cmd == null || cmd.isBlank()) return out;
        if (Pattern.compile("YOURNAME", Pattern.CASE_INSENSITIVE).matcher(cmd).find()) out.add("YOURNAME");
        Matcher angle = Pattern.compile("<([A-Za-z][A-Za-z0-9_-]*)>").matcher(cmd);
        while (angle.find()) {
            String tok = "<" + angle.group(1) + ">";
            if (!out.contains(tok)) out.add(tok);
        }
        Matcher brace = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_-]*)\\}").matcher(cmd);
        while (brace.find()) {
            String tok = "{" + brace.group(1) + "}";
            if (!out.contains(tok)) out.add(tok);
        }
        Matcher caps = Pattern.compile("\\b(TODO|CHANGEME|PLACEHOLDER)\\b").matcher(cmd);
        while (caps.find()) {
            if (!out.contains(caps.group(1))) out.add(caps.group(1));
        }
        return out;
    }

    public static Plan plan(Parsed p, String prompt) {
        if (p == null) p = err("script.txt", "empty script");
        String pr = prompt == null ? "" : prompt.trim();
        Set<Integer> skipSteps = parseSkipSteps(pr);
        Set<String> skipKw = parseSkipKeywords(pr);
        String name = parsePlayerName(pr);
        List<Line> toRun = new ArrayList<>();
        List<Line> skipped = new ArrayList<>();
        List<Line> needFill = new ArrayList<>();
        List<Line> willConfirm = new ArrayList<>();
        if (!p.ok()) return new Plan(p, pr, toRun, skipped, needFill, willConfirm);
        for (Line L : p.commands()) {
            if (skipSteps.contains(L.step()) || keywordSkip(L, skipKw)) {
                skipped.add(L);
                continue;
            }
            Line filled = fill(L, name);
            if (filled.needsFill()) {
                needFill.add(filled);
                continue;
            }
            toRun.add(filled);
            if (filled.sensitive()) willConfirm.add(filled);
        }
        return new Plan(p, pr, toRun, skipped, needFill, willConfirm);
    }

    static Line fill(Line L, String name) {
        if (L == null) return new Line(0, 0, "", "", false, List.of());
        if (name == null || name.isBlank()) return L;
        String c = L.command();
        c = c.replaceAll("(?i)YOURNAME", Matcher.quoteReplacement(name));
        c = c.replace("<player>", name).replace("<name>", name).replace("<username>", name);
        c = c.replace("{player}", name).replace("{name}", name);
        if (c.equals(L.command())) return L;
        return new Line(L.number(), L.step(), L.section(), c,
                CommandLearning.isSensitive(c), placeholders(c));
    }

    static boolean keywordSkip(Line L, Set<String> kws) {
        if (kws == null || kws.isEmpty() || L == null) return false;
        String hay = ((L.section() == null ? "" : L.section()) + " " + L.command()).toLowerCase(Locale.ROOT);
        for (String kw : kws) {
            if (kw != null && kw.length() >= 3 && hay.contains(kw)) return true;
        }
        return false;
    }

    public static Set<Integer> parseSkipSteps(String prompt) {
        Set<Integer> out = new LinkedHashSet<>();
        if (prompt == null || prompt.isBlank()) return out;
        Matcher m = Pattern.compile("(?i)skip\\s+(?:step\\s+)?(\\d+)").matcher(prompt);
        while (m.find()) {
            try { out.add(Integer.parseInt(m.group(1))); } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    public static Set<String> parseSkipKeywords(String prompt) {
        Set<String> out = new LinkedHashSet<>();
        if (prompt == null || prompt.isBlank()) return out;
        Matcher m = Pattern.compile("(?i)skip(?:\\s+the)?\\s+([a-z][a-z0-9_-]{2,})").matcher(prompt);
        while (m.find()) {
            String kw = m.group(1).toLowerCase(Locale.ROOT);
            if (kw.equals("step") || kw.equals("the") || kw.equals("script")
                    || kw.equals("this") || kw.equals("that")) continue;
            out.add(kw);
        }
        return out;
    }

    /** Player / IGN from an admin prompt. Null when none.
     *  v0.28.1 — hardened: leading \b so "ign"/"use" can't match inside words
     *  ("align"/"because"), and reject "your"/"my"/"the" so pasting the file's own
     *  comments ("replace YOURNAME with your Minecraft username") never fills "your". */
    public static String parsePlayerName(String prompt) {
        if (prompt == null || prompt.isBlank()) return null;
        Matcher m = Pattern.compile(
                "(?i)\\b(?:my\\s+name\\s+is|i(?:['’]m|\\s+am)|ign\\s*[:=]?|player(?:\\s+name)?\\s*[:=]|"
                        + "user(?:name)?\\s*[:=]|yourname\\s*(?:is|=)|replace\\s+yourname\\s+with|"
                        + "use\\s+(?:player|name))\\s+([.\\w-]{2,32})")
                .matcher(prompt.trim());
        if (m.find()) {
            String n = m.group(1).replaceAll("[^\\w.-]+$", "");
            if (n.length() >= 2 && !n.equalsIgnoreCase("skip") && !n.equalsIgnoreCase("step")
                    && !n.equalsIgnoreCase("your") && !n.equalsIgnoreCase("my") && !n.equalsIgnoreCase("the")) return n;
        }
        // Bedrock-style ".Name" as the whole prompt
        String t = prompt.trim();
        if (t.startsWith(".") && t.matches("\\.[\\w-]{2,31}")) return t;
        return null;
    }

    public static boolean isAmendPrompt(String text) {
        if (text == null || text.isBlank()) return false;
        return parsePlayerName(text) != null
                || !parseSkipSteps(text).isEmpty()
                || !parseSkipKeywords(text).isEmpty();
    }

    public static String preview(Plan plan) {
        if (plan == null || plan.parsed() == null) return "no script";
        Parsed p = plan.parsed();
        if (!p.ok()) return "⚠️ **script error:** " + p.error();
        StringBuilder sb = new StringBuilder();
        sb.append("📜 **").append(p.filename()).append("**");
        if (!p.purpose().isBlank()) sb.append(" — ").append(p.purpose());
        sb.append('\n');
        sb.append(p.commands().size()).append(" command(s) parsed");
        if (p.commentLines() > 0) sb.append(" · ").append(p.commentLines()).append(" comment line(s) ignored");
        if (p.truncated() > 0) sb.append(" · ").append(p.truncated()).append(" trimmed (cap ").append(MAX_COMMANDS).append(')');
        sb.append(" · **not run yet**\n");

        if (!plan.needFill().isEmpty()) {
            sb.append("\n⚠ placeholders still open (will NOT run until filled):\n");
            int n = 0;
            for (Line L : plan.needFill()) {
                if (n++ >= 8) { sb.append("  · +").append(plan.needFill().size() - 8).append(" more\n"); break; }
                sb.append("  · L").append(L.number()).append(" `").append(L.command()).append("`  (")
                        .append(String.join(", ", L.placeholders())).append(")\n");
            }
        }
        if (!plan.willConfirm().isEmpty()) {
            sb.append("\n⛔ will need confirm (CONF token) when you run:\n");
            for (Line L : plan.willConfirm()) {
                sb.append("  · L").append(L.number()).append(" `").append(L.command()).append("`\n");
            }
        }
        if (!plan.skipped().isEmpty()) {
            sb.append("\n⏭ skipped by your prompt (").append(plan.skipped().size()).append("):\n");
            int n = 0;
            for (Line L : plan.skipped()) {
                if (n++ >= 6) { sb.append("  · +").append(plan.skipped().size() - 6).append(" more\n"); break; }
                sb.append("  ·");
                if (L.step() > 0) sb.append(" step ").append(L.step());
                sb.append(" `").append(L.command()).append("`\n");
            }
        }
        sb.append("\n✅ ready to run (").append(plan.toRun().size()).append("):\n");
        int n = 0;
        for (Line L : plan.toRun()) {
            if (n++ >= 12) {
                sb.append("  · +").append(plan.toRun().size() - 12).append(" more\n");
                break;
            }
            sb.append("  ").append(n).append(". ");
            if (L.step() > 0) sb.append('[').append(L.step()).append("] ");
            sb.append('`').append(L.command()).append("`\n");
        }
        if (plan.toRun().isEmpty()) sb.append("  (none — fill placeholders or skip steps first)\n");

        sb.append("\nSay **run** to execute the ready lines in order");
        if (!plan.needFill().isEmpty()) sb.append(" **after filling holes**");
        sb.append(".\n");
        sb.append("Fill: `my name is .YourIGN` · Skip: `skip step 6` / `skip the boss` · Drop: `drop the script`");
        return sb.toString().trim();
    }

    /* ── pending stash (one script per bot — phone is single-admin) ── */

    public static void stash(String botId, Parsed parsed, String prompt) {
        if (botId == null || botId.isBlank() || parsed == null) return;
        PENDING.put(botId, new Pending(parsed, prompt == null ? "" : prompt, System.currentTimeMillis()));
    }

    public static boolean hasPending(String botId) {
        return peek(botId) != null;
    }

    public static Pending peek(String botId) {
        if (botId == null) return null;
        Pending p = PENDING.get(botId);
        if (p == null) return null;
        if (System.currentTimeMillis() - p.atMs() > PENDING_TTL_MS) {
            PENDING.remove(botId, p);
            return null;
        }
        return p;
    }

    public static Pending drop(String botId) {
        if (botId == null) return null;
        return PENDING.remove(botId);
    }

    public static Pending amend(String botId, String extraPrompt) {
        Pending cur = peek(botId);
        if (cur == null) return null;
        String merged = cur.prompt();
        if (extraPrompt != null && !extraPrompt.isBlank()) {
            merged = (merged == null || merged.isBlank()) ? extraPrompt.trim()
                    : merged + " " + extraPrompt.trim();
        }
        Pending next = new Pending(cur.parsed(), merged, System.currentTimeMillis());
        PENDING.put(botId, next);
        return next;
    }

    /**
     * Shared body for the {@code script} tool AND the in-game/console command.
     * Preview / drop / amend never touch the world. {@code run} refuses while
     * placeholders remain, then dispatches ready lines through the existing
     * cmd capture (per-line CONF).
     */
    public static String handle(GHBot bot, CommandLearning learning, String[] args) {
        String id = bot == null ? "" : bot.id();
        String sub = (args == null || args.length == 0) ? "status" : args[0].toLowerCase(Locale.ROOT);
        Pending pend = peek(id);
        switch (sub) {
            case "status", "preview", "" -> {
                if (pend == null) return "no pending command script — 📎 a .txt / .cmd / .mcfunction in the console first";
                return preview(plan(pend.parsed(), pend.prompt()));
            }
            case "drop", "cancel", "forget" -> {
                if (drop(id) == null) return "no pending script to drop";
                return "dropped pending script";
            }
            case "amend" -> {
                if (pend == null) return "no pending script to amend";
                String extra = "";
                if (args != null && args.length > 1) {
                    extra = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                }
                Pending next = amend(id, extra);
                return preview(plan(next.parsed(), next.prompt()));
            }
            case "run", "execute" -> {
                if (pend == null) return "no pending command script — 📎 a .txt first";
                Plan pl = plan(pend.parsed(), pend.prompt());
                if (!pl.needFill().isEmpty()) {
                    return preview(pl) + "\n\n❌ not running — fill placeholders first (or skip those lines).";
                }
                if (pl.toRun().isEmpty()) {
                    return preview(pl) + "\n\n❌ nothing to run.";
                }
                drop(id); // prevent double-run
                return execute(bot, learning, pl);
            }
            default -> {
                return "usage: script run|status|drop  (or 📎 a .txt command list in the console)";
            }
        }
    }

    public static String execute(GHBot bot, CommandLearning learning, Plan plan) {
        if (plan == null || plan.toRun().isEmpty()) return "nothing to run";
        if (!plan.needFill().isEmpty()) return "not running — fill placeholders first";
        StringBuilder multi = new StringBuilder();
        for (Line L : plan.toRun()) {
            if (multi.length() > 0) multi.append('\n');
            multi.append(L.command());
        }
        String header = "📜 ran script **" + plan.parsed().filename() + "** ("
                + plan.toRun().size() + " line(s))\n";
        if (learning == null) return header + "(no command learning — not dispatched)";
        if (learning.capture() != null) {
            java.util.List<CmdOutput> outs = learning.capture().captureMany(bot, multi.toString());
            return header + CmdOutputCapture.joinInline(outs, false);
        }
        return header + learning.dispatchGuardedMany(bot, multi.toString());
    }
}
