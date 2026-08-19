package dev.ghbot.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/** OpenAI-compatible chat completions (optional; same wire shape as Ollama). */
public class OpenAIClient implements AIClient {

    private final boolean enabled;
    private final String model;
    private final String apiKey;
    private final String baseUrl;
    private final String clientId;      // v0.21.16 — extra providers carry their own id
    private final String clientName;    // v0.21.16 — display name (e.g. "Pollinations", "Groq")
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();

    public OpenAIClient(boolean enabled, String model, String apiKey, String baseUrl) {
        this(enabled, model, apiKey, baseUrl, "openai", "OpenAI");
    }

    /** v0.21.16 — for extra OpenAI-compatible providers (Pollinations, Groq, Cerebras, Kiro-gateway, 9Router…). */
    public OpenAIClient(boolean enabled, String model, String apiKey, String baseUrl,
                        String id, String name) {
        this.enabled = enabled;
        this.model = model == null || model.isBlank() ? "gpt-4o-mini" : model;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.baseUrl = (baseUrl == null || baseUrl.isBlank())
                ? "https://api.openai.com/v1" : baseUrl;
        this.clientId = (id == null || id.isBlank()) ? "openai" : id;
        this.clientName = (name == null || name.isBlank()) ? clientId : name;
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
        body.append("],\"stream\":true}");

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        java.net.http.HttpResponse<java.io.InputStream> resp =
                http.send(req, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            String err = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new RuntimeException("OpenAI HTTP " + resp.statusCode() + ": " + GeminiClient.truncate(err));
        }
        StringBuilder full = new StringBuilder();
        try (var br = new java.io.BufferedReader(new java.io.InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String json = line.substring(5).trim();
                if (json.isEmpty() || json.equals("[DONE]")) continue;
                String content = JsonUtil.extractStringN(json, "content", 0);
                if (content != null && !content.isEmpty()) {
                    full.append(content);
                    if (onChunk != null) onChunk.accept(content);
                }
            }
        }
        String out = full.toString().trim();
        if (out.isEmpty()) throw new RuntimeException("OpenAI: no content in stream");
        return out;
    }

    @Override public String id() { return clientId; }
    @Override public String displayName() { return clientName + " (" + model + ")"; }
    @Override public boolean isConfigured() { return enabled; }   // v0.21.16 — `enabled` is the on/off; keyless free providers (Pollinations) work too

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
        body.append("]}");

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new RuntimeException("OpenAI HTTP " + resp.statusCode() + ": " + GeminiClient.truncate(resp.body()));
        }
        String out = JsonUtil.extractString(resp.body(), "content");
        if (out == null || out.isBlank()) throw new RuntimeException("OpenAI: no content in response");
        return out.trim();
    }
}
