package EarthRPGItem.listeners;

import EarthRPGItem.EarthRPGItem;
import EarthRPGItem.effects.DamageDisplay;
import EarthRPGItem.effects.ParticleEffectPlayer;
import EarthRPGItem.effects.SlashParticleEffectPlayer;
import EarthRPGItem.effects.SoundEffectPlayer;
import EarthRPGItem.fskills.FSkills;
import EarthRPGItem.items.RpgItem;
import EarthRPGItem.items.RpgStats;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class ActiveDefenseListener implements Listener {

    /**
     * 不参与闪避/格挡判定的伤害来源。
     *
     * <p>注意 {@code CAMPFIRE} 是 1.21 才加的枚举值，1.20.5 / 1.20.6 的
     * {@link EntityDamageEvent.DamageCause} 里没有它。直接写 {@code DamageCause.CAMPFIRE}
     * 会导致在 1.20.x 上编译不过（用低版本 API 编译时）或在 1.20.x 上加载时抛
     * {@code NoSuchFieldError}。这里改为按名字解析，缺了就跳过。
     */
    private static final Set<EntityDamageEvent.DamageCause> EXCLUDED_CAUSES = buildExcludedCauses();

    private static Set<EntityDamageEvent.DamageCause> buildExcludedCauses() {
        Set<EntityDamageEvent.DamageCause> causes = EnumSet.of(
                EntityDamageEvent.DamageCause.FIRE, EntityDamageEvent.DamageCause.FIRE_TICK,
                EntityDamageEvent.DamageCause.LAVA, EntityDamageEvent.DamageCause.HOT_FLOOR,
                EntityDamageEvent.DamageCause.VOID, EntityDamageEvent.DamageCause.STARVATION,
                EntityDamageEvent.DamageCause.SUFFOCATION, EntityDamageEvent.DamageCause.MAGIC,
                EntityDamageEvent.DamageCause.POISON, EntityDamageEvent.DamageCause.WITHER,
                EntityDamageEvent.DamageCause.DRAGON_BREATH);
        // CAMPFIRE（营火）只在 1.21+ 存在；按名字解析，老版本自动跳过。
        EntityDamageEvent.DamageCause campfire = causeOrNull("CAMPFIRE");
        if (campfire != null) causes.add(campfire);
        return causes;
    }

    private static EntityDamageEvent.DamageCause causeOrNull(String name) {
        try {
            return EntityDamageEvent.DamageCause.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private final EarthRPGItem plugin;
    private final DamageDisplay damageDisplay;
    private final ParticleEffectPlayer particleEffectPlayer;
    private final SoundEffectPlayer soundEffectPlayer;
    private final SlashParticleEffectPlayer slashParticleEffectPlayer;
    private final Map<UUID, Long> armedSneakTimes = new HashMap<>();
    private final Map<UUID, Long> lastSneakTimes = new HashMap<>();
    private final Map<UUID, Long> dodgeCooldowns = new HashMap<>();
    private final Map<UUID, Long> blockCooldowns = new HashMap<>();
    private final Map<UUID, Long> skillDodgeWindows = new HashMap<>();

    public ActiveDefenseListener(EarthRPGItem plugin) {
        this.plugin = plugin;
        this.damageDisplay = new DamageDisplay(plugin);
        this.particleEffectPlayer = new ParticleEffectPlayer(plugin);
        this.soundEffectPlayer = new SoundEffectPlayer(plugin);
        this.slashParticleEffectPlayer = new SlashParticleEffectPlayer(plugin);
        String dodgeSoundPath = "defensive-actions.dodge.sound";
        if ("ENTITY_PLAYER_ATTACK_SWEEP".equalsIgnoreCase(plugin.getConfig().getString(dodgeSoundPath, ""))) {
            plugin.getConfig().set(dodgeSoundPath, "ENTITY_ENDERMAN_TELEPORT");
            plugin.saveConfig();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onToggleSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) return;
        UUID uuid = event.getPlayer().getUniqueId();
        long now = System.currentTimeMillis();
        long repeatGuard = Math.max(0L, Math.round(plugin.getConfig().getDouble("defensive-actions.repeat-guard-seconds", 1.0) * 1000));
        long previous = lastSneakTimes.getOrDefault(uuid, 0L);
        lastSneakTimes.put(uuid, now);
        if (previous != 0L && now - previous < repeatGuard) {
            armedSneakTimes.remove(uuid);
            return;
        }
        armedSneakTimes.put(uuid, now);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || EXCLUDED_CAUSES.contains(event.getCause())) return;
        RpgStats stats = plugin.getRpgItemManager().collectActiveStats(player, false);
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        boolean skillDodge = skillDodgeWindows.getOrDefault(uuid, 0L) >= now;
        if (!skillDodge) skillDodgeWindows.remove(uuid);
        boolean hasBlock = plugin.isFeatureEnabled("block") && stats.block() != 0;
        boolean hasDodge = plugin.isFeatureEnabled("dodge") && stats.dodge() != 0;
        if (!hasBlock && !hasDodge && !skillDodge) return;

        long pressedAt = armedSneakTimes.getOrDefault(uuid, 0L);
        long age = now - pressedAt;
        long successWindow = Math.max(0L, Math.round(plugin.getConfig().getDouble("defensive-actions.timing-window-seconds", 0.3) * 1000));
        long invalidWindow = Math.max(successWindow, Math.round(plugin.getConfig().getDouble("defensive-actions.invalid-window-seconds", 1.0) * 1000));
        if (!skillDodge && (pressedAt == 0L || age < 0L || age > invalidWindow || age > successWindow)) return;

        boolean blockTrigger = !skillDodge && hasBlock;
        Map<UUID, Long> cooldowns = blockTrigger ? blockCooldowns : dodgeCooldowns;
        String type = blockTrigger ? "block" : "dodge";
        if (cooldowns.getOrDefault(player.getUniqueId(), 0L) > now) return;
        double cooldownSeconds = Math.max(0, plugin.getConfig().getDouble("defensive-actions." + type + ".cooldown-seconds", 1));
        cooldowns.put(player.getUniqueId(), now + Math.round(cooldownSeconds * 1000));
        armedSneakTimes.remove(player.getUniqueId());
        skillDodgeWindows.remove(player.getUniqueId());
        event.setCancelled(true);
        giveResistance(player);
        playEffect(player, type, hasBlock ? Color.WHITE : Color.fromRGB(170, 220, 255));
        healDetermination(player, stats.determination());

        if (hasBlock && event instanceof EntityDamageByEntityEvent byEntity) {
            tryCounterAttack(player, byEntity, stats);
        }
    }

    public void armSkillDodge(Player player, int durationTicks) {
        if (player == null) return;
        long ticks = Math.max(1, durationTicks);
        skillDodgeWindows.put(player.getUniqueId(), System.currentTimeMillis() + Math.round(ticks * 50L));
    }

    private void giveResistance(Player player) {
        int ticks = Math.max(1, (int) Math.round(plugin.getConfig().getDouble("defensive-actions.resistance-duration-seconds", 1) * 20));
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, ticks, 2, true, false, true));
    }

    private void playEffect(Player player, String type, Color color) {
        String root = "defensive-actions." + type + ".";
        if (plugin.getConfig().getBoolean(root + "particles-enabled", true)) {
            int count = Math.max(8, plugin.getConfig().getInt(root + "particle-count", 48));
            double radius = Math.max(0.1, plugin.getConfig().getDouble(root + "particle-radius", 1.0));
            Particle.DustOptions dust = new Particle.DustOptions(color, 1.0F);
            if (type.equals("block")) playBlockParticles(player, dust, count, radius);
            else playDodgeParticles(player, dust, count, radius);
        }
        if (!plugin.getConfig().getBoolean(root + "sound-enabled", true)) return;
        String fallbackSound = type.equals("dodge") ? "ENTITY_ENDERMAN_TELEPORT" : "BLOCK_ANVIL_LAND";
        Sound sound = parseSound(plugin.getConfig().getString(root + "sound", fallbackSound));
        player.getWorld().playSound(player.getLocation(), sound,
                (float) plugin.getConfig().getDouble(root + "volume", 1.0),
                (float) plugin.getConfig().getDouble(root + "pitch", type.equals("block") ? 0.4 : 1.2));
    }

    private void playBlockParticles(Player player, Particle.DustOptions dust, int count, double radius) {
        Vector forward = player.getEyeLocation().getDirection().normalize();
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());
        if (right.lengthSquared() < 0.0001) right = new Vector(1, 0, 0);
        right.normalize();
        Vector up = right.clone().crossProduct(forward).normalize();
        var center = player.getEyeLocation().add(forward.clone().multiply(0.85));
        for (int index = 0; index < count; index++) {
            double angle = Math.PI * 2 * index / count;
            double layer = index % 3 == 0 ? 0.35 : index % 3 == 1 ? 0.7 : 1.0;
            Vector offset = right.clone().multiply(Math.cos(angle) * radius * layer)
                    .add(up.clone().multiply(Math.sin(angle) * radius * layer));
            player.getWorld().spawnParticle(Particle.DUST, center.clone().add(offset), 1, 0, 0, 0, 0, dust);
        }
        player.getWorld().spawnParticle(Particle.FLASH, center, 1, 0, 0, 0, 0);
    }

    private void playDodgeParticles(Player player, Particle.DustOptions dust, int count, double radius) {
        var feet = player.getLocation();
        for (int index = 0; index < count; index++) {
            double angle = Math.PI * 2 * index / count;
            double y = 0.15 + (index % 7) * 0.12;
            player.getWorld().spawnParticle(Particle.DUST, feet.clone().add(Math.cos(angle) * radius, y, Math.sin(angle) * radius), 1, 0, 0, 0, 0, dust);
        }
        player.getWorld().spawnParticle(Particle.CLOUD, feet.clone().add(0, 0.5, 0), Math.max(8, count / 3), radius * 0.4, 0.35, radius * 0.4, 0.02);
    }

    private void healDetermination(Player player, double percent) {
        if (!plugin.isFeatureEnabled("determination") || percent == 0) return;
        AttributeInstance maxHealth = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        double maximum = maxHealth == null ? 20 : maxHealth.getValue();
        player.setHealth(Math.max(0.01, Math.min(maximum, player.getHealth() + maximum * percent / 100.0)));
    }

    private void tryCounterAttack(Player defender, EntityDamageByEntityEvent event, RpgStats stats) {
        if (!plugin.isFeatureEnabled("counter-attack") || stats.counterAttack() <= 0) return;
        if (event.getDamager() instanceof Projectile || !(event.getDamager() instanceof LivingEntity target) || target.equals(defender)) return;
        if (ThreadLocalRandom.current().nextDouble(100) >= Math.min(100, stats.counterAttack())) return;
        long damage = Math.max(0, Math.round(Math.max(0, stats.effectiveAttack()) * (1 + stats.cad() / 100.0)));
        if (damage <= 0) return;
        if (plugin.getDummyManager().absorbDamage(target, damage)) {
            playCounterEffects(defender, target, stats, damage);
            return;
        }
        target.setNoDamageTicks(0);
        target.setLastDamage(0);
        target.setMetadata(FSkills.SKILL_DAMAGE_METADATA, new FixedMetadataValue(plugin, true));
        try {
            target.damage(damage, defender);
        } finally {
            target.removeMetadata(FSkills.SKILL_DAMAGE_METADATA, plugin);
        }
        target.setNoDamageTicks(0);
        playCounterEffects(defender, target, stats, damage);
    }

    private void playCounterEffects(Player defender, LivingEntity target, RpgStats stats, long damage) {
        RpgItem item = plugin.getRpgItemManager().findActiveMainHandItem(defender, false);
        if (item != null) {
            particleEffectPlayer.play(target.getLocation(), item.particles());
            soundEffectPlayer.play(target.getLocation(), item.sound());
            var mainHand = defender.getInventory().getItemInMainHand();
            if (slashParticleEffectPlayer.supports(mainHand)) {
                slashParticleEffectPlayer.play(defender, item.slashParticles(), item.slashParticleLength(), stats.attackSpeed(), slashParticleEffectPlayer.isThrust(mainHand, defender));
            }
        }
        Sound sound = parseSound(plugin.getConfig().getString("counter-attack-sound.sound", "ENTITY_WITHER_HURT"));
        target.getWorld().playSound(target.getLocation(), sound,
                (float) plugin.getConfig().getDouble("counter-attack-sound.volume", 1.0),
                (float) plugin.getConfig().getDouble("counter-attack-sound.pitch", 0.8));
        damageDisplay.show(target.getLocation(), java.util.List.of(
                damageDisplay.format("damage-display.physical-format", "damage-display.physical-color", "\uD83D\uDDE1\u2764-{damage}", damage)));
    }

    private Sound parseSound(String value) {
        try {
            return Sound.valueOf(value == null ? "BLOCK_ANVIL_LAND" : value.toUpperCase());
        } catch (IllegalArgumentException exception) {
            return Sound.BLOCK_ANVIL_LAND;
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        armedSneakTimes.remove(uuid);
        lastSneakTimes.remove(uuid);
        dodgeCooldowns.remove(uuid);
        blockCooldowns.remove(uuid);
        skillDodgeWindows.remove(uuid);
    }
}
