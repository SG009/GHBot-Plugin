package dev.ghbot.avatar;

import dev.ghbot.bot.GHBot;
import dev.ghbot.log.WIBLogger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;

import java.util.HashMap;
import java.util.Map;

/**
 * Phase 12 — Avatar. Spawns a plugin-controlled mob (default Enderman) as an
 * AI-disabled, invulnerable statue at the build site so you can WATCH GH-bot
 * work. Despawns when the job is done (unless avatar-stay).
 */
public class AvatarService {

    private final WIBLogger log;
    private final Map<String, LivingEntity> avatars = new HashMap<>();

    public AvatarService(WIBLogger log) {
        this.log = log;
    }

    public LivingEntity avatar(GHBot bot) { return avatars.get(bot.id()); }

    public boolean has(GHBot bot) { return avatars.containsKey(bot.id()); }

    public void spawn(GHBot bot, Location loc, String type) {
        if (has(bot)) despawn(bot);
        if (Bukkit.getServer() == null) return; // headless
        try {
            EntityType et;
            try { et = EntityType.valueOf(type.toUpperCase()); }
            catch (Exception e) { et = EntityType.ENDERMAN; }
            Location l = loc.clone().add(0.5, 0, 0.5);
            LivingEntity e = (LivingEntity) l.getWorld().spawnEntity(l, et);
            e.setAI(false);
            e.setInvulnerable(true);
            e.setSilent(true);
            e.setPersistent(true);
            e.setGravity(false);
            e.setCustomName(bot.id());
            e.setCustomNameVisible(true);
            e.setRemoveWhenFarAway(false);
            avatars.put(bot.id(), e);
            log.info("[" + bot.id() + "] avatar spawned (" + et.name() + ") at "
                    + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ());
        } catch (Throwable t) {
            log.error("Could not spawn avatar for " + bot.id(), t);
        }
    }

    public void despawn(GHBot bot) {
        LivingEntity e = avatars.remove(bot.id());
        if (e != null && e.isValid()) e.remove();
    }

    /** Turn avatar toward a target (server-side look). */
    public void face(GHBot bot, Location target) {
        LivingEntity e = avatars.get(bot.id());
        if (e == null || target == null || e.getWorld() != target.getWorld()) return;
        try {
            Location eye = e.getEyeLocation();
            org.bukkit.util.Vector dir = target.toVector().subtract(eye.toVector());
            dir.setY(0).normalize();
            float yaw = (float) (Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ())));
            e.setRotation(yaw, 0);
        } catch (Throwable ignored) {}
    }
}
