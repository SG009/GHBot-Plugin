package dev.ghbot.audit;

import dev.ghbot.bot.GHBot;
import dev.ghbot.command.CommandBridge;

/**
 * v0.23.0 → v0.24.0 — the `audit` bot command (Phase B). Reachable from every
 * surface (chat AI tool / AUTO-TOOL, in-game, console /gh) because the tool
 * bridge whitelists the whole catalog. Admin-only via the v0.22.0 dispatch rule.
 */
public final class AuditCommands {

    private AuditCommands() {}

    public static void register(GHBot bot, CommandBridge bridge, AuditService svc) {
        var r = bridge.registryOf(bot);
        r.register("audit", (b, ctx) -> {
            String arg = ctx.args().length > 0 ? ctx.args()[0].toLowerCase() : "";
            switch (arg) {
                case "" -> sendLines(ctx.sender(), svc.digest());
                case "updates" -> {
                    ctx.sender().sendMessage("§7checking for updates… (results arrive in a moment)");
                    svc.refreshUpdatesAsync(() ->
                            ctx.sender().sendMessage(svc.updatesDetail()));
                }
                case "clear" -> {
                    svc.clear();
                    ctx.sender().sendMessage("§a[" + b.id() + "] audit log cleared (new WARN/ERROR will be captured fresh).");
                }
                case "selftest" -> {
                    svc.selfTest();
                    ctx.sender().sendMessage("§e[" + b.id() + "] emitted a test WARN — run `audit` in a few seconds; "
                            + "it should appear attributed to GHBot if the listener is live (attached: "
                            + svc.watch().isAttached() + ").");
                }
                default -> ctx.sender().sendMessage("§eUsage: " + b.id() + " audit [updates|clear|selftest]");
            }
        }, dev.ghbot.command.CommandRegistry.Meta.of(
                "Server-console audit: WARN/ERROR digest per plugin + update radar",
                "audit [updates|clear]"));
    }

    private static void sendLines(org.bukkit.command.CommandSender sender, String text) {
        for (String ln : text.split("\n")) sender.sendMessage(ln);
    }
}
