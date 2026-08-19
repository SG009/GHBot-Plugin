package dev.ghbot.admin;

import dev.ghbot.log.WIBLogger;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Phase 11b — Admin Operations (Pillar J). Safe config editing with
 * backups + validation + audit, DeluxeMenus menu creation, plugin reloads.
 *
 * v0.21 hardening (per your edge-case list):
 *  - TWO-STAGE YAML check: parse the edited doc in memory, then re-parse the
 *    exact bytes we are about to write. Any syntax error → refuse the write
 *    and keep the file untouched.
 *  - Every set() mints an audit token (ADM-…), logs it to logs/admin.log,
 *    and records file+backup in an index so `/gh admin rollback <token>`
 *    (or rollback with no token = last edit) restores the pre-edit file.
 *  - Reload guard: plugin reloads are followed by a 2s health-check; if the
 *    plugin went enabled → disabled, the last admin edit is auto-rolled back.
 *
 * v0.21.3 (Pillar J, generalized):
 *  - SERVER-ROOT FILES: a whitelist of server files is editable with the same
 *    safety — `server.properties` (motd, resource-pack…), `bukkit.yml`,
 *    `spigot.yml`, `paper-global.yml`, `paper-world-defaults.yml`. So GH-bot
 *    can handle "change the motd", "set the resource-pack URL", etc. — not
 *    just plugin configs. DeluxeMenus was never the limit; it was the example.
 *  - `.properties` files get format-aware editing (line-targeted, comments +
 *    order preserved, validated by a Properties round-trip) — they are NOT YAML.
 */
public class AdminService {

    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Server-root files GH-bot may edit (everything else stays plugin-scoped). */
    public static final java.util.Set<String> SERVER_FILES = java.util.Set.of(
            "server.properties", "bukkit.yml", "spigot.yml",
            "paper-global.yml", "paper-world-defaults.yml");

    /** Common server.properties keys — `admin set motd <value>` is a shortcut for these. */
    public static final java.util.Set<String> SERVER_PROPERTY_KEYS = java.util.Set.of(
            "motd", "resource-pack", "online-mode", "max-players", "view-distance",
            "simulation-distance", "spawn-protection", "pvp", "difficulty", "gamemode",
            "level-name", "level-seed", "white-list", "enforce-whitelist",
            "enable-command-block", "hardcore", "allow-nether", "allow-flight",
            "max-tick-time", "network-compression-threshold", "max-world-size",
            "force-gamemode", "player-idle-timeout", "op-permission-level",
            "function-permission-level", "rate-limit", "enable-status",
            "hide-online-players", "sync-chunk-writes", "enable-jmx-monitoring",
            "enable-query", "prevent-proxy-connections", "use-native-transport",
            "generate-structures", "max-build-height", "enable-rcon", "rcon-port",
            "rcon-password", "broadcast-console-to-ops", "broadcast-rcon-to-ops",
            "snooper-enabled", "require-resource-pack");

    private final Path pluginsDir;
    private final Path serverDir;              // parent of plugins/ = server root
    private final WIBLogger log;
    private final Path backupsDir;
    private final Path indexFile;
    private final JavaPlugin owner;                 // nullable — needed for reload health-check scheduling

    /** token -> [file, backupFileName] of every admin set() since plugin load (persisted). */
    private final Map<String, String[]> tokenIndex = new LinkedHashMap<>();

    /** @param pluginsDir the server's plugins/ directory (parent of the plugin's data folder). */
    public AdminService(Path pluginsDir, WIBLogger log, Path dataFolder) {
        this(pluginsDir, log, dataFolder, null);
    }

    public AdminService(Path pluginsDir, WIBLogger log, Path dataFolder, JavaPlugin owner) {
        this.pluginsDir = pluginsDir.toAbsolutePath();
        this.serverDir = detectServerRoot(this.pluginsDir);
        this.log = log;
        this.owner = owner;
        this.backupsDir = dataFolder.resolve("admin-backups");
        this.indexFile = dataFolder.resolve("admin-actions.yml");
        loadIndex();
    }

    /** v0.21.15 — find the REAL server root (where server.properties lives).
     *  Your world is at worlds/Forest, so Bukkit.getWorldContainer() = .../worlds and its
     *  PARENT is NOT the server root. Instead: walk UP from the world container (and from
     *  the plugins dir) looking for the server.properties marker — that's the real root. */
    private static Path detectServerRoot(Path pluginsDir) {
        // 1) walk up from the world container until we find server.properties
        try {
            if (Bukkit.getServer() != null && Bukkit.getWorldContainer() != null) {
                Path cur = Bukkit.getWorldContainer().toPath();
                for (int i = 0; i < 6 && cur != null; i++) {
                    if (Files.exists(cur.resolve("server.properties"))) return cur;
                    cur = cur.getParent();
                }
            }
        } catch (Throwable ignored) {}
        // 2) walk up from the plugins dir
        try {
            Path cur = pluginsDir;
            for (int i = 0; i < 8 && cur != null; i++) {
                if (Files.exists(cur.resolve("server.properties"))) return cur;
                cur = cur.getParent();
            }
        } catch (Throwable ignored) {}
        // 3) v0.21.31 — the server's CWD (where server.properties actually lives on most setups)
        try {
            Path cwd = Path.of("server.properties").toAbsolutePath();
            if (Files.exists(cwd)) return cwd.getParent();
        } catch (Throwable ignored) {}
        return pluginsDir.getParent() == null ? pluginsDir : pluginsDir.getParent();
    }

    /**
     * Resolve any editable path: a whitelisted server-root file (by basename)
     * OR a plugin config file (e.g. "DeluxeMenus/menus/shop.yml"). Everything
     * else is rejected — no absolute paths, no escaping.
     */
    public Path resolveFile(String file) throws IOException {
        Path p = Path.of(file);
        if (p.isAbsolute()) throw new IOException("Absolute paths not allowed.");
        String base = p.getFileName() == null ? "" : p.getFileName().toString();
        if (SERVER_FILES.contains(base)) {
            Path sp = serverDir.resolve(base).toAbsolutePath().normalize();
            if (!sp.startsWith(serverDir)) throw new IOException("Path escapes server dir.");
            return sp;
        }
        Path resolved = pluginsDir.resolve(p).normalize();
        if (!resolved.startsWith(pluginsDir)) throw new IOException("Path escapes plugins dir.");
        return resolved;
    }

    public String read(String file) throws IOException {
        Path p = resolveFile(file);
        if (!Files.exists(p)) throw new IOException("No such file: " + file);
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /** Back up a file (or no-op if absent) → backups/<sanitized>-<ts>-<rand>.yml. */
    public Path backup(String file) throws IOException {
        Path p = resolveFile(file);
        if (!Files.exists(p)) return null;
        Files.createDirectories(backupsDir);
        String safe = file.replaceAll("[^A-Za-z0-9_./-]", "_").replace('/', '_');
        // random suffix so two backups in the same second never collide
        Path bak = backupsDir.resolve(safe + "-" + LocalDateTime.now(WIB).format(TS)
                + "-" + ThreadLocalRandom.current().nextInt(1000, 10000) + ".yml");
        Files.copy(p, bak);
        return bak;
    }

    /**
     * Set a dot-path key in a YAML file, or a key=value in a .properties file,
     * with backup + validation + audit token. Returns the backup made (or null).
     * Rollback-able via {@link #rollback(String)} / {@link #rollbackLast()}.
     */
    public Path set(String file, String key, String value, CommandSender who) throws IOException {
        value = cleanValue(value);
        if (file.toLowerCase().endsWith(".properties")) return setProperties(file, key, value, who);
        // v0.21.9 — don't create junk files (e.g. `admin set motd ...` missing the file arg)
        Path chk = resolveFile(file);
        String base = chk.getFileName() == null ? "" : chk.getFileName().toString().toLowerCase();
        if (!Files.exists(chk) && !SERVER_FILES.contains(base)
                && !base.matches(".*\\.(yml|yaml)$")) {
            throw new IOException("No such config file \"" + file + "\" — usage: admin set <file> <key> <value>, "
                    + "or admin set <server-property> <value> (e.g. admin set motd Welcome! Have fun!)");
        }
        Path p = resolveFile(file);
        Path bak = backup(file);

        // STAGE 1: parse the CURRENT file — a config that's already
        // broken must not be silently overwritten.
        YamlConfiguration y = new YamlConfiguration();
        if (Files.exists(p)) {
            try {
                y.loadFromString(Files.readString(p, StandardCharsets.UTF_8));
            } catch (InvalidConfigurationException e) {
                throw new IOException("Refusing to touch " + file + " — existing YAML is invalid: " + e.getMessage());
            }
        }
        Object v = coerce(value);
        y.set(key, v);

        // STAGE 2: round-trip the EXACT bytes we are about to write. If they
        // don't parse cleanly (bad quoting/escaping), abort before touching disk.
        String out = y.saveToString();
        try {
            YamlConfiguration check = new YamlConfiguration();
            check.loadFromString(out);
        } catch (InvalidConfigurationException e) {
            throw new IOException("Refusing to write invalid YAML for " + file + ": " + e.getMessage());
        }

        Files.writeString(p, out, StandardCharsets.UTF_8);
        mintToken(file, bak, who, "set " + file + " " + key + "=" + value);
        return bak;
    }

    /**
     * Format-aware edit for Java .properties files (e.g. server.properties):
     * target the exact `key=` line, preserve comments + ordering + other keys,
     * and validate with a Properties round-trip before writing.
     */
    public Path setProperties(String file, String key, String value, CommandSender who) throws IOException {
        Path p = resolveFile(file);
        Path bak = backup(file);

        // STAGE 1: current file must parse
        if (Files.exists(p)) {
            try {
                java.util.Properties chk = new java.util.Properties();
                chk.load(Files.newBufferedReader(p, StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new IOException("Refusing to touch " + file + " — existing properties invalid: " + e.getMessage());
            }
        }
        List<String> lines = Files.exists(p)
                ? Files.readAllLines(p, StandardCharsets.UTF_8)
                : new ArrayList<>();
        String prefix = key + "=";
        boolean found = false;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.trim().startsWith(prefix) || l.trim().startsWith(key + ":")) {
                lines.set(i, prefix + propEscape(value));
                found = true;
                break;
            }
        }
        if (!found) lines.add(prefix + propEscape(value));
        String out = String.join("\n", lines) + "\n";

        // STAGE 2: validate the exact bytes we're about to write
        java.util.Properties check = new java.util.Properties();
        check.load(new java.io.StringReader(out));
        if (!check.containsKey(key)) {
            throw new IOException("Refusing to write — validation failed for " + file);
        }

        Files.writeString(p, out, StandardCharsets.UTF_8);
        mintToken(file, bak, who, "set " + file + " " + key + "=" + value);
        return bak;
    }

    /** Mint the ADM-… token, index it, audit-log it. */
    private void mintToken(String file, Path bak, CommandSender who, String auditLine) {
        String token = "ADM-" + LocalDateTime.now(WIB).format(TS) + "-" + ThreadLocalRandom.current().nextInt(1000, 10000);
        tokenIndex.put(token, new String[]{file, bak == null ? "" : bak.getFileName().toString()});
        saveIndex();
        log.adminLog(auditLine + " by " + who.getName()
                + (bak != null ? " (backup " + bak.getFileName() + ")" : "")
                + " [token=" + token + "]");
    }

    /** Token of the most recent set() (for display / scripting). */
    public String lastToken() {
        return tokenIndex.isEmpty() ? null : new ArrayList<>(tokenIndex.keySet()).get(tokenIndex.size() - 1);
    }

    /** Restore a file from its most recent backup (manual rollback). */
    public Path restore(String file) throws IOException {
        Path p = resolveFile(file);
        String safe = file.replaceAll("[^A-Za-z0-9_./-]", "_").replace('/', '_');
        try (var stream = Files.list(backupsDir)) {
            java.util.List<Path> candidates = new ArrayList<>();
            stream.filter(f -> f.getFileName().toString().startsWith(safe + "-")).forEach(candidates::add);
            Path latest = null;
            long newest = Long.MIN_VALUE;
            for (Path f : candidates) {
                long mt = Files.getLastModifiedTime(f).toMillis();
                if (mt > newest) { newest = mt; latest = f; }
            }
            if (latest == null) throw new IOException("No backup for " + file);
            Files.copy(latest, p, StandardCopyOption.REPLACE_EXISTING);
            log.adminLog("restored " + file + " from " + latest.getFileName());
            return latest;
        }
    }

    /** Roll back the most recent admin set(). Returns a description. */
    public String rollbackLast() throws IOException {
        String token = lastToken();
        if (token == null) throw new IOException("No recent admin edits to roll back.");
        return rollback(token);
    }

    /**
     * Roll back a specific admin action by its audit token
     * (`/gh admin rollback <token>`). Restores the file from the recorded backup.
     */
    public String rollback(String token) throws IOException {
        String[] rec = tokenIndex.get(token);
        if (rec == null) throw new IOException("No admin action with token " + token + " (check logs/admin.log).");
        String file = rec[0];
        Path p = resolveFile(file);
        Path bak = rec[1].isEmpty() ? null : backupsDir.resolve(rec[1]);
        if (bak == null || !Files.exists(bak)) throw new IOException("Backup missing for " + file + " — cannot roll back.");
        Files.copy(bak, p, StandardCopyOption.REPLACE_EXISTING);
        tokenIndex.remove(token);
        saveIndex();
        log.adminLog("rollback " + token + " → restored " + file + " from " + bak.getFileName());
        return file + " restored from " + bak.getFileName();
    }

    /** Create a DeluxeMenus menu file (if the plugin folder exists). */
    public Path createMenu(String name, String title) throws IOException {
        Path menusDir = pluginsDir.resolve("DeluxeMenus").resolve("menus");
        Files.createDirectories(menusDir);
        String safe = name.replaceAll("[^A-Za-z0-9_-]", "_");
        Path f = menusDir.resolve(safe + ".yml");
        if (Files.exists(f)) throw new IOException("Menu already exists: " + f.getFileName());

        String t = title == null || title.isBlank() ? "&8GH-Bot Menu" : title;
        YamlConfiguration y = new YamlConfiguration();
        y.set("menu-title", t);
        y.set("open-command", safe);
        y.set("size", 9);
        ConfigurationSection items = y.createSection("items");
        ConfigurationSection fill = items.createSection("fill");
        fill.set("material", "GRAY_STAINED_GLASS_PANE");
        fill.set("slot", -1);
        fill.set("display_name", " ");
        ConfigurationSection close = items.createSection("close");
        close.set("material", "BARRIER");
        close.set("slot", 8);
        close.set("display_name", "&cClose");
        close.set("left_click_commands", java.util.List.of("[close]"));
        Files.writeString(f, y.saveToString(), StandardCharsets.UTF_8);
        log.adminLog("created DeluxeMenus menu " + safe + ".yml (" + t + ")");
        return f;
    }

    /** Result of a guarded reload. */
    public record ReloadResult(boolean dispatched, boolean guarded, String pluginName, String message) {}

    /**
     * Dispatch a plugin/server reload (audited) with a health-check guard:
     * 2s later, if a named plugin was enabled before and is now disabled,
     * the last admin edit is auto-rolled back (backup restore) and logged.
     */
    public ReloadResult reload(String pluginName, CommandSender who) {
        final Plugin target;
        final boolean wasEnabled;
        if (pluginName != null && !pluginName.isBlank()) {
            Plugin t = Bukkit.getPluginManager().getPlugin(pluginName);
            target = t;
            wasEnabled = t != null && t.isEnabled();
        } else {
            target = null;
            wasEnabled = false;
        }
        String cmd = (pluginName == null || pluginName.isBlank()) ? "reload" : pluginName + ":reload";
        boolean ok = false;
        try {
            ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        } catch (Throwable t) {
            ok = false;
        }
        log.adminLog("reload " + (pluginName == null ? "server" : pluginName) + " by "
                + who.getName() + " → " + (ok ? "ok" : "FAIL"));
        if (!ok) return new ReloadResult(false, false, pluginName, "Reload command failed to dispatch — see console.");

        if (target != null && owner != null) {
            try {
                owner.getServer().getScheduler().runTaskLater(owner, () -> {
                    boolean nowEnabled = target.isEnabled();
                    if (wasEnabled && !nowEnabled) {
                        log.adminLog("rollback triggered: " + pluginName + " failed to reload (enabled → disabled)");
                        try {
                            String what = rollbackLast();
                            log.adminLog("auto-rolled back: " + what);
                        } catch (Exception e) {
                            log.adminLog("auto-rollback failed: " + e.getMessage());
                        }
                    }
                }, 40L);
            } catch (Throwable t) {
                // scheduler unavailable (headless) — skip guard silently
            }
            return new ReloadResult(true, true, pluginName,
                    "Reload sent; health-check in 2s — auto-rollback if " + pluginName + " fails to come back.");
        }
        return new ReloadResult(true, false, pluginName, "Reload command sent.");
    }

    // ------------------------------------------------------------------ index

    private void loadIndex() {
        if (!Files.exists(indexFile)) return;
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.loadFromString(Files.readString(indexFile, StandardCharsets.UTF_8));
            var sec = y.getConfigurationSection("actions");
            if (sec == null) return;
            for (String token : sec.getKeys(false)) {
                String file = y.getString("actions." + token + ".file", "");
                String bak = y.getString("actions." + token + ".backup", "");
                if (!file.isEmpty()) tokenIndex.put(token, new String[]{file, bak});
            }
        } catch (Exception e) {
            log.warn("Could not load admin action index: " + e.getMessage());
        }
    }

    private void saveIndex() {
        try {
            Files.createDirectories(indexFile.getParent());
            YamlConfiguration y = new YamlConfiguration();
            for (Map.Entry<String, String[]> e : tokenIndex.entrySet()) {
                y.set("actions." + e.getKey() + ".file", e.getValue()[0]);
                y.set("actions." + e.getKey() + ".backup", e.getValue()[1]);
            }
            Files.writeString(indexFile, y.saveToString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Could not save admin action index: " + e.getMessage());
        }
    }

    public List<String> tokens() { return new ArrayList<>(tokenIndex.keySet()); }

    /** Escape a value for a .properties file (backslashes, whitespace, leading #/!/space). */
    private static String cleanValue(String v) {
        if (v == null) return null;
        String t = v.trim();
        // strip wrapping quotes users type in chat: "Welcome! Have fun!" → Welcome! Have fun!
        if (t.length() >= 2 && ((t.startsWith("\"") && t.endsWith("\""))
                || (t.startsWith("'") && t.endsWith("'")))) {
            t = t.substring(1, t.length() - 1).trim();
        }
        return t;
    }

    /** Escape a value for a .properties file (backslashes, whitespace, leading #/!/space). */
    private static String propEscape(String v) {
        StringBuilder sb = new StringBuilder();
        for (char c : v.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        if (!sb.isEmpty() && (sb.charAt(0) == '!' || sb.charAt(0) == '#' || sb.charAt(0) == ' ')) {
            sb.insert(0, '\\');
        }
        return sb.toString();
    }

    private static Object coerce(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.equalsIgnoreCase("true")) return true;
        if (t.equalsIgnoreCase("false")) return false;
        try { if (t.matches("-?\\d+")) return Integer.parseInt(t); } catch (NumberFormatException ignored) {}
        try { if (t.matches("-?\\d+(\\.\\d+)?")) return Double.parseDouble(t); } catch (NumberFormatException ignored) {}
        return t;
    }
}
