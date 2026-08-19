package dev.ghbot.ai;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal JSON helpers — no external deps (gson is blocked on some mirrors,
 * and Paper's bundled gson shouldn't be a compile dependency). Good enough
 * for the small, well-structured provider payloads we build/parse.
 */
public final class JsonUtil {

    private JsonUtil() {}

    /** Escape a string for embedding inside a JSON string literal. */
    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private static final Pattern KEY = Pattern.compile("\"([A-Za-z_][A-Za-z0-9_]*)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    /** Extract the first value of a string key from JSON. Returns null if absent. */
    public static String extractString(String json, String key) {
        if (json == null) return null;
        Matcher m = KEY.matcher(json);
        while (m.find()) {
            if (m.group(1).equals(key)) return unescape(m.group(2));
        }
        return null;
    }

    /** Extract the n-th (0-based) occurrence's value of a key. */
    public static String extractStringN(String json, String key, int n) {
        if (json == null) return null;
        Matcher m = KEY.matcher(json);
        int i = 0;
        while (m.find()) {
            if (m.group(1).equals(key)) {
                if (i == n) return unescape(m.group(2));
                i++;
            }
        }
        return null;
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'u' -> {
                        if (i + 4 < s.length()) {
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException e) {
                                sb.append('u');
                            }
                        }
                    }
                    default -> sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
