package dev.ghbot.command;

import dev.ghbot.bot.GHBot;
import dev.ghbot.command.CommandBridge;
import dev.ghbot.command.CommandLearning;
import dev.ghbot.command.CommandRegistry;

/**
 * Phase 11 — command-learning commands: refresh / cmd / add.
 * (Full trust per your decision; every dispatch audit-logged.)
 */
public final class CommandLearningCommands {

    private CommandLearningCommands() {}

    public static void register(GHBot bot, CommandBridge bridge, CommandLearning learning) {
        CommandRegistry r = bridge.registryOf(bot);

        r.register("refresh", (b, ctx) -> {
            int n = learning.refresh();
            ctx.sender().sendMessage("§a[" + b.id() + "] Command catalog refreshed: " + n + " commands.");
        }, CommandRegistry.Meta.of("Re-learn the server's command catalog", "refresh commands"));

        r.register("cmd", (b, ctx) -> {
            if (ctx.args().length == 0) {
                ctx.sender().sendMessage("§eUsage: " + b.id() + " cmd <command> [; command; …]");
                return;
            }
            String line = String.join(" ", ctx.args());
            String res;
            if (learning.capture() != null) {
                // v0.22.2 — Pillar 3: show the command's REAL output (in-game bound), not just "✓ ran".
                java.util.List<dev.ghbot.command.CmdOutput> outs = learning.capture().captureMany(b, line);
                res = dev.ghbot.command.CmdOutputCapture.joinInline(outs, true);
            } else {
                res = learning.dispatchGuardedMany(b, line);
            }
            ctx.sender().sendMessage("§7[" + b.id() + "] " + res.replace("\n", "\n§7"));
            if (res.contains("⛔")) {
                ctx.sender().sendMessage("§e  Blocked commands need: " + b.id() + " confirm <CONF-token>");
            }
        }, CommandRegistry.Meta.of("Run server commands as console (multi via ';', systemic need confirm)", "cmd <command> [; command; …]"));

        r.register("confirm", (b, ctx) -> {
            if (ctx.args().length < 1) {
                ctx.sender().sendMessage("§eUsage: " + b.id() + " confirm <CONF-token> — e.g. after a blocked systemic command.");
                return;
            }
            var res = learning.confirm(ctx.args()[0]);
            switch (res.status()) {
                case "ran" -> ctx.sender().sendMessage("§a[" + b.id() + "] " + res.message());
                case "unknown" -> ctx.sender().sendMessage("§7[" + b.id() + "] " + res.message());
                case "expired" -> ctx.sender().sendMessage("§c[" + b.id() + "] " + res.message());
                default -> ctx.sender().sendMessage("§c[" + b.id() + "] " + res.message());
            }
        }, CommandRegistry.Meta.of("Confirm a blocked systemic command by its token", "confirm <CONF-token>"));

        r.register("add", (b, ctx) -> {
            if (ctx.args().length < 2) {
                ctx.sender().sendMessage("§eUsage: " + b.id() + " add <thing> at <where>  (e.g. add an NPC named Steve at here)");
                return;
            }
            String thing = String.join(" ", ctx.args());
            // try to resolve a catalog command by keywords
            String suggested = learning.suggest(thing);
            if (suggested != null) {
                ctx.sender().sendMessage("§7[" + b.id() + "] Matched command §f" + suggested
                        + "§7 — building args (AI command-assembly lands with full Admin Ops).");
                ctx.sender().sendMessage("§7  For now: " + b.id() + " cmd " + suggested
                        + " <args> (you provide the exact args), or ask the AI via " + b.id() + " chat.");
            } else {
                ctx.sender().sendMessage("§7[" + b.id() + "] No matching command in the catalog ("
                        + learning.size() + " known). Try " + b.id() + " refresh, or " + b.id() + " cmd <exact>.");
            }
        }, CommandRegistry.Meta.of("Add something (NPC, sign, …) via a plugin command", "add <thing> at <where>"));
    }
}
