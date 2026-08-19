package dev.ghbot.edit;

import dev.ghbot.bot.GHBot;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-bot undo stack (P2 time-rollback support).
 * Each edit command opens an operation; completed operations are pushed
 * as snapshots. `undo [minutes]` pops and reverts the last one, or all
 * within a time window.
 */
public class UndoManager {

    private final Map<String, Deque<EditSnapshot>> stacks = new ConcurrentHashMap<>();
    private final Map<String, EditSnapshot> open = new ConcurrentHashMap<>();
    private final int maxSnapshots;

    public UndoManager(int maxSnapshots) {
        this.maxSnapshots = Math.max(5, maxSnapshots);
    }

    public EditSnapshot begin(GHBot bot, String label, String who) {
        EditSnapshot s = new EditSnapshot(label, who);
        open.put(bot.id(), s);
        return s;
    }

    public EditSnapshot current(GHBot bot) {
        return open.get(bot.id());
    }

    /** Finish an operation: push snapshot if it changed anything. Returns true if pushed. */
    public boolean finish(GHBot bot) {
        EditSnapshot s = open.remove(bot.id());
        if (s == null) return false;
        if (s.changes.isEmpty()) return false;
        Deque<EditSnapshot> st = stacks.computeIfAbsent(bot.id(), k -> new ArrayDeque<>());
        st.push(s);
        while (st.size() > maxSnapshots) st.removeLast();
        return true;
    }

    /** Abort an operation (no changes pushed). */
    public void abort(GHBot bot) {
        open.remove(bot.id());
    }

    /** Pop the last snapshot, or (if minutes > 0) all snapshots within the window. */
    public java.util.List<EditSnapshot> popForUndo(GHBot bot, int minutes) {
        Deque<EditSnapshot> st = stacks.get(bot.id());
        if (st == null || st.isEmpty()) return java.util.Collections.emptyList();
        java.util.List<EditSnapshot> out = new java.util.ArrayList<>();
        long windowStart = minutes > 0 ? System.currentTimeMillis() - minutes * 60_000L : Long.MIN_VALUE;
        while (!st.isEmpty()) {
            EditSnapshot s = st.peek();
            if (minutes > 0 && s.timestampMs < windowStart) break;
            out.add(st.pop());
            if (minutes <= 0) break; // only the latest when no window
        }
        return out;
    }

    public int stackSize(GHBot bot) {
        Deque<EditSnapshot> st = stacks.get(bot.id());
        return st == null ? 0 : st.size();
    }
}
