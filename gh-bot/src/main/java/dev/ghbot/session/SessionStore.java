package dev.ghbot.session;

import dev.ghbot.bot.GHBot;
import dev.ghbot.log.WIBLogger;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * P5 — session persistence: save/restore each bot's state (activity,
 * debug flag, memory) across server restarts so nothing is lost.
 * Uses Bukkit's built-in YAML (no external deps — gson avoided).
 */
public class SessionStore {

    private final Path dir;
    private final boolean enabled;
    private final WIBLogger log;

    public SessionStore(Path dataFolder, boolean enabled, String subDir, WIBLogger log) {
        this.enabled = enabled;
        this.dir = dataFolder.resolve(subDir);
        this.log = log;
    }

    public boolean enabled() { return enabled; }

    /** Convert Bukkit YAML nested structures (ConfigurationSection) back into plain maps/lists. */
    @SuppressWarnings("unchecked")
    private static Object normalize(Object v) {
        if (v instanceof org.bukkit.configuration.ConfigurationSection cs) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            for (String k : cs.getKeys(false)) m.put(k, normalize(cs.get(k)));
            return m;
        }
        if (v instanceof java.util.Map<?, ?> mm) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            mm.forEach((k, val) -> m.put(String.valueOf(k), normalize(val)));
            return m;
        }
        if (v instanceof java.util.List<?> list) {
            java.util.List<Object> out = new java.util.ArrayList<>();
            for (Object o : list) out.add(normalize(o));
            return out;
        }
        return v;
    }

    public void save(GHBot bot) {
        if (!enabled) return;
        try {
            Files.createDirectories(dir);
            YamlConfiguration y = new YamlConfiguration();
            y.set("id", bot.id());
            y.set("activity", bot.activity().name());
            y.set("debugLogging", bot.debugLogging());
            // Store memory as a list of [key,value] pairs — avoids YAML dotted-key nesting entirely
            List<Object> mem = new ArrayList<>();
            for (Map.Entry<String, Object> e : bot.memory().entrySet()) {
                List<Object> pair = new ArrayList<>();
                pair.add(e.getKey());
                pair.add(e.getValue());
                mem.add(pair);
            }
            y.set("memory", mem);
            Files.writeString(dir.resolve(bot.id() + ".yml"),
                    y.saveToString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Could not save session for " + bot.id(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public void load(GHBot bot) {
        if (!enabled) return;
        Path f = dir.resolve(bot.id() + ".yml");
        if (!Files.exists(f)) return;
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.loadFromString(Files.readString(f, StandardCharsets.UTF_8));
            String act = y.getString("activity", "IDLE");
            try {
                bot.setActivity(GHBot.Activity.valueOf(act));
            } catch (IllegalArgumentException e) {
                bot.setActivity(GHBot.Activity.IDLE);
            }
            bot.setDebugLogging(y.getBoolean("debugLogging", false));
            Object mem = y.get("memory");
            if (mem instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof List<?> pair && pair.size() >= 2) {
                        bot.memory().put(String.valueOf(pair.get(0)), normalize(pair.get(1)));
                    }
                }
            } else if (mem instanceof Map<?, ?> m) {
                m.forEach((k, v) -> bot.memory().put(String.valueOf(k), normalize(v)));
            }
            log.info("Session restored for " + bot.id() + " (activity=" + bot.activity() + ")");
        } catch (IOException | InvalidConfigurationException e) {
            // Unreadable/corrupt session (e.g. an old file with a class tag that SnakeYAML
            // rejects). Back it up so it stops failing every boot, and start fresh.
            log.warn("Could not load session for " + bot.id() + " (" + e.getMessage() + ") — backing up and resetting.");
            try {
                Files.move(f, dir.resolve(bot.id() + ".bad-" + System.currentTimeMillis() + ".yml"));
            } catch (IOException e2) {
                log.error("Could not back up bad session for " + bot.id(), e2);
            }
            bot.setActivity(GHBot.Activity.IDLE);
            bot.clearMemory();
        }
    }
}
