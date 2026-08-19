package dev.ghbot.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Gemini (Google) — free tier (Flash models, 1,500 req/day, no card).
 * REST: v1beta/models/{model}:generateContent?key=...
 */
public class GeminiClient implements AIClient {

    private final boolean enabled;
    private final String model;
    private final String apiKey;
    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();

    public GeminiClient(boolean enabled, String model, String apiKey, String baseUrl) {
        this.enabled = enabled;
        this.model = model == null || model.isBlank() ? "gemini-2.5-flash" : model;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.baseUrl = (baseUrl == null || baseUrl.isBlank())
                ? "https://generativelanguage.googleapis.com/" : baseUrl;
    }

    @Override public String id() { return "gemini"; }
    @Override public String displayName() { return "Gemini (" + model + ")"; }
    @Override public boolean isConfigured() { return enabled && !apiKey.isBlank(); }
    @Override public boolean supportsVision() { return isConfigured(); }

    /** v0.21.40 — Gemini vision: send the image as an inline_data part (base64). */
    @Override
    public String chatWithImage(String systemPrompt, String userPrompt,
                                String mimeType, byte[] imageBytes) throws Exception {
        String b64 = java.util.Base64.getEncoder().encodeToString(imageBytes);
        StringBuilder body = new StringBuilder();
        body.append("{\"contents\":[{\"role\":\"user\",\"parts\":[")
            .append("{\"text\":\"").append(JsonUtil.esc(userPrompt)).append("\"},")
            .append("{\"inline_data\":{\"mime_type\":\"").append(mimeType)
            .append("\",\"data\":\"").append(b64).append("\"}}]}]");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.append(",\"systemInstruction\":{\"parts\":[{\"text\":\"")
                .append(JsonUtil.esc(systemPrompt)).append("\"}]}");
        }
        body.append(",\"generationConfig\":{\"temperature\":0.2,\"maxOutputTokens\":2048}}");

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "v1beta/models/" + model
                        + ":generateContent?key=" + apiKey))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Gemini vision HTTP " + resp.statusCode() + ": " + GeminiClient.truncate(resp.body()));
        }
        String out = JsonUtil.extractString(resp.body(), "text");
        if (out == null || out.isBlank()) throw new RuntimeException("Gemini vision: no text in response");
        return out.trim();
    }

    @Override
    public String chat(String systemPrompt, List<ChatMessage> history) throws Exception {
        StringBuilder body = new StringBuilder();
        body.append("{\"contents\":[");
        boolean first = true;
        for (ChatMessage m : history) {
            if (!first) body.append(',');
            first = false;
            body.append("{\"role\":\"").append(m.role().equals("assistant") ? "model" : m.role())
                .append("\",\"parts\":[{\"text\":\"").append(JsonUtil.esc(m.content())).append("\"}]}");
        }
        body.append(']');
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.append(",\"systemInstruction\":{\"parts\":[{\"text\":\"")
                .append(JsonUtil.esc(systemPrompt)).append("\"}]}");
        }
        body.append(",\"generationConfig\":{\"temperature\":0.7,\"maxOutputTokens\":1024}}");

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "v1beta/models/" + model
                        + ":generateContent?key=" + apiKey))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Gemini HTTP " + resp.statusCode() + ": " + GeminiClient.truncate(resp.body()));
        }
        String out = JsonUtil.extractString(resp.body(), "text");
        if (out == null || out.isBlank()) throw new RuntimeException("Gemini: no text in response");
        return out.trim();
    }

    @Override
    public String stream(String systemPrompt, List<ChatMessage> history,
                         java.util.function.Consumer<String> onChunk) throws Exception {
        StringBuilder body = new StringBuilder();
        body.append("{\"contents\":[");
        boolean first = true;
        for (ChatMessage m : history) {
            if (!first) body.append(',');
            first = false;
            body.append("{\"role\":\"").append(m.role().equals("assistant") ? "model" : m.role())
                .append("\",\"parts\":[{\"text\":\"").append(JsonUtil.esc(m.content())).append("\"}]}");
        }
        body.append(']');
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.append(",\"systemInstruction\":{\"parts\":[{\"text\":\"")
                .append(JsonUtil.esc(systemPrompt)).append("\"}]}");
        }
        body.append(",\"generationConfig\":{\"temperature\":0.7,\"maxOutputTokens\":1024}}");

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "v1beta/models/" + model
                        + ":streamGenerateContent?alt=sse&key=" + apiKey))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        java.net.http.HttpResponse<java.io.InputStream> resp =
                http.send(req, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            String err = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new RuntimeException("Gemini HTTP " + resp.statusCode() + ": " + truncate(err));
        }
        StringBuilder full = new StringBuilder();
        try (var br = new java.io.BufferedReader(new java.io.InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String json = line.substring(5).trim();
                if (json.isEmpty() || json.equals("[DONE]")) continue;
                String text = JsonUtil.extractStringN(json, "text", 0);
                if (text != null && !text.isEmpty()) {
                    full.append(text);
                    if (onChunk != null) onChunk.accept(text);
                }
            }
        }
        String out = full.toString().trim();
        if (out.isEmpty()) throw new RuntimeException("Gemini: no text in stream");
        return out;
    }

    static String truncate(String s) {
        return s == null ? "" : (s.length() > 300 ? s.substring(0, 300) : s);
    }
}
