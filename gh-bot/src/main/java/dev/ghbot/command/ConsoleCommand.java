package dev.ghbot.command;

import dev.ghbot.bot.BotRegistry;
import dev.ghbot.bot.GHBot;
import dev.ghbot.core.CapabilityEstimator;
import dev.ghbot.core.SystemStats;
import dev.ghbot.log.WIBLogger;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.Arrays;
import java.util.List;

/**
 * /gh console controls — mirrors your old index.js readline commands.
 * Phase 1: /gh status now includes live system stats + capability tier.
 */
public class ConsoleCommand implements CommandExecutor {

    private final BotRegistry registry;
    private final CommandBridge bridge;
    private final WIBLogger log;
    private final SystemStats stats;
    private final java.util.function.Supplier<String> reloadAction;   // v0.21.12 — /gh reload
    private int webPort = -1;

    public ConsoleCommand(BotRegistry registry, CommandBridge bridge, WIBLogger log, SystemStats stats,
                          java.util.function.Supplier<String> reloadAction) {
        this.registry = registry;
        this.bridge = bridge;
        this.log = log;
        this.stats = stats;
        this.reloadAction = reloadAction;
    }

    public void setWebPort(int port) { this.webPort = port; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(statusText());   // default: show status (like your old bot)
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "help", "?" -> sender.sendMessage(helpText());
            case "status" -> sender.sendMessage(statusText());
            case "start" -> startBot(sender, args);
            case "stop" -> stopBot(sender, args);
            case "debuglog" -> debuglog(sender, args);
            case "device-info", "di" -> deviceInfo(sender);
            case "reload" -> {
                if (reloadAction == null) sender.sendMessage("§cReload not available.");
                else sender.sendMessage(reloadAction.get());
            }
            case "web" -> {
                if (webPort < 0) sender.sendMessage("§7Web server not enabled (server.web.enabled: false).");
                else sender.sendMessage("§aWeb status: §fhttp://<this-server-ip>:" + webPort + "/ §7(open from any device on your LAN)");
            }
            default -> {
                GHBot bot = registry.defaultBot();
                if (bot == null) { sender.sendMessage("§cNo default bot configured."); return true; }
                // v0.21.11 — run commands off the main thread (AI HTTP must never block main).
                // Main-only ops (Bukkit.dispatchCommand in `cmd`) self-hop via CommandLearning.
                bridge.async(() -> bridge.dispatch(bot, sender, args[0],
                        Arrays.copyOfRange(args, 1, args.length)));
            }
        }
        return true;
    }

    private void startBot(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage("§cUsage: /gh start <BotName>"); return; }
        GHBot bot = registry.resolve(args[1]);
        if (bot == null) { sender.sendMessage("§cBot " + args[1] + " not found in config.yml."); return; }
        if (bot.isBusy()) { sender.sendMessage("§e[" + bot.id() + "] Already busy (" + bot.activity() + ")."); return; }
        sender.sendMessage("§a[" + bot.id() + "] Ready. (" + bot.config().provider() + " provider · " + bot.config().mode() + " mode)");
        log.info("[" + bot.id() + "] started");
    }

    private void stopBot(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage("§cUsage: /gh stop <BotName>"); return; }
        GHBot bot = registry.resolve(args[1]);
        if (bot == null) { sender.sendMessage("§cBot " + args[1] + " not found."); return; }
        if (bot.isBusy()) {
            bot.setActivity(GHBot.Activity.IDLE);
            bot.queue().clear();
            sender.sendMessage("§e[" + bot.id() + "] Stopped current job, queue cleared.");
        } else {
            sender.sendMessage("§7[" + bot.id() + "] Not running any job.");
        }
        log.info("[" + bot.id() + "] stopped");
    }

    private void debuglog(CommandSender sender, String[] args) {
        // /gh debuglog <show|hide>  (default bot)  |  /gh debuglog <Bot> <show|hide>
        if (args.length < 2) { sender.sendMessage("§cUsage: /gh debuglog <show|hide> | /gh debuglog <Bot> <show|hide>"); return; }
        String onArg;
        GHBot bot;
        if (args.length >= 3) {
            bot = registry.resolve(args[1]);
            onArg = args[2];
        } else {
            bot = registry.defaultBot();
            onArg = args[1];
        }
        if (bot == null) { sender.sendMessage("§cBot not found."); return; }
        boolean on = onArg.equalsIgnoreCase("show");
        bot.setDebugLogging(on);
        sender.sendMessage("§a[" + bot.id() + "] Debug logging " + (on ? "enabled." : "disabled.")
                + " §7(dispatches only — no periodic spam; use /gh device-info for reports)");
    }

    private void deviceInfo(CommandSender sender) {
        var cap = CapabilityEstimator.build(stats, java.util.List.of());
        sender.sendMessage("§eGH-Bot device-info:");
        sender.sendMessage("§f  TPS §a" + String.format("%.1f", stats.tps)
                + "§f · CPU §a" + String.format("%.1f%%", stats.cpuPercent)
                + "§f · RAM §a" + stats.usedMemMB + "/" + stats.maxMemMB + " MB"
                + "§f · players §a" + stats.players
                + "§f · up §a" + stats.uptimeSec + "s");
        sender.sendMessage("§f  capability tier §a" + cap.tier() + " §7(" + cap.tierName()
                + ") · est. §a" + cap.estMaxBuildBlocks() + "§7 blocks/job · §a"
                + cap.parallelBots() + "§7 parallel bots · offline AI: §a" + cap.offlineModel());
        for (GHBot b : registry.all()) {
            sender.sendMessage("§f  " + b.id() + " §7activity=" + b.activity()
                    + " · debug=" + (b.debugLogging() ? "on" : "off")
                    + " · memory=" + b.memory().size() + " · queue=" + b.queue().size());
        }
        log.info("device-info requested by " + sender.getName());
    }

    private String statusText() {
        var cap = CapabilityEstimator.build(stats, List.of());
        StringBuilder sb = new StringBuilder("§eGH-Bot status (" + registry.count() + " bots, default " + registry.defaultId() + "):");
        for (GHBot b : registry.all()) {
            sb.append("\n§f- ").append(b.id())
              .append("§7  activity=").append(b.activity())
              .append(" · provider=").append(b.config().provider())
              .append(" · mode=").append(b.config().mode())
              .append(" · debug=").append(b.debugLogging() ? "on" : "off")
              .append(" · queue=").append(b.queue().size());
        }
        sb.append("\n§7  TPS §f").append(String.format("%.1f", stats.tps))
          .append("§7 · CPU §f").append(String.format("%.1f%%", stats.cpuPercent))
          .append("§7 · RAM §f").append(stats.usedMemMB).append("/").append(stats.maxMemMB).append(" MB")
          .append("§7 · players §f").append(stats.players)
          .append("§7 · up §f").append(stats.uptimeSec).append("s")
          .append("\n§7  Capability tier §f").append(cap.tier()).append(" §7(").append(cap.tierName())
          .append(") §7· est. §f").append(cap.estMaxBuildBlocks()).append("§7 blocks/job · §f")
          .append(cap.parallelBots()).append("§7 parallel bots");
        return sb.toString();
    }

    private String helpText() {
        return """
                §e/gh — GH-bot console controls
                §f  /gh help §7— this help
                §f  /gh status §7— bots + live system stats + capability tier
                §f  /gh start <Bot> §7· §f/gh stop <Bot>
                §f  /gh debuglog <show|hide> §7· §f/gh debuglog <Bot> <show|hide>
                §f  /gh device-info §7· §f/gh di §7— one-shot stats & capability report
                §f  /gh reload §7— re-read config.yml (AI keys/models apply now; bots/web need restart)
                §f  /gh web §7— web status URL (Phase 3+)
                §f  /gh <command> [args] §7— run any bot command on the default bot
                §7Example: §f/gh build a tower at spawn""";
    }
}
