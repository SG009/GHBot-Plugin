package dev.ghbot.review;

import dev.ghbot.bot.GHBot;
import dev.ghbot.command.CommandBridge;
import dev.ghbot.command.CommandRegistry;

/**
 * Phase 7 — review commands: approve / deny / redo / export / animate.
 */
public final class ReviewCommands {

    private ReviewCommands() {}

    public static void register(GHBot bot, CommandBridge bridge, GhostService ghosts) {
        CommandRegistry r = bridge.registryOf(bot);

        r.register("approve", (b, ctx) -> ghosts.approve(b, ctx.sender()),
                CommandRegistry.Meta.of("Approve the staged build (keeps it)", "approve"));

        r.register("deny", (b, ctx) -> ghosts.clear(b, ctx.sender(), false),
                CommandRegistry.Meta.of("Clear the staged build", "deny"));

        r.register("redo", (b, ctx) -> {
            // clear + re-stage with tweaks: for now clear and tell user to rebuild
            ghosts.clear(b, ctx.sender(), true);
            ctx.sender().sendMessage("§7[" + b.id() + "] Re-stage: run "
                    + b.id() + " build <prompt> again (AI will tweak the design).");
        }, CommandRegistry.Meta.of("Clear + re-stage the build", "redo"));

        r.register("export", (b, ctx) -> {
            var s = ghosts.staged(b);
            if (s == null) {
                ctx.sender().sendMessage("§7[" + b.id() + "] Nothing staged to export. (Schematic export lands in Phase 8.)");
                return;
            }
            ctx.sender().sendMessage("§7[" + b.id() + "] Staged §f" + s.specName + "§7 (" + s.changes.size()
                    + " blocks) — full .schematic export lands in Phase 8.");
        }, CommandRegistry.Meta.of("Export the staged build as a schematic (Phase 8)", "export [name]"));

        r.register("animate", (b, ctx) -> {
            if (ctx.args().length < 1 || !(ctx.args()[0].equalsIgnoreCase("on") || ctx.args()[0].equalsIgnoreCase("off"))) {
                ctx.sender().sendMessage("§eUsage: " + b.id() + " animate <on|off>");
                return;
            }
            boolean on = ctx.args()[0].equalsIgnoreCase("on");
            b.memory().put("animate", on);
            ctx.sender().sendMessage("§a[" + b.id() + "] animate " + (on ? "on" : "off")
                    + " §7(cinematic build pass on approve)");
        }, CommandRegistry.Meta.of("Toggle cinematic build pass on approve", "animate <on|off>"));
    }
}
