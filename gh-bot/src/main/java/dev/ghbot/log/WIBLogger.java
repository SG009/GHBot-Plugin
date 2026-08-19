package dev.ghbot.log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

/**
 * WIB (Asia/Jakarta) timestamped logging — same convention as your old
 * mineflayer bot's chat logger (index.js "MESSAGE LOGGER").
 */
public final class WIBLogger {

    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Logger logger;
    private final Path chatLogFile;
    private final boolean chatLogEnabled;

    public WIBLogger(Logger logger, Path dataFolder, boolean chatLogEnabled) {
        this.logger = logger;
        this.chatLogEnabled = chatLogEnabled;
        this.chatLogFile = dataFolder.resolve("logs").resolve("chat.log");
    }

    public static String stamp() {
        return LocalDateTime.now(WIB).format(TS);
    }

    public void info(String msg) {
        logger.info("[" + stamp() + "] " + msg);
    }

    public void warn(String msg) {
        logger.warning("[" + stamp() + "] " + msg);
    }

    public void error(String msg, Throwable t) {
        logger.severe("[" + stamp() + "] " + msg + (t == null ? "" : " — " + t.getMessage()));
    }

    /** Append an admin action to logs/admin.log (audit trail). */
    public void adminLog(String line) {
        try {
            java.nio.file.Path f = chatLogFile.getParent().resolve("admin.log");
            Files.createDirectories(f.getParent());
            Files.writeString(f,
                    "[" + stamp() + "] " + line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.warning("Could not write admin log: " + e.getMessage());
        }
    }

    /** Append a command dispatch to logs/commands.log (audit trail). */
    public void commandLog(String line) {
        try {
            java.nio.file.Path f = chatLogFile.getParent().resolve("commands.log");
            Files.createDirectories(f.getParent());
            Files.writeString(f,
                    "[" + stamp() + "] " + line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.warning("Could not write commands log: " + e.getMessage());
        }
    }

    /** Append an edit action to logs/edits.log (audit trail). */
    public void editLog(String line) {
        try {
            java.nio.file.Path f = chatLogFile.getParent().resolve("edits.log");
            Files.createDirectories(f.getParent());
            Files.writeString(f,
                    "[" + stamp() + "] " + line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.warning("Could not write edits log: " + e.getMessage());
        }
    }

    /** Append a chat/console interaction to logs/chat-console.log (debug aid, v0.21.12).
     *  Captures user messages, the AI provider + replies, and every tool call/result
     *  so you can paste one file instead of screenshots. Always on. */
    public void consoleLog(String line) {
        try {
            java.nio.file.Path f = chatLogFile.getParent().resolve("chat-console.log");
            Files.createDirectories(f.getParent());
            Files.writeString(f,
                    "[" + stamp() + "] " + line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.warning("Could not write chat-console log: " + e.getMessage());
        }
    }

    /** Append an in-game chat line to logs/chat.log (your old chat-log behavior). */
    public void chatLog(String line) {
        if (!chatLogEnabled) return;
        try {
            Files.createDirectories(chatLogFile.getParent());
            Files.writeString(chatLogFile,
                    "[" + stamp() + "] " + line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.warning("Could not write chat log: " + e.getMessage());
        }
    }
}
