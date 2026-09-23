package dev.ghbot.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.ghbot.bot.BotRegistry;
import dev.ghbot.bot.GHBot;
import dev.ghbot.log.WIBLogger;
import dev.ghbot.review.GhostService;
import dev.ghbot.schematic.SchematicService;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * P13 — embedded web status page. Lightweight JDK HTTP server (no deps),
 * binds to 0.0.0.0 so you can open it from any device on the LAN while
 * GH-bot works hands-off. Serves / with bot state + live system stats.
 */
public class WebStatusServer {

    public record BotRow(String id, String activity, String provider, String mode,
                         boolean debug, int queue, int memory) {}

    private final HttpServer server;
    private final WIBLogger log;
    private final Supplier<Map<String, String>> sysStats;   // label -> value
    private final Supplier<List<BotRow>> bots;
    private final int port;
    private PreviewRegistry previews;
    private BotRegistry registry;
    private GhostService ghosts;
    private SchematicService schematics;
    private dev.ghbot.ai.ChatService chatService;
    private java.util.function.Supplier<dev.ghbot.agent.ToolExecutor> toolProvider;
    private dev.ghbot.command.CommandLearning commandLearning;
    private java.util.function.Supplier<java.util.Map<String, String>> jsonStats;
    private WebAuthService auth;   // v0.23.0 — Q3 login-token gate (null = legacy open)

    /** v0.21.42 — review-activity feed (Approve/Deny/Export from the 3D viewer) shown in the chat console. */
    private final java.util.List<String> reviewFeed = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** v0.21.42 — record a viewer action + echo it to the chat console log. */
    public void recordReview(String line) {
        reviewFeed.add(line);
        while (reviewFeed.size() > 200) reviewFeed.remove(0);
        if (log != null) log.consoleLog(line);
    }

    /** v0.21.42 — short HH:mm:ss timestamp for review-feed lines. */
    private static String ts() {
        return java.time.LocalTime.now().withNano(0).toString();
    }

    public WebStatusServer(int port, Supplier<Map<String, String>> sysStats,
                           Supplier<List<BotRow>> bots, WIBLogger log) throws IOException {
        this.port = port;
        this.sysStats = sysStats;
        this.bots = bots;
        this.log = log;
        this.server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/", guard(this::handleRoot));
        server.setExecutor(null); // default
    }

    /** v0.23.0 — Q3: attach the login-token gate. From now on every route requires a
     *  valid session cookie; only /login + /api/login stay public. The guard reads
     *  {@code auth} at REQUEST time (contexts register before attachAuth is called). */
    /** v0.27.0 — opt-in loopback login bypass (owner browses from the server's own device). */
    private volatile boolean localBypass;
    public void setLocalBypass(boolean on) { this.localBypass = on; }
    public boolean localBypass() { return localBypass; }

    /** Headless seam: hosts that mean "the server's own device". 127.0.0.0/8 (dot-checked),
     *  ::1 and localhost — private LAN (192.168.x, 10.x) NEVER bypasses. */
    public static boolean isLoopbackHost(String host) {
        if (host == null) return false;
        String h = host.trim().toLowerCase();
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        return h.equals("localhost") || h.equals("::1") || h.equals("0:0:0:0:0:0:0:1")
                || (h.startsWith("127.") && h.length() > 4);
    }

    public void attachAuth(WebAuthService auth) {
        this.auth = auth;
        server.createContext("/login", this::handleLogin);
        server.createContext("/api/login", this::handleApiLogin);
    }

    public WebAuthService auth() { return auth; }

    /** v0.23.0 — the gate itself: no valid session cookie → 302 to /login for pages,
     *  401 JSON for API-ish paths (/api/*, /cmd, /upload). Blocks are audit-logged
     *  with ip but never with any presented credential. */
    private HttpHandler guard(HttpHandler h) {
        return ex -> {
            WebAuthService a = this.auth;          // evaluated per request (late attach)
            if (a == null) { h.handle(ex); return; }
            // v0.27.0 — loopback bypass: the device hosting the server is already trusted hands-on
            if (localBypass && isLoopbackHost(ipOf(ex))) { h.handle(ex); return; }
            String cookie = ex.getRequestHeaders().getFirst("Cookie");
            if (a.sessionForCookie(cookie) != null) { h.handle(ex); return; }
            String path = ex.getRequestURI().getPath();
            log.consoleLog("WEB auth-block " + path + " ip=" + ipOf(ex));
            if (isApiPath(path)) {
                respond(ex, 401, "{\"error\":\"auth required — log in at /login "
                        + "(token is printed in the server console / latest.log)\"}",
                        "application/json; charset=utf-8");
            } else {
                // v0.23.1 — remember where they were going (/console, /view/<id>, …)
                String dest = path + (ex.getRequestURI().getQuery() != null
                        ? "?" + ex.getRequestURI().getQuery() : "");
                ex.getResponseHeaders().set("Location", "/login?next="
                        + java.net.URLEncoder.encode(dest, StandardCharsets.UTF_8));
                ex.sendResponseHeaders(302, -1);
                ex.close();
            }
        };
    }

    /** v0.23.1 — redirect target after login: only same-site absolute paths survive
     *  (blocks open-redirect tricks like //evil.com or scheme injection). Default
     *  landing is the chat console — the owner's main tool, not the status page. */
    public static String sanitizeNext(String next) {
        String fallback = "/console";
        if (next == null) return fallback;
        String n = next.trim();
        if (!n.startsWith("/") || n.startsWith("//")) return fallback;
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c < 0x21 || c == 0x7F || c == '\\' || c == '"' || c == '\'') return fallback;
        }
        return n;
    }

    private static boolean isApiPath(String path) {
        return path.startsWith("/api/") || path.equals("/cmd") || path.equals("/upload");
    }

    private static String ipOf(HttpExchange ex) {
        try {
            return ex.getRemoteAddress() == null || ex.getRemoteAddress().getAddress() == null
                    ? "?" : ex.getRemoteAddress().getAddress().getHostAddress();
        } catch (Exception e) {
            return "?";
        }
    }

    /* ── /login — v0.23.0: GET shows the token form; POST (form token=… or raw body)
     *  validates it, sets the session cookie, and redirects to <next> (or /console).
     *  Public by design. ── */
    private void handleLogin(HttpExchange ex) throws IOException {
        WebAuthService a = this.auth;
        if (a == null) { respond(ex, 503, "auth not configured"); return; }
        if (ex.getRequestMethod().equalsIgnoreCase("GET")) {
            String next = queryParam(ex, "next");
            respond(ex, 200, loginPage(null, next), "text/html; charset=utf-8");
            return;
        }
        byte[] raw = readBounded(ex.getRequestBody(), 4096);
        String body = raw == null ? "" : new String(raw, StandardCharsets.UTF_8).trim();
        String presented = body, next = "";
        if (body.contains("=") || body.contains("&")) {   // form-urlencoded (token=…&next=…)
            presented = "";
            for (String kv : body.split("&")) {
                String[] p = kv.split("=", 2);
                if (p.length != 2) continue;
                String v = java.net.URLDecoder.decode(p[1], StandardCharsets.UTF_8);
                if (p[0].equals("token")) presented = v;
                else if (p[0].equals("next")) next = v;
            }
        }
        loginAttempt(ex, a, presented, false, sanitizeNext(next));
    }

    /* ── /api/login — v0.23.0: same check for curl/script clients:
     *  POST /api/login?token=WEB-…  →  {"ok":true} + Set-Cookie, or 401/429 JSON. ── */
    private void handleApiLogin(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respond(ex, 405, "POST only"); return; }
        WebAuthService a = this.auth;
        if (a == null) { respond(ex, 503, "{\"error\":\"auth not configured\"}", "application/json; charset=utf-8"); return; }
        String presented = "";
        String query = ex.getRequestURI().getQuery();
        if (query != null) {
            for (String kv : query.split("&")) {
                String[] p = kv.split("=", 2);
                if (p.length == 2 && p[0].equals("token")) {
                    presented = java.net.URLDecoder.decode(p[1], StandardCharsets.UTF_8);
                }
            }
        }
        if (presented.isBlank()) {
            byte[] raw = readBounded(ex.getRequestBody(), 4096);
            presented = raw == null ? "" : new String(raw, StandardCharsets.UTF_8).trim();
        }
        loginAttempt(ex, a, presented, true, "/console");
    }

    private void loginAttempt(HttpExchange ex, WebAuthService a, String presented,
                              boolean json, String next) throws IOException {
        WebAuthService.LoginResult r = a.login(ipOf(ex), presented);
        if (r.session != null) {
            ex.getResponseHeaders().set("Set-Cookie", a.cookieHeaderFor(r.session));
            if (json) {
                respond(ex, 200, "{\"ok\":true}", "application/json; charset=utf-8");
            } else {
                ex.getResponseHeaders().set("Location", next);
                ex.sendResponseHeaders(302, -1);
                ex.close();
            }
            return;
        }
        if (r.lockedForMs > 0) {
            long secs = (r.lockedForMs + 999) / 1000;
            if (json) {
                respond(ex, 429, "{\"ok\":false,\"locked\":" + secs + "}", "application/json; charset=utf-8");
            } else {
                respond(ex, 429, loginPage("Too many wrong tries — locked. Retry in " + secs + "s.", next),
                        "text/html; charset=utf-8");
            }
            return;
        }
        if (json) {
            respond(ex, 401, "{\"ok\":false,\"fails\":" + r.failsSoFar + "}", "application/json; charset=utf-8");
        } else {
            respond(ex, 401, loginPage("Wrong token (" + r.failsSoFar + " fail(s) — ip locks after 5 within a minute).", next),
                    "text/html; charset=utf-8");
        }
    }

    /** Small helper: first value of a query parameter (decoded), "" when absent. */
    private static String queryParam(HttpExchange ex, String key) {
        String query = ex.getRequestURI().getQuery();
        if (query == null) return "";
        for (String kv : query.split("&")) {
            String[] p = kv.split("=", 2);
            if (p.length == 2 && p[0].equals(key)) {
                return java.net.URLDecoder.decode(p[1], StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    /** v0.23.0 — tiny dark login page (same style family as the status page). */
    private static String loginPage(String error, String next) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">")
          .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
          .append("<title>gh-bot · login</title><style>")
          .append("body{background:#0a0a0a;color:#f5f2e9;font-family:ui-monospace,Consolas,monospace;")
          .append("display:flex;min-height:100vh;align-items:center;justify-content:center;margin:0}")
          .append(".card{background:#15150f;border:1px solid #ffffff14;border-radius:10px;padding:26px;max-width:420px;width:90%}")
          .append("h1{font-size:18px;letter-spacing:.06em;margin-top:0}")
          .append("input{width:100%;box-sizing:border-box;background:#0a0a0a;color:#f5f2e9;border:1px solid #ffffff22;")
          .append("border-radius:8px;padding:10px;font:inherit;margin:10px 0}")
          .append("button{width:100%;background:#85a63e;color:#0a0a0a;border:0;border-radius:8px;padding:10px;")
          .append("font:inherit;font-weight:700;cursor:pointer}")
          .append(".err{color:#f5b64d;font-size:13px;margin:8px 0 0}")
          .append(".dim{color:#8d8a7e;font-size:12px;line-height:1.5}")
          .append("</style></head><body><div class=\"card\">")
          .append("<h1>gh-bot · <span style=\"color:#85a63e\">login</span></h1>")
          .append("<p class=\"dim\">This console is admin-only. Paste the login token ")
          .append("(<b>WEB-########</b>) — it is printed once in the server console / ")
          .append("latest.log at startup. Ops can regenerate it in-game with ")
          .append("<b>GH000 webtoken</b>.</p>")
          .append("<form method=\"POST\" action=\"/login\">")
          .append("<input type=\"hidden\" name=\"next\" value=\"").append(esc(sanitizeNext(next))).append("\">")
          .append("<input name=\"token\" autocomplete=\"off\" autocapitalize=\"none\" ")
          .append("placeholder=\"WEB-…\" required>")
          .append("<button type=\"submit\">log in</button></form>");
        if (error != null) sb.append("<p class=\"err\">").append(esc(error)).append("</p>");
        sb.append("</div></body></html>");
        return sb.toString();
    }

    /** Attach the /chat + /console + /api routes (Phase 9b v2 — web console). */
    public void attachChat(dev.ghbot.ai.ChatService chat, BotRegistry reg,
                           java.util.function.Supplier<dev.ghbot.agent.ToolExecutor> tools,
                           dev.ghbot.command.CommandLearning learning,
                           java.util.function.Supplier<java.util.Map<String, String>> stats) {
        this.chatService = chat;
        this.registry = reg;
        this.toolProvider = tools;
        this.commandLearning = learning;
        this.jsonStats = stats;
        server.createContext("/chat", guard(this::handleChat));
        server.createContext("/console", guard(this::handleConsole));
        server.createContext("/api/status", guard(this::handleApiStatus));
        server.createContext("/api/tools", guard(this::handleApiTools));
        server.createContext("/cmd", guard(this::handleCmd));
        server.createContext("/upload", guard(this::handleUpload));   // v0.21.40 — upload JSON build spec or image
        server.createContext("/api/events", guard(this::handleApiEvents));   // v0.21.42 — review-activity feed
        server.createContext("/api/cancel", guard(this::handleApiCancel));   // v0.21.44 — Stop button
        server.createContext("/api/scan/last", guard(this::handleApiScanLast)); // v0.25.0 — Phase C viewer scan layer
    }

    /* ── /api/cancel?session=<key> — v0.21.44: Stop button. Aborts the in-flight AI
     *  request for the session (and closes the provider HTTP call so tokens aren't
     *  wasted waiting out the timeout). ── */
    private void handleApiCancel(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respond(ex, 405, "POST only"); return; }
        String query = ex.getRequestURI().getQuery();
        String session = "";
        if (query != null) {
            for (String kv : query.split("&")) {
                String[] p = kv.split("=", 2);
                if (p.length == 2 && p[0].equals("session")) {
                    session = java.net.URLDecoder.decode(p[1], java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        if (chatService != null) chatService.requestStop(session);
        respond(ex, 200, "stopping");
    }

    /* ── /api/events?after=<idx> — v0.21.42: review actions (Approve/Deny/Export) that
     *  happened in the 3D viewer, for the chat console to display. Returns events with
     *  index >= after plus the next cursor. ── */
    private void handleApiEvents(HttpExchange ex) throws IOException {
        int after = 0;
        String query = ex.getRequestURI().getQuery();
        if (query != null) {
            for (String kv : query.split("&")) {
                String[] p = kv.split("=", 2);
                if (p.length == 2 && p[0].equals("after")) {
                    try { after = Integer.parseInt(p[1]); } catch (NumberFormatException ignored) {}
                }
            }
        }
        if (after < 0) after = 0;
        int size = reviewFeed.size();
        // v0.21.43 — plugin reload resets the in-memory feed (size drops below the client's
        // cursor). If the cursor is ahead of the feed, tell the client to reset its cursor
        // (it skips the replay of pre-reload events) so NEW events keep showing without a
        // page refresh.
        boolean reset = after > size;
        if (reset) after = 0;
        StringBuilder sb = new StringBuilder("{\"next\":").append(size)
                .append(",\"reset\":").append(reset).append(",\"events\":[");
        boolean first = true;
        for (int i = Math.max(after, 0); i < size; i++) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"").append(escJson(reviewFeed.get(i))).append("\"");
        }
        sb.append("]}");
        respond(ex, 200, sb.toString(), "application/json; charset=utf-8");
    }

    /* ── /upload — v0.21.40: upload a .json build spec (stages it directly, no paste into
     *  the bubble — solves 140KB+ specs) OR an image (vision → build spec → stage).
     *  Request: POST /upload?name=file.json  with raw body = file bytes. ── */
    private void handleUpload(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respond(ex, 405, "POST only"); return; }
        if (toolProvider == null || chatService == null || registry == null) {
            respond(ex, 503, "Upload not available (chat/tools not attached)"); return;
        }
        String query = ex.getRequestURI().getQuery();
        String name = "";
        if (query != null) {
            for (String kv : query.split("&")) {
                String[] p = kv.split("=", 2);
                if (p.length == 2 && p[0].equals("name")) {
                    name = java.net.URLDecoder.decode(p[1], java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        if (name.isBlank()) { respond(ex, 400, "missing ?name=<file>"); return; }
        // v0.22.2 — bounded read (AUDIT P1-4): reject DURING the read at 25 MB + 1 byte
        // instead of buffering an unbounded body into the heap first (phone, 0.0.0.0 bind).
        byte[] body = readBounded(ex.getRequestBody(), 25L * 1024 * 1024);
        if (body == null) { respond(ex, 413, "file too large (max 25 MB)"); return; }

        String lower = name.toLowerCase();
        String specText = null;
        if (lower.endsWith(".json")) {
            specText = new String(body, java.nio.charset.StandardCharsets.UTF_8);
            var jspec = dev.ghbot.builder.JsonBuildSpec.parse(specText);
            if (jspec == null || !jspec.isValid()) {
                respond(ex, 400, "not a valid JSON build spec: "
                        + (jspec == null ? "missing palette/blocks" : String.join("; ", jspec.errors)));
                return;
            }
        } else if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".webp") || lower.endsWith(".gif")) {
            String mime = lower.endsWith(".png") ? "image/png"
                    : lower.endsWith(".webp") ? "image/webp"
                    : lower.endsWith(".gif") ? "image/gif" : "image/jpeg";
            try {
                specText = chatService.imageToSpec(mime, body);
            } catch (Exception e) {
                respond(ex, 502, "vision failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                return;
            }
        } else {
            respond(ex, 415, "unsupported file type (use .json, .png, .jpg, .jpeg, .webp)");
            return;
        }

        log.consoleLog("WEB /upload name=" + name + " specChars=" + specText.length());
        // run through the SAME build tool as chat → stages + registers a preview job
        String result = toolProvider.get().run(new dev.ghbot.agent.ToolProtocol.ToolCall("build", new String[]{specText}));
        respond(ex, 200, summarizeBuild(result, name));
    }

    /** v0.21.40 — the build tool echoes the whole spec back; for uploads we keep only the
     *  useful lines (view URL + block count + preview image URL). */
    private static String summarizeBuild(String result, String name) {
        if (result == null || result.isBlank()) return "Upload processed, but no build result.";
        if (result.contains("build failed") || result.contains("no live server")
                || result.contains("no location") || result.contains("tool error")) {
            return "Upload received — build could not run:\n" + result.trim();
        }
        java.util.regex.Matcher vu = java.util.regex.Pattern
                .compile("(https?://[^/\\s]+)/view/([a-z0-9-]+)").matcher(result);
        java.util.regex.Matcher bc = java.util.regex.Pattern
                .compile("\\((\\d+) blocks\\)").matcher(result);
        StringBuilder sb = new StringBuilder();
        if (vu.find()) {
            String base = vu.group(1), id = vu.group(2);
            String blocks = bc.find() ? bc.group(1) : "?";
            sb.append("📦 Staged from upload: **").append(name).append("** (").append(blocks).append(" blocks)\n");
            sb.append("🔗 3D viewer: ").append(base).append("/view/").append(id).append("\n");
            sb.append("PREVIEW_IMG: ").append(base).append("/view/").append(id).append("/preview.png");
        } else {
            sb.append("Upload processed:\n").append(result.trim());
        }
        return sb.toString();
    }

    /** Attach the /view/* preview routes (Phase 9). */
    public void attachPreviews(PreviewRegistry previews, BotRegistry registry,
                               GhostService ghosts, SchematicService schematics) {
        this.previews = previews;
        this.registry = registry;
        this.ghosts = ghosts;
        this.schematics = schematics;
        server.createContext("/view", guard(this::handleView));
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void start() {
        server.start();
        log.info("Web status page listening on http://0.0.0.0:" + port() + "/");
    }

    public void stop() {
        server.stop(0);
    }

    private void handleRoot(HttpExchange ex) throws IOException {
        byte[] body = page().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private String page() {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">")
          .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
          .append("<title>gh-bot · status</title><style>")
          .append("body{background:#0a0a0a;color:#f5f2e9;font-family:ui-monospace,Consolas,monospace;margin:0;padding:24px}")
          .append("h1{font-size:20px;letter-spacing:.06em;border-bottom:1px solid #ffffff22;padding-bottom:10px}")
          .append("h2{font-size:13px;text-transform:uppercase;letter-spacing:.12em;color:#8d8a7e;margin-top:26px}")
          .append("table{border-collapse:collapse;width:100%;max-width:640px;font-size:13px}")
          .append("td,th{padding:6px 10px;text-align:left;border-bottom:1px solid #ffffff14}")
          .append("th{color:#8d8a7e;font-weight:600;font-size:11px;text-transform:uppercase}")
          .append(".ok{color:#85a63e}.warn{color:#f5b64d}.dim{color:#8d8a7e}")
          .append(".card{background:#15150f;border:1px solid #ffffff14;border-radius:10px;padding:14px;max-width:640px}")
          .append("</style></head><body>")
          .append("<h1>gh-bot · <span class=\"ok\">status</span></h1>");

        // system stats
        sb.append("<h2>System</h2><div class=\"card\"><table>");
        sysStats.get().forEach((k, v) -> sb.append("<tr><th>").append(k).append("</th><td>").append(v).append("</td></tr>"));
        sb.append("</table></div>");

        // bots
        sb.append("<h2>Bots</h2><div class=\"card\"><table><tr><th>id</th><th>activity</th><th>provider</th>")
          .append("<th>mode</th><th>debug</th><th>queue</th><th>memory</th></tr>");
        for (BotRow b : bots.get()) {
            String actCls = "IDLE".equals(b.activity()) ? "ok" : "warn";
            sb.append("<tr><td>").append(b.id()).append("</td>")
              .append("<td class=\"").append(actCls).append("\">").append(b.activity()).append("</td>")
              .append("<td>").append(b.provider()).append("</td>")
              .append("<td>").append(b.mode()).append("</td>")
              .append("<td>").append(b.debug() ? "on" : "off").append("</td>")
              .append("<td>").append(b.queue()).append("</td>")
              .append("<td>").append(b.memory()).append("</td></tr>");
        }
        sb.append("</table></div>");

        sb.append("<p class=\"dim\" style=\"margin-top:26px;font-size:11px\">gh-bot · "
                  + "<a href=\"/\" style=\"color:#85a63e\">status</a> · "
                  + "<a href=\"/console\" style=\"color:#85a63e\">console</a> · "
                  + "<a href=\"/view\" style=\"color:#85a63e\">previews</a></p>");
        sb.append("</body></html>");
        return sb.toString();
    }

    /* ── /console page (Phase 9b v2) ── */
    private void handleConsole(HttpExchange ex) throws IOException {
        byte[] html;
        try (var in = getClass().getResourceAsStream("/web/console.html")) {
            if (in == null) { respond(ex, 500, "console.html missing"); return; }
            html = in.readAllBytes();
        }
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
        ex.getResponseHeaders().set("Pragma", "no-cache");
        ex.getResponseHeaders().set("Expires", "0");
        ex.sendResponseHeaders(200, html.length);
        try (var os = ex.getResponseBody()) { os.write(html); }
    }

    /* ── /api/status JSON for the console sidebar ── */
    /* v0.25.0 — Phase C: the last terrain scan as viewer-ready JSON (the
     * "scan" toggle in the 3D viewer renders it with the build renderer, so
     * the owner can visually verify the bot measured the right area). */
    private void handleApiScanLast(HttpExchange ex) throws IOException {
        String json = dev.ghbot.terrain.ScanFeed.toJson(
                dev.ghbot.terrain.TerrainCommands.LAST_SCAN,
                dev.ghbot.terrain.TerrainCommands.LAST_ORIGIN,
                dev.ghbot.terrain.TerrainCommands.LAST_RADIUS);
        if (json == null) { respond(ex, 404, "{\"error\":\"no scan yet — run `scan <radius>` first\"}"); return; }
        respond(ex, 200, json, "application/json; charset=utf-8");
    }

    private void handleApiStatus(HttpExchange ex) throws IOException {
        StringBuilder sb = new StringBuilder("{");
        if (jsonStats != null) {
            sb.append("\"sys\":{");
            boolean f = true;
            for (var e : jsonStats.get().entrySet()) {
                if (!f) sb.append(',');
                f = false;
                sb.append("\"").append(escJson(e.getKey())).append("\":\"")
                  .append(escJson(e.getValue())).append("\"");
            }
            sb.append("},");
        }
        sb.append("\"bots\":[");
        if (registry != null) {
            boolean f = true;
            for (GHBot b : registry.all()) {
                if (!f) sb.append(',');
                f = false;
                sb.append("{\"id\":\"").append(escJson(b.id()))
                  .append("\",\"activity\":\"").append(b.activity())
                  .append("\",\"role\":\"").append(escJson(b.config().role()))
                  .append("\",\"queue\":").append(b.queue().size()).append("}");
            }
        }
        sb.append("]}");
        respond(ex, 200, sb.toString(), "application/json; charset=utf-8");
    }

    /* ── /api/tools — capability sheet for the on-device secretary (v0.21.2) ── */
    private void handleApiTools(HttpExchange ex) throws IOException {
        String bot = registry != null && registry.defaultBot() != null ? registry.defaultBot().id() : "GH000";
        String tools = jsonStr(dev.ghbot.agent.ToolProtocol.helpText());
        String commands = jsonStr(dev.ghbot.command.BotCommands.toolSheet());   // v0.21.14 single source
        respond(ex, 200, "{\"bot\":\"" + escJson(bot) + "\",\"tools\":\"" + tools
                + "\",\"commands\":\"" + commands + "\"}", "application/json; charset=utf-8");
    }

    /** Curated GH-bot command cheat-sheet fed to the local secretary so its drafts fit the technician. */
    private static final String KEY_COMMANDS =
            // v0.22.0 — JARVIS-FOR-ADMIN surface (4 pillars). Shelved commands removed.
            "build <prompt> [--direct] · plan <prompt> · edit <target|here> <instruction> · "
            + "scan <radius> · look <x> <y> <z> · find <block> · set <block> <radius> · replace <from> <to> <radius> · "
            + "terraform <smooth|flatten|raise|lower> <radius> · undo [minutes] · schem <name> <prompt> | schem import <file> · "
            + "paste <file> · library · export <name> · approve | deny | redo · chat <msg> · "
            + "admin read|set|backup|restore|rollback|reload|menu · cmd <line> [; line; …] (multi-step, e.g. LuckPerms rank setup) · "
            + "provider list|set · refresh · confirm <CONF-token> · status · cap · view · "
            + "SERVER FILES editable via admin set (also rollback-able): server.properties (motd, resource-pack), "
            + "bukkit.yml, spigot.yml, paper-global.yml, paper-world-defaults.yml";

    /** Escape a string for embedding as a JSON string value (incl. newlines). */
    private static String jsonStr(String s) {
        return escJson(s).replace("\r", "").replace("\n", "\\n");
    }

    /* ── /cmd — run a plugin command from the browser (expanded role) ── */
    private void handleCmd(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respond(ex, 405, "POST only"); return; }
        if (commandLearning == null || registry == null || registry.defaultBot() == null) {
            respond(ex, 503, "Command route not available"); return;
        }
        String query = ex.getRequestURI().getQuery();
        String line = "";
        if (query != null) {
            String[] q = query.split("&");
            for (String kv : q) {
                String[] p = kv.split("=", 2);
                if (p.length == 2 && p[0].equals("line")) {
                    line = java.net.URLDecoder.decode(p[1], java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        if (line.isBlank()) { respond(ex, 400, "empty line"); return; }
        // v0.21.2/3 — route through the SAME guardrails as chat/console: systemic
        // commands (stop/reload/op/…) are blocked and mint a CONF-… token;
        // multiple commands supported via ';' (multi-step admin tasks).
        // v0.21.9 — dispatch MUST run on the main thread (Paper AsyncCatcher).
        // v0.22.2 — Pillar 3: return the command's REAL output (web-bound inline).
        final String cmdLine = line;
        String res;
        if (commandLearning.capture() != null) {
            java.util.List<dev.ghbot.command.CmdOutput> outs =
                    commandLearning.capture().captureMany(registry.defaultBot(), cmdLine);
            res = dev.ghbot.command.CmdOutputCapture.joinInline(outs, false);
        } else {
            res = dev.ghbot.core.MainThread.call(() ->
                    commandLearning.dispatchGuardedMany(registry.defaultBot(), cmdLine));
        }
        if (res.contains("⛔")) res += "\n(confirm blocked ones in chat: @GH000 confirm <CONF-token>)";
        respond(ex, 200, res);
    }

    /* ── /chat route (Phase 9b v2 — SSE streaming + tools) ── */
    private void handleChat(HttpExchange ex) throws IOException {
        if (chatService == null || registry == null || registry.defaultBot() == null) {
            respond(ex, 503, "Chat not available");
            return;
        }
        if (ex.getRequestMethod().equalsIgnoreCase("GET")) {
            byte[] html;
            try (var in = getClass().getResourceAsStream("/web/console.html")) {
                if (in == null) { respond(ex, 500, "console.html missing"); return; }
                html = in.readAllBytes();
            }
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
            ex.sendResponseHeaders(200, html.length);
            try (var os = ex.getResponseBody()) { os.write(html); }
            return;
        }
        String query = ex.getRequestURI().getQuery();
        String msg = "", session = "web", stream = "0";
        if (query != null) {
            for (String kv : query.split("&")) {
                String[] p = kv.split("=", 2);
                if (p.length != 2) continue;
                String v = java.net.URLDecoder.decode(p[1], java.nio.charset.StandardCharsets.UTF_8);
                switch (p[0]) {
                    case "msg" -> msg = v;
                    case "session" -> session = v;
                    case "stream" -> stream = v;
                }
            }
        }
        if (msg.isBlank()) { respond(ex, 400, "empty msg"); return; }
        log.consoleLog("WEB /chat session=" + session + " stream=" + stream + " USER: " + msg);   // v0.21.12
        dev.ghbot.bot.GHBot bot = registry.defaultBot();
        var tools = toolProvider == null ? null : toolProvider.get();

        if (stream.equals("1")) {
            // SSE
            ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.sendResponseHeaders(200, 0);
            var os = ex.getResponseBody();
            StringBuilder full = new StringBuilder();
            final String sess = session;   // v0.21.44 — effective-final copy for the lambda
            try {
                chatService.streamChat(bot, sess, msg,
                        chunk -> {
                            try {

                                // v0.21.25 — send each chunk as a PROPER JSON string (JSON.stringify):
                                // quoted + all escapes, so the wire has zero literal newlines AND the client
                                // can JSON.parse it back to the exact chunk (multi-line replies intact).
                                String safe = "\"" + escJson(chunk) + "\"";
                                os.write(("data: " + safe + "\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                os.flush();
                            } catch (Exception e) {
                                // v0.21.44 — client closed the stream (Stop / tab closed): abort the
                                // in-flight AI call so the provider stops generating (saves tokens).
                                try { chatService.requestStop(sess); } catch (Exception ignored) {}
                            }
                        },
                        tools);
                os.write(("event: done\ndata: [DONE]\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } finally {
                os.close();
            }
            return;
        }

        String reply = chatService.streamChat(bot, session, msg, null, tools);
        // strip tool markers for plain reply
        reply = reply.replaceAll("⟦tool:[^⟧]*⟧", "").replaceAll("⟦result:[^⟧]*⟧", "").trim();
        respond(ex, 200, reply, "text/plain; charset=utf-8");
    }

    private static String escJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    /* ── /view routes ── */
    private void handleView(HttpExchange ex) throws IOException {
        if (previews == null) { respond(ex, 503, "Previews not attached"); return; }
        String path = ex.getRequestURI().getPath();
        String rest = path.substring("/view".length());
        rest = rest.replaceAll("^/+", "");
        String method = ex.getRequestMethod();

        // index: list jobs
        if (rest.isEmpty() || rest.equals("index.html")) {
            StringBuilder sb = new StringBuilder("<h2>gh-bot previews</h2><ul>");
            for (PreviewJob j : previews.all().values()) {
                sb.append("<li><a href=\"/view/").append(j.id).append("\">").append(esc(j.name))
                  .append("</a> (").append(j.model.size()).append(" blocks) · ")
                  .append(j.botId).append(" · <a href=\"/view/").append(j.id).append("/data.json\">data</a></li>");
            }
            sb.append("</ul><p><a href=\"/\">status</a></p>");
            respond(ex, 200, sb.toString(), "text/html; charset=utf-8");
            return;
        }

        String[] parts = rest.split("/");
        PreviewJob job = previews.get(parts[0]);
        if (job == null) { respond(ex, 404, "Unknown job: " + parts[0]); return; }

        if (parts.length == 1) {
            // serve the viewer HTML (embedded resource)
            byte[] html = viewerHtml();
            if (html == null) { respond(ex, 500, "viewer.html missing from jar"); return; }
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, html.length);
            try (var os = ex.getResponseBody()) { os.write(html); }
            return;
        }

        switch (parts[1]) {
            case "data.json" -> respond(ex, 200, PreviewRegistry.dataJson(job), "application/json; charset=utf-8");
            case "preview.png" -> {   // v0.21.34 — BlockGPT-style inline image of the staged build
                byte[] png = dev.ghbot.web.BuildPreviewImage.render(job.model);
                if (png == null) { respond(ex, 500, "render failed"); return; }
                ex.getResponseHeaders().set("Content-Type", "image/png");
                ex.getResponseHeaders().set("Cache-Control", "no-cache");
                ex.sendResponseHeaders(200, png.length);
                try (var os = ex.getResponseBody()) { os.write(png); }
                return;
            }
            case "approve" -> {
                if (!method.equalsIgnoreCase("POST")) { respond(ex, 405, "POST only"); return; }
                if (Bukkit.getServer() == null) { respond(ex, 503, "No live server"); return; }
                GHBot bot = registry == null ? null : registry.bot(job.botId);
                if (bot == null) { respond(ex, 400, "Bot not found"); return; }
                boolean ok = dev.ghbot.core.MainThread.call(() ->
                        ghosts.approve(bot, Bukkit.getConsoleSender()));
                recordReview("[" + ts() + "] " + (ok ? "✅ Approved" : "⚠️ Approve — nothing staged")
                        + " \"" + job.name + "\" (" + job.model.size() + " blocks)");   // v0.21.42
                respond(ex, ok ? 200 : 400, ok ? "Approved" : "Nothing staged");
            }
            case "deny" -> {
                if (!method.equalsIgnoreCase("POST")) { respond(ex, 405, "POST only"); return; }
                if (Bukkit.getServer() == null) { respond(ex, 503, "No live server"); return; }
                GHBot bot = registry == null ? null : registry.bot(job.botId);
                if (bot == null) { respond(ex, 400, "Bot not found"); return; }
                dev.ghbot.core.MainThread.call(() -> {
                    ghosts.clear(bot, Bukkit.getConsoleSender(), false);
                    return null;
                });
                recordReview("[" + ts() + "] ❌ Denied \"" + job.name + "\" (" + job.model.size() + " blocks)");   // v0.21.42
                respond(ex, 200, "Denied/cleared");
            }
            case "export" -> {
                if (!method.equalsIgnoreCase("POST")) { respond(ex, 405, "POST only"); return; }
                if (schematics == null) { respond(ex, 400, "Schematics not available"); return; }
                try {
                    var written = dev.ghbot.core.MainThread.call(() ->
                            schematics.export(job.name, job.model, "all"));
                    recordReview("[" + ts() + "] 📦 Exported \"" + job.name + "\" (" + written.size() + " file(s))");   // v0.21.42
                    respond(ex, 200, "Exported " + written.size() + " files");
                } catch (Exception e) {
                    respond(ex, 500, "Export failed: " + e.getMessage());
                }
            }
            default -> respond(ex, 404, "Unknown sub-route: " + parts[1]);
        }
    }

    private byte[] viewerHtml() {
        try (var in = getClass().getResourceAsStream("/web/viewer.html")) {
            if (in == null) return null;
            return in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    /** v0.22.2 — read at most maxBytes+1 from a stream; returns null when the body exceeds maxBytes. */
    private static byte[] readBounded(java.io.InputStream in, long maxBytes) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(chunk)) != -1) {
            total += n;
            if (total > maxBytes) return null;
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    private void respond(HttpExchange ex, int code, String body) throws IOException {
        respond(ex, code, body, "text/plain; charset=utf-8");
    }

    private void respond(HttpExchange ex, int code, String body, String ct) throws IOException {
        byte[] b = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.sendResponseHeaders(code, b.length);
        try (var os = ex.getResponseBody()) { os.write(b); }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
