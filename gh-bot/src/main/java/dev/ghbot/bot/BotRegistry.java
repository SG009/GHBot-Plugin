package dev.ghbot.bot;

import dev.ghbot.config.PluginConfig;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Registry of GH-bots — mirrors your old index.js activeBots + settings.json accounts. */
public class BotRegistry {

    private final Map<String, GHBot> bots = new LinkedHashMap<>();
    private final String defaultId;

    public BotRegistry(PluginConfig cfg) {
        String def = cfg.defaultBot();
        for (var bc : cfg.bots()) {
            bots.put(norm(bc.id()), new GHBot(bc.id(), bc));
        }
        if (!bots.containsKey(def) && !bots.isEmpty()) {
            def = bots.keySet().iterator().next();
        }
        this.defaultId = def;
    }

    /** Exact id (case-insensitive). */
    public GHBot bot(String id) { return id == null ? null : bots.get(norm(id)); }

    /** Resolve "@GH" / "@GH000" / bare name → bot. "@GH" alone = default bot. */
    public GHBot resolve(String name) {
        if (name == null || name.isBlank()) return defaultBot();
        String key = name.trim();
        if (key.startsWith("@")) key = key.substring(1);
        GHBot b = bots.get(norm(key));
        if (b != null) return b;
        // allow prefix match e.g. "@GH" → default bot
        if ("gh".equalsIgnoreCase(key) || key.isEmpty()) return defaultBot();
        return null;
    }

    public GHBot defaultBot() { return bots.get(norm(defaultId)); }

    /** Dynamically add a worker bot at runtime (Phase 14 — crews). */
    public synchronized void add(GHBot bot) {
        bots.put(norm(bot.id()), bot);
    }

    public synchronized boolean remove(String id) {
        GHBot removed = bots.remove(norm(id));
        return removed != null;
    }
    public String defaultId() { return defaultId; }
    public Collection<GHBot> all() { return bots.values(); }
    public int count() { return bots.size(); }

    private static String norm(String id) { return id == null ? "" : id.toLowerCase(); }
}
