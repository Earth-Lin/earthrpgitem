package EarthRPGItem.listeners;

import EarthRPGItem.EarthRPGItem;
import EarthRPGItem.effects.DamageDisplay;
import EarthRPGItem.effects.ParticleEffectPlayer;
import EarthRPGItem.effects.SoundEffectPlayer;
import EarthRPGItem.fskills.FSkills;
import EarthRPGItem.items.RpgItem;
import EarthRPGItem.items.RpgStats;
import EarthRPGItem.items.SoundConfig;
import io.papermc.paper.event.entity.EntityLoadCrossbowEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.Color;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class RpgProjectileListener implements Listener {

    private final EarthRPGItem plugin;
    private final NamespacedKey projectileKey;
    private final DamageDisplay damageDisplay;
    private final ParticleEffectPlayer particleEffectPlayer;
    private final SoundEffectPlayer soundEffectPlayer;
    private final Map<UUID, DrawState> drawStates = new HashMap<>();
    private final Map<UUID, ChargeState> crossbowChargeStates = new HashMap<>();
    private final Map<UUID, ShotState> shotStates = new HashMap<>();
    private final Map<UUID, SkillShotState> skillShotStates = new HashMap<>();
    private final Map<UUID, DamageProbe> pendingDamage = new HashMap<>();

    public RpgProjectileListener(EarthRPGItem plugin) {
        this.plugin = plugin;
        this.projectileKey = new NamespacedKey(plugin, "rpg_projectile");
        this.damageDisplay = new DamageDisplay(plugin);
        this.particleEffectPlayer = new ParticleEffectPlayer(plugin);
        this.soundEffectPlayer = new SoundEffectPlayer(plugin);
    }

    @EventHandler(ignoreCancelled = false)
    public void onStartDraw(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        ItemStack itemStack = event.getItem();
        if (itemStack == null || !isRangedWeapon(itemStack)) itemStack = player.getInventory().getItemInMainHand();
        if (!isRangedWeapon(itemStack)) itemStack = player.getInventory().getItemInOffHand();
        RpgItem item = plugin.getRpgItemManager().findItem(itemStack);
        if (item == null || !isRangedWeapon(itemStack)) return;
        if (isLoadedCrossbow(itemStack)) return;
        stopDraw(player);
        long startedAt = System.currentTimeMillis();
        drawStates.put(player.getUniqueId(), new DrawState(startedAt, item.id()));
        playDrawSound(player, item, startedAt);
    }

    @EventHandler(ignoreCancelled = true)
    public void onLoadCrossbow(EntityLoadCrossbowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        RpgItem item = plugin.getRpgItemManager().findItem(event.getCrossbow());
        if (item == null) return;
        RpgStats stats = plugin.getRpgItemManager().collectActiveStats(player, true);
        DrawResult drawResult = finishDraw(player, item, event.getCrossbow(), stats.exDraw(), true);
        crossbowChargeStates.put(player.getUniqueId(), new ChargeState(item.id(), drawResult.damageMultiplier(), drawResult.stage()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        RpgItem item = plugin.getRpgItemManager().findItem(event.getBow());
        if (item == null || !(event.getProjectile() instanceof Projectile projectile)) return;
        RpgStats stats = plugin.getRpgItemManager().collectActiveStats(player, true);
        DrawResult drawResult = drawMultiplier(player, item, event.getBow(), event.getForce(), stats);
        projectile.getPersistentDataContainer().set(projectileKey, PersistentDataType.INTEGER, 1);
        ShotState shotState = new ShotState(player.getUniqueId(), item, stats, drawResult.damageMultiplier(), event.getHand() == EquipmentSlot.OFF_HAND);
        shotStates.put(projectile.getUniqueId(), shotState);
        plugin.getRpgItemManager().consumeAttackDurability(player, event.getBow(), 1);
        playLaunchSound(player, shotState);
        startTrail(projectile, shotState);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwapHand(PlayerSwapHandItemsEvent event) {
        stopDraw(event.getPlayer());
    }

    @EventHandler
    public void onHeldChange(PlayerItemHeldEvent event) {
        stopDraw(event.getPlayer());
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        stopDraw(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stopDraw(event.getPlayer());
        crossbowChargeStates.remove(event.getPlayer().getUniqueId());
    }

    public void registerSkillProjectile(Projectile projectile, Player player, RpgItem item, RpgStats stats, double weaponPercent, boolean damageEnabled) {
        projectile.getPersistentDataContainer().set(projectileKey, PersistentDataType.INTEGER, 1);
        skillShotStates.put(projectile.getUniqueId(), new SkillShotState(player, item, stats, Math.max(0, weaponPercent) / 100.0, damageEnabled));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onNativeProjectileDamageMonitor(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (!(event.getDamager() instanceof LivingEntity attacker)) return;
        DamageProbe probe = pendingDamage.get(target.getUniqueId());
        if (probe != null && probe.attackerId.equals(attacker.getUniqueId())) {
            probe.cancelled = event.isCancelled();
            if (event.getFinalDamage() <= 0) probe.blocked = true;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onProjectileDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Projectile projectile)) return;
        if (event.isCancelled()) {
            if (isRpgProjectile(projectile)) {
                clearProjectileState(projectile.getUniqueId());
                projectile.remove();
            }
            return;
        }
        if (event.getEntity() instanceof Player player
                && (player.getGameMode() == org.bukkit.GameMode.CREATIVE || player.getGameMode() == org.bukkit.GameMode.SPECTATOR)) {
            if (isRpgProjectile(projectile)) {
                event.setCancelled(true);
                clearProjectileState(projectile.getUniqueId());
                projectile.remove();
            }
            return;
        }
        SkillShotState skillShot = skillShotStates.remove(projectile.getUniqueId());
        if (skillShot != null && event.getEntity() instanceof LivingEntity skillTarget) {
            if (!skillShot.damageEnabled()) {
                event.setCancelled(true);
                projectile.remove();
            } else {
                applySkillProjectileDamage(event, projectile, skillTarget, skillShot);
            }
            return;
        }
        ShotState shotState = shotStates.remove(projectile.getUniqueId());
        if (shotState == null || !(event.getEntity() instanceof LivingEntity target)) return;
        if (shotState.offHand()) {
            event.setCancelled(true);
            projectile.remove();
            return;
        }
        Player attacker = plugin.getServer().getPlayer(shotState.playerId());
        if (attacker == null) return;
        RpgStats defenderStats = plugin.getRpgItemManager().collectActiveStats(target, true);
        if (tryProjectileParry(event, target, defenderStats)) {
            projectile.remove();
            return;
        }
        ProjectileDamageResult result = calculateProjectileDamage(event, projectile, attacker, target, shotState, defenderStats);
        target.setNoDamageTicks(0);
        event.setCancelled(true);
        boolean applied = applyAttributedProjectileDamage(target, attacker, result.totalDamage());
        if (applied) {
            damageDisplay.show(target.getLocation(), result.lines());
            playProjectileHit(hitLocation(target), shotState);
            applyAttackRange(attacker, target, result, shotState);
        }
        target.setNoDamageTicks(0);
        projectile.remove();
    }

    @EventHandler(ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        SkillShotState skillShot = skillShotStates.remove(event.getEntity().getUniqueId());
        if (skillShot != null) {
            if (event.getHitEntity() instanceof LivingEntity target) applySkillProjectileHit(event.getEntity(), target, skillShot);
            else event.getEntity().remove();
            return;
        }
        ShotState shotState = shotStates.get(event.getEntity().getUniqueId());
        if (shotState == null) return;
        if (event.getHitBlock() != null) {
            shotStates.remove(event.getEntity().getUniqueId());
            playProjectileHit(event.getEntity().getLocation(), shotState);
        }
    }

    private void applySkillProjectileHit(Projectile projectile, LivingEntity target, SkillShotState skillShot) {
        if (target instanceof Player player
                && (player.getGameMode() == org.bukkit.GameMode.CREATIVE || player.getGameMode() == org.bukkit.GameMode.SPECTATOR)) {
            projectile.remove();
            return;
        }
        Player attacker = skillShot.player();
        if (!skillShot.damageEnabled()) {
            projectile.remove();
            return;
        }
        RpgStats defenderStats = plugin.getRpgItemManager().collectActiveStats(target, true);
        target.setNoDamageTicks(0);
        ShotState state = new ShotState(attacker.getUniqueId(), skillShot.item(), skillShot.stats(), skillShot.weaponMultiplier(), false);
        // Skill projectiles are spawned with arrow.setDamage(0), so there is no vanilla damage to read
        // the weapon from. Synthesize the same base the melee skill path uses (held weapon + RPG
        // Attack). Using effectiveAttack() alone meant a bow carrying no RPG Attack stat contributed
        // nothing, and the skill shot collapsed to its fixed damage.
        double base = plugin.getFSkills().weaponBaseDamage(attacker, skillShot.stats());
        ProjectileDamageResult result = calculateProjectileDamage(base, base, attacker, target, state, defenderStats);
        applyAttributedProjectileDamage(target, attacker, result.totalDamage());
        damageDisplay.show(target.getLocation(), result.lines());
        playProjectileHit(hitLocation(target), state);
        projectile.remove();
        target.setNoDamageTicks(0);
    }

    public boolean isRpgProjectile(Projectile projectile) {
        return projectile.getPersistentDataContainer().has(projectileKey, PersistentDataType.INTEGER);
    }

    private void applySkillProjectileDamage(EntityDamageByEntityEvent event, Projectile projectile, LivingEntity target, SkillShotState skillShot) {
        Player attacker = skillShot.player();
        RpgStats defenderStats = plugin.getRpgItemManager().collectActiveStats(target, true);
        target.setNoDamageTicks(0);
        event.setCancelled(true);
        ShotState state = new ShotState(attacker.getUniqueId(), skillShot.item(), skillShot.stats(), skillShot.weaponMultiplier(), false);
        double weaponBase = plugin.getFSkills().weaponBaseDamage(attacker, skillShot.stats());
        ProjectileDamageResult result = calculateProjectileDamage(weaponBase, Math.max(weaponBase, Math.max(0, event.getDamage())), attacker, target, state, defenderStats);
        applyAttributedProjectileDamage(target, attacker, result.totalDamage());
        damageDisplay.show(target.getLocation(), result.lines());
        playProjectileHit(hitLocation(target), state);
        projectile.remove();
        target.setNoDamageTicks(0);
    }

    private ProjectileDamageResult calculateProjectileDamage(EntityDamageByEntityEvent event, Projectile projectile, Player attacker, LivingEntity target, ShotState shotState, RpgStats defenderStats) {
        return calculateProjectileDamage(event.getDamage(), event.getFinalDamage(), attacker, target, shotState, defenderStats);
    }

    private ProjectileDamageResult calculateProjectileDamage(double vanillaRaw, double vanillaFinal, Player attacker, LivingEntity target, ShotState shotState, RpgStats defenderStats) {
        RpgStats stats = shotState.stats();
        double armorPen = Math.min(100, Math.max(0, stats.armorPen())) / 100.0;
        double penetratedIncoming = Math.max(0, vanillaFinal);
        boolean backstrike = isBackstrike(attacker, target);
        boolean assassin = isAssassinHit(attacker, target) || backstrike;
        CriticalRoll bowCritical = plugin.isFeatureEnabled("physical-crit")
                ? rollCritical(stats.critChance(), stats.critDamage(), defenderStats.critArmor(), defenderStats.critArmorPercent(), armorPen)
                : CriticalRoll.none();
        CriticalRoll magicCritical = plugin.isFeatureEnabled("magic-crit") && stats.magicDamage() != 0
                ? rollCritical(stats.critChance(), stats.critDamage(), defenderStats.critArmor(), defenderStats.critArmorPercent(), armorPen)
                : CriticalRoll.none();
        double backstrikePercent = Math.max(0, stats.backstrikeDamagePercent()) / 100.0;
        double bowDamageMultiplier = 1 + Math.max(0, stats.bowDamagePercent()) / 100.0;
        double projectilePanelDamage = Math.max(0, (vanillaRaw + stats.bowDamage()) * bowDamageMultiplier);
        double bonusFlat = (backstrike ? stats.backstrikeDamage() * (1 + backstrikePercent) + projectilePanelDamage * backstrikePercent : 0) + (assassin ? stats.assDamage() : 0);
        double scaleDamage = attackerMaxHealth(attacker) * Math.max(0, stats.pctDamage()) / 100.0;
        double proportionalDamage = targetMaxHealth(target) * Math.max(0, stats.pstDamage()) / 100.0;
        double percentPhysical = scaleDamage + proportionalDamage;
        double bowElementBase = (projectilePanelDamage + percentPhysical + bonusFlat + rawCriticalDamage(stats, bowCritical)) * shotState.damageMultiplier();
        double bowDamage = ((penetratedIncoming + stats.bowDamage()) * bowDamageMultiplier + percentPhysical + bonusFlat + bowCritical.damage() + (bowCritical.critical() ? ((penetratedIncoming + stats.bowDamage()) * bowDamageMultiplier + percentPhysical + bonusFlat) * Math.max(0, stats.critDamagePercent()) / 100.0 : 0)) * shotState.damageMultiplier();
        double backstrikePart = backstrike ? Math.max(0, stats.backstrikeDamage() * (1 + backstrikePercent) + projectilePanelDamage * backstrikePercent) * shotState.damageMultiplier() : 0;
        double assassinPart = assassin ? Math.max(0, stats.assDamage()) * shotState.damageMultiplier() : 0;
        bowDamage = applyArmorLayer(bowDamage, defenderStats.envArmor(), defenderStats.envArmorPercent(), 0);
        bowDamage = applyArmorLayer(bowDamage, defenderStats.bowArmor(), defenderStats.bowArmorPercent(), armorPen);
        if (assassin && stats.assDamagePercent() != 0) {
            double multiplier = 1 + Math.max(0, stats.assDamagePercent()) / 100.0;
            bowDamage *= multiplier;
            bowElementBase *= multiplier;
        }
        bowDamage = applyResistance(Math.max(0, bowDamage), defenderStats.resistance());
        double assassinPercentPart = assassin && stats.assDamagePercent() > 0
                ? bowDamage * stats.assDamagePercent() / (100.0 + stats.assDamagePercent()) : 0;
        backstrikePart = applyResistance(applyArmorLayer(applyArmorLayer(backstrikePart, defenderStats.envArmor(), defenderStats.envArmorPercent(), 0), defenderStats.bowArmor(), defenderStats.bowArmorPercent(), armorPen), defenderStats.resistance());
        assassinPart = applyResistance(applyArmorLayer(applyArmorLayer(assassinPart, defenderStats.envArmor(), defenderStats.envArmorPercent(), 0), defenderStats.bowArmor(), defenderStats.bowArmorPercent(), armorPen), defenderStats.resistance()) + assassinPercentPart;
        bowDamage = Math.max(0, bowDamage - backstrikePart - assassinPart);
        double magic = 0;
        double magicElementBase = 0;
        if (stats.magicDamage() != 0) {
            magicElementBase = (stats.effectiveMagicDamage() + rawCriticalDamage(stats, magicCritical)) * shotState.damageMultiplier();
            magic = (stats.effectiveMagicDamage() + magicCritical.damage()) * shotState.damageMultiplier();
            magic = applyArmorLayer(magic, defenderStats.envArmor(), defenderStats.envArmorPercent(), 0);
            magic = applyArmorLayer(magic, defenderStats.magicArmor(), defenderStats.magicArmorPercent(), armorPen);
            magic = applyArmorLayer(magic, defenderStats.bowArmor(), defenderStats.bowArmorPercent(), armorPen);
            if (assassin && stats.assDamagePercent() != 0) {
                double multiplier = 1 + Math.max(0, stats.assDamagePercent()) / 100.0;
                magic *= multiplier;
                magicElementBase *= multiplier;
            }
            magic = applyResistance(Math.max(0, magic), defenderStats.resistance());
        }
        long bowRounded = Math.max(0, Math.round(bowDamage));
        long backstrikeRounded = Math.max(0, Math.round(backstrikePart));
        long assassinRounded = Math.max(0, Math.round(assassinPart));
        long magicRounded = Math.max(0, Math.round(magic));
        long trueRounded = Math.max(0, Math.round(applyNegativeResistance(stats.trueDamage(), defenderStats.resistance())));
        double elementBase = Math.max(0, bowElementBase) + Math.max(0, magicElementBase) + Math.max(0, stats.trueDamage());
        long iceRounded = elementDamage(stats.iceDamage(), stats.iceDamagePercent(), elementBase, defenderStats.iceArmor(), defenderStats.iceArmorPercent(), defenderStats.resistance(), armorPen);
        long fireRounded = elementDamage(stats.fireDamage(), stats.fireDamagePercent(), elementBase, defenderStats.fireArmor(), defenderStats.fireArmorPercent(), defenderStats.resistance(), armorPen);
        long lightRounded = elementDamage(stats.lightDamage(), stats.lightDamagePercent(), elementBase, defenderStats.lightArmor(), defenderStats.lightArmorPercent(), defenderStats.resistance(), armorPen);
        long darkRounded = elementDamage(stats.darkDamage(), stats.darkDamagePercent(), elementBase, defenderStats.darkArmor(), defenderStats.darkArmorPercent(), defenderStats.resistance(), armorPen);
        List<String> lines = new ArrayList<>();
        if (bowRounded > 0) {
            lines.add(damageDisplay.format(
                    bowCritical.critical() ? "damage-display.bow-crit-format" : "damage-display.bow-format",
                    bowCritical.critical() ? "damage-display.bow-crit-color" : "damage-display.bow-color",
                    bowCritical.critical() ? "\uD83C\uDFF9\u2B50\u2764-{damage}" : "\uD83C\uDFF9\u2764-{damage}",
                    bowRounded));
        }
        if (backstrikeRounded > 0) lines.add(damageDisplay.format("damage-display.backstrike-format", "damage-display.backstrike-color", "&e🗡背击-{damage}", backstrikeRounded));
        if (assassinRounded > 0) lines.add(damageDisplay.format("damage-display.assassin-format", "damage-display.assassin-color", "&c☠刺客-{damage}", assassinRounded));
        if (magicRounded > 0) {
            lines.add(damageDisplay.format(
                    magicCritical.critical() ? "damage-display.magic-crit-format" : "damage-display.magic-format",
                    magicCritical.critical() ? "damage-display.magic-crit-color" : "damage-display.magic-color",
                    magicCritical.critical() ? "\uD83D\uDD2F\u2764-{damage}" : "\u2721\u2764-{damage}",
                    magicRounded));
        }
        if (trueRounded > 0) lines.add(damageDisplay.format("damage-display.true-format", "damage-display.true-color", "\u2620\u2764-{damage}", trueRounded));
        addElementLine(lines, iceRounded, "damage-display.ice-format", "damage-display.ice-color", "\uD83E\uDDCA\u2764-{damage}");
        addElementLine(lines, fireRounded, "damage-display.fire-format", "damage-display.fire-color", "\uD83D\uDD25\u2764-{damage}");
        addElementLine(lines, darkRounded, "damage-display.dark-format", "damage-display.dark-color", "\uD83C\uDF18\u2764-{damage}");
        addElementLine(lines, lightRounded, "damage-display.light-format", "damage-display.light-color", "\u2600\u2764-{damage}");
        return new ProjectileDamageResult(bowRounded + backstrikeRounded + assassinRounded + magicRounded + trueRounded + iceRounded + fireRounded + lightRounded + darkRounded, lines, vanillaFinal);
    }

    private void playDrawSound(Player player, RpgItem item, long startedAt) {
        if (!plugin.isFeatureEnabled("projectile-sounds") || !plugin.getConfig().getBoolean("projectile-draw-sound.enabled", true)) return;
        if (item.exDraw() <= 0) return;
        int[] elapsedTicks = {0};
        plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
            DrawState state = drawStates.get(player.getUniqueId());
            elapsedTicks[0] += 4;
            if (state == null || state.startedAt() != startedAt || (elapsedTicks[0] > 4 && !isActivelyDrawing(player, item))) {
                if (state != null && state.startedAt() == startedAt && !isActivelyDrawing(player, item)) stopDraw(player);
                task.cancel();
                return;
            }
            double elapsed = Math.max(0, (System.currentTimeMillis() - startedAt) / 1000.0);
            double high = plugin.getConfig().getDouble("projectile-draw-sound.high-pitch", 1.4);
            double low = plugin.getConfig().getDouble("projectile-draw-sound.low-pitch", 0.6);
            int stage = extraDrawStage(elapsed, item.exDraw(), true);
            double stageStep = plugin.getConfig().getDouble("projectile-draw-sound.stage-pitch-step", 0.08);
            float pitch = (float) Math.min(2.0, low + Math.min(1.0, elapsed) * (high - low) + stage * stageStep);
            Sound sound = parseSound(plugin.getConfig().getString("projectile-draw-sound.sound", "BLOCK_NOTE_BLOCK_PLING"));
            player.getWorld().playSound(player.getLocation(), sound, (float) plugin.getConfig().getDouble("projectile-draw-sound.volume", 0.35), pitch);
        }, 0L, 4L);
    }

    private void startTrail(Projectile projectile, ShotState shotState) {
        boolean particlesEnabled = plugin.isFeatureEnabled("projectile-trail-particles") && plugin.getConfig().getBoolean("projectile-trail-particles.enabled", true);
        if (!particlesEnabled) return;
        plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
            if (projectile.isDead() || projectile.isOnGround() || !shotStates.containsKey(projectile.getUniqueId())) {
                task.cancel();
                return;
            }
            if (particlesEnabled) particleEffectPlayer.playTrail(projectile.getLocation(), shotState.item().projectileTrailParticles());
        }, 1L, 1L);
    }

    private void playLaunchSound(Player player, ShotState shotState) {
        if (!plugin.isFeatureEnabled("projectile-sounds") || !plugin.getConfig().getBoolean("projectile-launch-sound.enabled", true)) return;
        SoundConfig itemSound = shotState.item().projectileTrailSound();
        if (itemSound.enabled()) {
            playConfiguredSound(player.getLocation(), itemSound);
            return;
        }
        Sound sound = parseSound(plugin.getConfig().getString("projectile-launch-sound.sound", "ENTITY_FIREWORK_ROCKET_LAUNCH"));
        player.getWorld().playSound(player.getLocation(), sound,
                (float) plugin.getConfig().getDouble("projectile-launch-sound.volume", 0.7),
                (float) plugin.getConfig().getDouble("projectile-launch-sound.pitch", 1.0));
    }

    private void playProjectileHit(Location location, ShotState shotState) {
        if (!plugin.getConfig().getBoolean("projectile-hit-feedback.allow", false)) return;
        if (plugin.isFeatureEnabled("projectile-hit-particles") && plugin.getConfig().getBoolean("projectile-hit-particles.enabled", false)) {
            particleEffectPlayer.playBurst(location, shotState.item().projectileHitParticles());
        }
        if (plugin.isFeatureEnabled("projectile-sounds") && plugin.getConfig().getBoolean("projectile-hit-sound.enabled", false)) {
            if (shotState.item().projectileHitSound().enabled()) playConfiguredSound(location, shotState.item().projectileHitSound());
        }
    }

    private void playConfiguredSound(Location location, SoundConfig config) {
        location.getWorld().playSound(location, parseSound(config.sound()), config.volume(), config.pitch());
    }

    private Location hitLocation(LivingEntity target) {
        return target.getLocation().add(0, Math.max(0.1, target.getHeight() * 0.55), 0);
    }

    private void clearProjectileState(UUID projectileId) {
        shotStates.remove(projectileId);
        skillShotStates.remove(projectileId);
    }

    private boolean applyAttributedProjectileDamage(LivingEntity target, Player attacker, double damage) {
        double exactDamage = Math.max(0, Math.round(damage));
        if (exactDamage <= 0 || target.isDead()) return false;
        if (target instanceof Player player
                && (player.getGameMode() == org.bukkit.GameMode.CREATIVE || player.getGameMode() == org.bukkit.GameMode.SPECTATOR)) return false;
        if (plugin.getDummyManager().absorbDamage(target, exactDamage)) return true;
        double healthBefore = target.getHealth();
        double absorptionBefore = target.getAbsorptionAmount();
        DamageProbe probe = new DamageProbe(attacker.getUniqueId());
        pendingDamage.put(target.getUniqueId(), probe);
        target.setNoDamageTicks(0);
        target.setLastDamage(0);
        target.setMetadata(FSkills.SKILL_DAMAGE_METADATA, new FixedMetadataValue(plugin, true));
        target.setMetadata("earthrpgitem_projectile_attributed", new FixedMetadataValue(plugin, true));
        try {
            target.damage(exactDamage, attacker);
        } finally {
            target.removeMetadata(FSkills.SKILL_DAMAGE_METADATA, plugin);
            target.removeMetadata("earthrpgitem_projectile_attributed", plugin);
            pendingDamage.remove(target.getUniqueId());
        }
        if (probe.cancelled || probe.blocked) return false;
        if (target.isInvulnerable()) return false;
        double applied = (healthBefore - target.getHealth()) + (absorptionBefore - target.getAbsorptionAmount());
        double missing = exactDamage - Math.max(0, applied);
        if (missing > 0.001 && !target.isDead()) {
            double absorbed = Math.min(target.getAbsorptionAmount(), missing);
            if (absorbed > 0) {
                target.setAbsorptionAmount(target.getAbsorptionAmount() - absorbed);
                missing -= absorbed;
            }
            if (missing > 0.001) target.setHealth(Math.max(0, target.getHealth() - missing));
        }
        target.setNoDamageTicks(0);
        return true;
    }

    private boolean tryProjectileParry(EntityDamageByEntityEvent event, LivingEntity target, RpgStats defenderStats) {
        if (!(target instanceof Player defender) || !plugin.isFeatureEnabled("parry") || defenderStats.parry() <= 0) return false;
        ItemStack mainHand = defender.getInventory().getItemInMainHand();
        if (mainHand != null && !mainHand.getType().isAir() && defender.hasCooldown(mainHand.getType())) return false;
        if (ThreadLocalRandom.current().nextDouble(100) >= Math.min(100, defenderStats.parry())) return false;
        event.setCancelled(true);
        target.setNoDamageTicks(0);
        playParryEffect(defender);
        long cooldownTicks = Math.max(1, (int) Math.ceil(attackCooldownMillis(defenderStats.attackSpeed() - 50) / 50.0));
        if (mainHand != null && !mainHand.getType().isAir()) defender.setCooldown(mainHand.getType(), (int) cooldownTicks);
        return true;
    }

    private void playParryEffect(Player defender) {
        if (plugin.isFeatureEnabled("parry-particles") && plugin.getConfig().getBoolean("parry-particles.enabled", true)) {
            var dust = new Particle.DustOptions(Color.fromRGB(255, 215, 0), 1.2F);
            var center = defender.getEyeLocation().add(defender.getLocation().getDirection().normalize().multiply(0.8));
            int count = Math.max(8, plugin.getConfig().getInt("parry-particles.count", 48));
            double radius = Math.max(0.1, plugin.getConfig().getDouble("parry-particles.radius", 0.8));
            center.getWorld().spawnParticle(Particle.DUST, center, count, radius, radius * 0.6, radius, 0, dust);
        }
        if (plugin.getConfig().getBoolean("parry-sound.enabled", true)) {
            Sound sound = parseSound(plugin.getConfig().getString("parry-sound.sound", "BLOCK_ANVIL_LAND"));
            defender.getWorld().playSound(defender.getLocation(), sound,
                    (float) plugin.getConfig().getDouble("parry-sound.volume", 1.0),
                    (float) plugin.getConfig().getDouble("parry-sound.pitch", 0.5));
        }
    }

    private boolean isRangedWeapon(ItemStack itemStack) {
        return itemStack != null && (itemStack.getType() == org.bukkit.Material.BOW || itemStack.getType() == org.bukkit.Material.CROSSBOW);
    }

    private boolean isLoadedCrossbow(ItemStack itemStack) {
        return itemStack != null && itemStack.getItemMeta() instanceof CrossbowMeta meta && meta.hasChargedProjectiles();
    }

    private DrawResult drawMultiplier(Player player, RpgItem item, ItemStack weapon, float force, RpgStats stats) {
        if (weapon != null && weapon.getType() == org.bukkit.Material.CROSSBOW) {
            ChargeState chargeState = crossbowChargeStates.remove(player.getUniqueId());
            if (chargeState != null && chargeState.itemId().equals(item.id())) {
                stopDraw(player);
                return new DrawResult(chargeState.damageMultiplier(), chargeState.stage());
            }
        }
        double baseForce = Math.max(0.2, Math.min(1.0, force));
        return finishDraw(player, item, weapon, stats.exDraw(), baseForce, baseForce >= 0.99);
    }

    private DrawResult finishDraw(Player player, RpgItem item, ItemStack weapon, double exDraw, boolean fullyDrawn) {
        return finishDraw(player, item, weapon, exDraw, weapon != null && weapon.getType() == org.bukkit.Material.CROSSBOW ? 1.0 : 0.2, fullyDrawn);
    }

    private DrawResult finishDraw(Player player, RpgItem item, ItemStack weapon, double exDraw, double baseForce, boolean fullyDrawn) {
        DrawState drawState = drawStates.remove(player.getUniqueId());
        double drawSeconds = 0;
        if (drawState != null && drawState.itemId().equals(item.id())) {
            drawSeconds = Math.max(0, (System.currentTimeMillis() - drawState.startedAt()) / 1000.0);
        } else if (player.isHandRaised()) {
            drawSeconds = Math.max(0, player.getHandRaisedTime() / 20.0);
        }
        int stage = extraDrawStage(drawSeconds, exDraw, fullyDrawn);
        return new DrawResult(baseForce * (1.0 + stage * 0.15), stage);
    }

    private int extraDrawStage(double drawSeconds, double exDraw, boolean fullyDrawn) {
        if (!fullyDrawn || exDraw <= 0 || drawSeconds < 1.0) return 0;
        return (int) Math.max(0, Math.min(Math.floor(exDraw), Math.floor(drawSeconds - 1.0)));
    }

    private void stopDraw(Player player) {
        drawStates.remove(player.getUniqueId());
    }

    private boolean isActivelyDrawing(Player player, RpgItem item) {
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ItemStack offHand = player.getInventory().getItemInOffHand();
        RpgItem mainItem = plugin.getRpgItemManager().findItem(mainHand);
        RpgItem offItem = plugin.getRpgItemManager().findItem(offHand);
        return player.isHandRaised() && ((mainItem != null && mainItem.id().equals(item.id()) && isRangedWeapon(mainHand))
                || (offItem != null && offItem.id().equals(item.id()) && isRangedWeapon(offHand)));
    }

    private void applyAttackRange(Player attacker, LivingEntity originalTarget, ProjectileDamageResult result, ShotState shotState) {
        double range = Math.round(shotState.stats().attackRange() * 100.0) / 100.0;
        if (range <= 0 || result.totalDamage() <= 0) return;
        for (LivingEntity target : originalTarget.getLocation().getNearbyLivingEntities(range)) {
            if (target.equals(originalTarget) || target.equals(attacker) || target.isDead()) continue;
            if (applyAttributedProjectileDamage(target, attacker, result.totalDamage())) {
                damageDisplay.show(target.getLocation(), result.lines());
                playProjectileHit(hitLocation(target), shotState);
            }
        }
    }

    private double attackerMaxHealth(Player attacker) {
        AttributeInstance attribute = attacker.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        return attribute == null ? 20 : attribute.getValue();
    }

    private double targetMaxHealth(LivingEntity target) {
        AttributeInstance attribute = target.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        return attribute == null ? 20 : attribute.getValue();
    }

    private boolean isBackstrike(Player attacker, LivingEntity target) {
        Vector targetToAttacker = attacker.getLocation().toVector().subtract(target.getLocation().toVector()).normalize();
        return target.getLocation().getDirection().normalize().dot(targetToAttacker) < -0.45;
    }

    private boolean isAssassinHit(Player attacker, LivingEntity target) {
        if (target instanceof Mob mob) return mob.getTarget() == null || !mob.getTarget().getUniqueId().equals(attacker.getUniqueId());
        return !(target instanceof Player);
    }

    private CriticalRoll rollCritical(double critChance, double critDamage, double critArmor, double critArmorPercent, double armorPen) {
        double overflowCritDamage = Math.max(0, critChance - 100) * 10;
        double cappedCritChance = Math.min(100, Math.max(0, critChance));
        boolean critical = cappedCritChance > 0 && ThreadLocalRandom.current().nextDouble(100) < cappedCritChance;
        double damage = critical ? applyArmorLayer(critDamage + overflowCritDamage, critArmor, critArmorPercent, armorPen) : 0;
        return new CriticalRoll(critical, damage);
    }

    private double rawCriticalDamage(RpgStats stats, CriticalRoll roll) {
        return roll.critical() ? Math.max(0, stats.critDamage() + Math.max(0, stats.critChance() - 100) * 10) : 0;
    }

    private double applyResistance(double damage, double resistance) {
        return damage * (1 - Math.min(100, resistance) / 100.0);
    }

    private double applyNegativeResistance(double damage, double resistance) {
        return resistance < 0 ? applyResistance(damage, resistance) : damage;
    }

    private double applyArmorLayer(double damage, double flatArmor, double armorPercent, double armorPen) {
        double penetratedFlat = Math.max(0, flatArmor * (1 - armorPen));
        double penetratedPercent = Math.max(0, armorPercent * (1 - armorPen));
        double afterFlat = Math.max(0, damage - penetratedFlat);
        return afterFlat * (1 - Math.min(100, penetratedPercent) / 100.0);
    }

    private long elementDamage(double flat, double percent, double elementBase, double armor, double armorPercent, double resistance, double armorPen) {
        double raw = Math.max(0, flat) + Math.max(0, percent) / 100.0 * elementBase;
        double penetratedArmor = Math.max(0, armor * (1 - armorPen));
        double penetratedPercent = Math.max(0, armorPercent * (1 - armorPen));
        double damage = Math.max(0, raw - penetratedArmor);
        damage *= 1 - Math.min(100, penetratedPercent) / 100.0;
        damage = applyResistance(damage, resistance);
        return Math.max(0, Math.round(damage));
    }

    private void addElementLine(List<String> lines, long damage, String formatPath, String colorPath, String fallback) {
        if (damage > 0) lines.add(damageDisplay.format(formatPath, colorPath, fallback, damage));
    }

    private long attackCooldownMillis(double attackSpeed) {
        if (!plugin.isFeatureEnabled("attack-speed")) return 1000;
        if (attackSpeed >= 0) return Math.round(1000 - Math.min(100, attackSpeed) / 100.0 * 950);
        return Math.round(1000 + Math.min(100, Math.abs(attackSpeed)) / 100.0 * 9000);
    }

    private Sound parseSound(String value) {
        try {
            return Sound.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return Sound.BLOCK_NOTE_BLOCK_PLING;
        }
    }

    private static final class DamageProbe {
        private final UUID attackerId;
        private boolean cancelled;
        private boolean blocked;

        private DamageProbe(UUID attackerId) {
            this.attackerId = attackerId;
        }
    }

    private record DrawState(long startedAt, String itemId) { }
    private record ChargeState(String itemId, double damageMultiplier, int stage) { }
    private record DrawResult(double damageMultiplier, int stage) { }
    private record SkillShotState(Player player, RpgItem item, RpgStats stats, double weaponMultiplier, boolean damageEnabled) { }
    private record ShotState(UUID playerId, RpgItem item, RpgStats stats, double damageMultiplier, boolean offHand) { }
    private record CriticalRoll(boolean critical, double damage) { static CriticalRoll none() { return new CriticalRoll(false, 0); } }
    private record ProjectileDamageResult(double totalDamage, List<String> lines, double vanillaFinal) { }
}
