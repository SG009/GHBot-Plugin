package dev.ghbot.ai;

import dev.ghbot.bot.GHBot;
import dev.ghbot.command.CommandBridge;
import dev.ghbot.command.CommandRegistry;

/**
 * Phase 5 — AI commands: chat / design / provider.
 */
public final class AiCommands {

    private AiCommands() {}

    public static void register(GHBot bot, CommandBridge bridge, ChatService chat) {
        CommandRegistry r = bridge.registryOf(bot);

        r.register("chat", (b, ctx) -> {
            if (ctx.args().length == 0) {
                ctx.sender().sendMessage("§eUsage: " + b.id() + " chat <message>");
                return;
            }
            String text = String.join(" ", ctx.args());
            chat.chat(b, ctx.sender(), text);
        }, CommandRegistry.Meta.of("Chat with GH-bot (AI)", "chat <message>"));

        r.register("design", (b, ctx) -> {
            if (ctx.args().length == 0) {
                ctx.sender().sendMessage("§eUsage: " + b.id() + " design <topic | answer | done>");
                return;
            }
            chat.design(b, ctx.sender(), ctx.args());
        }, CommandRegistry.Meta.of("Guided design conversation", "design <topic|answer|done>"));

        r.register("provider", (b, ctx) -> {
            if (ctx.args().length == 0 || ctx.args()[0].equalsIgnoreCase("list")) {
                StringBuilder sb = new StringBuilder("§e[" + b.id() + "] AI providers:");
                for (String line : bridge.providers().statusLines()) {
                    sb.append("\n§f  ").append(line);
                }
                sb.append("\n§7current: §f").append(bridge.providers().resolve(b).id());
                ctx.sender().sendMessage(sb.toString());
                return;
            }
            if (ctx.args()[0].equalsIgnoreCase("set")) {
                if (ctx.args().length < 2) {
                    ctx.sender().sendMessage("§eUsage: " + b.id() + " provider set <gemini|ollama|openai|fallback|auto>");
                    return;
                }
                String want = ctx.args()[1].toLowerCase();
                boolean known = "auto".equals(want) || bridge.providers().get(want) != null;
                if (!known) {
                    ctx.sender().sendMessage("§cUnknown provider \"" + want + "\".");
                    return;
                }
                // runtime override stored in memory (persisted via session)
                b.memory().put("provider", want);
                var resolved = bridge.providers().resolve(b);
                ctx.sender().sendMessage("§a[" + b.id() + "] Provider set to §f" + want
                        + "§a → " + resolved.displayName()
                        + (resolved.isConfigured() ? "" : " §c(not configured — will use fallback)"));
                return;
            }
            ctx.sender().sendMessage("§eUsage: " + b.id() + " provider list|set <name>");
        }, CommandRegistry.Meta.of("List or set the AI provider", "provider list | provider set <name>"));
    }
}
