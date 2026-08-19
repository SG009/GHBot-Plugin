package dev.ghbot.edit;

import dev.ghbot.builder.DesignSpec;
import dev.ghbot.builder.Primitives;
import dev.ghbot.builder.VoxelModel;
import dev.ghbot.log.WIBLogger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Phase 10 — Structure Editing (Pillar G).
 * Snapshot a region around a target, produce an EditSpec (ops, same line
 * format as DesignSpec) — from the AI if configured, else a template
 * parser — then apply the ops into the region, tick-budgeted, undo-able.
 * v0.21: structural anchors + region drift guard + audit tokens.
 */
public class EditService {

    private final JavaPlugin plugin;
    private final WIBLogger log;
    private final UndoManager undo;
    private final int blocksPerTick;

    public EditService(JavaPlugin plugin, WIBLogger log, UndoManager undo, int blocksPerTick) {
        this.plugin = plugin;
        this.log = log;
        this.undo = undo;
        this.blocksPerTick = blocksPerTick;
    }

    /** Capture a region into a voxel model (for summary / restore). */
    public VoxelModel snapshot(Location center, int radius) {
        Location c1 = center.clone().add(-radius, -radius, -radius);
        Location c2 = center.clone().add(radius, radius, radius);
        VoxelModel m = new VoxelModel();
        World w = center.getWorld();
        if (w == null) return m;
        int x0 = Math.min(c1.getBlockX(), c2.getBlockX()), x1 = Math.max(c1.getBlockX(), c2.getBlockX());
        int y0 = Math.min(c1.getBlockY(), c2.getBlockY()), y1 = Math.max(c1.getBlockY(), c2.getBlockY());
        int z0 = Math.min(c1.getBlockZ(), c2.getBlockZ()), z1 = Math.max(c1.getBlockZ(), c2.getBlockZ());
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++) {
                    Material mat = w.getBlockAt(x, y, z).getType();
                    if (mat != Material.AIR && mat != Material.CAVE_AIR && mat != Material.VOID_AIR)
                        m.set(x - x0, y - y0, z - z0, mat.name().toLowerCase());
                }
        return m;
    }

    /** One-line region summary (feeds the AI so it knows what's there). */
    public static String summarize(VoxelModel m) {
        Map<String, Integer> counts = new java.util.TreeMap<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Map.Entry<Long, String> e : m.entries()) {
            int x = VoxelModel.xOf(e.getKey()), y = VoxelModel.yOf(e.getKey()), z = VoxelModel.zOf(e.getKey());
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            counts.merge(e.getValue(), 1, Integer::sum);
        }
        if (minX == Integer.MAX_VALUE) return "empty";
        List<Map.Entry<String, Integer>> es = new ArrayList<>(counts.entrySet());
        es.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        StringBuilder top = new StringBuilder();
        for (int i = 0; i < Math.min(4, es.size()); i++) {
            if (top.length() > 0) top.append(" ");
            top.append(es.get(i).getKey()).append(":").append(es.get(i).getValue());
        }
        return (maxX - minX + 1) + "x" + (maxY - minY + 1) + "x" + (maxZ - minZ + 1)
                + " · " + m.size() + " blocks · top: " + top;
    }

    /** Dominant material of a material-count map (ties → first in sorted order). */
    private static String dominant(Map<String, Integer> counts) {
        String best = null; int bestN = -1;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestN) { best = e.getKey(); bestN = e.getValue(); }
        }
        return best;
    }

    /**
     * Structural anchor map (Phase 10, v0.21). Compresses the voxel model into
     * named anchors an LLM can target instead of absolute coordinates:
     * foundation_base, roof_center, center, and the four wall faces — each with
     * its dominant material. Edits expressed against these anchors stay correct
     * even when the region origin shifts.
     */
    public static String anchorLine(VoxelModel m) {
        if (m.size() == 0) return "no anchors (empty region)";
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        Map<String, Integer> base = new TreeMap<>();     // materials at minY
        Map<String, Integer> roof = new TreeMap<>();     // materials at maxY
        Map<String, Integer> north = new TreeMap<>();    // z == minZ
        Map<String, Integer> south = new TreeMap<>();    // z == maxZ
        Map<String, Integer> east = new TreeMap<>();     // x == maxX
        Map<String, Integer> west = new TreeMap<>();     // x == minX
        for (Map.Entry<Long, String> e : m.entries()) {
            int x = VoxelModel.xOf(e.getKey()), y = VoxelModel.yOf(e.getKey()), z = VoxelModel.zOf(e.getKey());
            if (x < minX) minX = x; if (x > maxX) maxX = x;
            if (y < minY) minY = y; if (y > maxY) maxY = y;
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;
        }
        for (Map.Entry<Long, String> e : m.entries()) {
            int x = VoxelModel.xOf(e.getKey()), y = VoxelModel.yOf(e.getKey()), z = VoxelModel.zOf(e.getKey());
            if (y == minY) base.merge(e.getValue(), 1, Integer::sum);
            if (y == maxY) roof.merge(e.getValue(), 1, Integer::sum);
            if (z == minZ) north.merge(e.getValue(), 1, Integer::sum);
            if (z == maxZ) south.merge(e.getValue(), 1, Integer::sum);
            if (x == maxX) east.merge(e.getValue(), 1, Integer::sum);
            if (x == minX) west.merge(e.getValue(), 1, Integer::sum);
        }
        int cx = (minX + maxX) / 2, cz = (minZ + maxZ) / 2;
        return "bbox " + (maxX - minX + 1) + "x" + (maxY - minY + 1) + "x" + (maxZ - minZ + 1)
                + " · center (" + cx + "," + ((minY + maxY) / 2) + "," + cz + ")"
                + " · foundation_base " + dominant(base)
                + " · roof_center (" + cx + "," + maxY + "," + cz + ") " + dominant(roof)
                + " · north_wall " + dominant(north) + " · south_wall " + dominant(south)
                + " · east_wall " + dominant(east) + " · west_wall " + dominant(west);
    }

    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");
    private static final DateTimeFormatter TOK = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Fresh audit token for an applied edit (logged to logs/edits.log). */
    public static String newToken() {
        return "EDT-" + LocalDateTime.now(WIB).format(TOK) + "-" + ThreadLocalRandom.current().nextInt(1000, 10000);
    }

    /** Apply an EditSpec's ops into the world at a region origin, recorded for undo. */
    public void apply(GHBotRef bot, DesignSpec spec, Location origin, CommandSender sender, Runnable done) {
        // build the ops into a temp voxel model
        VoxelModel ops = new VoxelModel();
        for (DesignSpec.Op op : spec.ops) {
            try { Primitives.apply(op, ops); } catch (Exception ignored) {}
        }
        if (ops.size() == 0) {
            sender.sendMessage("§7[GH] Edit produced no changes.");
            done.run();
            return;
        }
        int ox = origin.getBlockX(), oy = origin.getBlockY(), oz = origin.getBlockZ();
        List<Map.Entry<Long, String>> entries = new ArrayList<>();
        ops.entries().forEach(entries::add);
        EditSnapshot snap = undo.begin(bot.bot(), "edit " + spec.name, sender.getName());
        int[] idx = {0}; int[] placed = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int n = 0;
            while (idx[0] < entries.size() && n < blocksPerTick) {
                Map.Entry<Long, String> e = entries.get(idx[0]++);
                int x = ox + VoxelModel.xOf(e.getKey());
                int y = oy + VoxelModel.yOf(e.getKey());
                int z = oz + VoxelModel.zOf(e.getKey());
                Material mat = Material.matchMaterial(e.getValue());
                World w = origin.getWorld();
                if (mat == null || w == null) continue;
                var blk = w.getBlockAt(x, y, z);
                Material old = blk.getType();
                if (old != mat) {
                    blk.setType(mat, false);
                    snap.changes.add(new Change(w.getName(), x, y, z, old, mat));
                    placed[0]++;
                }
                n++;
            }
            if (idx[0] >= entries.size()) {
                task.cancel();
                boolean pushed = undo.finish(bot.bot());
                String token = newToken();
                sender.sendMessage("§a[GH] Edit applied: " + placed[0] + " blocks changed"
                        + (pushed ? " §7(undo available)" : "") + " §7[" + token + "]");
                log.info("[GH] edit " + spec.name + " applied (" + placed[0] + " blocks)");
                log.editLog("[" + bot.bot().id() + "] edit \"" + spec.name + "\" applied ("
                        + placed[0] + " blocks) by " + sender.getName() + " [token=" + token + "]");
                done.run();
            }
        }, 1L, 1L);
    }

    /** Tiny holder so we don't force a full GHBot dependency in the signature. */
    public interface GHBotRef {
        dev.ghbot.bot.GHBot bot();
    }
}
