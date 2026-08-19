package dev.ghbot.web;

import dev.ghbot.bot.GHBot;
import dev.ghbot.command.CommandBridge;
import dev.ghbot.command.CommandRegistry;
import dev.ghbot.log.WIBLogger;
import dev.ghbot.schematic.SchematicImporter;
import dev.ghbot.schematic.SchematicService;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Phase 9 — view commands:
 *   @GH000 view [jobId]          → print the browser URL for a preview job (default: latest)
 *   @GH000 view file <name>      → import a library schematic and view it in the browser
 */
public final class PreviewCommands {

    private PreviewCommands() {}

    public static void register(GHBot bot, CommandBridge bridge, PreviewRegistry previews,
                                SchematicService schematics, int webPort) {
        CommandRegistry r = bridge.registryOf(bot);

        r.register("view", (b, ctx) -> {
            String[] args = ctx.args();
            if (webPort <= 0) {
                ctx.sender().sendMessage("§7[" + b.id() + "] Web server not enabled (server.web.enabled: true).");
                return;
            }
            if (args.length >= 2 && args[0].equalsIgnoreCase("file")) {
                // view a library schematic file
                String file = args[1];
                Path f = schematics.dir().resolve(file);
                if (!Files.exists(f)) {
                    ctx.sender().sendMessage("§c[" + b.id() + "] No such file: " + file);
                    return;
                }
                try {
                    var model = SchematicImporter.importFile(Files.readAllBytes(f));
                    if (model == null) {
                        ctx.sender().sendMessage("§c[" + b.id() + "] Couldn't parse " + file);
                        return;
                    }
                    String name = file.replaceFirst("\\.[^.]+$", "");
                    PreviewJob job = previews.register(name, b.id(), model, false);
                    ctx.sender().sendMessage("§a[" + b.id() + "] View: §f" + viewUrl(job.id, webPort)
                            + "\n§7  on the phone itself: http://127.0.0.1:" + webPort + "/view/" + job.id);
                    return;
                } catch (Exception e) {
                    ctx.sender().sendMessage("§c[" + b.id() + "] Import failed: " + e.getMessage());
                    return;
                }
            }

            PreviewJob job = args.length >= 1 ? previews.get(args[0]) : previews.latest();
            if (job == null) {
                ctx.sender().sendMessage("§7[" + b.id() + "] No preview job. "
                        + "Use " + b.id() + " build <prompt> (then it stages), or " + b.id() + " view file <name>.");
                return;
            }
            ctx.sender().sendMessage("§a[" + b.id() + "] View §f" + job.name + "§a: "
                    + viewUrl(job.id, webPort) + " §7(" + job.model.size() + " blocks)"
                    + "\n§7  on the phone itself: http://127.0.0.1:" + webPort + "/view/" + job.id);
        }, CommandRegistry.Meta.of("Open a build in the browser 3D viewer", "view [jobId] | view file <name>"));
    }

    /** Best-effort LAN IP for the printed viewer URL (falls back to a placeholder). */
    private static String hostIp() {
        // v0.21.28 — enumerate interfaces: pick the first site-local IPv4 (192.168.x / 10.x / 172.16-31.x),
        // so the printed viewer URL is the REAL LAN IP, not loopback or a placeholder.
        try {
            var nets = java.net.NetworkInterface.getNetworkInterfaces();
            while (nets != null && nets.hasMoreElements()) {
                var ni = nets.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                var addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    var a = addrs.nextElement();
                    if (!(a instanceof java.net.Inet4Address)) continue;
                    String ip = a.getHostAddress();
                    if (ip == null || ip.startsWith("127.") || ip.startsWith("0.")) continue;
                    if (ip.startsWith("192.168.") || ip.startsWith("10.")
                            || ip.startsWith("172.16.") || ip.startsWith("172.17.")
                            || ip.startsWith("172.18.") || ip.startsWith("172.19.")
                            || ip.startsWith("172.2")) return ip;
                }
            }
        } catch (Throwable ignored) {}
        try {
            var addr = java.net.InetAddress.getLocalHost();
            if (addr != null) {
                String ip = addr.getHostAddress();
                if (ip != null && !ip.isBlank() && !ip.startsWith("127.") && !ip.startsWith("0.")) return ip;
            }
        } catch (Throwable ignored) {}
        return "<this-server-ip>";
    }

    private static String viewUrl(String jobId, int webPort) {
        return "http://" + hostIp() + ":" + webPort + "/view/" + jobId;
    }
}
