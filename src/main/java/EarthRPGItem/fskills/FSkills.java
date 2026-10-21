package EarthRPGItem.fskills;

import EarthRPGItem.EarthRPGItem;
import EarthRPGItem.effects.DamageDisplay;
import EarthRPGItem.effects.ParticleEffectPlayer;
import EarthRPGItem.items.*;
import EarthRPGItem.skills.SkillPanelManager;
import EarthRPGItem.util.SpreadMath;
import EarthRPGItem.util.Texts;
import org.bukkit.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.GameMode;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

public class FSkills implements Listener {

    public static final String SKILL_DAMAGE_METADATA = "earthrpgitem_skill_damage";
    public static final String CROUCH_RIGHT_TRIGGER = "SR";
    public static final String DROP_TRIGGER = "Q";
    public static final String CROUCH_DROP_TRIGGER = "SQ";
    private static final String MAGIC_CIRCLE_ARMOR_STAND = "earthrpgitem_magic_circle";

    private final EarthRPGItem plugin;
    private final ParticleEffectPlayer particleEffectPlayer;
    private final DamageDisplay damageDisplay;
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
    private final Map<UUID, Map<String, Long>> globalCooldowns = new HashMap<>();
    private final Map<UUID, Long> castLocks = new HashMap<>();
    private final Map<UUID, ChantSession> chantSessions = new HashMap<>();
    private final Map<UUID, Long> rightClickLocks = new HashMap<>();
    private final Map<UUID, Vector> movementInputs = new HashMap<>();
    private final Map<UUID, Long> inventoryDropWindows = new HashMap<>();
    private final Set<UUID> openInventories = new HashSet<>();
    /** Stack that triggered the current cast, so a dropped weapon can still be used as the base. */
    private final Map<UUID, ItemStack> castStacks = new HashMap<>();
    private final Set<ItemDisplay> magicCircleDisplays = new HashSet<>();
    /** Weapons currently falling or stuck in the ground for 「王之宝库」, one {@code ItemDisplay} each. */
    private final Set<ItemDisplay> gateDisplays = new HashSet<>();
    /**
     * 短弓类技能正在飞行的物品弹射物。每个都是一个 {@code ItemDisplay} 加一段自己的轨迹状态；
     * 共用一个 tick task 推进，全部结束时 task 自行取消。
     */
    private final Set<ItemShot> itemShots = new HashSet<>();
    /** 推进 {@link #itemShots} 的共用 task；没有弹射物时置回 null。 */
    private org.bukkit.scheduler.BukkitTask itemShotTask;
    /**
     * Default ceiling on live weapons across all casts. One cast is already capped at
     * {@link GateOfBabylonSkill#MAX_WEAPONS}; this is the second line of defence for a raid of
     * players casting at once, where the per-cast cap alone would still let the entity count grow.
     * <p>
     * Overridable with {@code fskill.gate-max-live-displays}. It is configurable because a full
     * attack-speed volley can legitimately outrun a fixed ceiling: the value that protects a normal
     * server is too low for deliberate stress testing, and hitting it silently refuses the cast.
     */
    private static final int GATE_DEFAULT_MAX_LIVE_DISPLAYS = 512;
    /** The live-weapon ceiling actually in force, from {@code fskill.gate-max-live-displays}. */
    private int gateMaxLiveDisplays() {
        return Math.max(1, plugin.getConfig().getInt("fskill.gate-max-live-displays", GATE_DEFAULT_MAX_LIVE_DISPLAYS));
    }
    /**
     * How far the hilt sits from the weapon entity's centre, in blocks. The sprite is one block tall
     * and centred on the entity, so the hilt is half a block back along the blade axis. The hovering
     * ring is drawn here rather than at the entity position, which is what puts it around the grip
     * instead of through the middle of the blade.
     */
    private static final double GATE_HILT_OFFSET = 0.5;
    /** Radians the hilt ring advances per tick of hovering, so it shimmers instead of sitting still. */
    private static final double GATE_RIPPLE_SPIN = 0.35;
    /**
     * 蹦山击俯冲时每 tick 施加的速度大小。1.1 格/tick 足够在 45° 下快速砸到地面，
     * 又不会快到让落地判定（isOnGround）来不及触发而穿地。
     */
    private static final double LEAP_DIVE_SPEED = 1.1;
    /** Players tracing the magic circle at runtime, toggled with {@code /rpgitem magicdebug}. */
    private final Set<UUID> magicCircleDebugPlayers = new HashSet<>();
    /** 面板拦截提示的节流记录，避免玩家连按时刷屏。 */
    private final Map<UUID, Long> panelBlockedNotice = new HashMap<>();

    public FSkills(EarthRPGItem plugin) {
        this.plugin = plugin;
        this.particleEffectPlayer = new ParticleEffectPlayer(plugin);
        this.damageDisplay = new DamageDisplay(plugin);
    }

    /**
     * 面板里绑定了技能、但这次施放被拦下时，给玩家一句明确的原因。
     *
     * <p>旧版 {@code resolve()} 直接返回 null，玩家看到的现象只是「按了没反应」，
     * 分不清到底是技能组冲突、武器类型不符、技能不在背包还是该键位没绑定。
     * 这里复用 {@code diagnose()} 的判定结果，所以提示文案与 {@code /rpgitem skillcheck} 完全一致。
     */
    /**
     * 「仅手持 RPG 物品才能放技能」这道闸门对<b>物品自带技能</b>这一路是否放行。
     *
     * <p>面板技能那一路已经在 {@code SkillPanelManager.resolve} 里查过了；这里管的是另一路：
     * 物品 yml 里直接定义的 qskill/rskill/... 可以走<b>副手</b>回落，
     * 于是「主手拿食物右键吃饭」会触发副手 RPG 物品的技能，还把事件取消掉，食物都吃不上——
     * 与面板那条是同一个毛病，所以用同一道闸门。
     *
     * @param heldOverride 判定用的手持物。Q/SQ 丢弃事件里物品已经离开主手，
     *                     传「被丢的那件」；其余情况传 null 表示用主手。
     */
    private boolean heldItemGateOpen(Player player, ItemStack heldOverride) {
        SkillPanelManager panel = plugin.getSkillPanelManager();
        if (!panel.requiresHeldRpgItem()) return true;
        return panel.holdsRpgItem(player, heldOverride);
    }

    private void notifyPanelBlocked(Player player, String trigger) {
        // 插件总开关关掉时不提示：玩家是在正常玩原版，不该被技能提示打断。
        if (!plugin.isFeatureEnabled("fskill")) return;
        var panel = plugin.getSkillPanelManager();
        if (!panel.isEnabled(player) || !panel.isTriggerEnabled(player, trigger)) return;
        // 该键位没有绑定任何技能时不提示：这是「回落到手持物品自带技能」的正常路径。
        if (panel.getSkillId(player, trigger) == null) return;
        String reason = panel.diagnose(player, trigger);
        if ("正常".equals(reason)) return;
        // 「主手没拿着 RPG 物品」同样静默：玩家拿着食物右键只是想吃饭，
        // 每隔 1.5 秒弹一句「无法释放 R 技能」比技能误触发还烦。要查原因用 /rpgitem skillcheck。
        if (SkillPanelManager.REASON_NO_HELD_ITEM.equals(reason)) return;
        long now = System.currentTimeMillis();
        Long last = panelBlockedNotice.get(player.getUniqueId());
        if (last != null && now - last < 1500L) return;
        panelBlockedNotice.put(player.getUniqueId(), now);
        player.sendMessage(Texts.color("&c无法释放 &f" + trigger + " &c技能：&f" + reason));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (!plugin.isFeatureEnabled("fskill")) return;
        Player player = event.getPlayer();
        String trigger = player.isSneaking() ? "SF" : "F";
        if (plugin.getSkillPanelManager().isEnabled(player) && plugin.getSkillPanelManager().isTriggerEnabled(player, trigger)) {
            RpgItem panelItem = plugin.getSkillPanelManager().resolve(player, trigger);
            // Fall through to the held item when the panel has nothing bound for this trigger.
            // Returning here made an enabled panel shadow every RPGItem's own fskill/rskill/...,
            // so a weapon that defines its skills directly dealt no damage at all.
            if (panelItem != null) {
                event.setCancelled(true);
                triggerSkill(player, player.getInventory().getItemInMainHand(), panelItem, skillFor(panelItem, trigger), trigger, false);
                return;
            }
            notifyPanelBlocked(player, trigger);
        }
        RpgItem item = plugin.getRpgItemManager().findActiveMainHandItem(player, true);
        if (item == null) return;
        boolean sneaking = player.isSneaking();
        FSkillConfig skill = sneaking && item.sfSkill() != null && item.sfSkill().enabled() ? item.sfSkill() : item.fSkill();
        if (skill == null || !skill.enabled()) return;
        event.setCancelled(true);
        triggerSkill(player, player.getInventory().getItemInMainHand(), item, skill, sneaking ? "SF" : "F", false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeldItemChange(PlayerItemHeldEvent event) {
        interruptChant(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInventoryDropSource(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getAction() != org.bukkit.event.inventory.InventoryAction.DROP_ALL_CURSOR
                && event.getAction() != org.bukkit.event.inventory.InventoryAction.DROP_ONE_CURSOR
                && event.getAction() != org.bukkit.event.inventory.InventoryAction.DROP_ALL_SLOT
                && event.getAction() != org.bukkit.event.inventory.InventoryAction.DROP_ONE_SLOT) return;
        ItemStack candidate = event.getCurrentItem();
        if (candidate == null || candidate.getType().isAir()) candidate = event.getCursor();
        if (plugin.getRpgItemManager().hasRpgIdentity(candidate)) {
            inventoryDropWindows.put(player.getUniqueId(), System.currentTimeMillis() + 750L);
        }
    }
    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) openInventories.add(player.getUniqueId());
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) openInventories.remove(player.getUniqueId());
    }
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getFrom().getWorld() != event.getTo().getWorld()) return;
        Vector movement = event.getTo().toVector().subtract(event.getFrom().toVector()).setY(0);
        if (movement.lengthSquared() > 0.0001) movementInputs.put(event.getPlayer().getUniqueId(), movement.normalize());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancelChant(event.getPlayer().getUniqueId(), false);
        cooldowns.remove(event.getPlayer().getUniqueId());
        globalCooldowns.remove(event.getPlayer().getUniqueId());
        rightClickLocks.remove(event.getPlayer().getUniqueId());
        castLocks.remove(event.getPlayer().getUniqueId());
        inventoryDropWindows.remove(event.getPlayer().getUniqueId());
        openInventories.remove(event.getPlayer().getUniqueId());
        movementInputs.remove(event.getPlayer().getUniqueId());
        magicCircleDebugPlayers.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDropSkill(PlayerDropItemEvent event) {
        if (!plugin.isFeatureEnabled("fskill")) return;
        Player player = event.getPlayer();
        Long inventoryDropUntil = inventoryDropWindows.get(player.getUniqueId());
        if (openInventories.contains(player.getUniqueId())
                || (inventoryDropUntil != null && inventoryDropUntil > System.currentTimeMillis())) {
            inventoryDropWindows.remove(player.getUniqueId());
            return;
        }
        String trigger = player.isSneaking() ? "SQ" : "Q";
        if (plugin.getSkillPanelManager().isEnabled(player) && plugin.getSkillPanelManager().isTriggerEnabled(player, trigger)) {
            // 丢弃事件触发时物品已经离开主手，所以把「被丢的那件」当作手持物传进去。
            // 不传的话「仅手持 RPG 物品才能放技能」会把丢弃键的技能一并废掉。
            RpgItem panelItem = plugin.getSkillPanelManager().resolve(player, trigger, event.getItemDrop().getItemStack());
            // Fall through to the held item when nothing is bound; see onSwap.
            if (panelItem != null) {
                event.setCancelled(true);
                // The dropped stack IS the item that was in the main hand; the hand is empty now.
                triggerSkill(player, event.getItemDrop().getItemStack().clone(), panelItem, skillFor(panelItem, trigger), trigger, false);
                return;
            }
            notifyPanelBlocked(player, trigger);
        }
        ItemStack dropped = event.getItemDrop().getItemStack().clone();
        // 物品自带技能这一路的闸门：丢掉食物时不要触发副手 RPG 物品的 Q/SQ 技能。
        // 丢弃事件里物品已经离开主手，所以拿「被丢的那件」当手持物判定。
        if (!heldItemGateOpen(player, dropped)) return;
        RpgItem main = plugin.getRpgItemManager().findItemByIdentity(player.getInventory().getItemInMainHand());
        RpgItem off = plugin.getRpgItemManager().findItemByIdentity(player.getInventory().getItemInOffHand());
        RpgItem droppedMain = plugin.getRpgItemManager().findItemByIdentity(dropped);
        if (droppedMain != null) main = droppedMain;
        boolean mainIsDroppedRpg = droppedMain != null;
        boolean crouching = player.isSneaking();
        if (crouching && mainIsDroppedRpg) event.setCancelled(true);
        FSkillConfig mainSkill = main == null ? null : (crouching ? main.sqSkill() : main.qSkill());
        FSkillConfig offSkill = off == null ? null : (crouching ? off.sqSkill() : off.qSkill());
        boolean useMain = mainIsDroppedRpg && mainSkill != null && mainSkill.enabled();
        RpgItem item = useMain ? main : off;
        FSkillConfig skill = useMain ? mainSkill : offSkill;
        if (item == null || skill == null || !skill.enabled()) {
            if (crouching && mainIsDroppedRpg) event.setCancelled(true);
            return;
        }
        if (mainIsDroppedRpg) event.setCancelled(true);
        ItemStack source = useMain ? dropped : player.getInventory().getItemInOffHand().clone();
        RpgItem skillItem = item;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) triggerSkill(player, source, skillItem, skill, trigger, false);
        });
    }



    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onRightClick(PlayerInteractEvent event) {
        if (!plugin.isFeatureEnabled("fskill")) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        EquipmentSlot hand = event.getHand();
        if (hand != EquipmentSlot.HAND && hand != EquipmentSlot.OFF_HAND) return;
        Player player = event.getPlayer();
        String panelTrigger = player.isSneaking() ? "SR" : "R";
        if (plugin.getSkillPanelManager().isEnabled(player) && plugin.getSkillPanelManager().isTriggerEnabled(player, panelTrigger)) {
            RpgItem panelItem = plugin.getSkillPanelManager().resolve(player, panelTrigger);
            // Fall through to the held item when nothing is bound; see onSwap.
            if (panelItem != null) {
                event.setCancelled(true);
                triggerSkill(player, player.getInventory().getItemInMainHand(), panelItem, skillFor(panelItem, panelTrigger), panelTrigger, false);
                return;
            }
            notifyPanelBlocked(player, panelTrigger);
        }
        Long lock = rightClickLocks.get(player.getUniqueId());
        if (lock != null && lock > System.currentTimeMillis()) return;
        // 物品自带技能这一路的闸门。这一路可以走副手回落，所以「主手拿食物右键吃饭」原本会
        // 触发副手 RPG 物品的 R/SR 技能并取消事件——食物吃不上。这就是玩家报的那个现象。
        if (!heldItemGateOpen(player, null)) return;
        boolean sneaking = player.isSneaking();
        RpgItem offhandItem = plugin.getRpgItemManager().findActiveItem(player, EquipmentSlot.OFF_HAND, true);
        RpgItem mainhandItem = plugin.getRpgItemManager().findActiveItem(player, EquipmentSlot.HAND, true);
        RpgItem item = null;
        EquipmentSlot sourceSlot = hand;
        if (sneaking && offhandItem != null && skillEnabled(offhandItem.srSkill())) {
            item = offhandItem;
            sourceSlot = EquipmentSlot.OFF_HAND;
        } else if (hand == EquipmentSlot.OFF_HAND && offhandItem != null && rightClickSkillReady(player, offhandItem, sneaking)) {
            item = offhandItem;
            sourceSlot = EquipmentSlot.OFF_HAND;
        } else if (hand == EquipmentSlot.HAND && mainhandItem != null && rightClickSkillReady(player, mainhandItem, sneaking)) {
            item = mainhandItem;
            sourceSlot = EquipmentSlot.HAND;
        } else if (offhandItem != null && rightClickSkillReady(player, offhandItem, sneaking)) {
            item = offhandItem;
            sourceSlot = EquipmentSlot.OFF_HAND;
        } else if (mainhandItem != null && rightClickSkillReady(player, mainhandItem, sneaking)) {
            item = mainhandItem;
            sourceSlot = EquipmentSlot.HAND;
        }
        if (item == null) return;
        event.setCancelled(true);
        rightClickLocks.put(player.getUniqueId(), System.currentTimeMillis() + 60L);
        ItemStack source = sourceSlot == EquipmentSlot.OFF_HAND ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand();
        if (sneaking && skillEnabled(item.srSkill())) {
            if (skillCooling(player, item, item.srSkill(), "SR")) {
                if (skillEnabled(item.rSkill())) triggerSkill(player, source, item, item.rSkill(), "R", false);
            } else {
                triggerSkill(player, source, item, item.srSkill(), "SR", false);
            }
            return;
        }
        if (skillEnabled(item.rSkill())) triggerSkill(player, source, item, item.rSkill(), "R", false);
    }

    private boolean usesAttackCooldown(FSkillConfig config, boolean useAttackSpeed) {
        return useAttackSpeed || config.attackSpeedCooldown()
                || (config.shortbow() != null && config.shortbow().attackSpeedCooldown());
    }

    private long localCooldownReadyAt(Player player, String key, FSkillConfig config, boolean attackCooldown) {
        if (!config.cooldownEnabled()) return 0L;
        return attackCooldown
                ? plugin.getAttackCooldownManager().readyAt(player)
                : cooldowns.computeIfAbsent(player.getUniqueId(), uuid -> new HashMap<>()).getOrDefault(key, 0L);
    }

    private long globalCooldownReadyAt(Player player, String trigger, FSkillConfig config) {
        if (!config.cooldownEnabled() || !config.globalCooldown()) return 0L;
        return globalCooldowns.computeIfAbsent(player.getUniqueId(), uuid -> new HashMap<>()).getOrDefault(trigger, 0L);
    }

    private boolean cooldownBlocked(Player player, String key, FSkillConfig config, String trigger, boolean attackCooldown) {
        long now = System.currentTimeMillis();
        return localCooldownReadyAt(player, key, config, attackCooldown) > now
                || globalCooldownReadyAt(player, trigger, config) > now;
    }

    private boolean skillCooling(Player player, RpgItem item, FSkillConfig config, String trigger) {
        return cooldownBlocked(player, trigger + ":" + item.id(), config, trigger, usesAttackCooldown(config, false));
    }

    private boolean skillEnabled(FSkillConfig skill) {
        return skill != null && skill.enabled();
    }

    private boolean rightClickSkillReady(Player player, RpgItem item, boolean sneaking) {
        if (!sneaking) return skillEnabled(item.rSkill());
        if (skillEnabled(item.srSkill()) && !skillCooling(player, item, item.srSkill(), "SR")) return true;
        return skillEnabled(item.rSkill());
    }
    private void applySkillCooldown(Player player, String key, FSkillConfig config, String trigger, boolean attackCooldown, double cooldown, double globalCooldown) {
        if (!config.cooldownEnabled()) return;
        if (attackCooldown) {
            plugin.getAttackCooldownManager().start(player, plugin.getRpgItemManager().collectActiveStats(player, false).attackSpeed());
        } else {
            cooldowns.computeIfAbsent(player.getUniqueId(), uuid -> new HashMap<>())
                    .put(key, System.currentTimeMillis() + Math.max(0, Math.round(cooldown * 1000)));
        }
        if (config.globalCooldown()) {
            globalCooldowns.computeIfAbsent(player.getUniqueId(), uuid -> new HashMap<>())
                    .put(trigger, System.currentTimeMillis() + Math.max(0, Math.round(globalCooldown * 1000)));
        }
    }

    /**
     * Resolves which skill a left-click should run, or null when the click is a plain vanilla attack.
     * A binding in the skill panel wins; when nothing is bound the held item's own L/SL skill applies.
     */
    private LeftClickBinding boundLeftClickSkill(Player player) {
        if (!plugin.isFeatureEnabled("fskill")) return null;
        String trigger = player.isSneaking() ? "SL" : "L";
        if (plugin.getSkillPanelManager().isEnabled(player) && plugin.getSkillPanelManager().isTriggerEnabled(player, trigger)) {
            RpgItem panelItem = plugin.getSkillPanelManager().resolve(player, trigger);
            // Fall through to the held item when nothing is bound; see onSwap.
            if (panelItem != null) return new LeftClickBinding(panelItem, skillFor(panelItem, trigger), trigger);
            notifyPanelBlocked(player, trigger);
        }
        RpgItem item = plugin.getRpgItemManager().findActiveMainHandItem(player, true);
        if (item == null) return null;
        FSkillConfig skill = trigger.equals("SL") && item.slSkill() != null && item.slSkill().enabled() ? item.slSkill() : item.lSkill();
        if (skill == null || !skill.enabled()) return null;
        return new LeftClickBinding(item, skill, trigger);
    }

    private record LeftClickBinding(RpgItem item, FSkillConfig skill, String trigger) { }

    /**
     * Whether this player's next left-click is a skill input rather than a vanilla attack.
     * The combat listener uses this to stop charging the shared attack cooldown on the arm swing:
     * a left-click produces both an arm swing and an attack/interact packet, and when the swing is
     * handled first it put the skill on cooldown before the skill handler ever ran, so the skill
     * silently did nothing while the vanilla sweep animation still played.
     */
    public boolean hasBoundLeftClickSkill(Player player) {
        return boundLeftClickSkill(player) != null;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onLeftClick(PlayerInteractEvent event) {
        if (!plugin.isFeatureEnabled("fskill")) return;
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_AIR && action != Action.LEFT_CLICK_BLOCK) return;
        // 对着【方块】左键 = 在挖矿，不是战斗意图，这里必须放行。
        // 原版「左键方块」就是开始挖掘，而下面一旦 setCancelled(true)，方块就完全挖不动了
        // （连挖掘进度都不会开始）。玩家拿着带 L/SL 技能的 RPG 工具挖矿时技能不断触发、
        // 挖掘被打断，根因就在这里。
        // 对着【空气】左键、或左键【实体】（走 onPreAttack / onMeleeHit）才是战斗意图，照常触发。
        if (action == Action.LEFT_CLICK_BLOCK && suppressLeftClickOnBlock()) return;
        Player player = event.getPlayer();
        LeftClickBinding binding = boundLeftClickSkill(player);
        if (binding == null) return;
        event.setCancelled(true);
        triggerSkill(player, player.getInventory().getItemInMainHand(), binding.item(), binding.skill(), binding.trigger(), true);
    }

    /** 对着方块左键时是否不触发 L/SL 技能（挖矿优先）。见 config.yml 的 skill-panel 段。 */
    private boolean suppressLeftClickOnBlock() {
        return plugin.getConfig().getBoolean("skill-panel.suppress-left-click-on-block", true);
    }

    /**
     * Left-click on an entity. This runs before the vanilla attack is built, so cancelling here also
     * removes the vanilla sweep. The sweep particle and its sound are produced inside the vanilla
     * attack method *before* the main target's damage event, so cancelling EntityDamageByEntityEvent
     * stops the sweep damage but still lets the sweep animation play. Cancelling this event is the
     * only lever that removes the animation: Paper 1.21.1 exposes no API that suppresses the sweep
     * on its own, and the particle itself is already sent by the time any damage event arrives.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPreAttack(PrePlayerAttackEntityEvent event) {
        if (!plugin.isFeatureEnabled("fskill")) return;
        if (!event.willAttack()) return;
        Player player = event.getPlayer();
        LeftClickBinding binding = boundLeftClickSkill(player);
        if (binding == null) return;
        event.setCancelled(true);
        triggerSkill(player, player.getInventory().getItemInMainHand(), binding.item(), binding.skill(), binding.trigger(), true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onMeleeHit(EntityDamageByEntityEvent event) {
        if (!plugin.isFeatureEnabled("fskill")) return;
        if (!(event.getDamager() instanceof Player player)) return;
        if (event.getEntity().hasMetadata(SKILL_DAMAGE_METADATA)) return;
        LeftClickBinding binding = boundLeftClickSkill(player);
        if (binding == null) return;
        event.setCancelled(true);
        triggerSkill(player, player.getInventory().getItemInMainHand(), binding.item(), binding.skill(), binding.trigger(), true);
    }

    private FSkillConfig skillFor(RpgItem item, String trigger) {
        return switch (trigger.toUpperCase(Locale.ROOT)) {
            case "Q" -> item.qSkill();
            case "SQ" -> item.sqSkill();
            case "F" -> item.fSkill();
            case "SF" -> item.sfSkill();
            case "R" -> item.rSkill();
            case "SR" -> item.srSkill();
            case "L" -> item.lSkill();
            case "SL" -> item.slSkill();
            default -> null;
        };
    }

    private boolean triggerSkill(Player player, ItemStack itemStack, RpgItem item, FSkillConfig config, String trigger, boolean useAttackSpeed) {
        if (!plugin.isFeatureEnabled("fskill")) return false;
        long now = System.currentTimeMillis();
        Long castReady = castLocks.get(player.getUniqueId());
        if (castReady != null && castReady > now) {
            if (cooldownMessagesEnabled(player, trigger) && messagesEnabled(trigger, config)) player.sendMessage(Texts.color("&e技能释放中，请等待 &f" + String.format(Locale.US, "%.2f", (castReady - now) / 1000.0) + " &e秒"));
            return false;
        }
        if (castReady != null) castLocks.remove(player.getUniqueId());
        String key = trigger + ":" + item.id();
        boolean attackCooldown = usesAttackCooldown(config, useAttackSpeed);
        long localReady = localCooldownReadyAt(player, key, config, attackCooldown);
        if (localReady > now) {
            double left = (localReady - now) / 1000.0;
            if (cooldownMessagesEnabled(player, trigger) && messagesEnabled(trigger, config)) player.sendMessage(Texts.color(skillMessage(trigger, "cooldown-message", "&e{item}&c{trigger}技能冷却: &f{seconds}秒", item, config)
                    .replace("{seconds}", String.format(Locale.US, "%.2f", left))));
            return false;
        }
        long globalReady = globalCooldownReadyAt(player, trigger, config);
        if (globalReady > now) {
            double left = (globalReady - now) / 1000.0;
            if (cooldownMessagesEnabled(player, trigger) && messagesEnabled(trigger, config)) {
                player.sendMessage(Texts.color(globalCooldownMessage(trigger, item, config)
                        .replace("{seconds}", String.format(Locale.US, "%.2f", left))));
            }
            return false;
        }
        if (messagesEnabled(trigger, config) && activationMessagesEnabled(player, trigger)) {
            player.sendMessage(Texts.color(skillMessage(trigger, "activation-message", "&b{item}&f释放了{trigger}技能", item, config)));
        }
        if (config.castTimeSeconds() > 0) castLocks.put(player.getUniqueId(), now + Math.max(1L, Math.round(config.castTimeSeconds() * 1000)));
        double cooldown = attackCooldown
                ? attackCooldownSeconds(plugin.getRpgItemManager().collectActiveStats(player, false, triggerWeapon(player, itemStack)).attackSpeed())
                : (config.cooldownSeconds() > 0 ? config.cooldownSeconds() : plugin.getConfig().getDouble("fskill.default-cooldown-seconds", 5));
        double globalCooldown = config.globalCooldownSeconds() >= 0
                ? config.globalCooldownSeconds()
                : cooldown;
        try {
            // The drop trigger (Q/SQ) removes the weapon from the hand before the event fires, so
            // the hands can be empty during the cast. Remember the stack that started it; the
            // weapon-damage read falls back to it when neither hand holds a real weapon.
            if (itemStack != null && !itemStack.getType().isAir() && !isSkillCarrier(itemStack)) {
                castStacks.put(player.getUniqueId(), itemStack);
            }
            runSkills(player, itemStack, item, config, key, useAttackSpeed);
        } finally {
            castStacks.remove(player.getUniqueId());
            // The cooldown is charged before the effects run and applied even if one throws. A
            // failing skill must still pay its cooldown, otherwise every input re-runs the broken
            // cast and the exception repeats indefinitely. Charging it here also closes the
            // same-tick double-cast window, where a second handler could pass the cooldown check
            // before the first had written it.
            applySkillCooldown(player, key, config, trigger, attackCooldown, cooldown, globalCooldown);
        }
        if (item.consumables()) consumeSkillItem(player, itemStack, item.id());
        double noticeCooldown = Math.max(config.cooldownEnabled() ? cooldown : 0, config.globalCooldown() ? globalCooldown : 0);
        if (noticeCooldown <= 0 || !readyMessagesEnabled(player, trigger) || !messagesEnabled(trigger, config) || trigger.equalsIgnoreCase("L")) return true;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            // 不能在这里要求 holdsItem(player, item.id())：面板绑定的技能是独立技能物品
            // （id 是技能文件名，如「横扫」），玩家释放时手里拿的是武器，永远匹配不上，
            // 于是「技能已就绪」这条提示对面板技能从来没发出去过。只要求玩家还在线即可。
            if (player.isOnline() && readyMessagesEnabled(player, trigger) && messagesEnabled(trigger, config)) {
                player.sendMessage(Texts.color(skillMessage(trigger, "ready-message", "&a{item}&f{trigger}技能已就绪", item, config)));
            }
        }, Math.max(1L, Math.round(noticeCooldown * 20)));
        return true;

    }

    private String skillMessage(String trigger, String suffix, String fallback, RpgItem item, FSkillConfig config) {
        String triggerKey = trigger.toLowerCase(Locale.ROOT);
        String configured = plugin.getConfig().getString("fskill." + triggerKey + "skill-" + suffix);
        if (configured == null || configured.isBlank()) {
            configured = plugin.getConfig().getString("fskill." + suffix);
        }
        if (configured == null || configured.isBlank()) configured = fallback;
        String cooldownName = config.cooldownName();
        boolean cooldownNotice = suffix.equals("ready-message") || suffix.equals("cooldown-message");
        boolean activationNotice = suffix.equals("activation-message");
        if (activationNotice && !cooldownName.isBlank() && !configured.contains("{skill}")) {
            configured = "&b释放了{skill}";
        }
        if (cooldownNotice && !cooldownName.isBlank() && !configured.contains("{skill}")) {
            configured = suffix.equals("ready-message")
                    ? "&a{skill}冷却就绪"
                    : "&e{skill}冷却时间: &f{seconds}秒";
        }
        String skillName = cooldownName.isBlank() ? item.displayName() + trigger + "技能" : cooldownName;
        return configured.replace("{skill}", skillName).replace("{item}", item.displayName()).replace("{trigger}", trigger);
    }

    private void consumeSkillItem(Player player, ItemStack sourceStack, String itemId) {
        if (sourceStack == null || sourceStack.getType().isAir()) return;
        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();
        boolean mainMatches = matchesItem(main, itemId);
        boolean offMatches = matchesItem(off, itemId);
        boolean consumeOff = offMatches && !mainMatches;
        ItemStack target = consumeOff ? off : main;
        if (!matchesItem(target, itemId)) return;
        int amount = target.getAmount();
        if (amount <= 1) {
            if (consumeOff) player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
            else player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
            return;
        }
        ItemStack remaining = target.clone();
        remaining.setAmount(amount - 1);
        if (consumeOff) player.getInventory().setItemInOffHand(remaining);
        else player.getInventory().setItemInMainHand(remaining);
    }

    private boolean matchesItem(ItemStack stack, String itemId) {
        if (stack == null || stack.getType().isAir()) return false;
        RpgItem item = plugin.getRpgItemManager().findItem(stack);
        return item != null && item.id().equalsIgnoreCase(itemId);
    }

    private boolean holdsItem(Player player, String itemId) {
        return matchesItem(player.getInventory().getItemInMainHand(), itemId)
                || matchesItem(player.getInventory().getItemInOffHand(), itemId);
    }

    private void runSkills(Player player, ItemStack originalStack, RpgItem item, FSkillConfig config, String cooldownKey, boolean useAttackSpeed) {
        Runnable release = () -> runReleasedSkills(player, originalStack, item, config);
        if (config.chants().isEmpty()) release.run();
        else runChants(player, config.chants(), release, release, cooldownKey, useAttackSpeed, config);
    }

    @EventHandler
    public void onMagicCirclePluginDisable(PluginDisableEvent event) {
        if (event.getPlugin() != plugin) return;
        for (ItemDisplay display : magicCircleDisplays) if (!display.isDead()) display.remove();
        magicCircleDisplays.clear();
        // The falling weapons are non-persistent display entities owned by the tasks below. If the
        // plugin is disabled mid-cast those tasks are cancelled by Bukkit without running their
        // cleanup, so release the entities here as well.
        for (ItemDisplay display : gateDisplays) if (!display.isDead()) display.remove();
        gateDisplays.clear();
        // 同理由：在飞的弹射物也是非持久化显示实体，插件禁用时必须在这里收掉，
        // 否则每次禁用都会在世界里留下几个再也删不掉的箭。
        for (ItemShot shot : new ArrayList<>(itemShots)) removeItemShot(shot);
        itemShots.clear();
        if (itemShotTask != null) { itemShotTask.cancel(); itemShotTask = null; }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMagicCircleArmorStandInteract(PlayerInteractEntityEvent event) {
        if (event.getRightClicked().hasMetadata(MAGIC_CIRCLE_ARMOR_STAND)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMagicCircleArmorStandDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity().hasMetadata(MAGIC_CIRCLE_ARMOR_STAND)) event.setCancelled(true);
    }
    private void runReleasedSkills(Player player, ItemStack originalStack, RpgItem item, FSkillConfig config) {
        for (ParticleSkill skill : config.particles()) runParticleSkill(player, skill);
        for (SoundSkill skill : config.sounds()) runSoundSkill(player, skill);
        for (BroadcastSkill skill : config.broadcasts()) runBroadcastSkill(player, skill);
        for (MessageSkill skill : config.messages()) runMessageSkill(player, skill);
        for (CommandSkill skill : config.commands()) runCommandSkill(player, skill);
        for (BuffSkill skill : config.buffs()) runBuffSkill(player, skill);
        if (config.roll() != null && config.roll().enabled()) runRollSkill(player, config.roll());
        if (config.dash() != null && config.dash().enabled()) runDashSkill(player, config.dash());
        if (config.backstep() != null && config.backstep().enabled()) runBackstepSkill(player, config.backstep());
        for (RangeBuffSkill skill : config.rangeBuffs()) runRangeBuffSkill(player, skill);
        if (config.attributeBoost() != null) plugin.getRpgItemManager().applyTemporaryAttributeBoost(player, config.attributeBoost().stats(), config.attributeBoost().durationSeconds(), config.attributeBoost().stackable());
        RpgStats stats = plugin.getRpgItemManager().collectActiveStats(player, true, originalStack);
        if (config.shortbow() != null && config.shortbow().enabled()) runShortbow(player, item, stats, config.shortbow());
        if (config.shockwave() != null && config.shockwave().enabled()) runShockwave(player, item, stats, config.shockwave());
        if (config.sweep() != null && config.sweep().enabled()) runSweep(player, item, stats, config.sweep());
        if (config.swing() != null && config.swing().enabled()) runSwing(player, item, stats, config.swing());
        if (config.phantomBlade() != null && config.phantomBlade().enabled()) runPhantomBlade(player, item, stats, config.phantomBlade());
        if (config.swordAura() != null && config.swordAura().enabled()) runSwordAura(player, item, stats, config.swordAura());
        if (config.crossSlash() != null && config.crossSlash().enabled()) runCrossSlash(player, item, stats, config.crossSlash());
        if (config.uppercut() != null && config.uppercut().enabled()) runUppercut(player, item, stats, config.uppercut());
        if (config.leapStrike() != null && config.leapStrike().enabled()) runLeapStrike(player, item, stats, config.leapStrike());
         if (config.magicCircle() != null && config.magicCircle().enabled()) {
            runMagicCircle(player, item, stats, config.magicCircle());
        } else if (magicCircleTraceEnabled(player)) {
            plugin.getLogger().info("[MagicCircle] 未运行：magic-circle 段"
                    + (config.magicCircle() == null
                    ? "没有解析到（检查 fskill.magic-circle 是否存在、层级是否正确）"
                    : "存在但 enabled()=false（需要 duration-seconds>0，且 damage/weapon-percent 至少一项大于 0）"));
        }
        GateOfBabylonSkill gate = config.gateOfBabylon();
        if (gate != null && gate.enabled()) {
            if (magicCircleTraceEnabled(player)) {
                // 正常施放也打一条，否则「只有声音没有武器」时无法区分到底有没有解析到——
                // 旧版只在 未运行 时才打日志，而 施放中 的打印是判断问题的关键基准。
                plugin.getLogger().info("[GateOfBabylon] 运行中：weapon-count=" + gate.weaponCount()
                        + " spawn-mode=" + gate.spawnMode()
                        + " 材质池=" + gate.weaponMaterials().size()
                        + " damage=" + (gate.damage() == null ? "null" : format(gate.damage().damage()))
                        + " weapon-percent=" + (gate.damage() == null ? "null" : format(gate.damage().weaponPercent())));
            }
            runGateOfBabylon(player, item, stats, gate);
        } else if (magicCircleTraceEnabled(player)) {
            plugin.getLogger().info("[GateOfBabylon] 未运行：gate-of-babylon 段"
                    + (gate == null
                    ? "没有解析到（检查 fskill.gate-of-babylon 是否存在、层级是否正确）"
                    : "存在但 enabled()=false（需要 weapon-count>0、weapon-materials 可解析、且 damage/weapon-percent 至少一项大于 0）"));
        }
        if (config.dimensionalSlash() != null && (config.dimensionalSlash().damage() > 0 || config.dimensionalSlash().weaponPercent() > 0)) {
            // 若连锁声明了「施放时 → 次元斩（延迟）」，这一段就不能在这里立刻放，
            // 否则会先打一次即时次元斩、1 秒后再打一次。见 delayedByCastChain。
            if (delayedByCastChain(config, "dimensional-slash")) {
                if (magicCircleTraceEnabled(player)) {
                    plugin.getLogger().info("[DimensionalSlash] 本次不即时释放：chain.after=cast 且 chain.next=dimensional-slash，"
                            + "将由连锁在 " + config.chain().delaySeconds() + " 秒后释放。");
                }
            } else {
                runDimensionalSlash(player, item, stats, config.dimensionalSlash(), config.dimensionalLineParticle());
                scheduleChain(player, item, config, "dimensional-slash");
            }
        }
        for (DamageSkill skill : config.damages()) runDamageSkill(player, item, stats, skill);
        for (SwitchSkill skill : config.switches()) runSwitchSkill(player, originalStack, skill);
        // 连锁的「释放时」分支：本次施放的即时效果（位移 / 增益 / 粒子）都已经跑完，
        // 这时才安排延迟段。次元斩就是靠这条：后撤 + 缓慢 → 1 秒后放出次元斩本身。
        scheduleChain(player, item, config, "cast");
    }

    /**
     * 技能连锁：本次施放里 {@code completedSkill} 这一段完成后，延迟 {@code delay-seconds}
     * 再放出 {@code next} 指定的那一段。
     * <p>
     * {@code after} 现在支持三类取值：
     * <ul>
     *   <li>{@code cast}（或 {@code start}）—— 本次施放的即时效果跑完就安排（用于
     *       「先位移/上减速，1 秒后才真正出招」这种前置动作型技能）；</li>
     *   <li>{@code dimensional-slash} —— 次元斩那一段跑完后；</li>
     *   <li>其他段名同理，只要在 {@link #runChainedSkill} 里有对应分支。</li>
     * </ul>
     */
    private void scheduleChain(Player player, RpgItem item, FSkillConfig config, String completedSkill) {
        SkillChain chain = config.chain();
        if (chain == null || !chain.after().equalsIgnoreCase(completedSkill) || chain.next().isBlank()) return;
        long delay = Math.max(0L, Math.round(chain.delaySeconds() * 20));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            RpgStats stats = plugin.getRpgItemManager().collectActiveStats(player, true);
            runChainedSkill(player, item, config, stats, chain.next());
        }, delay);
    }

    /**
     * 本次施放是否把 {@code sectionName} 这一段交给「施放时 → 延迟」的连锁去放。
     * <p>
     * {@code after: cast} 表示「本次施放的即时部分跑完就安排延迟段」，因此被它点名的段
     * 不能在即时流程里再放一次——次元斩的 yml 同时有 {@code backstep}、{@code buffs}
     * 和 {@code dimensional-slash}，靠这个判断才不会一次施放打出两次次元斩。
     */
    private boolean delayedByCastChain(FSkillConfig config, String sectionName) {
        SkillChain chain = config.chain();
        if (chain == null || chain.next().isBlank()) return false;
        String after = chain.after().toLowerCase(Locale.ROOT);
        if (!after.equals("cast") && !after.equals("start")) return false;
        return chain.next().toLowerCase(Locale.ROOT).replace("_", "-").equals(sectionName);
    }

    /** 连锁的目标段名 → 实际执行。段名大小写不敏感，写法与 yml 里的节点名一致。 */
    private void runChainedSkill(Player player, RpgItem item, FSkillConfig config, RpgStats stats, String next) {
        String target = next.toLowerCase(Locale.ROOT).replace("_", "-");
        switch (target) {
            case "shortbow" -> {
                if (config.shortbow() != null) runShortbow(player, item, stats, config.shortbow());
            }
            case "attribute-boost" -> {
                if (config.attributeBoost() != null) {
                    plugin.getRpgItemManager().applyTemporaryAttributeBoost(player, config.attributeBoost().stats(),
                            config.attributeBoost().durationSeconds(), config.attributeBoost().stackable());
                }
            }
            case "dimensional-slash" -> {
                if (config.dimensionalSlash() != null) {
                    runDimensionalSlash(player, item, stats, config.dimensionalSlash(), config.dimensionalLineParticle());
                    scheduleChain(player, item, config, "dimensional-slash");
                }
            }
            case "uppercut" -> {
                if (config.uppercut() != null) runUppercut(player, item, stats, config.uppercut());
            }
            case "leap-strike" -> {
                if (config.leapStrike() != null) runLeapStrike(player, item, stats, config.leapStrike());
            }
            case "backstep" -> {
                if (config.backstep() != null) runBackstepSkill(player, config.backstep());
            }
            default -> {
            }
        }
    }

    private void runChants(Player player, List<ChantSkill> chants, Runnable releaseAtStartAction, Runnable releaseAtEndAction, String cooldownKey,
                           boolean useAttackSpeed, FSkillConfig config) {
        cancelChant(player.getUniqueId(), false);
        ChantSession session = new ChantSession(cooldownKey, cooldownKey.substring(0, cooldownKey.indexOf(':')), useAttackSpeed,
                config.globalCooldown(), chants.stream().anyMatch(ChantSkill::interruptOnItemSwitch));
        chantSessions.put(player.getUniqueId(), session);
        long delayTicks = 0;
        boolean releaseAtStart = chants.stream().anyMatch(ChantSkill::releaseAtStart);
        boolean releaseAtEnd = chants.stream().anyMatch(ChantSkill::releaseAtEnd);
        if (releaseAtStart) releaseAtStartAction.run();
        for (ChantSkill chant : chants) {
            long intervalTicks = Math.max(1L, Math.round(chant.intervalSeconds() * 20));
            for (ChantStage stage : chant.stages()) {
                long currentDelay = delayTicks;
                BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (chantSessions.get(player.getUniqueId()) != session) return;
                    if (!player.isOnline()) return;
                    if (!stage.text().isBlank()) player.sendMessage(Texts.color(stage.text().replace("{player}", player.getName())));
                     for (BuffSkill buff : stage.buffs()) runBuffSkill(player, buff);
                    ParticleConfig particle = stage.particle();
                    if (particle != null && particle.enabled()) {
                        String effect = particle.effect().toLowerCase(Locale.ROOT);
                        if (effect.equals("contract") || effect.equals("收缩")) {
                            BukkitTask contractTask = particleEffectPlayer.playContract(player, particle, chant.intervalSeconds());
                            if (contractTask != null) session.tasks().add(contractTask);
                        }
                        else particleEffectPlayer.playAlways(player.getLocation(), particle);
                    }
                    playSound(player.getLocation(), stage.sound());
                }, currentDelay);
                session.tasks().add(task);
                delayTicks += intervalTicks;
            }
        }
        long completionDelay = Math.max(1L, delayTicks);
        BukkitTask completion = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (chantSessions.remove(player.getUniqueId(), session) && releaseAtEnd) releaseAtEndAction.run();
        }, completionDelay);
        session.tasks().add(completion);
    }

    private void interruptChant(Player player) {
        ChantSession session = chantSessions.get(player.getUniqueId());
        if (session == null || !session.interruptOnItemSwitch()) return;
        cancelChant(player.getUniqueId(), true);
        player.sendMessage(Texts.color(plugin.getConfig().getString("fskill.chant-interrupted-message", "&c切换物品，吟唱已中断，技能冷却已重置。")));
    }

    private void cancelChant(UUID playerId, boolean resetCooldown) {
        ChantSession session = chantSessions.remove(playerId);
        if (session == null) return;
        for (BukkitTask task : session.tasks()) if (task != null && !task.isCancelled()) task.cancel();
        if (!resetCooldown) return;
        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null) return;
        if (session.useAttackSpeed()) plugin.getAttackCooldownManager().reset(player);
        else cooldowns.computeIfAbsent(playerId, uuid -> new HashMap<>()).remove(session.cooldownKey());
        if (session.globalCooldown()) {
            globalCooldowns.computeIfAbsent(playerId, uuid -> new HashMap<>()).remove(session.trigger());
        }
    }

    private record ChantSession(String cooldownKey, String trigger, boolean useAttackSpeed, boolean globalCooldown,
                                boolean interruptOnItemSwitch, List<BukkitTask> tasks) {
        private ChantSession(String cooldownKey, String trigger, boolean useAttackSpeed, boolean globalCooldown,
                              boolean interruptOnItemSwitch) {
            this(cooldownKey, trigger, useAttackSpeed, globalCooldown, interruptOnItemSwitch, new ArrayList<>());
        }
    }

    private void runCommandSkill(Player player, CommandSkill skill) {
        long delayTicks = 0;
        for (String configured : skill.commands()) {
            if (configured == null || configured.isBlank()) continue;
            long stepDelay = parseCommandDelay(configured);
            if (stepDelay >= 0) {
                delayTicks += stepDelay;
                continue;
            }
            String message = extractWrappedValue(configured, "message", "m");
            String command = extractWrappedValue(configured, "command", "c");
            if (message != null) {
                String text = message;
                scheduleCommandStep(player, delayTicks, () -> player.sendMessage(Texts.color(replaceCommandVariables(text, player))));
                continue;
            }
            if (command == null) command = configured;
            String finalCommand = command;
            scheduleCommandStep(player, delayTicks, () -> dispatchCommand(player, finalCommand, skill.asOp()));
        }
    }

    private long parseCommandDelay(String configured) {
        String value = configured.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("delay")) return -1;
        value = value.substring(5).trim();
        if (value.startsWith(":")) value = value.substring(1).trim();
        try {
            return Math.max(0, Math.round(Double.parseDouble(value)));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private String extractWrappedValue(String configured, String type, String key) {
        String value = configured.trim();
        String prefix = type + "{" + key + "=";
        if (!value.regionMatches(true, 0, prefix, 0, prefix.length())) return null;
        int start = prefix.length();
        if (start >= value.length()) return null;
        char quote = value.charAt(start);
        int end;
        if (quote == '"' || quote == '\'') {
            // The command may contain JSON quotes; the wrapper closes at the quote before its final brace.
            // 命令内容可能包含 JSON 引号，外层参数应取最后一个闭合引号。
            int closingBrace = value.lastIndexOf('}');
            end = closingBrace > start ? value.lastIndexOf(quote, closingBrace) : -1;
        } else {
            end = value.indexOf('}', start);
        }
        if (end < 0) return null;
        return value.substring(quote == '"' || quote == '\'' ? start + 1 : start, end);
    }

    private void scheduleCommandStep(Player player, long delayTicks, Runnable action) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) action.run();
        }, Math.max(0, delayTicks));
    }

    private void dispatchCommand(Player player, String configured, boolean asOp) {
        String command = replaceCommandVariables(configured, player).trim();
        if (command.startsWith("/")) command = command.substring(1);
        if (command.isBlank()) return;
        if (asOp) plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), command);
        else plugin.getServer().dispatchCommand(player, command);
    }

    private String replaceCommandVariables(String value, Player player) {
        return value.replace("${player}", player.getName())
                .replace("{player}", player.getName())
                .replace("@self", player.getName());
    }

    private void runParticleSkill(Player player, ParticleSkill skill) {
        Runnable action = () -> particleEffectPlayer.playAlways(player.getLocation(), skill.particle());
        if (!skill.repeating()) action.run();
        else repeat(Math.max(1, skill.durationSeconds()), Math.max(0.05, skill.intervalSeconds()), action);
    }

    private void runSoundSkill(Player player, SoundSkill skill) {
        Runnable action = () -> playSound(player.getLocation(), skill.sound());
        if (!skill.repeating()) action.run();
        else repeat(Math.max(0.1, skill.durationSeconds()), Math.max(0.05, skill.intervalSeconds()), action);
    }

    private void runBroadcastSkill(Player player, BroadcastSkill skill) {
        player.sendTitle(color(skill.color() + skill.text()), "", 5, Math.max(1, (int) Math.round(skill.durationSeconds() * 20)), 10);
    }

    private void runMessageSkill(Player player, MessageSkill skill) {
        Runnable action = () -> sendMessages(player, skill);
        if (!skill.repeating()) action.run();
        else repeat(Math.max(0.1, skill.durationSeconds()), Math.max(0.05, skill.intervalSeconds()), action);
    }

    private void sendMessages(Player player, MessageSkill skill) {
        List<String> texts = skill.texts().isEmpty() ? List.of("&7技能的风吹过。") : skill.texts();
        if (skill.selfOnly()) {
            for (String text : texts) player.sendMessage(Texts.color(text.replace("{player}", player.getName())));
            return;
        }
        for (Player target : player.getWorld().getNearbyPlayers(player.getLocation(), Math.max(1, skill.radius()))) {
            for (String text : texts) target.sendMessage(Texts.color(text.replace("{player}", player.getName())));
        }
    }

    private void runDashSkill(Player player, DashSkill skill) {
        Vector direction = player.getLocation().getDirection().setY(0).normalize();
        if (direction.lengthSquared() <= 0.01) return;
        plugin.getActiveDefenseListener().armSkillDodge(player, skill.durationTicks());
        player.setVelocity(direction.multiply(Math.max(0.1, skill.distance() / Math.max(1, skill.durationTicks()))).setY(0.15));
        player.setNoDamageTicks(Math.max(player.getNoDamageTicks(), skill.invulnerabilityTicks()));
        if (skill.particle() != null && skill.particle().enabled()) particleEffectPlayer.playAlways(player.getLocation(), skill.particle());
        playSound(player.getLocation(), skill.sound());
    }

    /**
     * 后撤：与突进完全同一套实现，唯一区别是方向固定<b>向后</b>（视线方向取反）。
     * 刻意不复用 {@link #runDashSkill}：后撤不吃移动输入（翻滚才吃），否则一边后退一边
     * 按 W 会把人往前送，和「后撤」这个动作矛盾。
     */
    private void runBackstepSkill(Player player, BackstepSkill skill) {
        Vector facing = player.getLocation().getDirection().setY(0).normalize();
        if (facing.lengthSquared() <= 0.01) return;
        Vector direction = facing.multiply(-1);
        plugin.getActiveDefenseListener().armSkillDodge(player, skill.durationTicks());
        player.setVelocity(direction.multiply(Math.max(0.1, skill.distance() / Math.max(1, skill.durationTicks()))).setY(0.15));
        player.setNoDamageTicks(Math.max(player.getNoDamageTicks(), skill.invulnerabilityTicks()));
        if (skill.particle() != null && skill.particle().enabled()) particleEffectPlayer.playAlways(player.getLocation(), skill.particle());
        playSound(player.getLocation(), skill.sound());
    }

    private void runRollSkill(Player player, RollSkill skill) {
        Vector input = movementInputs.getOrDefault(player.getUniqueId(), new Vector());
        Vector direction = input.lengthSquared() > 0.01
                ? input.clone().setY(0).normalize()
                : player.getLocation().getDirection().setY(0).normalize();
        if (direction.lengthSquared() <= 0.01) return;
        plugin.getActiveDefenseListener().armSkillDodge(player, skill.durationTicks());
        player.setVelocity(direction.multiply(Math.max(0.1, skill.distance() / Math.max(1, skill.durationTicks()))).setY(0.15));
        player.setNoDamageTicks(Math.max(player.getNoDamageTicks(), skill.invulnerabilityTicks()));
        if (skill.particle() != null && skill.particle().enabled()) particleEffectPlayer.playAlways(player.getLocation(), skill.particle());
        playSound(player.getLocation(), skill.sound());
    }

    private void runRangeBuffSkill(Player player, RangeBuffSkill skill) {
        Location center = player.getLocation();
        String targetMode = skill.target().toUpperCase(Locale.ROOT);
        for (LivingEntity target : center.getNearbyLivingEntities(skill.radius(), skill.height(), skill.radius())) {
            if (target.equals(player) && !skill.includeSelf()) continue;
            boolean friendly = isFriendlyTarget(target);
            if (targetMode.equals("FRIENDLY") && !friendly) continue;
            if (targetMode.equals("ENEMY") && friendly) continue;
            for (BuffSkill effect : skill.effects()) {
                PotionEffectType type = PotionEffectType.getByName(effect.type().toUpperCase(Locale.ROOT));
                if (type != null) target.addPotionEffect(new PotionEffect(type, Math.max(1, (int) Math.round(effect.durationSeconds() * 20)), Math.max(0, effect.amplifier()), true, true, true));
            }
        }
        if (skill.particle() != null && skill.particle().enabled()) particleEffectPlayer.playAlways(center, skill.particle());
    }

    private void runBuffSkill(Player player, BuffSkill skill) {
        PotionEffectType type = PotionEffectType.getByName(skill.type().toUpperCase(Locale.ROOT));
        if (type != null) player.addPotionEffect(new PotionEffect(type, Math.max(1, (int) Math.round(skill.durationSeconds() * 20)), Math.max(0, skill.amplifier()), true, true, true));
    }

    private void runSwitchSkill(Player player, ItemStack originalStack, SwitchSkill skill) {
        ItemStack replacement = null;
        if (skill.rpgItem() != null && !skill.rpgItem().isBlank()) {
            RpgItem target = plugin.getRpgItemManager().findById(skill.rpgItem());
            if (target != null) replacement = plugin.getRpgItemManager().createItem(target);
        } else if (skill.material() != null && !skill.material().isBlank()) {
            Material material = Material.matchMaterial(skill.material());
            if (material != null && originalStack != null) {
                replacement = originalStack.clone();
                replacement.setType(material);
            }
        }
        if (replacement == null) return;
        ItemStack before = originalStack == null ? null : originalStack.clone();
        player.getInventory().setItemInMainHand(replacement);
        // The main hand just changed without an inventory click, so the memoised settlement holds
        // the pre-switch weapon. Drop it before anything else settles this player in this tick.
        plugin.getRpgItemManager().invalidateStatsCache(player);
        if (skill.switchedItemCooldown()) player.setCooldown(replacement.getType(), Math.max(1, (int) Math.round(plugin.getConfig().getDouble("fskill.default-cooldown-seconds", 5) * 20)));
        if (skill.timed()) plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            player.getInventory().setItemInMainHand(before);
            // Restoring the pre-switch weapon is another silent hand change: invalidate again, or
            // the tick that sees the restored weapon would reuse the switched-form settlement.
            plugin.getRpgItemManager().invalidateStatsCache(player);
        }, Math.max(1L, Math.round(skill.durationSeconds() * 20)));
    }

    private void runShortbow(Player player, RpgItem item, RpgStats stats, ShortbowSkill skill) {
        // 连发（三连射）：每一批按 volley-interval-ticks 错开，第一批立刻打。
        int volleys = Math.max(1, skill.volleys());
        long interval = Math.max(0, skill.volleyIntervalTicks());
        for (int volley = 0; volley < volleys; volley++) {
            long delay = (long) volley * interval;
            if (delay == 0) launchShortbowVolley(player, item, stats, skill);
            else plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> launchShortbowVolley(player, item, stats, skill), delay);
        }
    }

    /**
     * 一批弹射物。每一批都<b>重新读取</b>玩家当前视线：连发期间玩家可能已经转向，
     * 用第一批的方向打完三发会显得僵硬；同时每批各自播一次发射音效——原先音效只在
     * 第一批播，所以「三连射只有第一下有声音」。
     */
    private void launchShortbowVolley(Player player, RpgItem item, RpgStats stats, ShortbowSkill skill) {
        if (!player.isOnline()) return;
        ItemProjectile projectile = skill.projectile();
        if (projectile == null) return;
        Vector aim = player.getEyeLocation().getDirection().normalize();
        List<Vector> directions = skill.coneSpread()
                ? coneSpreadDirections(aim, skill.arrowCount(), skill.spreadDegrees())
                : lineSpreadDirections(aim, skill.arrowCount(), skill.spreadDegrees());
        for (Vector direction : directions) {
            launchItemProjectile(player, item, stats, skill, projectile, direction);
        }
        Location soundAt = player.getLocation();
        player.getWorld().playSound(soundAt, skill.launchSound(), skill.soundVolume(), skill.soundPitch());
    }

    /**
     * 一个物品弹射物：本体是一个 {@code ItemDisplay}（物品图标），自己推进、自己判命中。
     * <p>
     * 刻意不用原版箭实体：原版箭的穿透要满蓄力/暴击才生效、命中即被移除、也无法用任意物品
     * 当本体。这里用与「王之宝库」相同的机制——{@code NONE} transform + 朝向飞行方向，
     * 于是本体外观就是配置的物品，穿透数量也不受原版限制。
     */
    private void launchItemProjectile(Player player, RpgItem item, RpgStats stats, ShortbowSkill skill,
                                      ItemProjectile projectile, Vector direction) {
        Material material = Material.matchMaterial(projectile.itemMaterial());
        if (material == null || material.isAir()) material = Material.SPECTRAL_ARROW;
        Location spawnAt = player.getEyeLocation().clone().add(direction.clone().multiply(0.6));
        spawnAt.setYaw(0.0F);
        spawnAt.setPitch(0.0F);
        ItemStack stack = new ItemStack(material);
        if (projectile.glow()) {
            ItemMeta meta = stack.getItemMeta();
            meta.setEnchantmentGlintOverride(true);
            stack.setItemMeta(meta);
        }
        final Material displayMaterial = material;
        final ItemStack displayStack = stack;
        ItemDisplay display;
        try {
            display = spawnAt.getWorld().spawn(spawnAt, ItemDisplay.class, entity -> {
                entity.setItemStack(displayStack);
                // NONE = 物品按模型原样绘制（非 FIXED transform 会烘进旋转/缩放，就没法再瞄准了）。
                entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                entity.setBillboard(Display.Billboard.FIXED);
                entity.setInvulnerable(true);
                entity.setPersistent(false);
                entity.setShadowRadius(0);
                entity.setShadowStrength(0);
                entity.setBrightness(new Display.Brightness(15, 15));
                entity.setTeleportDuration(1);
            });
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.WARNING, "弹射物实体生成失败：" + displayMaterial, failure);
            return;
        }
        ItemShot shot = new ItemShot(display, player, item, stats, skill, projectile,
                spawnAt.clone(), direction.clone().normalize());
        faceItemProjectile(shot);
        itemShots.add(shot);
        if (projectile.trailParticle() != null && projectile.trailParticle().enabled()) {
            drawItemTrail(spawnAt, spawnAt, projectile.trailParticle());
        }
        startItemShotTask();
    }

    /**
     * 让弹射物朝向飞行方向。
     * <p>
     * 与「王之宝库」用同一套两步旋转：先把贴图在自身平面内按 {@code tilt-degrees} 摆正
     * （原版箭/光灵箭贴图里的箭头是斜的，不摆正就会斜着飞），再把局部 +Y 转到飞行向量上，
     * 最后绕飞行轴滚一下让贴图面垂直于飞行线。直上/直下是最短弧旋转的退化情形，单独处理。
     */
    private void faceItemProjectile(ItemShot shot) {
        if (shot.display == null || shot.display.isDead()) return;
        Vector dir = shot.direction;
        Vector3f blade = new Vector3f((float) dir.getX(), (float) dir.getY(), (float) dir.getZ());
        if (blade.lengthSquared() < 1.0E-6f) blade.set(0f, 1f, 0f);
        blade.normalize();
        float tilt = (float) Math.toRadians(shot.projectile.tiltDegrees());
        // 右旋转：贴图平面内摆正
        Quaternionf right = new Quaternionf().rotationZ(tilt);
        // 左旋转：把局部 +Y 转到飞行方向，并绕该轴滚到贴图面垂直于飞行线
        Quaternionf left = itemAimRotation(blade, tilt);
        float scale = (float) Math.max(0.1, shot.projectile.scale());
        shot.display.setTransformation(new Transformation(
                new Vector3f(0f, 0f, 0f), left,
                new Vector3f(scale, scale, scale), right));
        shot.display.setRotation(0.0F, 0.0F);
    }

    /**
     * 把局部 +Y 转到 {@code blade}，再绕 {@code blade} 滚一下让贴图面垂直于飞行线。
     * 与 {@code gateAimRotation} 同一套做法（那边是剑，这边是箭），所以两者的朝向手感一致。
     */
    private Quaternionf itemAimRotation(Vector3f blade, float tilt) {
        Quaternionf aim = new Quaternionf();
        if (blade.y < -0.9999f) {
            // 反平行：最短弧旋转没有唯一轴，改为在贴图平面内翻转 180°。
            aim.rotationZ((float) Math.PI);
        } else {
            aim.rotationTo(new Vector3f(0f, 1f, 0f), blade);
        }
        Vector3f face = new Vector3f(0f, 0f, 1f);
        new Quaternionf().rotationZ(tilt).transform(face);
        aim.transform(face);
        Vector3f perpendicular = face.sub(new Vector3f(blade).mul(face.dot(blade)));
        if (perpendicular.lengthSquared() < 1.0E-6f) return aim;
        perpendicular.normalize();
        if (Math.abs(blade.x) < 1.0E-6f && Math.abs(blade.z) < 1.0E-6f) return aim;
        Vector3f horizontal = new Vector3f(blade.x, 0f, blade.z).normalize();
        Vector3f target = new Vector3f(-horizontal.z, 0f, horizontal.x);
        target.sub(new Vector3f(blade).mul(target.dot(blade)));
        if (target.lengthSquared() < 1.0E-6f) return aim;
        target.normalize();
        float angle = (float) Math.atan2(
                new Vector3f(perpendicular).cross(target).dot(blade),
                perpendicular.dot(target));
        return new Quaternionf().rotationAxis(angle, blade).mul(aim);
    }

    /** 弹射物每 tick 推进 + 命中判定。所有在飞的弹射物共用一个 task。 */
    private void startItemShotTask() {
        if (itemShotTask != null) return;
        itemShotTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (itemShots.isEmpty()) {
                if (itemShotTask != null) itemShotTask.cancel();
                itemShotTask = null;
                return;
            }
            for (ItemShot shot : new ArrayList<>(itemShots)) advanceItemShot(shot);
        }, 1L, 1L);
    }

    /**
     * 推进一个弹射物一 tick：飞行 → 方块碰撞 → 生物命中（线段判定）→ 超距消散。
     * <p>
     * 命中判定用<b>线段</b>而不是当前位置的点：速度大于 1 格/tick 时一 tick 会跳过好几格，
     * 只测当前位置会让高速弹直接穿过站位不巧的目标（王之宝库为此踩过同样的坑）。
     */
    private void advanceItemShot(ItemShot shot) {
        if (shot.display == null || shot.display.isDead()) { removeItemShot(shot); return; }
        // 施法者中途下线：直接收掉，避免在世界里留下一个再也删不掉的显示实体。
        if (!shot.player.isOnline()) { removeItemShot(shot); return; }
        ItemProjectile projectile = shot.projectile;
        Location from = shot.lastPosition;
        Location to = from.clone().add(shot.direction.clone().multiply(shot.skill.speed()));
        // 方块碰撞：撞墙即停（爆炸箭就地爆，普通箭直接消散）
        if (projectile.collideBlocks() && !isPassable(to)) {
            shot.lastPosition = to;
            finishItemShot(shot, to, null);
            return;
        }
        Location point = to.clone();
        point.setYaw(0.0F);
        point.setPitch(0.0F);
        shot.lastPosition = point;
        shot.travelled += from.distance(point);
        shot.display.teleport(point);
        drawItemTrail(from, point, projectile.trailParticle());
        // 命中生物：找线段上最近的一个
        LivingEntity target = findItemCollision(shot, from, point);
        if (target != null) {
            if (shot.pierced.add(target.getUniqueId())) {
                hitItemTarget(shot, target, point);
            }
            // 穿透数用尽 → 停下；否则继续飞（同一个目标不会被打第二次）
            if (shot.pierced.size() > Math.max(0, projectile.pierce())) {
                finishItemShot(shot, point, target);
                return;
            }
        }
        if (shot.travelled >= projectile.maxDistance()) finishItemShot(shot, point, null);
    }

    /** 线段命中判定：返回这一步经过的最近一个生物。 */
    private LivingEntity findItemCollision(ItemShot shot, Location from, Location to) {
        double radius = Math.max(0.2, shot.projectile.hitRadius());
        Location mid = from.clone().add(to.toVector().subtract(from.toVector()).multiply(0.5));
        double reach = from.distance(to) / 2.0 + radius;
        LivingEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (LivingEntity candidate : mid.getWorld().getNearbyLivingEntities(mid, reach, reach, reach)) {
            // 施法者本人永远不是目标，否则一发贴脸弹会先打到自己。
            if (candidate.equals(shot.player) || candidate.isDead()) continue;
            if (shot.pierced.contains(candidate.getUniqueId())) continue;
            if (candidate instanceof Player other
                    && (other.getGameMode() == GameMode.CREATIVE || other.getGameMode() == GameMode.SPECTATOR)) continue;
            // 对躯干中心判定，而不是脚底：擦身而过的弹应该打中它明显穿过的身体。
            Vector centre = candidate.getLocation().toVector()
                    .add(new Vector(0, candidate.getHeight() / 2.0, 0));
            double distance = distanceToSegment(centre, from.toVector(), to.toVector());
            if (distance > radius) continue;
            if (distance < nearestDistance) { nearestDistance = distance; nearest = candidate; }
        }
        return nearest;
    }

    /** 命中一个生物：结算一次技能伤害 + 命中粒子/音效。 */
    private void hitItemTarget(ItemShot shot, LivingEntity target, Location point) {
        if (!shot.skill.damageEnabled()) return;
        DamageSkill damage = itemProjectileDamage(shot.skill, shot.projectile);
        Set<UUID> hit = new HashSet<>();
        damageTarget(shot.player, shot.item, target, shot.stats, damage, hit);
        try {
            particleEffectPlayer.playImpactBurst(point.clone().add(0, 0.2, 0), shot.projectile.impactParticle());
        } catch (RuntimeException ignored) {
            // 粒子名写错不该影响伤害结算
        }
        playSound(point, shot.projectile.impactSound());
    }

    /** 弹射物结束：命中爆炸（爆炸箭）+ 消散粒子 + 移除实体。 */
    private void finishItemShot(ItemShot shot, Location point, LivingEntity hitTarget) {
        removeItemShot(shot);
        ItemProjectile projectile = shot.projectile;
        boolean explosive = projectile.explosive();
        // 爆炸箭不再单独播「命中音」：爆炸音本身就是这次命中的声音，两个一起播会糊在一起。
        if (!explosive) {
            try {
                particleEffectPlayer.playImpactBurst(point.clone().add(0, 0.2, 0), projectile.impactParticle());
            } catch (RuntimeException ignored) {
            }
            playSound(point, projectile.impactSound());
        }
        if (explosive) explodeItemProjectile(shot, point);
    }

    /**
     * 爆炸箭的命中爆炸：粒子模拟爆炸 + 范围技能伤害。
     * <p>
     * 不生成爆炸实体、不破坏方块、不点燃，所以没有原版爆炸烟团粒子——爆炸观感完全由
     * {@code impact-explosion.particle} 模拟。
     */
    private void explodeItemProjectile(ItemShot shot, Location center) {
        ItemProjectile projectile = shot.projectile;
        ProjectileExplosion explosion = projectile.explosion();
        if (explosion == null || !explosion.enabled()) return;
        double radius = Math.max(0.5, explosion.radius());
        try {
            // 用专用的「大爆炸」而不是按 count 撒点的 playImpactBurst：后者配置 40 就真的只有
            // 40 颗，半径一大就稀。playBigExplosion 会按半径给出密集球 + 地面环 + 上升柱。
            particleEffectPlayer.playBigExplosion(center.clone().add(0, 0.3, 0), explosion.particle(), radius);
        } catch (RuntimeException ignored) {
            // 粒子名写错不该影响伤害结算
        }
        playSound(center, explosion.sound());
        // 伤害类型与来源过滤取自配置：原先这里写死 PHYSICAL + 空排除列表，
        // 法师的爆炸类法术（魔爆弹）会按物理结算，与本体伤害类型不一致。
        String explosionType = explosion.damageType() == null || explosion.damageType().isBlank()
                ? "PHYSICAL" : explosion.damageType();
        List<String> explosionSources = explosion.weaponPercentSources() == null
                ? List.of() : explosion.weaponPercentSources();
        DamageSkill damage = new DamageSkill("area", explosion.damage(), explosion.weaponPercent(), 0,
                radius, radius, false, 0, 1, true, false, true, 1, 0, false, 0,
                explosionType, explosionSources, false, explosion.particle(), explosion.sound(), 0);
        Set<UUID> hit = new HashSet<>();
        for (LivingEntity target : center.getNearbyLivingEntities(radius, radius, radius)) {
            if (target.equals(shot.player) || target.isDead()) continue;
            if (target instanceof Player other
                    && (other.getGameMode() == GameMode.CREATIVE || other.getGameMode() == GameMode.SPECTATOR)) continue;
            damageTarget(shot.player, shot.item, target, shot.stats, damage, hit);
        }
    }

    private void removeItemShot(ItemShot shot) {
        itemShots.remove(shot);
        if (shot.display != null && !shot.display.isDead()) shot.display.remove();
    }

    /** 把短弓配置翻成一次伤害结算用的 DamageSkill。 */
    private DamageSkill itemProjectileDamage(ShortbowSkill skill, ItemProjectile projectile) {
        // damageType 与 weaponPercentSources 必须取自配置。
        // 这里原先把它们写死成 "PHYSICAL" + List.of()（空排除列表 = 不排除任何来源），
        // 于是法师飞弹在 yml 里写的 damage-type: MAGIC 和 weapon-percent-sources 全部失效，
        // 近战面板照常参与倍率，表现就是「飞弹命中造成 1 点物理伤害」。
        String damageType = skill.damageType() == null || skill.damageType().isBlank()
                ? "PHYSICAL" : skill.damageType();
        List<String> sources = skill.weaponPercentSources() == null
                ? List.of() : skill.weaponPercentSources();
        return new DamageSkill("projectile", 0, skill.weaponPercent(), 0,
                Math.max(0.2, projectile.hitRadius()), Math.max(0.2, projectile.hitRadius()),
                false, 0, 1, true, false, true, 1, 0, false, 0,
                damageType, sources, true, projectile.trailParticle(), projectile.impactSound(), 0);
    }

    /**
     * 霰弹锥形散布：朝准星前方张开一个锥，弹道在锥内均匀分布。
     * <p>
     * 与「绕瞄准轴转一圈的圆环」完全不同——圆环是垂直于视线的平面，所以看起来朝四面八方射；
     * 锥形是<b>顺着视线</b>散开，抬头时整个锥跟着抬起。几何计算在
     * {@link SpreadMath#cone} 里（单独抽出来是为了能用纯数学断言守住这个区别）。
     */
    private List<Vector> coneSpreadDirections(Vector aim, int count, double spreadDegrees) {
        return SpreadMath.cone(aim, count, spreadDegrees, ThreadLocalRandom.current());
    }

    /** 水平一排：旧行为（spread-degrees 决定排开的角度）。 */
    private List<Vector> lineSpreadDirections(Vector base, int count, double spreadDegrees) {
        return SpreadMath.line(base, count, spreadDegrees);
    }

    /** 沿这一步走过的线段铺轨迹粒子（只在一个点撒会在高速下断成虚线）。 */
    private void drawItemTrail(Location from, Location to, ParticleConfig particle) {
        if (particle == null || !particle.enabled()) return;
        int samples = Math.max(1, (int) Math.ceil(from.distance(to)));
        int total = Math.max(1, particle.count());
        for (int index = 0; index < samples; index++) {
            Location point = from.clone().add(to.toVector().subtract(from.toVector())
                    .multiply((index + 1) / (double) samples));
            particleEffectPlayer.playJittered(point, particle, Math.max(1, total / samples));
        }
    }

    private void runShockwave(Player player, RpgItem item, RpgStats stats, ShockwaveSkill skill) {
        Location center = player.getLocation().clone().add(0, 0.08, 0);
        Vector forward = player.getLocation().getDirection().setY(0).normalize();
        Set<UUID> hit = new HashSet<>();
        playSound(center, skill.sound());
        new BukkitRunnable() {
            private double waveRadius = 0.25;

            @Override
            public void run() {
                if (!player.isOnline() || player.isDead() || waveRadius > skill.radius() + 0.3) {
                    cancel();
                    return;
                }
                drawGroundArc(center, forward, skill.angleDegrees(), waveRadius, skill.particle());
                for (LivingEntity target : center.getNearbyLivingEntities(Math.min(skill.radius(), waveRadius + 0.75), skill.height(), Math.min(skill.radius(), waveRadius + 0.75))) {
                    Vector offset = target.getLocation().toVector().subtract(center.toVector()).setY(0);
                    double targetRadius = offset.length();
                    if (Math.abs(targetRadius - waveRadius) > 0.75 || !inFan(center, forward, target.getLocation(), skill.radius(), skill.angleDegrees())) continue;
                    if (damageTarget(player, item, target, stats, skill.damage(), hit) && skill.knockback() > 0) {
                        Vector push = offset.lengthSquared() < 0.01 ? forward.clone() : offset.normalize();
                        target.setVelocity(push.multiply(skill.knockback() / 5.0).setY(0.25));
                    }
                }
                waveRadius += Math.max(0.25, skill.radius() / 8.0);
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void runSweep(Player player, RpgItem item, RpgStats stats, SweepSkill skill) {
        Location eye = player.getEyeLocation();
        Location center = eye.clone().add(eye.getDirection().normalize().multiply(skill.distance()));
        Vector forward = eye.getDirection().normalize();
        drawHorizontalArc(center, forward, skill.angleDegrees(), skill.radius(), skill.particle());
        Set<UUID> hit = new HashSet<>();
        // 伤害判定从玩家位置开始，覆盖到弧线外围（distance + radius），而不是只判定弧心周围的一个球。
        // 旧判定以弧心为球心、radius 为半径，且 Y 窗口以眼睛高度为中心——贴脸的怪物脚在眼睛下方
        // 1.6 格，落在窗口外，因此「怪物贴近玩家脸却打不到」。现在按扇形从玩家出发朝前扫。
        double reach = skill.distance() + skill.radius();
        SectorZone zone = sectorFromPlayer(player, eye, forward, reach, skill.angleDegrees(), skill.height());
        for (LivingEntity target : zone.collect()) {
            if (!zone.contains(target)) continue;
            if (canSkillReach(player, target, skill.damage())) damageTarget(player, item, target, stats, skill.damage(), hit);
        }
        playSound(center, skill.sound());
    }

    private void runCrossSlash(Player player, RpgItem item, RpgStats stats, CrossSlashSkill skill) {
        Location eye = player.getEyeLocation();
        Location center = eye.clone().add(eye.getDirection().normalize().multiply(skill.distance()));
        Vector forward = eye.getDirection().normalize();
        Vector horizontalForward = forward.clone().setY(0).normalize();
        Vector side = new Vector(-horizontalForward.getZ(), 0, horizontalForward.getX()).normalize();
        drawHorizontalArc(center, forward, skill.angleDegrees(), skill.radius(), skill.horizontalParticle());
        drawVerticalArc(center, forward, skill.angleDegrees(), skill.radius(), skill.verticalParticle());
        Set<UUID> horizontalHit = new HashSet<>();
        Set<UUID> verticalHit = new HashSet<>();
        // 两段判定都改为「从玩家位置出发到弧线外围」：旧的弧心球 / 弧心盒都漏掉贴脸怪物
        // （它们的脚在眼睛高度窗口之外）。水平段用扇形，竖直段用从玩家脚下到弧顶的竖直刀带。
        double reach = skill.distance() + skill.radius();
        SectorZone horizontalZone = sectorFromPlayer(player, eye, horizontalForward, reach, skill.angleDegrees(), skill.height());
        for (LivingEntity target : horizontalZone.collect()) {
            if (!horizontalZone.contains(target)) continue;
            if (!canSkillReach(player, target, skill.horizontalDamage() != null ? skill.horizontalDamage() : skill.verticalDamage())) continue;
            if (skill.horizontalDamage() != null) damageTarget(player, item, target, stats, skill.horizontalDamage(), horizontalHit);
            if (skill.verticalDamage() != null) {
                Location t = target.getLocation();
                double dx = t.getX() - eye.getX();
                double dz = t.getZ() - eye.getZ();
                double forwardProj = dx * horizontalForward.getX() + dz * horizontalForward.getZ();
                double sideOffset = Math.abs(dx * side.getX() + dz * side.getZ());
                // 竖直刀带：从玩家身前沿视线延伸到弧线外围，宽度 = 弧半径。
                // 竖直范围交给 horizontalZone（已按宽余量覆盖脚下与跳跃），这里只收窄水平形状，
                // 否则旧的「y >= 脚 - 0.5」会在玩家跳起时把地面怪排除掉。
                if (forwardProj >= 0 && forwardProj <= reach && sideOffset <= skill.radius()) {
                    damageTarget(player, item, target, stats, skill.verticalDamage(), verticalHit);
                }
            }
        }
        playSound(center, skill.horizontalSound());
        playSound(center, skill.verticalSound());
    }

    /**
     * 从玩家位置出发、向前延伸成一个扇形（覆盖玩家与粒子效果之间的整个区域）的命中测试。
     * 旧的「弧心周围一个球」判定把这些形状的伤害局限在弧心附近：贴脸的怪物站在玩家脚下，
     * 水平距离很近但偏偏不在以弧心为球心的高度窗口里，于是视觉上明明在刀光范围内却没有伤害。
     *
     * <p>这里的区域语义是「玩家到自己挥出的弧线之间」：水平半径从 0 到 {@code reach}，
     * 角度不超过 {@code angleDegrees}/2，竖直范围从玩家脚下再往下留 {@code max(2, height)} 格
     * 一直到眼睛高度 + {@code height}/2。因而紧贴玩家身体的怪物（包括脚下的）一定在区域内。
     *
     * <p><b>竖直下界为什么不贴着脚：</b>玩家跳起来时脚会抬高约 1.25 格，若窗口跟着脚一起上升，
     * 地面上的怪就会落到窗口下方而打不到——这正是「跳起来就打不到怪」的原因。所以下界从脚再往下
     * 留出足够余量（至少 2 格，覆盖一次跳跃与台阶），并对目标用包围盒做竖直重叠判定，
     * 这样跳劈、台阶上下都能正常命中。
     */
    private SectorZone sectorFromPlayer(Player player, Location eye, Vector forward, double reach, double angleDegrees, double height) {
        Vector aim = forward.clone().setY(0);
        if (aim.lengthSquared() < 1e-6) aim = eye.getDirection().clone().setY(0);
        if (aim.lengthSquared() < 1e-6) {
            // 视线正上 / 正下时水平投影为零，改用 yaw 推导朝向（Bukkit: yaw 0 = +Z）。
            double yaw = Math.toRadians(player.getLocation().getYaw());
            aim = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
        }
        aim.normalize();
        Vector side = new Vector(-aim.getZ(), 0, aim.getX()).normalize();
        double halfAngleRad = Math.toRadians(angleDegrees / 2.0);
        double eyeY = eye.getY();
        double feetY = player.getLocation().getY();
        double lowest = Math.min(feetY, eyeY);
        double highest = Math.max(feetY, eyeY);
        double downward = Math.max(2.0, height);
        double minY = lowest - downward;
        double maxY = highest + height / 2.0;
        // 收集盒包住整个扇形：以玩家为中心取 (reach, 竖直范围, reach)。
        Location boxCenter = player.getLocation().clone();
        boxCenter.setY((minY + maxY) / 2.0);
        double boxHeight = Math.max(1.0, (maxY - minY) / 2.0);
        return new SectorZone(boxCenter.getNearbyLivingEntities(reach, boxHeight, reach), eye, aim, side,
                reach, halfAngleRad, minY, maxY);
    }

    /** 扇形区域的收集结果 + 逐目标判定。分开写是为了先宽泛收集（性能）再精确判定（几何）。 */
    private record SectorZone(java.util.Collection<LivingEntity> nearby, Location eye, Vector aim, Vector side,
                              double reach, double halfAngleRad, double minY, double maxY) {
        boolean contains(LivingEntity target) {
            Location t = target.getLocation();
            // 用目标的包围盒做竖直重叠：目标脚在窗口下方时，只要它的身体还伸进窗口内就算命中，
            // 这样高个子怪（甚至玩家跳起来从上方劈）都不会因为「脚的位置」被判出界。
            double targetFeet = t.getY();
            double targetTop = targetFeet + Math.max(0.1, target.getBoundingBox().getHeight());
            if (targetTop < minY || targetFeet > maxY) return false;
            double dx = t.getX() - eye.getX();
            double dz = t.getZ() - eye.getZ();
            double radial = Math.sqrt(dx * dx + dz * dz);
            if (radial > reach) return false;
            if (radial < 1e-3) return true; // 玩家自己脚下，永远在挥击范围里
            double forwardProj = (dx * aim.getX() + dz * aim.getZ()) / radial;
            forwardProj = Math.max(-1.0, Math.min(1.0, forwardProj));
            double angleTo = Math.acos(forwardProj);
            return angleTo <= halfAngleRad;
        }

        java.util.Collection<LivingEntity> collect() {
            return nearby;
        }
    }

    private void runSwing(Player player, RpgItem item, RpgStats stats, SwingSkill skill) {
        Location eye = player.getEyeLocation();
        Location center = eye.clone().add(eye.getDirection().normalize().multiply(skill.distance()));
        Vector forward = eye.getDirection().normalize();
        // 每次施放随机一个倾斜角：0 = 水平弧（同横扫），90 = 竖直弧（同十字斩竖直段），默认在两者之间随机。
        drawTiltedArc(center, forward, skill.angleDegrees(), skill.radius(),
                randomTilt(skill.tiltMinDegrees(), skill.tiltMaxDegrees()), skill.particle());
        Set<UUID> hit = new HashSet<>();
        // 与横扫一致：伤害判定从玩家位置覆盖到弧线外围（distance + radius），贴脸怪物不会漏判。
        double reach = skill.distance() + skill.radius();
        SectorZone zone = sectorFromPlayer(player, eye, forward, reach, skill.angleDegrees(), skill.height());
        for (LivingEntity target : zone.collect()) {
            if (!zone.contains(target)) continue;
            if (canSkillReach(player, target, skill.damage())) damageTarget(player, item, target, stats, skill.damage(), hit);
        }
        playSound(center, skill.sound());
    }

    /**
     * 幻影剑舞：短时间内连续挥砍多下（默认），全部挥砍结束后射出一道剑气飞向前方。
     *
     * <p>每一下挥砍都随机旋转弧面、并在随机位置生成（幻影感），各自独立结算伤害——
     * 所以同一个目标会被多下连续命中（例如 5 下 × 30%）。挥砍用延迟任务排开，
     * 最后一下结束时调用 {@link #launchPhantomAura} 发射收尾剑气。
     *
     * <p><b>剑气版（{@code slash-aura: true}）</b>：把前面的**每一道挥砍都换成剑气**——
     * 每一下不再是近战弧，而是射出一道会飞的剑气（用 {@code aura} 段的飞行与造型参数、
     * {@code slash} 段的伤害 30%），按 {@code slash-interval-ticks} 间隔依次射出。
     * 因为每一道都已经是剑气了，所以**不再单独发射收尾剑气**（最后一道剑气删除）。
     */
    private void runPhantomBlade(Player player, RpgItem item, RpgStats stats, PhantomBladeSkill skill) {
        int count = Math.max(0, skill.slashCount());
        long interval = Math.max(1L, skill.slashIntervalTicks());
        // 剑气版：每一下都射出一道剑气，伤害用 slash 段（每道 30%），飞行造型用 aura 段的参数。
        if (skill.slashAura()) {
            if (count == 0) count = 1; // 剑气版至少射出一道
            for (int index = 0; index < count; index++) {
                long delay = index * interval;
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (!player.isOnline() || player.isDead()) return;
                    Location eye = player.getEyeLocation();
                    Vector forward = eye.getDirection().normalize();
                    Location start = eye.clone().add(forward.clone().multiply(skill.distance()))
                            .add(randomHorizontalOffset(skill.jitter()));
                    // skill.slash() 同时提供伤害（30%）与这道剑气的粒子/音效。
                    scheduleAuraFlight(player, item, stats,
                            skill.auraSpeed(), skill.auraMaxDistance(), skill.auraRadius(),
                            skill.auraArcRadius(), skill.auraArcAngleDegrees(),
                            skill.auraTiltMinDegrees(), skill.auraTiltMaxDegrees(),
                            skill.slash(), start, forward);
                }, delay);
            }
            return;
        }
        // slash-count: 0（非剑气版）→ 不做任何挥砍，只发射收尾剑气。
        if (count == 0) {
            launchPhantomAura(player, item, stats, skill);
            return;
        }
        for (int index = 0; index < count; index++) {
            boolean last = index == count - 1;
            long delay = index * interval;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline() || player.isDead()) return;
                Location eye = player.getEyeLocation();
                Vector forward = eye.getDirection().normalize();
                Location center = eye.clone().add(forward.clone().multiply(skill.distance()))
                        .add(randomHorizontalOffset(skill.jitter()));
                drawTiltedArc(center, forward, skill.angleDegrees(), skill.radius(),
                        randomTilt(skill.tiltMinDegrees(), skill.tiltMaxDegrees()),
                        skill.slash() == null ? null : skill.slash().particle());
                playSound(center, skill.slash() == null ? null : skill.slash().sound());
                if (skill.slash() == null || (skill.slash().damage() <= 0 && skill.slash().weaponPercent() <= 0)) return;
                // 每一下独立命中记录：多下可以对同一目标叠加命中。
                Set<UUID> hit = new HashSet<>();
                double reach = skill.distance() + skill.radius();
                SectorZone zone = sectorFromPlayer(player, eye, forward, reach, skill.angleDegrees(), skill.height());
                for (LivingEntity target : zone.collect()) {
                    if (!zone.contains(target)) continue;
                    if (canSkillReach(player, target, skill.slash())) damageTarget(player, item, target, stats, skill.slash(), hit);
                }
                if (last) launchPhantomAura(player, item, stats, skill);
            }, delay);
        }
    }

    /**
     * 发射幻影剑舞的收尾剑气：同样在随机位置生成，然后朝视线前方飞出，
     * 沿途对碰到的目标结算伤害，撞到方块或飞满 {@code auraMaxDistance} 后消失。
     */
    private void launchPhantomAura(Player player, RpgItem item, RpgStats stats, PhantomBladeSkill skill) {
        Location eye = player.getEyeLocation();
        Vector forward = eye.getDirection().normalize();
        Location start = eye.clone().add(forward.clone().multiply(skill.distance()))
                .add(randomHorizontalOffset(skill.jitter()));
        scheduleAuraFlight(player, item, stats,
                skill.auraSpeed(), skill.auraMaxDistance(), skill.auraRadius(),
                skill.auraArcRadius(), skill.auraArcAngleDegrees(),
                skill.auraTiltMinDegrees(), skill.auraTiltMaxDegrees(),
                skill.aura(), start, forward);
    }

    /**
     * 百人一太刀：直接发射一道剑气飞向前方，对沿途碰到的目标结算伤害。
     * 与幻影剑舞的收尾剑气共用同一套飞行与命中判定（{@link #scheduleAuraFlight}）。
     */
    private void runSwordAura(Player player, RpgItem item, RpgStats stats, SwordAuraSkill skill) {
        DamageSkill damage = skill.damage();
        if (damage == null || (damage.damage() <= 0 && damage.weaponPercent() <= 0)) return;
        Location eye = player.getEyeLocation();
        Vector forward = eye.getDirection().normalize();
        Location start = eye.clone().add(forward.clone().multiply(skill.distance()))
                .add(randomHorizontalOffset(skill.jitter()));
        scheduleAuraFlight(player, item, stats,
                skill.speed(), skill.maxDistance(), skill.auraRadius(),
                skill.arcRadius(), skill.arcAngleDegrees(),
                skill.tiltMinDegrees(), skill.tiltMaxDegrees(),
                damage, start, forward);
    }

    /**
     * 剑气飞行任务（幻影剑舞的收尾剑气与「百人一太刀」共用）。
     *
     * <p>从 {@code start} 朝 {@code forward} 每 tick 前进 {@code speed} 格，沿途每 tick 画一条
     * 与挥击/横扫同形的平面弧（{@link #drawTiltedArc}），并对碰到的目标各自结算一次伤害。
     * 撞到方块或飞满 {@code maxDistance} 后消失。
     *
     * <p><b>命中判定</b>：使用目标的包围盒（竖直重叠 + 水平距离），而不是剑气与目标「脚的位置」
     * 的直线距离。剑气在眼睛高度飞行，地面怪（约 2 格高）的脚在剑气下方约 1.6 格，
     * 旧代码用脚的位置算距离，判定永远不成立——这就是「剑气明明擦着怪飞过却打不到」的原因。
     */
    private void scheduleAuraFlight(Player player, RpgItem item, RpgStats stats,
                                    double speed, double maxDistance, double radius,
                                    double arcRadius, double arcAngleDegrees,
                                    double tiltMinDegrees, double tiltMaxDegrees,
                                    DamageSkill aura, Location start, Vector forward) {
        if (aura == null || (aura.damage() <= 0 && aura.weaponPercent() <= 0)) return;
        // 撞到方块就停在那里：先算出剑气能飞多远。
        double limit = maxDistance;
        var blockHit = player.getWorld().rayTraceBlocks(start, forward, limit, FluidCollisionMode.NEVER, true);
        if (blockHit != null) limit = Math.max(0.5, blockHit.getHitPosition().distance(start.toVector()));
        double maxTravel = limit;
        playSound(start, aura.sound());
        Set<UUID> hit = new HashSet<>();
        new BukkitRunnable() {
            private Location current = start.clone();
            private double travelled = 0;
            // 剑气飞出时随机一个弧面朝向，并在整段飞行中保持不变：
            // 与挥砍段「每一次随机」一致，所以剑气有时平飞、有时斜飞、有时立着飞。
            private final double tilt = randomTilt(tiltMinDegrees, tiltMaxDegrees);

            @Override
            public void run() {
                if (!player.isOnline() || player.isDead() || travelled > maxTravel) {
                    cancel();
                    return;
                }
                if (aura.particle() != null && aura.particle().enabled()) {
                    // 剑气就是「挥击那一道弧」整体飞出去：直接复用挥击/横扫的 drawTiltedArc，
                    // 弧面包含飞行方向，所以玩家看到的是自己刚挥出的那道弧形一路飞向远方。
                    // 旧版在这里画了一个垂直于飞行方向、弧心朝上的月牙，从背后看就是个「^」形。
                    drawTiltedArc(current, forward, arcAngleDegrees, arcRadius, tilt, aura.particle());
                }
                double y = current.getY();
                // 收集范围把 Y 方向放宽（+1，保底 2）：脚在剑气下方约 1.6 格的地面怪也能被收集到。
                for (LivingEntity target : current.getNearbyLivingEntities(radius, Math.max(radius + 1.0, 2.0), radius)) {
                    if (target.getUniqueId().equals(player.getUniqueId())) continue;
                    if (!canSkillReach(player, target, aura)) continue;
                    // 竖直重叠：剑气带一条高度为 radius 的判定带，目标包围盒与它重叠即为竖直命中。
                    BoundingBox box = target.getBoundingBox();
                    if (box.getMaxY() < y - radius || box.getMinY() > y + radius) continue;
                    // 水平距离：剑气到目标包围盒水平投影的最短距离 ≤ radius。
                    double cx = Math.max(box.getMinX(), Math.min(current.getX(), box.getMaxX()));
                    double cz = Math.max(box.getMinZ(), Math.min(current.getZ(), box.getMaxZ()));
                    double dx = current.getX() - cx;
                    double dz = current.getZ() - cz;
                    if (dx * dx + dz * dz > radius * radius) continue;
                    damageTarget(player, item, target, stats, aura, hit);
                }
                travelled += speed;
                current = current.clone().add(forward.clone().multiply(speed));
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** 在 [min, max] 之间随机取一个倾斜角；两者相等时就是固定角。 */
    private double randomTilt(double min, double max) {
        double low = min;
        double high = Math.max(min, max);
        return low == high ? low : low + ThreadLocalRandom.current().nextDouble() * (high - low);
    }

    /** 在半径为 {@code jitter} 的水平圆盘内随机取一个偏移，用于「每次在随机位置出现」。 */
    private Vector randomHorizontalOffset(double jitter) {
        if (jitter <= 0) return new Vector(0, 0, 0);
        double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        double distance = Math.sqrt(ThreadLocalRandom.current().nextDouble()) * jitter;
        return new Vector(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
    }

    private void drawGroundArc(Location center, Vector forward, double angleDegrees, double radius, ParticleConfig particle) {
        if (particle == null || !particle.enabled()) return;
        List<Location> points = new ArrayList<>();
        for (double angle = -angleDegrees / 2; angle <= angleDegrees / 2; angle += 4) points.add(center.clone().add(rotateHorizontal(forward, Math.toRadians(angle)).multiply(radius)));
        drawArcPoints(points, particle);
    }

    private void drawHorizontalArc(Location center, Vector forward, double angleDegrees, double radius, ParticleConfig particle) {
        if (particle == null || !particle.enabled()) return;
        Vector aim = forward.clone().normalize();
        Vector side = new Vector(-aim.getZ(), 0, aim.getX()).normalize();
        List<Location> points = new ArrayList<>();
        for (double angle = -angleDegrees / 2; angle <= angleDegrees / 2; angle += 3) {
            double radians = Math.toRadians(angle);
            Vector point = side.clone().multiply(Math.sin(radians) * radius)
                    .add(aim.clone().multiply((Math.cos(radians) - 1) * radius));
            points.add(center.clone().add(point));
        }
        drawArcPoints(points, particle);
    }

    private void drawVerticalArc(Location center, Vector forward, double angleDegrees, double radius, ParticleConfig particle) {
        if (particle == null || !particle.enabled()) return;
        Vector aim = forward.clone().normalize();
        Vector up = new Vector(0, 1, 0);
        List<Location> points = new ArrayList<>();
        for (double angle = -angleDegrees / 2; angle <= angleDegrees / 2; angle += 3) {
            double radians = Math.toRadians(angle);
            Vector point = up.clone().multiply(Math.sin(radians) * radius)
                    .add(aim.clone().multiply((Math.cos(radians) - 1) * radius));
            points.add(center.clone().add(point));
        }
        drawArcPoints(points, particle);
    }

    /**
     * 倾斜弧：弧面绕视线方向（{@code forward}）旋转 {@code tiltDegrees} 后画出。
     * 0 = 水平弧（与 {@link #drawHorizontalArc} 完全一致），90 = 竖直弧（与
     * {@link #drawVerticalArc} 一致），中间角度是两者的斜弧——这正是挥击每次随机挥出的形态。
     */
    private void drawTiltedArc(Location center, Vector forward, double angleDegrees, double radius,
                               double tiltDegrees, ParticleConfig particle) {
        if (particle == null || !particle.enabled()) return;
        Vector aim = forward.clone().normalize();
        Vector flat = aim.clone().setY(0);
        Vector side;
        if (flat.lengthSquared() < 1e-6) {
            // 视线正上 / 正下：任意取一个水平方向当基准边。
            side = new Vector(1, 0, 0);
        } else {
            side = new Vector(-flat.getZ(), 0, flat.getX()).normalize();
        }
        double tilt = Math.toRadians(tiltDegrees);
        // side 绕 aim 旋转 tilt：rotated = side*cos + (side × aim)*sin。
        // side × aim 在视线水平时等于竖直向上，所以 90° 时 rotated 恰好是 up。
        Vector up = side.clone().crossProduct(aim);
        double cos = Math.cos(tilt);
        double sin = Math.sin(tilt);
        Vector rotated = side.clone().multiply(cos).add(up.clone().multiply(sin));
        List<Location> points = new ArrayList<>();
        for (double angle = -angleDegrees / 2; angle <= angleDegrees / 2; angle += 3) {
            double radians = Math.toRadians(angle);
            Vector point = rotated.clone().multiply(Math.sin(radians) * radius)
                    .add(aim.clone().multiply((Math.cos(radians) - 1) * radius));
            points.add(center.clone().add(point));
        }
        drawArcPoints(points, particle);
    }

    /**
     * Renders an arc by spreading the configured particle {@code count} across all sample points
     * instead of spawning that many particles at every point. With the old per-point reading a
     * "count: 160" sweep produced roughly 160 particles at each of ~37 points (about 6000 particles
     * in total), which saturated into a solid blob and made the arc unreadable, and the configured
     * {@code radius} was discarded entirely so the trail had no thickness control.
     * <p>
     * When {@code count} is smaller than the number of sample points the particles are thinned across
     * the whole arc with {@code Math.round}, so a small count still traces the full shape evenly. The
     * earlier integer-division split gave every point after the first {@code count} zero particles,
     * which left the tail of the arc completely empty instead of sparse.
     */
    private void drawArcPoints(List<Location> points, ParticleConfig particle) {
        if (points.isEmpty()) return;
        int total = Math.max(1, particle.count());
        int pointCount = points.size();
        for (int index = 0; index < pointCount; index++) {
            // Map this point onto the particle budget: point i covers particles
            // [i*total/pointCount, (i+1)*total/pointCount). Rounding both edges keeps the whole arc
            // covered even when total < pointCount.
            int from = (int) Math.round(index * (double) total / pointCount);
            int to = (int) Math.round((index + 1) * (double) total / pointCount);
            int amount = to - from;
            if (amount <= 0) continue;
            particleEffectPlayer.playJittered(points.get(index), particle, amount);
        }
    }


    private Vector rotateHorizontal(Vector base, double angle) {
        return new Vector(base.getX() * Math.cos(angle) - base.getZ() * Math.sin(angle), 0, base.getX() * Math.sin(angle) + base.getZ() * Math.cos(angle)).normalize();
    }

    private boolean inFan(Location center, Vector forward, Location target, double radius, double angleDegrees) {
        Vector offset = target.toVector().subtract(center.toVector()).setY(0);
        if (offset.lengthSquared() > radius * radius || offset.lengthSquared() < 0.01) return true;
        return Math.toDegrees(forward.angle(offset.normalize())) <= angleDegrees / 2.0;
    }


    private double findMagicCircleGroundY(Player player, Location target) {
        var world = target.getWorld();
        int x = target.getBlockX();
        int z = target.getBlockZ();
        int y = Math.min(world.getMaxHeight() - 1, player.getLocation().getBlockY());
        while (y > world.getMinHeight() && world.getBlockAt(x, y, z).isPassable()) y--;
        if (y > world.getMinHeight()) return y + 1.02;
        return world.getHighestBlockYAt(x, z) + 1.02;
    }
    private void runMagicCircle(Player player, RpgItem item, RpgStats stats, MagicCircleSkill skill) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        double maxDistance = Math.max(0.5, skill.distance());
        var hit = player.getWorld().rayTraceBlocks(eye, direction, maxDistance, FluidCollisionMode.NEVER, true);
        Location center = hit == null ? eye.clone().add(direction.clone().multiply(maxDistance)) : hit.getHitPosition().toLocation(player.getWorld());
        center.setY(findMagicCircleGroundY(player, center));
        boolean debug = magicCircleTraceEnabled(player);
        if (debug) {
            plugin.getLogger().info("[MagicCircle] 施放：" + (hit == null ? "射线未命中方块，取最大距离处" : "射线命中方块")
                    + "，中心=" + formatLocation(center));
        }
        ItemDisplay display = null;
        if (skill.useArmorStand()) {
            try {
                // Only a display entity, deliberately. The invisible marker ArmorStand that used to be
                // spawned here did nothing but exist: it held no item, was never read for a position,
                // and was only tracked so it could be removed again. It cost real problems because
                // ArmorStand implements LivingEntity, so it appeared in getNearbyLivingEntities and
                // had to be filtered out of the circle's own damage, was damageable, was
                // player-manipulable, was targetable by mobs, and showed up in /kill @e[type=armor_stand]
                // cleanup. ItemDisplay is an Entity but not a LivingEntity, so none of that applies.
                display = center.getWorld().spawn(center.clone().add(0, 0.65, 0), ItemDisplay.class, entity -> {
                    entity.setItemStack(new ItemStack(skill.centerMaterial()));
                    // The default transform (NONE) draws the item flat, like an inventory icon, which
                    // is why a sword centrepiece looked wrong. GROUND gives the tilted dropped-item
                    // pose; THIRD_PERSON_RIGHTHAND is the held-in-hand angle if a floating sword is
                    // wanted instead.
                    entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
                    entity.setBillboard(Display.Billboard.FIXED);
                    entity.setInvulnerable(true);
                    entity.setPersistent(false);
                    // A shadow under a magic circle centrepiece reads as a stray floating object.
                    entity.setShadowRadius(0);
                    entity.setShadowStrength(0);
                    // Full brightness so the centrepiece stays visible in caves and at night, where a
                    // magic circle is otherwise easy to lose.
                    entity.setBrightness(new Display.Brightness(15, 15));
                    entity.setMetadata(MAGIC_CIRCLE_ARMOR_STAND, new FixedMetadataValue(plugin, true));
                });
            } catch (RuntimeException failure) {
                // The repeating task that normally owns the cleanup is never created in this case, so
                // release whatever the failed spawn may have left behind.
                releaseMagicCircleEntities(display);
                display = null;
                plugin.getLogger().log(Level.WARNING, "Magic circle decoration failed to spawn; the circle will still deal damage.", failure);
            }
        }
        if (display != null) magicCircleDisplays.add(display);
        ItemDisplay finalDisplay = display;
        playSound(center, skill.sound());
        new BukkitRunnable() {
            private int ticks;
            private int nextBurst;
            private boolean particleWarningLogged;
            private boolean failureLogged;
            private boolean finished;

            @Override
            public void run() {
                try {
                    if (!player.isOnline() || player.isDead() || ticks >= Math.round(skill.durationSeconds() * 20)) {
                        finish();
                        return;
                    }
                    boolean burst = ticks >= nextBurst;
                    if (burst) nextBurst += Math.max(1, Math.round(skill.intervalSeconds() * 20));
                    // Damage is resolved before any particle is drawn. The burst particles used to run
                    // first, so a particle that failed to spawn killed this repeating task outright and
                    // the circle kept its visuals for one tick while never dealing damage again. A skill
                    // whose damage depends on decoration being healthy is the wrong order.
                    if (burst) {
                        Collection<LivingEntity> candidates = center.getNearbyLivingEntities(skill.radius(), skill.height(), skill.radius());
                        if (debug) logMagicCircleCandidates(player, center, skill, candidates, stats);
                        Set<UUID> hitTargets = new HashSet<>();
                        for (LivingEntity target : candidates) {
                            if (!isMagicCircleTarget(player, target, skill)) continue;
                            if (skill.damage() != null && (skill.damage().damage() > 0 || skill.damage().weaponPercent() > 0)) {
                                boolean landed = damageTarget(player, item, target, stats, skill.damage(), hitTargets);
                                if (debug) plugin.getLogger().info("[MagicCircle]   结算 " + target.getType() + " -> "
                                        + (landed ? "造成伤害" : "未造成伤害（被过滤，或总伤害为 0）"));
                            } else if (debug) {
                                plugin.getLogger().info("[MagicCircle]   跳过伤害：skill.damage() 为 null，或 damage 与 weapon-percent 都为 0");
                            }
                            for (BuffSkill effect : skill.buffs()) {
                                PotionEffectType type = PotionEffectType.getByName(effect.type().toUpperCase(Locale.ROOT));
                                if (type != null) target.addPotionEffect(new PotionEffect(type, Math.max(1, (int) Math.round(skill.buffDurationSeconds() * 20)), Math.max(0, effect.amplifier()), true, true, true));
                            }
                            if (skill.attributeBoost() != null && skill.attributeDurationSeconds() > 0) plugin.getRpgItemManager().applyTemporaryAttributeBoost(target, skill.attributeBoost(), skill.attributeDurationSeconds());
                        }
                    }
                    try {
                        drawCircle(center, skill.radius(), skill.areaParticle());
                        if (burst) {
                            for (int index = 0; index < skill.burstCount(); index++) {
                                double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
                                double distance = Math.sqrt(ThreadLocalRandom.current().nextDouble()) * skill.radius();
                                Location point = center.clone().add(Math.cos(angle) * distance, -0.02, Math.sin(angle) * distance);
                                particleEffectPlayer.playRisingBurst(point, skill.burstParticle());
                            }
                        }
                    } catch (RuntimeException exception) {
                        // Reported once per circle instead of every tick; the damage above already ran.
                        if (!particleWarningLogged) {
                            particleWarningLogged = true;
                            plugin.getLogger().warning("Magic circle particle failed (damage is unaffected): " + exception);
                        }
                    }
                    ticks++;
                } catch (Throwable failure) {
                    // Bukkit cancels a repeating task whose run() throws, and the cleanup block below
                    // was then never reached: the circle stopped dealing damage for good while the
                    // armour stand and item display of that cast stayed in the world, one more pair
                    // per cast. That is why the damage stopped and memory climbed together. Report
                    // the real cause once and always release the entities.
                    if (!failureLogged) {
                        failureLogged = true;
                        plugin.getLogger().log(Level.SEVERE, "Magic circle task failed; removing the circle. Report this stack trace:", failure);
                    }
                    finish();
                }
            }

            private void finish() {
                if (finished) return;
                finished = true;
                releaseMagicCircleEntities(finalDisplay);
                cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** Removes a circle's decoration entity and drops it from the tracking set. */
    private void releaseMagicCircleEntities(ItemDisplay display) {
        if (display != null) {
            magicCircleDisplays.remove(display);
            if (!display.isDead()) display.remove();
        }
    }

    private boolean magicCircleTraceEnabled(Player player) {
        return magicCircleDebugPlayers.contains(player.getUniqueId());
    }

    /** Toggles per-player magic circle tracing; returns the new state. */
    public boolean toggleMagicCircleDebug(Player player) {
        UUID id = player.getUniqueId();
        if (magicCircleDebugPlayers.remove(id)) return false;
        magicCircleDebugPlayers.add(id);
        return true;
    }

    /** Live magic circle decoration entities the plugin still tracks; must fall back to 0 when idle. */
    public int magicCircleEntityCount() {
        return magicCircleDisplays.size();
    }

    /** Live 「王之宝库」 weapons still falling or stuck in the ground; used by {@code /rpgitem memdebug}. */
    public int gateOfBabylonEntityCount() {
        return gateDisplays.size();
    }

    /**
     * One falling weapon. Every field is per-weapon state: a volley is not one effect but N
     * independent objects that happen to share a configuration, so the task below has to advance
     * each of them separately and can never assume they land on the same tick.
     */
    /**
     * 一个在飞的物品弹射物。本体是 {@code ItemDisplay}，位置由 {@link #advanceItemShot} 每 tick 推进，
     * 命中判定用 {@link #lastPosition} 到新位置这条<b>线段</b>（见 {@link #advanceItemShot}）。
     */
    private static final class ItemShot {
        private final ItemDisplay display;
        private final Player player;
        private final RpgItem item;
        private final RpgStats stats;
        private final ShortbowSkill skill;
        private final ItemProjectile projectile;
        private final Vector direction;
        /** 已经命中过的目标：穿透时同一个目标不会被同一发弹打第二次。 */
        private final Set<UUID> pierced = new HashSet<>();
        private Location lastPosition;
        private double travelled;

        private ItemShot(ItemDisplay display, Player player, RpgItem item, RpgStats stats, ShortbowSkill skill,
                         ItemProjectile projectile, Location start, Vector direction) {
            this.display = display;
            this.player = player;
            this.item = item;
            this.stats = stats;
            this.skill = skill;
            this.projectile = projectile;
            this.direction = direction;
            this.lastPosition = start;
        }
    }

    private static final class GateWeapon {
        private final Material material;
        private final Location start;
        private final Location impact;
        private final int launchDelayTicks;
        private ItemDisplay display;
        private Location lastPosition;
        private double progress;
        private int waitTicks;
        private boolean launched;
        private boolean landed;
        private boolean released;
        private int lingerTicks;
        /** Set once the hilt ring has failed, so a per-tick effect reports its cause once, not 20x/s. */
        private boolean rippleFailed;
        /**
         * The segment travelled on the most recent tick. Mid-flight collision tests this rather than
         * {@link #lastPosition}, because at {@code fall-speed} above 1 a weapon advances several
         * blocks per tick and a point test would let it pass clean through a target standing in
         * between.
         */
        private Location segmentFrom;
        private Location segmentTo;

        private GateWeapon(Material material, Location start, Location impact, int launchDelayTicks) {
            this.material = material;
            this.start = start;
            this.impact = impact;
            this.launchDelayTicks = launchDelayTicks;
        }
    }

    /**
     * 「王之宝库」：在施法者背后（或落点上方的空中，由 {@code spawn-mode} 决定）展开一批武器，
     * 短暂停留后射向准星所指的位置，飞行穿过方块，落地产生一次不破坏方块的爆炸并结算一次技能伤害，
     * 停留 {@code linger-seconds} 秒后消失。
     * <p>
     * 几何关系分两半：落点区域来自视线射线（准星决定砸哪里），生成点来自背后扇形或落点上方的空中
     * （{@link GateOfBabylonSkill.SpawnMode}）。两者之间是直线插值且不做碰撞检测，所以天花板挡不住它
     * —— 这也是它与 {@link #runMagicCircle} 最大的区别，法阵的地面扫描起点在施法者身上，因此会被
     * 天花板限制。
     * <p>
     * Everything here is presentation plus one damage call. The damage itself goes through
     * {@link #damageTarget}, so the volley respects the same resistance, armour penetration, crit
     * family and damage-type rules as every other skill; nothing about it is special-cased.
     * <p>
     * Two guards keep the entity count honest: a single cast never exceeds
     * {@link GateOfBabylonSkill#MAX_WEAPONS}, and a cast is refused outright while
     * {@link #gateMaxLiveDisplays()} weapons from earlier casts are still alive. The magic circle
     * taught the cost of tracking decoration loosely — a task that dies before its cleanup leaks one
     * entity per cast — so the cleanup below runs from a {@code finally}-style path that fires on
     * every exit, including a thrown exception.
     */
    private void runGateOfBabylon(Player player, RpgItem item, RpgStats stats, GateOfBabylonSkill skill) {
        int maxLive = gateMaxLiveDisplays();
        if (gateDisplays.size() >= maxLive) {
            plugin.getLogger().warning("王之宝库未施放：当前残留武器实体 " + gateDisplays.size() + " 已达上限 "
                    + maxLive + "，请用 /rpgitem memdebug 检查是否有其它施放没有正常结束。"
                    + "（上限可用 config.yml 的 fskill.gate-max-live-displays 调整）");
            return;
        }
        Location origin = player.getLocation().clone();
        World world = origin.getWorld();
        Location eye = player.getEyeLocation();
        Vector aim = eye.getDirection().normalize();
        // The landing area follows the crosshair. The ray only fixes the X/Z of the focus point and
        // the height the ground search starts from; it deliberately does not restrict where a weapon
        // may fly, because the flight below passes through blocks. Scanning down from the focus point
        // - rather than from above the caster - is what stops a ceiling from pulling the whole volley
        // up onto the roof, which is exactly what the magic circle got wrong.
        double reach = Math.max(1.0, skill.distance());
        var blockHit = world.rayTraceBlocks(eye, aim, reach, FluidCollisionMode.NEVER, true);
        Location focus = blockHit == null
                ? eye.clone().add(aim.clone().multiply(reach))
                : blockHit.getHitPosition().toLocation(world);
        Location center = new Location(world, focus.getX(),
                findGateGroundY(world, focus.getX(), focus.getZ(), focus.getBlockY()) + 0.15, focus.getZ());
        // Behind the caster, spread over a fan (only used by the BEHIND / MIXED spawn modes). The flat
        // fallback matters when the caster looks straight up or down, where the horizontal part of the
        // aim vector is zero-length.
        Vector flat = aim.clone().setY(0);
        if (flat.lengthSquared() < 0.01) flat = new Vector(0, 0, 1);
        flat.normalize();
        Vector behind = flat.multiply(-1);
        double fan = Math.toRadians(Math.max(0, Math.min(360, skill.spawnFanDegrees()))) / 2.0;
        List<GateWeapon> weapons = new ArrayList<>();
        int count = Math.min(GateOfBabylonSkill.MAX_WEAPONS, Math.max(1, skill.weaponCount()));
        for (int index = 0; index < count; index++) {
            // sqrt on the radius spreads the impacts evenly instead of piling them up in the middle,
            // which is what a plain uniform distance does.
            double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
            double distance = Math.sqrt(ThreadLocalRandom.current().nextDouble()) * Math.max(0.5, skill.spreadRadius());
            double x = center.getX() + Math.cos(angle) * distance;
            double z = center.getZ() + Math.sin(angle) * distance;
            Location impact = new Location(world, x,
                    findGateGroundY(world, x, z, center.getBlockY() + 1) + 0.15, z);
            // Where this weapon comes from. Both origins use their own parameter set, so switching
            // spawn-mode never requires retuning the other one; MIXED simply flips a coin per weapon.
            boolean fromSky = switch (skill.spawnMode()) {
                case SKY -> true;
                case BEHIND -> false;
                case MIXED -> ThreadLocalRandom.current().nextBoolean();
            };
            Location start;
            if (fromSky) {
                // From the sky: straight above the impact point, within sky-offset horizontally. This
                // is the mode that most obviously reads as "raining down", and because the flight
                // ignores blocks it still reaches a target under a roof.
                double skyAngle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
                double skyOffset = Math.max(0, skill.skyOffset()) * Math.sqrt(ThreadLocalRandom.current().nextDouble());
                start = impact.clone().add(Math.cos(skyAngle) * skyOffset,
                        Math.max(1, skill.skyHeight()) * ThreadLocalRandom.current().nextDouble(0.85, 1.15),
                        Math.sin(skyAngle) * skyOffset);
            } else {
                // From behind the caster, over a fan. This is the mode that reads as the gate itself
                // opening behind you.
                Vector outward = rotateHorizontal(behind, ThreadLocalRandom.current().nextDouble(-fan, fan));
                double behindDistance = Math.max(0.5, skill.spawnDistance()) * ThreadLocalRandom.current().nextDouble(0.5, 1.5);
                start = origin.clone().add(outward.multiply(behindDistance));
                start.setY(origin.getY() + Math.max(0.5, skill.spawnHeight()) * ThreadLocalRandom.current().nextDouble(0.4, 1.4));
            }
            Material material = skill.weaponMaterials().get(ThreadLocalRandom.current().nextInt(skill.weaponMaterials().size()));
            weapons.add(new GateWeapon(material, start, impact,
                    ThreadLocalRandom.current().nextInt(0, Math.max(1, skill.launchDelayTicks() + 1))));
        }
        playSound(origin, skill.castSound());
        int lingerTicks = Math.max(1, (int) Math.round(skill.lingerSeconds() * 20));

        new BukkitRunnable() {
            private boolean failureLogged;
            private boolean finished;
            /** Per-target hit count for this cast, the ceiling being {@code max-hits-per-target}. */
            private final Map<UUID, Integer> hitsByTarget = new HashMap<>();

            @Override
            public void run() {
                // A disconnected caster invalidates the attacker used by damageTarget, and there is no
                // one left to see the volley, so the remaining weapons are released instead of being
                // allowed to land. Death is deliberately not checked: the player entity still exists
                // and an already-launched volley finishing is the expected behaviour.
                if (!player.isOnline()) {
                    finish();
                    return;
                }
                try {
                    boolean active = false;
                    for (GateWeapon weapon : weapons) {
                        if (weapon.released) continue;
                        if (weapon.display == null && !spawnGateWeapon(weapon, skill)) {
                            weapon.released = true;
                            continue;
                        }
                        if (!weapon.launched) {
                            // The weapons hang behind the caster for a moment before launching. Without
                            // this the whole volley reads as a wall of items appearing out of nowhere
                            // and already moving; the wait is what makes it read as a gate opening.
                            weapon.waitTicks++;
                            if (weapon.waitTicks < weapon.launchDelayTicks) {
                                // Flat golden ring at the hilt for as long as the weapon hangs here,
                                // so the volley reads as weapons being drawn out of circular treasury
                                // gates rather than as sprites waiting in mid-air.
                                drawGateRipple(weapon, skill);
                                active = true;
                                continue;
                            }
                            weapon.launched = true;
                        }
                        if (!weapon.landed) {
                            boolean flying = advanceGateWeapon(weapon, skill);
                            // Mid-flight hit. Only for skills that opt in: the gate's identity is a
                            // volley that passes through the world and lands on the crosshair, while
                            // an arrow rain that flies through its target and hits the ground behind
                            // it is the one thing that reads as broken.
                            LivingEntity struck = skill.collideWithEntities()
                                    ? findGateCollision(player, weapon, skill) : null;
                            if (struck != null) {
                                strikeGateWeapon(player, item, stats, skill, weapon, hitsByTarget, struck);
                            } else if (!flying) {
                                landGateWeapon(player, item, stats, skill, weapon, hitsByTarget);
                            }
                            active = true;
                            continue;
                        }
                        weapon.lingerTicks++;
                        if (weapon.lingerTicks >= lingerTicks) releaseGateWeapon(weapon);
                        else active = true;
                    }
                    if (!active) cancel();
                } catch (Throwable failure) {
                    // Bukkit cancels a repeating task whose run() throws and then never calls it again,
                    // so without this the weapons of this cast would stay in the world forever. Report
                    // the real cause once and always release them.
                    if (!failureLogged) {
                        failureLogged = true;
                        plugin.getLogger().log(Level.SEVERE, "王之宝库任务异常，已清理本次施放的全部武器。请反馈该堆栈：", failure);
                    }
                    finish();
                }
            }

            private void finish() {
                if (finished) return;
                finished = true;
                for (GateWeapon weapon : weapons) releaseGateWeapon(weapon);
                cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** Spawns one weapon's display entity; returns false when the spawn failed and was reported. */
    private boolean spawnGateWeapon(GateWeapon weapon, GateOfBabylonSkill skill) {
        try {
            // Spawned at zero yaw/pitch on purpose: spawn() copies the location's rotation onto the
            // entity, and the behind-spawn location is cloned from the caster, so it carries the
            // caster's view rotation. A FIXED display applies that entity rotation on top of the
            // transformation, which would tilt every weapon by however the caster happened to be
            // looking. faceGateWeapon sets the rotation too, but doing it here as well means the
            // entity is never even briefly sent to clients with the wrong orientation.
            Location spawnAt = weapon.start.clone();
            spawnAt.setYaw(0.0F);
            spawnAt.setPitch(0.0F);
            weapon.display = spawnAt.getWorld().spawn(spawnAt, ItemDisplay.class, entity -> {
                entity.setItemStack(new ItemStack(weapon.material));
                // NONE is the only neutral base: the item is drawn exactly as its model defines it,
                // i.e. an upright sprite in its own XY plane with the blade running up-right and the
                // face pointing along local +Z. Every other transform bakes in its own rotation or
                // scale (GROUND is upright but 0.5 scale, FIXED is a [0,180,0] mirror), which would
                // have to be undone again before the weapon can be aimed. faceGateWeapon then aims
                // the blade at the impact point, so the weapon flies tip-first instead of hanging
                // like an item in a frame.
                entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                // FIXED keeps the weapon's own orientation instead of billboarding it to the camera;
                // a billboarded sprite ignores the aim entirely and always lies flat to the viewer.
                entity.setBillboard(Display.Billboard.FIXED);
                entity.setInvulnerable(true);
                // Non-persistent: a weapon that somehow outlives a crash is not written to the chunk,
                // so it cannot come back as a stray entity after a restart.
                entity.setPersistent(false);
                entity.setShadowRadius(0);
                entity.setShadowStrength(0);
                entity.setBrightness(new Display.Brightness(15, 15));
                // Interpolate the per-tick teleport, otherwise the weapon visibly steps along its path.
                entity.setTeleportDuration(1);
            });
            faceGateWeapon(weapon, skill);
            // A golden flash at the point of appearance, so the weapon reads as coming out of an open
            // gate rather than simply popping into existence. Guarded separately from the spawn itself:
            // a bad particle name must not cost the cast one of its weapons.
            try {
                particleEffectPlayer.playBurst(weapon.start, skill.spawnParticle());
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("王之宝库的生成粒子失败（武器本身不受影响）：" + exception);
            }
            weapon.lastPosition = weapon.start;
            gateDisplays.add(weapon.display);
            return true;
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.WARNING, "王之宝库的武器实体生成失败，本次施放会少一把武器。", failure);
            weapon.display = null;
            return false;
        }
    }

    /**
     * Aims the weapon at its own impact point: the blade points along the flight line, so the weapon
     * flies tip-first and stabs toward the spot it is going to hit.
     * <p>
     * The pose is two rotations:
     * <ol>
     *   <li><b>right rotation</b> - the sword sprite's blade is <b>diagonal</b>, not aligned with
     *       local +Y, so it is first turned inside its own plane by {@code blade-tilt-degrees}. Without
     *       this the blade sits a fixed angle away from the flight line no matter how the weapon is
     *       aimed, which is what made earlier attempts read as "thrown sideways" and then as "pointing
     *       diagonally up".</li>
     *   <li><b>left rotation</b> - turns local +Y onto the start→impact vector, carrying the now
     *       blade-aligned sprite with it. Straight down is a degenerate case for a shortest-arc
     *       rotation (the axis is undefined when the two vectors are antiparallel), so it is handled
     *       explicitly with a 180° flip in the sprite plane.</li>
     * </ol>
     * A third rotation rolls the sprite about the flight axis, which cannot disturb the blade
     * direction at all (a roll about the blade axis leaves the blade invariant). It puts the sprite's
     * face into the horizontal plane <i>perpendicular</i> to the flight line, so the weapon reads as a
     * blade seen in profile instead of collapsing to a thin edge-on line.
     * <p>
     * The scale stays 1: the {@code NONE} transform (see {@link #spawnGateWeapon}) applies no scaling
     * of its own, so no compensation is needed to keep the one-block size.
     */
    private void faceGateWeapon(GateWeapon weapon, GateOfBabylonSkill skill) {
        if (weapon.display == null) return;
        Vector3f blade = gateBladeDirection(weapon);
        double tilt = Math.toRadians(skill.bladeTiltDegrees());
        weapon.display.setTransformation(new Transformation(
                new Vector3f(),
                gateAimRotation(blade, tilt),
                new Vector3f(1f, 1f, 1f),
                new Quaternionf().rotationZ((float) tilt)));
        weapon.display.setRotation(0.0F, 0.0F);
    }

    /**
     * Draws the flat golden ring at a hovering weapon's hilt - the "being drawn out of the treasury"
     * visual that fills the wait between spawning and launching.
     * <p>
     * The ring is centred on the <b>hilt</b>, not on the entity: the sprite is one block tall and
     * centred on the entity, and {@link #faceGateWeapon} has already aimed its blade along the flight
     * line, so the hilt sits {@link #GATE_HILT_OFFSET} back from the centre along the blade axis.
     * With the default {@code blade} plane the ring's normal <i>is</i> the blade, so the weapon passes
     * through the middle of the ring and reads as being pulled out of a circular gate.
     */
    private void drawGateRipple(GateWeapon weapon, GateOfBabylonSkill skill) {
        if (weapon.rippleFailed) return;
        ParticleConfig particle = skill.rippleParticle();
        if (particle == null || !particle.enabled()) return;
        Vector3f blade = gateBladeDirection(weapon);
        Location hilt = weapon.start.clone().add(
                -blade.x * GATE_HILT_OFFSET, -blade.y * GATE_HILT_OFFSET, -blade.z * GATE_HILT_OFFSET);
        Vector axis = skill.ripplePlane() == GateOfBabylonSkill.RipplePlane.HORIZONTAL
                ? new Vector(0, 1, 0)
                : new Vector(blade.x, blade.y, blade.z);
        try {
            particleEffectPlayer.playGateRipple(hilt, axis, particle, weapon.waitTicks * GATE_RIPPLE_SPIN);
        } catch (RuntimeException exception) {
            // Reported once per weapon: this runs every tick, and the weapons themselves are fine, so
            // a bad particle name must not spam the log or cost the cast.
            weapon.rippleFailed = true;
            plugin.getLogger().warning("王之宝库的剑柄圆环粒子失败（武器本身不受影响）：" + exception);
        }
    }

    /** Unit vector from the weapon's spawn point to its impact point; straight down if degenerate. */
    private Vector3f gateBladeDirection(GateWeapon weapon) {
        Vector3f direction = weapon.impact.toVector().subtract(weapon.start.toVector()).toVector3f();
        if (direction.lengthSquared() < 1.0E-6f) return new Vector3f(0f, -1f, 0f);
        return direction.normalize();
    }

    /**
     * The left rotation: blade onto {@code blade}, then a roll about that same axis so the sprite's
     * face is horizontal and perpendicular to the flight line. The roll is applied first in the
     * returned product ({@code roll * aim}) and rotates about the already-aimed blade axis, so the
     * blade direction is preserved exactly.
     *
     * @param tilt the same in-plane tilt used as the right rotation, needed here to know where the
     *             sprite's face ends up once aimed.
     */
    private Quaternionf gateAimRotation(Vector3f blade, double tilt) {
        Quaternionf aim = new Quaternionf();
        if (blade.y < -0.9999f) {
            // Antiparallel case: a shortest-arc rotation has no unique axis here, so flip in-plane.
            aim.rotationZ((float) Math.PI);
        } else {
            aim.rotationTo(new Vector3f(0f, 1f, 0f), blade);
        }

        // Where the sprite's face (local +Z, after the in-plane tilt) points once aimed.
        Vector3f face = new Vector3f(0f, 0f, 1f);
        new Quaternionf().rotationZ((float) tilt).transform(face);
        aim.transform(face);
        Vector3f perpendicular = face.sub(new Vector3f(blade).mul(face.dot(blade)));
        if (perpendicular.lengthSquared() < 1.0E-6f) return aim;
        perpendicular.normalize();

        // Target face direction: horizontal, and perpendicular to the flight line.
        Vector3f target;
        if (Math.abs(blade.x) < 1.0E-6f && Math.abs(blade.z) < 1.0E-6f) {
            // Straight down: the aimed face is already horizontal, so no roll is wanted.
            return aim;
        }
        Vector3f horizontal = new Vector3f(blade.x, 0f, blade.z).normalize();
        target = new Vector3f(-horizontal.z, 0f, horizontal.x);
        target.sub(new Vector3f(blade).mul(target.dot(blade)));
        if (target.lengthSquared() < 1.0E-6f) return aim;
        target.normalize();

        float angle = (float) Math.atan2(
                new Vector3f(perpendicular).cross(target).dot(blade),
                perpendicular.dot(target));
        // roll * aim: the roll rotates about the world-space blade axis the aim already produced.
        return new Quaternionf().rotationAxis(angle, blade).mul(aim);
    }

    /** Moves one weapon a step along its line; returns false once it has reached the impact point. */
    private boolean advanceGateWeapon(GateWeapon weapon, GateOfBabylonSkill skill) {
        if (weapon.display == null) return false;
        double total = Math.max(0.5, weapon.start.distance(weapon.impact));
        weapon.progress = Math.min(1.0, weapon.progress + Math.max(0.1, skill.fallSpeed()) / total);
        Location previous = weapon.lastPosition == null ? weapon.start : weapon.lastPosition;
        Location point = weapon.start.clone()
                .add(weapon.impact.toVector().subtract(weapon.start.toVector()).multiply(weapon.progress));
        // teleport() copies the location's yaw/pitch onto the entity, and a FIXED display applies the
        // entity rotation <b>on top of</b> the transformation. weapon.start is cloned from the caster's
        // location, so it carries the caster's view rotation: the weapon is posed correctly at spawn
        // (faceGateWeapon resets the rotation right after spawning) and then visibly tilts the moment
        // it starts moving, because the first teleport writes the caster's yaw/pitch back onto it.
        // Zeroing both keeps the entity rotation at identity, so the pose is decided solely by the
        // transformation and cannot drift with where the caster happens to be looking.
        point.setYaw(0.0F);
        point.setPitch(0.0F);
        weapon.lastPosition = point;
        weapon.segmentFrom = previous;
        weapon.segmentTo = point;
        weapon.display.teleport(point);
        drawGateTrail(previous, point, skill.trailParticle());
        return weapon.progress < 1.0;
    }

    /**
     * Draws the golden trail for one step. The particles are spread across the segment the weapon
     * just travelled instead of being dropped at its current position: at a fall speed of a block per
     * tick, one point per tick leaves a visible gap and the trail reads as a dotted line.
     */
    private void drawGateTrail(Location from, Location to, ParticleConfig particle) {
        if (particle == null || !particle.enabled()) return;
        int samples = Math.max(1, (int) Math.ceil(from.distance(to)));
        int total = Math.max(1, particle.count());
        for (int index = 0; index < samples; index++) {
            Location point = from.clone().add(to.toVector().subtract(from.toVector()).multiply((index + 1) / (double) samples));
            particleEffectPlayer.playJittered(point, particle, Math.max(1, total / samples));
        }
    }

    /** Lands one weapon: impact effects, then one skill-damage burst on everything at the point. */
    private void landGateWeapon(Player player, RpgItem item, RpgStats stats, GateOfBabylonSkill skill,
                                GateWeapon weapon, Map<UUID, Integer> hitsByTarget) {
        weapon.landed = true;
        weapon.lingerTicks = 0;
        Location point = weapon.impact.clone();
        // Zero rotation, for the same reason as the flight teleports: teleport() would otherwise write
        // the impact location's yaw/pitch onto the entity and rotate the pose. impact happens to be
        // built with zero rotation today, so this is belt-and-braces rather than a live fix.
        point.setYaw(0.0F);
        point.setPitch(0.0F);
        // No rotation change on landing: the blade already points along the flight line, so it stays
        // pointing into the ground it just hit.
        if (weapon.display != null) weapon.display.teleport(point);
        try {
            // Purely visual: no explosion entity is created, so blocks and dropped items are
            // untouched. playImpactBurst (not playBurst) is used deliberately - see that method:
            // playBurst silently falls through to playAlways for any effect that is not
            // circle/sphere/round, and playAlways offsets its shape one block above the centre,
            // which is why the configured explosion appeared to have vanished: it was a handful of
            // SMALL_FLAME sprites floating a block over the impact instead of a blast on the ground.
            particleEffectPlayer.playImpactBurst(point, skill.explosionParticle());
            particleEffectPlayer.playRisingBurst(point, skill.impactParticle());
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("王之宝库的落地粒子失败（伤害不受影响）：" + exception);
        }
        playSound(point, skill.impactSound());
        if (skill.damage() == null) return;
        double radius = Math.max(0.5, skill.impactRadius());
        for (LivingEntity target : point.getNearbyLivingEntities(radius, Math.max(0.5, skill.impactHeight()), radius)) {
            if (target.getLocation().distanceSquared(point) > radius * radius) continue;
            if (!canSkillReach(player, target, skill.damage())) continue;
            UUID id = target.getUniqueId();
            int used = hitsByTarget.getOrDefault(id, 0);
            if (used >= Math.max(1, skill.maxHitsPerTarget())) continue;
            // A fresh hit set per weapon on purpose: every weapon is its own hit, and the per-target
            // ceiling is tracked in hitsByTarget. Sharing one set would let the first weapon to land
            // claim every target and the remaining volley would deal nothing at all.
            if (damageTarget(player, item, target, stats, skill.damage(), new HashSet<>())) {
                hitsByTarget.put(id, used + 1);
            }
        }
    }

    /**
     * The living entity the weapon just flew through, or {@code null} for a clean miss.
     * <p>
     * Tests the <b>segment travelled this tick</b> rather than the weapon's current point. At the
     * default {@code fall-speed} of 0.8 that hardly matters, but a rain of arrows wants a fast
     * {@code fall-speed}, and at 1.0+ a weapon advances whole blocks between ticks — a point test
     * would let it skip clean over a target standing exactly where it passed. The distance is
     * measured to the segment, so a target anywhere along this tick's step is caught.
     */
    private LivingEntity findGateCollision(Player player, GateWeapon weapon, GateOfBabylonSkill skill) {
        Location from = weapon.segmentFrom;
        Location to = weapon.segmentTo;
        if (from == null || to == null || skill.damage() == null) return null;
        double radius = Math.max(0.1, skill.collisionRadius());
        // One box query centred on the segment's midpoint, sized to cover the whole step plus the
        // hit radius, so the volley costs one entity lookup per weapon per tick rather than a scan.
        Location mid = from.clone().add(to.toVector().subtract(from.toVector()).multiply(0.5));
        double reach = from.distance(to) / 2.0 + radius;
        LivingEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (LivingEntity candidate : mid.getWorld().getNearbyLivingEntities(mid, reach, reach, reach)) {
            // The caster is never a target: every weapon spawns around them and one aimed straight
            // down would otherwise hit the shooter on the first tick.
            if (candidate.equals(player) || candidate.isDead()) continue;
            if (!canSkillReach(player, candidate, skill.damage())) continue;
            // Against the entity's centre of mass, not its feet: a fall of arrows raining past a
            // target should register a hit on the torso it visibly passed through.
            Vector centre = candidate.getLocation().toVector().add(new Vector(0, candidate.getHeight() / 2.0, 0));
            double distance = distanceToSegment(centre, from.toVector(), to.toVector());
            if (distance > radius) continue;
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = candidate;
            }
        }
        return nearest;
    }

    /** Shortest distance from {@code point} to the segment {@code a}→{@code b}. */
    private static double distanceToSegment(Vector point, Vector a, Vector b) {
        Vector ab = b.clone().subtract(a);
        double lengthSquared = ab.lengthSquared();
        if (lengthSquared < 1.0E-6) return point.distance(a);
        // Clamped so a point beyond either end measures to the endpoint, not to the infinite line.
        double t = Math.max(0.0, Math.min(1.0, point.clone().subtract(a).dot(ab) / lengthSquared));
        return point.distance(a.clone().add(ab.multiply(t)));
    }

    /**
     * One weapon hits a living entity mid-flight: impact effects at the contact point, then a
     * <b>single</b> skill-damage settlement on that target.
     * <p>
     * Deliberately not the landing burst. Reusing {@link #landGateWeapon} here would deal area damage
     * to everything around every arrow, which is the explosion the arrow version exists to get rid
     * of — the same call would also let one arrow damage a target it never touched. The arrow then
     * lingers where it struck instead of vanishing, and the ordinary linger path removes it.
     */
    private void strikeGateWeapon(Player player, RpgItem item, RpgStats stats, GateOfBabylonSkill skill,
                                  GateWeapon weapon, Map<UUID, Integer> hitsByTarget, LivingEntity target) {
        weapon.landed = true;
        weapon.lingerTicks = 0;
        Location point = weapon.segmentTo == null ? weapon.lastPosition : weapon.segmentTo;
        if (point == null) point = target.getLocation();
        point = point.clone();
        // Same reason as the flight teleports: teleport() writes the location's rotation onto the
        // entity, and a FIXED display applies that rotation on top of the transformation.
        point.setYaw(0.0F);
        point.setPitch(0.0F);
        if (weapon.display != null) weapon.display.teleport(point);
        try {
            particleEffectPlayer.playRisingBurst(point, skill.impactParticle());
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("王之宝库系技能的命中粒子失败（伤害不受影响）：" + exception);
        }
        playSound(point, skill.impactSound());
        UUID id = target.getUniqueId();
        int used = hitsByTarget.getOrDefault(id, 0);
        if (used >= Math.max(1, skill.maxHitsPerTarget())) return;
        // A fresh hit set per weapon, exactly as the landing path does: every weapon is its own hit,
        // and the per-target ceiling is what stops a 16-arrow volley from landing 16 times.
        if (damageTarget(player, item, target, stats, skill.damage(), new HashSet<>())) {
            hitsByTarget.put(id, used + 1);
        }
    }

    /** Removes one weapon's entity and drops it from the tracking set; safe to call twice. */
    private void releaseGateWeapon(GateWeapon weapon) {
        if (weapon.released) return;
        weapon.released = true;
        if (weapon.display == null) return;
        gateDisplays.remove(weapon.display);
        if (!weapon.display.isDead()) weapon.display.remove();
    }

    /**
     * First standing surface at or below {@code startY}. Scans down from the weapon's spawn height,
     * so a volley under a roof lands on the roof rather than falling through it.
     */
    private double findGateGroundY(World world, double x, double z, int startY) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        int y = Math.min(world.getMaxHeight() - 1, Math.max(world.getMinHeight(), startY));
        while (y > world.getMinHeight() && world.getBlockAt(blockX, y, blockZ).isPassable()) y--;
        if (y <= world.getMinHeight()) return world.getHighestBlockYAt(blockX, blockZ) + 1.0;
        return y + 1.0;
    }

    /**
     * Prints what one burst of a circle actually sees: the resolved geometry, the parsed damage
     * configuration, and every entity in range with the verdict of the target filter. Written
     * because "the circle shows but deals nothing" has several silent causes — a missing damage
     * block, a filter that rejects everything, or a settled damage of zero — and they are
     * indistinguishable from the outside.
     */
    private void logMagicCircleCandidates(Player player, Location center, MagicCircleSkill skill, Collection<LivingEntity> candidates, RpgStats stats) {
        plugin.getLogger().info("[MagicCircle] radius=" + skill.radius() + " height=" + skill.height()
                + " target=" + skill.target() + " includeSelf=" + skill.includeSelf());
        if (skill.damage() == null) {
            plugin.getLogger().info("[MagicCircle] 伤害配置为 null：没有解析到 damage / weapon-percent，本技能不会有任何伤害");
        } else {
            plugin.getLogger().info("[MagicCircle] damage=" + skill.damage().damage()
                    + " weaponPercent=" + skill.damage().weaponPercent()
                    + " damageType=" + skill.damage().damageType()
                    + " sources=" + skill.damage().weaponPercentSources());
        }
        plugin.getLogger().info("[MagicCircle] 范围内实体=" + candidates.size());
        for (LivingEntity candidate : candidates) {
            boolean accepted = isMagicCircleTarget(player, candidate, skill);
            plugin.getLogger().info("[MagicCircle]   - " + candidate.getType() + " " + candidate.getName()
                    + " 距离=" + String.format(Locale.ROOT, "%.2f", candidate.getLocation().distance(center))
                    + " 通过筛选=" + accepted
                    + (candidate.hasMetadata(MAGIC_CIRCLE_ARMOR_STAND) ? " (法阵自身实体)" : ""));
            if (accepted && skill.damage() != null) {
                plugin.getLogger().info("[MagicCircle]     类型伤害=" + skillDamageByType(player, candidate, stats, skill.damage()));
            }
        }
    }

    private static String formatLocation(Location location) {
        return String.format(Locale.ROOT, "%.1f/%.1f/%.1f", location.getX(), location.getY(), location.getZ());
    }

    private boolean isMagicCircleTarget(Player player, LivingEntity target, MagicCircleSkill skill) {
        if (target.equals(player) && !skill.includeSelf()) return false;
        if (target.hasMetadata(MAGIC_CIRCLE_ARMOR_STAND)) return false;
        String mode = skill.target().toUpperCase(Locale.ROOT);
        if (mode.equals("ALL")) return true;
        if (mode.equals("PLAYER")) return target instanceof Player;
        // A training dummy exists to be damaged, and its entity type is switchable, so classifying
        // it by type made a villager- or animal-shaped dummy immune to the circle while the same
        // dummy still took sweep and cross-slash damage. Dummies always count as enemies.
        if (mode.equals("ENEMY") && plugin.getDummyManager().isDummy(target)) return true;
        boolean friendly = isFriendlyTarget(target);
        if (mode.equals("FRIENDLY")) return friendly;
        if (mode.equals("ENEMY")) return !friendly;
        return false;
    }

    /**
     * Shared by the circle and the range buff.
     * <p>
     * Iron golems used to be listed here, which made them permanently immune to every ENEMY-mode
     * skill even though they are ordinary attackable mobs. A golem spawned by another plugin as a
     * training target could therefore never be hit by a magic circle at all -- the diagnostic log
     * showed the entity in range with {@code 通过筛选=false} on every burst.
     */
    private boolean isFriendlyTarget(LivingEntity target) {
        return target instanceof Player
                || target instanceof org.bukkit.entity.Animals
                || target instanceof org.bukkit.entity.AbstractVillager
                || target instanceof org.bukkit.entity.WaterMob
                || target instanceof org.bukkit.entity.Ambient;
    }
    private void drawCircle(Location center, double radius, ParticleConfig particle) {
        if (particle == null || !particle.enabled()) return;
        int points = Math.max(12, particle.count());
        for (int index = 0; index < points; index++) {
            double angle = Math.PI * 2 * index / points;
            particleEffectPlayer.playSingle(center.clone().add(Math.cos(angle) * radius, -0.02, Math.sin(angle) * radius), particle);
        }
    }
    private void runDimensionalSlash(Player player, RpgItem item, RpgStats stats, DamageSkill skill, ParticleConfig lineParticle) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        double distance = Math.max(0.5, skill.length());
        var hit = player.getWorld().rayTraceBlocks(eye, direction, distance, FluidCollisionMode.NEVER, true);
        Location center = hit == null ? eye.clone().add(direction.clone().multiply(distance)) : hit.getHitPosition().toLocation(player.getWorld());
        // 爆炸本体只放 1 颗 EXPLOSION 粒子（不再铺 6 圈白墙）；叠加层照常由配置粒子画。
        center.getWorld().spawnParticle(Particle.EXPLOSION, center, 1, 0, 0, 0, 0);
        if (skill.particle() != null && skill.particle().enabled()) particleEffectPlayer.playBurst(center, skill.particle());
        // 新型粒子：爆炸范围附近、任意方向的粒子线（默认蓝色），叠在爆炸之上。
        particleEffectPlayer.playRandomLineBurst(center, lineParticle);
        playSound(center, skill.sound());
        Set<UUID> hitTargets = new HashSet<>();
        for (LivingEntity target : center.getNearbyLivingEntities(skill.radius(), skill.height(), skill.radius())) {
            if (canSkillReach(player, target, skill)) damageTarget(player, item, target, stats, skill, hitTargets);
        }
    }

    /**
     * 上挑：沿一条<b>固定</b>的上挑路线画粒子——从身前低处起沿弧线升到身前高处，每次施放
     * 轨迹完全一致（刻意不用随机数，用户要的就是「固定上挑路线」），命中的生物被击飞，
     * 速度尽量朝正上方（{@code launch} 决定飞多高）。
     */
    private void runUppercut(Player player, RpgItem item, RpgStats stats, UppercutSkill skill) {
        Location feet = player.getLocation();
        Vector facing = feet.getDirection().setY(0).normalize();
        if (facing.lengthSquared() <= 0.01) facing = new Vector(0, 0, 1);
        Location front = feet.clone().add(facing.clone().multiply(skill.distance()));
        List<Location> points = new ArrayList<>();
        int steps = 24;
        for (int index = 0; index <= steps; index++) {
            double progress = index / (double) steps;
            double lift = -0.4 + progress * (skill.height() + 0.4);
            // 向前微微推出去（sin 曲线），刀路才是「上挑」的弧而不是一根直棍。
            double push = Math.sin(progress * Math.PI / 2) * 0.7;
            points.add(front.clone().add(facing.clone().multiply(push)).add(0, lift, 0));
        }
        drawArcPoints(points, skill.particle());
        playSound(feet, skill.sound());
        Set<UUID> hitTargets = new HashSet<>();
        for (LivingEntity target : front.getNearbyLivingEntities(skill.radius() + 0.6, skill.height(), skill.radius() + 0.6)) {
            if (target.equals(player)) continue;
            if (!canSkillReach(player, target, skill.damage())) continue;
            if (!damageTarget(player, item, target, stats, skill.damage(), hitTargets)) continue;
            // 击飞：竖直分量 = launch（不再纯直线向上），水平分量 = 朝前（玩家朝向）的一小段，
            // 读作「上挑把人带飞出去」，而不是原地直上直下。水平只有 launch 的 0.4 倍，属轻微。
            target.setVelocity(facing.clone().multiply(skill.launch() * 0.4).setY(skill.launch()));
        }
    }

    /**
     * 蹦山击：先把玩家抛到 {@code leapHeight} 格高（默认 5），到最高点后按固定俯冲角
     * （默认 45°）朝视线前方砸向地面；落地时结算冲击波（缩减版）+ 轻微击飞 + 小爆炸。
     */
    private void runLeapStrike(Player player, RpgItem item, RpgStats stats, LeapStrikeSkill skill) {
        Vector facing = player.getLocation().getDirection().setY(0).normalize();
        if (facing.lengthSquared() <= 0.01) facing = new Vector(0, 0, 1);
        Vector forward = facing;
        playSound(player.getLocation(), skill.sound());
        plugin.getActiveDefenseListener().armSkillDodge(player, 30);
        // 起跳：Minecraft 重力约 0.08 格/tick²，v = sqrt(2gh) 就是「刚好到 h 格」的初速度。
        double jumpVelocity = Math.sqrt(2 * 0.08 * skill.leapHeight());
        player.setVelocity(new Vector(0, Math.min(2.2, jumpVelocity), 0));
        double diveAngle = Math.toRadians(skill.diveAngleDegrees());
        double horizontal = Math.cos(diveAngle);
        double vertical = -Math.sin(diveAngle);
        new BukkitRunnable() {
            private int ticks;

            @Override
            public void run() {
                ticks++;
                if (!player.isOnline() || player.isDead()) {
                    cancel();
                    return;
                }
                // 上升阶段不干预（否则跳不到设定高度）；到最高点后开始俯冲。
                if (ticks < 40 && player.getVelocity().getY() > 0.01) return;
                if (ticks > 120) {
                    cancel();
                    return;
                }
                player.setVelocity(forward.clone().multiply(horizontal).setY(vertical).normalize().multiply(LEAP_DIVE_SPEED));
                if (player.isOnGround()) {
                    cancel();
                    impactLeapStrike(player, item, stats, skill);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** 蹦山击的落地结算：缩减版冲击波 + 轻微击飞 + 落地小爆炸。 */
    private void impactLeapStrike(Player player, RpgItem item, RpgStats stats, LeapStrikeSkill skill) {
        Location impact = player.getLocation().clone();
        // 冲击波沿用震地的「一圈圈扩散」造型，但半径与粒子数都缩减（playCompactShockwave）。
        particleEffectPlayer.playCompactShockwave(impact, skill.shockwaveParticle(), skill.impactRadius());
        // 落地小爆炸：以落点上方一点为球心，按配置半径铺一层粒子。
        particleEffectPlayer.playImpactBurst(impact.clone().add(0, 0.4, 0), skill.explosionParticle());
        playSound(impact, skill.impactSound());
        Set<UUID> hitTargets = new HashSet<>();
        for (LivingEntity target : impact.getNearbyLivingEntities(skill.impactRadius(), skill.impactRadius(), skill.impactRadius())) {
            if (target.equals(player)) continue;
            if (!damageTarget(player, item, target, stats, skill.damage(), hitTargets)) continue;
            // 轻微击飞：保留一点原有水平动量，只把竖直分量抬起来。
            if (skill.knockUp() > 0) target.setVelocity(target.getVelocity().multiply(0.3).setY(skill.knockUp()));
        }
    }

    private void runDamageSkill(Player player, RpgItem item, RpgStats stats, DamageSkill skill) {
        Set<UUID> hitTargets = new HashSet<>();
        Runnable action = () -> {
            if (skill.mode().equalsIgnoreCase("area")) areaDamage(player, item, stats, skill, hitTargets);
            else waveDamage(player, item, stats, skill, hitTargets);
        };
        playSound(player.getLocation(), skill.sound());
        if (!skill.repeating()) action.run();
        else repeat(Math.max(0.1, skill.durationSeconds()), Math.max(0.05, skill.intervalSeconds()), action);
    }

    private void waveDamage(Player player, RpgItem item, RpgStats stats, DamageSkill skill, Set<UUID> hitTargets) {
        Location start = player.getEyeLocation();
        List<Vector> directions = waveDirections(start.getDirection().normalize(), skill);
        for (Vector direction : directions) castWave(player, item, stats, skill, hitTargets, start, direction);
    }

    private void castWave(Player player, RpgItem item, RpgStats stats, DamageSkill skill, Set<UUID> hitTargets, Location start, Vector direction) {
        boolean stopped = false;
        double maxLength = Math.max(1, plugin.getConfig().getDouble("fskill.max-ray-length", 512));
        double length = Math.min(maxLength, Math.max(0.5, skill.length()));
        double step = Math.max(0.1, plugin.getConfig().getDouble("fskill.ray-step", 0.4));
        for (double distance = 0.5; distance <= length && !stopped; distance += step) {
            Location point = start.clone().add(direction.clone().multiply(distance));
            if (!skill.pierceBlocks() && !isPassable(point)) break;
            particleEffectPlayer.playTrail(point, skill.particle());
            for (LivingEntity target : point.getNearbyLivingEntities(Math.max(0.3, skill.radius()))) {
                if (!canSkillReach(player, target, skill)) continue;
                if (damageTarget(player, item, target, stats, skill, hitTargets)) {
                    damageInAttackRange(player, item, target, stats, skill, hitTargets);
                    applySkillKnockback(player, target, skill);
                    if (skill.pierce()) continue;
                    stopped = true;
                    break;
                }
            }
        }
    }

    private List<Vector> waveDirections(Vector base, DamageSkill skill) {
        int count = Math.max(1, skill.spreadCount());
        if (count == 1) return List.of(base);
        List<Vector> directions = new ArrayList<>();
        double angle = Math.toRadians(Math.max(0, skill.spreadAngle()));
        Vector aim = base.clone().normalize();
        double vertical = aim.getY();
        double horizontalScale = Math.sqrt(Math.max(0, 1.0 - vertical * vertical));
        Vector horizontalBase = aim.clone().setY(0);
        if (horizontalBase.lengthSquared() == 0) horizontalBase = new Vector(0, 0, 1);
        horizontalBase.normalize();
        Vector side = new Vector(-horizontalBase.getZ(), 0, horizontalBase.getX()).normalize();
        for (int index = 0; index < count; index++) {
            double ratio = count == 1 ? 0 : index / (double) (count - 1);
            double yaw = -angle / 2 + angle * ratio;
            Vector direction = horizontalBase.clone().multiply(Math.cos(yaw) * horizontalScale)
                    .add(side.clone().multiply(Math.sin(yaw) * horizontalScale))
                    .add(new Vector(0, vertical, 0)).normalize();
            directions.add(direction);
        }
        return directions;
    }

    private void areaDamage(Player player, RpgItem item, RpgStats stats, DamageSkill skill, Set<UUID> hitTargets) {
        Location center = player.getLocation().clone().add(0, 0.05, 0);
        particleEffectPlayer.playGroundExpansion(center, skill.particle(), skill.pierceBlocks()
                ? location -> true
                : location -> canTrace(center, location));
        double radius = Math.max(0.5, skill.radius() + Math.max(0, skill.spreadRadius()));
        for (LivingEntity target : center.getNearbyLivingEntities(radius, Math.max(0.5, skill.height()), radius)) {
            if (!canSkillReach(player, target, skill)) continue;
            if (damageTarget(player, item, target, stats, skill, hitTargets)) {
                damageInAttackRange(player, item, target, stats, skill, hitTargets);
                applySkillKnockback(player, target, skill);
                if (!skill.pierce()) break;
            }
        }
    }

    /** damages 段的击退（踢击）：命中后把目标从玩家身边水平推开并稍微抬一点。 */
    private void applySkillKnockback(Player player, LivingEntity target, DamageSkill skill) {
        if (skill.knockback() <= 0) return;
        Vector offset = target.getLocation().toVector().subtract(player.getLocation().toVector()).setY(0);
        double length = offset.length();
        Vector direction = length < 0.01
                ? player.getLocation().getDirection().setY(0).normalize()
                : offset.normalize();
        if (direction.lengthSquared() < 0.01) direction = new Vector(0, 0, 1);
        target.setVelocity(direction.multiply(skill.knockback()).setY(0.28));
    }

    private void damageInAttackRange(Player player, RpgItem item, LivingEntity center, RpgStats stats, DamageSkill skill, Set<UUID> hitTargets) {
        double range = Math.max(0, stats.attackRange());
        if (range <= 0) return;
        for (LivingEntity nearby : center.getLocation().getNearbyLivingEntities(range, range, range)) {
            if (!nearby.equals(center) && canSkillReach(player, nearby, skill)) damageTarget(player, item, nearby, stats, skill, hitTargets);
        }
    }

    private boolean canSkillReach(Player player, LivingEntity target, DamageSkill skill) {
        return skill.pierceBlocks() || player.hasLineOfSight(target);
    }

    private boolean isPassable(Location location) {
        return location.getBlock().isPassable();
    }

    private boolean canTrace(Location start, Location end) {
        Vector offset = end.toVector().subtract(start.toVector());
        double distance = offset.length();
        if (distance <= 0.01) return true;
        return start.getWorld().rayTraceBlocks(start, offset.normalize(), distance, FluidCollisionMode.NEVER, true) == null;
    }

    private boolean damageTarget(Player player, RpgItem item, LivingEntity target, RpgStats stats, DamageSkill skill, Set<UUID> hitTargets) {
        if (target.equals(player) || target.isDead() || target.hasMetadata(MAGIC_CIRCLE_ARMOR_STAND)) return false;
        if (target instanceof Player targetPlayer && (targetPlayer.getGameMode() == GameMode.CREATIVE || targetPlayer.getGameMode() == GameMode.SPECTATOR)) return false;
        // Only claim the once-per-cast slot for a hit that actually lands. Registering the target up
        // front meant any later early return - a zero-damage roll, or attributed damage being refused -
        // still consumed the slot, so a second legitimate hit on the same target in the same cast was
        // silently dropped.
        if (skill.hitOncePerCast() && hitTargets.contains(target.getUniqueId())) return false;
        RpgStats defenderStats = plugin.getRpgItemManager().collectActiveStats(target, false);
        double armorPen = Math.min(100, Math.max(0, stats.armorPen())) / 100.0;
        boolean backstrike = isBackstrike(player, target);
        boolean assassin = isAssassinHit(player, target) || backstrike;
        // Roll crit once per family and reuse it, matching the normal attack path in
        // RpgCombatListener.calculateDamage: there the physical family shares one roll, the magic
        // family shares another, and the elemental types never roll at all. Rolling per damage type
        // made elements crit on their own and printed them with the crit icon, which normal attacks
        // never do.
        boolean canCrit = skill.canCrit();
        CriticalRoll physicalCritical = canCrit && isSkillCritEligible("PHYSICAL") && isSkillCritEnabled("PHYSICAL")
                ? rollCritical(stats.critChance(), stats.critDamage(), defenderStats.critArmor(), defenderStats.critArmorPercent(), armorPen)
                : CriticalRoll.none();
        CriticalRoll magicCritical = canCrit && isSkillCritEligible("MAGIC") && isSkillCritEnabled("MAGIC")
                ? rollCritical(stats.critChance(), stats.critDamage(), defenderStats.critArmor(), defenderStats.critArmorPercent(), armorPen)
                : CriticalRoll.none();
        // Elements never roll a crit, but they do scale by percentage from the crit-inflated base,
        // exactly as melee elements do. Passing the unmitigated crit bonus in as part of that base is
        // what gives ice/fire/light/dark their proportional share of a crit without ever marking them
        // as critical. Omitting it made skill elements ignore crit entirely - a different
        // inconsistency from the one being fixed.
        double elementCritBonus = rawSkillCritDamage(stats, physicalCritical) + rawSkillCritDamage(stats, magicCritical);
        // Backstrike and assassin flat bonuses are assembled before the damage types because melee
        // folds them into the base the elements scale from. They are still applied below as their own
        // display lines, so this only widens the elemental base.
        double backstrikePercent = Math.max(0, stats.backstrikeDamagePercent()) / 100.0;
        double weaponPanelDamage = weaponBaseDamage(player, stats);
        double backstrikeBonus = backstrike ? stats.backstrikeDamage() * (1 + backstrikePercent) + weaponPanelDamage * backstrikePercent : 0;
        double assassinFlat = assassin ? Math.max(0, stats.assDamage()) : 0;
        double bonusFlat = backstrikeBonus + assassinFlat;
        // CritDamagePercent scales the crit itself, and the skill path used to ignore it completely:
        // only the flat CritDamage reached the roll. Anyone who invested in crit damage percentage saw
        // melee and bow crits grow while every skill crit stayed exactly as before, which reads as the
        // stat being broken rather than as a skill-only omission.
        // It multiplies the same base the melee path multiplies — the pre-crit raw damage — so the two
        // agree instead of drifting apart again. The element base above deliberately keeps using the
        // flat value only, because that is what melee does with rawCriticalDamage.
        double critDamagePercent = Math.max(0, stats.critDamagePercent()) / 100.0;
        Map<String, Double> rawDamageByType = skillDamageByType(player, target, stats, skill, elementCritBonus, bonusFlat, assassin);
        List<String> displayLines = new ArrayList<>();
        long totalDamage = 0;
        // The assassin / backstrike percentage attributes are physical-scaling stats in the melee
        // calculation, so they are accumulated from the physical buckets only. Deriving them from the
        // whole total made magic, bow, true and elemental damage widen the assassin bonus as well,
        // which every caster build now triggers because all sources contribute by default.
        long physicalDamage = 0;
        for (Map.Entry<String, Double> entry : rawDamageByType.entrySet()) {
            String type = normalizeDamageType(entry.getKey());
            double rawDamage = entry.getValue();
            CriticalRoll critical = switch (type) {
                case "MAGIC", "POTION" -> magicCritical;
                case "PHYSICAL", "BOW" -> physicalCritical;
                default -> CriticalRoll.none();
            };
            // Mirrors RpgCombatListener.calculateDamage: pre-crit base + flat crit damage
            // + CritDamagePercent of that base. Only the flat term existed here before.
            double critPercentBonus = critical.critical() ? rawDamage * critDamagePercent : 0;
            long damage = Math.max(0, Math.round(defendSkillDamage(rawDamage + critical.damage() + critPercentBonus, defenderStats, armorPen, type)));
            if (damage <= 0) continue;
            totalDamage += damage;
            if (type.equals("PHYSICAL")) physicalDamage += damage;
            displayLines.add(formatSkillDamage(type, damage, critical.critical(), type));
        }
        long assassinPercentDamage = assassin && stats.assDamagePercent() > 0
                ? Math.max(0, Math.round(physicalDamage * stats.assDamagePercent() / (100.0 + stats.assDamagePercent()))) : 0;
        long backstrikeDamage = backstrike
                ? Math.max(0, Math.round(defendSkillDamage(Math.max(0, backstrikeBonus), defenderStats, armorPen, "PHYSICAL"))) : 0;
        long assassinDamage = assassin
                ? Math.max(0, Math.round(defendSkillDamage(assassinFlat, defenderStats, armorPen, "PHYSICAL"))) : 0;
        if (assassinPercentDamage > 0) {
            totalDamage += assassinPercentDamage;
            displayLines.add(damageDisplay.format("damage-display.assassin-format", "damage-display.assassin-color", "&c☠刺客-{damage}", assassinPercentDamage));
        }
        if (backstrikeDamage > 0) {
            totalDamage += backstrikeDamage;
            displayLines.add(damageDisplay.format("damage-display.backstrike-format", "damage-display.backstrike-color", "&e🗡背击-{damage}", backstrikeDamage));
        }
        if (assassinDamage > 0) {
            totalDamage += assassinDamage;
            displayLines.add(damageDisplay.format("damage-display.assassin-format", "damage-display.assassin-color", "&c☠刺客-{damage}", assassinDamage));
        }
        if (totalDamage <= 0) return false;
        target.setNoDamageTicks(0);
        target.setLastDamage(0);
        target.setMetadata(SKILL_DAMAGE_METADATA, new FixedMetadataValue(plugin, true));
        try {
            if (!applyStrictAttributedDamage(target, player, totalDamage)) return false;
        } finally {
            target.removeMetadata(SKILL_DAMAGE_METADATA, plugin);
        }
        target.setNoDamageTicks(0);
        playSound(target.getLocation(), item.sound());
        damageDisplay.show(target.getLocation(), displayLines);
        // The hit landed, so it now claims the once-per-cast slot for this target.
        if (skill.hitOncePerCast()) hitTargets.add(target.getUniqueId());
        return true;
    }

    private boolean isBackstrike(Player attacker, LivingEntity target) {
        Vector targetToAttacker = attacker.getLocation().toVector().subtract(target.getLocation().toVector()).normalize();
        return target.getLocation().getDirection().normalize().dot(targetToAttacker) < -0.45;
    }

    private boolean isAssassinHit(Player attacker, LivingEntity target) {
        if (target instanceof Mob mob) return mob.getTarget() == null || !mob.getTarget().getUniqueId().equals(attacker.getUniqueId());
        return !(target instanceof Player);
    }

    private Map<String, Double> skillDamageByType(Player player, LivingEntity target, RpgStats stats, DamageSkill skill) {
        // Preview / debug convenience: no crit roll and no backstrike or assassin bonus.
        return skillDamageByType(player, target, stats, skill, 0, 0, false);
    }

    /**
     * Builds the per-damage-type buckets a skill shape deals.
     * <p>
     * Every source contributes unless the config excludes it, so a skill can reach every damage type
     * without registering them one by one.
     * <p>
     * Two distinct bases have to be kept apart here, because conflating them double-counts stats:
     * <ul>
     *   <li>{@code weaponBase} is the <b>physical attack</b> the multiplier scales from, mirroring
     *       {@code RpgCombatListener.calculateDamage} where melee uses
     *       {@code penetratedIncoming + effectiveAttack()}. Only the physical source feeds it.
     *       Folding magic / bow / true damage in as well made those stats widen the multiplier base
     *       <em>and</em> contribute their own bucket, so a player stacking magic damage had the
     *       multiplier applied twice, and the elemental percentages were inflated by stats that are
     *       not part of melee's attack value.</li>
     *   <li>{@code elementBase} is what melee scales elements from. Melee builds
     *       {@code physicalElementBase = vanillaRaw + effectiveAttack + percentPhysical + bonusFlat +
     *       rawCriticalDamage} and then adds the magic and true parts, so the same terms are assembled
     *       here: the attack value, the percentage damage ({@code pctDamage} / {@code pstDamage}), the
     *       backstrike and assassin flat bonuses, and the unmitigated {@code critBonus}. Elements take
     *       a percentage of it but never crit themselves. Leaving the percentage and bonus terms out
     *       made a skill's elemental share ignore stats the melee elemental share scales with.</li>
     * </ul>
     *
     * @param critBonus unmitigated crit damage from the family rolls
     * @param bonusFlat backstrike + assassin flat damage, as melee folds into {@code bonusFlat}
     * @param assassin  whether the assassin percentage multiplier applies to the element base too
     */
    private Map<String, Double> skillDamageByType(Player player, LivingEntity target, RpgStats stats, DamageSkill skill, double critBonus, double bonusFlat, boolean assassin) {
        Map<String, Double> damageByType = new LinkedHashMap<>();
        addSkillDamage(damageByType, normalizeDamageType(skill.damageType()), skill.damage());
        double multiplier = Math.max(0, skill.weaponPercent()) / 100.0;
        Set<String> sources = normalizedDamageSources(skill.weaponPercentSources());
        double weaponBase = 0;
        for (String source : sources) {
            if ("ATTACK".equals(canonicalDamageSource(source))) weaponBase += sourceDamage(player, target, stats, source);
        }
        double percentPhysical = 0;
        // pctDamage/pstDamage 是「按最大生命值百分比」的物理份额。
        // 它们以前是无条件计算的，而且下面无条件以 PHYSICAL 类型入账——
        // 于是即使 weapon-percent-sources 排除了 ATTACK，技能依然会打出一份物理伤害
        // （法师飞弹「造成 1 点物理伤害」就是这么来的）。
        // 现在跟随 ATTACK 来源一起被排除，未排除时行为与原来完全一致。
        boolean physicalIncluded = sources.contains("ATTACK");
        if (physicalIncluded) {
            percentPhysical = maxHealth(player) * Math.max(0, stats.pctDamage()) / 100.0
                    + maxHealth(target) * Math.max(0, stats.pstDamage()) / 100.0;
        }
        double elementBase = weaponBase + percentPhysical + Math.max(0, bonusFlat) + Math.max(0, critBonus);
        if (sources.contains("MAGIC_DAMAGE")) elementBase += sourceDamage(player, target, stats, "MAGIC_DAMAGE");
        if (sources.contains("TRUE_DAMAGE")) elementBase += sourceDamage(player, target, stats, "TRUE_DAMAGE");
        // Melee multiplies the element base by the assassin percentage as well, so elements grow with
        // it. Skipping this left the elemental share flat while the physical share was multiplied.
        if (assassin && stats.assDamagePercent() != 0) elementBase *= 1 + Math.max(0, stats.assDamagePercent()) / 100.0;
        for (String source : sources) {
            String type = sourceDamageType(source);
            addSkillDamage(damageByType, type, sourceDamage(player, target, stats, source, elementBase) * multiplier);
        }
        if (physicalIncluded) {
            addSkillDamage(damageByType, "PHYSICAL", maxHealth(player) * Math.max(0, stats.pctDamage()) / 100.0);
            addSkillDamage(damageByType, "PHYSICAL", maxHealth(target) * Math.max(0, stats.pstDamage()) / 100.0);
        }
        return damageByType;
    }

    /** Every source a weapon-percent multiplier can draw from; one per damage type. */
    private static final List<String> ALL_DAMAGE_SOURCES = List.of(
            "ATTACK", "MAGIC_DAMAGE", "BOW_DAMAGE", "TRUE_DAMAGE",
            "ICE_DAMAGE", "FIRE_DAMAGE", "LIGHT_DAMAGE", "DARK_DAMAGE");

    /**
     * Canonical source name for a configured token, so aliases such as {@code MAGIC} or
     * {@code MAGIC_DAMAGE} both remove the same source from the default set.
     */
    private static String canonicalDamageSource(String raw) {
        String source = raw == null ? "" : raw.toUpperCase(Locale.ROOT).replace("-", "_").replace(" ", "_");
        return switch (source) {
            case "ATTACK", "PHYSICAL" -> "ATTACK";
            case "MAGIC", "MAGIC_DAMAGE", "POTION", "POTION_DAMAGE" -> "MAGIC_DAMAGE";
            case "BOW", "BOW_DAMAGE", "PROJECTILE" -> "BOW_DAMAGE";
            case "TRUE", "TRUE_DAMAGE" -> "TRUE_DAMAGE";
            case "ICE", "ICE_DAMAGE" -> "ICE_DAMAGE";
            case "FIRE", "FIRE_DAMAGE" -> "FIRE_DAMAGE";
            case "LIGHT", "LIGHT_DAMAGE" -> "LIGHT_DAMAGE";
            case "DARK", "DARK_DAMAGE" -> "DARK_DAMAGE";
            default -> source;
        };
    }

    /**
     * The configured source list is an <b>exclusion</b> list: every source contributes unless it is
     * named, so a skill can reach every damage type without registering them one by one. An empty
     * list or an explicit {@code ALL} therefore means "exclude nothing", and naming a source cancels
     * that source's damage type. This replaced the old allow-list behaviour, where only the listed
     * sources contributed and {@code ATTACK} was the implicit default.
     */
    private Set<String> normalizedDamageSources(List<String> configuredSources) {
        Set<String> sources = new LinkedHashSet<>(ALL_DAMAGE_SOURCES);
        if (configuredSources == null) return sources;
        for (String raw : configuredSources) {
            String source = canonicalDamageSource(raw);
            if (source.equals("ALL")) return new LinkedHashSet<>(ALL_DAMAGE_SOURCES);
            sources.remove(source);
        }
        return sources;
    }

    private double sourceDamage(Player player, LivingEntity target, RpgStats stats, String source) {
        return sourceDamage(player, target, stats, source, 0);
    }

    /**
     * The attack value a {@code weapon-percent} skill scales from: the held weapon's real damage
     * plus the RPG Attack stat.
     * <p>
     * This mirrors how melee damage is assembled in RpgCombatListener.calculateDamage, which adds
     * the vanilla event damage (the weapon) to {@code effectiveAttack()} (the RPG stat). Skills
     * previously used {@code effectiveAttack()} alone, so the held weapon contributed nothing:
     * switching weapons changed melee damage but left skill damage identical, and a plain vanilla
     * weapon scaled the skill by zero.
     * <p>
     * Public because the skill-projectile path in RpgProjectileListener needs the same value. That
     * path synthesized its base from {@code effectiveAttack()} alone, so it kept the original bug
     * after the melee skill path was fixed: a bow with no RPG Attack stat contributed nothing to a
     * skill shot.
     */
    public double weaponBaseDamage(Player player, RpgStats stats) {
        return heldWeaponDamage(player) + Math.max(0, stats.effectiveAttack());
    }

    /** Player base GENERIC_ATTACK_DAMAGE (bare hand) when the attribute is unavailable. */
    private static final double BASE_ATTACK_DAMAGE = 1.0;

    /**
     * Vanilla attack damage of the held item, including the Sharpness bonus vanilla would apply.
     * <p>
     * Three sources are combined and the highest wins, because
     * {@code AttributeInstance#getValue()} does not reliably include the held item's attack damage
     * modifier: on some Paper builds it returns only the player's base value (1.0), which made the
     * weapon contribute nothing and left skill damage effectively fixed. Reading the item's own
     * modifier and the material's vanilla value as well makes the result independent of that.
     */
    private double heldWeaponDamage(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE);
        double base = attribute == null ? BASE_ATTACK_DAMAGE : Math.max(0, attribute.getBaseValue());
        ItemStack weapon = weaponStack(player);
        if (weapon == null || weapon.getType().isAir()) return base;
        double live = attribute == null ? base : Math.max(0, attribute.getValue());
        double fromItem = base + itemAttackDamage(weapon);
        double fromMaterial = vanillaAttackDamage(weapon.getType());
        double damage = Math.max(live, Math.max(fromItem, fromMaterial));
        return damage + sharpnessBonus(weapon);
    }

    /**
     * The stack a weapon-percent skill scales from.
     * <p>
     * A skill item is a carrier for a skill, not a weapon: it is an {@code ENCHANTED_BOOK} whose
     * vanilla attack damage is the bare-hand value of 1, so it must never be used as the base. The
     * panel's own weapon check already accepts a weapon in either hand, so when the main hand is a
     * skill item the weapon is expected in the other hand — use it. For the drop trigger (Q/SQ) the
     * weapon has already left the hand when the event fires, so the stack that started the cast is
     * that weapon and it takes precedence over the off hand.
     */
    private ItemStack weaponStack(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        if (main != null && !main.getType().isAir() && !isSkillCarrier(main)) return main;
        ItemStack cast = castStacks.get(player.getUniqueId());
        if (cast != null && !cast.getType().isAir() && !isSkillCarrier(cast)) return cast;
        ItemStack off = player.getInventory().getItemInOffHand();
        if (off != null && !off.getType().isAir() && !isSkillCarrier(off)) return off;
        return main;
    }

    /**
     * The same choice as {@link #weaponStack(Player)}, made from the stack that triggered the cast
     * instead of the recorded one. Used before the cast record exists, so the cooldown is charged
     * from the same weapon the damage will scale from.
     */
    private ItemStack triggerWeapon(Player player, ItemStack triggerStack) {
        if (triggerStack != null && !triggerStack.getType().isAir() && !isSkillCarrier(triggerStack)) return triggerStack;
        ItemStack off = player.getInventory().getItemInOffHand();
        if (off != null && !off.getType().isAir() && !isSkillCarrier(off)) return off;
        return triggerStack;
    }

    /** @return true when the stack is a registered skill item (skills/*.yml carrier), not a weapon. */
    private boolean isSkillCarrier(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return false;
        return plugin.getSkillPanelManager().registeredSkillId(stack) != null;
    }

    /** Sum of the item's own ADD_NUMBER attack damage modifiers, as stored on the ItemStack. */
    private double itemAttackDamage(ItemStack weapon) {
        if (!weapon.hasItemMeta()) return 0;
        ItemMeta meta = weapon.getItemMeta();
        if (meta == null) return 0;
        var modifiers = meta.getAttributeModifiers(Attribute.GENERIC_ATTACK_DAMAGE);
        if (modifiers == null) return 0;
        double sum = 0;
        for (AttributeModifier modifier : modifiers) {
            if (modifier.getOperation() == AttributeModifier.Operation.ADD_NUMBER) sum += modifier.getAmount();
        }
        return Math.max(0, sum);
    }

    /** Vanilla attack damage by material, used when neither the attribute nor the item reports it. */
    private double vanillaAttackDamage(Material material) {
        Double known = VANILLA_ATTACK_DAMAGE.get(material);
        return known == null ? BASE_ATTACK_DAMAGE : known;
    }

    private static final Map<Material, Double> VANILLA_ATTACK_DAMAGE = buildVanillaAttackDamage();

    private static Map<Material, Double> buildVanillaAttackDamage() {
        Map<Material, Double> values = new EnumMap<>(Material.class);
        for (Material material : Material.values()) {
            String name = material.name();
            if (name.endsWith("_SWORD")) {
                values.put(material, switch (name) {
                    case "WOODEN_SWORD", "GOLDEN_SWORD" -> 4.0;
                    case "STONE_SWORD" -> 5.0;
                    case "IRON_SWORD" -> 6.0;
                    case "DIAMOND_SWORD" -> 7.0;
                    case "NETHERITE_SWORD" -> 8.0;
                    default -> BASE_ATTACK_DAMAGE;
                });
            } else if (name.endsWith("_AXE")) {
                values.put(material, switch (name) {
                    case "WOODEN_AXE", "GOLDEN_AXE" -> 7.0;
                    case "STONE_AXE", "IRON_AXE", "DIAMOND_AXE" -> 9.0;
                    case "NETHERITE_AXE" -> 10.0;
                    default -> BASE_ATTACK_DAMAGE;
                });
            } else if (name.endsWith("_PICKAXE")) {
                values.put(material, switch (name) {
                    case "WOODEN_PICKAXE", "GOLDEN_PICKAXE" -> 2.0;
                    case "STONE_PICKAXE" -> 3.0;
                    case "IRON_PICKAXE" -> 4.0;
                    case "DIAMOND_PICKAXE" -> 5.0;
                    case "NETHERITE_PICKAXE" -> 6.0;
                    default -> BASE_ATTACK_DAMAGE;
                });
            } else if (name.endsWith("_SHOVEL")) {
                values.put(material, switch (name) {
                    case "WOODEN_SHOVEL", "GOLDEN_SHOVEL" -> 2.5;
                    case "STONE_SHOVEL" -> 3.5;
                    case "IRON_SHOVEL" -> 4.5;
                    case "DIAMOND_SHOVEL" -> 5.5;
                    case "NETHERITE_SHOVEL" -> 6.5;
                    default -> BASE_ATTACK_DAMAGE;
                });
            }
        }
        values.put(Material.TRIDENT, 9.0);
        values.put(Material.MACE, 6.0);
        return values;
    }

    /** Diagnostic breakdown of the inputs behind weapon-percent skill damage. */
    public List<String> weaponDamageDebug(Player player) {
        RpgStats stats = plugin.getRpgItemManager().collectActiveStats(player, false);
        AttributeInstance attribute = player.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE);
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ItemStack weapon = weaponStack(player);
        boolean empty = weapon == null || weapon.getType().isAir();
        List<String> lines = new ArrayList<>();
        lines.add("&7主手物品: &f" + (mainHand == null || mainHand.getType().isAir() ? "空手" : mainHand.getType().name()
                + (isSkillCarrier(mainHand) ? " &8(技能物品，不作为武器)" : "")));
        lines.add("&7副手物品: &f" + (player.getInventory().getItemInOffHand().getType().isAir() ? "空手" : player.getInventory().getItemInOffHand().getType().name()));
        lines.add("&7实际取用武器: &f" + (empty ? "空手" : weapon.getType().name()));
        lines.add("&7属性 base 值: &f" + (attribute == null ? "无该属性" : format(attribute.getBaseValue())));
        lines.add("&7属性 getValue: &f" + (attribute == null ? "无该属性" : format(attribute.getValue())));
        lines.add("&7物品攻击力修饰符: &f" + (empty ? "-" : format(itemAttackDamage(weapon))));
        lines.add("&7材质原版攻击力: &f" + (empty ? "-" : format(vanillaAttackDamage(weapon.getType()))));
        lines.add("&7锋利加成: &f" + (empty ? "-" : format(sharpnessBonus(weapon))));
        lines.add("&a最终武器原版伤害: &f" + format(heldWeaponDamage(player)));
        lines.add("&aRPG Attack 属性: &f" + format(Math.max(0, stats.effectiveAttack())));
        lines.add("&6倍率基数 = 武器 + RPG攻击: &f" + format(weaponBaseDamage(player, stats)));
        lines.add("&6---- 暴击 / Crit ----");
        lines.add("&7物理暴击开关 physical-crit: " + (plugin.isFeatureEnabled("physical-crit") ? "&a开" : "&c关")
                + " &7魔法暴击 magic-crit: " + (plugin.isFeatureEnabled("magic-crit") ? "&a开" : "&c关"));
        lines.add("&7暴击率功能开关 crit-chance: " + (plugin.isFeatureEnabled("crit-chance") ? "&a开" : "&c关")
                + " &7暴击伤害 crit-damage: " + (plugin.isFeatureEnabled("crit-damage") ? "&a开" : "&c关"));
        // A skill can only crit when this is above zero; it comes from the equipment the plugin
        // actually counted, so a weapon in the off hand without "deputy: true" contributes nothing.
        lines.add(stats.critChance() > 0
                ? "&a当前暴击率 CritChance: &f" + format(stats.critChance()) + "%"
                : "&c当前暴击率 CritChance: &f0% &7(技能永远不会暴击，检查装备是否被计入)");
        lines.add("&a当前暴击伤害 CritDamage: &f" + format(stats.critDamage()) + "%");
        // Printed next to CritDamage because the two are easy to confuse and only one of them used to
        // reach skill crits. Without this line, "I have crit damage but my crits don't grow" had no
        // visible number to point at the skill path.
        lines.add("&a暴击伤害百分比 CritDamagePercent: &f" + format(stats.critDamagePercent()) + "%"
                + " &7(技能暴击按此值放大伤害)");
        return lines;
    }

    /**
     * Explains the magic circle carried by the item in hand: whether the section parsed at all,
     * whether the skill would run, which target mode filters it, and the exact raw damage one
     * burst produces.
     * <p>
     * A circle that renders its ring but deals nothing is one of three things — an unparsed
     * section, a target mode that rejects every nearby entity, or a failure inside the repeating
     * task. Printing the loaded values separates those cases instead of guessing at them.
     */
    public List<String> magicCircleDebug(Player player) {
        List<String> lines = new ArrayList<>();
        ItemStack held = player.getInventory().getItemInMainHand();
        RpgItem item = plugin.getRpgItemManager().findActiveMainHandItem(player, false);
        if (item == null) {
            lines.add("&c主手不是可用的 RPGItem，无法读取法阵配置");
            return lines;
        }
        RpgStats stats = plugin.getRpgItemManager().collectActiveStats(player, false, held);
        lines.add("&7主手 RPGItem: &f" + item.id());
        lines.add("&a当前 MagicDamage: &f" + format(Math.max(0, stats.effectiveMagicDamage())));
        String[] triggers = {"F", "SF", "R", "SR", "L", "SL", "Q", "SQ"};
        FSkillConfig[] configs = {item.fSkill(), item.sfSkill(), item.rSkill(), item.srSkill(),
                item.lSkill(), item.slSkill(), item.qSkill(), item.sqSkill()};
        boolean found = false;
        for (int index = 0; index < triggers.length; index++) {
            FSkillConfig config = configs[index];
            MagicCircleSkill circle = config == null ? null : config.magicCircle();
            if (circle != null) {
                found = true;
                lines.add("&6---- " + triggers[index] + " 键位 法阵 magic-circle ----");
                lines.add("&7技能是否执行 enabled(): " + (circle.enabled() ? "&a是" : "&c否（法阵不出现，也不会伤害）"));
                lines.add("&7distance/radius/height: &f" + format(circle.distance()) + " / " + format(circle.radius()) + " / " + format(circle.height()));
                lines.add("&7duration/interval/burst-count: &f" + format(circle.durationSeconds()) + "s / " + format(circle.intervalSeconds()) + "s / " + circle.burstCount());
                // ENEMY rejects players, animals, villagers and other passive mobs. Iron golems and
                // plugin training dummies are NOT friendly, so they are valid ENEMY targets.
                lines.add("&7target/include-self: &f" + circle.target() + " / " + circle.includeSelf()
                        + " &8(ENEMY 排除玩家/动物/村民等被动生物；铁傀儡与训练假人算敌人)");
                lines.add("&7use-armor-stand/center-material: &f" + circle.useArmorStand() + " / " + circle.centerMaterial());
                DamageSkill damage = circle.damage();
                if (damage == null) {
                    lines.add("&cdamage 未解析：法阵没有伤害配置"
                            + (circle.buffs().isEmpty() ? "" : " &7(只有 buffs，属于治疗/辅助法阵)"));
                } else {
                    lines.add("&7damage/weapon-percent: &f" + format(damage.damage()) + " / " + format(damage.weaponPercent()) + "%");
                    lines.add("&7damage-type/sources: &f" + damage.damageType() + " / " + damage.weaponPercentSources());
                    lines.add("&7can-crit: &f" + damage.canCrit());
                    Map<String, Double> raw = skillDamageByType(player, player, stats, damage);
                    lines.add("&a每次喷发原始伤害（以自身作为参照目标）: "
                            + (raw.isEmpty() ? "&c0，不会造成任何伤害" : "&f" + raw));
                }
            }
            // Both decoration skills are reported here rather than in a second command: the question
            // they answer is the same one ("why does this item's visual skill do nothing"), and the
            // per-trigger loop is already walking every FSkillConfig.
            GateOfBabylonSkill gate = config == null ? null : config.gateOfBabylon();
            if (gate != null) {
                found = true;
                lines.add("&6---- " + triggers[index] + " 键位 王之宝库 gate-of-babylon ----");
                lines.add("&7技能是否执行 enabled(): " + (gate.enabled() ? "&a是" : "&c否（武器不出现，也不会伤害）"));
                lines.add("&7weapon-count/材质种类: &f" + gate.weaponCount() + " / " + gate.weaponMaterials().size()
                        + " &8(单次施放上限 " + GateOfBabylonSkill.MAX_WEAPONS + "，全服残留上限 " + gateMaxLiveDisplays() + ")");
                lines.add("&7spawn-mode: &f" + gate.spawnMode() + " &8(behind 背后 / sky 天上 / mixed 两者随机)");
                lines.add("&7distance/spread-radius: &f" + format(gate.distance()) + " / " + format(gate.spreadRadius())
                        + " &8(落点区域跟随准星)");
                lines.add("&7背后生成 spawn-distance/spawn-height/spawn-fan-degrees: &f" + format(gate.spawnDistance()) + " / "
                        + format(gate.spawnHeight()) + " / " + format(gate.spawnFanDegrees()));
                lines.add("&7天上生成 sky-height/sky-offset: &f" + format(gate.skyHeight()) + " / " + format(gate.skyOffset()));
                lines.add("&7fall-speed/launch-delay-ticks/linger-seconds: &f" + format(gate.fallSpeed()) + " 格每 tick / "
                        + gate.launchDelayTicks() + " / " + format(gate.lingerSeconds()) + "s");
                lines.add("&7impact-radius/impact-height/max-hits-per-target: &f" + format(gate.impactRadius()) + " / "
                        + format(gate.impactHeight()) + " / " + gate.maxHitsPerTarget());
                // Printed because it silently changes what the volley even is: with this on, a weapon
                // that meets a target stops there instead of continuing to its crosshair landing.
                lines.add("&7飞行命中生物 collide-with-entities/collision-radius: &f"
                        + (gate.collideWithEntities() ? "&a开" : "&c关")
                        + " &f/ " + format(gate.collisionRadius()));
                lines.add("&7blade-tilt-degrees: &f" + format(gate.bladeTiltDegrees())
                        + " &8(剑刃摆正角；实测剑刃斜着不指落点时按 ±45 调整)");
                lines.add("&7粒子 轨迹/生成/悬停圆环/落地上升/落地爆炸: &f" + particleSummary(gate.trailParticle()) + " / "
                        + particleSummary(gate.spawnParticle()) + " / " + particleSummary(gate.rippleParticle()) + " / "
                        + particleSummary(gate.impactParticle()) + " / "
                        + particleSummary(gate.explosionParticle()) + " &8(爆炸 count@radius 越小越不挡视野)");
                lines.add("&7悬停圆环平面 ripple.plane: &f" + gate.ripplePlane()
                        + " &8(blade 垂直于剑刃 / horizontal 水平；画在剑柄处)");
                DamageSkill gateDamage = gate.damage();
                if (gateDamage == null) {
                    lines.add("&cdamage 未解析：宝库没有伤害配置，武器只会砸在地上不造成伤害");
                } else {
                    lines.add("&7damage/weapon-percent: &f" + format(gateDamage.damage()) + " / " + format(gateDamage.weaponPercent()) + "%");
                    lines.add("&7damage-type/sources: &f" + gateDamage.damageType() + " / " + gateDamage.weaponPercentSources());
                    lines.add("&7can-crit: &f" + gateDamage.canCrit());
                    Map<String, Double> raw = skillDamageByType(player, player, stats, gateDamage);
                    lines.add("&a每把武器落地原始伤害（以自身作为参照目标）: "
                            + (raw.isEmpty() ? "&c0，不会造成任何伤害" : "&f" + raw));
                }
            }
        }
        if (!found) lines.add("&c该物品的八个键位都没有 magic-circle / gate-of-babylon 配置");
        return lines;
    }

    private double sharpnessBonus(ItemStack weapon) {
        int sharpness = weapon.getEnchantmentLevel(Enchantment.SHARPNESS);
        return sharpness > 0 ? 0.5 * sharpness + 0.5 : 0;
    }

    /** 诊断用：把一组粒子压成「类型(count@radius)」，未启用时显示「关闭」。 */
    private String particleSummary(ParticleConfig config) {
        if (config == null || !config.enabled()) return "关闭";
        // The colour is included because it is a fallback, not a required key: a section that is
        // missing from an older yml still renders, in grey, and without the colour on screen there is
        // no way to tell that apart from a rendering fault.
        String color = config.block() == null || config.block().isBlank() ? "?" : config.block();
        return config.particle() + "(" + config.count() + "@" + format(config.radius()) + " " + color + ")";
    }

    private String format(double value) {
        return String.format(Locale.US, "%.2f", value);    }

    private double sourceDamage(Player player, LivingEntity target, RpgStats stats, String source, double weaponBase) {
        return switch (source) {
            case "ATTACK", "PHYSICAL" -> weaponBaseDamage(player, stats);
            case "MAGIC", "MAGIC_DAMAGE", "POTION", "POTION_DAMAGE" -> Math.max(0, stats.effectiveMagicDamage());
            case "BOW", "BOW_DAMAGE", "PROJECTILE" -> Math.max(0, stats.bowDamage() * (1 + Math.max(0, stats.bowDamagePercent()) / 100.0));
            case "TRUE", "TRUE_DAMAGE" -> Math.max(0, stats.trueDamage());
            case "ICE", "ICE_DAMAGE" -> elementSourceDamage(stats.iceDamage(), stats.iceDamagePercent(), weaponBase);
            case "FIRE", "FIRE_DAMAGE" -> elementSourceDamage(stats.fireDamage(), stats.fireDamagePercent(), weaponBase);
            case "LIGHT", "LIGHT_DAMAGE" -> elementSourceDamage(stats.lightDamage(), stats.lightDamagePercent(), weaponBase);
            case "DARK", "DARK_DAMAGE" -> elementSourceDamage(stats.darkDamage(), stats.darkDamagePercent(), weaponBase);
            case "PCT", "PCT_DAMAGE" -> maxHealth(player) * Math.max(0, stats.pctDamage()) / 100.0;
            case "PST", "PST_DAMAGE" -> maxHealth(target) * Math.max(0, stats.pstDamage()) / 100.0;
            default -> 0;
        };
    }

    private double elementSourceDamage(double flat, double percent, double weaponBase) {
        return Math.max(0, flat) + Math.max(0, percent) / 100.0 * Math.max(0, weaponBase);
    }

    private String sourceDamageType(String source) {
        return switch (source) {
            case "MAGIC", "MAGIC_DAMAGE" -> "MAGIC";
            case "POTION", "POTION_DAMAGE" -> "POTION";
            case "BOW", "BOW_DAMAGE", "PROJECTILE" -> "BOW";
            case "TRUE", "TRUE_DAMAGE" -> "TRUE";
            case "ICE", "ICE_DAMAGE" -> "ICE";
            case "FIRE", "FIRE_DAMAGE" -> "FIRE";
            case "LIGHT", "LIGHT_DAMAGE" -> "LIGHT";
            case "DARK", "DARK_DAMAGE" -> "DARK";
            default -> "PHYSICAL";
        };
    }

    private String normalizeDamageType(String configuredType) {
        if (configuredType == null) return "PHYSICAL";
        return sourceDamageType(configuredType.toUpperCase(Locale.ROOT).replace("-", "_").replace(" ", "_"));
    }

    private void addSkillDamage(Map<String, Double> damageByType, String type, double damage) {
        if (damage > 0) damageByType.merge(type, damage, Double::sum);
    }

    private String formatSkillDamage(String configuredType, long damage) {
        String type = configuredType == null ? "PHYSICAL" : configuredType.toUpperCase(Locale.ROOT);
        return switch (type) {
            case "MAGIC", "POTION" -> damageDisplay.format("damage-display.magic-format", "damage-display.magic-color", "✡❤-{damage}", damage);
            case "TRUE" -> damageDisplay.format("damage-display.true-format", "damage-display.true-color", "☠❤-{damage}", damage);
            case "BOW", "PROJECTILE" -> damageDisplay.format("damage-display.bow-format", "damage-display.bow-color", "🏹❤-{damage}", damage);
            case "ICE" -> damageDisplay.format("damage-display.ice-format", "damage-display.ice-color", "🧊❤-{damage}", damage);
            case "FIRE" -> damageDisplay.format("damage-display.fire-format", "damage-display.fire-color", "🔥❤-{damage}", damage);
            case "LIGHT" -> damageDisplay.format("damage-display.light-format", "damage-display.light-color", "☀❤-{damage}", damage);
            case "DARK" -> damageDisplay.format("damage-display.dark-format", "damage-display.dark-color", "🌘❤-{damage}", damage);
            default -> damageDisplay.format("damage-display.physical-format", "damage-display.physical-color", "🗡❤-{damage}", damage);
        };
    }

    private String formatSkillDamage(String configuredType, long damage, boolean critical, String skillType) {
        if (!critical) return formatSkillDamage(configuredType, damage);
        String family = skillCritFamily(skillType);
        return switch (family) {
            case "MAGIC" -> damageDisplay.format("damage-display.magic-crit-format", "damage-display.magic-crit-color", "\uD83D\uDD2F\u2764-{damage}", damage);
            case "BOW" -> damageDisplay.format("damage-display.bow-crit-format", "damage-display.bow-crit-color", "\uD83C\uDFF9\u2B50\u2764-{damage}", damage);
            default -> damageDisplay.format("damage-display.physical-crit-format", "damage-display.physical-crit-color", "\u2694\u2764-{damage}", damage);
        };
    }

    private double defendSkillDamage(double damage, RpgStats defender, double armorPen, String configuredType) {
        String type = configuredType == null ? "PHYSICAL" : configuredType.toUpperCase(Locale.ROOT);
        if (type.equals("TRUE")) return Math.max(0, damage);
        double defended = applyArmorLayer(damage, defender.envArmor(), defender.envArmorPercent(), 0);
        defended = switch (type) {
            case "MAGIC", "POTION" -> applyArmorLayer(defended, defender.magicArmor(), defender.magicArmorPercent(), armorPen);
            case "BOW", "PROJECTILE" -> applyArmorLayer(defended, defender.bowArmor(), defender.bowArmorPercent(), armorPen);
            case "ICE" -> applyArmorLayer(defended, defender.iceArmor(), defender.iceArmorPercent(), armorPen);
            case "FIRE" -> applyArmorLayer(defended, defender.fireArmor(), defender.fireArmorPercent(), armorPen);
            case "LIGHT" -> applyArmorLayer(defended, defender.lightArmor(), defender.lightArmorPercent(), armorPen);
            case "DARK" -> applyArmorLayer(defended, defender.darkArmor(), defender.darkArmorPercent(), armorPen);
            default -> applyArmorLayer(defended, defender.armor(), defender.armorPercent(), armorPen);
        };
        return defended * (1 - Math.min(100, defender.resistance()) / 100.0);
    }

    private boolean isMagicSkillType(String configuredType) {
        String type = configuredType == null ? "" : configuredType.toUpperCase(Locale.ROOT);
        return type.equals("MAGIC") || type.equals("POTION");
    }

    private boolean isSkillCritEnabled(String configuredType) {
        return isMagicSkillType(configuredType)
                ? plugin.isFeatureEnabled("magic-crit")
                : plugin.isFeatureEnabled("physical-crit");
    }

    /**
     * Whether a crit family may crit at all. Crit is rolled per family, not per damage type, and the
     * two families are the physical one (PHYSICAL / BOW) and the magic one (MAGIC / POTION).
     * {@code fskill.crit-excluded-damage-types} cancels a family's crit. Because a family shares one
     * roll, the entry must name the family's primary type: write {@code PHYSICAL} to cancel the
     * physical family (which also covers bow damage) and {@code MAGIC} to cancel the magic family
     * (which also covers potion damage). Naming an element has no effect, because elemental damage
     * never rolls its own crit in either the skill path or the normal attack path.
     */
    private boolean isSkillCritEligible(String configuredType) {
        String type = normalizeDamageType(configuredType);
        for (String excluded : plugin.getConfig().getStringList("fskill.crit-excluded-damage-types")) {
            if (normalizeDamageType(excluded).equals(type)) return false;
        }
        return true;
    }

    /**
     * Warns at startup about {@code fskill.crit-excluded-damage-types} entries that cannot take
     * effect, so a server owner does not silently ship a no-op config. Only the two family names do
     * anything - {@code PHYSICAL} (covers PHYSICAL and BOW) and {@code MAGIC} (covers MAGIC and
     * POTION) - and every other entry is reported together with the name that should be written
     * instead. Without this the only symptom is that crit keeps happening, with nothing in the log
     * to explain why.
     */
    public void validateCritExclusionConfig() {
        for (String raw : plugin.getConfig().getStringList("fskill.crit-excluded-damage-types")) {
            if (raw == null || raw.isBlank()) continue;
            String type = normalizeDamageType(raw);
            if (type.equals("PHYSICAL") || type.equals("MAGIC")) continue;
            String suggestion = switch (type) {
                case "BOW", "PROJECTILE" -> "PHYSICAL";
                case "POTION" -> "MAGIC";
                default -> "";
            };
            if (suggestion.isEmpty()) {
                plugin.getLogger().warning("fskill.crit-excluded-damage-types 里的 \"" + raw + "\" 不会生效："
                        + type + " 伤害不单独参与暴击，元素与真实伤害只会从翻倍后的基数按百分比放大。"
                        + "只有 PHYSICAL（物理族，含弓）与 MAGIC（魔法族，含药水）可以取消暴击。");
            } else {
                plugin.getLogger().warning("fskill.crit-excluded-damage-types 里的 \"" + raw + "\" 不会生效："
                        + "暴击按族结算，请改写成 " + suggestion + "（" + type + " 与 " + suggestion
                        + " 共用同一次暴击判定，写 " + suggestion + " 会同时取消两者）。");
            }
        }
    }

    private String skillCritFamily(String configuredType) {
        if (isMagicSkillType(configuredType)) return "MAGIC";
        String type = configuredType == null ? "" : configuredType.toUpperCase(Locale.ROOT);
        return type.equals("BOW") || type.equals("PROJECTILE") ? "BOW" : "PHYSICAL";
    }

    private CriticalRoll rollCritical(double critChance, double critDamage, double critArmor, double critArmorPercent, double armorPen) {
        double overflowCritDamage = Math.max(0, critChance - 100) * 10;
        double cappedCritChance = Math.min(100, Math.max(0, critChance));
        boolean critical = cappedCritChance > 0 && ThreadLocalRandom.current().nextDouble(100) < cappedCritChance;
        double damage = critical ? applyArmorLayer(critDamage + overflowCritDamage, critArmor, critArmorPercent, armorPen) : 0;
        return new CriticalRoll(critical, damage);
    }

    /**
     * The <b>unmitigated</b> crit damage a family roll contributes to the element base. Melee uses the
     * same value ({@code rawCriticalDamage}) rather than {@link CriticalRoll#damage()}, which has
     * already been reduced by the defender's crit armor - feeding the mitigated number into the base
     * would make elemental scaling depend on crit armor twice.
     */
    private double rawSkillCritDamage(RpgStats stats, CriticalRoll roll) {
        return roll.critical() ? Math.max(0, stats.critDamage() + Math.max(0, stats.critChance() - 100) * 10) : 0;
    }

    private record CriticalRoll(boolean critical, double damage) {
        private static CriticalRoll none() { return new CriticalRoll(false, 0); }
    }

    private void playSound(Location location, SoundConfig config) {
        if (config == null || !config.enabled()) return;
        try {
            String soundName = config.sound().toUpperCase(Locale.ROOT);
            if (soundName.equals("ENTITY_TNT_EXPLODE")) soundName = "ENTITY_GENERIC_EXPLODE";
            float pitch = Float.isFinite(config.pitch()) ? config.pitch() : 1.0F;
            location.getWorld().playSound(location, Sound.valueOf(soundName), config.volume(), pitch);
        } catch (IllegalArgumentException ignored) {
            plugin.getLogger().warning("Invalid skill sound: " + config.sound());
        }
    }

    private double attackCooldownSeconds(double attackSpeed) {
        return plugin.getAttackCooldownManager().cooldownMillis(attackSpeed) / 1000.0;
    }

    private double maxHealth(LivingEntity entity) {
        AttributeInstance attribute = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        return attribute == null ? 20 : attribute.getValue();
    }

    private double applyArmorLayer(double damage, double flatArmor, double armorPercent, double armorPen) {
        double penetratedFlat = Math.max(0, flatArmor * (1 - armorPen));
        double penetratedPercent = Math.max(0, armorPercent * (1 - armorPen));
        double afterFlat = Math.max(0, damage - penetratedFlat);
        return afterFlat * (1 - Math.min(100, penetratedPercent) / 100.0);
    }

    private boolean applyStrictAttributedDamage(LivingEntity target, LivingEntity attacker, long damage) {
        if (plugin.getDummyManager().absorbDamage(target, damage)) return true;
        if (damage <= 0 || target.isDead()) return false;
        target.setNoDamageTicks(0);
        target.setLastDamage(0);
        target.setMetadata(SKILL_DAMAGE_METADATA, new FixedMetadataValue(plugin, true));
        try {
            target.damage(damage, attacker);
        } finally {
            target.removeMetadata(SKILL_DAMAGE_METADATA, plugin);
        }
        return true;
    }

    private boolean messagesEnabled(String trigger) {
        return plugin.getConfig().getBoolean("fskill." + trigger.toLowerCase(Locale.ROOT) + "skill-messages-enabled", true);
    }

    private boolean messagesEnabled(String trigger, FSkillConfig config) {
        return config.messagesEnabled() && messagesEnabled(trigger);
    }

    /**
     * Cooldown feedback ("still cooling down", "global cooldown", "already casting").
     * The config switch is a server-wide kill switch; when it is on, the player's own toggle in
     * the skill panel decides. This is separate from the ready notice so a player can silence the
     * spam while keeping the one message that says the skill is usable again.
     */
    private boolean cooldownMessagesEnabled(Player player, String trigger) {
        return plugin.getConfig().getBoolean("fskill.cooldown-message-enabled", true)
                && plugin.getSkillPanelManager().isCooldownMessagesEnabled(player, trigger);
    }

    /** The "skill is ready again" notice; same config-then-panel precedence as above. */
    private boolean readyMessagesEnabled(Player player, String trigger) {
        return plugin.getConfig().getBoolean("fskill.ready-message-enabled", true)
                && plugin.getSkillPanelManager().isReadyMessagesEnabled(player, trigger);
    }

    /**
     * The "released the skill" notice. Same config-then-panel precedence as the two switches above:
     * the config entry is the server-wide kill switch, and when it is on the player's per-key toggle
     * in the skill panel decides.
     */
    private boolean activationMessagesEnabled(Player player, String trigger) {
        return plugin.getConfig().getBoolean("fskill.activation-message-enabled", true)
                && plugin.getSkillPanelManager().isActivationMessagesEnabled(player, trigger);
    }

    private String globalCooldownMessage(String trigger, RpgItem item, FSkillConfig config) {
        String configured = plugin.getConfig().getString("fskill.global-cooldown-message",
                "&e{skill}同类型全局冷却: &f{seconds}秒");
        return configured.replace("{skill}", config.cooldownName().isBlank()
                        ? item.displayName() + trigger + "技能" : config.cooldownName())
                .replace("{item}", item.displayName())
                .replace("{trigger}", trigger);
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private void repeat(double durationSeconds, double intervalSeconds, Runnable runnable) {
        long intervalTicks = Math.max(1L, Math.round(intervalSeconds * 20));
        long maxRuns = Math.max(1L, Math.round(durationSeconds * 20 / intervalTicks));
        new BukkitRunnable() {
            long runs = 0;
            @Override
            public void run() {
                runnable.run();
                runs++;
                if (runs >= maxRuns) cancel();
            }
        }.runTaskTimer(plugin, 0L, intervalTicks);
    }
}
