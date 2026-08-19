package dev.ghbot.edit;

import org.bukkit.Material;

/** One recorded block change inside an edit operation (for undo). */
public class Change {
    public final String world;
    public final int x, y, z;
    public final Material oldType;
    public final Material newType;

    public Change(String world, int x, int y, int z, Material oldType, Material newType) {
        this.world = world; this.x = x; this.y = y; this.z = z;
        this.oldType = oldType;
        this.newType = newType;
    }
}
