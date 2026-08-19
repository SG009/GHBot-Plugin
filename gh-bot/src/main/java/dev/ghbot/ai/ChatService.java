package dev.ghbot.ai;

import dev.ghbot.bot.GHBot;
import dev.ghbot.log.WIBLogger;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chat & design sessions with per-bot-per-sender history.
 * All provider calls run on the bridge's async pool; replies are scheduled
 * back onto the main thread (safe to send to players).
 *
 * v0.21.41 — session eviction: sessions are capped at {@link #MAX_SESSIONS}
 * and stale sessions (> {@link #SESSION_TTL_MS} unused) are evicted to prevent
 * unbounded memory growth on long-running servers (especially the 6 GB phone).
 * Thread-safe via ConcurrentHashMap (chat can arrive on async events).
 */
public class ChatService {

    private final JavaPlugin plugin;
    private final ProviderRegistry providers;
    private final WIBLogger log;
    private final int maxHistory;
    private final int compressBudget;   // v0.21.17 — RTK-style tool-result compression budget (chars)

    // key = botId + "|" + senderName → session  (v0.21.41 — concurrent + eviction)
    private final Map<String, List<AIClient.ChatMessage>> sessions = new ConcurrentHashMap<>();
    private final Map<String, Long> sessionLastAccess = new ConcurrentHashMap<>();

    /** Max concurrent sessions before LRU eviction kicks in. */
    private static final int MAX_SESSIONS = 100;
    /** Sessions unused for this long (ms) are evicted on the next cleanup sweep. */
    private static final long SESSION_TTL_MS = 30 * 60 * 1000L;   // 30 minutes

    /** v0.21.40 — system prompt for image→JSON build spec (vision). */
    private static final String VISION_SYSTEM = """
            You are GH000, a veteran Minecraft builder. Look at the image the user
            uploaded and produce a JSON build spec that reconstructs the build shown,
            in EXACTLY this schema (no markdown, no code fence, reply with ONLY the JSON):
            {
              "name": "My Build",
              "palette": { "0": "minecraft:deepslate_bricks", "1": "minecraft:polished_deepslate", ... },
              "blocks": [ { "x": 0, "y": 0, "z": 0, "block": "0" }, ... ]
            }
            Rules: blocks are relative to the build origin; "block" can be a palette id
            or a full name; keep under ~2000 blocks; every block the build needs must be
            listed. If the image is not a building/structure, describe its main shape
            anyway in voxel blocks.""";

    /** v0.21.40 — send an image to a vision-capable provider and ask for a JSON build spec. */
    public String imageToSpec(String mimeType, byte[] imageBytes) throws Exception {
        java.util.List<String> tried = new ArrayList<>();
        for (AIClient c : providers.allConfigured()) {
            if (!c.supportsVision()) continue;
            tried.add(c.id());
            for (int attempt = 1; attempt <= 2; attempt++) {   // v0.21.43 — one retry per provider
                try {
                    String spec = c.chatWithImage(VISION_SYSTEM,
                            "Output the JSON build spec for the structure in this image.",
                            mimeType, imageBytes);
                    var result = dev.ghbot.builder.JsonBuildSpec.parseWithDiagnostics(spec);
                    if (result.isValid()) return spec;
                    log.warn("[GHBot] vision provider " + c.id() + " returned invalid spec ("
                            + (spec == null ? "null" : spec.length()) + " chars): "
                            + result.summary() + " — trying next");
                    break;   // got a reply; no point retrying this provider
                } catch (Exception e) {
                    String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    boolean transient5xx = c.id().equals("gemini")
                            && (msg.contains("503") || msg.contains("429") || msg.contains("UNAVAILABLE")
                                || msg.contains("RESOURCE_EXHAUSTED"));
                    if (attempt == 1 && transient5xx) {
                        // Gemini free tier: "high demand" spikes are usually brief — wait and retry once
                        log.warn("[GHBot] vision provider " + c.id() + " transient failure (" + msg
                                + ") — retrying in 5s");
                        try { Thread.sleep(5000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        continue;
                    }
                    log.warn("[GHBot] vision provider " + c.id() + " failed: " + msg);
                    break;   // permanent failure (or retry exhausted) — move to next provider
                }
            }
        }
        throw new RuntimeException("No vision-capable provider produced a valid build spec (tried: "
                + tried + "). Enable Gemini (2.5 flash supports images) or a multimodal Ollama model.");
    }

    private static final String SYSTEM = """
            You are GH000, a veteran Minecraft builder and server admin assistant.
            You are friendly, brief and practical. You help design builds and manage
            a Minecraft server. When asked to design, ask short clarifying questions
            (style, size, biome, vibe) one at a time, then propose a concrete design.
            Keep answers under 120 words. English only.
            You can also act as the server admin (Pillar J):
            - Edit server files with admin set: server.properties (motd, resource-pack…),
              bukkit.yml, spigot.yml, paper-global.yml — always backed up, validated,
              rollback-able. Note MOTD/resource-pack apply after a restart.
            - Run multi-step commands in ONE cmd call separated by ';' (e.g. setting up
              LuckPerms ranks: lp creategroup PRO; lp group PRO parent add default;
              lp group PRO meta addprefix 1000 "&#ffaa00[PRO]"). Each line is audited.
            - Dangerous commands (stop/reload/op/deop/ban/whitelist/rm -rf…) are blocked
              and mint a CONF-… token the user must confirm — never run them yourself.
            - You have a BROAD toolset: status, players, worlds, scan, find, look, plan,
              build, edit, schem, paste, set, replace, terraform, where, list-locations,
              save-location, workers, deploy, undeploy, marker, avatar, critique, cmd,
              admin (read/set/backup/restore/rollback/reload/menu), undo — use the right
              tool for the job instead of just talking.
            - For resource-pack prefix badges (e.g. NauticalRank): create ranks/prefixes
              in LuckPerms and set the pack URL in server.properties, but ASK the user
              for the exact symbol codes from the pack docs — never invent them.""";

    public ChatService(JavaPlugin plugin, ProviderRegistry providers, WIBLogger log, int maxHistory) {
        this(plugin, providers, log, maxHistory, dev.ghbot.agent.TokenCompress.DEFAULT_BUDGET);
    }

    public ChatService(JavaPlugin plugin, ProviderRegistry providers, WIBLogger log, int maxHistory, int compressBudget) {
        this.plugin = plugin;
        this.providers = providers;
        this.log = log;
        this.maxHistory = Math.max(4, maxHistory);
        this.compressBudget = Math.max(0, compressBudget);
    }

    /**
     * Streaming agent chat with tool calling (Phase 9b v2).
     * Streams chunks, executes any ⟦tool:…⟧ calls via the executor (up to 3
     * rounds), and returns the final reply. Marker lines are left in place;
     * the UI hides them.
     */
    public String streamChat(GHBot bot, String sessionKey, String text,
                             java.util.function.Consumer<String> onChunk,
                             dev.ghbot.agent.ToolExecutor tools) {
        String key = bot.id() + "|" + sessionKey;
        List<AIClient.ChatMessage> hist = getOrCreateSession(key);
        hist.add(new AIClient.ChatMessage("user", text));
        trim(hist);
        log.consoleLog("[" + bot.id() + "] session=" + sessionKey + " USER: " + text);

        // v0.21.10 — AUTO-TOOL: if the user's message is an imperative ("scan 100 at 86 86 262",
        // "find diamond_ore", "build a house", "status", "admin set motd ..."), run the tool
        // ON THE SERVER and feed the REAL result to the model. Never rely on the model to emit
        // ⟦tool:…⟧ — smaller/cloud models just talk (that was the "chatbot only" bug).
        if (tools != null) {
            dev.ghbot.agent.AutoTools.Call auto = dev.ghbot.agent.AutoTools.detect(text);
            if (auto != null) {
                try {
                    if (onChunk != null) onChunk.accept("⟦tool:" + auto.display() + "⟧\n");
                    String result = tools.run(new dev.ghbot.agent.ToolProtocol.ToolCall(auto.name(), auto.args()));
                    log.consoleLog("[" + bot.id() + "] AUTO-TOOL " + auto.display() + " → " + truncate(result));
                    var cResult = compressResult(result);
                    if (onChunk != null) onChunk.accept(cResult.text() + "\n");
                    hist.add(new AIClient.ChatMessage("assistant", "⟦tool:" + auto.display() + "⟧"));
                    hist.add(new AIClient.ChatMessage("user",
                            "[The server already ran the tool \"" + auto.display()
                            + "\" for you. Here is the REAL result:\n" + cResult.text()
                            + "\nSummarize it to the user based on this actual data. Do NOT claim you ran it yourself.]"));
                    trim(hist);
                    String reply = streamRun(bot, hist, onChunk);
                    hist.add(new AIClient.ChatMessage("assistant", reply));
                    trim(hist);
                    return reply;
                } catch (Throwable t) {
                    log.warn("Auto-tool " + auto.name() + " failed: " + t.getMessage());
                    // fall through to the normal model flow
                }
            }
        }

        StringBuilder finalReply = new StringBuilder();
        int rounds = 0;
        while (rounds++ < 3) {
            String reply = streamRun(bot, hist, onChunk);
            finalReply.append(reply);
            hist.add(new AIClient.ChatMessage("assistant", reply));
            trim(hist);
            // v0.21.30 — ONE tool per reply. If the model emitted multiple, run ONLY the first
            // and tell it (via history) to do one at a time — prevents tool-spam in one bubble.
            var calls = dev.ghbot.agent.ToolProtocol.allCalls(reply);
            if (calls.isEmpty() || tools == null) break;
            if (calls.size() > 1) {
                hist.add(new AIClient.ChatMessage("user",
                        "[You emitted " + calls.size() + " tool calls in one reply. I ran only the first. "
                        + "Do ONE tool action per reply, then wait for the user — never chain multiple ⟦tool:…⟧ in one message. "
                        + "If you need a sequence (e.g. deny then build), do them one at a time across replies.]"));
                trim(hist);
            }
            var call = calls.get(0);
            String result = tools.run(call);
            var cResult = compressResult(result);
            log.consoleLog("[" + bot.id() + "] TOOL " + call.name() + " " + String.join(" ", call.args())
                    + " → " + truncate(cResult.text()) + (cResult.originalLength() > cResult.text().length()
                        ? " (compressed −" + cResult.savedPercent() + "%)" : ""));
            hist.add(new AIClient.ChatMessage("user", cResult.text()));
            trim(hist);
        }
        return finalReply.toString();
    }

    /** v0.21.17 — RTK-style: compress long tool results before they enter AI context. */
    private dev.ghbot.agent.TokenCompress.Result compressResult(String result) {
        if (result == null) return new dev.ghbot.agent.TokenCompress.Result("", 0);
        int budget = compressBudget;
        if (budget <= 0 || result.length() <= budget) {
            return new dev.ghbot.agent.TokenCompress.Result(result, result.length());
        }
        var r = dev.ghbot.agent.TokenCompress.compress(result, budget);
        log.info("Token compression saved " + r.savedPercent() + "% on a tool result ("
                + r.originalLength() + " → " + r.text().length() + " chars)");
        return r;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 2000 ? s.substring(0, 2000) + "…" : s;
    }

    /** v0.21.9 — try providers in order (Gemini → Ollama → OpenAI → fallback) so a
     *  quota error (429) or outage auto-falls back instead of failing the chat. */
    private String streamRun(GHBot bot, List<AIClient.ChatMessage> hist,
                             java.util.function.Consumer<String> onChunk) {
        String sys = SYSTEM + "\n" + dev.ghbot.agent.ToolProtocol.helpText()
                + "\n" + dev.ghbot.ai.CapabilityGuide.text();   // v0.21.27 — full capability knowledge
        Exception last = null;
        for (AIClient c : ordered(bot)) {
            try {
                log.info("[" + bot.id() + "] chat streaming via " + c.id());
                String out = c.stream(sys, hist, onChunk);
                log.consoleLog("[" + bot.id() + "] AI " + c.id() + " REPLY: " + truncate(out));
                return out;
            } catch (Exception e) {
                last = e;
                log.warn("AI provider " + c.id() + " stream failed — trying next: " + e.getMessage());
            }
        }
        String err = "§cAll AI providers failed: " + (last == null ? "unknown" : last.getMessage());
        if (onChunk != null) onChunk.accept(err);
        return err;
    }

    /** Ordered provider candidates: memory override (if any) first, then all configured providers, then fallback. */
    private java.util.List<AIClient> ordered(GHBot bot) {
        java.util.List<AIClient> out = new java.util.ArrayList<>();
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        Object want = bot.memory().get("provider");
        if (want instanceof String s && !s.isBlank() && !s.equalsIgnoreCase("auto")) ids.add(s.toLowerCase());
        for (AIClient c : providers.allConfigured()) ids.add(c.id());
        for (String id : ids) {
            AIClient c = providers.get(id);
            if (c != null && c.isConfigured()) out.add(c);
        }
        AIClient fb = providers.get("fallback");
        if (fb != null) out.add(fb);
        return out;
    }

    /** Sync chat — returns the reply text (used by the web chat panel). */
    public String chatSync(GHBot bot, String text) {
        String key = bot.id() + "|web";
        List<AIClient.ChatMessage> hist = getOrCreateSession(key);
        hist.add(new AIClient.ChatMessage("user", text));
        trim(hist);
        log.consoleLog("[" + bot.id() + "] session=web USER: " + text);
        String reply = runCatch(bot, hist);
        hist.add(new AIClient.ChatMessage("assistant", reply));
        trim(hist);
        return reply;
    }

    /** Free chat with the bot (keeps conversation memory). */
    public void chat(GHBot bot, CommandSender sender, String text) {
        String key = bot.id() + "|" + sender.getName();
        List<AIClient.ChatMessage> hist = getOrCreateSession(key);
        hist.add(new AIClient.ChatMessage("user", text));
        trim(hist);

        log.info("[" + bot.id() + "] chat from " + sender.getName());
        log.consoleLog("[" + bot.id() + "] IN-GAME " + sender.getName() + " USER: " + text);
        String reply = runCatch(bot, hist);
        hist.add(new AIClient.ChatMessage("assistant", reply));
        trim(hist);
        send(sender, reply);
    }

    /** Guided design conversation: stores a topic; later messages continue it. */
    public void design(GHBot bot, CommandSender sender, String[] args) {
        String key = bot.id() + "|" + sender.getName() + "|design";
        List<AIClient.ChatMessage> hist = getOrCreateSession(key);
        String input = String.join(" ", args).trim();

        if (input.equalsIgnoreCase("done") || input.equalsIgnoreCase("summary")) {
            // dump the brief so far
            StringBuilder sb = new StringBuilder("§e[" + bot.id() + "] Design brief so far:");
            for (AIClient.ChatMessage m : hist) {
                sb.append("\n§7").append(m.role()).append(": §f").append(shorten(m.content(), 120));
            }
            send(sender, sb.toString());
            return;
        }

        String designSystem = SYSTEM + """
            
            You are in a design session. Ask ONE clarifying question at a time
            (style, size, biome, purpose, palette). When you have enough info, output
            a short DesignSpec-like summary: name, style, palette, size, key features.""";
        String userLine = hist.isEmpty()
                ? "I want to design: " + input
                : input;
        hist.add(new AIClient.ChatMessage("user", userLine));
        trim(hist);

        log.info("[" + bot.id() + "] design from " + sender.getName());
        String reply = runWithCatch(bot, designSystem, hist);
        hist.add(new AIClient.ChatMessage("assistant", reply));
        trim(hist);
        send(sender, reply);
    }

    /** v0.21.9 — fallback chain for sync chat/design calls. */
    private String runCatch(GHBot bot, List<AIClient.ChatMessage> hist) {
        return runWithCatch(bot, fullSystem(), hist);
    }

    private String fullSystem() {
        return SYSTEM + "\n" + dev.ghbot.agent.ToolProtocol.helpText()
                + "\n" + dev.ghbot.ai.CapabilityGuide.text();
    }

    private String runWithCatch(GHBot bot, String system, List<AIClient.ChatMessage> hist) {
        Exception last = null;
        for (AIClient c : ordered(bot)) {
            try {
                log.info("[" + bot.id() + "] chat via " + c.id());
                String out = c.chat(system, hist);
                log.consoleLog("[" + bot.id() + "] AI " + c.id() + " REPLY: " + truncate(out));
                return out;
            } catch (Exception e) {
                last = e;
                log.warn("AI provider " + c.id() + " failed — trying next: " + e.getMessage());
            }
        }
        return "§cAll AI providers failed: " + (last == null ? "unknown" : last.getMessage());
    }

    private String run(AIClient client, GHBot bot, List<AIClient.ChatMessage> hist) {
        return runWith(client, SYSTEM, hist);
    }

    private String runWith(AIClient client, String system, List<AIClient.ChatMessage> hist) {
        try {
            return client.chat(system, hist);
        } catch (Exception e) {
            log.error("AI provider " + client.id() + " failed", e);
            return "§cAI provider " + client.displayName() + " failed: " + e.getMessage()
                    + "\n§7Check your provider config (@GH000 provider list).";
        }
    }

    private void trim(List<AIClient.ChatMessage> hist) {
        while (hist.size() > maxHistory) hist.remove(0);
    }

    /** v0.21.41 — get-or-create a session list, recording last-access time. */
    private List<AIClient.ChatMessage> getOrCreateSession(String key) {
        sessionLastAccess.put(key, System.currentTimeMillis());
        List<AIClient.ChatMessage> list = sessions.get(key);
        if (list != null) return list;
        // computeIfAbsent on ConcurrentHashMap is atomic per key
        list = sessions.computeIfAbsent(key, k -> new ArrayList<>());
        evictIfNeeded();
        return list;
    }

    /**
     * v0.21.41 — evict stale / excess sessions.
     * Called automatically after session creation; can also be called periodically
     * by the plugin (e.g. every 5 minutes) for thorough cleanup.
     */
    public void evictStaleSessions() {
        long now = System.currentTimeMillis();
        // 1) Remove sessions that haven't been accessed within the TTL
        List<String> stale = new ArrayList<>();
        sessionLastAccess.forEach((key, ts) -> {
            if (now - ts > SESSION_TTL_MS) stale.add(key);
        });
        for (String key : stale) {
            sessions.remove(key);
            sessionLastAccess.remove(key);
        }
        if (!stale.isEmpty()) {
            log.info("[GHBot] evicted " + stale.size() + " stale chat session(s) (>"
                    + (SESSION_TTL_MS / 60_000) + " min idle). " + sessions.size() + " active.");
        }
        // 2) If still over the cap, evict least-recently-used until under
        if (sessions.size() > MAX_SESSIONS) {
            List<Map.Entry<String, Long>> byAge = new ArrayList<>(sessionLastAccess.entrySet());
            byAge.sort(Map.Entry.comparingByValue());   // oldest first
            int toRemove = sessions.size() - MAX_SESSIONS;
            for (int i = 0; i < toRemove && i < byAge.size(); i++) {
                String key = byAge.get(i).getKey();
                sessions.remove(key);
                sessionLastAccess.remove(key);
            }
            if (toRemove > 0) {
                log.info("[GHBot] LRU-evicted " + Math.min(toRemove, byAge.size())
                        + " chat session(s) (cap=" + MAX_SESSIONS + "). " + sessions.size() + " active.");
            }
        }
    }

    /** v0.21.41 — evict if over cap (called on every new session creation). */
    private void evictIfNeeded() {
        if (sessions.size() <= MAX_SESSIONS) return;
        // Only do the full sweep occasionally (not every single access)
        if (sessions.size() <= MAX_SESSIONS + 10) return;
        evictStaleSessions();
    }

    /** v0.21.41 — current number of active chat sessions (for /status or debug). */
    public int sessionCount() { return sessions.size(); }

    private void send(CommandSender sender, String text) {
        Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(text));
    }

    private static String shorten(String s, int n) {
        return s == null ? "" : (s.length() > n ? s.substring(0, n) + "…" : s);
    }
}
