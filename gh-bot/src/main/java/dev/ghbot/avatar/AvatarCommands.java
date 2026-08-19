package dev.ghbot.avatar;

import dev.ghbot.bot.GHBot;
import dev.ghbot.command.CommandBridge;
import dev.ghbot.command.CommandRegistry;
import dev.ghbot.terrain.CoordResolver;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Phase 12 — avatar + marker commands.
 */
public final class AvatarCommands {

    private AvatarCommands() {}

    public static void register(GHBot bot, CommandBridge bridge, AvatarService avatars, MarkerService markers) {
        CommandRegistry r = bridge.registryOf(bot);

        r.register("avatar", (b, ctx) -> {
            if (ctx.args().length < 1 || !(ctx.args()[0].equalsIgnoreCase("on") || ctx.args()[0].equalsIgnoreCase("off"))) {
                Object o = b.memory().get("avatar");
                ctx.sender().sendMessage("§7[" + b.id() + "] avatar: " + (o instanceof Boolean bb && bb ? "on" : "off")
                        + " §7· " + b.id() + " avatar <on|off>");
                return;
            }
            boolean on = ctx.args()[0].equalsIgnoreCase("on");
            b.memory().put("avatar", on);
            if (!on) avatars.despawn(b);
            ctx.sender().sendMessage("§a[" + b.id() + "] avatar " + (on ? "on" : "off")
                    + " §7(Enderman statue at build sites)");
        }, CommandRegistry.Meta.of("Toggle the in-world avatar statue", "avatar <on|off>"));

        r.register("marker", (b, ctx) -> {
            if (ctx.args().length < 1) {
                ctx.sender().sendMessage("§eUsage: " + b.id() + " marker <name> | marker remove <name> | marker list");
                return;
            }
            if (ctx.args()[0].equalsIgnoreCase("remove")) {
                if (ctx.args().length < 2) { ctx.sender().sendMessage("§eUsage: marker remove <name>"); return; }
                boolean ok = markers.remove(b, ctx.args()[1]);
                ctx.sender().sendMessage(ok ? "§aMarker removed: " + ctx.args()[1] : "§7No marker named " + ctx.args()[1]);
                markers.saveToMemory(b);
                return;
            }
            if (ctx.args()[0].equalsIgnoreCase("list")) {
                var m = markers.all(b);
                if (m.isEmpty()) { ctx.sender().sendMessage("§7[" + b.id() + "] No markers."); return; }
                StringBuilder sb = new StringBuilder("§e[" + b.id() + "] Markers:");
                m.forEach((k, v) -> sb.append("\n§f- §a").append(k).append("§7 → (")
                        .append(v[0]).append(", ").append(v[1]).append(", ").append(v[2]).append(")"));
                ctx.sender().sendMessage(sb.toString());
                return;
            }
            if (!(ctx.sender() instanceof Player p)) {
                ctx.sender().sendMessage("§cMarker needs a player position (or use marker list/remove).");
                return;
            }
            String name = ctx.args()[0];
            Location where = ctx.args().length >= 2
                    ? CoordResolver.resolve(ctx.sender(), String.join(" ", java.util.Arrays.copyOfRange(ctx.args(), 1, ctx.args().length)), p.getLocation())
                    : p.getLocation();
            if (where == null) where = p.getLocation();
            markers.place(b, where, name);
            markers.saveToMemory(b);
            ctx.sender().sendMessage("§a[" + b.id() + "] Marker §f" + name + "§a placed at ("
                    + where.getBlockX() + ", " + where.getBlockY() + ", " + where.getBlockZ() + ").");
        }, CommandRegistry.Meta.of("Place a named waypoint marker", "marker <name> | marker remove <name> | marker list"));
    }
}
