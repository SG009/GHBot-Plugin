package dev.ghbot.builder;

import dev.ghbot.bot.GHBot;
import dev.ghbot.config.PluginConfig;
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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 6 — the builder. Turns a DesignSpec into a voxel model, then places
 * it in the world on the main thread in tick-budgeted batches with progress,
 * stage announcements, TPS auto-pause, cancel, and undo.
 */
public class BuildService {

    private final JavaPlugin plugin;
    private final WIBLogger log;
    private final UndoManager undo;
    private final int blocksPerTick;
    private final double tpsPauseThreshold;
    private final Map<String, Object> running = new ConcurrentHashMap<>();
    private dev.ghbot.avatar.AvatarService avatarSvc;
    public void setAvatarService(dev.ghbot.avatar.AvatarService a) { this.avatarSvc = a; }
    private boolean avatarOn(GHBot bot) { return bot.memory().get("avatar") instanceof Boolean bb && bb; }

    public BuildService(JavaPlugin plugin, WIBLogger log, PluginConfig cfg, UndoManager undo) {
        this.plugin = plugin;
        this.log = log;
        this.undo = undo;
        this.blocksPerTick = cfg.editBlocksPerTick();
        this.tpsPauseThreshold = cfg.buildTpsPause();
    }

    /** Convert a spec to a voxel model (shared with ghost staging). */
    public static VoxelModel toModel(DesignSpec spec) {
        VoxelModel model = new VoxelModel();
        for (DesignSpec.Op op : spec.ops) {
            try { Primitives.apply(op, model); } catch (Exception ignored) {}
        }
        return model;
    }

    public boolean cancel(GHBot bot) {
        Object o = running.get(bot.id());
        if (o instanceof boolean[] arr) { arr[0] = true; return true; }
        return false;
    }

    public void build(GHBot bot, DesignSpec spec, Location origin, CommandSender sender) {
        VoxelModel model = toModel(spec);
        if (model.size() == 0) { sender.sendMessage("§c[" + bot.id() + "] Design produced no blocks."); return; }

        Location base = origin.clone();
        var surface = TerrainScanner.surfaceBelow(base);
        if (surface != null) base.setY(surface.getY() + 1);
        base.setX(origin.getBlockX() + 0.5);
        base.setZ(origin.getBlockZ() + 0.5);

        int total = model.size();
        sender.sendMessage("§a[" + bot.id() + "] §f" + spec.name + "§a — building "
                + total + " blocks at (" + base.getBlockX() + ", " + base.getBlockY() + ", " + base.getBlockZ() + ")…");
        log.info("[" + bot.id() + "] build " + spec.name + " (" + total + " blocks) at "
                + base.getBlockX() + "," + base.getBlockY() + "," + base.getBlockZ());

        EditSnapshot snap = undo.begin(bot, "build " + spec.name, sender.getName());
        boolean[] cancelFlag = {false};
        running.put(bot.id(), cancelFlag);
        bot.setActivity(GHBot.Activity.BUILDING);
        if (avatarSvc != null && avatarOn(bot)) avatarSvc.spawn(bot, base, bot.config().avatar().type());

        java.util.List<Map.Entry<Long, String>> entries = new java.util.ArrayList<>();
        model.entries().forEach(entries::add);

        int[] idx = {0};
        int[] placed = {0};
        int[] lastPct = {-1};
        int[] opIdx = {0};

        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            try { if (Bukkit.getTPS()[0] < tpsPauseThreshold) return; } catch (Throwable ignored) {}
            if (opIdx[0] < spec.ops.size() && idx[0] >= (int) ((double) opIdx[0] / Math.max(1, spec.ops.size()) * entries.size())) {
                sender.sendMessage("§7[" + bot.id() + "] ▸ " + spec.ops.get(opIdx[0]).type() + "…");
                opIdx[0]++;
            }
            int n = 0;
            while (idx[0] < entries.size() && n < blocksPerTick) {
                if (cancelFlag[0]) {
                    task.cancel();
                    finish(bot, snap, sender, placed[0], true);
                    return;
                }
                Map.Entry<Long, String> e = entries.get(idx[0]++);
                Material mat = Material.matchMaterial(e.getValue());
                World w = base.getWorld();
                if (mat == null || w == null) continue;
                var blk = w.getBlockAt(base.getBlockX() + VoxelModel.xOf(e.getKey()),
                        base.getBlockY() + VoxelModel.yOf(e.getKey()),
                        base.getBlockZ() + VoxelModel.zOf(e.getKey()));
                Material old = blk.getType();
                if (old != mat) {
                    blk.setType(mat, false);
                    snap.changes.add(new Change(w.getName(),
                            base.getBlockX() + VoxelModel.xOf(e.getKey()),
                            base.getBlockY() + VoxelModel.yOf(e.getKey()),
                            base.getBlockZ() + VoxelModel.zOf(e.getKey()), old, mat));
                    placed[0]++;
                }
                n++;
            }
            int pct = (int) ((double) idx[0] / total * 100);
            if (pct / 10 != lastPct[0] && pct % 10 == 0) {
                lastPct[0] = pct / 10;
                sender.sendMessage("§7[" + bot.id() + "] " + pct + "%");
            }
            if (idx[0] >= entries.size()) { task.cancel(); finish(bot, snap, sender, placed[0], false); }
        }, 1L, 1L);
    }

    private void finish(GHBot bot, EditSnapshot snap, CommandSender sender, int placed, boolean cancelled) {
        running.remove(bot.id());
        bot.setActivity(GHBot.Activity.IDLE);
        if (avatarSvc != null) avatarSvc.despawn(bot);
        boolean pushed = undo.finish(bot);
        if (cancelled) {
            sender.sendMessage("§e[" + bot.id() + "] Cancelled — " + placed + " blocks placed (undoable).");
            log.info("[" + bot.id() + "] build cancelled (" + placed + " blocks)");
        } else {
            sender.sendMessage("§a[" + bot.id() + "] Done! " + placed + " blocks placed"
                    + (pushed ? " §7(undo available)" : ""));
            log.info("[" + bot.id() + "] build finished (" + placed + " blocks)");
        }
    }
}
