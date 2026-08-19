package dev.ghbot.admin;

import dev.ghbot.bot.GHBot;
import dev.ghbot.command.CommandBridge;
import dev.ghbot.command.CommandRegistry;

/**
 * Phase 11b — admin operations commands:
 *   admin read <file> · admin set <file> <key> <value> · admin backup <file>
 *   admin restore <file> · admin reload [plugin] · admin menu <name> [title]
 *   admin rollback [token] — revert the last admin edit, or one by ADM-… token
 */
public final class AdminCommands {

    private AdminCommands() {}

    public static void register(GHBot bot, CommandBridge bridge, AdminService admin) {
        CommandRegistry r = bridge.registryOf(bot);

        r.register("admin", (b, ctx) -> {
            String[] a = ctx.args();
            if (a.length == 0) {
                ctx.sender().sendMessage("§eUsage: " + b.id() + " admin <read|set|backup|restore|reload|menu|rollback> …");
                return;
            }
            switch (a[0].toLowerCase()) {
                case "read" -> {
                    if (a.length < 2) { ctx.sender().sendMessage("§eUsage: admin read <file>"); return; }
                    try {
                        String content = admin.read(a[1]);
                        // print first ~20 lines
                        String[] lines = content.split("\\n");
                        int n = Math.min(20, lines.length);
                        StringBuilder sb = new StringBuilder("§e" + a[1] + " (" + lines.length + " lines):");
                        for (int i = 0; i < n; i++) sb.append("\n§7").append(lines[i]);
                        if (lines.length > n) sb.append("\n§7… ").append(lines.length - n).append(" more");
                        ctx.sender().sendMessage(sb.toString());
                    } catch (Exception e) { ctx.sender().sendMessage("§c" + e.getMessage()); }
                }
                case "set" -> {
                    boolean propShortcut = a.length >= 2 && dev.ghbot.admin.AdminService.SERVER_PROPERTY_KEYS
                            .contains(a[1].toLowerCase());
                    if (a.length < (propShortcut ? 3 : 4)) {
                        ctx.sender().sendMessage("§eUsage: admin set <file> <key.path> <value>"
                                + " — or for server properties: admin set <property> <value> (e.g. admin set motd Welcome! Have fun!)");
                        return;
                    }
                    String file = propShortcut ? "server.properties" : a[1];
                    String key = propShortcut ? a[1].toLowerCase() : a[2];
                    int vStart = propShortcut ? 2 : 3;
                    String value = String.join(" ", java.util.Arrays.copyOfRange(a, vStart, a.length));
                    if (value.isBlank()) { ctx.sender().sendMessage("§eUsage: admin set <file> <key.path> <value>"); return; }
                    try {
                        var bak = admin.set(file, key, value, ctx.sender());
                        String token = admin.lastToken();
                        ctx.sender().sendMessage("§aSet " + a[1] + " " + a[2] + " = " + value
                                + (bak != null ? " §7(backup: " + bak.getFileName() + ")" : "")
                                + (token != null ? " §7[token: " + token + " — rollback via admin rollback " + token + "]" : ""));
                    } catch (Exception e) { ctx.sender().sendMessage("§c" + e.getMessage()); }
                }
                case "backup" -> {
                    if (a.length < 2) { ctx.sender().sendMessage("§eUsage: admin backup <file>"); return; }
                    try {
                        var bak = admin.backup(a[1]);
                        ctx.sender().sendMessage(bak == null
                                ? "§7No such file to back up: " + a[1]
                                : "§aBacked up → " + bak.getFileName());
                    } catch (Exception e) { ctx.sender().sendMessage("§c" + e.getMessage()); }
                }
                case "restore" -> {
                    if (a.length < 2) { ctx.sender().sendMessage("§eUsage: admin restore <file>"); return; }
                    try {
                        var bak = admin.restore(a[1]);
                        ctx.sender().sendMessage("§aRestored " + a[1] + " from " + bak.getFileName());
                    } catch (Exception e) { ctx.sender().sendMessage("§c" + e.getMessage()); }
                }
                case "rollback" -> {
                    try {
                        String what = a.length >= 2 ? admin.rollback(a[1]) : admin.rollbackLast();
                        ctx.sender().sendMessage("§aRolled back: §f" + what);
                    } catch (Exception e) { ctx.sender().sendMessage("§c" + e.getMessage()); }
                }
                case "reload" -> {
                    String pluginName = a.length >= 2 ? a[1] : null;
                    var res = admin.reload(pluginName, ctx.sender());
                    ctx.sender().sendMessage(res.dispatched()
                            ? "§a" + res.message()
                            : "§c" + res.message());
                }
                case "menu" -> {
                    if (a.length < 2) { ctx.sender().sendMessage("§eUsage: admin menu <name> [title]"); return; }
                    String title = a.length >= 3 ? String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length)) : null;
                    try {
                        var f = admin.createMenu(a[1], title);
                        ctx.sender().sendMessage("§aCreated DeluxeMenus menu: §f" + f.getFileName()
                                + "§a — run §f" + a[1] + "§a in-game (after /dm reload).");
                    } catch (Exception e) { ctx.sender().sendMessage("§c" + e.getMessage()); }
                }
                default -> ctx.sender().sendMessage("§eUsage: admin <read|set|backup|restore|reload|menu|rollback> …");
            }
        }, CommandRegistry.Meta.of("Admin operations: safe config editing, backups, menus, reloads, rollback", "admin <read|set|backup|restore|reload|menu|rollback> …"));
    }
}
