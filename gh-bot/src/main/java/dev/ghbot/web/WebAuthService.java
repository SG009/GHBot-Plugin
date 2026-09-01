package dev.ghbot.web;

import dev.ghbot.log.WIBLogger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * v0.23.0 — Q3: web-console login token (the owner's design: CONF-style token,
 * shown ONLY in the server console / latest.log → only the real admin sees it).
 *
 * The web console (0.0.0.0:8580) serves a login page until a valid token is
 * presented; success sets an HttpOnly session cookie (12 h sliding). All other
 * routes are gated by {@link WebStatusServer#guard} (attached via attachAuth).
 *
 * Security notes baked in:
 * - token minted with SecureRandom (8 digits; 5 fails/min per IP → 10 min lockout
 *   makes brute force infeasible), NEVER logged outside the one startup line and
 *   the op-only /gh webtoken reply. Failed attempts log ip + count only.
 * - constant-time token comparison (MessageDigest.isEqual).
 * - sessions are in-memory (a restart logs everyone out); optional fixed token
 *   via server.web.token in config.yml (then the token is config-backed and
 *   regen is refused).
 *
 * Pure logic only (no Bukkit / HTTP types) so the smoke suite can pin every rule
 * headlessly; the HTTP glue lives in WebStatusServer. The clock is injectable
 * for deterministic TTL tests.
 */
public final class WebAuthService {

    public static final String COOKIE_NAME = "ghbot_session";

    public static final class Session {
        public final String id;
        public final String ip;
        public long lastSeen;
        public long expiresAt;

        Session(String id, String ip, long now, long expiresAt) {
            this.id = id;
            this.ip = ip;
            this.lastSeen = now;
            this.expiresAt = expiresAt;
        }
    }

    /** Outcome of one login attempt. session != null on success; lockedForMs > 0
     *  when the ip is (still) locked out; failsSoFar counts recent failures. */
    public static final class LoginResult {
        public final Session session;
        public final long lockedForMs;
        public final int failsSoFar;

        LoginResult(Session session, long lockedForMs, int failsSoFar) {
            this.session = session;
            this.lockedForMs = lockedForMs;
            this.failsSoFar = failsSoFar;
        }

        static LoginResult ok(Session s) { return new LoginResult(s, 0, 0); }
        static LoginResult locked(long forMs, int n) { return new LoginResult(null, forMs, n); }
        static LoginResult wrong(int n) { return new LoginResult(null, 0, n); }
    }

    private static final class RateRec {
        final ArrayDeque<Long> times = new ArrayDeque<>();
        long lockedUntil = 0;
    }

    private final WIBLogger log;            // null in headless tests
    private final LongSupplier clock;
    private final SecureRandom rng = new SecureRandom();
    private final boolean fixed;
    private String token;

    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final Map<String, RateRec> fails = new LinkedHashMap<>();

    // tuning knobs (public withX() so tests can shrink them deterministically)
    private long sessionTtlMs = 12L * 60 * 60 * 1000;   // 12 h sliding
    private int maxFails = 5;
    private long failWindowMs = 60_000;
    private long lockMs = 10 * 60_000;
    private int maxSessions = 64;

    public WebAuthService(String fixedToken, WIBLogger log, LongSupplier clock) {
        this.log = log;
        this.clock = clock;
        this.fixed = fixedToken != null && !fixedToken.isBlank();
        this.token = fixed ? fixedToken.trim() : mint();
    }

    public WebAuthService withSessionTtlMs(long ms) { this.sessionTtlMs = ms; return this; }
    public WebAuthService withMaxFails(int n) { this.maxFails = n; return this; }
    public WebAuthService withFailWindowMs(long ms) { this.failWindowMs = ms; return this; }
    public WebAuthService withLockMs(long ms) { this.lockMs = ms; return this; }

    private String mint() {
        return "WEB-" + String.format("%08d", rng.nextInt(100_000_000));
    }

    public boolean isFixed() { return fixed; }
    public String token() { return token; }
    public int sessionCount() { return sessions.size(); }

    /** Regenerate the token (op-only /gh webtoken): mints a new token and logs all
     *  sessions + rate state out. Returns null for config-fixed tokens (refused). */
    public synchronized String regen() {
        if (fixed) return null;
        token = mint();
        sessions.clear();
        fails.clear();
        return token;
    }

    /** Constant-time token comparison. */
    public boolean verifyToken(String presented) {
        if (presented == null) return false;
        byte[] a = token.getBytes(StandardCharsets.UTF_8);
        byte[] b = presented.trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    /** Millis the ip remains locked (0 = not locked). Side-effect-free. */
    public synchronized long lockedFor(String ip) {
        RateRec rec = fails.get(normalize(ip));
        if (rec == null) return 0;
        return Math.max(0, rec.lockedUntil - clock.getAsLong());
    }

    public synchronized LoginResult login(String ip, String presented) {
        ip = normalize(ip);
        long now = clock.getAsLong();
        RateRec rec = fails.computeIfAbsent(ip, k -> new RateRec());
        if (rec.lockedUntil > now) {
            return LoginResult.locked(rec.lockedUntil - now, rec.times.size());
        }
        if (verifyToken(presented)) {
            fails.remove(ip);
            Session s = new Session(randomSessionId(), ip, now, now + sessionTtlMs);
            sessions.put(s.id, s);
            evictOverflow();
            return LoginResult.ok(s);
        }
        rec.times.addLast(now);
        prune(rec, now);
        int n = rec.times.size();
        if (n >= maxFails) {
            rec.lockedUntil = now + lockMs;
            if (log != null) log.consoleLog("WEB /login LOCKOUT ip=" + ip + " (" + n + " fails in window)");
            return LoginResult.locked(lockMs, n);
        }
        if (log != null) log.consoleLog("WEB /login FAIL ip=" + ip + " (" + n + "/" + maxFails + " in window)");
        return LoginResult.wrong(n);
    }

    /** Look up (and slide) a session by raw Cookie header. Null when absent/expired. */
    public synchronized Session sessionForCookie(String cookieHeader) {
        String id = sessionIdFromCookie(cookieHeader);
        if (id == null) return null;
        Session s = sessions.get(id);
        if (s == null) return null;
        long now = clock.getAsLong();
        if (s.expiresAt <= now) {
            sessions.remove(id);
            return null;
        }
        s.lastSeen = now;
        s.expiresAt = now + sessionTtlMs;   // sliding renewal
        return s;
    }

    public String cookieHeaderFor(Session s) {
        return COOKIE_NAME + "=" + s.id + "; Path=/; HttpOnly; SameSite=Lax";
    }

    /** Extract the session id from a raw Cookie header (null when absent). */
    public static String sessionIdFromCookie(String cookieHeader) {
        if (cookieHeader == null) return null;
        for (String part : cookieHeader.split(";")) {
            String kv = part.trim();
            if (kv.startsWith(COOKIE_NAME + "=")) {
                String v = kv.substring(COOKIE_NAME.length() + 1).trim();
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    private void prune(RateRec rec, long now) {
        while (!rec.times.isEmpty() && rec.times.peekFirst() < now - failWindowMs) {
            rec.times.pollFirst();
        }
    }

    private void evictOverflow() {
        while (sessions.size() > maxSessions) {
            var it = sessions.keySet().iterator();
            it.next();
            it.remove();
        }
    }

    private String randomSessionId() {
        byte[] b = new byte[16];
        rng.nextBytes(b);
        StringBuilder sb = new StringBuilder(32);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }

    private static String normalize(String ip) {
        return ip == null || ip.isBlank() ? "?" : ip;
    }
}
