package dev.ghbot.audit;

import dev.ghbot.bot.GHBot;
import dev.ghbot.command.CommandBridge;

/**
 * v0.24.0 → v0.26.0 — the `audit` bot command. v0.26.0 (Phase E2, owner-proposed):
 * the audit doesn't just say WHAT happened — it hands the admin the FIX:
 * `audit show <n>` browses a group's full lines + stacks, `audit fix <n>` answers
 * from the audit-fixes.yml knowledge base (rules first, clearly-labeled AI guess
 * when nothing matches), `audit reload` re-reads the KB without a restart.
 * Reachable from every surface (chat AI tool / AUTO-TOOL, in-game, console /gh).
 */
public final class AuditCommands {

    private AuditCommands() {}

    public static void register(GHBot bot, CommandBridge bridge, AuditService svc,
                                dev.ghbot.ai.ChatService chatService) {
        var r = bridge.registryOf(bot);
        r.register("audit", (b, ctx) -> {
            String arg = ctx.args().length > 0 ? ctx.args()[0].toLowerCase() : "";
            switch (arg) {
                case "" -> sendLines(ctx.sender(), svc.digest());
                case "updates" -> {
                    // v0.26.0 honesty fix: answer with the CURRENT table immediately
                    // (no "results arrive in a moment" that then stays silent), and
                    // kick a silent re-check in the background.
                    if (svc.radarResults() != null) {
                        sendLines(ctx.sender(), svc.updatesDetail());
                        svc.refreshUpdatesAsync(null);
                    } else {
                        ctx.sender().sendMessage("§7update radar: first check still pending (runs ~60 s after boot)."
                                + " A re-check was kicked — ask `audit updates` again in a few seconds.");
                        svc.refreshUpdatesAsync(null);
                    }
                }
                case "show" -> {
                    Integer n = indexArg(ctx.args());
                    if (n == null) { usage(ctx.sender(), b); break; }
                    sendLines(ctx.sender(), svc.show(n));
                }
                case "fix" -> {
                    Integer n = indexArg(ctx.args());
                    if (n == null) { usage(ctx.sender(), b); break; }
                    String src = svc.groupSource(n);
                    if (src == null) { ctx.sender().sendMessage("§e" + svc.noSuchSource(n)); break; }
                    FixRules.Rule rule = svc.fixRule(n);
                    if (rule != null) {
                        sendLines(ctx.sender(), "🛠 fix for audit #" + n + " — " + src
                                + " · rule '" + rule.id() + "' ("
                                + (svc.fixes().isCustom(rule) ? "audit-fixes.yml" : "built-in") + "):\n"
                                + FixRules.render(rule, src));
                    } else if (chatService == null) {
                        ctx.sender().sendMessage("§7no known fix for #" + n + " (" + src + ") in the knowledge base"
                                + " — add a rule to plugins/GHBot/audit-fixes.yml, or browse the full lines with `audit show " + n + "`.");
                    } else {
                        // rare path: no rule → ONE clearly-labeled AI guess. Blocking the
                        // worker thread on the provider is deliberate — interim messages
                        // don't reach the web chat surface, only the returned reply does.
                        String hay = svc.groupContextForAi(n, 1200);
                        String prompt = "You are a PaperMC server-doctor helping a small-phone-hosted Paper 1.21.11 "
                                + "admin. Read these server-console WARN/ERROR lines and give ONE practical fix in at "
                                + "most 4 short lines, most-likely cause first. No fluff, no markdown headers.\n" + hay;
                        String ai = null;
                        try { ai = chatService.askOnce(b, "audit-fix", prompt); } catch (Throwable ignored) {}
                        if (ai == null || ai.isBlank() || ai.contains("Connect an AI provider")) {
                            ctx.sender().sendMessage("§7no rule for #" + n + " (" + src + ") and no AI provider available"
                                    + " — add a rule to plugins/GHBot/audit-fixes.yml or paste `audit show " + n + "` into chat.");
                        } else {
                            sendLines(ctx.sender(), "🤖 no ready-made rule for #" + n + " (" + src + ") — AI guess"
                                    + " (unverified — double-check before acting):\n" + ai.trim());
                        }
                    }
                }
                case "reload" -> ctx.sender().sendMessage("§a[GHBot] " + svc.reloadFixes());
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
                default -> usage(ctx.sender(), b);
            }
        }, dev.ghbot.command.CommandRegistry.Meta.of(
                "Server-console audit: WARN/ERROR digest + update radar + fix advice (show/fix <n>)",
                "audit [updates|show <n>|fix <n>|reload|clear|selftest]"));
    }

    private static void usage(org.bukkit.command.CommandSender sender, GHBot b) {
        sender.sendMessage("§eUsage: " + b.id() + " audit [updates|show <n>|fix <n>|reload|clear|selftest]");
    }

    private static Integer indexArg(String[] args) {
        if (args.length < 2) return null;
        try { return Integer.parseInt(args[1].trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private static void sendLines(org.bukkit.command.CommandSender sender, String text) {
        for (String ln : text.split("\n")) sender.sendMessage(ln);
    }
}
