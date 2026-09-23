package dev.ghbot.audit;

import dev.ghbot.log.WIBLogger;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * v0.24.0 — Phase B (owner's re-scope): listen to the server console stream and
 * keep ONLY WARN/ERROR lines in a small bounded ring. NOT a second console —
 * GHBot reads this ring to answer "audit / any errors?" with a digest.
 *
 * Attach path: log4j2 is Paper's logging pipeline (plugin JUL lines are
 * forwarded into it by CraftBukkit's ForwardLogHandler, so ONE listener sees
 * everything that reaches the console). We attach a plain Appender to the root
 * logger PROGRAMMATICALLY — and do it REFLECTION-ONLY (+ a dynamic Proxy for
 * the Appender interface) so there are zero new compile dependencies and the
 * listener simply stays off (feature-detected) on platforms without log4j-core.
 *
 * All ring logic (note/stripIp/dedup/evict) is headlessly pinned by the smoke
 * suite via note(); the log4j plumbing itself is validated live on the owner's
 * Paper build (audit selftest → captured → attributed to GHBot).
 */
public final class LogWatch implements AutoCloseable {

    public static final String APPENDER_NAME = "GHBotAudit";
    public static final int DEFAULT_CAPACITY = 200;

    /** One captured console line (WARN/ERROR/FATAL), collapse-deduped. */
    public static final class Entry {
        public final String logger;
        public final String level;
        public final String message;   // IPs already stripped
        public final String thrown;    // "Class: msg\n  at a.b.C..." ≤ 8 frames, "" when none
        public final long firstTime;
        public long lastTime;
        public int count = 1;

        Entry(String logger, String level, String message, String thrown, long time) {
            this.logger = logger;
            this.level = level;
            this.message = message;
            this.thrown = thrown == null ? "" : thrown;
            this.firstTime = time;
            this.lastTime = time;
        }

        boolean sameAs(String logger, String message) {
            return this.logger.equals(logger) && this.message.equals(message);
        }
    }

    private final int capacity;
    private final ArrayDeque<Entry> ring = new ArrayDeque<>();
    private Object loggerContext;    // log4j LoggerContext (held for updateLoggers on close)
    private boolean attached;

    public LogWatch(int capacity) {
        this.capacity = Math.max(16, capacity);
    }

    /** Ingest one line (appender callback AND smoke pins). Collapses repeats. */
    public synchronized void note(String logger, String level, String message, String thrown, long time) {
        if (logger == null || logger.isBlank()) logger = "?";
        message = stripIp(message == null ? "" : message);
        if (!ring.isEmpty()) {
            Entry tail = ring.peekLast();
            if (tail.sameAs(logger, message)) {
                tail.count++;
                tail.lastTime = time;
                return;
            }
        }
        ring.addLast(new Entry(logger, level, message, thrown, time));
        while (ring.size() > capacity) ring.pollFirst();
    }

    /** Newest-last snapshot copy. */
    public synchronized List<Entry> snapshot() {
        return new ArrayList<>(ring);
    }

    public synchronized int size() { return ring.size(); }

    public synchronized void clear() { ring.clear(); }

    /** Privacy: never carry raw IPv4s into AI digests. */
    public static String stripIp(String s) {
        return s.replaceAll("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b(:\\d{1,5})?", "<ip>");
    }

    /** Best-effort source name when the event carries no logger — derive it from the
     *  thread (DiscordSRV-style fallback; e.g. Paper's own update-banner logs without
     *  a logger name, so the digest used to show "?"). */
    public static String fromThread(String thread) {
        if (thread == null || thread.isBlank()) return "?";
        if (thread.contains("Paper")) return "PaperMC";
        if (thread.contains("Netty")) return "netty-io";
        if (thread.contains("Server")) return "Server";
        return thread.length() > 24 ? thread.substring(0, 24) : thread;
    }

    /* ── log4j2 attach (reflection-only) ─────────────────────────────── */

    /**
     * Attach to the root logger; returns true when live. Any reflection hiccup →
     * false (caller logs a one-line note and audit degrades to radar-only).
     */
    public synchronized boolean attach(WIBLogger log) {
        if (attached) return true;
        try {
            Class<?> logManager = Class.forName("org.apache.logging.log4j.LogManager");
            Object ctx = logManager.getMethod("getContext", boolean.class).invoke(null, false);
            Object config = ctx.getClass().getMethod("getConfiguration").invoke(ctx);
            Object rootCfg = config.getClass().getMethod("getLoggerConfig", String.class).invoke(config, "");

            Class<?> appenderIface = Class.forName("org.apache.logging.log4j.core.Appender");
            Class<?> levelClass = Class.forName("org.apache.logging.log4j.Level");
            Class<?> filterIface = Class.forName("org.apache.logging.log4j.core.Filter");
            Object levelWarn = levelClass.getField("WARN").get(null);

            Object appender = Proxy.newProxyInstance(
                    appenderIface.getClassLoader(), new Class<?>[]{appenderIface}, new AppenderHandler());
            rootCfg.getClass()
                    .getMethod("addAppender", appenderIface, levelClass, filterIface)
                    .invoke(rootCfg, appender, levelWarn, null);
            ctx.getClass().getMethod("updateLoggers").invoke(ctx);
            this.loggerContext = ctx;
            this.attached = true;
            return true;
        } catch (Throwable t) {
            if (log != null) log.warn("[GHBot] audit listener unavailable (log4j attach: "
                    + t.getClass().getSimpleName() + ") — warn/error digest off, update radar still on");
            return false;
        }
    }

    @Override
    public synchronized void close() {
        if (!attached) return;
        attached = false;
        try {
            Object config = loggerContext.getClass().getMethod("getConfiguration").invoke(loggerContext);
            Object rootCfg = config.getClass().getMethod("getLoggerConfig", String.class).invoke(config, "");
            rootCfg.getClass().getMethod("removeAppender", String.class).invoke(rootCfg, APPENDER_NAME);
            loggerContext.getClass().getMethod("updateLoggers").invoke(loggerContext);
        } catch (Throwable ignored) {}
        loggerContext = null;
    }

    public boolean isAttached() { return attached; }

    /** Dynamic-Proxy handler implementing log4j's Appender without compile-time types. */
    private final class AppenderHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            switch (name) {
                case "append":
                    try {
                        Object event = args[0];
                        String logger = str(call(event, "getLoggerName"));
                        if (logger.isBlank()) logger = fromThread(str(call(event, "getThreadName")));
                        Object lvl = call(event, "getLevel");
                        String level = lvl == null ? "WARN" : String.valueOf(lvl);
                        Object msg = call(event, "getMessage");
                        String text = msg == null ? "" : str(call(msg, "getFormattedMessage"));
                        Throwable thrown = (Throwable) call(event, "getThrown");
                        long when = System.currentTimeMillis();
                        Object tm = call(event, "getTimeMillis");
                        if (tm instanceof Long l) when = l;
                        note(logger, level, text, thrownText(thrown), when);
                    } catch (Throwable ignored) {}
                    return null;
                case "getName": return APPENDER_NAME;
                case "isStarted": return true;
                case "isStopped": return false;
                case "ignoreExceptions": return true;
                case "start": case "stop": case "setErrorHandler": return null;
                case "getLayout": case "getErrorHandler": case "getHandler": case "getState": return null;
                case "equals": return proxy == args[0];
                case "hashCode": return System.identityHashCode(proxy);
                case "toString": return APPENDER_NAME;
                default: return null;
            }
        }

        private static Object call(Object target, String method) {
            try {
                return target.getClass().getMethod(method).invoke(target);
            } catch (Throwable t) {
                return null;
            }
        }

        private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    }

    /** "Class: message\n  at top.frames(…)" bounded to 8 frames — enough for plugin attribution. */
    public static String thrownText(Throwable t) {
        if (t == null) return "";
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String[] lines = sw.toString().split("\n");
        StringBuilder sb = new StringBuilder();
        int n = Math.min(lines.length, 9);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines[i].trim());
        }
        String out = sb.toString();
        return out.length() > 1200 ? out.substring(0, 1200) : out;
    }
}
