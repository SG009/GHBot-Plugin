package dev.ghbot.terrain;

import dev.ghbot.bot.GHBot;
import dev.ghbot.command.CommandBridge;
import dev.ghbot.command.CommandRegistry;
import dev.ghbot.log.WIBLogger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Phase 2 — Terrain Eyes commands: scan / look / find.
 * Registers on a bot's command registry and stores scan context in the
 * bot's memory (session-persisted), feeding later phases (Pillar G editing).
 */
public final class TerrainCommands {

    private TerrainCommands() {}

    public static void register(GHBot bot, CommandBridge bridge, WIBLogger log) {
        CommandRegistry r = bridge.registryOf(bot);

        // ── scan ──
        r.register("scan", (b, ctx) -> {
            CommandSender sender = ctx.sender();
            int radius = defaultRadius(b);
            String where = null;

            // parse: scan [where] [radius]  |  scan [radius]
            if (ctx.args().length >= 1) {
                String a0 = ctx.args()[0];
                if (a0.matches("\\d+")) radius = clampRadius(Integer.parseInt(a0));
                else where = a0;
            }
            if (ctx.args().length >= 2) {
                String a1 = ctx.args()[1];
                if (a1.matches("\\d+")) radius = clampRadius(Integer.parseInt(a1));
                else if (where == null) where = a1;
            }

            Location base = baseLocation(sender, b);
            if (base == null) { sender.sendMessage("§cScan needs a location (player position or coordinates)."); return; }
            Location target = CoordResolver.resolve(sender, where, base);
            if (target == null) {
                // try as player name (works from console too — scan around a friend)
                target = CoordResolver.resolvePlayer(where);
                if (target == null) {
                    sender.sendMessage("§cCouldn't resolve \"" + where + "\".");
                    return;
                }
                sender.sendMessage("§7[" + b.id() + "] Scanning around player at ("
                        + target.getBlockX() + ", " + target.getBlockY() + ", " + target.getBlockZ() + ")…");
            }

            b.setActivity(GHBot.Activity.SCANNING);
            sender.sendMessage("§7[" + b.id() + "] Scanning " + radius + " blocks around ("
                    + target.getBlockX() + ", " + target.getBlockY() + ", " + target.getBlockZ() + ")…");
            log.info("[" + b.id() + "] scan radius " + radius + " at " + target.getBlockX() + "," + target.getBlockY() + "," + target.getBlockZ());

            // scanning is CPU-heavy → run on the async pool so the server never hitches
            int rFinal = radius;
            Location targetFinal = target;
            bridge.async(() -> {
                try {
                    var summary = TerrainScanner.scan(targetFinal, rFinal);
                    b.memory().put("terrain", summary.toMap());   // plain map → YAML-safe
                    b.memory().put("terrain.radius", rFinal);
                    b.memory().put("terrain.origin", targetFinal.getBlockX() + "," + targetFinal.getBlockY() + "," + targetFinal.getBlockZ()); // kept for runtime
                    sender.sendMessage("§a[" + b.id() + "] " + summary.toLine());
                } finally {
                    b.setActivity(GHBot.Activity.IDLE);
                }
            });
        }, CommandRegistry.Meta.of("Scan the terrain (around you, a player, or coords)", "scan [radius] | scan <player|coords> [radius]"));

        // ── look ──
        r.register("look", (b, ctx) -> {
            CommandSender sender = ctx.sender();
            String[] raw = ctx.args();
            int start = (raw.length > 0 && raw[0].equalsIgnoreCase("at")) ? 1 : 0;
            String arg = start < raw.length ? String.join(" ", java.util.Arrays.copyOfRange(raw, start, raw.length)) : "here";
            Location base = baseLocation(sender, b);
            if (base == null) { sender.sendMessage("§cLook needs a location."); return; }
            Location target = CoordResolver.resolve(sender, arg, base);
            if (target == null) { sender.sendMessage("§cCouldn't resolve \"" + arg + "\"."); return; }
            var blk = target.getBlock();
            Material m = blk.getType();
            String extra = blk.getBlockData().getAsString().replace("minecraft:", "");
            if (!extra.startsWith(m.name().toLowerCase())) extra = "";
            sender.sendMessage("§f(" + target.getBlockX() + ", " + target.getBlockY() + ", " + target.getBlockZ()
                    + ") §7→ §a" + m.name().toLowerCase()
                    + (extra.isEmpty() ? "" : " §7[" + extra + "]"));
        }, CommandRegistry.Meta.of("What block is at a location?", "look at <coord|here>"));

        // ── find ──
        r.register("find", (b, ctx) -> {
            CommandSender sender = ctx.sender();
            if (ctx.args().length < 1) {
                sender.sendMessage("§eUsage: " + b.id() + " find <block> [radius]");
                return;
            }
            String blockName = ctx.args()[0].toLowerCase();
            int radius = ctx.args().length >= 2 && ctx.args()[1].matches("\\d+")
                    ? clampRadius(Integer.parseInt(ctx.args()[1])) : 50;

            Material mat = Material.matchMaterial(blockName);
            if (mat == null) {
                // try minecraft: prefix or plural-tolerant lookup
                mat = Material.matchMaterial("minecraft:" + blockName);
            }
            if (mat == null) {
                sender.sendMessage("§cUnknown block \"" + blockName + "\". Try e.g. diamond_ore, oak_log, chest.");
                return;
            }

            Location base = baseLocation(sender, b);
            if (base == null) { sender.sendMessage("§cFind needs a location."); return; }
            // optional player target: find <block> <radius> <playerName>
            if (ctx.args().length >= 3) {
                Location pl = CoordResolver.resolvePlayer(ctx.args()[2]);
                if (pl != null) {
                    base = pl;
                    sender.sendMessage("§7[" + b.id() + "] Finding around player at ("
                            + pl.getBlockX() + ", " + pl.getBlockY() + ", " + pl.getBlockZ() + ")…");
                }
            }

            b.setActivity(GHBot.Activity.SCANNING);
            sender.sendMessage("§7[" + b.id() + "] Finding " + mat.name().toLowerCase()
                    + " within " + radius + "…");
            Location baseFinal = base;
            Material matFinal = mat;
            bridge.async(() -> {
                try {
                    List<org.bukkit.block.Block> found = TerrainScanner.findBlocks(baseFinal, matFinal, radius, 20);
                    if (found.isEmpty()) {
                        sender.sendMessage("§7[" + b.id() + "] No " + matFinal.name().toLowerCase()
                                + " found within " + radius + " blocks.");
                    } else {
                        sender.sendMessage("§a[" + b.id() + "] Found " + found.size()
                                + (found.size() >= 20 ? "+" : "") + " " + matFinal.name().toLowerCase() + ":");
                        for (var blk : found) {
                            sender.sendMessage("§f- " + matFinal.name().toLowerCase() + " at ("
                                    + blk.getX() + ", " + blk.getY() + ", " + blk.getZ() + ")");
                        }
                    }
                } finally {
                    b.setActivity(GHBot.Activity.IDLE);
                }
            });
        }, CommandRegistry.Meta.of("Find blocks of a type (your old scan.js)", "find <block> [radius] [player]"));
    }

    private static Location baseLocation(CommandSender sender, GHBot bot) {
        if (sender instanceof Player p) return p.getLocation();
        // console: use last scan origin if available (so /gh scan works hands-off)
        Object o = bot.memory().get("terrain.origin");
        if (o instanceof String s && sender.getServer() != null) {
            try {
                String[] p = s.split(",");
                if (p.length == 3) {
                    World w = sender.getServer().getWorlds().get(0);
                    if (w != null) return new Location(w, Integer.parseInt(p[0].trim()),
                            Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
                }
            } catch (NumberFormatException ignored) {}
        }
        // fallback: world spawn
        try {
            if (sender.getServer() != null && !sender.getServer().getWorlds().isEmpty()) {
                World w = sender.getServer().getWorlds().get(0);
                return w.getSpawnLocation();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static int defaultRadius(GHBot bot) {
        Object o = bot.memory().get("terrain.radius");
        return o instanceof Integer i ? i : 20;
    }

    private static int clampRadius(int r) {
        return Math.max(1, Math.min(200, r));
    }
}
