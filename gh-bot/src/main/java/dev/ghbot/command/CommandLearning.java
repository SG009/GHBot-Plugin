package dev.ghbot.command;

import dev.ghbot.bot.GHBot;
import dev.ghbot.log.WIBLogger;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Phase 11 — Command Learning (Pillar E). GH-bot reads the server's command
 * registry into a Command Catalog, then can run commands as console
 * (full trust — your call) with an audit trail. This is the gateway to
 * other plugins: "use fancynpc", "reload essentials", etc.
 *
 * v0.21 guardrails: systemic commands (/stop, /reload, /deop, /op, bans,
 * file-destruction patterns) are NOT run automatically — they mint a
 * CONF-… confirmation token that must be confirmed explicitly (chat or
 * console: `confirm <token>`), even when invoked by the AI. Never bypassable.
 */
public class CommandLearning {

    private final JavaPlugin plugin;
    private final WIBLogger log;
    private final Map<String, String> catalog = new LinkedHashMap<>(); // name -> usage/desc

    /** Systemic commands that always need explicit confirmation (hardcoded, non-bypassable). */
    private static final java.util.Set<String> SENSITIVE_COMMANDS = java.util.Set.of(
            "stop", "restart", "reload", "rl", "shutdown", "save-off", "save-all-off",
            "op", "deop", "ban", "ban-ip", "pardon", "pardon-ip", "kickall", "killall",
            "whitelist"
    );

    /** Pending confirmations: token -> (bot, line, expiry). */
    private record Pending(GHBot bot, String line, long expiryMs) {}
    private static final long CONFIRM_TTL_MS = 5 * 60_000L;
    private final Map<String, Pending> pendingConfirm = new LinkedHashMap<>();

    /** Result of a guarded dispatch. */
    public record DispatchResult(String status, String token, String message) {} // ran | failed | needs-confirm | unknown | expired

    public CommandLearning(JavaPlugin plugin, WIBLogger log) {
        this.plugin = plugin;
        this.log = log;
    }

    /** v0.22.2 — Pillar 3 capture service (wired once at startup; see GHBotPlugin). */
    private volatile CmdOutputCapture capture;

    public void setCapture(CmdOutputCapture c) { this.capture = c; }

    /** The shared cmd-output capture service (null before startup wiring finishes). */
    public CmdOutputCapture capture() { return capture; }

    /**
     * True for systemic / destructive commands that must never auto-run from
     * chat, the web console, or the AI agent without an explicit confirmation.
     */
    public static boolean isSensitive(String line) {
        if (line == null) return false;
        String t = line.trim().toLowerCase();
        // file / disk destruction patterns (cross-platform)
        if (t.matches(".*\\brm\\s+-r[f]?\\b.*") || t.contains("rmdir")
                || t.matches(".*\\bdel(ete)?\\s+/[a-z]*s\\b.*")
                || t.contains("\\rm -rf") || t.matches(".*\\bformat\\s+.*")) return true;
        // first token, strip namespace (minecraft:stop) and leading slash
        String first = t.replaceAll("^[/\\\\]", "");
        int sp = first.indexOf(' ');
        if (sp >= 0) first = first.substring(0, sp);
        int colon = first.indexOf(':');
        if (colon >= 0) first = first.substring(colon + 1);
        // "whitelist off/remove" is disruptive; plain "whitelist list" is harmless
        if (first.equals("whitelist")) {
            return !t.contains("whitelist list") && !t.contains("whitelist on");
        }
        return SENSITIVE_COMMANDS.contains(first);
    }

    /** (Re)build the catalog from Bukkit's command map. */
    public synchronized int refresh() {
        catalog.clear();
        try {
            var cmdMap = Bukkit.getCommandMap();
            if (cmdMap == null) return 0;
            for (Map.Entry<String, Command> e : cmdMap.getKnownCommands().entrySet()) {
                String name = e.getKey();
                if (name == null || name.isEmpty()) continue;
                Command cmd = e.getValue();
                String usage = cmd.getUsage() == null || cmd.getUsage().isEmpty() ? "" : cmd.getUsage();
                String desc = cmd.getDescription() == null ? "" : cmd.getDescription();
                catalog.put(name.toLowerCase(), (desc.isEmpty() ? "" : desc + " · ") + usage);
            }
        } catch (Throwable t) {
            log.error("Could not refresh command catalog", t);
        }
        log.info("Command catalog refreshed: " + catalog.size() + " commands.");
        return catalog.size();
    }

    public synchronized int size() { return catalog.size(); }

    /** Register/override a catalog entry (used by tests + future AI-managed entries). */
    public synchronized void registerAlias(String name, String meta) {
        catalog.put(name.toLowerCase(), meta);
    }

    public synchronized Map<String, String> all() { return new LinkedHashMap<>(catalog); }

    /** Run a command line as console (full trust), audit-logged. Returns success. */
    public boolean dispatchAsConsole(GHBot bot, String line) {
        // v0.21.11 — Bukkit.dispatchCommand MUST run on the main thread. This is the
        // single choke point for chat cmd / web /cmd / tool cmd / /gh, so dispatch
        // always hops here regardless of the caller's thread.
        try {
            boolean ok = dev.ghbot.core.MainThread.call(() ->
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line));
            audit(bot, "cmd", line, ok);
            return ok;
        } catch (Throwable t) {
            audit(bot, "cmd", line, false);
            return false;
        }
    }

    /**
     * Guarded dispatch: sensitive commands are blocked and mint a confirmation
     * token instead of running. Everything else runs as before.
     */
    public synchronized DispatchResult dispatchGuarded(GHBot bot, String line) {
        if (isSensitive(line)) {
            pruneExpired();
            String token = "CONF-" + System.currentTimeMillis() % 100000000L
                    + "-" + ThreadLocalRandom.current().nextInt(100, 1000);
            pendingConfirm.put(token, new Pending(bot, line, System.currentTimeMillis() + CONFIRM_TTL_MS));
            log.commandLog("[" + bot.id() + "] cmd → \"" + line
                    + "\" → BLOCKED (systemic — needs confirm, token=" + token + ")");
            return new DispatchResult("needs-confirm", token,
                    "Systemic command blocked. Confirm it with: confirm " + token);
        }
        boolean ok = dispatchAsConsole(bot, line);
        return ok ? new DispatchResult("ran", null, "ran: " + line)
                  : new DispatchResult("failed", null, "failed: " + line);
    }

    /** Confirm a previously blocked command (expires after 5 minutes).
     *  v0.22.2 — confirmed commands run through the SAME output capture (D5):
     *  the reply includes what the command actually printed. */
    public synchronized DispatchResult confirm(String token) {
        Pending p = pendingConfirm.remove(token == null ? "" : token.trim());
        if (p == null) return new DispatchResult("unknown", token, "No pending command for that token.");
        if (System.currentTimeMillis() > p.expiryMs) {
            return new DispatchResult("expired", token, "Confirmation token expired — re-run the command.");
        }
        if (capture != null) {
            dev.ghbot.command.CmdOutput out = capture.capture(p.bot, p.line);
            boolean ok = out.status == dev.ghbot.command.CmdOutput.Status.RAN;
            String msg = (ok ? "confirmed + ran: " : "confirmed but command failed: ") + p.line
                    + "\n" + out.inlineGame();
            audit(p.bot, "cmd", p.line, ok);
            return new DispatchResult(ok ? "ran" : "failed", token, msg);
        }
        boolean ok = dispatchAsConsole(p.bot, p.line);
        return ok ? new DispatchResult("ran", token, "confirmed + ran: " + p.line)
                  : new DispatchResult("failed", token, "confirmed but command failed: " + p.line);
    }

    /**
     * Run several commands in one shot (v0.21.3) — split on `;` or newlines.
     * Each line goes through the SAME guardrails individually (systemic →
     * blocked with its own CONF-… token). Returns a compact audit summary,
     * e.g. "2 ran, 1 blocked, 1 failed". This is what makes multi-step admin
     * tasks ("create rank PRO, PREMIUM, ADMIN in LuckPerms") one instruction.
     */
    public synchronized String dispatchGuardedMany(GHBot bot, String multi) {
        StringBuilder sb = new StringBuilder();
        String[] lines = (multi == null ? "" : multi).split(";|\\r?\\n");
        int ran = 0, blocked = 0, failed = 0;
        for (String raw : lines) {
            String l = raw.trim();
            if (l.isEmpty()) continue;
            DispatchResult r = dispatchGuarded(bot, l);
            switch (r.status()) {
                case "ran" -> { ran++; sb.append("✓ ").append(l).append('\n'); }
                case "needs-confirm" -> { blocked++; sb.append("⛔ ").append(l).append(" → ").append(r.message()).append('\n'); }
                default -> { failed++; sb.append("✗ ").append(l).append('\n'); }
            }
        }
        if (sb.length() == 0) return "nothing to run";
        sb.insert(0, ran + " ran · " + blocked + " blocked · " + failed + " failed\n");
        return sb.toString().trim();
    }

    /** v0.21.26 — dispatch a command and CAPTURE its output (for the AI to see).
     *  v0.22.2 — delegates to the Pillar-3 capture service (CapturingSender across all
     *  three message surfaces + a session JUL handler for plugins that log instead of
     *  replying). Signature + text contract ("✓ ran … OUTPUT: …" / "⛔ BLOCKED …")
     *  kept; the AI additionally learns the logs/cmd/ file reference when the output
     *  was truncated. Falls back to the legacy console path if the service isn't
     *  wired (early startup). */
    public String dispatchCaptured(GHBot bot, String line) {
        if (capture != null) {
            dev.ghbot.command.CmdOutput out = capture.capture(bot, line);
            audit(bot, "cmd", line, out.status != dev.ghbot.command.CmdOutput.Status.FAILED);
            return out.inlineWeb();
        }
        // legacy fallback (capture service not yet constructed)
        if (isSensitive(line)) {
            DispatchResult blocked = dispatchGuarded(bot, line);   // mints CONF token, does NOT run
            audit(bot, "cmd", line, false);
            return "⛔ BLOCKED: " + line + " → " + blocked.message();
        }
        dev.ghbot.agent.ToolBridge.Capture cap = new dev.ghbot.agent.ToolBridge.Capture();
        boolean ok;
        try {
            ok = dev.ghbot.core.MainThread.call(() ->
                    Bukkit.dispatchCommand((org.bukkit.command.CommandSender) cap, line));
        } catch (Throwable t) {
            ok = false;
        }
        if (!ok) {
            try {
                ok = dev.ghbot.core.MainThread.call(() ->
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line));
            } catch (Throwable t) {
                ok = false;
            }
        }
        String out = cap.text();
        audit(bot, "cmd", line, ok);
        if (!ok) return "✗ failed: " + line;
        if (out.isEmpty()) {
            return "✓ ran: " + line + "\n[output went to the server console — not capturable by the tool]";
        }
        return "✓ ran: " + line + "\nOUTPUT:\n" + out;
    }

    /** Number of pending confirmations (with any expired ones dropped). */
    public synchronized int pendingCount() {
        pruneExpired();
        return pendingConfirm.size();
    }

    private void pruneExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Pending>> it = pendingConfirm.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expiryMs < now) it.remove();
        }
    }

    /** Natural-language helper: pick a catalog command by keywords. */
    public synchronized String suggest(String keywords) {
        String k = keywords.toLowerCase();
        List<String> hits = new ArrayList<>();
        for (Map.Entry<String, String> e : catalog.entrySet()) {
            String name = e.getKey();
            String meta = e.getValue().toLowerCase();
            for (String w : k.split("\\s+")) {
                if (w.length() >= 3 && (name.contains(w) || meta.contains(w))) {
                    hits.add(name);
                    break;
                }
            }
        }
        return hits.isEmpty() ? null : hits.get(0);
    }

    private void audit(GHBot bot, String kind, String what, boolean ok) {
        log.commandLog("[" + bot.id() + "] " + kind + " → \"" + what + "\" → " + (ok ? "ok" : "FAIL"));
    }
}
