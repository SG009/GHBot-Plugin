package dev.ghbot.command;

import dev.ghbot.bot.GHBot;
import dev.ghbot.config.PluginConfig;
import dev.ghbot.core.CapabilityEstimator;
import dev.ghbot.ai.ProviderRegistry;
import dev.ghbot.core.SystemStats;
import dev.ghbot.log.WIBLogger;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bridge between bots, their command registries, and senders.
 * Registers built-in commands (help, memory, cap, debuglog) on every bot
 * and routes dispatches with centralized error logging. Phase 1: cap uses
 * the live capability estimator (P16).
 */
public class CommandBridge {

    private final Map<String, CommandRegistry> registries = new LinkedHashMap<>();
    private final WIBLogger log;
    private final SystemStats stats;
    private final PluginConfig cfg;
    private final Runnable noticeSender;      // throttled P16 capability notices (console)
    private final ExecutorService async = Executors.newCachedThreadPool();
    private ProviderRegistry providers;

    public CommandBridge(WIBLogger log, SystemStats stats, PluginConfig cfg, Runnable noticeSender) {
        this.log = log;
        this.stats = stats;
        this.cfg = cfg;
        this.noticeSender = noticeSender;
    }

    public void setProviders(ProviderRegistry providers) { this.providers = providers; }
    public ProviderRegistry providers() { return providers; }

    private dev.ghbot.review.GhostService ghostService;
    public void setGhostService(dev.ghbot.review.GhostService gs) { this.ghostService = gs; }
    public dev.ghbot.review.GhostService ghostService() { return ghostService; }

    public void shutdown() {
        async.shutdownNow();
    }

    /** Run a command body on the async pool so heavy work (scan/find) never blocks the main/chat thread. */
    public void async(Runnable r) {
        async.submit(r);
    }

    public void logActivity(GHBot bot, String change) {
        if (bot.debugLogging()) log.info("[" + bot.id() + "] " + change);
    }

    public CommandRegistry registryOf(GHBot bot) {
        return registries.computeIfAbsent(bot.id(), k -> new CommandRegistry());
    }

    /** Register built-in commands on a bot (help, memory clear, cap, debuglog). */
    public void registerBuiltins(GHBot bot) {
        CommandRegistry r = registryOf(bot);

        r.register("help", (b, ctx) ->
                ctx.sender().sendMessage(r.helpText(b)),
                CommandRegistry.Meta.of("Show available commands with descriptions and usage", "help"));

        r.register("memory", (b, ctx) -> {
            String action = ctx.args().length > 0 ? ctx.args()[0].toLowerCase() : "";
            if (action.equals("clear")) {
                int n = b.memory().size();
                b.clearMemory();
                ctx.sender().sendMessage("§aMemory cleared: " + n + " entries.");
                log.info("[" + b.id() + "] memory cleared by " + ctx.sender().getName());
            } else {
                ctx.sender().sendMessage("§eUsage: " + b.id() + " memory clear");
            }
        }, CommandRegistry.Meta.of("Manage session memory", "memory clear"));

        r.register("device-info", (b, ctx) -> {
            List<String> providers = new ArrayList<>();
            providers.add(b.config().provider());
            var cap = CapabilityEstimator.build(stats, providers);
            ctx.sender().sendMessage("§e[" + b.id() + "] device-info:");
            ctx.sender().sendMessage("§f  TPS §a" + String.format("%.1f", stats.tps)
                    + "§f · CPU §a" + String.format("%.1f%%", stats.cpuPercent)
                    + "§f · RAM §a" + stats.usedMemMB + "/" + stats.maxMemMB + " MB"
                    + "§f · players §a" + stats.players
                    + "§f · up §a" + stats.uptimeSec + "s");
            ctx.sender().sendMessage("§f  tier §a" + cap.tier() + " §7(" + cap.tierName()
                    + ") · est. §a" + cap.estMaxBuildBlocks() + "§7 blocks/job · §a"
                    + cap.parallelBots() + "§7 parallel bots");
            if (cap.fawePresent()) ctx.sender().sendMessage("§f  FAWE/WorldEdit: §aavailable");
            ctx.sender().sendMessage("§f  memory entries: §a" + b.memory().size()
                    + "§f · queue: §a" + b.queue().size()
                    + "§f · activity: §a" + b.activity());
        }, CommandRegistry.Meta.of("Device info — one-shot stats & capability report", "device-info"));

        r.register("cap", (b, ctx) -> {
            List<String> providers = new ArrayList<>();
            providers.add(b.config().provider());
            ctx.sender().sendMessage(CapabilityEstimator.reportText(
                    CapabilityEstimator.build(stats, providers)));
        }, CommandRegistry.Meta.of("Capability report — what this device can handle", "cap"));

        r.register("debuglog", (b, ctx) -> {
            if (ctx.args().length < 1) {
                ctx.sender().sendMessage("§eUsage: " + b.id() + " debuglog <show|hide>");
                return;
            }
            boolean on = ctx.args()[0].equalsIgnoreCase("show");
            b.setDebugLogging(on);
            ctx.sender().sendMessage("§a[" + b.id() + "] Debug logging " + (on ? "enabled." : "disabled."));
        }, CommandRegistry.Meta.of("Toggle per-bot debug logs", "debuglog <show|hide>"));
    }

    /** Fire a throttled capability notice (P16) — called periodically or at enable. */
    public void maybeNotice() {
        if (!cfg.capabilityNotices() || noticeSender == null) return;
        noticeSender.run();
    }

    public void dispatch(GHBot bot, CommandSender sender, String name, String[] args) {
        // v0.22.0 — JARVIS-FOR-ADMIN: hard-block non-ops (console + OP players only).
        if (sender instanceof org.bukkit.entity.Player p && !p.isOp()) {
            sender.sendMessage("§cGH-bot is admin-only — console/OP required.");
            return;
        }
        // v0.22.0 — shelved commands are removed from the surface: never run, say so.
        if (dev.ghbot.command.BotCommands.SHELVED.contains(name)) {
            sender.sendMessage("§7[" + bot.id() + "] '" + name
                    + "' is shelved in v0.22.0 (not part of the admin surface).");
            return;
        }
        CommandRegistry reg = registries.get(bot.id());
        if (reg == null) {
            sender.sendMessage("§cCommand system not loaded for " + bot.id() + ".");
            return;
        }
        if (bot.debugLogging()) {
            log.info("[" + bot.id() + "] dispatch: " + name + " " + String.join(" ", args));
        }
        try {
            reg.execute(bot, sender, name, args);
        } catch (RuntimeException | StackOverflowError e) {   // v0.21.46 — contain Errors too (seen SOE in build)
            log.error("Error executing \"" + name + "\" on " + bot.id(), e);
        }
    }
}
