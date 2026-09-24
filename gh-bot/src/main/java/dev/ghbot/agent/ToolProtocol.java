package dev.ghbot.agent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provider-agnostic tool calling for the web console / chat agent.
 * The model emits lines exactly like:  ⟦tool:name arg1 arg2⟧
 * The server parses, executes (audited), appends results, and continues.
 * This works with every provider (Gemini/Ollama/OpenAI/fallback), unlike
 * provider-specific function-calling schemas.
 */
public final class ToolProtocol {

    private ToolProtocol() {}

    // v0.21.37 — accept BOTH fancy ⟦⟧ AND plain [] markers (models often emit [tool:...])
    public static final Pattern TOOL = Pattern.compile("[" + java.util.regex.Pattern.quote("[") + "⟦]tool:([a-z_]+)(.*?)[" + java.util.regex.Pattern.quote("]") + "⟧]", Pattern.DOTALL);
    public static final Pattern TOOL_FANCY = Pattern.compile("⟦tool:([a-z_]+)(.*?)⟧", Pattern.DOTALL);

    /** The tool documentation appended to the system prompt. */
    public static String helpText() {
        return """

                You can call tools by emitting a line exactly like: [tool:name arg1 arg2]  (or ⟦tool:name arg1 arg2⟧)
                IMPORTANT: when the user asks in plain language (e.g. "scan 100 at 86 86 262",
                "find diamond_ore", "build a house", "status", "admin set motd ..."), the server
                ALREADY ran the tool and gave you the real result — just summarize that result.
                Available tools:
                - status                 — show server + bot status
                - catalog [keyword]     — browse the server's full command catalog (every command
                                           you can run via cmd); e.g. catalog lp for LuckPerms commands
                - players                — list who is online
                - worlds                 — list loaded worlds
                - scan [radius] [at x y z] [--full [depth]] — scan terrain (returns a summary AND a
                                           compact JSON world spec {name,palette,blocks[]} with
                                           absolute coords + origin; surface by default, --full for depth)
                - find <block> [radius]  — find blocks of a type (returns exact coordinates as a JSON spec)
                - look <x> <y> <z>       — inspect the block at those coordinates (returns its blockstate)
                - plan <prompt...>       — text-only design preview (no blocks)
                - build <prompt...>      — design and stage a build (then it's in the review viewer)
                - edit <target> <instruction...> — modify an existing build by instruction (snapshot→plan→apply)
                - schem <name> <prompt...> — design and export a schematic
                - paste <file...>        — paste a schematic in-world
                - set <block> <radius>   — set a region of blocks
                - replace <from> <to> [radius] — swap block types in a region
                - terraform <smooth|flatten|raise|lower> <radius> — terrain edits
                - cmd <command...>       — run server commands as console (audited). Separate multiple
                                           commands with ';' in ONE call (e.g. ⟦tool:cmd lp creategroup PRO;
                                           lp group PRO parent add default; lp group PRO meta addprefix 1000 "…"⟧).
                                           Systemic commands (stop/reload/op/deop/ban/whitelist/rm -rf…) are
                                           BLOCKED and mint a CONF-… token — tell the user to confirm it.
                - script [run|status|drop] — pending command-script from a 📎 .txt upload (preview first,
                                           never auto-run). Do NOT re-emit the script lines via cmd.
                - admin <op> <file> [args] — admin ops: read <file> | set <file> <key> <value> |
                                           backup <file> | restore <file> | rollback [token] | reload [plugin] |
                                           menu <name> [title]. Works on plugin YAML AND server .properties
                                           (server.properties motd/resource-pack, bukkit.yml, spigot.yml,
                                           paper-global.yml). Backed up + validated + rollback-able.
                - undo                   — undo the last edit
                Use a tool when it would help answer the user, then continue normally.
                Only use the exact ⟦tool:…⟧ syntax on its own line.""";
    }

    public record ToolCall(String name, String[] args) {}

    /** Extract the first tool call from a reply, if any. */
    public static ToolCall firstCall(String reply) {
        Matcher m = TOOL.matcher(reply == null ? "" : reply);
        if (m.find()) {
            String name = m.group(1);
            String rest = m.group(2).trim();
            String[] args = rest.isEmpty() ? new String[0] : rest.split("\\s+");
            return new ToolCall(name, args);
        }
        return null;
    }

    /** v0.21.28 — extract ALL tool calls from a reply, in order. */
    public static java.util.List<ToolCall> allCalls(String reply) {
        java.util.List<ToolCall> out = new java.util.ArrayList<>();
        if (reply == null) return out;
        Matcher m = TOOL.matcher(reply);
        while (m.find()) {
            String name = m.group(1);
            String rest = m.group(2).trim();
            String[] args = rest.isEmpty() ? new String[0] : rest.split("\\s+");
            out.add(new ToolCall(name, args));
        }
        return out;
    }

    public static boolean hasCall(String reply) {
        return TOOL.matcher(reply == null ? "" : reply).find();
    }
}
