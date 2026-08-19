package dev.ghbot.schematic;

import dev.ghbot.bot.GHBot;
import dev.ghbot.builder.DesignSpec;
import dev.ghbot.builder.DesignTemplates;
import dev.ghbot.command.CommandBridge;
import dev.ghbot.command.CommandRegistry;
import dev.ghbot.log.WIBLogger;
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

    public static void register(GHBot bot, CommandBridge bridge, SchematicService schematics, WIBLogger log) {
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
            Path f = schematics.dir().resolve(file);
            if (!Files.exists(f)) {
                sender.sendMessage("§c[" + b.id() + "] No such file in the library. Try " + b.id() + " library");
                return;
            }
            sender.sendMessage("§7[" + b.id() + "] Paste from schematics (importers land with the codec readers in a later phase). "
                    + "File found: " + f.getFileName() + " (" + safeLen(f) + " bytes)");
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
