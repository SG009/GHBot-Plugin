package dev.ghbot.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/** Loads config.yml into typed objects. */
public class PluginConfig {

    private final List<BotConfig> bots = new ArrayList<>();
    private String defaultBot = "GH000";
    private boolean wibLog = true;
    private boolean chatLog = true;
    private final List<String> allowedPlayers = new ArrayList<>();
    private boolean sessionsPersist = true;
    private String sessionsDir = "sessions";
    private boolean selfTest = true;
    private int statsIntervalTicks = 20;
    private boolean capabilityNotices = true;
    private int noticeThrottleSeconds = 600;
    private int editBlocksPerTick = 200;
    private int editMaxRegion = 20_000;
    private int undoMaxSnapshots = 50;
    private boolean webEnabled = false;
    private int webPort = 8580;
    private String webBind = "0.0.0.0";
    private String webToken = "";   // v0.23.0 — Q3 login token; empty = mint WEB-######## each boot
    private boolean webLocalBypass = false;   // v0.27.0 — loopback-only login bypass (opt-in)
    private final AiConfig ai = new AiConfig();
    private double buildTpsPause = 16.0;
    private boolean autoSaveApproved = false;
    private int buildPauseTicks = 10;
    private boolean buildVerifyVision = false;   // v0.27.2 — opt-in vision auto-verify (default OFF)

    public static PluginConfig load(JavaPlugin plugin) {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        return loadFrom(plugin.getConfig());
    }

    /** Load from an already-built FileConfiguration (usable in tests/headless harness). */
    public static PluginConfig loadFrom(FileConfiguration c) {
        PluginConfig cfg = new PluginConfig();
        cfg.defaultBot = c.getString("bots.default-bot", "GH000");

        ConfigurationSection list = c.getConfigurationSection("bots.list");
        if (list != null) {
            for (String key : list.getKeys(false)) {
                ConfigurationSection s = list.getConfigurationSection(key);
                if (s == null) continue;
                cfg.bots.add(readBot(s));
            }
        }
        if (cfg.bots.isEmpty()) {
            cfg.bots.add(new BotConfig()); // fallback: single default GH000
        }

        cfg.wibLog = c.getBoolean("utils.wib-log", true);
        cfg.chatLog = c.getBoolean("utils.chat-log", true);
        cfg.allowedPlayers.addAll(c.getStringList("allowed-players"));
        cfg.sessionsPersist = c.getBoolean("sessions.persist", true);
        cfg.sessionsDir = c.getString("sessions.dir", "sessions");
        cfg.selfTest = c.getBoolean("self-test", true);
        cfg.statsIntervalTicks = c.getInt("core.stats-interval-ticks", 20);
        cfg.capabilityNotices = c.getBoolean("core.capability-notices", true);
        cfg.noticeThrottleSeconds = c.getInt("core.notice-throttle-seconds", 600);
        cfg.editBlocksPerTick = c.getInt("edit.blocks-per-tick", 200);
        cfg.editMaxRegion = c.getInt("edit.max-region", 20_000);
        cfg.undoMaxSnapshots = c.getInt("edit.undo-max-snapshots", 50);
        cfg.webEnabled = c.getBoolean("server.web.enabled", false);
        cfg.webPort = c.getInt("server.web.port", 8580);
        cfg.webBind = c.getString("server.web.bind", "0.0.0.0");
        cfg.webToken = c.getString("server.web.token", "");
        cfg.webLocalBypass = c.getBoolean("server.web.local-bypass", false);   // v0.27.0
        cfg.ai.load(c.getConfigurationSection("ai.providers"));
        cfg.buildTpsPause = c.getDouble("build.tps-pause-threshold", 16.0);
        cfg.buildPauseTicks = c.getInt("build.pause-ticks", 10);
        // v0.27.2 — read via the build SECTION so a hyphenated key cannot be
        // mistaken for a nested path (build.verify.vision).
        org.bukkit.configuration.ConfigurationSection buildSec = c.getConfigurationSection("build");
        cfg.buildVerifyVision = buildSec != null && buildSec.getBoolean("verify-vision", false);
        cfg.autoSaveApproved = c.getBoolean("schematic.auto-save-approved", false);
        return cfg;
    }

    private static BotConfig readBot(ConfigurationSection s) {
        BotConfig b = new BotConfig();
        b.setId(s.getString("id", b.id()));
        b.setProvider(s.getString("provider", b.provider()));
        b.setQueueSize(s.getInt("queue-size", b.queueSize()));
        b.setMaxBuildSize(s.getInt("max-build-size", b.maxBuildSize()));
        b.setMode(s.getString("mode", b.mode()));
        b.setAnimate(s.getBoolean("animate", b.animate()));
        b.setDebuglog(s.getBoolean("debuglog", b.debuglog()));
        b.setRole(s.getString("role", b.role()));
        ConfigurationSection av = s.getConfigurationSection("avatar");
        if (av != null) {
            b.avatar().setEnabled(av.getBoolean("enabled", b.avatar().enabled()));
            b.avatar().setType(av.getString("type", b.avatar().type()));
            b.avatar().setStay(av.getBoolean("stay", b.avatar().stay()));
        }
        return b;
    }

    public List<BotConfig> bots() { return bots; }
    public String defaultBot() { return defaultBot; }
    public boolean wibLog() { return wibLog; }
    public boolean chatLog() { return chatLog; }
    public List<String> allowedPlayers() { return allowedPlayers; }
    public boolean sessionsPersist() { return sessionsPersist; }
    public String sessionsDir() { return sessionsDir; }
    public boolean selfTest() { return selfTest; }
    public int statsIntervalTicks() { return Math.max(20, statsIntervalTicks); }
    public boolean capabilityNotices() { return capabilityNotices; }
    public int noticeThrottleSeconds() { return Math.max(60, noticeThrottleSeconds); }
    public int editBlocksPerTick() { return Math.max(1, editBlocksPerTick); }
    public int editMaxRegion() { return Math.max(100, editMaxRegion); }
    public int undoMaxSnapshots() { return Math.max(5, undoMaxSnapshots); }
    public boolean webEnabled() { return webEnabled; }
    public int webPort() { return webPort; }
    public String webBind() { return webBind; }
    public String webToken() { return webToken; }
    public boolean webLocalBypass() { return webLocalBypass; }   // v0.27.0
    public AiConfig ai() { return ai; }
    public double buildTpsPause() { return Math.max(10, buildTpsPause); }
    public int buildPauseTicks() { return Math.max(2, buildPauseTicks); }
    public boolean buildVerifyVision() { return buildVerifyVision; }   // v0.27.2
    public boolean autoSaveApproved() { return autoSaveApproved; }

    /** Phase 5 — AI provider config ("nothing default": everything optional). */
    public static class AiConfig {
        private boolean geminiEnabled = false;
        private String geminiModel = "gemini-2.5-flash";
        private String geminiApiKey = "";
        private String geminiBaseUrl = "https://generativelanguage.googleapis.com/";
        private boolean ollamaEnabled = false;
        private String ollamaModel = "qwen2.5:0.5b";
        private String ollamaBaseUrl = "http://localhost:11434";
        private boolean openaiEnabled = false;
        private String openaiModel = "gpt-4o-mini";
        private String openaiApiKey = "";
        private String openaiBaseUrl = "https://api.openai.com/v1";
        private final java.util.List<ExtraProvider> extraProviders = new java.util.ArrayList<>();
        private int chatMaxHistory = 20;
        private int compressBudgetChars = dev.ghbot.agent.TokenCompress.DEFAULT_BUDGET;   // v0.21.17 — 0 disables

        /** v0.21.16 — a generic OpenAI-compatible provider (Pollinations, Groq, Cerebras, Kiro-gateway, 9Router…). */
        public record ExtraProvider(String id, String name, String model, String apiKey, String baseUrl, boolean enabled) {}
        public java.util.List<ExtraProvider> extraProviders() { return java.util.Collections.unmodifiableList(extraProviders); }

        public boolean geminiEnabled() { return geminiEnabled; }
        public String geminiModel() { return geminiModel; }
        public String geminiApiKey() { return geminiApiKey; }
        public String geminiBaseUrl() { return geminiBaseUrl; }
        public boolean ollamaEnabled() { return ollamaEnabled; }
        public String ollamaModel() { return ollamaModel; }
        public String ollamaBaseUrl() { return ollamaBaseUrl; }
        public boolean openaiEnabled() { return openaiEnabled; }
        public String openaiModel() { return openaiModel; }
        public String openaiApiKey() { return openaiApiKey; }
        public String openaiBaseUrl() { return openaiBaseUrl; }
        public int chatMaxHistory() { return Math.max(4, chatMaxHistory); }
        public int compressBudgetChars() { return compressBudgetChars; }

        void load(org.bukkit.configuration.ConfigurationSection s) {
            if (s == null) return;
            geminiEnabled = s.getBoolean("gemini.enabled", false);
            geminiModel = s.getString("gemini.model", geminiModel);
            geminiApiKey = s.getString("gemini.api-key", "");
            geminiBaseUrl = s.getString("gemini.base-url", geminiBaseUrl);
            ollamaEnabled = s.getBoolean("ollama.enabled", false);
            ollamaModel = s.getString("ollama.model", ollamaModel);
            ollamaBaseUrl = s.getString("ollama.base-url", ollamaBaseUrl);
            openaiEnabled = s.getBoolean("openai.enabled", false);
            openaiModel = s.getString("openai.model", openaiModel);
            openaiApiKey = s.getString("openai.api-key", "");
            openaiBaseUrl = s.getString("openai.base-url", openaiBaseUrl);
            // extra OpenAI-compatible providers (any amount — free tiers, routers, gateways)
            var extras = s.getConfigurationSection("extra");
            extraProviders.clear();
            if (extras != null) {
                for (String id : extras.getKeys(false)) {
                    var es = extras.getConfigurationSection(id);
                    if (es == null) continue;
                    extraProviders.add(new ExtraProvider(
                            id,
                            es.getString("name", id),
                            es.getString("model", ""),
                            es.getString("api-key", ""),
                            es.getString("base-url", ""),
                            es.getBoolean("enabled", true)));
                }
            }
            chatMaxHistory = s.getInt("chat-max-history", 20);
            compressBudgetChars = s.getInt("compress-budget-chars", dev.ghbot.agent.TokenCompress.DEFAULT_BUDGET);
        }
    }
}
