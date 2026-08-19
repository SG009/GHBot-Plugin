package dev.ghbot.schematic;

import dev.ghbot.log.WIBLogger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

/**
 * Downloads schematics from the internet into the library (size-capped,
 * extension-whitelisted). This is how GH-bot's build-learning dataset grows.
 * (You trust the URLs you give it — it's your admin server.)
 */
public class SchematicDownloader {

    private static final Set<String> ALLOWED_EXT = Set.of(".schem", ".schematic", ".nbt", ".litematic");
    private static final long MAX_BYTES = 25L * 1024 * 1024; // 25 MB cap

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final WIBLogger log;

    public SchematicDownloader(WIBLogger log) {
        this.log = log;
    }

    /** Download url to the library dir as <name><ext>. Returns the saved path or throws. */
    public Path download(String name, String url, Path dir) throws IOException, InterruptedException {
        // sanitize name
        String safe = name.replaceAll("[^A-Za-z0-9_-]", "_");

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "gh-bot/0.9 (schematic downloader)")
                .timeout(Duration.ofSeconds(60))
                .GET().build();

        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + " for " + url);
        }
        byte[] body = resp.body();
        if (body.length > MAX_BYTES) {
            throw new IOException("File too large (" + body.length + " bytes, cap " + MAX_BYTES + ")");
        }
        if (body.length == 0) {
            throw new IOException("Empty response from " + url);
        }

        // pick extension from the URL path (fallback .schem)
        String path = URI.create(url).getPath().toLowerCase();
        String ext = ".schem";
        for (String e : ALLOWED_EXT) {
            if (path.endsWith(e)) { ext = e; break; }
        }

        Files.createDirectories(dir);
        Path f = dir.resolve(safe + ext);
        Files.write(f, body);
        log.info("Downloaded schematic " + url + " → " + f.getFileName() + " (" + body.length + " bytes)");
        return f;
    }
}
