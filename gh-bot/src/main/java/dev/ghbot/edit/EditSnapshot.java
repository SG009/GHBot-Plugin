package dev.ghbot.edit;

import java.util.ArrayList;
import java.util.List;

/** An undo-able edit operation (a snapshot of what changed). */
public class EditSnapshot {
    public final String label;
    public final long timestampMs;
    public final String who;
    public final List<Change> changes = new ArrayList<>();

    public EditSnapshot(String label, String who) {
        this.label = label;
        this.who = who;
        this.timestampMs = System.currentTimeMillis();
    }

    public int size() { return changes.size(); }
}
