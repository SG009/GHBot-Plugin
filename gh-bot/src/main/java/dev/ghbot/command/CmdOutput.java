package dev.ghbot.command;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * v0.22.2 — merged result of one captured `cmd` dispatch (Pillar 3).
 *
 * Composed of the sender-feed (what the command replied to the dispatching
 * sender — the authoritative output) plus any JUL log lines a plugin emitted
 * during the dispatch window instead of replying (prefixed "console-log: ").
 * The merge/format logic is pure and headless-smoke-tested; only the dispatch
 * itself needs a live server.
 */
public final class CmdOutput {

    /** Status of the dispatch. */
    public enum Status { RAN, FAILED, BLOCKED }

    /** Log-section caps (the inline reply stays phone-safe no matter how spammy the command). */
    public static final int LOG_MAX_LINES = 30;
    public static final int LOG_MAX_CHARS = 2048;
    /** Inline reply budgets per surface (D3). */
    public static final int WEB_MAX_CHARS = 4000, WEB_MAX_LINES = 40;
    public static final int GAME_MAX_CHARS = 1500, GAME_MAX_LINES = 15;

    public final Status status;
    public final String line;
    public final String senderFeed;      // may be empty
    public final List<String> logLines;  // raw lines, no prefix (already self-filtered + deduped)
    public final int droppedFeedChars;   // chars the sender-feed capture dropped at MAX_CHARS
    public final String logFile;         // "logs/cmd/<file>" when written, else null
    public final String note;            // optional detail (BLOCKED: the CONF-token message)

    public CmdOutput(Status status, String line, String senderFeed, List<String> logLines,
                     int droppedFeedChars, String logFile) {
        this(status, line, senderFeed, logLines, droppedFeedChars, logFile, null);
    }

    public CmdOutput(Status status, String line, String senderFeed, List<String> logLines,
                     int droppedFeedChars, String logFile, String note) {
        this.status = status;
        this.line = line == null ? "" : line;
        this.senderFeed = senderFeed == null ? "" : senderFeed;
        this.logLines = logLines == null ? List.of() : logLines;
        this.droppedFeedChars = droppedFeedChars;
        this.logFile = logFile;
        this.note = note;
    }

    /** Compose the full merged text (unbounded by surface — the file copy uses this). */
    public String fullText() {
        StringBuilder sb = new StringBuilder();
        switch (status) {
            case BLOCKED -> { return "⛔ BLOCKED: " + line + (note == null ? "" : " → " + note); }
            case FAILED -> sb.append("✗ failed: ").append(line);
            default -> sb.append("✓ ran: ").append(line);
        }
        // v0.22.3 — FAILED carries the reason (dispatch threw / unknown command) so the
        // failure is diagnosable from the reply instead of a bare ✗.
        if (status == Status.FAILED && note != null && !note.isBlank()) sb.append(" — ").append(note);
        if (status == Status.RAN && senderFeed.isEmpty() && logLines.isEmpty()) {
            sb.append("\n(no output)");
            return sb.toString();
        }
        if (!senderFeed.isEmpty()) {
            sb.append("\nOUTPUT:\n").append(senderFeed);
            if (droppedFeedChars > 0) sb.append("\n…(+").append(droppedFeedChars).append(" chars truncated)");
        }
        for (String l : logLines) sb.append("\nconsole-log: ").append(l);
        return sb.toString();
    }

    /**
     * Inline reply bounded for a surface: tail truncation with a pointer to the
     * log file when content was cut. Never throws; pure text operation.
     */
    public String inline(int maxChars, int maxLines) {
        String full = fullText();
        // line bound (keep head; commands print the important bits first)
        String[] ls = full.split("\n", -1);
        boolean cutLines = ls.length > maxLines;
        String joined = full;
        if (cutLines) {
            StringBuilder j = new StringBuilder();
            for (int i = 0; i < maxLines; i++) { if (i > 0) j.append('\n'); j.append(ls[i]); }
            joined = j.toString();
        }
        boolean cutChars = joined.length() > maxChars;
        if (cutChars) joined = joined.substring(0, Math.max(0, maxChars));
        if (cutLines || cutChars) {
            StringBuilder note = new StringBuilder(joined);
            note.append("\n…(truncated");
            if (cutLines) note.append(" — ").append(ls.length - maxLines).append(" more line(s)");
            note.append(")");
            if (logFile != null) note.append(" Full output: ").append(logFile);
            return note.toString();
        }
        if (logFile != null && droppedFeedChars > 0) {
            return joined + "\nFull output: " + logFile;
        }
        return joined;
    }

    /** Convenience: inline for the web chat-console. */
    public String inlineWeb() { return inline(WEB_MAX_CHARS, WEB_MAX_LINES); }

    /** Convenience: inline for in-game chat. */
    public String inlineGame() { return inline(GAME_MAX_CHARS, GAME_MAX_LINES); }

    /**
     * Merge raw JUL lines into the bounded log section: drops GHBot self-lines
     * (by logger name), dedupes against the sender feed and within the section
     * (order-preserving), collapses consecutive repeats into "×N", then caps at
     * LOG_MAX_LINES / LOG_MAX_CHARS. Pure static — smoke-tested headlessly.
     */
    public static List<String> mergeLogLines(List<String> rawLogLines, List<String> rawLoggerNames,
                                             String selfLoggerName, String senderFeed) {
        LinkedHashSet<String> dedupe = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        int chars = 0;
        String prev = null;
        for (int i = 0; i < rawLogLines.size(); i++) {
            String msg = rawLogLines.get(i);
            if (msg == null || msg.isBlank()) continue;
            String logger = i < rawLoggerNames.size() ? rawLoggerNames.get(i) : null;
            if (selfLoggerName != null && selfLoggerName.equalsIgnoreCase(logger)) continue; // own audit trail
            if (senderFeed != null && !senderFeed.isEmpty() && senderFeed.contains(msg)) continue; // already in feed
            if (msg.equals(prev)) { // collapse consecutive repeats
                if (!out.isEmpty()) {
                    String last = out.get(out.size() - 1);
                    if (last.endsWith("…")) continue;
                    if (last.startsWith(msg + " ×")) {
                        int n = Integer.parseInt(last.substring(msg.length() + 2)) + 1;
                        out.set(out.size() - 1, msg + " ×" + n);
                        continue;
                    }
                    out.set(out.size() - 1, msg + " ×2");
                    continue;
                }
            }
            prev = msg;
            if (!dedupe.add(msg)) continue;
            if (out.size() >= LOG_MAX_LINES || chars + msg.length() > LOG_MAX_CHARS) {
                if (out.isEmpty() || !out.get(out.size() - 1).endsWith("…")) out.add("…");
                break;
            }
            out.add(msg);
            chars += msg.length() + 1;
        }
        return out;
    }
}
