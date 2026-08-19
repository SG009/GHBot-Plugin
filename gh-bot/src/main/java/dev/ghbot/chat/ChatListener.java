package dev.ghbot.chat;

import dev.ghbot.bot.BotRegistry;
import dev.ghbot.bot.GHBot;
import dev.ghbot.command.CommandBridge;
import dev.ghbot.log.WIBLogger;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.Arrays;
import java.util.List;

/**
 * Chat command parser — "@GH000 <command> <args>" / "@GH <command> <args>".
 * Mirrors your old index.js chat handler (commandTarget = bot username).
 */
@SuppressWarnings("deprecation") // AsyncPlayerChatEvent — fine for Phase 0; migrate to AsyncChatEvent later
public class ChatListener implements Listener {

    private final BotRegistry registry;
    private final CommandBridge bridge;
    private final WIBLogger log;
    private final List<String> allowedPlayers;

    public ChatListener(BotRegistry registry, CommandBridge bridge, WIBLogger log, List<String> allowedPlayers) {
        this.registry = registry;
        this.bridge = bridge;
        this.log = log;
        this.allowedPlayers = allowedPlayers;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onChat(AsyncPlayerChatEvent e) {
        Player player = e.getPlayer();
        String message = e.getMessage();

        // chat-log (your old behavior) — log every chat line with WIB stamp
        log.chatLog(player.getName() + ": " + message);

        if (!message.trim().startsWith("@")) return;

        String[] parts = message.trim().split("\\s+");
        String target = parts[0];                 // e.g. "@GH000" or "@GH"
        if (parts.length < 2) return;

        // not for us?
        if (!isForUs(target)) return;

        // permission gate: ops or allowed-players
        if (!player.isOp() && !allowedPlayers.contains(player.getName().toLowerCase())) {
            player.sendMessage("§cYou are not allowed to use GH-bot.");
            return;
        }

        GHBot bot = registry.resolve(target);
        if (bot == null) {
            player.sendMessage("§cBot \"" + target + "\" not found.");
            return;
        }

        String cmd = parts[1];
        String[] args = Arrays.copyOfRange(parts, 2, parts.length);

        log.info("[" + bot.id() + "] chat cmd by " + player.getName() + ": " + cmd + " " + String.join(" ", args));
        // v0.21.11 — commands run OFF the main thread (AI calls are blocking HTTP; block
        // placement is scheduler-based). The only main-required op (Bukkit.dispatchCommand
        // inside `cmd`) self-hops via CommandLearning. Runs on the bridge async pool.
        bridge.async(() -> bridge.dispatch(bot, (CommandSender) player, cmd, args));
        e.setCancelled(true); // command consumed
    }

    private boolean isForUs(String target) {
        return registry.resolve(target) != null;
    }
}
