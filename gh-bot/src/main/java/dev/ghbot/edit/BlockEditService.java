package dev.ghbot.edit;

import dev.ghbot.bot.GHBot;
import dev.ghbot.config.PluginConfig;
import dev.ghbot.log.WIBLogger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Phase 3 — world editing core. All block changes run on the MAIN thread
 * (Bukkit requirement) but are TICK-BUDGETED (N blocks per tick) so big
 * edits never tank TPS on a phone. Every change is recorded into an undo
 * snapshot and written to the audit log (logs/edits.log).
 */
public class BlockEditService {

    private final JavaPlugin plugin;
    private final WIBLogger log;
    private final UndoManager undo;
    private final int blocksPerTick;
    private final int maxRegion;

    public BlockEditService(JavaPlugin plugin, WIBLogger log, PluginConfig cfg) {
        this.plugin = plugin;
        this.log = log;
        this.blocksPerTick = cfg.editBlocksPerTick();
        this.maxRegion = cfg.editMaxRegion();
        this.undo = new UndoManager(cfg.undoMaxSnapshots());
    }

    public UndoManager undo() { return undo; }

    /** Begin an undo-able edit operation for a bot. */
    public EditSnapshot begin(GHBot bot, String label, CommandSender who) {
        return undo.begin(bot, label, who.getName());
    }

    /* ── single block set ── */
    public void setBlock(GHBot bot, Location loc, Material mat, CommandSender sender, EditSnapshot op, Runnable done) {
        runMain(() -> {
            Block b = loc.getBlock();
            Material old = b.getType();
            if (old == mat) {
                sender.sendMessage("§7[" + bot.id() + "] Already " + mat.name().toLowerCase() + " there.");
                done.run();
                return;
            }
            b.setType(mat, false);
            op.changes.add(new Change(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), old, mat));
            audit(bot, "set " + mat.name().toLowerCase() + " at " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ() + " by " + sender.getName());
            sender.sendMessage("§a[" + bot.id() + "] Set (" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ()
                    + ") → " + mat.name().toLowerCase());
            done.run();
        });
    }

    /* ── replace <from> with <to> in a cube radius, tick-budgeted ── */
    public void replace(GHBot bot, Location center, Material from, Material to, int radius,
                        CommandSender sender, EditSnapshot op, Runnable done) {
        World w = center.getWorld();
        if (w == null) { done.run(); return; }
        int cx = center.getBlockX(), cz = center.getBlockZ();
        int y0 = Math.max(w.getMinHeight(), center.getBlockY() - radius);
        int y1 = Math.min(w.getMaxHeight() - 1, center.getBlockY() + radius);
        long total = (long) (2L * radius + 1) * (2L * radius + 1) * (y1 - y0 + 1);
        if (total > maxRegion) {
            sender.sendMessage("§c[" + bot.id() + "] Region too big (" + total + " blocks, cap " + maxRegion
                    + "). Use a smaller radius.");
            undo.abort(bot);
            done.run();
            return;
        }
        // build candidate positions (only matching `from`) quickly on main thread
        List<int[]> targets = new ArrayList<>();
        for (int x = cx - radius; x <= cx + radius; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = cz - radius; z <= cz + radius; z++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (b.getType() == from) targets.add(new int[]{x, y, z});
                }
        if (targets.isEmpty()) {
            sender.sendMessage("§7[" + bot.id() + "] No " + from.name().toLowerCase()
                    + " found in that region.");
            undo.abort(bot);
            done.run();
            return;
        }
        int[] idx = {0};
        int[] changed = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int n = 0;
            while (idx[0] < targets.size() && n < blocksPerTick) {
                int[] p = targets.get(idx[0]++);
                Block b = w.getBlockAt(p[0], p[1], p[2]);
                if (b.getType() == from) {
                    b.setType(to, false);
                    op.changes.add(new Change(w.getName(), p[0], p[1], p[2], from, to));
                    changed[0]++;
                }
                n++;
            }
            if (idx[0] >= targets.size()) {
                task.cancel();
                boolean pushed = undo.finish(bot);
                audit(bot, "replace " + from.name().toLowerCase() + " -> " + to.name().toLowerCase()
                        + " radius " + radius + " (" + changed[0] + " blocks) by " + sender.getName());
                sender.sendMessage("§a[" + bot.id() + "] Replaced " + changed[0] + "× "
                        + from.name().toLowerCase() + " → " + to.name().toLowerCase());
                done.run();
            }
        }, 1L, 1L);
    }

    /* ── terraform flatten <radius> [block]: set the surface block per column ── */
    public void flatten(GHBot bot, Location center, int radius, Material surface,
                        CommandSender sender, EditSnapshot op, Runnable done) {
        World w = center.getWorld();
        if (w == null) { done.run(); return; }
        int cx = center.getBlockX(), cz = center.getBlockZ();
        long cols = (2L * radius + 1) * (2L * radius + 1);
        if (cols > maxRegion) {
            sender.sendMessage("§c[" + bot.id() + "] Area too big (" + cols + " columns, cap " + maxRegion + ").");
            undo.abort(bot);
            done.run();
            return;
        }
        List<int[]> colsList = new ArrayList<>();
        for (int x = cx - radius; x <= cx + radius; x++)
            for (int z = cz - radius; z <= cz + radius; z++)
                colsList.add(new int[]{x, z});
        int[] idx = {0};
        int[] changed = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int n = 0;
            while (idx[0] < colsList.size() && n < blocksPerTick) {
                int[] c = colsList.get(idx[0]++);
                Block top = topSolid(w, c[0], c[1], center.getBlockY() + radius);
                if (top != null && top.getType() != surface) {
                    Material old = top.getType();
                    top.setType(surface, false);
                    op.changes.add(new Change(w.getName(), top.getX(), top.getY(), top.getZ(), old, surface));
                    changed[0]++;
                }
                n++;
            }
            if (idx[0] >= colsList.size()) {
                task.cancel();
                boolean pushed = undo.finish(bot);
                audit(bot, "terraform flatten radius " + radius + " -> " + surface.name().toLowerCase()
                        + " (" + changed[0] + " blocks) by " + sender.getName());
                sender.sendMessage("§a[" + bot.id() + "] Flattened " + changed[0]
                        + " surface blocks to " + surface.name().toLowerCase());
                done.run();
            }
        }, 1L, 1L);
    }

    private Block topSolid(World w, int x, int z, int startY) {
        for (int y = startY; y >= w.getMinHeight(); y--) {
            Block b = w.getBlockAt(x, y, z);
            Material m = b.getType();
            if (!m.isAir() && m != Material.WATER && m != Material.LAVA && m != Material.SEAGRASS) return b;
        }
        return null;
    }

    /* ── undo ── */
    public void undo(GHBot bot, int minutes, CommandSender sender, Runnable done) {
        List<EditSnapshot> snaps = undo.popForUndo(bot, minutes);
        if (snaps.isEmpty()) {
            sender.sendMessage("§7[" + bot.id() + "] Nothing to undo.");
            done.run();
            return;
        }
        int[] reverted = {0};
        // apply on main thread in budgeted batches
        List<Change> all = new ArrayList<>();
        for (EditSnapshot s : snaps) all.addAll(s.changes);
        int[] idx = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int n = 0;
            while (idx[0] < all.size() && n < blocksPerTick) {
                Change c = all.get(idx[0]++);
                World w = Bukkit.getWorld(c.world);
                if (w == null) continue;
                Block b = w.getBlockAt(c.x, c.y, c.z);
                if (b.getType() != c.oldType) {
                    b.setType(c.oldType, false);
                    reverted[0]++;
                }
                n++;
            }
            if (idx[0] >= all.size()) {
                task.cancel();
                audit(bot, "undo " + (minutes > 0 ? "(last " + minutes + " min) " : "")
                        + "reverted " + reverted[0] + " blocks by " + sender.getName());
                sender.sendMessage("§a[" + bot.id() + "] Undone " + snaps.size() + " operation(s), reverted "
                        + reverted[0] + " blocks.");
                done.run();
            }
        }, 1L, 1L);
    }

    private void audit(GHBot bot, String line) {
        log.editLog("[" + bot.id() + "] " + line);
    }

    private void runMain(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }
}
