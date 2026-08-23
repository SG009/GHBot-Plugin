package dev.ghbot.schematic;

import dev.ghbot.bot.GHBot;
import dev.ghbot.builder.DesignSpec;
import dev.ghbot.builder.DesignTemplates;
import dev.ghbot.command.CommandBridge;
import dev.ghbot.command.CommandRegistry;
import dev.ghbot.log.WIBLogger;
import dev.ghbot.terrain.CoordResolver;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Phase 8 — schematic commands: schem / export / paste / library.
 */
public final class SchematicCommands {

    private SchematicCommands() {}

    public static void register(GHBot bot, CommandBridge bridge, SchematicService schematics,
                                dev.ghbot.review.GhostService ghosts, WIBLogger log) {
        CommandRegistry r = bridge.registryOf(bot);

        // ── schem <name> <prompt> [format|all] : design → export ──
        r.register("schem", (b, ctx) -> {
            CommandSender sender = ctx.sender();
            if (ctx.args().length < 2) {
                sender.sendMessage("§eUsage: " + b.id() + " schem <name> <prompt> [format|all]");
                return;
            }
            String name = ctx.args()[0];
            String format = "all";
            StringBuilder prompt = new StringBuilder();
            for (int i = 1; i < ctx.args().length; i++) {
                String a = ctx.args()[i];
                if (a.equalsIgnoreCase("all") || a.equalsIgnoreCase("schem") || a.equalsIgnoreCase("schematic")
                        || a.equalsIgnoreCase("litematic") || a.equalsIgnoreCase("nbt")) {
                    format = a;
                } else {
                    if (prompt.length() > 0) prompt.append(' ');
                    prompt.append(a);
                }
            }
            // design via template (AI design path for schem lands with Phase 9+; keep simple)
            DesignSpec spec = DesignTemplates.pick(prompt.toString());
            var model = dev.ghbot.builder.BuildService.toModel(spec);
            if (model.size() == 0) {
                sender.sendMessage("§c[" + b.id() + "] Design produced no blocks.");
                return;
            }
            try {
                var written = schematics.export(name, model, format);
                if (written.isEmpty()) { sender.sendMessage("§c[" + b.id() + "] Export failed (no formats written)."); return; }
                sender.sendMessage("§a[" + b.id() + "] Exported §f" + name + "§a to:");
                for (Path p : written) sender.sendMessage("§7  → " + p.getFileName());
            } catch (IOException e) {
                sender.sendMessage("§c[" + b.id() + "] Export error: " + e.getMessage());
            }
        }, CommandRegistry.Meta.of("Design + export a schematic (all formats)", "schem <name> <prompt> [format|all]"));

        // ── export <name> [format|all] : export the STAGED build ──
        r.register("export", (b, ctx) -> {
            CommandSender sender = ctx.sender();
            var staged = bridge.ghostService() == null ? null : bridge.ghostService().staged(b);
            if (staged == null) {
                sender.sendMessage("§7[" + b.id() + "] Nothing staged to export. "
                        + "Use " + b.id() + " build <prompt> first, or " + b.id() + " schem <name> <prompt>.");
                return;
            }
            String name = ctx.args().length >= 1 ? ctx.args()[0] : staged.specName;
            String format = ctx.args().length >= 2 ? ctx.args()[1] : "all";
            try {
                var written = schematics.export(name, buildFromChanges(staged), format);
                if (written.isEmpty()) { sender.sendMessage("§cExport failed."); return; }
                sender.sendMessage("§a[" + b.id() + "] Exported staged §f" + name + "§a:");
                for (Path p : written) sender.sendMessage("§7  → " + p.getFileName());
            } catch (IOException e) {
                sender.sendMessage("§cExport error: " + e.getMessage());
            }
        }, CommandRegistry.Meta.of("Export the staged build as a schematic", "export <name> [format|all]"));

        // ── paste <file> [where] : paste a schematic from the library ──
        r.register("paste", (b, ctx) -> {
            CommandSender sender = ctx.sender();
            if (ctx.args().length < 1) {
                sender.sendMessage("§eUsage: " + b.id() + " paste <file> [here|coords|player]");
                return;
            }
            String file = ctx.args()[0];
            Path f = schematics.resolveInLibrary(file);
            if (f == null) {
                sender.sendMessage("§c[" + b.id() + "] Invalid file name — it must live inside the schematics library.");
                return;
            }
            if (!Files.exists(f)) {
                sender.sendMessage("§c[" + b.id() + "] No such file in the library. Try " + b.id() + " library");
                return;
            }
            try {
                // v0.21.45 — paste actually reads the file and stages it as a ghost (same review
                // flow as build: approve/deny/redo/export). Supports Sponge v2/v3, Classic,
                // Vanilla .nbt and Litematica files already in the library.
                byte[] data = Files.readAllBytes(f);
                dev.ghbot.builder.VoxelModel model = dev.ghbot.schematic.SchematicImporter.importFile(data);
                if (model == null || model.size() == 0) {
                    sender.sendMessage("§c[" + b.id() + "] Couldn't parse " + f.getFileName()
                            + " (" + safeLen(f) + " bytes) — unsupported or empty format.");
                    return;
                }
                Location base = base(sender, b);
                if (base == null) {
                    sender.sendMessage("§c[" + b.id() + "] Paste needs a location (stand there, or use 'at <where>').");
                    return;
                }
                // v0.22.1 — respect "paste <file> [at <x y z>|x z|x y z|here|player]" offsets
                // (the old code ignored every arg after the filename and always pasted
                // at the sender's base → the "it ignored my 30 10 offset" batch-test bug).
                if (ctx.args().length >= 2) {
                    String[] rest = java.util.Arrays.copyOfRange(ctx.args(), 1, ctx.args().length);
                    Location off = CoordResolver.offset(rest, base);          // "x z" / "x y z"
                    if (off == null) off = CoordResolver.parseWhere(sender, rest, base); // at/here/player/coords
                    if (off == null) off = CoordResolver.resolvePlayer(String.join(" ", rest));
                    if (off != null) {
                        base = off;
                        sender.sendMessage("§7[" + b.id() + "] Paste target: ("
                                + base.getBlockX() + ", " + base.getBlockY() + ", " + base.getBlockZ() + ")");
                    }
                }
                String name = f.getFileName().toString().replaceFirst("\\.[^.]+$", "");
                boolean animate = b.memory().get("animate") instanceof Boolean ab && ab;
                ghosts.stage(b, model, name, base, sender, animate);
                // surface the viewer URL + inline preview like the build tool does
                String viewOut = dev.ghbot.agent.ToolBridge.run(bridge, b, "view", new String[0]);
                java.util.regex.Matcher vu = java.util.regex.Pattern
                        .compile("(https?://[^/\\s]+)/view/([a-z0-9-]+)").matcher(viewOut);
                if (vu.find()) {
                    String vbase = vu.group(1), jobId = vu.group(2);
                    sender.sendMessage("§a[" + b.id() + "] Pasted §f" + name + "§a (" + model.size()
                            + " blocks) — review: " + vbase + "/view/" + jobId
                            + "\nPREVIEW_IMG: " + vbase + "/view/" + jobId + "/preview.png");
                } else {
                    sender.sendMessage("§a[" + b.id() + "] Pasted §f" + name + "§a (" + model.size()
                            + " blocks) — walk around it, then approve / deny / redo / export.");
                }
            } catch (Throwable t) {
                sender.sendMessage("§c[" + b.id() + "] Paste failed: "
                        + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
            }
        }, CommandRegistry.Meta.of("Paste a schematic from the library", "paste <file> [where]"));

        // ── library : list saved schematics ──
        r.register("library", (b, ctx) -> {
            CommandSender sender = ctx.sender();
            try {
                var files = schematics.library();
                if (files.isEmpty()) {
                    sender.sendMessage("§7[" + b.id() + "] Library empty. Use " + b.id() + " schem <name> <prompt> to create one.");
                    return;
                }
                StringBuilder sb = new StringBuilder("§e[" + b.id() + "] Library (" + files.size() + "):");
                for (Path p : files) sb.append("\n§f- §a").append(p.getFileName()).append("§7 (").append(safeLen(p)).append(" bytes)");
                sender.sendMessage(sb.toString());
            } catch (IOException e) {
                sender.sendMessage("§cLibrary error: " + e.getMessage());
            }
        }, CommandRegistry.Meta.of("Browse the schematic library", "library"));
    }

    /** Origin for paste from a non-player sender: bot memory origin → world spawn. */
    private static Location base(CommandSender sender, GHBot bot) {
        if (sender instanceof Player p) return p.getLocation();
        Object o = bot.memory().get("terrain.origin");
        if (o instanceof String s) {
            try {
                String[] p = s.split(",");
                if (p.length == 3 && sender.getServer() != null && !sender.getServer().getWorlds().isEmpty()) {
                    var w = sender.getServer().getWorlds().get(0);
                    return new Location(w, Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
                }
            } catch (NumberFormatException ignored) {}
        }
        try {
            if (sender.getServer() != null && !sender.getServer().getWorlds().isEmpty())
                return sender.getServer().getWorlds().get(0).getSpawnLocation();
        } catch (Throwable ignored) {}
        return null;
    }

    private static dev.ghbot.builder.VoxelModel buildFromChanges(dev.ghbot.review.GhostService.Staged s) {
        dev.ghbot.builder.VoxelModel m = new dev.ghbot.builder.VoxelModel();
        int ox = s.origin.getBlockX(), oy = s.origin.getBlockY(), oz = s.origin.getBlockZ();
        for (dev.ghbot.edit.Change c : s.changes) {
            m.set(c.x - ox, c.y - oy, c.z - oz, c.newType.name().toLowerCase());
        }
        return m;
    }

    private static long safeLen(Path p) {
        try { return Files.size(p); } catch (IOException e) { return -1; }
    }
}
