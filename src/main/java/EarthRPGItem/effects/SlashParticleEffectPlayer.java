package EarthRPGItem.effects;

import EarthRPGItem.EarthRPGItem;
import EarthRPGItem.items.ParticleConfig;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

public class SlashParticleEffectPlayer {

    private final EarthRPGItem plugin;

    public SlashParticleEffectPlayer(EarthRPGItem plugin) {
        this.plugin = plugin;
    }

    public boolean supports(ItemStack itemStack) {
        if (itemStack == null) return false;
        Material type = itemStack.getType();
        String name = type.name();
        return name.endsWith("_SWORD") || name.endsWith("_AXE") || type == Material.TRIDENT;
    }

    public boolean isThrust(ItemStack itemStack, Player player) {
        return itemStack != null && (itemStack.getType() == Material.TRIDENT || player.isSprinting());
    }

    public void play(Player player, ParticleConfig config, double slashLength, double attackSpeed, boolean thrust) {
        if (!plugin.isFeatureEnabled("slash-particles") || !plugin.getConfig().getBoolean("slash-particles.enabled", true) || !config.enabled()) return;
        Particle particle = parseParticle(config.particle());
        Object data = particle == Particle.DUST ? dust(config.block()) : null;
        int totalCount = Math.max(120, (int) Math.round(config.count() * plugin.getConfig().getDouble("slash-particles.count-multiplier", 4.0)));
        double seconds = presentationSeconds(attackSpeed) * plugin.getConfig().getDouble("slash-particles.speed-multiplier", 0.65);
        int steps = Math.max(3, Math.min(40, (int) Math.ceil(seconds * 20.0)));
        int perStep = Math.max(2, (int) Math.ceil(totalCount / (double) steps));
        double startRandom = Math.max(0, plugin.getConfig().getDouble("slash-particles.start-random-radius", 0.5));
        double forward = Math.max(0.1, plugin.getConfig().getDouble("slash-particles.start-forward-distance", 1.0));
        Location origin = start(player, startRandom, forward);
        boolean falling = !thrust && ThreadLocalRandom.current().nextBoolean();
        boolean leftToRight = ThreadLocalRandom.current().nextBoolean();
        double diameter = thrust ? Math.min(1.5, plugin.getConfig().getDouble("slash-particles.thrust-diameter", 1.5)) : Math.min(2.0, plugin.getConfig().getDouble("slash-particles.slash-diameter", 2.0));
        double radius = thrust ? Math.min(0.3, plugin.getConfig().getDouble("slash-particles.thrust-radius", 0.18)) : Math.min(0.5, plugin.getConfig().getDouble("slash-particles.slash-radius", 0.08));

        // One repeating task per swing instead of `steps` (up to 40) separate runTaskLater
        // tasks. The previous form queued a brand new task object for every step on every
        // arm swing, and a player holding left-click produces many swings per second, so the
        // scheduler queue was the single largest transient allocation in the combat path.
        new BukkitRunnable() {
            private int currentStep;

            @Override
            public void run() {
                if (currentStep >= steps || !player.isOnline()) {
                    cancel();
                    return;
                }
                for (int index = 0; index < perStep; index++) {
                    double progress = Math.min(1.0, (currentStep + index / (double) Math.max(1, perStep)) / Math.max(1.0, steps - 1.0));
                    Location point = thrust ? thrustPoint(origin, player, progress, diameter, radius) : slashPoint(origin, player, progress, diameter, radius, slashLength, falling, leftToRight, index, perStep);
                    spawn(point, particle, data);
                }
                currentStep++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private Location start(Player player, double randomRadius, double forward) {
        Vector direction = player.getLocation().getDirection().normalize();
        Vector side = new Vector(-direction.getZ(), 0, direction.getX()).normalize();
        double sideOffset = ThreadLocalRandom.current().nextDouble(-randomRadius, randomRadius);
        double upOffset = ThreadLocalRandom.current().nextDouble(0, randomRadius);
        return player.getEyeLocation().add(direction.multiply(forward)).add(side.multiply(sideOffset)).add(0, -0.4 + upOffset, 0);
    }

    private Location slashPoint(Location origin, Player player, double progress, double diameter, double radius, double length, boolean falling, boolean leftToRight, int index, int perStep) {
        double arc = (leftToRight ? -1 : 1) * (-0.5 + progress) * Math.PI;
        Vector direction = player.getLocation().getDirection().setY(0).normalize();
        if (direction.lengthSquared() == 0) direction = new Vector(0, 0, 1);
        Vector side = new Vector(-direction.getZ(), 0, direction.getX()).normalize();
        double horizontal = Math.sin(arc) * diameter / 2.0;
        double vertical = falling ? (diameter / 2.0 - progress * diameter) : -0.7 + Math.cos(arc) * Math.min(0.42, diameter / 5.0);
        double noise = (index / (double) Math.max(1, perStep - 1) - 0.5) * radius;
        double forward = Math.max(0, length) * progress + Math.cos(arc) * radius;
        return origin.clone().add(side.multiply(horizontal + noise)).add(0, vertical + random(radius), 0).add(direction.multiply(forward));
    }

    private Location thrustPoint(Location origin, Player player, double progress, double diameter, double radius) {
        Vector direction = player.getLocation().getDirection().normalize();
        Vector side = new Vector(-direction.getZ(), 0, direction.getX()).normalize();
        return origin.clone().add(direction.multiply(progress * diameter)).add(side.multiply(random(radius))).add(0, random(radius), 0);
    }

    private void spawn(Location location, Particle particle, Object data) {
        if (data == null) location.getWorld().spawnParticle(particle, location, 1, 0, 0, 0, 0);
        else location.getWorld().spawnParticle(particle, location, 1, 0, 0, 0, 0, data);
    }

    private double presentationSeconds(double attackSpeed) {
        if (attackSpeed >= 0) return 0.35 - Math.min(100, attackSpeed) / 100.0 * 0.20;
        return 0.35 + Math.min(100, Math.abs(attackSpeed)) / 100.0 * 2.0;
    }

    private Particle parseParticle(String value) {
        try { return Particle.valueOf(value.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException exception) { return Particle.DUST; }
    }

    /**
     * DUST 的颜色与颗粒大小，直接复用 {@link ParticleEffectPlayer#dustOptions(String)}。
     * <p>
     * 这里原本是自己一份 {@code 0.55F} 的实现，而 {@code ParticleEffectPlayer} 那份是 {@code 1.0F}：
     * 同样是 DUST 轨迹，弹射物（箭雨）比剑气粗了近一倍，玩家一眼就看出不一致。
     * 现在共用同一个工厂与同一个默认值，尺寸不会再分叉；写法也统一支持
     * {@code "r,g,b"} 与 {@code "r,g,b,size"}。
     */
    private Particle.DustOptions dust(String value) {
        return ParticleEffectPlayer.dustOptions(value);
    }

    private int clamp(String value) {
        return Math.max(0, Math.min(255, Integer.parseInt(value.trim())));
    }

    private double random(double range) {
        return ThreadLocalRandom.current().nextDouble(-range, range);
    }
}
