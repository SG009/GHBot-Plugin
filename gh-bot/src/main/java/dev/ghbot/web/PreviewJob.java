package dev.ghbot.web;

import dev.ghbot.builder.VoxelModel;

/**
 * A preview job — a build ready to view in the browser at /view/<id>.
 * Created automatically when a build is staged (ghost), or on demand
 * when viewing a schematic file.
 */
public class PreviewJob {

    public final String id;
    public final String name;
    public final String botId;
    public final VoxelModel model;
    public final long created = System.currentTimeMillis();
    public boolean staged;   // has an associated in-game staged build (approve/deny)

    public PreviewJob(String id, String name, String botId, VoxelModel model, boolean staged) {
        this.id = id;
        this.name = name;
        this.botId = botId;
        this.model = model;
        this.staged = staged;
    }

    public String shortId() {
        return id.length() > 12 ? id.substring(0, 12) : id;
    }
}
