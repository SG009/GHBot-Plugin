package dev.ghbot.command;

import dev.ghbot.bot.GHBot;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Per-bot command registry — mirrors your old command_handler.js:
 * register(name, fn, meta) + async-safe execute with error handling +
 * dynamic help listing everything.
 */
public class CommandRegistry {

    /** Metadata for a command (shown in dynamic help). */
    public record Meta(String description, String usage) {
        public static Meta of(String description, String usage) { return new Meta(description, usage); }
    }

    public record Cmd(Meta meta, BiConsumer<GHBot, Context> fn) {}

    /** Execution context passed to every command. */
    public record Context(CommandSender sender, String[] args) {}

    private final Map<String, Cmd> commands = new LinkedHashMap<>();

    public void register(String name, BiConsumer<GHBot, Context> fn, Meta meta) {
        commands.put(sanitize(name), new Cmd(meta, fn));
    }

    public void register(String name, BiConsumer<GHBot, Context> fn) {
        register(name, fn, Meta.of("", ""));
    }

    public boolean contains(String name) {
        return commands.containsKey(sanitize(name));
    }

    /** Execute a command async-safe with error handling (like command_handler.js). */
    public void execute(GHBot bot, CommandSender sender, String name, String[] args) {
        Cmd cmd = commands.get(sanitize(name));
        if (cmd == null) {
            sender.sendMessage("§cUnknown command: \"" + name + "\"");
            sender.sendMessage("§eType \"" + bot.id() + " help\" for available commands.");
            return;
        }
        try {
            cmd.fn().accept(bot, new Context(sender, args == null ? new String[0] : args));
        } catch (Throwable t) {
            sender.sendMessage("§cError executing command \"" + name + "\". Check console for details.");
            // caller-provided logger hooks here; plugin logs via its own logger
            throw t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException(t);
        }
    }

    /** Dynamic help text: every command with description + usage example. */
    public String helpText(GHBot bot) {
        StringBuilder sb = new StringBuilder();
        sb.append("§e[").append(bot.id()).append("] Available commands:");
        for (Map.Entry<String, Cmd> e : commands.entrySet()) {
            Cmd c = e.getValue();
            String desc = c.meta().description();
            if (desc == null || desc.isBlank()) desc = "No description.";
            sb.append("\n§f- ").append(e.getKey()).append("§7: ").append(desc);
            String usage = c.meta().usage();
            if (usage != null && !usage.isBlank()) {
                sb.append("\n§8    e.g. ").append(bot.id()).append(" ").append(usage);
            }
        }
        sb.append("\n§eType \"").append(bot.id()).append(" <command> [args]\" to run a command.");
        return sb.toString();
    }

    public List<String> names() { return new ArrayList<>(commands.keySet()); }

    private static String sanitize(String name) {
        return (name == null ? "" : name.trim().toLowerCase());
    }
}
