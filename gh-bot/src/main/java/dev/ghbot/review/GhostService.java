package dev.ghbot.review;

import dev.ghbot.bot.GHBot;
import dev.ghbot.edit.Change;
import dev.ghbot.edit.EditSnapshot;
import dev.ghbot.edit.UndoManager;
import dev.ghbot.log.WIBLogger;
import dev.ghbot.terrain.TerrainScanner;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 7 — Ghost Review. Stages a build as a TEMPORARY in-place preview
 * layer (real blocks, instantly clearable) → the player walks around it →
 * approve (keeps it, optionally re-animated) / deny (clears) / redo (clears
 * + re-stages with tweaks) / export (save as schematic in Phase 8).
 *
 * The staged layer is recorded as a special EditSnapshot; approve keeps it
 * (pushes to undo), deny/redo clears it (reverts the snapshot).
 */
public class GhostService {

    private final JavaPlugin plugin;
    private final WIBLogger log;
    private final UndoManager undo;
    private final int blocksPerTick;

    // botId -> staged state
    private final Map<String, Staged> staged = new HashMap<>();

    public static class Staged {
        public String specName;
        public String who;
        public Location origin;
        public List<Change> changes = new ArrayList<>();
        public boolean approved;
        public boolean animateOnApprove;
        public int totalBlocks;
        public dev.ghbot.builder.VoxelModel model;   // for web preview
    }

    public GhostService(JavaPlugin plugin, WIBLogger log, UndoManager undo, int blocksPerTick) {
        this.plugin = plugin;
        this.log = log;
        this.undo = undo;
        this.blocksPerTick = blocksPerTick;
    }

    public Staged staged(GHBot bot) { return staged.get(bot.id()); }
    public boolean hasStaged(GHBot bot) { return staged.containsKey(bot.id()); }

    // fired when a build is staged (plugin registers a web preview job)
    private java.util.function.BiConsumer<GHBot, Staged> onStaged;
    public void setOnStaged(java.util.function.BiConsumer<GHBot, Staged> cb) { this.onStaged = cb; }

    // avatar + auto-save hooks (Phase 12)
    private dev.ghbot.avatar.AvatarService avatarSvc;
    private dev.ghbot.schematic.SchematicService schematicSvc;
    private boolean autoSaveApproved = false;
    public void setAvatarService(dev.ghbot.avatar.AvatarService a) { this.avatarSvc = a; }
    public void setSchematicService(dev.ghbot.schematic.SchematicService s) { this.schematicSvc = s; }
    public void setAutoSaveApproved(boolean b) { this.autoSaveApproved = b; }
    private boolean avatarOn(GHBot bot) { return bot.memory().get("avatar") instanceof Boolean bb && bb; }

    /**
     * Stage a built voxel model as a ghost preview. Expects `model` to already
     * be produced; we place it, recording changes so we can clear instantly.
     */
    public void stage(GHBot bot, dev.ghbot.builder.VoxelModel model, String name,
                      Location origin, CommandSender sender, boolean animateOnApprove) {
        // scan-aware origin
        Location base = origin.clone();
        var surface = TerrainScanner.surfaceBelow(base);
        if (surface != null) base.setY(surface.getY() + 1);
        base.setX(origin.getBlockX() + 0.5);
        base.setZ(origin.getBlockZ() + 0.5);

        Staged s = new Staged();
        s.specName = name;
        s.who = sender.getName();
        s.origin = base.clone();
        s.animateOnApprove = animateOnApprove;
        s.totalBlocks = model.size();
        s.model = model;
        staged.put(bot.id(), s);
        if (onStaged != null) onStaged.accept(bot, s);
        if (avatarSvc != null && avatarOn(bot)) {
            avatarSvc.spawn(bot, base, bot.config().avatar().type());
        }

        bot.setActivity(GHBot.Activity.WAITING_APPROVAL);
        sender.sendMessage("§e[" + bot.id() + "] §f" + name + "§e staged (" + s.totalBlocks
                + " blocks) at (" + base.getBlockX() + ", " + base.getBlockY() + ", " + base.getBlockZ()
                + ") — walk around it, then approve / deny / redo / export.");

        // place ghost (budgeted)
        List<Map.Entry<Long, String>> entries = new ArrayList<>();
        model.entries().forEach(entries::add);
        int[] idx = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int n = 0;
            while (idx[0] < entries.size() && n < blocksPerTick) {
                Map.Entry<Long, String> e = entries.get(idx[0]++);
                int x = dev.ghbot.builder.VoxelModel.xOf(e.getKey());
                int y = dev.ghbot.builder.VoxelModel.yOf(e.getKey());
                int z = dev.ghbot.builder.VoxelModel.zOf(e.getKey());
                Material mat = Material.matchMaterial(e.getValue());
                World w = base.getWorld();
                if (mat == null || w == null) continue;
                var blk = w.getBlockAt(base.getBlockX() + x, base.getBlockY() + y, base.getBlockZ() + z);
                Material old = blk.getType();
                if (old != mat) {
                    blk.setType(mat, false);
                    s.changes.add(new Change(w.getName(),
                            base.getBlockX() + x, base.getBlockY() + y, base.getBlockZ() + z, old, mat));
                }
                n++;
            }
            if (idx[0] >= entries.size()) task.cancel();
        }, 1L, 1L);
    }

    /** Approve the staged build: keep it, push as undo snapshot, done. */
    public boolean approve(GHBot bot, CommandSender sender) {
        Staged s = staged.get(bot.id());
        if (s == null) { sender.sendMessage("§7[" + bot.id() + "] Nothing staged to approve."); return false; }
        if (s.changes.isEmpty()) {
            sender.sendMessage("§e[" + bot.id() + "] Build placed no blocks.");
            staged.remove(bot.id());
            bot.setActivity(GHBot.Activity.IDLE);
            return false;
        }
        // record into undo (kept)
        EditSnapshot snap = undo.begin(bot, "build " + s.specName, s.who);
        snap.changes.addAll(s.changes);
        undo.finish(bot);
        staged.remove(bot.id());
        bot.setActivity(GHBot.Activity.IDLE);
        if (avatarSvc != null) avatarSvc.despawn(bot);
        // auto-save approved build to the schematic library (P7)
        if (autoSaveApproved && schematicSvc != null && s.model != null) {
            try {
                var written = schematicSvc.export(s.specName, s.model, "all");
                sender.sendMessage("§7[" + bot.id() + "] Auto-saved to library: " + written.size() + " file(s)");
            } catch (Exception e) {
                log.error("Auto-save failed for " + s.specName, e);
            }
        }
        sender.sendMessage("§a[" + bot.id() + "] Approved ✓ — " + s.changes.size() + " blocks kept"
                + (s.animateOnApprove ? " §7(animate: on)" : " §7(animate: off)"));
        log.info("[" + bot.id() + "] approved staged build " + s.specName + " (" + s.changes.size() + " blocks)");
        return true;
    }

    /** Deny or redo: clear the ghost instantly (revert staged changes). */
    public void clear(GHBot bot, CommandSender sender, boolean redo) {
        Staged s = staged.get(bot.id());
        if (s == null) { sender.sendMessage("§7[" + bot.id() + "] Nothing staged."); return; }
        staged.remove(bot.id());
        bot.setActivity(GHBot.Activity.IDLE);
        if (avatarSvc != null) avatarSvc.despawn(bot);
        int count = s.changes.size();
        // revert on main thread (fast, budgeted)
        List<Change> changes = s.changes;
        int[] idx = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int n = 0;
            while (idx[0] < changes.size() && n < blocksPerTick) {
                Change c = changes.get(idx[0]++);
                World w = Bukkit.getWorld(c.world);
                if (w == null) continue;
                var blk = w.getBlockAt(c.x, c.y, c.z);
                if (blk.getType() != c.oldType) blk.setType(c.oldType, false);
                n++;
            }
            if (idx[0] >= changes.size()) task.cancel();
        }, 1L, 1L);
        sender.sendMessage("§7[" + bot.id() + "] " + (redo ? "Redo — " : "") + "cleared " + count + " ghost blocks.");
        log.info("[" + bot.id() + "] " + (redo ? "redo" : "deny") + " cleared staged " + s.specName);
    }
}
