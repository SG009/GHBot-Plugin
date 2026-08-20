package dev.ghbot.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Ollama (local) via its OpenAI-compatible endpoint /v1/chat/completions.
 * Runs on the same phone at http://localhost:11434 — zero cloud cost.
 */
public class OllamaClient implements AIClient {

    private final boolean enabled;
    private final String model;
    private final String baseUrl;
    private volatile boolean jsonMode = false;   // v0.21.36 — Ollama "format":"json" constrained decoding
    private volatile boolean tempZero = false;   // v0.21.38 — temperature 0 for deterministic JSON
    public void setTempZero(boolean on) { this.tempZero = on; }
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    // v0.21.44 — cancellable HTTP (web console Stop button aborts the in-flight request).
    private volatile java.util.concurrent.CompletableFuture<?> inFlight;
    private volatile boolean cancelled;

    @Override
    public void cancelActiveCall() {
        cancelled = true;
        var f = inFlight;
        if (f != null) f.cancel(true);
    }

    private <T> java.net.http.HttpResponse<T> sendCall(HttpRequest req,
            java.net.http.HttpResponse.BodyHandler<T> handler) throws Exception {
        if (cancelled) throw new RuntimeException(id() + ": request cancelled");
        cancelled = false;
        var f = http.sendAsync(req, handler);
        inFlight = f;
        try {
            return f.get();
        } catch (java.util.concurrent.CancellationException ce) {
            throw new RuntimeException(id() + ": request cancelled");
        } catch (InterruptedException ie) {
            Thread.interrupted();   // clear interrupt flag (pooled thread) — stop requested
            throw new RuntimeException(id() + ": request cancelled");
        } catch (java.util.concurrent.ExecutionException ee) {
            if (ee.getCause() instanceof Exception e) throw e;
            throw ee;
        } finally {
            inFlight = null;
        }
    }

    /** v0.21.36 — toggle Ollama's constrained JSON decoding (guarantees valid JSON output). */
    public void setJsonMode(boolean on) { this.jsonMode = on; }

    public OllamaClient(boolean enabled, String model, String baseUrl) {
        this.enabled = enabled;
        this.model = model == null || model.isBlank() ? "qwen2.5:0.5b" : model;
        this.baseUrl = (baseUrl == null || baseUrl.isBlank())
                ? "http://localhost:11434" : baseUrl;
    }

    @Override
    public String stream(String systemPrompt, List<ChatMessage> history,
                         java.util.function.Consumer<String> onChunk) throws Exception {
        StringBuilder body = new StringBuilder();
        body.append("{\"model\":\"").append(JsonUtil.esc(model)).append("\",\"messages\":[");
        boolean first = true;
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.append("{\"role\":\"system\",\"content\":\"").append(JsonUtil.esc(systemPrompt)).append("\"}");
            first = false;
        }
        for (ChatMessage m : history) {
            if (!first) body.append(',');
            first = false;
            body.append("{\"role\":\"").append(m.role()).append("\",\"content\":\"")
                .append(JsonUtil.esc(m.content())).append("\"}");
        }
        body.append("],\"stream\":true");
        if (jsonMode) body.append(",\"format\":\"json\"");
        if (tempZero) body.append(",\"options\":{\"temperature\":0}");
        body.append("}");

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/chat"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(180))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        java.net.http.HttpResponse<java.io.InputStream> resp =
                sendCall(req, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            String err = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new RuntimeException("Ollama HTTP " + resp.statusCode() + ": " + GeminiClient.truncate(err));
        }
        StringBuilder full = new StringBuilder();
        try (var br = new java.io.BufferedReader(new java.io.InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (cancelled) throw new RuntimeException(id() + ": request cancelled");   // v0.21.44
                line = line.trim();
                if (line.isEmpty()) continue;
                String content = JsonUtil.extractString(line, "content");
                if (content != null && !content.isEmpty()) {
                    full.append(content);
                    if (onChunk != null) onChunk.accept(content);
                }
            }
        }
        String out = full.toString().trim();
        if (out.isEmpty()) throw new RuntimeException("Ollama: no content in stream");
        return out;
    }

    @Override public String id() { return "ollama"; }
    @Override public String displayName() { return "Ollama (" + model + ")"; }
    @Override public boolean isConfigured() { return enabled; }

    /** v0.21.40 — Ollama native /api/chat accepts an "images": [base64] array for
     *  multimodal models (llava, minicpm-v, minimax-m3 …). Uses JSON mode + temp 0
     *  so image→JSON build spec output is deterministic. */
    @Override
    public boolean supportsVision() { return enabled; }

    @Override
    public String chatWithImage(String systemPrompt, String userPrompt,
                                String mimeType, byte[] imageBytes) throws Exception {
        String b64 = java.util.Base64.getEncoder().encodeToString(imageBytes);
        StringBuilder body = new StringBuilder();
        body.append("{\"model\":\"").append(JsonUtil.esc(model)).append("\",\"messages\":[");
        boolean first = true;
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.append("{\"role\":\"system\",\"content\":\"").append(JsonUtil.esc(systemPrompt)).append("\"}");
            first = false;
        }
        if (!first) body.append(',');
        body.append("{\"role\":\"user\",\"content\":\"").append(JsonUtil.esc(userPrompt))
            .append("\",\"images\":[\"").append(b64).append("\"]}");
        body.append("],\"stream\":false");
        body.append(",\"format\":\"json\"");
        body.append(",\"options\":{\"temperature\":0}");
        body.append("}");

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/chat"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(180))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = sendCall(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Ollama vision HTTP " + resp.statusCode() + ": " + GeminiClient.truncate(resp.body()));
        }
        String out = JsonUtil.extractString(resp.body(), "content");
        if (out == null || out.isBlank()) throw new RuntimeException("Ollama vision: no content in response");
        return out.trim();
    }

    @Override
    public String chat(String systemPrompt, List<ChatMessage> history) throws Exception {
        StringBuilder body = new StringBuilder();
        body.append("{\"model\":\"").append(JsonUtil.esc(model)).append("\",\"messages\":[");
        boolean first = true;
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.append("{\"role\":\"system\",\"content\":\"").append(JsonUtil.esc(systemPrompt)).append("\"}");
            first = false;
        }
        for (ChatMessage m : history) {
            if (!first) body.append(',');
            first = false;
            body.append("{\"role\":\"").append(m.role()).append("\",\"content\":\"")
                .append(JsonUtil.esc(m.content())).append("\"}");
        }
        body.append("],\"stream\":false}");

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = sendCall(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Ollama HTTP " + resp.statusCode() + ": " + GeminiClient.truncate(resp.body()));
        }
        String out = JsonUtil.extractString(resp.body(), "content");
        if (out == null || out.isBlank()) throw new RuntimeException("Ollama: no content in response");
        return out.trim();
    }
}
