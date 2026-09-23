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

    /** v0.25.0 — the most recent scan (any bot), for the web viewer's scan layer.
     *  Volatile: written on the async scan pool, read by the web thread. */
    public static volatile dev.ghbot.terrain.TerrainScanner.TerrainSummary LAST_SCAN;
    public static volatile int[] LAST_ORIGIN = new int[]{0, 0, 0};
    public static volatile int LAST_RADIUS;

    private TerrainCommands() {}

    public static void register(GHBot bot, CommandBridge bridge, WIBLogger log) {
        CommandRegistry r = bridge.registryOf(bot);

        // ── scan ──
        r.register("scan", (b, ctx) -> {
            CommandSender sender = ctx.sender();
            // v0.22.1 — scan [--full [depth]] [radius] [where|here|me|player|at x y z]
            boolean full = false;
            int fullDepth = 0;
            java.util.List<String> rest = new java.util.ArrayList<>();
            String[] a = ctx.args();
            for (int i = 0; i < a.length; i++) {
                String t = a[i] == null ? "" : a[i].trim();
                if (t.equalsIgnoreCase("--full")) { full = true; continue; }
                if (full && fullDepth == 0 && t.matches("\\d+")) { fullDepth = Integer.parseInt(t); continue; }
                rest.add(a[i]);
            }
            var st = CoordResolver.scanTarget(rest.toArray(new String[0]), defaultRadius(b));
            int radius = st.radius();
            String where = st.where();

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
                    + target.getBlockX() + ", " + target.getBlockY() + ", " + target.getBlockZ() + ")"
                    + (full ? " §8(full depth " + (fullDepth > 0 ? fullDepth : 6) + ")" : "") + "…");
            log.info("[" + b.id() + "] scan radius " + radius + (full ? " full depth " + (fullDepth > 0 ? fullDepth : 6) : "")
                    + " at " + target.getBlockX() + "," + target.getBlockY() + "," + target.getBlockZ());

            // scanning is CPU-heavy → run on the async pool so the server never hitches
            int rFinal = radius;
            int depthFinal = full ? (fullDepth > 0 ? fullDepth : 6) : 0;
            Location targetFinal = target;
            bridge.async(() -> {
                try {
                    var summary = TerrainScanner.scan(targetFinal, rFinal);
                    b.memory().put("terrain", summary.toMap());   // plain map → YAML-safe
                    b.memory().put("terrain.radius", rFinal);
                    b.memory().put("terrain.origin", targetFinal.getBlockX() + "," + targetFinal.getBlockY() + "," + targetFinal.getBlockZ()); // kept for runtime
                    // v0.22.1 — eyes-as-data: emit the world as a TerrainSpec (memory + file + inline)
                    TerrainSpec spec = TerrainScanner.scanSpec(targetFinal, rFinal, depthFinal);
                    if (spec != null) routeSpec(b, sender, log, spec);
                    // v0.25.0 — Phase C lattice: small scans hand the AI the real
                    // per-column grid (absolute coords); the FULL grid always lands
                    // in logs/scan/*.log; the web viewer reads it via LAST_SCAN.
                    // Composed by toolScanReply — the SAME helper the AI/AutoTool
                    // surface uses, so chat and API replies can never drift.
                    String body = toolScanReply(summary, targetFinal, rFinal, log);
                    sender.sendMessage("§a[" + b.id() + "] " + body.replace("\n", "\n§7"));
                } finally {
                    b.setActivity(GHBot.Activity.IDLE);
                }
            });
        }, CommandRegistry.Meta.of("Scan the terrain (around you, a player, or coords); --full emits a deeper spec",
                "scan [radius] | scan <player|coords> [radius] | scan --full [depth]"));

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
            // v0.22.1 — eyes-as-data: single-block spec preserving the full blockstate
            TerrainSpec spec = new TerrainSpec();
            spec.kind = "look";
            spec.ox = target.getBlockX(); spec.oy = target.getBlockY(); spec.oz = target.getBlockZ();
            spec.name = "look@" + target.getBlockX() + "," + target.getBlockY() + "," + target.getBlockZ();
            spec.addRaw(target.getBlockX(), target.getBlockY(), target.getBlockZ(), blk.getBlockData().getAsString());
            routeSpec(b, sender, log, spec);
        }, CommandRegistry.Meta.of("What block is at a location?", "look at <coord|here>"));

        // ── find ──
        r.register("find", (b, ctx) -> {
            CommandSender sender = ctx.sender();
            if (ctx.args().length < 1) {
                sender.sendMessage("§eUsage: " + b.id() + " find <block> [radius]");
                return;
            }
            String blockName = ctx.args()[0].toLowerCase().replace("minecraft:", "");
            int radius = ctx.args().length >= 2 && ctx.args()[1].matches("\\d+")
                    ? clampRadius(Integer.parseInt(ctx.args()[1])) : 50;

            // v0.22.1 — the AI sometimes says "find that"/"find this": strip
            // conversational pronouns and fall back to the last scan's dominant
            // top block instead of a brittle "unknown block" error.
            if (blockName.matches("(that|this|it|here|the|those|these)")) {
                String fb = dominantTopBlock(b);
                if (fb == null) {
                    sender.sendMessage("§c\"find " + blockName + "\" isn't a block. Try e.g. find diamond_ore 50.");
                    return;
                }
                sender.sendMessage("§7[" + b.id() + "] \"find " + blockName + "\" → using the last scan's dominant block: " + fb);
                blockName = fb;
            }

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
            // optional target: find <block> [radius] [playerName | at x y z | x y z]
            if (ctx.args().length >= 3) {
                String[] rest = java.util.Arrays.copyOfRange(ctx.args(), 2, ctx.args().length);
                Location target = CoordResolver.parseWhere(sender, rest, base);
                if (target == null) target = CoordResolver.resolvePlayer(String.join(" ", rest));
                if (target != null && target != base) {
                    base = target;
                    sender.sendMessage("§7[" + b.id() + "] Finding around ("
                            + target.getBlockX() + ", " + target.getBlockY() + ", " + target.getBlockZ() + ")…");
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
                        // v0.22.1 — eyes-as-data: found positions as a find-spec (exact coords → replace/undo)
                        TerrainSpec spec = new TerrainSpec();
                        spec.kind = "find";
                        spec.ox = baseFinal.getBlockX(); spec.oy = baseFinal.getBlockY(); spec.oz = baseFinal.getBlockZ();
                        spec.name = "find@" + matFinal.name().toLowerCase() + "@r" + radius;
                        for (var blk : found) spec.add(blk.getX(), blk.getY(), blk.getZ(), matFinal.name());
                        routeSpec(b, sender, log, spec);
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

    /**
     * v0.22.1 — route an eyes spec to (1) bot memory, (2) logs/eyes/&lt;name&gt;.json
     * (full fidelity), and (3) the sender as a bounded inline JSON digest
     * (TerrainSpec.INLINE_MAX blocks) so the Technician sees the world as data
     * without blowing up the context window on the phone.
     */
    private static void routeSpec(GHBot b, CommandSender sender, WIBLogger log, TerrainSpec spec) {
        String json = spec.toJson();
        b.memory().put("eyes.spec", json);
        b.memory().put("eyes.kind", spec.kind);
        b.memory().put("eyes.origin", spec.ox + "," + spec.oy + "," + spec.oz);
        b.memory().put("eyes.summary", spec.toLine());
        String file = log.writeEyesSpec(spec.name, json);
        StringBuilder msg = new StringBuilder("§7[" + b.id() + "] eyes-spec: " + spec.toLine());
        if (file != null) msg.append(" §7(").append(file).append(')');
        msg.append("\n§7").append(spec.toJsonInline(TerrainSpec.INLINE_MAX));
        sender.sendMessage(msg.toString());
    }

    /**
     * v0.25.0 — THE scan-reply composer, shared by the registry command AND the
     * AI/AutoTool tool surface (GHBotPlugin) so both always answer identically.
     * Sets LAST_SCAN/LAST_ORIGIN/LAST_RADIUS (viewer scan layer), writes the full
     * grid to logs/scan/*.log when a lattice exists, and returns
     * "toLine + lattice grid (+ grid-file note)" — toLine only when the scan is
     * counts-only (radius > 32 → stride 0).
     */
    public static String toolScanReply(dev.ghbot.terrain.TerrainScanner.TerrainSummary summary,
                                       Location target, int radius, WIBLogger log) {
        LAST_SCAN = summary;
        LAST_ORIGIN = new int[]{target.getBlockX(), target.getBlockY(), target.getBlockZ()};
        LAST_RADIUS = radius;
        String lattice = summary.latticeText(1500);
        if (lattice.isEmpty()) return summary.toLine();
        String file = log != null ? log.writeScanGrid(summary.fullLattice()) : null;
        return summary.toLine() + "\n" + lattice + (file != null ? "\nfull grid: " + file : "");
    }

    /** v0.22.1 — dominant top block from the last scan context (for "find that" fallback). */
    private static String dominantTopBlock(GHBot bot) {
        Object terrain = bot.memory().get("terrain");
        if (terrain instanceof java.util.Map<?, ?> tmap) {
            Object tb = tmap.get("topBlocks");
            if (tb instanceof java.util.Map<?, ?> tbm && !tbm.isEmpty()) {
                for (Object k : tbm.keySet()) {
                    if (k != null && !String.valueOf(k).isBlank()) return String.valueOf(k);
                }
            }
        }
        return null;
    }
}
