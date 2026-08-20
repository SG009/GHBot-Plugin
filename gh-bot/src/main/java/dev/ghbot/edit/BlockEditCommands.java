package dev.ghbot.edit;

import dev.ghbot.bot.GHBot;
import dev.ghbot.command.CommandBridge;
import dev.ghbot.command.CommandRegistry;
import dev.ghbot.terrain.CoordResolver;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;

/**
 * Phase 3 — Block editing commands: set / replace / terraform / undo.
 * All edits are undo-able (snapshot stack) and audit-logged.
 */
public final class BlockEditCommands {

    private BlockEditCommands() {}

    public static void register(GHBot bot, CommandBridge bridge, BlockEditService svc) {
        CommandRegistry r = bridge.registryOf(bot);

        // ── set ──
        r.register("set", (b, ctx) -> {
            CommandSender sender = ctx.sender();
            String[] args = ctx.args();
            // set <where> to <block>  |  set <where> <block>  |  set <block> at <where>
            int toIdx = -1, atIdx = -1;
            for (int i = 0; i < args.length; i++) {
                if (args[i].equalsIgnoreCase("to") && toIdx < 0) toIdx = i;
                if (args[i].equalsIgnoreCase("at") && atIdx < 0) atIdx = i;
            }
            if (args.length < 2) { sender.sendMessage("§eUsage: " + b.id() + " set <where> to <block>"); return; }
            String where, blockName;
            if (toIdx > 0) {
                where = String.join(" ", Arrays.copyOfRange(args, 0, toIdx));
                blockName = String.join(" ", Arrays.copyOfRange(args, toIdx + 1, args.length));
            } else if (atIdx > 0) {
                // v0.22.1 — accept the AI's natural order: "set stone at 86 86 262"
                blockName = String.join(" ", Arrays.copyOfRange(args, 0, atIdx));
                where = String.join(" ", Arrays.copyOfRange(args, atIdx, args.length));
            } else {
                where = args[0];
                blockName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            }
            Material mat = Material.matchMaterial(blockName.toLowerCase().replace("minecraft:", ""));
            if (mat == null) { sender.sendMessage("§cUnknown block \"" + blockName + "\"."); return; }
            Location base = base(sender, b);
            if (base == null) { sender.sendMessage("§cSet needs a location."); return; }
            Location target = CoordResolver.resolve(sender, where, base);
            if (target == null) { sender.sendMessage("§cCouldn't resolve \"" + where + "\"."); return; }
            EditSnapshot op = svc.begin(b, "set", sender);
            svc.setBlock(b, target, mat, sender, op, () -> svc.undo().finish(b));
        }, CommandRegistry.Meta.of("Set a block at a location", "set <where> to <block>"));

        // ── replace ──
        r.register("replace", (b, ctx) -> {
            CommandSender sender = ctx.sender();
            String[] args = ctx.args();
            if (args.length < 2) {
                sender.sendMessage("§eUsage: " + b.id() + " replace <from> with <to> [radius] [x y z|player]");
                return;
            }
            // accept "replace <from> with <to> …" OR "replace <from> <to> …"
            int withIdx = -1;
            for (int i = 0; i < args.length; i++) if (args[i].equalsIgnoreCase("with")) { withIdx = i; break; }
            int fromIdx = 0, toIdx = 1;
            if (withIdx >= 1) { fromIdx = 0; toIdx = withIdx + 1; }
            Material from = Material.matchMaterial(args[fromIdx].toLowerCase().replace("minecraft:", ""));
            Material to = Material.matchMaterial(args[toIdx].toLowerCase().replace("minecraft:", ""));
            if (from == null || to == null) { sender.sendMessage("§cUnknown block in replace: " + args[fromIdx] + " → " + args[toIdx]); return; }
            int radius = 20;
            String where = null;
            java.util.List<Integer> ints = new java.util.ArrayList<>();
            for (int i = toIdx + 1; i < args.length; i++) {
                if (args[i].matches("-?\\d+")) ints.add(Integer.parseInt(args[i]));
                else if (where == null) where = args[i];
            }
            Location base = base(sender, b);
            if (base == null) { sender.sendMessage("§cReplace needs a location."); return; }
            Location target = null;
            // v0.21.32 — parse "… <radius> <x> <y> <z>" or "… <x> <y> <z>" from the trailing ints:
            // last 3 ints (if present) = coords, the int before them (if any) = radius.
            if (ints.size() >= 3) {
                int z = ints.remove(ints.size() - 1);
                int y = ints.remove(ints.size() - 1);
                int x = ints.remove(ints.size() - 1);
                target = new Location(base.getWorld(), x, y, z);
                if (!ints.isEmpty()) radius = Math.max(1, Math.min(100, ints.get(ints.size() - 1)));
            } else {
                if (!ints.isEmpty()) radius = Math.max(1, Math.min(100, ints.get(0)));
                target = CoordResolver.resolve(sender, where, base);
                if (target == null) target = CoordResolver.resolvePlayer(where);
            }
            if (target == null) target = base;
            sender.sendMessage("§7[" + b.id() + "] Replacing " + from.name().toLowerCase() + " → "
                    + to.name().toLowerCase() + " within " + radius + "…");
            EditSnapshot op = svc.begin(b, "replace", sender);
            svc.replace(b, target, from, to, radius, sender, op, () -> {});
        }, CommandRegistry.Meta.of("Swap block types in a region", "replace <from> with <to> [radius] [player]"));

        // ── terraform ──
        r.register("terraform", (b, ctx) -> {
            CommandSender sender = ctx.sender();
            String[] args = ctx.args();
            if (args.length == 0 || args[0].equalsIgnoreCase("status") || args[0].equalsIgnoreCase("?")) {
                Object o = b.memory().get("terraform");
                sender.sendMessage("§7[" + b.id() + "] terraform: " + (o instanceof Boolean bb && bb ? "on" : "off"));
                return;
            }
            if (args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("off")) {
                b.memory().put("terraform", args[0].equalsIgnoreCase("on"));
                sender.sendMessage("§a[" + b.id() + "] terraform " + (args[0].equalsIgnoreCase("on") ? "on" : "off")
                        + " §7(builds will auto-flatten the base, Phase 6+)");
                return;
            }
            if (args[0].equalsIgnoreCase("flatten")) {
                int radius = 15;
                Material surface = Material.GRASS_BLOCK;
                if (args.length >= 2 && args[1].matches("\\d+")) radius = Math.max(1, Math.min(100, Integer.parseInt(args[1])));
                if (args.length >= 3) {
                    Material m = Material.matchMaterial(args[2].toLowerCase().replace("minecraft:", ""));
                    if (m != null) surface = m;
                }
                Location base = base(sender, b);
                if (base == null) { sender.sendMessage("§cTerraform needs a location."); return; }
                sender.sendMessage("§7[" + b.id() + "] Flattening " + radius + " blocks to "
                        + surface.name().toLowerCase() + "…");
                EditSnapshot op = svc.begin(b, "terraform", sender);
                svc.flatten(b, base, radius, surface, sender, op, () -> {});
                return;
            }
            sender.sendMessage("§eUsage: " + b.id() + " terraform [on|off|flatten <radius> [block]|status]");
        }, CommandRegistry.Meta.of("Auto-terraform the ground (flatten)", "terraform flatten <radius> [block]"));

        // ── undo ──
        r.register("undo", (b, ctx) -> {
            CommandSender sender = ctx.sender();
            int minutes = 0;
            if (ctx.args().length >= 1 && ctx.args()[0].matches("\\d+")) minutes = Math.max(1, Integer.parseInt(ctx.args()[0]));
            svc.undo(b, minutes, sender, () -> {});
        }, CommandRegistry.Meta.of("Undo last edit (or within N minutes)", "undo [minutes]"));
    }

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
}
