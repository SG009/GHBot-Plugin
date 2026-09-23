package dev.ghbot.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * v0.26.0 — Phase E2 (owner-proposed): the fix knowledge base behind
 * `audit fix <n>`. Two layers, matched in order:
 *   1. **custom rules** from plugins/GHBot/audit-fixes.yml (hand-editable —
 *      the owner grows the KB as their server grows; they always win)
 *   2. **built-in rules** shipped in the jar (the floor — deleting the file
 *      never breaks anything)
 * A rule is {id, match-regex, fix-text}: the regex is searched
 * (case-insensitive by author's `(?i)` prefix) against the whole group's hay
 * (all messages + stack frames); the fix text may use %s for the group source.
 * Parsing is SnakeYAML (already on the classpath) and never throws.
 */
public final class FixRules {

    public record Rule(String id, String pattern, String fix) {}

    private final List<Rule> custom;
    private final boolean fromFile;

    private FixRules(List<Rule> custom, boolean fromFile) {
        this.custom = List.copyOf(custom);
        this.fromFile = fromFile;
    }

    /** Custom (file) rules, in file order. */
    public List<Rule> custom() { return custom; }
    /** Whether any custom rule actually parsed from the file. */
    public boolean hasFileRules() { return fromFile && !custom.isEmpty(); }
    public int builtinCount() { return BUILTINS.size(); }
    public int total() { return custom.size() + BUILTINS.size(); }
    /** True when this rule came from audit-fixes.yml (not a built-in). */
    public boolean isCustom(Rule r) { return r != null && custom.contains(r); }

    /** First matching rule — custom file rules FIRST (owner wins), then built-ins. */
    public Rule match(String hay) {
        if (hay == null || hay.isEmpty()) return null;
        for (Rule r : custom) if (find(r.pattern(), hay)) return r;
        for (Rule r : BUILTINS) if (find(r.pattern(), hay)) return r;
        return null;
    }

    private static boolean find(String pattern, String hay) {
        try {
            return java.util.regex.Pattern.compile(pattern).matcher(hay).find();
        } catch (Throwable t) {
            return false;   // a broken owner regex must never break the audit
        }
    }

    /** Render a rule's fix text for a concrete group source (%s → source name). */
    public static String render(Rule rule, String sourceName) {
        if (rule == null) return "";
        return rule.fix().replace("%s", sourceName == null ? "?" : sourceName).trim();
    }

    /**
     * Parse audit-fixes.yml content. Shape:
     *   rules:
     *     - id: release-behind
     *       match: "(?i)release\\(s\\) behind"
     *       fix: "swap the jar …"
     * Entries that aren't maps or lack match/fix are skipped silently; custom
     * rules are kept in file order. null/blank → built-ins only. Never throws.
     */
    @SuppressWarnings("unchecked")
    public static FixRules fromString(String yml) {
        List<Rule> out = new ArrayList<>();
        boolean loaded = false;
        try {
            if (yml != null && !yml.isBlank()) {
                Object root = new org.yaml.snakeyaml.Yaml().load(yml);
                if (root instanceof Map<?, ?> m) {
                    Object rules = m.get("rules");
                    if (rules instanceof List<?> list) {
                        loaded = true;
                        for (Object o : list) {
                            if (!(o instanceof Map)) continue;
                            Map<String, Object> e = (Map<String, Object>) o;
                            Object match = e.get("match"), fix = e.get("fix");
                            if (match == null || fix == null) continue;
                            String id = e.get("id") == null ? "rule-" + (out.size() + 1) : String.valueOf(e.get("id"));
                            String pat = String.valueOf(match), text = String.valueOf(fix);
                            if (pat.isBlank() || text.isBlank()) continue;
                            out.add(new Rule(id, pat, text));
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // malformed audit-fixes.yml → fall back to built-ins (logged by the caller)
        }
        return new FixRules(out, loaded);
    }

    /* ── built-in knowledge base (the floor — keep every fix practical and
     *    honest; %s = the group's source/plugin name) ── */
    public static final List<Rule> BUILTINS = List.of(
        new Rule("release-behind",
            "(?i)release\\(s\\) behind|newer (stable )?release|It is recommended that you update",
            "Paper is behind the newest stable release. Update path: stop the server → "
            + "grab the newest build for your MC version from https://papermc.io/downloads/paper → "
            + "swap the jar → start. The radar line in `audit` tracks the gap."),
        new Rule("plugin-update-available",
            "(?i)newer plugin version available|new %s update available|update available to support",
            "An update for %s exists. Check `audit updates` for the tracked latest, download it from "
            + "the plugin's official page, swap the jar in plugins/, restart. Prefer release builds "
            + "over SNAPSHOT/pre-release ones — those are tested on a copy first."),
        new Rule("class-def-not-found",
            "(?i)NoClassDefFoundError|ClassNotFoundException",
            "%s can't find a class — almost always a MISSING dependency plugin or a %s jar built for a "
            + "different MC version. Read the first stack trace for the class name: if it names another "
            + "plugin, install that dependency; otherwise reinstall the correct %s build."),
        new Rule("java-version",
            "(?i)UnsupportedClassVersionError|compiled by a more recent version of the Java Runtime",
            "%s was compiled for a NEWER Java than this server runs. Update the server's Java or get a "
            + "%s build for your Java version, then restart."),
        new Rule("api-mismatch",
            "(?i)NoSuchMethodError|NoSuchFieldError|AbstractMethodError",
            "%s called an API method this server build doesn't have — a version mismatch (%s built for "
            + "another MC/API). Update %s and/or the server so their versions line up."),
        new Rule("enable-failure",
            "(?i)Error occurred while enabling|Error occurred while disabling|Could not load|"
            + "Failed to (load|enable)|InvalidPluginException|InvalidDescriptionException",
            "%s failed to start cleanly. Find the FIRST stack trace directly above this error in "
            + "logs/latest.log — usually a bad config value or a missing dependency — fix that and restart."),
        new Rule("offline-mode",
            "(?i)OFFLINE/INSECURE MODE|no attempt to authenticate usernames|online-mode",
            "Offline-mode is common behind Geyser/Floodgate — but it means anyone can join as any name "
            + "if they can reach the Java port. Protect it: whitelist on, firewall the Java port from "
            + "the public internet, and don't set online-mode=true with Floodgate unless you know why."),
        new Rule("out-of-memory",
            "(?i)OutOfMemoryError|out of memory|GC overhead limit",
            "The server hit its RAM ceiling. On the 6 GB phone: keep -Xmx at/below ~2.5 GB (Android needs "
            + "the rest), lower view-distance/simulation-distance in server.properties, and keep big "
            + "scans/builds small."),
        new Rule("tick-lag",
            "(?i)Can't keep up|running \\d+ms.*behind|overloaded|Skipping Entity",
            "The server is tick-lagging. Use the bundled profiler (`spark tps`, `spark profiler`) to find "
            + "the hotspot, lower view/simulation distance, and trim entity/hopper counts."),
        new Rule("network",
            "(?i)connection reset|read timed out|connect timed out|SocketTimeoutException|UnknownHostException",
            "A remote connection failed — one-off = internet blip, ignore. Repeating = check the host's "
            + "DNS/firewall/proxy, or whether a plugin keeps calling a dead endpoint."),
        new Rule("tls",
            "(?i)SSLHandshakeException|PKIX path|SunCertPathBuilder|certificate|handshake_failure",
            "TLS/HTTPS failure — most often a wrong system clock or missing CA certificates on the host "
            + "(on Termux: `pkg install ca-certificates`). Fix the clock/certs, not the plugin."),
        new Rule("ai-provider-401",
            "(?i)HTTP 401|UNAUTHORIZED|invalid api[- ]key|API key is required",
            "The AI provider rejected GHBot's key. Set a valid api-key under ai: in "
            + "plugins/GHBot/config.yml (or switch provider with `provider list` / `provider set`), "
            + "then `provider list` to confirm."),
        new Rule("geyser-update",
            "(?i)new Geyser update available|Geyser.*update available",
            "Geyser tracks Bedrock versions tightly after a client wave. Wait for your Bedrock players "
            + "to need it, then update from https://geysermc.org/download (and floodgate together with it)."),
        new Rule("vault-no-provider",
            "(?i)no (economy|permission) (plugin|provider).{0,40}(found|registered)|Vault.{0,50}hook",
            "Vault found nothing to hook into. Install an economy / permission plugin that registers "
            + "with Vault (e.g. Essentials economy, LuckPerms) — Vault alone is just the bridge."),
        new Rule("unknown-command",
            "(?i)Unknown command",
            "That command doesn't exist on this server — the plugin may not be installed/enabled, or the "
            + "command name changed. `plugins` shows what's actually live."),
        new Rule("auth-servers",
            "(?i)Authentication servers|Failed to verify username|session.{0,30}(down|unreachable)",
            "Mojang's session servers were unreachable — usually a Mojang outage or host DNS issue; "
            + "retry later. Offline-mode (Floodgate) setups can usually ignore this for Geyser players."),
        new Rule("port-bind",
            "(?i)Failed to bind|Address already in use|BindException",
            "The port is taken — usually another server instance still running (or one that didn't shut "
            + "down fully). Stop the other instance, or change server-port in server.properties."),
        new Rule("world-corruption",
            "(?i)Failed to (read|load) chunk|RegionFile|corrupt|EOFException.{0,80}(region|chunk)",
            "Possible world-file damage. Stop the server BEFORE touching world files, then restore the "
            + "affected files from the newest GHBot admin backup."),
        new Rule("audit-selftest",
            "(?i)audit self-test warning|listener plumbing check",
            "Nothing to fix — this WARN is GHBot's own `audit selftest` plumbing check proving the "
            + "console listener is alive. Safe to ignore.")
    );
}
