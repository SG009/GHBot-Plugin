package dev.ghbot;

import java.io.IOException;

import dev.ghbot.bot.BotRegistry;
import dev.ghbot.bot.GHBot;
import dev.ghbot.chat.ChatListener;
import dev.ghbot.command.CommandBridge;
import dev.ghbot.command.CommandRegistry;
import dev.ghbot.command.CommandLearning;
import dev.ghbot.admin.AdminCommands;
import dev.ghbot.admin.AdminService;
import dev.ghbot.avatar.AvatarCommands;
import dev.ghbot.avatar.AvatarService;
import dev.ghbot.avatar.MarkerService;
import dev.ghbot.command.CommandLearningCommands;
import dev.ghbot.command.ConsoleCommand;
import dev.ghbot.config.PluginConfig;
import dev.ghbot.core.CapabilityEstimator;
import dev.ghbot.core.StatsSampler;
import dev.ghbot.edit.BlockEditCommands;
import dev.ghbot.ai.AiCommands;
import dev.ghbot.ai.ChatService;
import dev.ghbot.ai.ProviderRegistry;
import dev.ghbot.builder.BuildCommands;
import dev.ghbot.builder.BuildService;
import dev.ghbot.edit.BlockEditService;
import dev.ghbot.edit.EditCommands;
import dev.ghbot.edit.EditService;
import dev.ghbot.location.LocationCommands;
import dev.ghbot.location.LocationStore;
import dev.ghbot.review.GhostService;
import dev.ghbot.review.ReviewCommands;
import dev.ghbot.schematic.SchematicCommands;
import dev.ghbot.schematic.DatasetCommands;
import dev.ghbot.schematic.LearningDataset;
import dev.ghbot.schematic.SchematicDownloader;
import dev.ghbot.schematic.SchematicService;
import dev.ghbot.web.PreviewCommands;
import dev.ghbot.web.PreviewJob;
import dev.ghbot.web.PreviewRegistry;
import dev.ghbot.web.WebStatusServer;
import dev.ghbot.log.WIBLogger;
import dev.ghbot.session.SessionStore;
import dev.ghbot.terrain.TerrainCommands;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * GH-Bot — Phase 0 Foundation.
 * Loads config (mirrors your old settings.json), builds the bot registry,
 * wires per-bot command registries + built-ins, the /gh console command,
 * the "@GH000 ..." chat parser, WIB logging, and session persistence.
 */
public class GHBotPlugin extends JavaPlugin {

    private PluginConfig cfg;
    private WIBLogger log;
    private BotRegistry registry;
    private CommandBridge bridge;
    private SessionStore sessions;
    private StatsSampler sampler;
    private BlockEditService editService;
    private LocationStore locations;
    private WebStatusServer webServer;
    private ProviderRegistry providers;
    private ChatService chatService;
    private BuildService buildService;
    private GhostService ghostService;
    private SchematicService schematics;
    private EditService editService2;
    private CommandLearning commandLearning;
    private AdminService adminService;
    private AvatarService avatarService;
    private MarkerService markerService;
    private LearningDataset dataset;
    private SchematicDownloader downloader;
    private PreviewRegistry previews;
    private long lastNotice = 0;

    @Override
    public void onEnable() {
        long t0 = System.currentTimeMillis();

        cfg = PluginConfig.load(this);
        log = new WIBLogger(getLogger(), getDataFolder().toPath(), cfg.chatLog());
        log.info("GH-Bot loading…");
        dev.ghbot.core.MainThread.setPlugin(this);   // v0.21.9 — main-thread hops for async callers

        // Registry + per-bot command systems
        registry = new BotRegistry(cfg);

        // Phase 1 — Core Brain: live stats sampler (CPU/RAM/TPS/uptime)
        sampler = StatsSampler.start(this, log, cfg.statsIntervalTicks(),
                () -> registry.all().stream().anyMatch(GHBot::debugLogging));

        bridge = new CommandBridge(log, sampler.stats(), cfg, this::maybeCapabilityNotice);
        providers = new ProviderRegistry(cfg.ai());           // Phase 5 — AI providers
        bridge.setProviders(providers);
        chatService = new ChatService(this, providers, log, cfg.ai().chatMaxHistory(), cfg.ai().compressBudgetChars());
        editService = new BlockEditService(this, log, cfg);   // Phase 3 — block editing
        buildService = new BuildService(this, log, cfg, editService.undo()); // Phase 6 — builder
        ghostService = new GhostService(this, log, editService.undo(), cfg.editBlocksPerTick()); // Phase 7 — ghost review
        ghostService.setOnStaged((bot, st) -> {                 // Phase 9 — auto-register web preview
            previews.register(st.specName, bot.id(), st.model, true);
        });
        schematics = new SchematicService(getDataFolder().toPath(), log);  // Phase 8 — schematics
        avatarService = new AvatarService(log);                       // Phase 12 — avatar
        markerService = new MarkerService(log);                      // Phase 12 — markers
        // v0.21.41 — wire avatar/schematic services AFTER they are constructed (ordering fix)
        ghostService.setAvatarService(avatarService);           // Phase 12 — avatar at build site
        ghostService.setSchematicService(schematics);
        ghostService.setAutoSaveApproved(cfg.autoSaveApproved());
        buildService.setAvatarService(avatarService);           // Phase 12 — avatar on direct build
        editService2 = new EditService(this, log, editService.undo(), cfg.editBlocksPerTick()); // Phase 10 — structure editing
        commandLearning = new CommandLearning(this, log);  // Phase 11 — command learning
        adminService = new AdminService(getDataFolder().getParentFile().toPath(), log, getDataFolder().toPath(), this); // Phase 11b — admin ops (owner enables reload health-check guard)
        dataset = new LearningDataset(getDataFolder().toPath(), log);      // Phase 8b — learning dataset
        downloader = new SchematicDownloader(log);
        previews = new PreviewRegistry(20);                    // Phase 9 — web preview jobs
        locations = new LocationStore(getDataFolder().toPath(), log); // Phase 4 — named locations
        dev.ghbot.terrain.CoordResolver.setLocationStore(locations);
        // Session persistence (P5) — MUST exist before registerBot() (it loads sessions per bot).
        sessions = new SessionStore(getDataFolder().toPath(), cfg.sessionsPersist(), cfg.sessionsDir(), log);
        for (GHBot bot : registry.all()) {
            registerBot(bot);   // also loads session + restores markers (Phase 12)
        }

        // Phase 14 — multi-bot crew commands (deploy/undeploy/workers)
        registerCrewCommands();
        registerToggles();
        bridge.setGhostService(ghostService);

        // Phase 4+9 — web status page + preview viewer (P13/P14)
        if (cfg.webEnabled()) {
            try {
                webServer = new WebStatusServer(cfg.webPort(), this::webStats, this::webBots, log);
                webServer.attachPreviews(previews, registry, ghostService, schematics);
                webServer.attachChat(chatService, registry, this::toolExecutor, commandLearning, this::webStats);
                webServer.start();
            } catch (IOException e) {
                log.error("Could not start web status server", e);
            }
        }

        // Console /gh command
        var console = getCommand("gh");
        if (console != null) {
            ConsoleCommand cc = new ConsoleCommand(registry, bridge, log, sampler.stats(), this::reloadGhbot);
            cc.setWebPort(cfg.webEnabled() ? cfg.webPort() : -1);
            console.setExecutor(cc);
        } else {
            log.warn("Could not register /gh command (plugin.yml issue?)");
        }

        // @GH000 chat parser
        Bukkit.getPluginManager().registerEvents(
                new ChatListener(registry, bridge, log, cfg.allowedPlayers()), this);

        long ms = System.currentTimeMillis() - t0;
        log.info("Enabled in " + ms + "ms — " + registry.count() + " bot(s), default " + registry.defaultId());

        // P16: one throttled capability notice at startup on low-spec devices
        maybeCapabilityNotice();

        // Phase-0 self-test (config toggle, defaults on)
        if (cfg.selfTest()) {
            Bukkit.getScheduler().runTaskLater(this, this::selfTest, 1L);
        }

        // v0.21.41 — periodic session eviction (every 5 min) to prevent memory creep
        // on long-running servers (the 6 GB phone).
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            try {
                chatService.evictStaleSessions();
            } catch (Exception e) {
                log.warn("[GHBot] session eviction failed: " + e.getMessage());
            }
        }, 6000L, 6000L);   // 5 minutes = 5*60*20 ticks
    }

    /** Builds the agent tool executor (Phase 9b v2 + v0.21.9 thread-safe). */
    private dev.ghbot.agent.ToolExecutor toolExecutor() {
        return new dev.ghbot.agent.ToolExecutor((name, args) -> {
            var bot = registry.defaultBot();
            if (bot == null) return "no bot";
            String arg = args.length > 0 ? String.join(" ", args) : "";
            // ── read-only tools — safe on any thread (TerrainScanner is snapshot-based) ──
            switch (name) {
                case "status" -> {
                    var cap = dev.ghbot.core.CapabilityEstimator.build(sampler.stats(), java.util.List.of());
                    return "TPS " + String.format("%.1f", sampler.stats().tps)
                            + " · CPU " + String.format("%.1f%%", sampler.stats().cpuPercent)
                            + " · RAM " + sampler.stats().usedMemMB + "/" + sampler.stats().maxMemMB + " MB"
                            + " · players " + sampler.stats().players
                            + " · tier " + cap.tier() + " (" + cap.tierName() + ")"
                            + " · bots " + registry.count();
                }
                case "players" -> {
                    if (Bukkit.getServer() == null || Bukkit.getOnlinePlayers().isEmpty()) return "no players online";
                    StringBuilder sb = new StringBuilder();
                    for (var p : Bukkit.getOnlinePlayers()) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(p.getName());
                    }
                    return "online (" + Bukkit.getOnlinePlayers().size() + "): " + sb;
                }
                case "worlds" -> {
                    if (Bukkit.getServer() == null || Bukkit.getWorlds().isEmpty()) return "no worlds";
                    StringBuilder sb = new StringBuilder();
                    for (var w : Bukkit.getWorlds()) {
                        if (sb.length() > 0) sb.append(" · ");
                        sb.append(w.getName()).append(" (").append(w.getEnvironment().name().toLowerCase()).append(")");
                    }
                    return sb.toString();
                }
                case "scan" -> {
                    // scan [radius] [x y z] — coords optional (defaults to bot location)
                    int radius = args.length > 0 ? Math.max(1, Math.min(100, Integer.parseInt(args[0]))) : 50;
                    var loc = baseToolLocation(bot);
                    if (loc == null) return "no location";
                    if (args.length >= 4) {
                        try {
                            loc = new org.bukkit.Location(loc.getWorld(),
                                    Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]));
                        } catch (NumberFormatException e) { return "bad coords"; }
                    }
                    return dev.ghbot.terrain.TerrainScanner.scan(loc, radius).toLine();
                }
                case "find" -> {
                    // v0.21.29 — find <block> [radius] [x y z] (coords optional, default bot origin)
                    if (args.length < 1) return "usage: find <block> [radius] [x y z]";
                    org.bukkit.Material mat = org.bukkit.Material.matchMaterial(args[0].toLowerCase());
                    if (mat == null) mat = org.bukkit.Material.matchMaterial("minecraft:" + args[0].toLowerCase());
                    if (mat == null) return "unknown block: " + args[0];
                    int radius = args.length >= 2 ? Math.max(1, Math.min(100, Integer.parseInt(args[1]))) : 40;
                    var loc = baseToolLocation(bot);
                    if (loc == null) return "no location";
                    if (args.length >= 5) {
                        try {
                            loc = new org.bukkit.Location(loc.getWorld(),
                                    Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4]));
                        } catch (NumberFormatException e) { return "bad coords"; }
                    }
                    var found = dev.ghbot.terrain.TerrainScanner.findBlocks(loc, mat, radius, 8);
                    if (found.isEmpty()) return "no " + args[0] + " within " + radius + " blocks of (" + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ() + ")";
                    StringBuilder sb = new StringBuilder(found.size() + " " + args[0] + " found near (" + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ() + "):");
                    for (var b : found) sb.append("\n- (").append(b.getX()).append(",").append(b.getY()).append(",").append(b.getZ()).append(")");
                    return sb.toString();
                }
            }
            // ── v0.21.11: run tools OFF the main thread. AI calls (build/plan/edit/schem/chat)
            // are blocking HTTP — they must NEVER run on the main thread (that froze the server).
            // Block placement is scheduler-based; `cmd` self-hops to main via CommandLearning.
            try {
                return runMutatingTool(bot, name, args);
            } catch (Throwable t) {
                return "tool error: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            }
        });
    }

    /** World-touching tool bodies — MUST run on the main thread. */
    private String runMutatingTool(GHBot bot, String name, String[] args) throws Exception {
        String arg = args.length > 0 ? String.join(" ", args) : "";
        switch (name) {
            case "look" -> {
                if (args.length < 3) return "usage: look <x> <y> <z>";
                try {
                    final int x = Integer.parseInt(args[0]), y = Integer.parseInt(args[1]), z = Integer.parseInt(args[2]);
                    if (Bukkit.getServer() == null || Bukkit.getWorlds().isEmpty()) return "no world";
                    // quick single-block read — hop to main (world reads must be on main)
                    return dev.ghbot.core.MainThread.call(() -> {
                        var blk = Bukkit.getWorlds().get(0).getBlockAt(x, y, z);
                        return blk.getType().name().toLowerCase() + " at (" + x + "," + y + "," + z + ")";
                    });
                } catch (NumberFormatException e) { return "bad coords"; }
            }
            case "cmd" -> {
                if (arg.isBlank()) return "usage: cmd <command...> (separate multiple with ';')";
                // v0.21.26 — return the command's ACTUAL output (e.g. the schematic list) so the
                // technician can see what the command printed, not just "ran ok".
                StringBuilder out = new StringBuilder();
                for (String l : arg.split(";")) {
                    String t = l.trim();
                    if (t.isEmpty()) continue;
                    if (out.length() > 0) out.append("\n");
                    out.append(commandLearning.dispatchCaptured(bot, t));
                }
                return out.toString();
            }
            case "catalog" -> {
                // list the server's command catalog (every command GH-bot can run via cmd)
                var all = commandLearning.all();
                if (all.isEmpty()) return "Command catalog empty — run refresh first.";
                String filter = args.length > 0 ? args[0].toLowerCase() : "";
                StringBuilder sb = new StringBuilder("Server command catalog (" + all.size() + "):");
                int n = 0;
                for (var e : all.entrySet()) {
                    if (!filter.isEmpty() && !e.getKey().contains(filter)) continue;
                    if (n++ >= 60) { sb.append("\n… and more (").append(all.size() - n).append("); use catalog <keyword> to filter"); break; }
                    sb.append("\n- /").append(e.getKey());
                    if (e.getValue() != null && !e.getValue().isBlank()) sb.append(" — ").append(e.getValue());
                }
                return sb.toString();
            }
            case "admin" -> {
                if (args.length < 1) return "usage: admin <read|set|backup|restore|rollback|reload|menu> …";
                switch (args[0].toLowerCase()) {
                    case "read" -> {
                        if (args.length < 2) return "usage: admin read <file>";
                        String content = adminService.read(args[1]);
                        String[] lines = content.split("\n");
                        int n = Math.min(10, lines.length);
                        StringBuilder sb = new StringBuilder(args[1] + " (" + lines.length + " lines):");
                        for (int i = 0; i < n; i++) sb.append("\n").append(lines[i]);
                        if (lines.length > n) sb.append("\n… ").append(lines.length - n).append(" more");
                        return sb.toString();
                    }
                    case "set" -> {
                        boolean propShortcut = args.length >= 2 && dev.ghbot.admin.AdminService.SERVER_PROPERTY_KEYS
                                .contains(args[1].toLowerCase());
                        if (args.length < (propShortcut ? 3 : 4))
                            return "usage: admin set <file> <key> <value> | admin set <property> <value> (e.g. admin set motd Welcome! Have fun!)";
                        String file = propShortcut ? "server.properties" : args[1];
                        String key = propShortcut ? args[1].toLowerCase() : args[2];
                        int vStart = propShortcut ? 2 : 3;
                        String value = String.join(" ", java.util.Arrays.copyOfRange(args, vStart, args.length));
                        if (value.isBlank()) return "usage: admin set <file> <key> <value>";
                        var bak = adminService.set(file, key, value, org.bukkit.Bukkit.getConsoleSender());
                        return "set " + file + " " + key + "=" + value
                                + (bak != null ? " (backup " + bak.getFileName() + ")" : "")
                                + " [token=" + adminService.lastToken() + "]";
                    }
                    case "backup" -> {
                        if (args.length < 2) return "usage: admin backup <file>";
                        var bak = adminService.backup(args[1]);
                        return bak == null ? "no such file: " + args[1] : "backed up → " + bak.getFileName();
                    }
                    case "restore" -> {
                        if (args.length < 2) return "usage: admin restore <file>";
                        var bak = adminService.restore(args[1]);
                        return "restored " + args[1] + " from " + bak.getFileName();
                    }
                    case "rollback" -> {
                        String what = args.length >= 2 ? adminService.rollback(args[1]) : adminService.rollbackLast();
                        return "rolled back: " + what;
                    }
                    case "reload" -> {
                        String pn = args.length >= 2 ? args[1] : null;
                        var res = adminService.reload(pn, org.bukkit.Bukkit.getConsoleSender());
                        return res.dispatched() ? res.message() : "FAILED: " + res.message();
                    }
                    case "menu" -> {
                        if (args.length < 2) return "usage: admin menu <name> [title]";
                        String title = args.length >= 3 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)) : null;
                        var f = adminService.createMenu(args[1], title);
                        return "created DeluxeMenus menu " + f.getFileName() + " (title: " + (title == null ? "default" : title) + ")";
                    }
                    default -> { return "usage: admin <read|set|backup|restore|rollback|reload|menu> …"; }
                }
            }
            case "build" -> {
                if (arg.isBlank()) return "usage: build <prompt>";
                if (Bukkit.getServer() == null) return "no live server";
                bridge.dispatch(bot, Bukkit.getConsoleSender(), "build", arg.split("\\s+"));
                // v0.21.26 — after staging, auto-run `view` so the AI gets the 3D viewer URL
                String viewOut = dev.ghbot.agent.ToolBridge.run(bridge, bot, "view", new String[0]);
                // v0.21.34 — also give the preview image URL (BlockGPT-style inline image).
                // The view output contains "http://<ip>:<port>/view/<jobId>" — derive the preview URL from it.
                java.util.regex.Matcher vu = java.util.regex.Pattern.compile("(https?://[^/\\s]+)/view/([a-z0-9-]+)").matcher(viewOut);
                if (vu.find()) {
                    String base = vu.group(1), jobId = vu.group(2);
                    return "build requested: " + arg + "\n" + viewOut
                            + "\nPREVIEW_IMG: " + base + "/view/" + jobId + "/preview.png";
                }
                return "build requested: " + arg + "\n" + viewOut;
            }
            case "undo" -> {
                // v0.21.13 — "undo what you were doing" on a STAGED build = deny (clear the ghost);
                // plain undo only reverts APPLIED block edits.
                if (ghostService != null && ghostService.hasStaged(bot)) {
                    ghostService.clear(bot, Bukkit.getConsoleSender(), false);
                    return "cleared the staged build (deny) — nothing was applied, nothing to undo";
                }
                editService.undo(bot, 0, Bukkit.getConsoleSender(), () -> {});
                return "undo started";
            }
            default -> {
                return dev.ghbot.agent.ToolBridge.run(bridge, bot, name, args);
            }
        }
    }

    /** /gh reload — re-read config.yml + rebuild AI providers without a server restart (v0.21.12). */
    public String reloadGhbot() {
        StringBuilder out = new StringBuilder();
        try {
            // 1) config.yml + AI providers
            cfg = PluginConfig.load(this);
            providers.reload(cfg.ai());
            bridge.setProviders(providers);
            out.append("config.yml + AI providers ✓ (").append(providers.statusLines().size()).append(")\n");
            // 2) named locations (locations.yml)
            if (locations != null) { locations.reload(); out.append("locations.yml ✓ (").append(locations.all().size()).append(")\n"); }
            // 3) learning dataset (learning_dataset.yml)
            if (dataset != null) { dataset.reload(); out.append("learning_dataset.yml ✓ (").append(dataset.size()).append(" samples)\n"); }
            // 4) sessions (sessions/*.yml) — reload memory from disk
            if (sessions != null && sessions.enabled()) {
                for (GHBot b : registry.all()) sessions.load(b);
                out.append("sessions ✓ (").append(registry.count()).append(" bot(s))\n");
            }
            // 5) command catalog (refresh from server command map)
            int n = commandLearning.refresh();
            out.append("command catalog ✓ (").append(n).append(" server commands)\n");
            log.info("GH-Bot reloaded: " + out.toString().replace("\n", "; "));
            return "§aGH-Bot reloaded:\n§f" + out.toString().replace("\n", "\n§f")
                    + "§7(Note: bot list / web port changes still need a restart.)";
        } catch (Throwable t) {
            log.error("Reload failed", t);
            return "§cReload failed: " + t.getMessage();
        }
    }

    private org.bukkit.Location baseToolLocation(GHBot bot) {
        if (Bukkit.getServer() == null || Bukkit.getWorlds().isEmpty()) return null;
        Object o = bot.memory().get("terrain.origin");
        if (o instanceof String s) {
            try {
                String[] p = s.split(",");
                if (p.length == 3) return new org.bukkit.Location(Bukkit.getWorlds().get(0),
                        Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
            } catch (NumberFormatException ignored) {}
        }
        return Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    private java.util.Map<String, String> webStats() {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("TPS", String.format("%.1f", sampler.stats().tps));
        m.put("CPU", String.format("%.1f%%", sampler.stats().cpuPercent));
        m.put("RAM", sampler.stats().usedMemMB + " / " + sampler.stats().maxMemMB + " MB");
        m.put("Players", String.valueOf(sampler.stats().players));
        m.put("Uptime", sampler.stats().uptimeSec + "s");
        m.put("Locations", String.valueOf(locations.all().size()));
        m.put("Sessions", String.valueOf(chatService.sessionCount()));   // v0.21.41
        return m;
    }

    private java.util.List<WebStatusServer.BotRow> webBots() {
        java.util.List<WebStatusServer.BotRow> rows = new java.util.ArrayList<>();
        for (GHBot b : registry.all()) {
            rows.add(new WebStatusServer.BotRow(b.id(), b.activity().name(), b.config().provider(),
                    b.config().mode(), b.debugLogging(), b.queue().size(), b.memory().size()));
        }
        return rows;
    }

    /** Register all commands on a (new) bot — used at enable and on runtime deploy. */
    private void registerBot(GHBot bot) {
        bot.setActivityLogger((b, msg) -> log.info("[" + b.id() + "] " + msg));
        bridge.registerBuiltins(bot);
        TerrainCommands.register(bot, bridge, log);
        BlockEditCommands.register(bot, bridge, editService);
        LocationCommands.register(bot, bridge, locations);
        AiCommands.register(bot, bridge, chatService);
        BuildCommands.register(bot, bridge, buildService, providers, ghostService, dataset, log);
        ReviewCommands.register(bot, bridge, ghostService);
        SchematicCommands.register(bot, bridge, schematics, log);
        DatasetCommands.register(bot, bridge, schematics, dataset, downloader, ghostService, log);
        PreviewCommands.register(bot, bridge, previews, schematics, cfg.webEnabled() ? cfg.webPort() : -1);
        EditCommands.register(bot, bridge, editService2, providers, log);
        CommandLearningCommands.register(bot, bridge, commandLearning);
        AdminCommands.register(bot, bridge, adminService);
        AvatarCommands.register(bot, bridge, avatarService, markerService);
        // null-guard: sessions is created before the first registerBot() in onEnable,
        // but keep this safe for any future code path that registers a bot earlier.
        if (sessions != null && sessions.enabled()) sessions.load(bot);
        markerService.restoreFromMemory(bot);
    }

    /** Phase 16 — toggles: image preview on/off (config-driven). */
    private void registerToggles() {
        var def = registry.defaultBot();
        if (def == null) return;
        var r = bridge.registryOf(def);
        r.register("image", (b, ctx) -> {
            if (ctx.args().length < 1 || !(ctx.args()[0].equalsIgnoreCase("on") || ctx.args()[0].equalsIgnoreCase("off"))) {
                ctx.sender().sendMessage("§7[" + b.id() + "] ai-image-preview: off (stretch feature, Phase 16 toggle). "
                        + b.id() + " image <on|off>");
                return;
            }
            boolean on = ctx.args()[0].equalsIgnoreCase("on");
            b.memory().put("image-preview", on);
            ctx.sender().sendMessage("§a[" + b.id() + "] ai-image-preview " + (on ? "on" : "off")
                    + " §7(concept image alongside review — requires a vision/image provider)");
        }, CommandRegistry.Meta.of("Toggle AI concept-image preview (stretch)", "image <on|off>"));
    }

    /** Phase 14 — deploy/undeploy/workers for parallel build crews. */
    private void registerCrewCommands() {
        var def = registry.defaultBot();
        if (def == null) return;
        var r = bridge.registryOf(def);
        r.register("deploy", (b, ctx) -> {
            if (ctx.args().length < 1) { ctx.sender().sendMessage("§eUsage: " + b.id() + " deploy <id> [role]"); return; }
            String id = ctx.args()[0];
            if (registry.bot(id) != null) { ctx.sender().sendMessage("§7Bot " + id + " already exists."); return; }
            dev.ghbot.config.BotConfig bc = new dev.ghbot.config.BotConfig();
            // copy defaults from default bot config
            bc.setId(id);
            bc.setRole(ctx.args().length >= 2 ? ctx.args()[1].toLowerCase() : "general");
            GHBot nb = new GHBot(id, bc);
            registry.add(nb);
            registerBot(nb);
            ctx.sender().sendMessage("§aDeployed §f" + id + "§a (role: " + bc.role()
                    + ") — @§f" + id + "§a now available. §7(Parallel crew member.)");
        }, CommandRegistry.Meta.of("Deploy a new worker bot at runtime", "deploy <id> [role]"));

        r.register("undeploy", (b, ctx) -> {
            if (ctx.args().length < 1) { ctx.sender().sendMessage("§eUsage: " + b.id() + " undeploy <id>"); return; }
            if (registry.resolve(ctx.args()[0]) == registry.defaultBot()) {
                ctx.sender().sendMessage("§cCan't undeploy the default bot.");
                return;
            }
            boolean ok = registry.remove(ctx.args()[0]);
            ctx.sender().sendMessage(ok ? "§aUndeployed " + ctx.args()[0] + "." : "§7No bot " + ctx.args()[0] + ".");
        }, CommandRegistry.Meta.of("Remove a worker bot", "undeploy <id>"));

        r.register("workers", (b, ctx) -> {
            StringBuilder sb = new StringBuilder("§e[" + b.id() + "] Workers (" + registry.count() + "):");
            for (GHBot w : registry.all()) {
                sb.append("\n§f- §a").append(w.id()).append("§7  role=").append(w.config().role())
                  .append(" · activity=").append(w.activity())
                  .append(" · provider=").append(w.config().provider());
            }
            ctx.sender().sendMessage(sb.toString());
        }, CommandRegistry.Meta.of("List all bots/workers and their roles", "workers"));
    }

    /** P16 — throttled capability notice to console (and in-game later). */
    private void maybeCapabilityNotice() {
        if (!cfg.capabilityNotices()) return;
        long now = System.currentTimeMillis();
        if (now - lastNotice < cfg.noticeThrottleSeconds() * 1000L) return;
        lastNotice = now;
        var rep = CapabilityEstimator.build(sampler.stats(), java.util.List.of());
        log.info(CapabilityEstimator.noticeLine(rep).replace("§7", ""));
    }

    @Override
    public void onDisable() {
        if (webServer != null) { webServer.stop(); webServer = null; }
        if (bridge != null) bridge.shutdown();
        if (sampler != null) {
            sampler.cancel();
            sampler = null;
        }
        if (sessions != null && sessions.enabled() && registry != null) {
            for (GHBot bot : registry.all()) sessions.save(bot);
            log.info("Sessions saved.");
        }
        log.info("GH-Bot disabled.");
    }

    /** Phase-0 verification: exercise config, registry, commands, logging, sessions. */
    private void selfTest() {
        boolean ok = true;
        StringBuilder out = new StringBuilder("§e[GH-Bot] Phase-0 self-test:");

        // 1) config + registry
        out.append("\n§f  bots: §a").append(registry.count())
           .append(" §7(default §f").append(registry.defaultId()).append("§7)");
        if (registry.count() < 1) ok = false;

        // 2) default bot resolves
        GHBot def = registry.defaultBot();
        if (def == null) { ok = false; out.append("\n§c  default bot: FAIL"); }
        else out.append("\n§f  default bot activity: §a").append(def.activity());

        // 3) command registry: built-ins present
        var names = bridge.registryOf(def).names();
        for (String need : new String[]{"help", "memory", "cap", "debuglog"}) {
            if (!names.contains(need)) { ok = false; out.append("\n§c  builtin ").append(need).append(": FAIL"); }
        }
        out.append("\n§f  builtins: §ahelp, memory, cap, debuglog §7(").append(names.size()).append(" cmds)");

        // 4) dispatch "help" through the bridge (no sender crash)
        try {
            bridge.dispatch(def, Bukkit.getConsoleSender(), "help", new String[0]);
            out.append("\n§f  /gh help dispatch: §aOK");
        } catch (Exception e) {
            ok = false;
            out.append("\n§c  /gh help dispatch: FAIL — ").append(e.getMessage());
        }

        // 5) WIB logger
        out.append("\n§f  WIB stamp: §a").append(WIBLogger.stamp());

        // 6) session store round-trip (if enabled)
        if (sessions.enabled()) {
            try {
                def.setDebugLogging(true);
                sessions.save(def);
                sessions.load(def);
                out.append("\n§f  session round-trip: §aOK");
            } catch (Exception e) {
                ok = false;
                out.append("\n§c  session round-trip: FAIL — ").append(e.getMessage());
            }
        }

        // Phase 1 — Core Brain: live stats + capability
        try {
            sampler.stats().sample();
            var rep = CapabilityEstimator.build(sampler.stats(), java.util.List.of());
            out.append("\n§f  stats: §aTPS ").append(String.format("%.1f", rep.tps()))
               .append(" · CPU ").append(String.format("%.1f%%", rep.cpuPercent()))
               .append(" · RAM ").append(rep.freeMemMB()).append(" MB free");
            out.append("\n§f  capability tier: §a").append(rep.tier())
               .append(" §7(").append(rep.tierName()).append(") · est. ")
               .append(rep.estMaxBuildBlocks()).append(" blocks/job · ")
               .append(rep.parallelBots()).append(" bots");
            if (rep.tier() < 1 || rep.estMaxBuildBlocks() <= 0) ok = false;
        } catch (Exception e) {
            ok = false;
            out.append("\n§c  stats/capability: FAIL — ").append(e.getMessage());
        }
        out.append("\n§").append(ok ? "a" : "c")
           .append(ok ? "  RESULT: PASS ✓" : "  RESULT: FAIL ✗");
        Bukkit.getConsoleSender().sendMessage(out.toString());
        log.info("Self-test " + (ok ? "PASS" : "FAIL"));
    }
}
