package dev.ghbot.command;

import dev.ghbot.bot.GHBot;
import dev.ghbot.core.MainThread;
import dev.ghbot.log.WIBLogger;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * v0.22.2 — Pillar 3: one orchestration point for capturing `cmd` output.
 *
 * Design (docs/PLAN-pillar3-cmd-output-capture.md, approved D1 full scope):
 *   1. Guardrails first — sensitive commands mint a CONF token and never run
 *      (same behavior as before; callers keep the ⛔ text contract).
 *   2. Dispatch on the main thread through a CapturingSender (captures the
 *      authoritative sender-feed for legacy/Adventure/bungee reply paths),
 *      while a session-scoped java.util.logging Handler at the JUL root
 *      catches plugins that LOG instead of replying (bounded, self-filtered,
 *      deduped). Console-sender fallback preserved if the proxy dispatch
 *      reports unknown-command (ok == false).
 *   3. Routing (eyes-style): full merged text → logs/cmd/<file>.log when the
 *      inline reply would need truncation; the caller gets a bounded inline
 *      string for its surface.
 *
 * Headless note: everything except the live dispatch is smoke-testable
 * (see julFilter/julFormat and CmdOutput). The dispatch itself degrades to
 * FAILED in a headless context, which tests tolerate.
 */
public final class CmdOutputCapture {

    private final JavaPlugin plugin;
    private final WIBLogger log;
    private final CommandLearning learning;
    /** Self-line filter: our own plugin's JUL logger name (audit lines must not echo back). */
    private final String selfLoggerName;
    /** Log the "console fallback" note only once per JVM. */
    private static volatile boolean ffNoteLogged;

    public CmdOutputCapture(JavaPlugin plugin, WIBLogger log, CommandLearning learning) {
        this.plugin = plugin;
        this.log = log;
        this.learning = learning;
        String n = null;
        try { n = plugin == null ? null : plugin.getLogger().getName(); } catch (Throwable ignored) {}
        this.selfLoggerName = n;
    }

    /** FAILED detail when the server doesn't know the command at all. */
    static final String UNKNOWN_NOTE = "unknown to the server (try refresh / catalog <keyword>)";

    /** Capture one command line. Guardrails run first (blocked → CONF text, never executes). */
    public CmdOutput capture(GHBot bot, String line) {
        return capture(bot, line, false);
    }

    /**
     * Capture one command line.
     * v0.22.3 — bypassGuard is used ONLY by the CONF-… confirm flow: the token IS the
     * explicit confirmation, so re-running the guard would just mint a second token and
     * block again forever (the v0.22.2 confirm loop). All other callers keep the guard.
     */
    public CmdOutput capture(GHBot bot, String line, boolean bypassGuard) {
        if (!bypassGuard && CommandLearning.isSensitive(line)) {
            var blocked = learning.dispatchGuarded(bot, line); // mints CONF-… token; does NOT run
            return blockedOutput(line, blocked.message());
        }
        CapturingSender cap = new CapturingSender();
        List<String> rawLogs = new ArrayList<>();
        List<String> rawLogNames = new ArrayList<>();
        Handler session = julHandler(rawLogs, rawLogNames);
        Logger root = Logger.getLogger("");
        boolean ok = false;
        boolean logged = false;
        String failureNote = null;
        try {
            root.addHandler(session);
            logged = true;
            // v0.22.3 — primary route: Paper's FeedbackForwardingSender. On 1.21,
            // dispatchCommand rejects plain custom senders (getListener throws), and the
            // old one-try/catch structure let that throw also skip the console fallback.
            CommandSender fwd = FeedbackForwarder.create(c ->
                    cap.sendMessage(net.kyori.adventure.identity.Identity.nil(), c,
                            net.kyori.adventure.audience.MessageType.SYSTEM));
            if (fwd != null) {
                final CommandSender dispatchSender = fwd;
                try {
                    ok = MainThread.call(() -> Bukkit.dispatchCommand(dispatchSender, line));
                } catch (Throwable t) {
                    // CommandException = the command DID reach the server (executed or
                    // arg-error) — never retry it via console (double-run risk).
                    failureNote = rootCause(t);
                }
                if (!ok && failureNote == null) {
                    ok = consoleDispatch(line);
                    if (!ok) failureNote = UNKNOWN_NOTE;
                }
            } else {
                // Spigot / old Paper / headless: console dispatch (pre-1.21 behavior).
                if (!ffNoteLogged) {
                    ffNoteLogged = true;
                    log.info("cmd capture: FeedbackForwardingSender not available on this server — "
                            + "using console dispatch (sender replies go to the console; JUL logs still merge).");
                }
                ok = consoleDispatch(line);
                if (!ok) failureNote = UNKNOWN_NOTE;
            }
        } catch (Throwable t) {
            ok = false;
            if (failureNote == null) failureNote = rootCause(t);
        } finally {
            if (logged) { try { root.removeHandler(session); } catch (Throwable ignored) {} }
        }

        String feed = cap.rawText();
        List<String> logLines = CmdOutput.mergeLogLines(rawLogs, rawLogNames, selfLoggerName, feed);
        if (!ok && failureNote == null && feed.isEmpty() && logLines.isEmpty()) {
            failureNote = UNKNOWN_NOTE; // e.g. the "stip" typo
        }
        // v0.22.3 — audit here, once, for ALL four dispatch paths (AI tool, web /cmd,
        // in-game, confirm) — previously only the AI path audited.
        log.commandLog("[" + bot.id() + "] cmd → \"" + line + "\" → " + (ok ? "ok" : "FAIL"));
        CmdOutput withoutFile = new CmdOutput(ok ? CmdOutput.Status.RAN : CmdOutput.Status.FAILED,
                line, feed, logLines, cap.droppedChars(), null, ok ? null : failureNote);

        // eyes-style routing: persist full output and reference it when the inline reply
        // would be truncated (web budget is the loosest — the file is written once per output).
        String inlineWeb = withoutFile.inlineWeb();
        boolean needsFile = !inlineWeb.equals(withoutFile.fullText()) || withoutFile.droppedFeedChars > 0;
        String fileRef = null;
        if (needsFile) {
            fileRef = log.writeCmdOutput(fileName(line), withoutFile.fullText());
            if (fileRef != null) {
                log.commandLog("[" + bot.id() + "] cmd out=" + fileRef + " (" + line + ")");
            }
        }
        // v0.22.3 — MUST carry the note through (FAILED reason) — the six-arg rebuild
        // in v0.22.2 silently dropped it (found via live repro: "✗ failed: stip" hid the why).
        return new CmdOutput(withoutFile.status, line, feed, logLines, withoutFile.droppedFeedChars,
                fileRef, withoutFile.note);
    }

    /** Capture many commands separated by ';' (same per-line guardrails + captures). */
    public List<CmdOutput> captureMany(GHBot bot, String multi) {
        List<CmdOutput> out = new ArrayList<>();
        for (String raw : (multi == null ? "" : multi).split(";|\\r?\\n")) {
            String l = raw.trim();
            if (!l.isEmpty()) out.add(capture(bot, l));
        }
        return out;
    }

    /** Joins many captures into one reply for a surface's inline budget. */
    public static String joinInline(List<CmdOutput> outs, boolean gameBound) {
        StringBuilder sb = new StringBuilder();
        int ran = 0, blocked = 0, failed = 0;
        for (CmdOutput o : outs) {
            switch (o.status) {
                case RAN -> ran++;
                case FAILED -> failed++;
                default -> blocked++;
            }
            if (outs.size() > 1) sb.append("── ").append(o.line).append('\n');
            sb.append(gameBound ? o.inlineGame() : o.inlineWeb()).append('\n');
        }
        if (outs.size() > 1) {
            sb.insert(0, ran + " ran · " + blocked + " blocked · " + failed + " failed\n");
        }
        return sb.toString().trim();
    }

    /** File-safe name for logs/cmd/: cmd-<firstWord>-<epochSeconds>. */
    static String fileName(String line) {
        String first = line == null || line.isBlank() ? "cmd"
                : line.trim().split("\\s+")[0].replaceAll("^[/\\\\]", "");
        return "cmd-" + first.replaceAll("[^A-Za-z0-9._@-]", "_") + "-" + (System.currentTimeMillis() / 1000L);
    }

    /** Session JUL handler: records every published record (filtering happens at merge time). */
    static Handler julHandler(List<String> sink, List<String> loggerNames) {
        return new Handler() {
            @Override public void publish(LogRecord record) {
                if (record == null || record.getMessage() == null) return;
                sink.add(julFormat(record));
                loggerNames.add(record.getLoggerName());
            }
            @Override public void flush() {}
            @Override public void close() throws SecurityException {}
        };
    }

    /** Formats a JUL record as one console-style line: "[LEVEL] message" (+logger when useful).
     *  Public for SmokeTest (pure formatter). */
    public static String julFormat(LogRecord r) {
        String msg = r.getMessage();
        Object[] params = r.getParameters();
        if (params != null && params.length > 0) {
            try { msg = java.text.MessageFormat.format(msg, params); } catch (Throwable ignored) {}
        }
        if (msg == null) msg = "";
        msg = CapturingSender.stripColor(msg);
        String lvl = r.getLevel() == null ? "INFO" : r.getLevel().getName();
        String name = r.getLoggerName();
        boolean named = name != null && !name.isBlank() && !"Minecraft".equalsIgnoreCase(name);
        return "[" + lvl + (named ? "/" + name : "") + "] " + msg;
    }

    /* package-visible for smoke tests */
    static CmdOutput blockedOutput(String line, String blockedMessage) {
        return new CmdOutput(CmdOutput.Status.BLOCKED, line, "", List.of(), 0, null, blockedMessage);
    }

    /** Deepest cause message, bounded — used as the FAILED detail note ("why").
     *  Pure/unwrapping/bounding — public for SmokeTest. */
    public static String rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String msg = cur.getMessage();
        String name = cur.getClass().getSimpleName();
        String s = msg == null || msg.isBlank() ? name : name + ": " + msg;
        return s.length() > 160 ? s.substring(0, 160) : s;
    }

    /** Legacy console route (main-thread, guarded). */
    private boolean consoleDispatch(String line) {
        try {
            return MainThread.call(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line));
        } catch (Throwable t) {
            return false;
        }
    }
}
