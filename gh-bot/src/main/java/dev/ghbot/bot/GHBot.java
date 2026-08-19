package dev.ghbot.bot;

import dev.ghbot.config.BotConfig;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/** A single GH-bot instance (GH000, GH001, ...). Phase 0: identity + state machine. */
public class GHBot {

    /** Adapted from your old core.js ActivityStates. */
    public enum Activity {
        IDLE, CHAT, SCANNING, DESIGNING, BUILDING, EDITING, WAITING_APPROVAL, PAUSED
    }

    private final String id;
    private final BotConfig config;
    private Activity activity = Activity.IDLE;
    private boolean debugLogging;
    private final Map<String, Object> memory = new HashMap<>();
    private final Deque<String> queue = new ArrayDeque<>();
    private java.util.function.BiConsumer<GHBot, String> activityLogger;

    public GHBot(String id, BotConfig config) {
        this.id = id;
        this.config = config;
        this.debugLogging = config.debuglog();
    }

    public String id() { return id; }
    public BotConfig config() { return config; }

    public Activity activity() { return activity; }
    public void setActivity(Activity a) {
        if (debugLogging && this.activity != a && activityLogger != null) {
            activityLogger.accept(this, "activity: " + this.activity + " → " + a);
        }
        this.activity = a;
    }
    public void setActivityLogger(java.util.function.BiConsumer<GHBot, String> logger) {
        this.activityLogger = logger;
    }
    public boolean isIdle() { return activity == Activity.IDLE; }
    public boolean isBusy() { return activity != Activity.IDLE; }

    public boolean debugLogging() { return debugLogging; }
    public void setDebugLogging(boolean b) { this.debugLogging = b; }

    /** Session memory (adapted from core.js bot.memory). */
    public Map<String, Object> memory() { return memory; }
    public void clearMemory() { memory.clear(); }

    /** Job queue for this bot. */
    public Deque<String> queue() { return queue; }
}
