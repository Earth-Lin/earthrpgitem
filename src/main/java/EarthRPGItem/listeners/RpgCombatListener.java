package EarthRPGItem.listeners;

import EarthRPGItem.EarthRPGItem;
import EarthRPGItem.effects.DamageDisplay;
import EarthRPGItem.effects.ParticleEffectPlayer;
import EarthRPGItem.effects.SlashParticleEffectPlayer;
import EarthRPGItem.effects.SoundEffectPlayer;
import EarthRPGItem.fskills.FSkills;
import EarthRPGItem.items.HitBuffConfig;
import EarthRPGItem.items.RpgItem;
import EarthRPGItem.items.RpgStats;
import org.bukkit.Color;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class RpgCombatListener implements Listener {

    private final EarthRPGItem plugin;
    private final DamageDisplay damageDisplay;
    private final ParticleEffectPlayer particleEffectPlayer;
    private final SoundEffectPlayer soundEffectPlayer;
    private final SlashParticleEffectPlayer slashParticleEffectPlayer;
    private final Map<UUID, Long> swingGraceTimes = new HashMap<>();
    private final Set<UUID> pluginDamageTargets = new HashSet<>();
    private final Map<UUID, DamageProbe> pendingDamage = new HashMap<>();

    public RpgCombatListener(EarthRPGItem plugin) {
        this.plugin = plugin;
        this.damageDisplay = new DamageDisplay(plugin);
        this.particleEffectPlayer = new ParticleEffectPlayer(plugin);
        this.soundEffectPlayer = new SoundEffectPlayer(plugin);
        this.slashParticleEffectPlayer = new SlashParticleEffectPlayer(plugin);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        swingGraceTimes.remove(uuid);
        plugin.getAttackCooldownManager().clear(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onNativeDamageMonitor(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (!(event.getDamager() instanceof LivingEntity attacker)) return;
        DamageProbe probe = pendingDamage.get(target.getUniqueId());
        if (probe != null && probe.attackerId.equals(attacker.getUniqueId())) {
            probe.cancelled = event.isCancelled();
            // A protection plugin that zeroes the damage without cancelling must also win.
            if (event.getFinalDamage() <= 0) probe.blocked = true;
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerAnimation(PlayerAnimationEvent event) {
        Player player = event.getPlayer();
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        RpgItem item = plugin.getRpgItemManager().findActiveMainHandItem(player, false);
        if (item == null || (item.lSkill() != null && item.lSkill().enabled()) || !slashParticleEffectPlayer.supports(mainHand) || !isAttackReady(player)) return;
        // A left-click sends an arm swing as well as the attack/interact packet. When the player has
        // an L/SL skill bound in the panel, the swing must not charge the shared attack cooldown,
        // otherwise the skill handler that runs for the same input sees a cooldown it never set and
        // refuses to cast - the skill appeared to fire only intermittently.
        if (plugin.getFSkills().hasBoundLeftClickSkill(player)) return;
        RpgStats stats = plugin.getRpgItemManager().collectActiveStats(player, false);
        setItemCooldown(player, stats.attackSpeed());
        swingGraceTimes.put(player.getUniqueId(), System.currentTimeMillis() + 300L);
        slashParticleEffectPlayer.play(player, item.slashParticles(), item.slashParticleLength(), stats.attackSpeed(), slashParticleEffectPlayer.isThrust(mainHand, player));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getEntity().hasMetadata(FSkills.SKILL_DAMAGE_METADATA)) return;
        if (event.getEntity() instanceof LivingEntity attributedTarget && attributedTarget.hasMetadata("earthrpgitem_projectile_attributed")) return;
        if (event.getEntity() instanceof LivingEntity livingEntity && pluginDamageTargets.contains(livingEntity.getUniqueId())) return;
        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) { event.setCancelled(true); return; }
        if (!plugin.isFeatureEnabled("rpgitems")) return;
        if (event.getDamager() instanceof Projectile projectile && projectile.getPersistentDataContainer().has(new NamespacedKey(plugin, "rpg_projectile"), PersistentDataType.INTEGER)) return;

        LivingEntity attackerEntity = resolveAttackerEntity(event);
        Player attacker = attackerEntity instanceof Player player ? player : null;
        LivingEntity counterTarget = event.getDamager() instanceof LivingEntity livingDamager ? livingDamager : null;
        Player defenderPlayer = event.getEntity() instanceof Player player ? player : null;
        RpgStats defenderStats = event.getEntity() instanceof LivingEntity defenderLiving ? plugin.getRpgItemManager().collectActiveStats(defenderLiving, true) : RpgStats.empty();
        if (tryParry(event, defenderPlayer, defenderStats, counterTarget)) return;
        if (rollChance(defenderStats.evasion())) { event.setCancelled(true); return; }

        if (attackerEntity == null) {
            applyDefensiveOnlyDamage(event, defenderStats);
            return;
        }

        boolean directWeaponAttack = event.getDamager() instanceof LivingEntity && !(event.getDamager() instanceof Projectile);
        ItemStack mainHand = mainHand(attackerEntity);
        boolean holdingRpgItem = plugin.getRpgItemManager().isRpgItem(mainHand);
        RpgItem attackingItem = attacker instanceof Player player ? plugin.getRpgItemManager().findActiveMainHandItem(player, true) : plugin.getRpgItemManager().findItem(mainHand);
        boolean loreWeaponAttack = directWeaponAttack && attackingItem == null
                && plugin.getRpgItemManager().collectLoreStats(mainHand).hasOffensiveStats();
        if (attacker != null && directWeaponAttack && attackingItem != null && attackingItem.lSkill() != null && attackingItem.lSkill().enabled()) {
            event.setCancelled(true);
            return;
        }
        boolean consumedSwingGrace = false;
        if (attacker != null && directWeaponAttack && (holdingRpgItem || loreWeaponAttack)
                && (attackingItem != null || loreWeaponAttack) && !isAttackReady(attacker)) {
            consumedSwingGrace = consumeSwingGrace(attacker);
            if (!consumedSwingGrace) { event.setCancelled(true); return; }
        }
        if (directWeaponAttack && attackingItem == null && !loreWeaponAttack) {
            applyDefensiveOnlyDamage(event, defenderStats);
            return;
        }

        RpgStats attackerStats = plugin.getRpgItemManager().collectActiveStats(attackerEntity, true);
        if (directWeaponAttack && isRangedWeapon(mainHand)) attackerStats = rangedMeleeStats(attackerStats);
        boolean playerMagicSource = attacker != null && !directWeaponAttack && isMagicDamageCause(event.getCause());
        if (!directWeaponAttack && attackerStats.magicDamage() == 0 && !playerMagicSource) {
            applyDefensiveOnlyDamage(event, defenderStats);
            return;
        }
        if (!attackerStats.hasAny() && !playerMagicSource) return;

        DamageResult result = calculateDamage(event, attackerEntity, attackerStats, defenderStats, directWeaponAttack);
        event.setCancelled(true);
        LivingEntity target = event.getEntity() instanceof LivingEntity livingEntity ? livingEntity : null;
        boolean damageApplied = applyAttributedDamage(target, attackerEntity, result.totalDamage());
        if (!damageApplied) return;
        if (attacker != null) playVanillaHitFeedback(attacker, target, mainHand);

        if (directWeaponAttack) {
            if (attacker != null && !consumedSwingGrace) setItemCooldown(attacker, attackerStats.attackSpeed());
            if (attacker != null) plugin.getRpgItemManager().consumeAttackDurability(attacker, mainHand, 1);
            if (attacker != null) healLifeSteal(attacker, result.totalDamage(), attackerStats);
            if (attackingItem != null) {
                applyHitBuffs(target, attackingItem);
                particleEffectPlayer.play(event.getEntity().getLocation(), attackingItem.particles());
                soundEffectPlayer.play(event.getEntity().getLocation(), attackingItem.sound());
                if (slashParticleEffectPlayer.supports(mainHand) && !consumedSwingGrace) {
                    slashParticleEffectPlayer.play(attacker, attackingItem.slashParticles(), attackingItem.slashParticleLength(), attackerStats.attackSpeed(), slashParticleEffectPlayer.isThrust(mainHand, attacker));
                }
            }
        }
        damageDisplay.show(event.getEntity().getLocation(), result.displayLines());
        if (directWeaponAttack) applyAttackRange(attackerEntity, target, result, attackerStats, attackingItem);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent || !plugin.isFeatureEnabled("rpgitems")) return;
        if (!(event.getEntity() instanceof Player defender)) return;
        RpgStats defenderStats = plugin.getRpgItemManager().collectActiveStats(defender, true);
        if (rollChance(defenderStats.evasion())) { event.setCancelled(true); return; }
        applyDefensiveOnlyDamage(event, defenderStats);
    }

    private void applyDefensiveOnlyDamage(EntityDamageEvent event, RpgStats defenderStats) {
        double damage = applyArmorLayer(event.getFinalDamage(), defenderStats.envArmor(), defenderStats.envArmorPercent(), 0);
        if (isMagicDamageCause(event.getCause())) {
            damage = applyArmorLayer(damage, defenderStats.magicArmor(), defenderStats.magicArmorPercent(), 0);
        }
        damage = applyResistance(damage, defenderStats.resistance());
        event.setDamage(Math.max(0, Math.round(damage)));
    }

    private Player resolveAttacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) return player;
        if (event.getDamager() instanceof Projectile projectile) {
            ProjectileSource source = projectile.getShooter();
            if (source instanceof Player player) return player;
        }
        return null;
    }

    private LivingEntity resolveAttackerEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof LivingEntity livingEntity) return livingEntity;
        if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof LivingEntity livingEntity) return livingEntity;
        return null;
    }

    private ItemStack mainHand(LivingEntity entity) {
        if (entity instanceof Player player) return player.getInventory().getItemInMainHand();
        return entity.getEquipment() == null ? null : entity.getEquipment().getItemInMainHand();
    }

    private DamageResult calculateDamage(EntityDamageByEntityEvent event, LivingEntity attacker, RpgStats attackerStats, RpgStats defenderStats, boolean directWeaponAttack) {
        boolean projectile = event.getCause() == EntityDamageEvent.DamageCause.PROJECTILE || event.getDamager() instanceof Projectile;
        double armorPen = Math.min(100, Math.max(0, attackerStats.armorPen())) / 100.0;
        double eventRaw = event.getDamage();
        double eventFinal = event.getFinalDamage();
        double missingSharpness = directWeaponAttack ? missingSharpnessDamage(attacker, eventRaw) : 0;
        double vanillaRaw = eventRaw + missingSharpness;
        double vanillaFinal = eventFinal + missingSharpness * (eventRaw > 0 ? eventFinal / eventRaw : 1.0);
        double vanillaMitigation = Math.max(0, vanillaRaw - vanillaFinal);
        double penetratedIncoming = vanillaFinal + vanillaMitigation * armorPen;
        boolean magicSource = !directWeaponAttack && isMagicDamageCause(event.getCause());

        boolean backstrike = directWeaponAttack && isBackstrike(attacker, event.getEntity() instanceof LivingEntity livingEntity ? livingEntity : null);
        boolean assassin = directWeaponAttack && isAssassinHit(attacker, event.getEntity() instanceof LivingEntity livingEntity ? livingEntity : null);
        CriticalRoll physicalCritical = plugin.isFeatureEnabled("physical-crit") && directWeaponAttack && attackerStats.attack() != 0
                ? rollCritical(attackerStats.critChance(), attackerStats.critDamage(), defenderStats.critArmor(), defenderStats.critArmorPercent(), armorPen)
                : CriticalRoll.none();
        CriticalRoll magicCritical = plugin.isFeatureEnabled("magic-crit") && !directWeaponAttack && attackerStats.magicDamage() != 0
                ? rollCritical(attackerStats.critChance(), attackerStats.critDamage(), defenderStats.critArmor(), defenderStats.critArmorPercent(), armorPen)
                : CriticalRoll.none();

        double backstrikePercent = Math.max(0, attackerStats.backstrikeDamagePercent()) / 100.0;
        double weaponPanelDamage = directWeaponAttack ? Math.max(0, penetratedIncoming + attackerStats.effectiveAttack()) : 0;
        double backstrikeBonus = backstrike ? attackerStats.backstrikeDamage() * (1 + backstrikePercent) + weaponPanelDamage * backstrikePercent : 0;
        double bonusFlat = backstrikeBonus + (assassin ? attackerStats.assDamage() : 0);
        double scaleDamage = directWeaponAttack ? attackerMaxHealth(attacker) * Math.max(0, attackerStats.pctDamage()) / 100.0 : 0;
        double proportionalDamage = directWeaponAttack ? targetMaxHealth(event.getEntity() instanceof LivingEntity livingEntity ? livingEntity : null) * Math.max(0, attackerStats.pstDamage()) / 100.0 : 0;
        double percentPhysical = scaleDamage + proportionalDamage;
        double physicalRaw = directWeaponAttack ? penetratedIncoming + attackerStats.effectiveAttack() + percentPhysical + bonusFlat + physicalCritical.damage() + (physicalCritical.critical() ? (penetratedIncoming + attackerStats.effectiveAttack() + percentPhysical + bonusFlat) * Math.max(0, attackerStats.critDamagePercent()) / 100.0 : 0) : (magicSource ? 0 : penetratedIncoming);
        double physicalElementBase = directWeaponAttack
                ? vanillaRaw + attackerStats.effectiveAttack() + percentPhysical + bonusFlat + rawCriticalDamage(attackerStats, physicalCritical)
                : (magicSource ? 0 : vanillaRaw);
        double physical = applyArmorLayer(physicalRaw, defenderStats.envArmor(), defenderStats.envArmorPercent(), 0);
        if (directWeaponAttack) physical = applyArmorLayer(physical, defenderStats.armor(), defenderStats.armorPercent(), armorPen);
        if (projectile) physical = applyArmorLayer(physical, defenderStats.bowArmor(), defenderStats.bowArmorPercent(), armorPen);
        if (assassin && attackerStats.assDamagePercent() != 0) {
            double multiplier = 1 + Math.max(0, attackerStats.assDamagePercent()) / 100.0;
            physical *= multiplier;
            physicalElementBase *= multiplier;
        }
        physical = applyResistance(Math.max(0, physical), defenderStats.resistance());
        double assassinPercentPart = assassin && attackerStats.assDamagePercent() > 0
                ? physical * attackerStats.assDamagePercent() / (100.0 + attackerStats.assDamagePercent()) : 0;
        double backstrikePart = directWeaponAttack && backstrike ? Math.max(0, backstrikeBonus) : 0;
        double assassinPart = directWeaponAttack && assassin ? Math.max(0, attackerStats.assDamage()) : 0;
        backstrikePart = applyResistance(applyArmorLayer(applyArmorLayer(backstrikePart, defenderStats.envArmor(), defenderStats.envArmorPercent(), 0), defenderStats.armor(), defenderStats.armorPercent(), armorPen), defenderStats.resistance());
        assassinPart = applyResistance(applyArmorLayer(applyArmorLayer(assassinPart, defenderStats.envArmor(), defenderStats.envArmorPercent(), 0), defenderStats.armor(), defenderStats.armorPercent(), armorPen), defenderStats.resistance()) + assassinPercentPart;
        physical = Math.max(0, physical - backstrikePart - assassinPart);

        double magic = 0;
        double magicElementBase = 0;
        if (attackerStats.magicDamage() != 0 || magicSource) {
            double magicRaw = (magicSource ? penetratedIncoming : 0) + attackerStats.effectiveMagicDamage() + magicCritical.damage();
            magicElementBase = (magicSource ? vanillaRaw : 0) + attackerStats.effectiveMagicDamage() + rawCriticalDamage(attackerStats, magicCritical);
            magic = applyArmorLayer(magicRaw, defenderStats.envArmor(), defenderStats.envArmorPercent(), 0);
            magic = applyArmorLayer(magic, defenderStats.magicArmor(), defenderStats.magicArmorPercent(), armorPen);
            if (projectile) magic = applyArmorLayer(magic, defenderStats.bowArmor(), defenderStats.bowArmorPercent(), armorPen);
            if (assassin && attackerStats.assDamagePercent() != 0) {
                double multiplier = 1 + Math.max(0, attackerStats.assDamagePercent()) / 100.0;
                magic *= multiplier;
                magicElementBase *= multiplier;
            }
            magic = applyResistance(Math.max(0, magic), defenderStats.resistance());
        }

        long physicalDamage = Math.max(0, Math.round(physical));
        long backstrikeDamage = Math.max(0, Math.round(backstrikePart));
        long assassinDamage = Math.max(0, Math.round(assassinPart));
        long magicDamage = Math.max(0, Math.round(magic));
        long trueDamage = Math.max(0, Math.round(applyNegativeResistance(attackerStats.trueDamage(), defenderStats.resistance())));
        double elementBase = Math.max(0, physicalElementBase) + Math.max(0, magicElementBase) + Math.max(0, attackerStats.trueDamage());
        long iceDamage = elementDamage(attackerStats.iceDamage(), attackerStats.iceDamagePercent(), elementBase, defenderStats.iceArmor(), defenderStats.iceArmorPercent(), defenderStats.resistance(), armorPen);
        long fireDamage = elementDamage(attackerStats.fireDamage(), attackerStats.fireDamagePercent(), elementBase, defenderStats.fireArmor(), defenderStats.fireArmorPercent(), defenderStats.resistance(), armorPen);
        long lightDamage = elementDamage(attackerStats.lightDamage(), attackerStats.lightDamagePercent(), elementBase, defenderStats.lightArmor(), defenderStats.lightArmorPercent(), defenderStats.resistance(), armorPen);
        long darkDamage = elementDamage(attackerStats.darkDamage(), attackerStats.darkDamagePercent(), elementBase, defenderStats.darkArmor(), defenderStats.darkArmorPercent(), defenderStats.resistance(), armorPen);
        List<String> lines = new ArrayList<>();
        if (directWeaponAttack && physicalDamage > 0 && (attackerStats.attack() != 0 || physicalCritical.damage() != 0 || bonusFlat != 0 || percentPhysical != 0 || missingSharpness > 0)) {
            lines.add(damageDisplay.format(physicalCritical.critical() ? "damage-display.physical-crit-format" : "damage-display.physical-format", physicalCritical.critical() ? "damage-display.physical-crit-color" : "damage-display.physical-color", physicalCritical.critical() ? "\u2694\u2764-{damage}" : "\uD83D\uDDE1\u2764-{damage}", physicalDamage));
        }
        if (backstrikeDamage > 0) lines.add(damageDisplay.format("damage-display.backstrike-format", "damage-display.backstrike-color", "&e🗡背击-{damage}", backstrikeDamage));
        if (assassinDamage > 0) lines.add(damageDisplay.format("damage-display.assassin-format", "damage-display.assassin-color", "&c☠刺客-{damage}", assassinDamage));
        if (magicDamage > 0) {
            lines.add(damageDisplay.format(magicCritical.critical() ? "damage-display.magic-crit-format" : "damage-display.magic-format", magicCritical.critical() ? "damage-display.magic-crit-color" : "damage-display.magic-color", magicCritical.critical() ? "\uD83D\uDD2F\u2764-{damage}" : "\u2721\u2764-{damage}", magicDamage));
        }
        if (trueDamage > 0) lines.add(damageDisplay.format("damage-display.true-format", "damage-display.true-color", "\u2620\u2764-{damage}", trueDamage));
        addElementLine(lines, iceDamage, "damage-display.ice-format", "damage-display.ice-color", "\uD83E\uDDCA\u2764-{damage}");
        addElementLine(lines, fireDamage, "damage-display.fire-format", "damage-display.fire-color", "\uD83D\uDD25\u2764-{damage}");
        addElementLine(lines, darkDamage, "damage-display.dark-format", "damage-display.dark-color", "\uD83C\uDF18\u2764-{damage}");
        addElementLine(lines, lightDamage, "damage-display.light-format", "damage-display.light-color", "\u2600\u2764-{damage}");
        return new DamageResult(physicalDamage + backstrikeDamage + assassinDamage + magicDamage + trueDamage + iceDamage + fireDamage + lightDamage + darkDamage, lines);
    }

    private void playVanillaHitFeedback(Player attacker, LivingEntity target, ItemStack mainHand) {
        if (target == null) return;
        if (attacker.getFallDistance() > 0.0F && !attacker.isOnGround()) {
            target.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0, target.getHeight() * 0.65, 0), 16, 0.35, 0.35, 0.35, 0.15);
        }
        if (mainHand != null && mainHand.hasItemMeta() && !mainHand.getEnchantments().isEmpty()) {
            target.getWorld().spawnParticle(Particle.ENCHANTED_HIT, target.getLocation().add(0, target.getHeight() * 0.55, 0), 18, 0.35, 0.35, 0.35, 0.12);
        }
    }

    private double missingSharpnessDamage(LivingEntity attacker, double eventRawDamage) {
        ItemStack weapon = mainHand(attacker);
        if (weapon == null || weapon.getType().isAir()) return 0;
        int level = weapon.getEnchantmentLevel(Enchantment.SHARPNESS);
        if (level <= 0) return 0;
        AttributeInstance attackDamage = attacker.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE);
        if (attackDamage == null) return 0;
        double sharpnessBonus = 0.5 * level + 0.5;
        double expectedMinimum = attackDamage.getValue() + sharpnessBonus;
        return Math.max(0, expectedMinimum - eventRawDamage);
    }

    private boolean isBackstrike(LivingEntity attacker, LivingEntity target) {
        if (target == null) return false;
        Vector targetToAttacker = attacker.getLocation().toVector().subtract(target.getLocation().toVector()).normalize();
        return target.getLocation().getDirection().normalize().dot(targetToAttacker) < -0.45;
    }

    private boolean isAssassinHit(LivingEntity attacker, LivingEntity target) {
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

    private boolean rollChance(double chance) { return chance > 0 && ThreadLocalRandom.current().nextDouble(100) < chance; }
    private double applyResistance(double damage, double resistance) { return damage * (1 - Math.min(100, resistance) / 100.0); }

    private double applyNegativeResistance(double damage, double resistance) {
        return resistance < 0 ? applyResistance(damage, resistance) : damage;
    }

    private double applyArmorLayer(double damage, double flatArmor, double armorPercent, double armorPen) {
        double penetratedFlat = Math.max(0, flatArmor * (1 - armorPen));
        double penetratedPercent = Math.max(0, armorPercent * (1 - armorPen));
        double afterFlat = Math.max(0, damage - penetratedFlat);
        return afterFlat * (1 - Math.min(100, penetratedPercent) / 100.0);
    }

    private boolean isMagicDamageCause(EntityDamageEvent.DamageCause cause) {
        String name = cause.name();
        return name.contains("MAGIC") || name.equals("POISON") || name.equals("WITHER") || name.equals("DRAGON_BREATH");
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

    private double attackerMaxHealth(LivingEntity attacker) {
        AttributeInstance attribute = attacker.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        return attribute == null ? 20 : attribute.getValue();
    }

    private double targetMaxHealth(LivingEntity target) {
        if (target == null) return 20;
        AttributeInstance attribute = target.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        return attribute == null ? 20 : attribute.getValue();
    }

    private void applyAttackRange(LivingEntity attacker, LivingEntity originalTarget, DamageResult result, RpgStats attackerStats, RpgItem attackingItem) {
        if (originalTarget == null || result.totalDamage() <= 0) return;
        double range = Math.round(attackerStats.attackRange() * 100.0) / 100.0;
        if (range <= 0) return;
        for (LivingEntity target : originalTarget.getLocation().getNearbyLivingEntities(range)) {
            if (target.equals(originalTarget) || target.equals(attacker) || target.isDead()) continue;
            if (!applyAttributedDamage(target, attacker, result.totalDamage())) continue;
            damageDisplay.show(target.getLocation(), result.displayLines());
            if (attackingItem != null) {
                particleEffectPlayer.play(target.getLocation(), attackingItem.particles());
                soundEffectPlayer.play(target.getLocation(), attackingItem.sound());
            }
        }
    }

    private boolean isRangedWeapon(ItemStack itemStack) {
        return itemStack != null && (itemStack.getType() == org.bukkit.Material.BOW || itemStack.getType() == org.bukkit.Material.CROSSBOW);
    }

    private RpgStats rangedMeleeStats(RpgStats stats) {
        return RpgStats.attackOnly(stats.effectiveAttack());
    }

    private boolean tryParry(EntityDamageByEntityEvent event, Player defender, RpgStats defenderStats, LivingEntity counterTarget) {
        if (defender == null || !plugin.isFeatureEnabled("parry") || defenderStats.parry() <= 0 || !isParryDamage(event)) return false;
        if (!isAttackReady(defender) || hasVisibleCooldown(defender)) return false;
        if (!rollChance(Math.min(100, defenderStats.parry()))) return false;
        event.setCancelled(true);
        playParryEffect(defender);
        double penalty = plugin.getConfig().getDouble("parry.attack-speed-penalty", -20);
        setItemCooldown(defender, defenderStats.attackSpeed() + penalty);
        if (counterTarget != null && !counterTarget.equals(defender) && !(event.getDamager() instanceof Projectile) && plugin.isFeatureEnabled("counter-attack") && rollChance(Math.min(100, defenderStats.counterAttack()))) {
            counterAttack(defender, counterTarget, defenderStats);
        }
        return true;
    }

    private boolean isParryDamage(EntityDamageByEntityEvent event) {
        return event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK
                || event.getCause() == EntityDamageEvent.DamageCause.PROJECTILE;
    }

    private boolean hasVisibleCooldown(Player player) {
        ItemStack itemStack = player.getInventory().getItemInMainHand();
        return itemStack != null && !itemStack.getType().isAir() && player.hasCooldown(itemStack.getType());
    }

    private void playParryEffect(Player defender) {
        if (plugin.isFeatureEnabled("parry-particles") && plugin.getConfig().getBoolean("parry-particles.enabled", true)) {
            var dust = new Particle.DustOptions(Color.fromRGB(255, 215, 0), 1.2F);
            var center = defender.getEyeLocation().add(defender.getLocation().getDirection().normalize().multiply(0.8));
            int count = Math.max(8, plugin.getConfig().getInt("parry-particles.count", 48));
            double radius = Math.max(0.1, plugin.getConfig().getDouble("parry-particles.radius", 0.8));
            for (int index = 0; index < count; index++) {
                center.getWorld().spawnParticle(Particle.DUST, center, 1, radius, radius * 0.6, radius, 0, dust);
            }
        }
        if (plugin.getConfig().getBoolean("parry-sound.enabled", true)) {
            Sound sound = parseSound(plugin.getConfig().getString("parry-sound.sound", "BLOCK_ANVIL_LAND"));
            defender.getWorld().playSound(defender.getLocation(), sound,
                    (float) plugin.getConfig().getDouble("parry-sound.volume", 1.0),
                    (float) plugin.getConfig().getDouble("parry-sound.pitch", 0.5));
        }
    }

    private void counterAttack(Player defender, LivingEntity target, RpgStats defenderStats) {
        long damage = Math.max(0, Math.round(defenderStats.effectiveAttack() * (1 + defenderStats.cad() / 100.0)));
        if (damage <= 0) return;
        if (!applyAttributedDamage(target, defender, damage)) return;
        RpgItem item = plugin.getRpgItemManager().findActiveMainHandItem(defender, false);
        ItemStack mainHand = defender.getInventory().getItemInMainHand();
        if (item != null) {
            particleEffectPlayer.play(target.getLocation(), item.particles());
            soundEffectPlayer.play(target.getLocation(), item.sound());
            playCounterSound(target);
            if (slashParticleEffectPlayer.supports(mainHand)) {
                slashParticleEffectPlayer.play(defender, item.slashParticles(), item.slashParticleLength(), defenderStats.attackSpeed(), slashParticleEffectPlayer.isThrust(mainHand, defender));
            }
        }
        damageDisplay.show(target.getLocation(), List.of(damageDisplay.format("damage-display.physical-format", "damage-display.physical-color", "\uD83D\uDDE1\u2764-{damage}", damage)));
    }

    private void playCounterSound(LivingEntity target) {
        if (!plugin.getConfig().getBoolean("counter-attack-sound.enabled", true)) return;
        Sound sound = parseSound(plugin.getConfig().getString("counter-attack-sound.sound", "ENTITY_WITHER_HURT"));
        target.getWorld().playSound(target.getLocation(), sound,
                (float) plugin.getConfig().getDouble("counter-attack-sound.volume", 1.0),
                (float) plugin.getConfig().getDouble("counter-attack-sound.pitch", 0.8));
    }

    private Sound parseSound(String value) {
        try { return Sound.valueOf(value.toUpperCase()); } catch (IllegalArgumentException exception) { return Sound.BLOCK_ANVIL_LAND; }
    }

    private boolean applyAttributedDamage(LivingEntity target, LivingEntity attacker, long damage) {
        if (target == null || damage <= 0) return false;
        if (target instanceof Player player
                && (player.getGameMode() == org.bukkit.GameMode.CREATIVE || player.getGameMode() == org.bukkit.GameMode.SPECTATOR)) return false;
        if (plugin.getDummyManager().absorbDamage(target, damage)) return true;
        double healthBefore = target.getHealth();
        double absorptionBefore = target.getAbsorptionAmount();
        DamageProbe probe = new DamageProbe(attacker.getUniqueId());
        pendingDamage.put(target.getUniqueId(), probe);
        target.setNoDamageTicks(0);
        target.setLastDamage(0);
        pluginDamageTargets.add(target.getUniqueId());
        try { target.damage(damage, attacker); } finally {
            pluginDamageTargets.remove(target.getUniqueId());
            pendingDamage.remove(target.getUniqueId());
        }
        if (probe.cancelled || probe.blocked) return false;
        if (target.isInvulnerable()) return false;
        double applied = (healthBefore - target.getHealth()) + (absorptionBefore - target.getAbsorptionAmount());
        double missing = damage - Math.max(0, applied);
        if (missing > 0.001 && !target.isDead()) {
            double absorbed = Math.min(target.getAbsorptionAmount(), missing);
            if (absorbed > 0) {
                target.setAbsorptionAmount(target.getAbsorptionAmount() - absorbed);
                missing -= absorbed;
            }
            if (missing > 0.001) target.setHealth(Math.max(0, target.getHealth() - missing));
        }
        target.setNoDamageTicks(0);
        target.setLastDamage(0);
        return true;
    }

    private void applyHitBuffs(LivingEntity target, RpgItem item) {
        if (target == null || !plugin.isFeatureEnabled("hit-buffs") || !plugin.getConfig().getBoolean("hit-buffs.enabled", true)) return;
        for (HitBuffConfig buff : item.hitBuffs()) {
            PotionEffectType type = PotionEffectType.getByName(buff.type().toUpperCase());
            if (type != null) target.addPotionEffect(new PotionEffect(type, Math.max(1, buff.durationSeconds()) * 20, Math.max(0, buff.amplifier()), true, true, true));
        }
    }

    private void healLifeSteal(Player attacker, double damage, RpgStats stats) {
        double heal = stats.lifeSteal() + damage * Math.max(0, stats.lifeStealPercent()) / 100.0;
        if (heal <= 0 || attacker.isDead()) return;
        AttributeInstance attribute = attacker.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        double maxHealth = attribute == null ? 20 : attribute.getValue();
        attacker.setHealth(Math.min(maxHealth, attacker.getHealth() + heal));
    }

    private boolean isAttackReady(Player player) { return plugin.getAttackCooldownManager().isReady(player); }
    private boolean consumeSwingGrace(Player player) {
        Long expires = swingGraceTimes.remove(player.getUniqueId());
        return expires != null && System.currentTimeMillis() <= expires;
    }

    private void setItemCooldown(Player player, double attackSpeed) {
        plugin.getAttackCooldownManager().start(player, attackSpeed);
    }

    private static final class DamageProbe {
        private final UUID attackerId;
        private boolean cancelled;
        private boolean blocked;

        private DamageProbe(UUID attackerId) {
            this.attackerId = attackerId;
        }
    }

    private record CriticalRoll(boolean critical, double damage) { static CriticalRoll none() { return new CriticalRoll(false, 0); } }
    private record DamageResult(long totalDamage, List<String> displayLines) { }
}
