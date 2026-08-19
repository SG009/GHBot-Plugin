package dev.ghbot.location;

import dev.ghbot.bot.GHBot;
import dev.ghbot.command.CommandBridge;
import dev.ghbot.command.CommandRegistry;
import dev.ghbot.terrain.CoordResolver;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Named-location commands: save-location / list-locations / delete-location / where.
 * Enables hands-off building ("build the hub at spawn") from chat or console.
 */
public final class LocationCommands {

    private LocationCommands() {}

    public static void register(GHBot bot, CommandBridge bridge, LocationStore store) {
        CommandRegistry r = bridge.registryOf(bot);

        r.register("save-location", (b, ctx) -> {
            CommandSender sender = ctx.sender();
            if (ctx.args().length < 1) {
                sender.sendMessage("§eUsage: " + b.id() + " save-location <name> [here|coords|player]");
                return;
            }
            String name = ctx.args()[0];
            Location loc = null;
            if (ctx.args().length >= 2) {
                loc = CoordResolver.resolve(sender, String.join(" ", java.util.Arrays.copyOfRange(ctx.args(), 1, ctx.args().length)), sender instanceof Player p ? p.getLocation() : null);
            }
            if (loc == null && sender instanceof Player p) loc = p.getLocation();
            if (loc == null) {
                sender.sendMessage("§cSave-location needs a position (stand there, or give coords/player).");
                return;
            }
            store.put(name, loc);
            sender.sendMessage("§a[" + b.id() + "] Saved location §f" + name
                    + "§a at (" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + ").");
        }, CommandRegistry.Meta.of("Save a named location for hands-off building", "save-location <name> [here|coords|player]"));

        r.register("list-locations", (b, ctx) -> {
            if (store.all().isEmpty()) {
                ctx.sender().sendMessage("§7[" + b.id() + "] No saved locations. Use save-location <name>.");
                return;
            }
            StringBuilder sb = new StringBuilder("§e[" + b.id() + "] Saved locations:");
            store.all().forEach((k, v) -> sb.append("\n§f- §a").append(k)
                    .append("§7 → ").append(v.world()).append(" (").append(v.coord()).append(")"));
            ctx.sender().sendMessage(sb.toString());
        }, CommandRegistry.Meta.of("List saved named locations", "list-locations"));

        r.register("delete-location", (b, ctx) -> {
            if (ctx.args().length < 1) {
                ctx.sender().sendMessage("§eUsage: " + b.id() + " delete-location <name>");
                return;
            }
            var n = store.remove(ctx.args()[0]);
            ctx.sender().sendMessage(n == null
                    ? "§7[" + b.id() + "] No location named \"" + ctx.args()[0] + "\"."
                    : "§a[" + b.id() + "] Deleted location \"" + ctx.args()[0] + "\".");
        }, CommandRegistry.Meta.of("Delete a saved location", "delete-location <name>"));

        r.register("where", (b, ctx) -> {
            if (ctx.args().length < 1) {
                ctx.sender().sendMessage("§eUsage: " + b.id() + " where <location>");
                return;
            }
            var n = store.get(ctx.args()[0]);
            if (n == null) {
                ctx.sender().sendMessage("§7[" + b.id() + "] Unknown location \"" + ctx.args()[0]
                        + "\". Try list-locations.");
                return;
            }
            ctx.sender().sendMessage("§a[" + b.id() + "] §f" + ctx.args()[0] + "§a → "
                    + n.world() + " (" + n.coord() + ")");
        }, CommandRegistry.Meta.of("Show a saved location's coordinates", "where <location>"));
    }
}
