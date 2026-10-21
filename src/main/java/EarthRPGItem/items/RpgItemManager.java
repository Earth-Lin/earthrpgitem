package EarthRPGItem.items;

import EarthRPGItem.EarthRPGItem;
import EarthRPGItem.collection.CollectionStats;
import EarthRPGItem.skills.EarthExpBridge;
import EarthRPGItem.util.Texts;
import org.bukkit.GameMode;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.Bukkit;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.scheduler.BukkitTask;

public class RpgItemManager {

    private static final Pattern LORE_ATTRIBUTE_PATTERN = Pattern.compile("^\\s*\\+?([A-Za-z][A-Za-z0-9/%]*)\\s*(?::|=)?\\s*<?\\s*([+-]?\\d+(?:\\.\\d+)?)(?:\\s*-\\s*([+-]?\\d+(?:\\.\\d+)?))?\\s*(%)?\\s*>?\\s*$");
    /**
     * Shape of the generated description. Bump it whenever the lore rendering changes so that
     * items already in the world are re-created once and show the new lines.
     * <p>
     * 2 added the vanilla-attribute and buff lines.
     * 3 added the enhancement level line and folded the enhancement bonus into the attribute values.
     */
    private static final int LORE_FORMAT_VERSION = 3;
    private static final Pattern RANDOM_RANGE_PATTERN = Pattern.compile("^\\s*([+-]?\\d+)\\s*-\\s*([+-]?\\d+)\\s*(%)?\\s*$");
    private static final Set<String> PERCENT_BASE_ATTRIBUTES = Set.of(
            "attack", "armor", "lifesteal", "magicdamage", "envarmor", "magicarmor",
            "bowarmor", "critarmor", "assdamage", "icedamage", "firedamage", "lightdamage", "darkdamage",
            "icearmor", "firearmor", "lightarmor", "darkarmor"
    );
    private static final Set<String> SUPPORTED_LORE_ATTRIBUTES = Set.of(
            "attack", "attackpercent", "armor", "armorpercent", "critchance", "critdamage", "attackspeed",
            "health", "vitality", "recovery", "recoverypercent", "lifesteal", "lifestealpercent", "magicdamage", "magicdamagepercent",
            "bowdamage", "bowdamagepercent", "exdraw", "fix", "exdur", "envarmor", "envarmorpercent", "magicarmor", "magicarmorpercent",
            "resistance", "bowarmor", "bowarmorpercent", "critarmor", "critarmorpercent", "evasion", "armorpen",
            "truedamage", "backstrikedamage", "assdamage", "assdamagepercent", "pctdamage", "pstdamage", "attackrange",
            "parry", "counterattack", "dodge", "block", "determination", "cad",
            "icedamage", "icedamagepercent", "firedamage", "firedamagepercent", "lightdamage", "lightdamagepercent",
            "darkdamage", "darkdamagepercent", "icearmor", "icearmorpercent", "firearmor", "firearmorpercent",
            "lightarmor", "lightarmorpercent", "darkarmor", "darkarmorpercent"
    );

    private final EarthRPGItem plugin;
    private final NamespacedKey idKey;
    private final NamespacedKey typeKey;
    private final NamespacedKey soulboundKey;
    private final NamespacedKey vanillaSpeedKey;
    private final NamespacedKey exDurCurrentKey;
    private final NamespacedKey exDurMaxKey;
    private final NamespacedKey itemVersionKey;
    private final NamespacedKey attributeSignatureKey;
    private final NamespacedKey randomAttributesKey;
    private final NamespacedKey enhancementKey;
    /** Which gem id sits in each socket of this stack, in slot order; empty entries are open slots. */
    private final NamespacedKey gemKey;
    private final Map<RpgItemType, List<RpgItem>> items = new EnumMap<>(RpgItemType.class);
    private final Map<RpgItemType, Map<String, List<RpgItem>>> itemGroups = new EnumMap<>(RpgItemType.class);
    private final Map<String, RpgItem> independentSkillCache = new HashMap<>();
    /** Lowercase skill id to its built item, so id lookups can resolve independent skills. */
    private final Map<String, RpgItem> independentSkillById = new HashMap<>();
    private final Map<String, YamlConfiguration> skillFileCache = new HashMap<>();
    private final Map<String, String> skillLoadErrors = new HashMap<>();
    private final Map<UUID, Long> levelMessageTimes = new HashMap<>();
    private final Map<UUID, Long> durabilityMessageTimes = new HashMap<>();
    /**
     * Per-tick memo of {@link #collectActiveStats(Player, boolean, boolean)} for the non-rerolling
     * settlement.
     * <p>
     * <b>Why</b>: settling a player costs one full pass over the whole kit (six equipment slots,
     * two passes over the armour array, accessory stats, external modifiers, and a lore parse per
     * non-RPG line). A single damage event used to trigger it for the attacker and again for the
     * defender, an AoE skill triggered it once per target per round, and the passive refresh task
     * triggered it for every online player once a second. Measured on paper that is thousands of
     * full-kit passes per second on a busy server, all on the main thread.
     * <p>
     * <b>Only the {@code rerollRandom == false} settlement is cached.</b> That variant is a pure
     * function of the player's kit, so repeating it inside the same tick cannot produce a different
     * answer. The {@code true} variant draws random attribute ranges again on every call, so
     * caching it would freeze a {@code "10-20"} roll for a tick and, worse, change when a roll
     * happens at all.
     * <p>
     * <b>Only the {@code notifyLevel == false} settlement is cached</b> for the same reason in the
     * other direction: that flag exists to emit durability/level notices, which are side effects
     * that must still run when a caller asks for them.
     */
    private final Map<UUID, CachedStats> statsCache = new HashMap<>();

    /** One memoised settlement, tagged with the tick it belongs to. */
    private record CachedStats(RpgStats stats, int tick) {
    }

    /**
     * Memoised {@link #attributeSignature(RpgItem)} results.
     * <p>
     * Keyed by the {@link RpgItem} record, which has value semantics and is immutable once the item
     * table has loaded. Dropped wholesale whenever either attribute table's revision changes, so
     * the entry can never outlive the numbers it was derived from.
     */
    private final Map<RpgItem, String> signatureCache = new HashMap<>();
    /** Revision pair the signature cache was built for; a mismatch clears the cache. */
    private String signatureEnhancementRevision = "";
    private String signatureGemstoneRevision = "";
    /**
     * External attribute modifiers, keyed by entity and then by source name, so several plugins —
     * or several features of one plugin — can each hold a modifier without overwriting each other.
     * The previous single slot per entity meant a second caller replaced the first, and the first
     * caller's modifier vanished the moment the second one expired.
     */
    private final Map<UUID, Map<String, AttributeModifierEntry>> attributeModifiers = new HashMap<>();
    /**
     * The pending expiry task per entity and source. Kept so a re-applied modifier replaces its
     * task instead of queueing another: an aura that re-applies every tick used to schedule a fresh
     * task per call, each holding its own closure until it eventually ran.
     */
    private final Map<UUID, Map<String, BukkitTask>> modifierExpiryTasks = new HashMap<>();
    /** Players whose permanent modifiers have already been read out of their persistent data. */
    private final Set<UUID> loadedPermanentModifiers = new HashSet<>();
    /** Attribute modifier keys handed to entities by the API, so they can be removed again. */
    private final Map<UUID, Map<String, List<NamespacedKey>>> vanillaModifierKeys = new HashMap<>();
    private final NamespacedKey permanentModifiersKey;
    private Set<String> exampleGenerationFolders = Set.of();

    /**
     * One external modifier. {@code expiryMillis == Long.MAX_VALUE} marks a permanent modifier,
     * which is also mirrored into the player's persistent data so it survives a restart.
     */
    private record AttributeModifierEntry(RpgStats stats, long expiryMillis) {
        static AttributeModifierEntry permanent(RpgStats stats) { return new AttributeModifierEntry(stats, Long.MAX_VALUE); }
        boolean permanent() { return expiryMillis == Long.MAX_VALUE; }
        boolean expired(long now) { return !permanent() && now >= expiryMillis; }
    }

    public RpgItemManager(EarthRPGItem plugin) {
        this.plugin = plugin;
        this.idKey = new NamespacedKey(plugin, "rpgitem_id");
        this.typeKey = new NamespacedKey(plugin, "rpgitem_type");
        this.soulboundKey = new NamespacedKey(plugin, "soulbound_owner");
        this.vanillaSpeedKey = new NamespacedKey(plugin, "max_vanilla_attack_speed");
        this.exDurCurrentKey = new NamespacedKey(plugin, "exdur_current");
        this.exDurMaxKey = new NamespacedKey(plugin, "exdur_max");
        this.itemVersionKey = new NamespacedKey(plugin, "item_version");
        this.attributeSignatureKey = new NamespacedKey(plugin, "attribute_signature");
        this.randomAttributesKey = new NamespacedKey(plugin, "random_attributes");
        this.enhancementKey = new NamespacedKey(plugin, "enhance_level");
        this.gemKey = new NamespacedKey(plugin, "gem_sockets");
        this.permanentModifiersKey = new NamespacedKey(plugin, "api_permanent_modifiers");
    }

    public void initialize() {
        createDirectoriesAndExamples();
        reloadItems();
    }

    public void reloadItems() {
        items.clear();
        itemGroups.clear();
        independentSkillCache.clear();
        independentSkillById.clear();
        skillFileCache.clear();
        skillLoadErrors.clear();
        // Both caches are derived from the tables being replaced here, and the player settlements
        // hold the very RpgItem instances that are about to be dropped.
        signatureCache.clear();
        statsCache.clear();
        for (RpgItemType itemType : RpgItemType.values()) items.put(itemType, loadItems(itemType));
        // Items the active configuration *requires* — the enhancement stone and every declared gem —
        // are written after the first load rather than by the example generator, because that only
        // ever fills an empty folder. On an install that already has items, the required files would
        // otherwise never appear, and the feature would look unimplemented: the stone would never
        // count and every gem would refuse to socket.
        if (ensureRequiredItemFiles() > 0) {
            for (RpgItemType itemType : RpgItemType.values()) items.put(itemType, loadItems(itemType));
        }
        plugin.getLogger().info("Loaded " + getAllItemCount() + " RPG items.");
        warnIfEnhancementStoneMissing();
    }

    /**
     * Writes a config file for every item the current configuration requires but nothing defines.
     * <p>
     * Only ever creates a file that does not already exist, so an operator's own version of the stone
     * or a gem is never overwritten — this fills gaps, it does not own the content.
     *
     * @return how many files were written, so the caller knows whether a reload is needed
     */
    private int ensureRequiredItemFiles() {
        int written = 0;
        EnhancementManager enhancement = plugin.getEnhancementManager();
        if (enhancement != null && enhancement.enabled() && enhancement.consumesStone()) {
            String stoneId = enhancement.stoneItemId();
            if (stoneId != null && !stoneId.isBlank() && findById(stoneId) == null
                    && writeItemFileIfAbsent(stoneId, item(stoneId, "AMETHYST_SHARD", "&d&l强化石", ""))) {
                plugin.getLogger().info("enhancement.yml 指定的强化石 '" + stoneId
                        + "' 没有物品配置，已自动生成 Items/" + stoneId + ".txt（可自行修改外观）。");
                written++;
            }
        }
        GemstoneManager gems = plugin.getGemstoneManager();
        if (gems != null && plugin.isFeatureEnabled("gemstones")) {
            for (String gemId : gems.ids()) {
                if (findById(gemId) != null) continue;
                GemstoneManager.Gemstone gem = gems.get(gemId);
                String name = gem == null ? "&f" + gemId : gem.displayName();
                String material = gem == null ? "PAPER" : gem.material();
                if (writeItemFileIfAbsent(gemId, gem(gemId, material, name))) {
                    plugin.getLogger().info("gemstones.yml 声明的宝石 '" + gemId
                            + "' 没有物品配置，已自动生成 Items/" + gemId + ".txt（可自行修改外观）。");
                    written++;
                }
            }
        }
        return written;
    }

    /** Writes {@code Items/<id>.txt}, refusing when a config for that id already exists anywhere. */
    private boolean writeItemFileIfAbsent(String id, String content) {
        if (id.contains("/") || id.contains("\\") || id.contains("..")) return false;
        File folder = new File(plugin.getDataFolder(), RpgItemType.ITEMS.getFolderName());
        folder.mkdirs();
        File file = new File(folder, id + ".txt");
        if (file.exists()) return false;
        try {
            Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
            return true;
        } catch (IOException exception) {
            plugin.getLogger().warning("无法写入 Items/" + id + ".txt：" + exception.getMessage());
            return false;
        }
    }

    /**
     * Warns when {@code enhancement.yml} names a stone item that no config defines.
     * <p>
     * Left unchecked this misconfiguration is silent in the worst way: the stone never counts, so
     * every attempt reports "not enough materials" no matter how many the player is carrying, and
     * the operator goes looking for a bug in the enhancement logic instead of in the item id.
     */
    private void warnIfEnhancementStoneMissing() {
        EnhancementManager enhancement = plugin.getEnhancementManager();
        if (enhancement == null || !enhancement.enabled() || !enhancement.consumesStone()) return;
        String stoneId = enhancement.stoneItemId();
        if (findById(stoneId) != null) return;
        plugin.getLogger().warning("enhancement.yml 的 stone.item-id = '" + stoneId + "' 找不到对应的物品配置，"
                + "强化会一直提示材料不足。请在 Items/ 等目录里创建该物品，"
                + "或把 stone.item-id 留空以关闭材料消耗。");
    }

    public List<RpgItem> getItems(RpgItemType type) {
        return items.getOrDefault(type, Collections.emptyList());
    }

    public List<String> getItemGroups(RpgItemType type) {
        return itemGroups.getOrDefault(type, Collections.emptyMap()).keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public List<RpgItem> getItems(RpgItemType type, String group) {
        return itemGroups.getOrDefault(type, Collections.emptyMap()).getOrDefault(group, Collections.emptyList());
    }

    public List<RpgItem> getAllItems() {
        return items.values().stream().flatMap(List::stream).toList();
    }

    public RpgItem findById(String id) {
        if (id == null) return null;
        RpgItem registered = getAllItems().stream().filter(item -> item.id().equalsIgnoreCase(id)).findFirst().orElse(null);
        if (registered != null) return registered;
        // Independent skills (skills/*.yml) are built on demand and never enter the registry above,
        // so without this fallback they were invisible to every lookup by id.
        return independentSkillById.get(id.toLowerCase(Locale.ROOT));
    }

    public List<RpgItem> collectActiveItems(Player player, boolean notifyLevel) {
        if (!plugin.isFeatureEnabled("rpgitems")) return Collections.emptyList();
        updateEquippedItems(player);
        List<RpgItem> activeItems = new ArrayList<>();
        for (ItemStack itemStack : player.getInventory().getArmorContents()) addActiveItem(player, itemStack, activeItems, notifyLevel, false);
        addActiveItem(player, player.getInventory().getItemInMainHand(), activeItems, notifyLevel, false);
        addActiveItem(player, player.getInventory().getItemInOffHand(), activeItems, notifyLevel, true);
        return activeItems;
    }

    public RpgStats collectActiveStats(Player player, boolean notifyLevel) {
        return collectActiveStats(player, notifyLevel, rerollOnUse());
    }

    /**
     * @param rerollRandom when true, random attribute ranges are drawn again for this settlement.
     *                     The passive refresh task passes false, because it rewrites max health
     *                     every second from {@code stats.health()}: re-rolling there would make a
     *                     {@code Health: "10-20"} item drain the player's health once per second.
     *                     Combat and command settlements keep randomizing.
     */
    public RpgStats collectActiveStats(Player player, boolean notifyLevel, boolean rerollRandom) {
        // See statsCache: only the quiet, non-rerolling settlement is memoised, because only that
        // one is a pure function of the kit. Anything else must run every time.
        if (!notifyLevel && !rerollRandom) return collectActiveStatsCached(player);
        return collectActiveStatsUncached(player, notifyLevel, rerollRandom);
    }

    /**
     * Settles the quiet non-rerolling stat block, reusing this tick's result when it has one.
     * <p>
     * The cache is dropped on equip changes, on join and on quit (see
     * {@link #invalidateStatsCache(Player)}), so a hit landing in the same tick as an equipment
     * swap is never scored against the previous kit.
     */
    private RpgStats collectActiveStatsCached(Player player) {
        int currentTick = Bukkit.getCurrentTick();
        CachedStats cached = statsCache.get(player.getUniqueId());
        if (cached != null && cached.tick() == currentTick) return cached.stats();
        RpgStats stats = collectActiveStatsUncached(player, false, false);
        statsCache.put(player.getUniqueId(), new CachedStats(stats, currentTick));
        return stats;
    }

    /**
     * Drops this player's memoised settlement so the next call recomputes it.
     * <p>
     * Must be called whenever the kit can have changed — equipment swap, inventory click, join and
     * quit — otherwise a settlement from earlier in the same tick keeps being served after the
     * gear behind it was replaced.
     */
    public void invalidateStatsCache(Player player) {
        statsCache.remove(player.getUniqueId());
    }

    /** Drops every memoised settlement. Used when the item tables themselves are reloaded. */
    public void invalidateStatsCache() {
        statsCache.clear();
    }

    private RpgStats collectActiveStatsUncached(Player player, boolean notifyLevel, boolean rerollRandom) {
        updateEquippedItems(player);
        RpgStats stats = RpgStats.empty();
        for (ItemStack itemStack : player.getInventory().getArmorContents()) stats = addActiveStats(player, itemStack, stats, notifyLevel, false, rerollRandom);
        stats = addActiveStats(player, player.getInventory().getItemInMainHand(), stats, notifyLevel, false, rerollRandom);
        stats = addActiveStats(player, player.getInventory().getItemInOffHand(), stats, notifyLevel, true, rerollRandom);
        stats = stats.addAccessoryStats(collectEquipmentLoreStats(player.getInventory().getArmorContents(),
                player.getInventory().getItemInMainHand(), player.getInventory().getItemInOffHand()));
        stats = stats.addAccessoryStats(plugin.getAccessoryManager().collectStats(player));
        stats = stats.addAccessoryStats(collectAttributeModifiers(player));
        return filterDisabledStats(stats);
    }

    /**
     * Collects the player's active stats and additionally counts one stack that is no longer in the
     * player's hands: the weapon dropped to fire a Q/SQ skill.
     * <p>
     * The drop event removes the weapon from the hand before it fires, so the weapon's own RPG
     * attributes (Attack and the rest) would be missing and every weapon-percent skill would scale
     * from the bare-hand base instead. It is counted only when that stack is no longer in a hand,
     * so a weapon that is still held is never counted twice.
     */
    public RpgStats collectActiveStats(Player player, boolean notifyLevel, ItemStack castWeapon) {
        RpgStats stats = collectActiveStats(player, notifyLevel);
        if (castWeapon == null || castWeapon.getType().isAir()) return stats;
        // Only a trigger stack that is no longer in a hand is added. Anything still held was already
        // counted by the base collection above, and counting it again would double its Attack. That
        // covers the ordinary cast while holding a skill item with an empty off hand, where no
        // weapon is held but the trigger stack itself is counted.
        if (isHeld(player, castWeapon)) return stats;
        RpgItem item = findItem(castWeapon);
        if (item == null || item.type() == RpgItemType.ACCESSORY) return stats;
        if (!canUseItem(player, castWeapon, item, notifyLevel, false)) return stats;
        return filterDisabledStats(stats.add(item, resolvedRandomAttributes(castWeapon, item)));
    }

    /** True when this exact stack is in the player's main or off hand, so it is already counted. */
    private boolean isHeld(Player player, ItemStack stack) {
        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();
        return (main != null && main.isSimilar(stack)) || (off != null && off.isSimilar(stack));
    }

    // ---- External attribute modifiers ---------------------------------------------------------
    //
    // Other plugins add or remove attributes through these methods (see EarthRPGItem.api). Every
    // modifier is owned by a "source" name, so two callers never overwrite each other and one
    // caller's expiry never removes another's modifier. A duration of 0 or less means permanent:
    // for a player that is mirrored into their persistent data and survives a restart.

    /**
     * Reserved source name for the plugin's own skill-applied boosts. Rejected for external
     * callers, so a plugin can never overwrite or clear what a skill applied to itself.
     */
    public static final String INTERNAL_MODIFIER_SOURCE = "earthrpgitem";

    /** Source names are persisted inside a delimited string, so the character set is restricted. */
    private static final java.util.regex.Pattern MODIFIER_SOURCE_PATTERN = java.util.regex.Pattern.compile("[A-Za-z0-9_.:-]{1,64}");

    private static String normalizeModifierSource(String source) {
        if (source == null || !MODIFIER_SOURCE_PATTERN.matcher(source).matches()) {
            throw new IllegalArgumentException("Attribute modifier source must match [A-Za-z0-9_.:-]{1,64} but was: " + source);
        }
        String normalized = source.toLowerCase(Locale.ROOT);
        if (normalized.equals(INTERNAL_MODIFIER_SOURCE)) {
            throw new IllegalArgumentException("Attribute modifier source '" + INTERNAL_MODIFIER_SOURCE + "' is reserved for EarthRPGItem skills");
        }
        return normalized;
    }

    /** One source's modifier, or an empty block when that source holds nothing. */
    public RpgStats attributeModifier(LivingEntity entity, String source) {
        if (entity == null || source == null) return RpgStats.empty();
        ensurePermanentModifiersLoaded(entity);
        Map<String, AttributeModifierEntry> entries = attributeModifiers.get(entity.getUniqueId());
        if (entries == null) return RpgStats.empty();
        AttributeModifierEntry entry = entries.get(source.toLowerCase(Locale.ROOT));
        return entry == null ? RpgStats.empty() : entry.stats();
    }

    public boolean hasAttributeModifier(LivingEntity entity, String source) {
        return entity != null && source != null && attributeModifier(entity, source).hasAny();
    }

    /** Every source currently holding a modifier on this entity. */
    public Set<String> attributeModifierSources(LivingEntity entity) {
        if (entity == null) return Set.of();
        ensurePermanentModifiersLoaded(entity);
        Map<String, AttributeModifierEntry> entries = attributeModifiers.get(entity.getUniqueId());
        return entries == null ? Set.of() : Set.copyOf(entries.keySet());
    }

    /** Replaces this source's modifier. Idempotent, so an aura can call it every tick safely. */
    public void setAttributeModifier(LivingEntity entity, String source, RpgStats stats, double durationSeconds) {
        writeAttributeModifier(entity, source, stats, durationSeconds, false);
    }

    /** Adds to this source's existing modifier instead of replacing it. */
    public void addAttributeModifier(LivingEntity entity, String source, RpgStats stats, double durationSeconds) {
        writeAttributeModifier(entity, source, stats, durationSeconds, true);
    }

    private void writeAttributeModifier(LivingEntity entity, String source, RpgStats stats, double durationSeconds, boolean accumulate) {
        if (entity == null) return;
        String key = normalizeModifierSource(source);
        if (stats == null) stats = RpgStats.empty();
        ensurePermanentModifiersLoaded(entity);
        UUID id = entity.getUniqueId();
        Map<String, AttributeModifierEntry> entries = attributeModifiers.computeIfAbsent(id, ignored -> new HashMap<>());
        RpgStats applied = stats;
        if (accumulate) {
            AttributeModifierEntry existing = entries.get(key);
            if (existing != null) applied = existing.stats().addAccessoryStats(stats);
        }
        boolean permanent = durationSeconds <= 0;
        AttributeModifierEntry entry = permanent
                ? AttributeModifierEntry.permanent(applied)
                : new AttributeModifierEntry(applied, System.currentTimeMillis() + Math.max(1L, Math.round(durationSeconds * 1000)));
        if (applied.toMap().isEmpty()) entries.remove(key);
        else entries.put(key, entry);
        if (entity instanceof Player player) persistPermanentModifiers(player);
        if (!permanent) scheduleModifierExpiry(id, key, entry.expiryMillis());
    }

    public void removeAttributeModifier(LivingEntity entity, String source) {
        if (entity == null || source == null) return;
        // Rejected for the same reason a write is: an external plugin must not be able to strip the
        // boost a skill applied to itself.
        String key = normalizeModifierSource(source);
        UUID id = entity.getUniqueId();
        Map<String, AttributeModifierEntry> entries = attributeModifiers.get(id);
        if (entries == null || entries.remove(key) == null) return;
        if (entries.isEmpty()) attributeModifiers.remove(id);
        if (entity instanceof Player player) persistPermanentModifiers(player);
    }

    /**
     * Drops every external modifier on this entity, including permanent ones. The reserved internal
     * source is kept, so clearing cannot remove a skill's own boost.
     */
    public void clearAttributeModifiers(LivingEntity entity) {
        if (entity == null) return;
        UUID id = entity.getUniqueId();
        Map<String, AttributeModifierEntry> entries = attributeModifiers.get(id);
        if (entries != null) {
            entries.keySet().removeIf(key -> !key.equals(INTERNAL_MODIFIER_SOURCE));
            if (entries.isEmpty()) attributeModifiers.remove(id);
        }
        removeVanillaModifiers(entity);
        if (entity instanceof Player player) persistPermanentModifiers(player);
    }

    /**
     * Drops all in-memory modifier state for an entity. Used when a player leaves: a permanent
     * modifier is already mirrored into their persistent data and is re-read on the next join, and
     * keeping the memory copy as well would let the two drift apart and would leak an entry for
     * every player who never returns. Vanilla attribute modifiers are removed from the entity
     * outright, since those are not persisted anywhere.
     */
    public void forgetAttributeModifiers(LivingEntity entity) {
        if (entity == null) return;
        UUID id = entity.getUniqueId();
        attributeModifiers.remove(id);
        loadedPermanentModifiers.remove(id);
        Map<String, BukkitTask> pending = modifierExpiryTasks.remove(id);
        if (pending != null) for (BukkitTask task : pending.values()) task.cancel();
        removeVanillaModifiers(entity);
        vanillaModifierKeys.remove(id);
    }

    /** All modifiers on this entity summed into one block, for reading outside a stat settlement. */
    public RpgStats collectAttributeModifiers(LivingEntity entity) {
        if (entity == null) return RpgStats.empty();
        ensurePermanentModifiersLoaded(entity);
        UUID id = entity.getUniqueId();
        Map<String, AttributeModifierEntry> entries = attributeModifiers.get(id);
        if (entries == null) return RpgStats.empty();
        long now = System.currentTimeMillis();
        // Expired entries are pruned on read as well as by their expiry task, so an entity that is
        // never written to again — a mob, which has no quit event to clean it up — cannot leave an
        // entry behind forever.
        entries.entrySet().removeIf(entry -> entry.getValue().expired(now));
        if (entries.isEmpty()) {
            attributeModifiers.remove(id);
            return RpgStats.empty();
        }
        RpgStats stats = RpgStats.empty();
        for (AttributeModifierEntry entry : entries.values()) stats = stats.addAccessoryStats(entry.stats());
        return stats;
    }

    private void scheduleModifierExpiry(UUID id, String source, long expiry) {
        long delayTicks = Math.max(1L, (Math.max(1L, expiry - System.currentTimeMillis()) + 49L) / 50L);
        Map<String, BukkitTask> pending = modifierExpiryTasks.computeIfAbsent(id, ignored -> new HashMap<>());
        // A re-applied modifier pushes its own expiry out; replacing the pending task keeps at most
        // one scheduled task per entity and source.
        BukkitTask previous = pending.remove(source);
        if (previous != null) previous.cancel();
        pending.put(source, plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Map<String, BukkitTask> current = modifierExpiryTasks.get(id);
            if (current != null) {
                current.remove(source);
                if (current.isEmpty()) modifierExpiryTasks.remove(id);
            }
            Map<String, AttributeModifierEntry> entries = attributeModifiers.get(id);
            if (entries == null) return;
            AttributeModifierEntry entry = entries.get(source);
            // Only the entry that actually timed out is dropped, and only for its own source.
            if (entry == null || entry.permanent() || entry.expiryMillis() != expiry) return;
            if (entry.expired(System.currentTimeMillis())) {
                entries.remove(source);
                if (entries.isEmpty()) attributeModifiers.remove(id);
            } else {
                scheduleModifierExpiry(id, source, entry.expiryMillis());
            }
        }, delayTicks));
    }

    private void ensurePermanentModifiersLoaded(LivingEntity entity) {
        if (!(entity instanceof Player player)) return;
        UUID id = player.getUniqueId();
        if (!loadedPermanentModifiers.add(id)) return;
        String raw = player.getPersistentDataContainer().get(permanentModifiersKey, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) return;
        Map<String, AttributeModifierEntry> entries = attributeModifiers.computeIfAbsent(id, ignored -> new HashMap<>());
        // Format: source|key=value,key=value;source|key=value
        for (String block : raw.split(";")) {
            int separator = block.indexOf('|');
            if (separator <= 0) continue;
            String source = block.substring(0, separator);
            if (!MODIFIER_SOURCE_PATTERN.matcher(source).matches()) continue;
            Map<String, Double> values = new HashMap<>();
            for (String pair : block.substring(separator + 1).split(",")) {
                int equals = pair.indexOf('=');
                if (equals <= 0) continue;
                try { values.put(pair.substring(0, equals), Double.parseDouble(pair.substring(equals + 1))); }
                catch (NumberFormatException ignored) { }
            }
            if (values.isEmpty()) continue;
            // A live entry wins: it was set after the data was written, and overwriting it would
            // discard a modifier another plugin applied during this session.
            entries.putIfAbsent(source, AttributeModifierEntry.permanent(RpgStats.fromLore(values)));
        }
    }

    private void persistPermanentModifiers(Player player) {
        Map<String, AttributeModifierEntry> entries = attributeModifiers.get(player.getUniqueId());
        StringBuilder builder = new StringBuilder();
        if (entries != null) {
            for (Map.Entry<String, AttributeModifierEntry> entry : entries.entrySet()) {
                if (!entry.getValue().permanent()) continue;
                Map<String, Double> values = entry.getValue().stats().toMap();
                if (values.isEmpty()) continue;
                if (builder.length() > 0) builder.append(';');
                builder.append(entry.getKey()).append('|');
                boolean first = true;
                for (Map.Entry<String, Double> value : values.entrySet()) {
                    if (!first) builder.append(',');
                    builder.append(value.getKey()).append('=').append(value.getValue());
                    first = false;
                }
            }
        }
        if (builder.length() == 0) player.getPersistentDataContainer().remove(permanentModifiersKey);
        else player.getPersistentDataContainer().set(permanentModifiersKey, PersistentDataType.STRING, builder.toString());
    }

    // ---- Vanilla attribute modifiers ----------------------------------------------------------

    /**
     * Adds a vanilla entity attribute modifier owned by {@code source}, for attributes that have no
     * RPG stat equivalent (movement speed, jump strength, ...). Duration 0 or less is permanent
     * until {@link #removeVanillaModifiers(LivingEntity, String)} is called.
     */
    public void setVanillaModifier(LivingEntity entity, String source, String attributeName, double amount,
                                   AttributeModifier.Operation operation, double durationSeconds) {
        if (entity == null) return;
        String key = normalizeModifierSource(source);
        Attribute attribute = resolveVanillaAttribute(attributeName);
        if (attribute == null) throw new IllegalArgumentException("Unknown vanilla attribute: " + attributeName);
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance == null) throw new IllegalArgumentException("Entity " + entity.getType() + " has no attribute " + attributeName);
        removeVanillaModifiers(entity, key);
        AttributeModifier.Operation resolved = operation == null ? AttributeModifier.Operation.ADD_NUMBER : operation;
        NamespacedKey modifierKey = new NamespacedKey(plugin, "api_" + Integer.toHexString((key + ':' + attribute).hashCode()));
        instance.addModifier(new AttributeModifier(modifierKey, amount, resolved));
        vanillaModifierKeys.computeIfAbsent(entity.getUniqueId(), ignored -> new HashMap<>())
                .computeIfAbsent(key, ignored -> new ArrayList<>()).add(modifierKey);
        if (durationSeconds > 0) {
            long delayTicks = Math.max(1L, Math.round(durationSeconds * 20));
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> removeVanillaModifiers(entity, key), delayTicks);
        }
    }

    public void removeVanillaModifiers(LivingEntity entity, String source) {
        if (entity == null || source == null) return;
        Map<String, List<NamespacedKey>> bySource = vanillaModifierKeys.get(entity.getUniqueId());
        if (bySource == null) return;
        List<NamespacedKey> keys = bySource.remove(source.toLowerCase(Locale.ROOT));
        if (keys == null) return;
        for (Attribute attribute : Attribute.values()) {
            AttributeInstance instance = entity.getAttribute(attribute);
            if (instance == null) continue;
            for (NamespacedKey key : keys) instance.removeModifier(key);
        }
        if (bySource.isEmpty()) vanillaModifierKeys.remove(entity.getUniqueId());
    }

    public void removeVanillaModifiers(LivingEntity entity) {
        if (entity == null) return;
        Map<String, List<NamespacedKey>> bySource = vanillaModifierKeys.remove(entity.getUniqueId());
        if (bySource == null) return;
        for (List<NamespacedKey> keys : bySource.values()) {
            for (Attribute attribute : Attribute.values()) {
                AttributeInstance instance = entity.getAttribute(attribute);
                if (instance == null) continue;
                for (NamespacedKey key : keys) instance.removeModifier(key);
            }
        }
    }

    /**
     * Backwards-compatible entry point for the plugin's own skills. Routes to the reserved internal
     * source so external plugins cannot collide with, or clear, a skill's own boost.
     */
    public void applyTemporaryAttributeBoost(Player player, RpgStats stats, double durationSeconds) {
        applyTemporaryAttributeBoost((LivingEntity) player, stats, durationSeconds);
    }

    public void applyTemporaryAttributeBoost(LivingEntity entity, RpgStats stats, double durationSeconds) {
        applyTemporaryAttributeBoost(entity, stats, durationSeconds, false);
    }

    public void applyTemporaryAttributeBoost(LivingEntity entity, RpgStats stats, double durationSeconds, boolean stackable) {
        if (entity == null) return;
        UUID id = entity.getUniqueId();
        Map<String, AttributeModifierEntry> entries = attributeModifiers.computeIfAbsent(id, ignored -> new HashMap<>());
        AttributeModifierEntry existing = entries.get(INTERNAL_MODIFIER_SOURCE);
        RpgStats applied = stackable && existing != null ? existing.stats().addAccessoryStats(stats) : stats;
        AttributeModifierEntry entry = new AttributeModifierEntry(applied,
                System.currentTimeMillis() + Math.max(1L, Math.round(durationSeconds * 1000)));
        entries.put(INTERNAL_MODIFIER_SOURCE, entry);
        scheduleModifierExpiry(id, INTERNAL_MODIFIER_SOURCE, entry.expiryMillis());
    }

    public CollectionStats collectActiveCollectionStats(Player player, boolean notifyLevel) {
        updateEquippedItems(player);
        CollectionStats stats = CollectionStats.empty();
        for (ItemStack itemStack : player.getInventory().getArmorContents()) {
            stats = stats.add(activeCollectionStats(player, itemStack, notifyLevel, false));
        }
        stats = stats.add(activeCollectionStats(player, player.getInventory().getItemInMainHand(), notifyLevel, false));
        stats = stats.add(activeCollectionStats(player, player.getInventory().getItemInOffHand(), notifyLevel, true));
        return stats.add(plugin.getAccessoryManager().collectCollectionStats(player));
    }

    public CollectionStats collectItemCollectionStats(ItemStack itemStack, RpgItem item) {
        return item == null ? CollectionStats.empty() : CollectionStats.from(item, resolvedRandomAttributes(itemStack, item));
    }

    private CollectionStats activeCollectionStats(Player player, ItemStack itemStack, boolean notifyLevel, boolean offhand) {
        RpgItem item = findItem(itemStack);
        if (item == null || item.type() == RpgItemType.ACCESSORY || !canUseItem(player, itemStack, item, notifyLevel, offhand)) return CollectionStats.empty();
        return collectItemCollectionStats(itemStack, item);
    }


    public Map<String, RandomAttributeRange> collectConfiguredRandomRanges(Player player) {
        Map<String, int[]> totals = new HashMap<>();
        // Empty armor slots are null, and List.of rejects null elements, so collect them by hand.
        List<ItemStack> equipment = new ArrayList<>();
        for (ItemStack stack : player.getInventory().getArmorContents()) {
            if (stack != null && !stack.getType().isAir()) equipment.add(stack);
        }
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (mainHand != null && !mainHand.getType().isAir()) equipment.add(mainHand);
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (offHand != null && !offHand.getType().isAir()) equipment.add(offHand);
        for (ItemStack stack : equipment) {
            RpgItem item = findItem(stack);
            if (item == null || item.type() == RpgItemType.ACCESSORY) continue;
            for (Map.Entry<String, RandomAttributeRange> entry : item.randomAttributes().entrySet()) {
                int[] total = totals.computeIfAbsent(entry.getKey(), ignored -> new int[]{0, 0});
                total[0] += entry.getValue().minimum();
                total[1] += entry.getValue().maximum();
            }
        }
        Map<String, RandomAttributeRange> result = new HashMap<>();
        totals.forEach((key, total) -> result.put(key, new RandomAttributeRange(total[0], total[1])));
        return result;
    }

    public RpgStats collectActiveStats(LivingEntity entity, boolean notifyLevel) {
        if (entity instanceof Player player) return collectActiveStats(player, notifyLevel);
        if (!plugin.isFeatureEnabled("rpgitems")) return RpgStats.empty();
        EntityEquipment equipment = entity.getEquipment();
        if (equipment == null) return RpgStats.empty();
        RpgStats stats = RpgStats.empty();
        for (ItemStack itemStack : equipment.getArmorContents()) {
            RpgItem item = findItem(itemStack);
            if (item != null) stats = addWithEnhancement(stats, itemStack, item, resolvedRandomAttributes(itemStack, item));
        }
        RpgItem main = findItem(equipment.getItemInMainHand());
        if (main != null && main.type() != RpgItemType.ACCESSORY) {
            stats = addWithEnhancement(stats, equipment.getItemInMainHand(), main, resolvedRandomAttributes(equipment.getItemInMainHand(), main));
        }
        RpgItem off = findItem(equipment.getItemInOffHand());
        if (off != null && off.type() != RpgItemType.ACCESSORY && off.deputy()) {
            stats = addWithEnhancement(stats, equipment.getItemInOffHand(), off, resolvedRandomAttributes(equipment.getItemInOffHand(), off));
        }
        stats = stats.addAccessoryStats(collectEquipmentLoreStats(equipment.getArmorContents(),
                equipment.getItemInMainHand(), equipment.getItemInOffHand()));
        stats = stats.addAccessoryStats(collectAttributeModifiers(entity));
        return filterDisabledStats(stats);
    }

    public ItemStack createItem(RpgItem rpgItem) {
        return createItem(rpgItem, rollRandomAttributes(rpgItem), 0, List.of());
    }

    public ItemStack createItem(RpgItem rpgItem, Map<String, Integer> randomValues, int enhanceLevel) {
        return createItem(rpgItem, randomValues, enhanceLevel, List.of());
    }

    /**
     * Builds an item at a given enhancement level.
     * <p>
     * {@code randomValues} is passed in rather than rolled here so that an item can be rebuilt
     * without disturbing the random attributes it already rolled. Re-rolling on every enhancement
     * would silently change an item's other stats each time the player upgraded it.
     *
     * @param enhanceLevel stored on the item and used to look up the bonus in {@code enhancement.yml};
     *                     0 means "not enhanced" and skips both the bonus and the lore line
     * @param sockets      the gem id in each socket, in slot order; a blank entry is an open socket.
     *                     Passed in rather than read from the stack because the socket line and each
     *                     gem's contribution to the printed numbers are rendered here, so a rebuild
     *                     that dropped it would silently erase the player's gems from the display.
     */
    public ItemStack createItem(RpgItem rpgItem, Map<String, Integer> randomValues, int enhanceLevel, List<String> sockets) {
        Material material = Material.matchMaterial(rpgItem.material());
        if (material == null || material.isAir()) material = Material.STONE;
        ItemStack itemStack = new ItemStack(material);
        var meta = itemStack.getItemMeta();
        meta.displayName(Texts.item(rpgItem.displayName()));
        if (meta instanceof LeatherArmorMeta leatherArmorMeta) leatherArmorMeta.setColor(parseColor(rpgItem.leatherColor()));
        if (rpgItem.customModelData() > 0) meta.setCustomModelData(rpgItem.customModelData());
        EnhancementManager enhancement = plugin.getEnhancementManager();
        List<String> loreLines = new ArrayList<>();
        if (enhanceLevel > 0 && enhancement != null && enhancement.loreLevelLineEnabled()) {
            loreLines.add(enhancement.loreLevelLine(enhanceLevel));
        }
        // The socket strip sits right under the level line: both describe the item's *state* rather
        // than one of its attributes, so they read as a pair above the attribute box.
        List<String> socketIds = normalizeSockets(sockets, rpgItem.sockets());
        String socketLine = renderSocketLine(socketIds, rpgItem.sockets());
        if (socketLine != null) loreLines.add(socketLine);
        // The item's own value and the enhancement are printed separately — "Attack +286[+200]" —
        // so the bracket reads as "this much of it came from enhancing". Folding the two together
        // would leave the player unable to tell an intrinsically strong item from a heavily
        // enhanced one, which is the whole point of showing an enhancement level.
        // Gems get a second, green bracket beside each attribute they feed, and they are folded into
        // the *base* the enhancement scales — so a gem's contribution is itself enhanced, which is
        // what settlement does. The two must agree or the printed numbers would lie.
        GemDisplay gems = buildGemDisplay(socketIds);
        BonusDisplay bonus = enhanceLevel > 0 && enhancement != null && enhancement.showBonus()
                ? buildBonusDisplay(enhanceLevel, rpgItem, randomValues, enhancement, gems)
                : BonusDisplay.none();
        List<String> attributeLore = renderAttributeLore(rpgItem, randomValues, bonus, gems);
        loreLines.addAll(attributeLore);
        if (!attributeLore.isEmpty() && !rpgItem.lore().isEmpty()
                && plugin.getConfig().getBoolean("lore-templates.separator.enabled", true)) {
            List<String> separatorLines = plugin.getConfig().getStringList("lore-templates.separator.lines");
            if (separatorLines.isEmpty()) separatorLines = List.of("&8&m──────────────────────────────────────");
            loreLines.addAll(separatorLines);
        }
        loreLines.addAll(rpgItem.lore());
        addLore(loreLines, "fix", randomValues.getOrDefault("fix", (int) Math.round(rpgItem.fixPerSecond())), "&f+Fix/" + rpgItem.fixIntervalSeconds() + "s: ");
        int rolledExtraDurability = randomValues.getOrDefault("exdur", rpgItem.extraDurability());
        if (plugin.isFeatureEnabled("ex-dur") && rolledExtraDurability > 0) loreLines.add("&fExDur:" + rolledExtraDurability + "/" + rolledExtraDurability);
        if (plugin.isFeatureEnabled("break-limit") && rpgItem.breakLimit()) loreLines.add("&cBreakLimit");
        if (plugin.isFeatureEnabled("soulbound") && rpgItem.soulbound()) loreLines.add("&5Soulbound");
        meta.lore(loreLines.stream().map(Texts::item).toList());
        meta.setUnbreakable(rpgItem.unbreakable());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        if (rpgItem.hideEnchantments()) meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        if (rpgItem.unbreakable()) meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        for (Map.Entry<String, Integer> entry : rpgItem.enchantments().entrySet()) {
            Enchantment enchantment = findEnchantment(entry.getKey());
            if (enchantment != null) meta.addEnchant(enchantment, entry.getValue(), true);
        }
        if (!hasVanillaAttribute(rpgItem, Attribute.GENERIC_ATTACK_SPEED)) {
            meta.addAttributeModifier(Attribute.GENERIC_ATTACK_SPEED, new AttributeModifier(vanillaSpeedKey, 1024, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        }
        applyVanillaAttributes(meta, rpgItem, material);
        meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, rpgItem.id());
        meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, rpgItem.type().name());
        meta.getPersistentDataContainer().set(itemVersionKey, PersistentDataType.INTEGER, rpgItem.itemVersion());
        meta.getPersistentDataContainer().set(attributeSignatureKey, PersistentDataType.STRING, attributeSignature(rpgItem));
        meta.getPersistentDataContainer().set(randomAttributesKey, PersistentDataType.STRING, serializeRandomAttributes(randomValues));
        if (enhanceLevel > 0) {
            meta.getPersistentDataContainer().set(enhancementKey, PersistentDataType.INTEGER, enhanceLevel);
        }
        if (!socketIds.isEmpty()) {
            meta.getPersistentDataContainer().set(gemKey, PersistentDataType.STRING, serializeGems(socketIds));
        }
        int extraDurability = rolledExtraDurability;
        if (extraDurability > 0) {
            meta.getPersistentDataContainer().set(exDurCurrentKey, PersistentDataType.INTEGER, extraDurability);
            meta.getPersistentDataContainer().set(exDurMaxKey, PersistentDataType.INTEGER, extraDurability);
        }
        itemStack.setItemMeta(meta);
        return itemStack;
    }

    public RpgItem findActiveItem(Player player, org.bukkit.inventory.EquipmentSlot slot, boolean notifyLevel) {
        updateHeldItems(player);
        ItemStack itemStack = slot == org.bukkit.inventory.EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand();
        RpgItem rpgItem = findItem(itemStack);
        boolean offhand = slot == org.bukkit.inventory.EquipmentSlot.OFF_HAND;
        if (rpgItem == null || rpgItem.type() == RpgItemType.ACCESSORY || !canUseItem(player, itemStack, rpgItem, notifyLevel, offhand)) return null;
        return rpgItem;
    }

    public RpgItem findActiveMainHandItem(Player player, boolean notifyLevel) {
        updateHeldItems(player);
        ItemStack itemStack = player.getInventory().getItemInMainHand();
        RpgItem rpgItem = findItem(itemStack);
        if (rpgItem == null || rpgItem.type() == RpgItemType.ACCESSORY || !canUseItem(player, itemStack, rpgItem, notifyLevel, false)) return null;
        return rpgItem;
    }

    public boolean isRpgItem(ItemStack itemStack) {
        return findItem(itemStack) != null;
    }

    public boolean updateOutdatedItem(ItemStack itemStack) {
        if (!hasRpgIdentity(itemStack)) return false;
        var oldMeta = itemStack.getItemMeta();
        var container = oldMeta.getPersistentDataContainer();
        String id = container.get(idKey, PersistentDataType.STRING);
        String typeName = container.get(typeKey, PersistentDataType.STRING);
        if (id == null || typeName == null) return false;

        RpgItem registered;
        try {
            RpgItemType type = RpgItemType.valueOf(typeName);
            registered = getItems(type).stream().filter(item -> item.id().equals(id)).findFirst().orElse(null);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        if (registered == null || matchesRegisteredAttributes(itemStack, registered)) return false;

        int amount = itemStack.getAmount();
        int oldDamage = oldMeta instanceof Damageable damageable ? damageable.getDamage() : 0;
        Integer oldExtraCurrent = container.get(exDurCurrentKey, PersistentDataType.INTEGER);
        Integer oldExtraMax = container.get(exDurMaxKey, PersistentDataType.INTEGER);
        String soulboundOwner = container.get(soulboundKey, PersistentDataType.STRING);
        // The enhancement level is player-earned progress exactly like extra durability, so the
        // rebuild is done *at* that level rather than at 0. Without this, editing any item config
        // would silently reset every enhanced item in the world back to +0.
        Integer oldEnhanceLevel = container.get(enhancementKey, PersistentDataType.INTEGER);

        ItemStack replacement = createItem(registered, rollRandomAttributes(registered),
                oldEnhanceLevel == null ? 0 : oldEnhanceLevel, socketedGems(itemStack));
        replacement.setAmount(amount);
        var replacementMeta = replacement.getItemMeta();
        if (replacementMeta instanceof Damageable replacementDamageable && replacement.getType().getMaxDurability() > 0) {
            replacementDamageable.setDamage(Math.min(oldDamage, replacement.getType().getMaxDurability() - 1));
        }
        var replacementContainer = replacementMeta.getPersistentDataContainer();
        Integer newExtraMax = replacementContainer.get(exDurMaxKey, PersistentDataType.INTEGER);
        if (oldExtraCurrent != null && oldExtraMax != null && newExtraMax != null) {
            int consumed = Math.max(0, oldExtraMax - oldExtraCurrent);
            int newExtraCurrent = Math.max(0, newExtraMax - consumed);
            replacementContainer.set(exDurCurrentKey, PersistentDataType.INTEGER, newExtraCurrent);
        }
        if (soulboundOwner != null) replacementContainer.set(soulboundKey, PersistentDataType.STRING, soulboundOwner);
        replacement.setItemMeta(replacementMeta);

        itemStack.setType(replacement.getType());
        itemStack.setAmount(replacement.getAmount());
        itemStack.setItemMeta(replacement.getItemMeta());
        Integer current = itemStack.getItemMeta().getPersistentDataContainer().get(exDurCurrentKey, PersistentDataType.INTEGER);
        Integer maximum = itemStack.getItemMeta().getPersistentDataContainer().get(exDurMaxKey, PersistentDataType.INTEGER);
        if (current != null && maximum != null) updateExtraDurabilityLore(itemStack, current, maximum);
        return true;
    }

    public RpgStats collectLoreStats(ItemStack itemStack) {
        if (!plugin.isFeatureEnabled("lore-attributes")) return RpgStats.empty();
        return filterDisabledStats(parseLoreStats(itemStack));
    }

    private RpgStats collectEquipmentLoreStats(ItemStack[] armor, ItemStack mainHand, ItemStack offHand) {
        if (!plugin.isFeatureEnabled("lore-attributes")) return RpgStats.empty();
        RpgStats stats = RpgStats.empty();
        for (ItemStack itemStack : armor) stats = stats.addAccessoryStats(parseLoreStats(itemStack));
        stats = stats.addAccessoryStats(parseLoreStats(mainHand));
        if (hasLoreFlag(offHand, "Deputy")) stats = stats.addAccessoryStats(parseLoreStats(offHand));
        return stats;
    }

    private RpgStats parseLoreStats(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType().isAir() || hasRpgIdentity(itemStack) || !itemStack.hasItemMeta()) return RpgStats.empty();
        List<net.kyori.adventure.text.Component> lore = itemStack.getItemMeta().lore();
        if (lore == null || lore.isEmpty()) return RpgStats.empty();
        Map<String, Double> values = new HashMap<>();
        for (net.kyori.adventure.text.Component component : lore) {
            String line = stripLoreColors(Texts.plainText(component));
            Matcher matcher = LORE_ATTRIBUTE_PATTERN.matcher(line);
            if (!matcher.matches()) continue;
            String key = normalizeLoreAttribute(matcher.group(1), matcher.group(4) != null);
            if (!SUPPORTED_LORE_ATTRIBUTES.contains(key)) continue;
            try {
                double minimum = Double.parseDouble(matcher.group(2));
                double value = minimum;
                if (matcher.group(3) != null) {
                    double maximum = Double.parseDouble(matcher.group(3));
                    double low = Math.min(minimum, maximum);
                    double high = Math.max(minimum, maximum);
                    value = ThreadLocalRandom.current().nextInt((int) Math.ceil(low), (int) Math.floor(high) + 1);
                }
                values.merge(key, value, Double::sum);
            } catch (NumberFormatException ignored) {
            }
        }
        return RpgStats.fromLore(values);
    }

    private boolean hasLoreFlag(ItemStack itemStack, String flag) {
        if (itemStack == null || itemStack.getType().isAir() || hasRpgIdentity(itemStack) || !itemStack.hasItemMeta()) return false;
        List<net.kyori.adventure.text.Component> lore = itemStack.getItemMeta().lore();
        if (lore == null) return false;
        return lore.stream().map(Texts::plainText).map(RpgItemManager::stripLoreColors)
                .map(String::trim).anyMatch(line -> line.equalsIgnoreCase(flag) || line.equalsIgnoreCase("+" + flag));
    }

    private static String normalizeLoreAttribute(String rawKey, boolean percentSuffix) {
        String key = rawKey.toLowerCase().replace("/", "").replace("%", "percent");
        if (key.equals("fixs")) key = "fix";
        if (percentSuffix && PERCENT_BASE_ATTRIBUTES.contains(key)) key += "percent";
        return key;
    }

    private static String stripLoreColors(String text) {
        return text.replaceAll("(?i)[&§]x(?:[&§][0-9a-f]){6}", "")
                .replaceAll("(?i)&#[0-9a-f]{6}", "")
                .replaceAll("(?i)[&§][0-9a-fk-or]", "");
    }

    public boolean applyDurabilityLoss(Player player, ItemStack itemStack, int amount) {
        RpgItem rpgItem = findItem(itemStack);
        if (rpgItem == null || amount <= 0 || itemStack == null || !itemStack.hasItemMeta()) return false;
        initializeExtraDurability(itemStack, rpgItem);
        var meta = itemStack.getItemMeta();
        Integer current = meta.getPersistentDataContainer().get(exDurCurrentKey, PersistentDataType.INTEGER);
        Integer max = meta.getPersistentDataContainer().get(exDurMaxKey, PersistentDataType.INTEGER);
        if (plugin.isFeatureEnabled("ex-dur") && current != null && max != null && current > 0) {
            int next = Math.max(0, current - amount);
            meta.getPersistentDataContainer().set(exDurCurrentKey, PersistentDataType.INTEGER, next);
            itemStack.setItemMeta(meta);
            updateExtraDurabilityLore(itemStack, next, max);
            return true;
        }
        return protectVanillaDurability(player, itemStack, rpgItem, amount);
    }

    public void consumeAttackDurability(Player player, ItemStack itemStack, int amount) {
        if (player.getGameMode() == GameMode.CREATIVE || itemStack == null || itemStack.getType().getMaxDurability() <= 0) return;
        if (applyDurabilityLoss(player, itemStack, amount)) return;
        if (!shouldConsumeVanillaDurability(itemStack)) return;
        if (itemStack.getItemMeta() instanceof Damageable damageable) {
            int maxDamage = itemStack.getType().getMaxDurability();
            damageable.setDamage(Math.min(maxDamage - 1, damageable.getDamage() + amount));
            itemStack.setItemMeta(damageable);
        }
    }

    public void repairDurability(ItemStack itemStack, int amount) {
        RpgItem rpgItem = findItem(itemStack);
        if (rpgItem == null || amount <= 0 || itemStack == null || !itemStack.hasItemMeta()) return;
        initializeExtraDurability(itemStack, rpgItem);
        if (plugin.getConfig().getBoolean("durability.fix-repairs-extra-durability", false)) {
            var meta = itemStack.getItemMeta();
            Integer current = meta.getPersistentDataContainer().get(exDurCurrentKey, PersistentDataType.INTEGER);
            Integer max = meta.getPersistentDataContainer().get(exDurMaxKey, PersistentDataType.INTEGER);
            if (current != null && max != null && current < max) {
                int next = Math.min(max, current + amount);
                meta.getPersistentDataContainer().set(exDurCurrentKey, PersistentDataType.INTEGER, next);
                itemStack.setItemMeta(meta);
                updateExtraDurabilityLore(itemStack, next, max);
                return;
            }
        }
        if (itemStack.getItemMeta() instanceof Damageable damageable) {
            damageable.setDamage(Math.max(0, damageable.getDamage() - amount));
            itemStack.setItemMeta(damageable);
        }
    }

    public void restoreAllDurability(ItemStack itemStack) {
        RpgItem rpgItem = findItem(itemStack);
        if (rpgItem == null || itemStack == null || !itemStack.hasItemMeta()) return;
        var meta = itemStack.getItemMeta();
        if (plugin.isFeatureEnabled("ex-dur") && rpgItem.extraDurability() > 0) {
            meta.getPersistentDataContainer().set(exDurCurrentKey, PersistentDataType.INTEGER, rpgItem.extraDurability());
            meta.getPersistentDataContainer().set(exDurMaxKey, PersistentDataType.INTEGER, rpgItem.extraDurability());
            itemStack.setItemMeta(meta);
            updateExtraDurabilityLore(itemStack, rpgItem.extraDurability(), rpgItem.extraDurability());
            meta = itemStack.getItemMeta();
        }
        if (meta instanceof Damageable damageable) {
            damageable.setDamage(0);
            itemStack.setItemMeta(damageable);
        } else {
            itemStack.setItemMeta(meta);
        }
    }

    public void damageDurability(Player player, ItemStack itemStack, int amount) {
        consumeAttackDurability(player, itemStack, amount);
    }

    public RpgItem findItem(ItemStack itemStack) {
        if (itemStack == null || !itemStack.hasItemMeta()) return null;
        var container = itemStack.getItemMeta().getPersistentDataContainer();
        String id = container.get(idKey, PersistentDataType.STRING);
        String typeName = container.get(typeKey, PersistentDataType.STRING);
        if (id == null || typeName == null) return null;
        normalizeLoreFont(itemStack);
        try {
            RpgItemType type = RpgItemType.valueOf(typeName);
            RpgItem item = getItems(type).stream().filter(rpgItem -> rpgItem.id().equals(id)).findFirst()
                    .orElseGet(() -> findIndependentSkill(id, type));
            if (item == null || !matchesRegisteredAttributes(itemStack, item)) {
                disableOutdatedVanillaAttributes(itemStack);
                return null;
            }
            return item;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public RpgItem findItemByIdentity(ItemStack itemStack) {
        String id = identityId(itemStack);
        return id == null ? null : findById(id);
    }

    /**
     * Resolves an independent skill item (skills/*.yml) by id.
     * <p>
     * These items are built on demand from their file and only ever live in the independent cache,
     * so a registry lookup never finds them. That made a held skill item contribute nothing at all
     * to the player's stats: its own {@code attributes} were ignored and its weapon contribution
     * fell back to the bare material value, so a skill bound to the panel could not scale with the
     * item the way an RPGItem with an embedded skill does.
     */
    private RpgItem findIndependentSkill(String id, RpgItemType type) {
        if (id == null) return null;
        RpgItem item = independentSkillById.get(id.toLowerCase(Locale.ROOT));
        return item != null && item.type() == type ? item : null;
    }

    /**
     * Raw persistent id of an RPG item, read straight from the item without resolving it against
     * the item registry. Independent skill items are built on demand and never enter that
     * registry, so callers that only need the identity must not go through findById.
     */
    public String identityId(ItemStack itemStack) {
        if (!hasRpgIdentity(itemStack)) return null;
        return itemStack.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
    }

    /**
     * 手持物品注册表里声明的武器类型（{@code weapon-type} 字段）。
     * 物品不是 RPG 物品、或定义里没写这个字段时返回空串（调用方回退到材质推断）。
     *
     * @return 注册表填写的 weapon-type（trim 后），没有则返回 ""
     */
    public String registeredWeaponType(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType().isAir()) return "";
        String id = identityId(itemStack);
        RpgItem item = id == null ? null : findById(id);
        return item == null ? "" : item.weaponType();
    }

    public RpgStats collectItemStats(ItemStack itemStack, RpgItem item) {
        return item == null ? RpgStats.empty()
                : addWithEnhancement(RpgStats.empty(), itemStack, item, resolvedRandomAttributes(itemStack, item));
    }

    public double getRolledAttribute(ItemStack itemStack, String key, double fallback) {
        return readRandomAttributes(itemStack).getOrDefault(key.toLowerCase(Locale.ROOT), (int) Math.round(fallback));
    }

    // ---- Enhancement --------------------------------------------------------------------------

    /**
     * The enhancement level stored on {@code itemStack}, or 0 when it is not enhanced.
     * <p>
     * Only the level is persisted. Everything the level grants is derived from
     * {@code enhancement.yml} on demand, so rebalancing that file changes every item already in
     * the world instead of only items created afterwards.
     */
    public int enhanceLevel(ItemStack itemStack) {
        if (itemStack == null || !itemStack.hasItemMeta()) return 0;
        Integer level = itemStack.getItemMeta().getPersistentDataContainer().get(enhancementKey, PersistentDataType.INTEGER);
        return level == null ? 0 : Math.max(0, level);
    }

    /** The enhancement table's fingerprint, or a fixed marker while the table is not loaded yet. */
    private String enhancementRevision() {
        EnhancementManager enhancement = plugin.getEnhancementManager();
        return enhancement == null ? "unloaded" : enhancement.revision();
    }

    /** The gem table's fingerprint, or a fixed marker while it is not loaded yet. */
    private String gemstoneRevision() {
        GemstoneManager gems = plugin.getGemstoneManager();
        return gems == null ? "unloaded" : gems.revision();
    }

    /**
     * Scales every attribute of {@code base} by {@code percent}, producing the enhancement bonus.
     * <p>
     * Only keys {@link RpgStats#toMap()} produces are considered, which is what keeps the bonus and
     * the settlement in exact agreement: a line such as the level requirement is not an
     * {@link RpgStats} field, so it is absent here and therefore cannot be enhanced by accident.
     * <p>
     * Two rules shape each entry:
     * <ul>
     *   <li><b>Round to an integer, per attribute.</b> Rounding happens before the values are
     *       combined, so an item with two attributes of 1.5 shows {@code [+2][+2]} rather than
     *       {@code [+3]} — the number printed next to an attribute is that attribute's own bonus.</li>
     *   <li><b>A negative attribute is scaled by its magnitude and granted as a positive number.</b>
     *       An item with {@code AttackSpeed -60} at a {@code +20%} level becomes {@code -48} and
     *       reads {@code -60[+12]}. Enhancement is a reward, so it must never make a drawback worse.
     *       Combined with the exclusion list this also stops a penalty from being amplified into a
     *       soft ban on the item.</li>
     * </ul>
     */
    private static Map<String, Double> scaleAttributes(RpgStats base, double percent, EnhancementManager enhancement) {
        Map<String, Double> bonus = new LinkedHashMap<>();
        if (percent <= 0) return bonus;
        for (Map.Entry<String, Double> entry : base.toMap().entrySet()) {
            if (!enhancement.isEnhanced(entry.getKey())) continue;
            double value = entry.getValue();
            if (value == 0) continue;
            long scaled = Math.round(Math.abs(value) * percent);
            if (scaled != 0) bonus.put(entry.getKey(), (double) scaled);
        }
        return bonus;
    }

    /** The per-attribute bonus {@code item}'s {@code level} produces; empty when it grants nothing. */
    public Map<String, Double> enhancementBonusMap(int level, RpgItem item, Map<String, Integer> randomValues) {
        return enhancementBonusMap(level, item, randomValues, null);
    }

    /**
     * As above, with {@code gems} folded into the scaled base.
     * <p>
     * Gem values go in before the scaling rather than after, which is exactly what "a gem's stats are
     * enhanced like the item's own" means: a socketed {@code Attack 4} on a {@code +20%} item prints
     * {@code [+1]} beside the gem bracket and settles as 4.8, not as a flat, unenhanceable 4.
     */
    public Map<String, Double> enhancementBonusMap(int level, RpgItem item, Map<String, Integer> randomValues, RpgStats gems) {
        EnhancementManager enhancement = plugin.getEnhancementManager();
        if (enhancement == null || item == null || level <= 0) return Map.of();
        EnhancementManager.Level definition = enhancement.level(level);
        if (definition == null) return Map.of();
        RpgStats base = RpgStats.empty().add(item, randomValues);
        if (gems != null) base = base.addAccessoryStats(gems);
        return scaleAttributes(base, definition.percent(), enhancement);
    }

    /**
     * The bonus a specific stack would gain at {@code level}, using the random attributes it actually
     * carries. Exists so {@code /rpgitem enhance info} can print the real per-attribute numbers:
     * the percentage alone does not tell an operator whether a given item gains anything at all.
     */
    public Map<String, Double> enhancementBonusMap(ItemStack itemStack, RpgItem item, int level) {
        if (itemStack == null || item == null) return Map.of();
        return enhancementBonusMap(level, item, resolvedRandomAttributes(itemStack, item), gemStats(itemStack));
    }

    /**
     * The enhancement bonus as a stat block, or {@code null} when there is nothing to add.
     * <p>
     * Returned as {@link RpgStats} so it can be merged with
     * {@link RpgStats#addAccessoryStats(RpgStats)}, which adds field-wise. That is the whole reason
     * the bonus is computed separately instead of being folded into the item: an enhancement is
     * added on top of the item, whereas {@code rolled} <em>replaces</em> the configured value, which
     * is what a random attribute means.
     */
    public RpgStats enhancementBonus(int level, RpgItem item, Map<String, Integer> randomValues) {
        return enhancementBonus(level, item, randomValues, null);
    }

    /**
     * As above, with the item's socketed gems folded into the base the enhancement scales — which is
     * what makes a gem's contribution itself enhanceable.
     */
    public RpgStats enhancementBonus(int level, RpgItem item, Map<String, Integer> randomValues, RpgStats gems) {
        Map<String, Double> bonus = enhancementBonusMap(level, item, randomValues, gems);
        return bonus.isEmpty() ? null : RpgStats.fromLore(bonus);
    }

    /**
     * Adds one held or worn item's stats, including its enhancement, to {@code stats}.
     * <p>
     * Both equipment paths — the player's inventory and a generic {@link EntityEquipment} — funnel
     * through here so an enhanced item is worth exactly the same in a dummy's hands as in a
     * player's.
     */
    private RpgStats addWithEnhancement(RpgStats stats, ItemStack itemStack, RpgItem item, Map<String, Integer> rolled) {
        RpgStats result = stats.add(item, rolled);
        RpgStats gems = gemStats(itemStack);
        if (gems != null) result = result.addAccessoryStats(gems);
        RpgStats bonus = enhancementBonus(enhanceLevel(itemStack), item, rolled, gems);
        return bonus == null ? result : result.addAccessoryStats(bonus);
    }

    /**
     * The figures an enhanced item's lore needs: the bonus at the value actually rolled, plus the
     * bonus at the low and high ends of every random range.
     * <p>
     * The range ends are needed because an item with reroll-on-use shows {@code 10-20} and never a
     * single number; printing the bonus for one rolled value next to a range would understate the
     * ceiling and overstate the floor.
     */
    private record BonusDisplay(Map<String, Double> rolled, Map<String, Double> minimum,
                                Map<String, Double> maximum, String color) {

        static BonusDisplay none() {
            return new BonusDisplay(Map.of(), Map.of(), Map.of(), null);
        }

        /** The colour is null exactly when no bracket should be printed at all. */
        boolean disabled() {
            return color == null;
        }
    }

    private BonusDisplay buildBonusDisplay(int level, RpgItem item, Map<String, Integer> randomValues,
                                           EnhancementManager enhancement, GemDisplay gems) {
        EnhancementManager.Level definition = enhancement.level(level);
        if (definition == null || definition.percent() <= 0) return BonusDisplay.none();
        RpgStats gemStats = gems.disabled() ? null : RpgStats.fromLore(gems.values());
        Map<String, Double> rolled = enhancementBonusMap(level, item, randomValues, gemStats);
        Map<String, Double> minimum = rolled;
        Map<String, Double> maximum = rolled;
        if (rerollOnUse() && !item.randomAttributes().isEmpty()) {
            Map<String, Integer> lows = new HashMap<>();
            Map<String, Integer> highs = new HashMap<>();
            item.randomAttributes().forEach((key, range) -> {
                lows.put(key, range.minimum());
                highs.put(key, range.maximum());
            });
            minimum = enhancementBonusMap(level, item, lows, gemStats);
            maximum = enhancementBonusMap(level, item, highs, gemStats);
        }
        return new BonusDisplay(rolled, minimum, maximum, enhancement.bonusColor());
    }

    /**
     * Rewrites {@code itemStack} at {@code newLevel} with {@code sockets}, re-rendering its lore so
     * the displayed numbers match what will actually be settled.
     * <p>
     * The item is rebuilt through {@link #createItem(RpgItem, Map, int, List)} and then has everything
     * the player earned copied back on, the same way {@link #updateOutdatedItem(ItemStack)} does it.
     * The random attributes already rolled on the stack are carried over rather than re-rolled, so
     * enhancing an item never quietly changes its other stats.
     * <p>
     * Both the enhancement level and the socket contents are things the player earned, so every
     * caller must pass both: a rebuild that forgot one would silently erase it from the item.
     *
     * @return false when the stack is not a registered RPG item
     */
    private boolean rebuildItem(ItemStack itemStack, RpgItem registered, int level, List<String> sockets) {
        var oldMeta = itemStack.getItemMeta();
        var container = oldMeta.getPersistentDataContainer();
        int amount = itemStack.getAmount();
        int oldDamage = oldMeta instanceof Damageable damageable ? damageable.getDamage() : 0;
        Integer oldExtraCurrent = container.get(exDurCurrentKey, PersistentDataType.INTEGER);
        Integer oldExtraMax = container.get(exDurMaxKey, PersistentDataType.INTEGER);
        String soulboundOwner = container.get(soulboundKey, PersistentDataType.STRING);
        Map<String, Integer> randomValues = readRandomAttributes(itemStack);
        if (randomValues.isEmpty() && !registered.randomAttributes().isEmpty()) {
            randomValues = rollRandomAttributes(registered);
        }

        ItemStack replacement = createItem(registered, randomValues, level, sockets);
        replacement.setAmount(amount);
        var replacementMeta = replacement.getItemMeta();
        if (replacementMeta instanceof Damageable replacementDamageable && replacement.getType().getMaxDurability() > 0) {
            replacementDamageable.setDamage(Math.min(oldDamage, replacement.getType().getMaxDurability() - 1));
        }
        var replacementContainer = replacementMeta.getPersistentDataContainer();
        Integer newExtraMax = replacementContainer.get(exDurMaxKey, PersistentDataType.INTEGER);
        if (oldExtraCurrent != null && oldExtraMax != null && newExtraMax != null) {
            int consumed = Math.max(0, oldExtraMax - oldExtraCurrent);
            replacementContainer.set(exDurCurrentKey, PersistentDataType.INTEGER, Math.max(0, newExtraMax - consumed));
        }
        if (soulboundOwner != null) replacementContainer.set(soulboundKey, PersistentDataType.STRING, soulboundOwner);
        if (level > 0) replacementContainer.set(enhancementKey, PersistentDataType.INTEGER, level);
        replacement.setItemMeta(replacementMeta);

        itemStack.setType(replacement.getType());
        itemStack.setAmount(replacement.getAmount());
        itemStack.setItemMeta(replacement.getItemMeta());
        return true;
    }

    /** Rewrites {@code itemStack} at {@code newLevel}, keeping its socketed gems. */
    public boolean setEnhanceLevel(ItemStack itemStack, int newLevel) {
        if (!hasRpgIdentity(itemStack)) return false;
        RpgItem registered = findItem(itemStack);
        if (registered == null) return false;
        EnhancementManager enhancement = plugin.getEnhancementManager();
        int level = Math.max(0, newLevel);
        if (enhancement != null) level = Math.min(level, enhancement.maxLevel());
        return rebuildItem(itemStack, registered, level, socketedGems(itemStack));
    }

    // ---- Gem sockets --------------------------------------------------------------------------

    /**
     * The gem display for a set of sockets: the summed values per attribute plus the bracket colour.
     * <p>
     * A record of its own rather than a field on {@link BonusDisplay} because the two brackets are
     * independent — an item can carry gems without being enhanced, and vice versa.
     */
    private record GemDisplay(Map<String, Double> values, String color) {

        static GemDisplay none() {
            return new GemDisplay(Map.of(), null);
        }

        /** The colour is null exactly when no gem bracket should be printed at all. */
        boolean disabled() {
            return color == null;
        }
    }

    private GemDisplay buildGemDisplay(List<String> sockets) {
        if (!gemsEnabled() || sockets == null || sockets.isEmpty()) return GemDisplay.none();
        Map<String, Double> values = gemValueMap(sockets);
        if (values.isEmpty()) return GemDisplay.none();
        return new GemDisplay(values, gemColor());
    }

    private boolean gemsEnabled() {
        return plugin.isFeatureEnabled("gemstones") && plugin.getGemstoneManager() != null;
    }

    private String gemColor() {
        return plugin.getConfig().getString("lore-templates.socket-line.gem-color", "&a");
    }

    /**
     * The attribute values every socketed gem contributes, summed per attribute.
     * <p>
     * An id that is no longer in {@code gemstones.yml} contributes nothing rather than throwing: a
     * gem removed from config must not corrupt settlement, and the socket keeps its id so putting the
     * entry back restores it.
     */
    public Map<String, Double> gemValueMap(List<String> sockets) {
        Map<String, Double> raw = new LinkedHashMap<>();
        GemstoneManager gems = plugin.getGemstoneManager();
        if (gems == null || sockets == null) return raw;
        for (String gemId : sockets) {
            if (gemId == null || gemId.isBlank()) continue;
            GemstoneManager.Gemstone gem = gems.get(gemId);
            if (gem == null) continue;
            for (Map.Entry<String, Double> entry : gem.stats().entrySet()) {
                raw.merge(entry.getKey(), entry.getValue(), Double::sum);
            }
        }
        // Round-tripped through RpgStats so only keys settlement actually reads survive: a typo'd key
        // in gemstones.yml would otherwise print a green bracket for a stat that is never granted,
        // and the printed numbers must never promise more than settlement delivers.
        return RpgStats.fromLore(raw).toMap();
    }

    /** The gem stats of {@code itemStack} as a stat block, or null when it has no effective gems. */
    public RpgStats gemStats(ItemStack itemStack) {
        if (!gemsEnabled()) return null;
        List<String> sockets = socketedGems(itemStack);
        if (sockets.isEmpty()) return null;
        Map<String, Double> values = gemValueMap(sockets);
        return values.isEmpty() ? null : RpgStats.fromLore(values);
    }

    /** The gem id in each socket of {@code itemStack}, in slot order; blank means an open socket. */
    public List<String> socketedGems(ItemStack itemStack) {
        if (itemStack == null || !itemStack.hasItemMeta()) return List.of();
        String serialized = itemStack.getItemMeta().getPersistentDataContainer().get(gemKey, PersistentDataType.STRING);
        if (serialized == null || serialized.isBlank()) return List.of();
        List<String> sockets = new ArrayList<>();
        for (String part : serialized.split(",", -1)) sockets.add(part.trim());
        return sockets;
    }

    /** How many sockets {@code item} declares; the count the UI and the strip display. */
    public int socketCapacity(ItemStack itemStack) {
        RpgItem registered = findItem(itemStack);
        return registered == null ? 0 : registered.sockets();
    }

    /**
     * Places {@code gemId} into one open socket and re-renders.
     * <p>
     * The caller consumes the gem item only after this returns true, so a refusal never costs the
     * player a gem. Capacity comes from the registry, never from the current PDC length, so a socket
     * cannot be opened by writing past the declared count.
     *
     * @return false when the stack is unregistered, the slot is out of range or already filled
     */
    public boolean socketGem(ItemStack itemStack, int slot, String gemId) {
        if (gemId == null || gemId.isBlank()) return false;
        if (!hasRpgIdentity(itemStack)) return false;
        RpgItem registered = findItem(itemStack);
        if (registered == null || slot < 0 || slot >= registered.sockets()) return false;
        if (!plugin.getGemstoneManager().isGem(gemId)) return false;
        List<String> sockets = new ArrayList<>(normalizeSockets(socketedGems(itemStack), registered.sockets()));
        if (slot >= sockets.size() || !sockets.get(slot).isBlank()) return false;
        sockets.set(slot, gemId);
        return rebuildItem(itemStack, registered, enhanceLevel(itemStack), sockets);
    }

    /**
     * Removes whatever sits in one socket and re-renders.
     *
     * @return the gem id that was removed, or null when the socket was already open (in which case
     *         nothing changed and the caller must not hand anything back)
     */
    public String unsocketGem(ItemStack itemStack, int slot) {
        if (!hasRpgIdentity(itemStack)) return null;
        RpgItem registered = findItem(itemStack);
        if (registered == null || slot < 0) return null;
        List<String> sockets = new ArrayList<>(normalizeSockets(socketedGems(itemStack), registered.sockets()));
        if (slot >= sockets.size()) return null;
        String removed = sockets.get(slot);
        if (removed == null || removed.isBlank()) return null;
        sockets.set(slot, "");
        if (!rebuildItem(itemStack, registered, enhanceLevel(itemStack), sockets)) return null;
        return removed;
    }

    /**
     * Pads {@code sockets} up to {@code capacity} with open entries.
     * <p>
     * Never truncates: an item whose configured capacity shrank still carries its extra gems, and
     * dropping them here would delete a player's property on a config edit that is meant to be
     * cosmetic. The extras keep granting their stats and stay reachable by index.
     */
    private static List<String> normalizeSockets(List<String> sockets, int capacity) {
        List<String> normalized = new ArrayList<>();
        if (sockets != null) {
            for (String gemId : sockets) normalized.add(gemId == null ? "" : gemId.trim());
        }
        while (normalized.size() < capacity) normalized.add("");
        return normalized;
    }

    /**
     * The socket strip rendered under the level line, or null when the item has no sockets.
     * <p>
     * An open socket is a white {@code [   ]} and a filled one shows that gem's own coloured icon
     * inside white brackets, so the colour of the brackets themselves stays neutral and the gem's
     * colour is what varies — which is what makes two different gems distinguishable at a glance.
     */
    private String renderSocketLine(List<String> sockets, int capacity) {
        if (!gemsEnabled() || capacity <= 0 || sockets.isEmpty()) return null;
        if (!plugin.getConfig().getBoolean("lore-templates.socket-line.enabled", true)) return null;
        GemstoneManager gems = plugin.getGemstoneManager();
        String bracketColor = plugin.getConfig().getString("lore-templates.socket-line.bracket-color", "&f");
        String label = plugin.getConfig().getString("lore-templates.socket-line.label", "&7宝石槽: ");
        StringBuilder line = new StringBuilder(label);
        for (String gemId : sockets) {
            if (line.length() > label.length()) line.append(' ');
            line.append(bracketColor).append("[ ");
            if (gemId == null || gemId.isBlank()) {
                line.append(' ');
            } else {
                GemstoneManager.Gemstone gem = gems.get(gemId);
                // An id that is no longer registered is drawn as an explicit unknown rather than as an
                // empty slot: an empty slot would invite the player to fill it and quietly overwrite
                // the gem still stored there.
                line.append(gem == null ? "&7?" : gem.coloredIcon());
            }
            line.append(' ').append(bracketColor).append(']');
        }
        return line.toString();
    }

    public boolean hasRpgIdentity(ItemStack itemStack) {
        return itemStack != null && itemStack.hasItemMeta()
                && itemStack.getItemMeta().getPersistentDataContainer().has(idKey, PersistentDataType.STRING);
    }

    private boolean matchesRegisteredAttributes(ItemStack itemStack, RpgItem item) {
        var container = itemStack.getItemMeta().getPersistentDataContainer();
        Integer storedVersion = container.get(itemVersionKey, PersistentDataType.INTEGER);
        String storedSignature = container.get(attributeSignatureKey, PersistentDataType.STRING);
        return storedVersion != null && storedVersion == item.itemVersion()
                && storedSignature != null && storedSignature.equals(attributeSignature(item))
                && readRandomAttributes(itemStack).keySet().containsAll(item.randomAttributes().keySet());
    }

    /**
     * The rendered-attribute fingerprint of a registered item, memoised.
     * <p>
     * <b>Why a cache</b>: building the fingerprint means string-joining every attribute of the item
     * (via {@code RpgStats.toString()}, which prints all sixty components), both attribute tables'
     * fingerprints, and hashing the lot with SHA-256. It used to run on <b>every</b>
     * {@link #findItem(ItemStack)} call — that is, several times per equipment slot per settlement,
     * on every settlement, for every player. Nothing in the input changes between two calls unless
     * the item table or one of the two revision counters changed, so the repeated work was pure
     * waste on the main thread.
     * <p>
     * The key is the {@link RpgItem} record itself: records have value semantics, which is exactly
     * the identity this fingerprint is derived from. The cache is dropped whenever either table's
     * fingerprint changes, so a rebalance of {@code enhancement.yml} or the gem table re-renders
     * items exactly as before.
     */
    private String attributeSignature(RpgItem item) {
        String enhanceRevision = enhancementRevision();
        String gemRevision = gemstoneRevision();
        // A changed table invalidates every entry, so the revision pair is only ever compared, not
        // stored per entry.
        if (!enhanceRevision.equals(signatureEnhancementRevision) || !gemRevision.equals(signatureGemstoneRevision)) {
            signatureCache.clear();
            signatureEnhancementRevision = enhanceRevision;
            signatureGemstoneRevision = gemRevision;
        }
        return signatureCache.computeIfAbsent(item, key -> computeAttributeSignature(key, enhanceRevision, gemRevision));
    }

    private String computeAttributeSignature(RpgItem item, String enhanceRevision, String gemRevision) {
        String randomRanges = new TreeMap<>(item.randomAttributes()).toString();
        String vanillaAttributes = item.vanillaAttributes().stream().map(VanillaAttributeConfig::toString).sorted().toList().toString();
        String buffs = item.buffs().stream().map(BuffConfig::toString).sorted().toList().toString();
        String source = RpgStats.empty().add(item) + "|" + randomRanges + "|" + vanillaAttributes
                + "|buffs=" + buffs
                // Bumped whenever the rendered lore changes shape, so items already in the world
                // are re-created once and pick up the new description instead of keeping the old one.
                + "|loreFormat=" + LORE_FORMAT_VERSION
                + "|level=" + item.level() + "|deputy=" + item.deputy()
                + "|fixInterval=" + item.fixIntervalSeconds() + "|breakLimit=" + item.breakLimit()
                + "|soulbound=" + item.soulbound() + "|consumables=" + item.consumables() + "|unbreakable=" + item.unbreakable()
                + "|collection=" + item.farmingSpeed() + ":" + item.miningSpeed() + ":" + item.forestSpeed()
                + ":" + item.farmingFortune() + ":" + item.miningFortune() + ":" + item.forestFortune()
                + ":" + item.farmingLevel() + ":" + item.miningLevel() + ":" + item.forestLevel()
                // The enhancement table is part of the signature because the level line and the
                // bonus folded into the attribute values are rendered into the lore at build time.
                // Rebalancing enhancement.yml therefore re-renders every item once, keeping the
                // printed numbers equal to the numbers that get settled.
                + "|enhance=" + enhanceRevision
                // Socket count and the gem table both change what the lore renders (the socket strip
                // and each gem's green bracket), so an edit to either re-renders items once.
                + "|sockets=" + item.sockets()
                + "|gems=" + gemRevision;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private Map<String, Integer> rollRandomAttributes(RpgItem item) {
        Map<String, Integer> values = new HashMap<>();
        item.randomAttributes().forEach((key, range) -> values.put(key, range.roll()));
        return values;
    }

    private String serializeRandomAttributes(Map<String, Integer> values) {
        return new TreeMap<>(values).entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(";"));
    }

    /**
     * Sockets are stored as a comma-joined id list, positionally: the entry at index {@code i} is the
     * gem in socket {@code i}, and an empty entry is an open socket. A gem id containing a comma is
     * rejected when {@code gemstones.yml} loads, so the separator cannot be spoofed.
     */
    private String serializeGems(List<String> sockets) {
        return String.join(",", sockets);
    }

    private Map<String, Integer> readRandomAttributes(ItemStack itemStack) {
        if (itemStack == null || !itemStack.hasItemMeta()) return Map.of();
        String serialized = itemStack.getItemMeta().getPersistentDataContainer().get(randomAttributesKey, PersistentDataType.STRING);
        if (serialized == null || serialized.isBlank()) return Map.of();
        Map<String, Integer> values = new HashMap<>();
        for (String pair : serialized.split(";")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2) values.put(parts[0], parseInt(parts[1], 0));
        }
        return values;
    }

    private Map<String, Integer> resolvedRandomAttributes(ItemStack itemStack, RpgItem item) {
        return resolvedRandomAttributes(itemStack, item, rerollOnUse());
    }

    /**
     * Whether random attribute ranges are re-rolled for every settlement.
     * <p>
     * This reads {@code rpgitem.random-attributes.reroll-on-use}, which the manual documents and
     * which previously was never read at all: every item behaved as if it were {@code false}, so a
     * range such as {@code BowDamage: "40-60"} rolled once at creation and then never changed.
     */
    public boolean rerollOnUse() {
        return plugin.getConfig().getBoolean("rpgitem.random-attributes.reroll-on-use", true);
    }

    private Map<String, Integer> resolvedRandomAttributes(ItemStack itemStack, RpgItem item, boolean reroll) {
        if (item.randomAttributes().isEmpty()) return Map.of();
        Map<String, Integer> stored = readRandomAttributes(itemStack);
        Map<String, Integer> values = new HashMap<>();
        for (Map.Entry<String, RandomAttributeRange> entry : item.randomAttributes().entrySet()) {
            if (reroll) {
                // reroll-on-use: the range is drawn again for this settlement, so a range in the
                // lore ("40-60") is the honest description of what the player will actually get.
                values.put(entry.getKey(), entry.getValue().roll());
                continue;
            }
            Integer rolled = stored.get(entry.getKey());
            // The value rolled by createItem is persisted on the item and rendered into its lore.
            // Fall back to the range minimum, never to a fresh roll.
            values.put(entry.getKey(), rolled != null ? rolled : entry.getValue().minimum());
        }
        return values;
    }

    private void updateHeldItems(Player player) {
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (updateOutdatedItem(mainHand)) player.getInventory().setItemInMainHand(mainHand);
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (updateOutdatedItem(offHand)) player.getInventory().setItemInOffHand(offHand);
    }

    private void updateEquippedItems(Player player) {
        updateHeldItems(player);
        ItemStack[] armor = player.getInventory().getArmorContents();
        boolean changed = false;
        for (ItemStack itemStack : armor) {
            if (updateOutdatedItem(itemStack)) changed = true;
        }
        if (changed) player.getInventory().setArmorContents(armor);
        plugin.getAccessoryManager().updateOutdatedItems(player);
    }

    /** Diagnostic counters for /rpgitem memdebug: external modifier state held in memory. */
    public int trackedModifierEntities() {
        return attributeModifiers.size();
    }

    public int trackedModifierSources() {
        int total = 0;
        for (Map<String, AttributeModifierEntry> entries : attributeModifiers.values()) total += entries.size();
        return total;
    }

    public int pendingModifierExpiryTasks() {
        int total = 0;
        for (Map<String, BukkitTask> tasks : modifierExpiryTasks.values()) total += tasks.size();
        return total;
    }

    private boolean hasVanillaAttribute(RpgItem item, Attribute expected) {
        return item.vanillaAttributes().stream().map(VanillaAttributeConfig::attribute)
                .map(this::resolveVanillaAttribute).anyMatch(expected::equals);
    }

    private void applyVanillaAttributes(org.bukkit.inventory.meta.ItemMeta meta, RpgItem item, Material material) {
        if (item.type() == RpgItemType.ACCESSORY) {
            if (!item.vanillaAttributes().isEmpty()) {
                plugin.getLogger().warning("RPGItem " + item.id() + " is an accessory; vanilla attributes are ignored to prevent hand-held activation.");
            }
            return;
        }
        for (int index = 0; index < item.vanillaAttributes().size(); index++) {
            VanillaAttributeConfig config = item.vanillaAttributes().get(index);
            Attribute attribute = resolveVanillaAttribute(config.attribute());
            if (attribute == null) {
                plugin.getLogger().warning("Unknown vanilla attribute '" + config.attribute() + "' in RPGItem " + item.id());
                continue;
            }
            AttributeModifier.Operation operation = resolveOperation(config.operation());
            EquipmentSlotGroup slot = resolveSlot(config.slot(), material, item.type());
            NamespacedKey key = new NamespacedKey(plugin, "native_" + Integer.toHexString((item.id() + ':' + index + ':' + attribute).hashCode()));
            meta.addAttributeModifier(attribute, new AttributeModifier(key, config.amount(), operation, slot));
        }
    }

    private Attribute resolveVanillaAttribute(String raw) {
        if (raw == null) return null;
        String key = raw.toLowerCase(Locale.ROOT).replace("minecraft:", "").replace("generic.", "").replace("generic_", "");
        return switch (key) {
            case "knockback_resistance" -> Attribute.GENERIC_KNOCKBACK_RESISTANCE;
            case "max_health" -> Attribute.GENERIC_MAX_HEALTH;
            case "attack_damage" -> Attribute.GENERIC_ATTACK_DAMAGE;
            case "movement_speed" -> Attribute.GENERIC_MOVEMENT_SPEED;
            case "armor" -> Attribute.GENERIC_ARMOR;
            case "armor_toughness" -> Attribute.GENERIC_ARMOR_TOUGHNESS;
            case "attack_speed" -> Attribute.GENERIC_ATTACK_SPEED;
            case "luck" -> Attribute.GENERIC_LUCK;
            case "attack_knockback" -> Attribute.GENERIC_ATTACK_KNOCKBACK;
            case "jump_strength" -> Attribute.GENERIC_JUMP_STRENGTH;
            default -> null;
        };
    }

    private AttributeModifier.Operation resolveOperation(String raw) {
        if (raw == null) return AttributeModifier.Operation.ADD_NUMBER;
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "ADD_SCALAR", "ADD_PERCENT" -> AttributeModifier.Operation.ADD_SCALAR;
            case "MULTIPLY", "MULTIPLY_SCALAR_1" -> AttributeModifier.Operation.MULTIPLY_SCALAR_1;
            default -> AttributeModifier.Operation.ADD_NUMBER;
        };
    }

    private EquipmentSlotGroup resolveSlot(String raw, Material material, RpgItemType type) {
        if (raw != null && !raw.equalsIgnoreCase("AUTO")) {
            EquipmentSlotGroup configured = switch (raw.trim().toUpperCase(Locale.ROOT)) {
                case "ANY" -> EquipmentSlotGroup.ANY;
                case "ARMOR" -> EquipmentSlotGroup.ARMOR;
                case "BODY" -> EquipmentSlotGroup.BODY;
                case "CHEST" -> EquipmentSlotGroup.CHEST;
                case "FEET", "BOOTS" -> EquipmentSlotGroup.FEET;
                case "HAND" -> EquipmentSlotGroup.HAND;
                case "HEAD", "HELMET" -> EquipmentSlotGroup.HEAD;
                case "LEGS", "LEGGINGS" -> EquipmentSlotGroup.LEGS;
                case "MAINHAND", "MAIN_HAND" -> EquipmentSlotGroup.MAINHAND;
                case "OFFHAND", "OFF_HAND" -> EquipmentSlotGroup.OFFHAND;
                default -> null;
            };
            if (configured != null) return configured;
            plugin.getLogger().warning("Unknown equipment slot group '" + raw + "', using AUTO.");
        }
        if (type == RpgItemType.ACCESSORY) return EquipmentSlotGroup.ANY;
        String name = material.name();
        if (name.endsWith("_HELMET")) return EquipmentSlotGroup.HEAD;
        if (name.endsWith("_CHESTPLATE") || name.equals("ELYTRA")) return EquipmentSlotGroup.CHEST;
        if (name.endsWith("_LEGGINGS")) return EquipmentSlotGroup.LEGS;
        if (name.endsWith("_BOOTS")) return EquipmentSlotGroup.FEET;
        return EquipmentSlotGroup.MAINHAND;
    }

    private void disableOutdatedVanillaAttributes(ItemStack itemStack) {
        if (itemStack == null || !itemStack.hasItemMeta()) return;
        var meta = itemStack.getItemMeta();
        boolean changed = false;
        for (Attribute attribute : Attribute.values()) {
            var modifiers = meta.getAttributeModifiers(attribute);
            if (modifiers == null) continue;
            for (AttributeModifier modifier : List.copyOf(modifiers)) {
                NamespacedKey key = modifier.getKey();
                if (key.getNamespace().equals(plugin.getName().toLowerCase(Locale.ROOT))
                        && (key.getKey().startsWith("native_") || key.equals(vanillaSpeedKey))) {
                    meta.removeAttributeModifier(attribute, modifier);
                    changed = true;
                }
            }
        }
        if (changed) itemStack.setItemMeta(meta);
    }

    private void normalizeLoreFont(ItemStack itemStack) {
        var meta = itemStack.getItemMeta();
        List<net.kyori.adventure.text.Component> lore = meta.lore();
        var displayName = meta.displayName();
        boolean nameNeedsUpdate = displayName != null && !Texts.isNonItalic(displayName);
        boolean loreNeedsUpdate = lore != null && !lore.stream().allMatch(Texts::isNonItalic);
        if (!nameNeedsUpdate && !loreNeedsUpdate) return;
        if (nameNeedsUpdate) meta.displayName(Texts.nonItalic(displayName));
        if (loreNeedsUpdate) meta.lore(lore.stream().map(Texts::nonItalic).toList());
        itemStack.setItemMeta(meta);
    }

    private void addActiveItem(Player player, ItemStack itemStack, List<RpgItem> activeItems, boolean notifyLevel, boolean offhand) {
        RpgItem rpgItem = findItem(itemStack);
        if (rpgItem == null || !canUseItem(player, itemStack, rpgItem, notifyLevel, offhand)) return;
        if (rpgItem.type() == RpgItemType.ACCESSORY) return;
        initializeExtraDurability(itemStack, rpgItem);
        bindIfNeeded(player, itemStack, rpgItem);
        activeItems.add(rpgItem);
    }

    private RpgStats addActiveStats(Player player, ItemStack itemStack, RpgStats stats, boolean notifyLevel, boolean offhand, boolean rerollRandom) {
        RpgItem item = findItem(itemStack);
        if (item == null || item.type() == RpgItemType.ACCESSORY || !canUseItem(player, itemStack, item, notifyLevel, offhand)) return stats;
        initializeExtraDurability(itemStack, item);
        bindIfNeeded(player, itemStack, item);
        return addWithEnhancement(stats, itemStack, item, resolvedRandomAttributes(itemStack, item, rerollRandom));
    }

    private boolean canUseItem(Player player, ItemStack itemStack, RpgItem rpgItem, boolean notifyLevel, boolean offhand) {
        if (offhand && !rpgItem.deputy()) return false;
        if (isNearlyBroken(itemStack)) {
            if (notifyLevel) sendDurabilityMessage(player, plugin.getConfig().getString("durability.warning-message", "&cItem is nearly broken"));
            return false;
        }
        if (plugin.isFeatureEnabled("level") && rpgItem.level() > 0 && effectiveLevel(player) < rpgItem.level()) {
            if (notifyLevel) sendLevelMessage(player, rpgItem.level());
            return false;
        }
        if (plugin.isFeatureEnabled("soulbound") && rpgItem.soulbound() && player.getGameMode() != GameMode.CREATIVE && itemStack != null && itemStack.hasItemMeta()) {
            String owner = itemStack.getItemMeta().getPersistentDataContainer().get(soulboundKey, PersistentDataType.STRING);
            return owner == null || owner.equals(player.getUniqueId().toString()) || owner.equalsIgnoreCase(player.getName());
        }
        return true;
    }

    public ItemStack bindIfNeeded(Player player, ItemStack itemStack, RpgItem rpgItem) {
        if (!plugin.isFeatureEnabled("soulbound") || !rpgItem.soulbound() || player.getGameMode() == GameMode.CREATIVE || itemStack == null || !itemStack.hasItemMeta()) return itemStack;
        var meta = itemStack.getItemMeta();
        String existingOwner = meta.getPersistentDataContainer().get(soulboundKey, PersistentDataType.STRING);
        if (existingOwner != null) {
            if (existingOwner.equals(player.getUniqueId().toString())) {
                meta.getPersistentDataContainer().set(soulboundKey, PersistentDataType.STRING, player.getName());
                List<net.kyori.adventure.text.Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
                lore.replaceAll(Texts::nonItalic);
                lore.removeIf(component -> Texts.plainText(component).startsWith("bind:"));
                lore.add(Texts.item("bind:" + player.getName()));
                meta.lore(lore);
                itemStack.setItemMeta(meta);
            }
            return itemStack;
        }
        String owner = player.getName();
        meta.getPersistentDataContainer().set(soulboundKey, PersistentDataType.STRING, owner);
        List<net.kyori.adventure.text.Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.replaceAll(Texts::nonItalic);
        lore.removeIf(component -> Texts.plainText(component).startsWith("bind:"));
        lore.add(Texts.item("bind:" + owner));
        meta.lore(lore);
        itemStack.setItemMeta(meta);
        return itemStack;
    }

    public boolean isSoulboundToOther(Player player, ItemStack itemStack) {
        if (player == null || player.isOp()) return false;
        RpgItem rpgItem = findItem(itemStack);
        if (rpgItem == null || !rpgItem.soulbound() || itemStack == null || !itemStack.hasItemMeta()) return false;
        String owner = itemStack.getItemMeta().getPersistentDataContainer().get(soulboundKey, PersistentDataType.STRING);
        return owner != null && !owner.equalsIgnoreCase(player.getName()) && !owner.equals(player.getUniqueId().toString());
    }

    public boolean ensureSoulboundOwner(Player player, ItemStack itemStack) {
        RpgItem rpgItem = findItem(itemStack);
        if (rpgItem == null || !rpgItem.soulbound()) return true;
        if (isSoulboundToOther(player, itemStack)) return false;
        bindIfNeeded(player, itemStack, rpgItem);
        return true;
    }

    private boolean isNearlyBroken(ItemStack itemStack) {
        if (itemStack == null || !(itemStack.getItemMeta() instanceof Damageable damageable)) return false;
        int max = itemStack.getType().getMaxDurability();
        return max > 0 && max - damageable.getDamage() <= 1;
    }

    private boolean protectVanillaDurability(Player player, ItemStack itemStack, RpgItem rpgItem, int amount) {
        if (!(itemStack.getItemMeta() instanceof Damageable damageable)) return false;
        int max = itemStack.getType().getMaxDurability();
        if (max <= 0) return false;
        if (plugin.isFeatureEnabled("break-limit") && rpgItem.breakLimit() && damageable.getDamage() + amount >= max) {
            damageable.setDamage(max - 1);
            itemStack.setItemMeta(damageable);
            sendDurabilityMessage(player, plugin.getConfig().getString("durability.break-limit-message", "&cBreakLimit protected this item"));
            return true;
        }
        return false;
    }

    private boolean shouldConsumeVanillaDurability(ItemStack itemStack) {
        int unbreakingLevel = itemStack.getEnchantmentLevel(Enchantment.UNBREAKING);
        return unbreakingLevel <= 0 || ThreadLocalRandom.current().nextInt(unbreakingLevel + 1) == 0;
    }

    private void initializeExtraDurability(ItemStack itemStack, RpgItem rpgItem) {
        if (!plugin.isFeatureEnabled("ex-dur") || rpgItem.extraDurability() <= 0 || itemStack == null || !itemStack.hasItemMeta()) return;
        var meta = itemStack.getItemMeta();
        if (meta.getPersistentDataContainer().has(exDurCurrentKey, PersistentDataType.INTEGER)) return;
        meta.getPersistentDataContainer().set(exDurCurrentKey, PersistentDataType.INTEGER, rpgItem.extraDurability());
        meta.getPersistentDataContainer().set(exDurMaxKey, PersistentDataType.INTEGER, rpgItem.extraDurability());
        itemStack.setItemMeta(meta);
        updateExtraDurabilityLore(itemStack, rpgItem.extraDurability(), rpgItem.extraDurability());
    }

    private void updateExtraDurabilityLore(ItemStack itemStack, int current, int max) {
        if (itemStack == null || !itemStack.hasItemMeta()) return;
        var meta = itemStack.getItemMeta();
        List<net.kyori.adventure.text.Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.replaceAll(Texts::nonItalic);
        lore.removeIf(component -> Texts.plainText(component).startsWith("ExDur:"));
        lore.add(Texts.item("&fExDur:" + current + "/" + max));
        meta.lore(lore);
        itemStack.setItemMeta(meta);
    }

    private RpgStats filterDisabledStats(RpgStats stats) {
        return new RpgStats(
                enabled("attack", stats.attack()), enabled("attack", stats.attackPercent()), enabled("armor", stats.armor()), enabled("armor", stats.armorPercent()), enabled("crit-chance", stats.critChance()), enabled("crit-damage", stats.critDamage()), enabled("crit-damage", stats.critDamagePercent()), enabled("attack-speed", stats.attackSpeed()),
                enabled("health", stats.health()), enabled("vitality", stats.vitality()), enabled("recovery", stats.recovery()), enabled("recovery-percent", stats.recoveryPercent()), enabled("life-steal", stats.lifeSteal()), enabled("life-steal", stats.lifeStealPercent()), enabled("magic-damage", stats.magicDamage()), enabled("magic-damage", stats.magicDamagePercent()),
                enabled("bow-damage", stats.bowDamage()), enabled("bow-damage", stats.bowDamagePercent()), enabled("exdraw", stats.exDraw()), enabled("fix", stats.fixPerSecond()), plugin.isFeatureEnabled("ex-dur") ? stats.extraDurability() : 0, enabled("env-armor", stats.envArmor()), enabled("env-armor", stats.envArmorPercent()),
                enabled("magic-armor", stats.magicArmor()), enabled("magic-armor", stats.magicArmorPercent()), enabled("resistance", stats.resistance()), enabled("bow-armor", stats.bowArmor()), enabled("bow-armor", stats.bowArmorPercent()),
                enabled("crit-armor", stats.critArmor()), enabled("crit-armor", stats.critArmorPercent()), enabled("evasion", stats.evasion()), enabled("armor-pen", stats.armorPen()),
                enabled("true-damage", stats.trueDamage()), enabled("backstrike-damage", stats.backstrikeDamage()), enabled("backstrike-damage", stats.backstrikeDamagePercent()), enabled("ass-damage", stats.assDamage()), enabled("ass-damage", stats.assDamagePercent()),
                enabled("pct-damage", stats.pctDamage()), enabled("pst-damage", stats.pstDamage()), enabled("attack-range", stats.attackRange()), enabled("parry", stats.parry()), enabled("counter-attack", stats.counterAttack()),
                enabled("dodge", stats.dodge()), enabled("block", stats.block()), enabled("determination", stats.determination()), enabled("cad", stats.cad()),
                enabled("ice-damage", stats.iceDamage()), enabled("ice-damage", stats.iceDamagePercent()), enabled("fire-damage", stats.fireDamage()), enabled("fire-damage", stats.fireDamagePercent()),
                enabled("light-damage", stats.lightDamage()), enabled("light-damage", stats.lightDamagePercent()), enabled("dark-damage", stats.darkDamage()), enabled("dark-damage", stats.darkDamagePercent()),
                enabled("ice-armor", stats.iceArmor()), enabled("ice-armor", stats.iceArmorPercent()), enabled("fire-armor", stats.fireArmor()), enabled("fire-armor", stats.fireArmorPercent()),
                enabled("light-armor", stats.lightArmor()), enabled("light-armor", stats.lightArmorPercent()), enabled("dark-armor", stats.darkArmor()), enabled("dark-armor", stats.darkArmorPercent()));
    }

    private double enabled(String feature, double value) { return plugin.isFeatureEnabled(feature) ? value : 0; }

    private List<RpgItem> loadItems(RpgItemType type) {
        File folder = new File(plugin.getDataFolder(), type.getFolderName());
        File[] files = folder.listFiles((dir, name) -> isItemConfigFile(name));
        if (files == null) return Collections.emptyList();
        Arrays.sort(files, java.util.Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        List<RpgItem> loadedItems = new ArrayList<>();
        Map<String, List<RpgItem>> groups = new LinkedHashMap<>();
        for (File file : files) {
            List<RpgItem> loaded = loadItemFile(type, file);
            String lower = file.getName().toLowerCase(Locale.ROOT);
            String group = lower.endsWith(".yml") || lower.endsWith(".yaml") ? stripItemConfigExtension(file.getName()) : "默认 TXT 物品";
            groups.computeIfAbsent(group, ignored -> new ArrayList<>()).addAll(loaded);
            loadedItems.addAll(loaded);
        }
        itemGroups.put(type, groups);
        return loadedItems;
    }

    private List<RpgItem> loadItemFile(RpgItemType type, File file) {
        List<RpgItem> concatenatedItems = loadConcatenatedYamlItems(type, file);
        if (!concatenatedItems.isEmpty()) return concatenatedItems;

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        String fallbackId = stripItemConfigExtension(file.getName());
        ConfigurationSection multiItems = config.getConfigurationSection("items");
        if (multiItems == null) multiItems = config.getConfigurationSection("rpgitems");
        if (multiItems != null) return loadItemSections(type, file, multiItems);

        if (!isSingleItemRoot(config)) {
            List<RpgItem> directItems = loadDirectItemSections(type, file, config);
            if (!directItems.isEmpty()) return directItems;
        }
        return List.of(loadItem(type, config, fallbackId));
    }

    private List<RpgItem> loadConcatenatedYamlItems(RpgItemType type, File file) {
        String lowerName = file.getName().toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".yml") && !lowerName.endsWith(".yaml")) return Collections.emptyList();

        try {
            String raw = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            Matcher idMatcher = Pattern.compile("(?m)^id\\s*:").matcher(raw);
            List<Integer> starts = new ArrayList<>();
            while (idMatcher.find()) starts.add(idMatcher.start());
            if (starts.size() < 2) return Collections.emptyList();

            List<RpgItem> loaded = new ArrayList<>();
            for (int index = 0; index < starts.size(); index++) {
                int start = starts.get(index);
                int end = index + 1 < starts.size() ? starts.get(index + 1) : raw.length();
                String itemYaml = raw.substring(start, end).trim();
                if (itemYaml.endsWith("---")) itemYaml = itemYaml.substring(0, itemYaml.length() - 3).trim();

                YamlConfiguration itemConfig = new YamlConfiguration();
                itemConfig.loadFromString(itemYaml);
                String fallbackId = stripItemConfigExtension(file.getName()) + "_" + (index + 1);
                loaded.add(loadItem(type, itemConfig, fallbackId));
            }
            plugin.getLogger().info("Loaded concatenated multi-item YAML: " + file.getName() + " (" + loaded.size() + " items)");
            return loaded;
        } catch (Exception exception) {
            plugin.getLogger().warning("Cannot load concatenated RPGItem YAML " + file.getPath() + ": " + exception.getMessage());
            return Collections.emptyList();
        }
    }

    private List<RpgItem> loadItemSections(RpgItemType type, File file, ConfigurationSection itemsSection) {
        List<RpgItem> loaded = new ArrayList<>();
        for (String key : itemsSection.getKeys(false)) {
            ConfigurationSection itemSection = itemsSection.getConfigurationSection(key);
            if (itemSection == null) {
                plugin.getLogger().warning("Cannot load RPGItem '" + key + "' from " + file.getPath() + ": item node must be a YAML section.");
                continue;
            }
            loaded.add(loadItem(type, scopedConfiguration(itemSection), key));
        }
        return loaded;
    }

    private List<RpgItem> loadDirectItemSections(RpgItemType type, File file, YamlConfiguration config) {
        List<RpgItem> loaded = new ArrayList<>();
        for (String key : config.getKeys(false)) {
            ConfigurationSection itemSection = config.getConfigurationSection(key);
            if (itemSection == null || (!itemSection.contains("material") && !itemSection.contains("id"))) continue;
            loaded.add(loadItem(type, scopedConfiguration(itemSection), key));
        }
        if (!loaded.isEmpty()) plugin.getLogger().info("Loaded direct multi-item YAML: " + file.getName());
        return loaded;
    }

    private YamlConfiguration scopedConfiguration(ConfigurationSection section) {
        YamlConfiguration scoped = new YamlConfiguration();
        for (Map.Entry<String, Object> entry : section.getValues(true).entrySet()) {
            if (!(entry.getValue() instanceof ConfigurationSection)) scoped.set(entry.getKey(), entry.getValue());
        }
        return scoped;
    }

    private boolean isSingleItemRoot(YamlConfiguration config) {
        return config.contains("id") || config.contains("material") || config.contains("attributes")
                || config.contains("description") || config.contains("fskill") || config.contains("rskill") || config.contains("lskill");
    }

    private boolean isItemConfigFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".txt") || lower.endsWith(".yml") || lower.endsWith(".yaml");
    }

    private String stripItemConfigExtension(String name) {
        return name.replaceFirst("(?i)\\.(txt|ya?ml)$", "");
    }

    private RpgItem loadItem(RpgItemType type, YamlConfiguration config, String fallbackId) {
        String id = config.getString("id", fallbackId);
        Map<String, RandomAttributeRange> randomAttributes = loadRandomAttributes(config);
        String displayName = config.getString("display-name", "&f" + id);
        String material = config.getString("material", "STONE");
        // 注册表声明的武器类型（技能 supported-weapons 按它匹配）。中文/英文均可，例如
        // 剑/SWORD、法杖/STAFF、弓/BOW、弩/CROSSBOW、匕首/DAGGER；留空则回退到材质推断。
        String weaponType = config.getString("weapon-type", "").trim();
        double attack = config.getDouble("attributes.Attack", 0);
        double attackPercent = config.getDouble("attributes.AttackPercent", config.getDouble("attributes.Attack%", 0));
        double armor = config.getDouble("attributes.Armor", 0);
        double armorPercent = config.getDouble("attributes.ArmorPercent", config.getDouble("attributes.Armor%", 0));
        double critChance = config.getDouble("attributes.CritChance", 0);
        double critDamage = config.getDouble("attributes.CritDamage", 0);
         double critDamagePercent = config.getDouble("attributes.CritDamagePercent", config.getDouble("attributes.CritDamage%", 0));
        double attackSpeed = config.getDouble("attributes.AttackSpeed", 0);
        double health = config.getDouble("attributes.Health", 0);
        double vitality = parsePercent(config, "attributes.Vitality");
        double recovery = config.getDouble("attributes.Recovery", 0);
        double recoveryPercent = Math.min(100, Math.max(0, config.getDouble("attributes.RecoveryPercent", config.getDouble("attributes.Recovery%", 0))));
        double lifeSteal = config.getDouble("attributes.LifeSteal", 0);
        double lifeStealPercent = config.getDouble("attributes.LifeStealPercent", config.getDouble("attributes.LifeSteal%", 0));
        double magicDamage = config.getDouble("attributes.MagicDamage", 0);
        double magicDamagePercent = config.getDouble("attributes.MagicDamagePercent", config.getDouble("attributes.MagicDamage%", 0));
        double bowDamage = config.getDouble("attributes.BowDamage", 0);
        double bowDamagePercent = config.getDouble("attributes.BowDamagePercent", config.getDouble("attributes.BowDamage%", 0));
        double exDraw = config.getDouble("attributes.EXDraw", config.getDouble("attributes.ExDraw", 0));
        FixConfig fix = parseFixConfig(config);
        double fixPerSecond = fix.amount();
        int extraDurability = config.getInt("attributes.ExDur", 0);
        boolean breakLimit = config.getBoolean("attributes.BreakLimit", config.getBoolean("BreakLimit", false));
        double envArmor = config.getDouble("attributes.EnvArmor", 0);
        double envArmorPercent = config.getDouble("attributes.EnvArmorPercent", config.getDouble("attributes.EnvArmor%", 0));
        double magicArmor = config.getDouble("attributes.MagicArmor", 0);
        double magicArmorPercent = config.getDouble("attributes.MagicArmorPercent", config.getDouble("attributes.MagicArmor%", 0));
        double resistance = config.getDouble("attributes.Resistance", 0);
        double bowArmor = config.getDouble("attributes.BowArmor", 0);
        double bowArmorPercent = config.getDouble("attributes.BowArmorPercent", config.getDouble("attributes.BowArmor%", 0));
        double critArmor = config.getDouble("attributes.CritArmor", 0);
        double critArmorPercent = config.getDouble("attributes.CritArmorPercent", config.getDouble("attributes.CritArmor%", 0));
        double evasion = config.getDouble("attributes.Evasion", 0);
        double armorPen = config.getDouble("attributes.ArmorPen", 0);
        double trueDamage = config.getDouble("attributes.TrueDamage", 0);
        double backstrikeDamage = config.getDouble("attributes.BackstrikeDamage", 0);
        double backstrikeDamagePercent = config.getDouble("attributes.BackstrikeDamagePercent", config.getDouble("attributes.BackstrikeDamage%", 0));
        double assDamage = config.getDouble("attributes.AssDamage", 0);
        double assDamagePercent = config.getDouble("attributes.AssDamagePercent", config.getDouble("attributes.AssDamage%", 0));
        double pctDamage = parsePercent(config, "attributes.PctDamage");
        double pstDamage = parsePercent(config, "attributes.PstDamage");
        double attackRange = config.getDouble("attributes.AttackRange", 0);
        double parry = config.getDouble("attributes.Parry", 0);
        double counterAttack = config.getDouble("attributes.CounterAttack", 0);
        double dodge = config.getDouble("attributes.Dodge", 0);
        double block = config.getDouble("attributes.Block", 0);
        double determination = parsePercent(config, "attributes.Determination");
        double cad = parsePercent(config, "attributes.CAD");
        double iceDamage = config.getDouble("attributes.IceDamage", 0);
        double iceDamagePercent = config.getDouble("attributes.IceDamagePercent", config.getDouble("attributes.IceDamage%", 0));
        double fireDamage = config.getDouble("attributes.FireDamage", 0);
        double fireDamagePercent = config.getDouble("attributes.FireDamagePercent", config.getDouble("attributes.FireDamage%", 0));
        double lightDamage = config.getDouble("attributes.LightDamage", 0);
        double lightDamagePercent = config.getDouble("attributes.LightDamagePercent", config.getDouble("attributes.LightDamage%", 0));
        double darkDamage = config.getDouble("attributes.DarkDamage", 0);
        double darkDamagePercent = config.getDouble("attributes.DarkDamagePercent", config.getDouble("attributes.DarkDamage%", 0));
        double iceArmor = config.getDouble("attributes.IceArmor", 0);
        double iceArmorPercent = config.getDouble("attributes.IceArmorPercent", config.getDouble("attributes.IceArmor%", 0));
        double fireArmor = config.getDouble("attributes.FireArmor", 0);
        double fireArmorPercent = config.getDouble("attributes.FireArmorPercent", config.getDouble("attributes.FireArmor%", 0));
        double lightArmor = config.getDouble("attributes.LightArmor", 0);
        double lightArmorPercent = config.getDouble("attributes.LightArmorPercent", config.getDouble("attributes.LightArmor%", 0));
        double darkArmor = config.getDouble("attributes.DarkArmor", 0);
        double darkArmorPercent = config.getDouble("attributes.DarkArmorPercent", config.getDouble("attributes.DarkArmor%", 0));
        double farmingSpeed = config.getDouble("attributes.FarmingSpeed", 0);
        double miningSpeed = config.getDouble("attributes.MiningSpeed", 0);
        double forestSpeed = config.getDouble("attributes.ForestSpeed", 0);
        double farmingFortune = config.getDouble("attributes.FarmingFortune", 0);
        double miningFortune = config.getDouble("attributes.MiningFortune", 0);
        double forestFortune = config.getDouble("attributes.ForestFortune", 0);
        // 手持工具给的采集等级加成（挖掘等级门槛用）。WoodcuttingLevel 是 ForestLevel 的别名。
        double farmingLevel = config.getDouble("attributes.FarmingLevel", 0);
        double miningLevel = config.getDouble("attributes.MiningLevel", 0);
        double forestLevel = config.getDouble("attributes.ForestLevel",
                config.getDouble("attributes.WoodcuttingLevel", 0));
        int sockets = Math.max(0, config.getInt("sockets", 0));
        int level = config.getInt("requirements.Level", config.getInt("Level", 0));
        boolean deputy = config.getBoolean("Deputy", config.getBoolean("attributes.Deputy", false));
        boolean soulbound = config.getBoolean("Soulbound", false);
        boolean consumables = config.getBoolean("Consumables", config.getBoolean("attributes.Consumables", false));
        boolean unbreakable = config.getBoolean("unbreakable", false);
        boolean hideEnchantments = config.getBoolean("hide-enchantments", plugin.getConfig().getBoolean("rpgitem.hide-enchantments-default", false));
        int itemVersion = Math.max(1, config.getInt("item-version", config.getInt("version", 1)));
        int customModelData = config.getInt("custom-model-data", config.getInt("CustomModelData", 0));
        String leatherColor = config.getString("leather-color", config.getString("color", "160,101,64"));
        String accessoryType = config.getString("accessory-type", config.getString("AccessoryType", "ring"));
        List<String> lore = config.getStringList("description");
        ParticleConfig particles = loadParticleConfig(config, "particles");
        ParticleConfig slashParticles = loadParticleConfig(config, "slash-particles");
        double slashParticleLength = Math.max(0, config.getDouble("slash-particles.length", plugin.getConfig().getDouble("slash-particles.length", 1.2)));
        ParticleConfig projectileTrailParticles = loadTrailParticleConfig(config, "projectile-trail-particles", "128,128,128");
        ParticleConfig projectileHitParticles = loadParticleConfig(config, "projectile-hit-particles");
        SoundConfig sound = loadSoundConfig(config, "sounds", plugin.getConfig().getBoolean("sounds.enabled", true), "ENTITY_PLAYER_ATTACK_STRONG", 1.0F, 1.0F);
        SoundConfig projectileTrailSound = loadSoundConfig(config, "projectile-trail-sound", false, "ENTITY_WITHER_SHOOT", 0.25F, 1.2F);
        SoundConfig projectileHitSound = loadSoundConfig(config, "projectile-hit-sound", false, "ENTITY_FIREWORK_ROCKET_BLAST", 1.0F, 1.0F);
        List<BuffConfig> buffs = loadBuffs(config, "buffs");
        List<HitBuffConfig> hitBuffs = loadHitBuffs(config);
        FSkillConfig fSkill = loadSkill(config, "fskill");
        FSkillConfig sfSkill = loadSkill(config, "sfskill");
        FSkillConfig rSkill = loadSkill(config, "rskill");
        FSkillConfig lSkill = loadSkill(config, "lskill");
        FSkillConfig slSkill = loadSkill(config, "slskill");
        FSkillConfig srSkill = loadSkill(config, "srskill");
        FSkillConfig qSkill = loadSkill(config, "qskill");
        FSkillConfig sqSkill = loadSkill(config, "sqskill");
        Map<String, Integer> enchantments = new HashMap<>();
        for (String enchantmentLine : config.getStringList("enchantments")) {
            String[] parts = enchantmentLine.split(":", 2);
            if (parts.length == 2) enchantments.put(parts[0].trim(), parseInt(parts[1].trim(), 1));
        }
        ConfigurationSection enchantmentSection = config.getConfigurationSection("enchantments");
        if (enchantmentSection != null) {
            for (String enchantmentName : enchantmentSection.getKeys(false)) {
                enchantments.put(enchantmentName, enchantmentSection.getInt(enchantmentName, 1));
            }
        }
        List<VanillaAttributeConfig> vanillaAttributes = loadVanillaAttributes(config);
        return new RpgItem(id, type, displayName, material, weaponType, attack, attackPercent, armor, armorPercent, critChance, critDamage, critDamagePercent, attackSpeed, health, vitality, recovery, recoveryPercent, lifeSteal, lifeStealPercent, magicDamage, magicDamagePercent, bowDamage, bowDamagePercent, exDraw, fixPerSecond, fix.intervalSeconds(), extraDurability, breakLimit, envArmor, envArmorPercent, magicArmor, magicArmorPercent, resistance, bowArmor, bowArmorPercent, critArmor, critArmorPercent, evasion, armorPen, trueDamage, backstrikeDamage, backstrikeDamagePercent, assDamage, assDamagePercent, pctDamage, pstDamage, attackRange, parry, counterAttack, dodge, block, determination, cad, iceDamage, iceDamagePercent, fireDamage, fireDamagePercent, lightDamage, lightDamagePercent, darkDamage, darkDamagePercent, iceArmor, iceArmorPercent, fireArmor, fireArmorPercent, lightArmor, lightArmorPercent, darkArmor, darkArmorPercent, level, deputy, soulbound, consumables, unbreakable, hideEnchantments, itemVersion, customModelData, leatherColor, accessoryType, lore, enchantments, randomAttributes, vanillaAttributes, particles, slashParticles, slashParticleLength, projectileTrailParticles, projectileHitParticles, sound, projectileTrailSound, projectileHitSound, buffs, hitBuffs, fSkill, sfSkill, rSkill, lSkill, slSkill, srSkill, qSkill, sqSkill, farmingSpeed, miningSpeed, forestSpeed, farmingFortune, miningFortune, forestFortune, farmingLevel, miningLevel, forestLevel, sockets);
    }

    private Map<String, RandomAttributeRange> loadRandomAttributes(YamlConfiguration config) {
        Map<String, RandomAttributeRange> ranges = new HashMap<>();
        ConfigurationSection section = config.getConfigurationSection("attributes");
        if (section != null) {
            for (String rawKey : section.getKeys(false)) {
                Object rawValue = section.get(rawKey);
                if (rawValue == null) continue;
                String text = rawValue.toString().trim();
                Matcher matcher = RANDOM_RANGE_PATTERN.matcher(text);
                if (!matcher.matches()) continue;
                String key = canonicalAttributeKey(rawKey, matcher.group(3) != null);
                if (!SUPPORTED_LORE_ATTRIBUTES.contains(key) && !key.equals("exdur")) continue;
                RandomAttributeRange range = new RandomAttributeRange(parseInt(matcher.group(1), 0), parseInt(matcher.group(2), 0));
                ranges.put(key, range);
            }
        }
        for (String loreLine : config.getStringList("description")) {
            Matcher matcher = LORE_ATTRIBUTE_PATTERN.matcher(stripLoreColors(loreLine));
            if (!matcher.matches()) continue;
            String key = canonicalAttributeKey(matcher.group(1), matcher.group(4) != null);
            if (ranges.containsKey(key) || hasConfiguredAttribute(section, key)) continue;
            try {
                int minimum = (int) Math.round(Double.parseDouble(matcher.group(2)));
                int maximum = matcher.group(3) == null ? minimum : (int) Math.round(Double.parseDouble(matcher.group(3)));
                if (SUPPORTED_LORE_ATTRIBUTES.contains(key) || key.equals("exdur")) ranges.put(key, new RandomAttributeRange(minimum, maximum));
            } catch (NumberFormatException ignored) {
            }
        }
        return Map.copyOf(ranges);
    }

    private boolean hasConfiguredAttribute(ConfigurationSection section, String canonicalKey) {
        if (section == null) return false;
        for (String key : section.getKeys(false)) {
            if (canonicalAttributeKey(key, key.endsWith("%" )).equals(canonicalKey)) return true;
        }
        return false;
    }

    private static String canonicalAttributeKey(String rawKey, boolean percentSuffix) {
        if (rawKey.toLowerCase(Locale.ROOT).startsWith("fix/")) return "fix";
        return normalizeLoreAttribute(rawKey, percentSuffix);
    }

    private List<VanillaAttributeConfig> loadVanillaAttributes(YamlConfiguration config) {
        ConfigurationSection section = config.getConfigurationSection("vanilla-attributes");
        if (section == null) section = config.getConfigurationSection("minecraft-attributes");
        if (section == null) return List.of();
        List<VanillaAttributeConfig> attributes = new ArrayList<>();
        collectVanillaAttributes(section, "", attributes);
        return List.copyOf(attributes);
    }

    private void collectVanillaAttributes(ConfigurationSection section, String prefix, List<VanillaAttributeConfig> attributes) {
        for (Map.Entry<String, Object> entry : section.getValues(false).entrySet()) {
            String attributeName = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof ConfigurationSection node) {
                Map<String, Object> values = node.getValues(false);
                if (values.containsKey("amount") || values.containsKey("value")) {
                    double amount = numberValue(values.getOrDefault("amount", values.get("value")), 0);
                    String operation = String.valueOf(values.getOrDefault("operation", "ADD_NUMBER"));
                    String slot = String.valueOf(values.getOrDefault("slot", "AUTO"));
                    attributes.add(new VanillaAttributeConfig(attributeName, amount, operation, slot));
                } else {
                    collectVanillaAttributes(node, attributeName, attributes);
                }
            } else {
                attributes.add(new VanillaAttributeConfig(attributeName, numberValue(entry.getValue(), 0), "ADD_NUMBER", "AUTO"));
            }
        }
    }

    private double numberValue(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        try {
            return value == null ? fallback : Double.parseDouble(value.toString());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public RpgItem loadIndependentSkill(String id, String displayName, String material, String fileName) {
        return loadIndependentSkill(id, displayName, material, fileName, Map.of());
    }

    /**
     * Same as {@link #loadIndependentSkill(String, String, String, String)} but applies skill-level
     * overrides on top of the file before parsing.
     *
     * <p>The overrides are {@code 字段路径 -> 数值} pairs computed from the player's skill level.
     * Patching the config (instead of teaching every {@code load*Skill} method about levels) means
     * <b>any</b> numeric field of <b>any</b> skill can be leveled, including skills added later,
     * with no loader changes. The level is part of the cache key, so each level is parsed once and
     * casting stays allocation-free.
     */
    public RpgItem loadIndependentSkill(String id, String displayName, String material, String fileName,
                                        Map<String, Double> levelOverrides) {
        String cacheKey = id + "\u0000" + displayName + "\u0000" + material + "\u0000" + fileName
                + "\u0000" + levelOverrides;
        if (independentSkillCache.containsKey(cacheKey)) return independentSkillCache.get(cacheKey);
        RpgItem loaded = parseIndependentSkill(id, displayName, material, fileName, levelOverrides);
        independentSkillCache.put(cacheKey, loaded);
        if (loaded != null) independentSkillById.put(id.toLowerCase(Locale.ROOT), loaded);
        return loaded;
    }

    /** 技能文件的内容会被复制进这些触发段，所以等级补丁要对每一段都打一遍。 */
    private static final String[] SKILL_TRIGGER_KEYS =
            {"qskill", "sqskill", "fskill", "sfskill", "rskill", "srskill", "lskill", "slskill"};

    /**
     * 技能文件里只有这些键是「物品级」的：它们描述技能书本身（外观、描述、属性、附魔……），
     * 必须出现在 {@link #loadItem} 读取的**顶层**，而不是被缩进两层埋进触发段里。
     * <p>
     * 背景：技能文件被整体缩进后复制进 {@code qskill}/{@code fskill} 等 8 个触发段，
     * 而 {@code loadItem} 是在顶层读 {@code description} 的。原先只手动把
     * {@code id}/{@code display-name}/{@code material} 三个键拷到顶层，
     * 于是<b>技能书的自定义描述、custom-model-data、附魔、皮革颜色等全部静默失效</b>
     * ——玩家写了 {@code description} 却看不到任何变化，因为顶层的 {@code description} 是空的。
     * <p>
     * 这里按<b>白名单</b>提升：只有下面这些明确的物品级键会被复制到顶层。刻意不用
     * 「顶层缺失就补」的通用做法，因为技能段（{@code phantom-blade}、{@code shortbow} 等）
     * 同样不在顶层，通用做法会把技能段也搬到顶层，污染物品配置。
     */
    private static final String[] SKILL_ITEM_FIELDS = {
            "description", "custom-model-data", "CustomModelData", "unbreakable", "hide-enchantments",
            "leather-color", "color", "deputy", "soulbound", "consumables", "accessory-type",
            "AccessoryType", "weapon-type", "enchantments", "random-attributes", "vanilla-attributes",
            "requirements", "Level", "item-version", "version",
            "particles", "slash-particles", "projectile-trail-particles", "projectile-hit-particles",
            "sounds", "projectile-trail-sound", "projectile-hit-sound", "buffs", "hit-buffs",
    };

    /**
     * 把技能文件里的物品级字段从触发段提升到顶层，让技能书能自定义描述、模型、附魔等。
     * <p>
     * 取值来源是第一个包含该键的触发段——8 个段内容完全相同（同一份文件缩进复制），
     * 所以取哪个都一样。已被调用方显式设置过的顶层键（{@code id}/{@code display-name}/
     * {@code material}）不会被覆盖，那些值可能来自 config.yml 的注册覆盖。
     */
    private void promoteSkillItemFields(YamlConfiguration config) {
        for (String field : SKILL_ITEM_FIELDS) {
            if (config.contains(field)) continue;
            for (String trigger : SKILL_TRIGGER_KEYS) {
                String path = trigger + "." + field;
                if (!config.contains(path)) continue;
                config.set(field, config.get(path));
                break;
            }
        }
    }

    private RpgItem parseIndependentSkill(String id, String displayName, String material, String fileName,
                                          Map<String, Double> levelOverrides) {
        File skillFile = new File(new File(plugin.getDataFolder(), "skills"), fileName);
        try {
            File skillsFolder = new File(plugin.getDataFolder(), "skills");
            if (!skillFile.getCanonicalFile().toPath().startsWith(skillsFolder.getCanonicalFile().toPath()) || !skillFile.isFile()) return null;
            String raw = Files.readString(skillFile.toPath(), StandardCharsets.UTF_8);
            String indented = raw.lines().map(line -> "  " + line).collect(java.util.stream.Collectors.joining("\n"));
            StringBuilder sections = new StringBuilder();
            for (String key : SKILL_TRIGGER_KEYS) {
                sections.append(key).append(":\n").append(indented).append('\n');
            }
            YamlConfiguration config = new YamlConfiguration();
            config.loadFromString(sections.toString());
            config.set("id", id);
            config.set("display-name", displayName);
            config.set("material", material);
            promoteSkillItemFields(config);
            applyLevelOverrides(config, levelOverrides);
            RpgItem loaded = loadItem(RpgItemType.WEAPON, config, id);
            skillLoadErrors.remove(fileName);
            return loaded;
        } catch (Exception exception) {
            String message = exception.getClass().getSimpleName() + ": " + exception.getMessage();
            skillLoadErrors.put(fileName, message);
            plugin.getLogger().warning("Cannot load independent skill item " + fileName + ": " + message);
            return null;
        }
    }

    /**
     * 把技能等级的成长覆盖写进配置的每个触发段。
     * <p>
     * 整数值写回整数（而不是 6.0），这样 yml 读出来还是干净的数字，也避免下游
     * 出现 "6.0 次挥砍" 这类显示噪声。
     */
    private static void applyLevelOverrides(YamlConfiguration config, Map<String, Double> levelOverrides) {
        if (levelOverrides == null || levelOverrides.isEmpty()) return;
        for (String trigger : SKILL_TRIGGER_KEYS) {
            for (Map.Entry<String, Double> entry : levelOverrides.entrySet()) {
                double value = entry.getValue();
                config.set(trigger + "." + entry.getKey(),
                        Math.abs(value - Math.rint(value)) < 1e-9 ? (Object) (long) Math.rint(value) : (Object) value);
            }
        }
    }

    /** @return the last load failure for a skills file, or null when it loaded fine. */
    public String skillLoadError(String fileName) {
        return fileName == null ? null : skillLoadErrors.get(fileName);
    }

    /** @return every .yml file inside the skills folder, sorted by name. */
    public List<String> listSkillFiles() {
        File[] files = new File(plugin.getDataFolder(), "skills")
                .listFiles(file -> file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) return List.of();
        return java.util.Arrays.stream(files).map(File::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    /** Parsed root config of a skills file, cached until the next reload. Never wrapped in a trigger node. */
    public YamlConfiguration skillFileConfig(String fileName) {
        if (fileName == null || fileName.isBlank()) return null;
        if (skillFileCache.containsKey(fileName)) return skillFileCache.get(fileName);
        YamlConfiguration parsed = null;
        File skillFile = new File(new File(plugin.getDataFolder(), "skills"), fileName);
        try {
            if (skillFile.getCanonicalFile().toPath()
                    .startsWith(new File(plugin.getDataFolder(), "skills").getCanonicalFile().toPath())
                    && skillFile.isFile()) {
                parsed = new YamlConfiguration();
                parsed.loadFromString(Files.readString(skillFile.toPath(), StandardCharsets.UTF_8));
            }
        } catch (Exception exception) {
            skillLoadErrors.put(fileName, exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
        skillFileCache.put(fileName, parsed);
        return parsed;
    }

    public FSkillConfig loadExternalSkill(String fileName) {
        File skillFile = new File(new File(plugin.getDataFolder(), "skills"), fileName);
        try {
            if (!skillFile.getCanonicalFile().toPath().startsWith(new File(plugin.getDataFolder(), "skills").getCanonicalFile().toPath()) || !skillFile.isFile()) return null;
            String raw = Files.readString(skillFile.toPath(), StandardCharsets.UTF_8);
            YamlConfiguration wrapped = new YamlConfiguration();
            String indented = raw.lines().map(line -> "  " + line).collect(java.util.stream.Collectors.joining("\n"));
            wrapped.loadFromString("lskill:\n" + indented);
            return loadSkill(wrapped, "lskill");
        } catch (Exception exception) {
            plugin.getLogger().warning("Cannot load external skill " + fileName + ": " + exception.getMessage());
            return null;
        }
    }

    private FSkillConfig loadFSkill(YamlConfiguration config) {
        return loadSkill(config, "fskill");
    }

    private FSkillConfig loadSkill(YamlConfiguration config, String skillPath) {
        ConfigurationSection section = config.getConfigurationSection(skillPath);
        if (section != null) {
            String externalFile = section.getString("skill-file", "").trim();
            if (!externalFile.isBlank()) {
                File skillsFolder = new File(plugin.getDataFolder(), "skills");
                File skillFile = new File(skillsFolder, externalFile);
                try {
                    if (!skillFile.getCanonicalFile().toPath().startsWith(skillsFolder.getCanonicalFile().toPath())) {
                        plugin.getLogger().warning("Rejected skill file outside skills folder: " + externalFile);
                        section = null;
                    } else if (!skillFile.isFile()) {
                        plugin.getLogger().warning("Skill file not found: " + skillFile.getPath());
                        section = null;
                    } else {
                        String raw = Files.readString(skillFile.toPath(), StandardCharsets.UTF_8);
                        YamlConfiguration wrapped = new YamlConfiguration();
                        String indented = raw.lines()
                                .map(line -> "  " + line)
                                .collect(java.util.stream.Collectors.joining("\n"));
                        wrapped.loadFromString("lskill:\n" + indented);
                        config = wrapped;
                        skillPath = "lskill";
                        section = config.getConfigurationSection("lskill");
                    }
                } catch (Exception exception) {
                    plugin.getLogger().warning("Cannot load skill file " + skillFile.getPath() + ": " + exception.getMessage());
                    section = null;
                }
            }
        }
        if (section == null) return new FSkillConfig(0, 0, "", true, false, true, true, -1, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null, List.of(), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        double cooldown = section.getDouble("cooldown-seconds", plugin.getConfig().getDouble("fskill.default-cooldown-seconds", 5));
        double castTime = Math.max(0, section.getDouble("cast-time-seconds", 0));
        String cooldownName = section.getString("cooldown-name", section.getString("skill-name", section.getString("name", "")));
        boolean cooldownEnabled = section.getBoolean("cooldown-enabled", true);
        boolean attackSpeedCooldown = section.getBoolean("attack-speed-cooldown", false);
        boolean messagesEnabled = section.getBoolean("messages-enabled", true);
        boolean globalCooldown = section.getBoolean("global-cooldown", true);
        double globalCooldownSeconds = section.contains("global-cooldown-seconds")
                ? Math.max(0, section.getDouble("global-cooldown-seconds")) : -1;
        List<ParticleSkill> particles = new ArrayList<>();
        ConfigurationSection particleSection = section.getConfigurationSection("particles");
        if (particleSection != null) for (String key : particleSection.getKeys(false)) {
            String path = skillPath + ".particles." + key;
            particles.add(new ParticleSkill(loadParticleConfig(config, path), particleSection.getBoolean(key + ".repeating", false), particleSection.getDouble(key + ".duration-seconds", 3), particleSection.getDouble(key + ".interval-seconds", 1)));
        }
        List<SwitchSkill> switches = new ArrayList<>();
        ConfigurationSection switchSection = section.getConfigurationSection("switches");
        if (switchSection != null) for (String key : switchSection.getKeys(false)) {
            ConfigurationSection node = switchSection.getConfigurationSection(key);
            if (node != null) switches.add(new SwitchSkill(node.getString("rpgitem", ""), node.getString("material", ""), node.getBoolean("timed", false), node.getDouble("duration-seconds", 5), node.getBoolean("switched-item-cooldown", true)));
        }
        List<BuffSkill> buffs = new ArrayList<>();
        ConfigurationSection buffSection = section.getConfigurationSection("buffs");
        if (buffSection != null) for (String key : buffSection.getKeys(false)) {
            ConfigurationSection node = buffSection.getConfigurationSection(key);
            if (node != null) buffs.add(new BuffSkill(node.getString("type", key), node.getInt("amplifier", 0), node.getDouble("duration-seconds", 5)));
        }
        List<DamageSkill> damages = new ArrayList<>();
        ConfigurationSection damageSection = section.getConfigurationSection("damages");
        if (damageSection != null) for (String key : damageSection.getKeys(false)) {
            ConfigurationSection node = damageSection.getConfigurationSection(key);
            if (node != null) {
                List<String> percentSources = weaponPercentSources(node);
                damages.add(new DamageSkill(node.getString("mode", "wave"), node.getDouble("damage", 0), node.getDouble("weapon-percent", 100), node.getDouble("length", 6), node.getDouble("radius", 2), node.getDouble("height", 5), node.getBoolean("repeating", false), node.getDouble("duration-seconds", 3), node.getDouble("interval-seconds", 1), node.getBoolean("hit-once-per-cast", true), node.getBoolean("pierce", true), node.getBoolean("pierce-blocks", true), node.getInt("spread-count", 1), node.getDouble("spread-angle", 30), node.getBoolean("shotgun", false), node.getDouble("spread-radius", 1.5), node.getString("damage-type", "PHYSICAL"), percentSources, node.getBoolean("can-crit", false), loadParticleConfig(config, skillPath + ".damages." + key + ".particles"), loadSoundConfig(config, skillPath + ".damages." + key + ".sound", false, "ENTITY_PLAYER_ATTACK_SWEEP", 1.0F, 1.0F), Math.max(0, node.getDouble("knockback", 0))));
            }
        }
        List<SoundSkill> sounds = new ArrayList<>();
        ConfigurationSection soundSection = section.getConfigurationSection("sounds");
        if (soundSection != null) for (String key : soundSection.getKeys(false)) {
            ConfigurationSection node = soundSection.getConfigurationSection(key);
            if (node != null) sounds.add(new SoundSkill(loadSoundConfig(config, skillPath + ".sounds." + key, true, "ENTITY_PLAYER_LEVELUP", 1.0F, 1.0F), node.getBoolean("repeating", false), node.getDouble("duration-seconds", 3), node.getDouble("interval-seconds", 1)));
        }
        // Also accept one direct sound node for simple skills: fskill.sound / rskill.sound / lskill.sound.
        ConfigurationSection directSound = section.getConfigurationSection("sound");
        if (directSound != null) {
            sounds.add(new SoundSkill(loadSoundConfig(config, skillPath + ".sound", true, "ENTITY_PLAYER_LEVELUP", 1.0F, 1.0F),
                    directSound.getBoolean("repeating", false),
                    directSound.getDouble("duration-seconds", 3),
                    directSound.getDouble("interval-seconds", 1)));
        }
        List<BroadcastSkill> broadcasts = new ArrayList<>();
        ConfigurationSection broadcastSection = section.getConfigurationSection("broadcasts");
        if (broadcastSection != null) for (String key : broadcastSection.getKeys(false)) {
            ConfigurationSection node = broadcastSection.getConfigurationSection(key);
            if (node != null) broadcasts.add(new BroadcastSkill(node.getString("text", ""), node.getString("color", "&e"), node.getDouble("duration-seconds", 3)));
        }
        List<MessageSkill> messages = new ArrayList<>();
        ConfigurationSection messageSection = section.getConfigurationSection("messages");
        if (messageSection != null) for (String key : messageSection.getKeys(false)) {
            ConfigurationSection node = messageSection.getConfigurationSection(key);
            if (node != null) messages.add(new MessageSkill(node.getStringList("texts"), node.getBoolean("self-only", true), node.getDouble("radius", 12), node.getBoolean("repeating", false), node.getDouble("duration-seconds", 3), node.getDouble("interval-seconds", 1)));
        }
        List<CommandSkill> commands = new ArrayList<>();
        ConfigurationSection commandSection = section.getConfigurationSection("commands");
        if (commandSection != null) for (String key : commandSection.getKeys(false)) {
            ConfigurationSection node = commandSection.getConfigurationSection(key);
            if (node != null) {
                List<String> commandLines = node.getStringList("commands");
                if (commandLines.isEmpty() && node.isString("command")) commandLines = List.of(node.getString("command", ""));
                commands.add(new CommandSkill(commandLines, node.getBoolean("as-op", true)));
            }
        }
        List<ChantSkill> chants = new ArrayList<>();
        ConfigurationSection chantSection = section.getConfigurationSection("chants");
        if (chantSection != null) for (String key : chantSection.getKeys(false)) {
            ConfigurationSection node = chantSection.getConfigurationSection(key);
            if (node == null) continue;
            String chantPath = skillPath + ".chants." + key;
            ParticleConfig defaultParticle = loadParticleConfig(config, chantPath + ".particles");
            SoundConfig defaultSound = loadSoundConfig(config, chantPath + ".sound", false, "BLOCK_BEACON_POWER_SELECT", 1.0F, 1.0F);
            List<ChantStage> stages = new ArrayList<>();
            ConfigurationSection stageSection = node.getConfigurationSection("stages");
            if (stageSection != null) {
                for (String stageKey : stageSection.getKeys(false)) {
                    ConfigurationSection stage = stageSection.getConfigurationSection(stageKey);
                    if (stage == null) continue;
                    String stagePath = chantPath + ".stages." + stageKey;
                    ParticleConfig particle = stage.contains("particles") ? loadParticleConfig(config, stagePath + ".particles") : defaultParticle;
                    SoundConfig sound = stage.contains("sound") ? loadSoundConfig(config, stagePath + ".sound", false, "BLOCK_BEACON_POWER_SELECT", 1.0F, 1.0F) : defaultSound;
                     List<BuffSkill> stageBuffs = new ArrayList<>();
                     ConfigurationSection stageBuffSection = stage.getConfigurationSection("buffs");
                     if (stageBuffSection != null) for (String buffKey : stageBuffSection.getKeys(false)) {
                         ConfigurationSection buff = stageBuffSection.getConfigurationSection(buffKey);
                         if (buff != null) stageBuffs.add(new BuffSkill(buff.getString("type", buffKey), buff.getInt("amplifier", 0), buff.getDouble("duration-seconds", 5)));
                     }
                    stages.add(new ChantStage(stage.getString("text", ""), particle, sound, stageBuffs));
                }
            } else {
                for (String text : node.getStringList("texts")) stages.add(new ChantStage(text, defaultParticle, defaultSound));
            }
            String releaseAt = node.getString("release-at", "end").toLowerCase(Locale.ROOT);
            chants.add(new ChantSkill(
                    Math.max(0.05, node.getDouble("interval-seconds", 1)),
                    defaultParticle, defaultSound, stages,
                    releaseAt.equals("start") || releaseAt.equals("both"),
                    !releaseAt.equals("start") || releaseAt.equals("both"),
                    node.getBoolean("interrupt-on-item-switch", true)));
        }
        RollSkill roll = null;
        ConfigurationSection rollSection = section.getConfigurationSection("roll");
        if (rollSection != null) roll = new RollSkill(Math.max(0, rollSection.getDouble("distance", 1)), Math.max(1, rollSection.getInt("duration-ticks", 8)), Math.max(0, rollSection.getInt("invulnerability-ticks", 6)), loadParticleConfig(config, skillPath + ".roll.particle"), loadSoundConfig(config, skillPath + ".roll.sound", false, "ENTITY_PLAYER_ATTACK_SWEEP", 1.0F, 1.0F));
        DashSkill dash = null;
        ConfigurationSection dashSection = section.getConfigurationSection("dash");
        if (dashSection != null) dash = new DashSkill(Math.max(0, dashSection.getDouble("distance", 1)), Math.max(1, dashSection.getInt("duration-ticks", 8)), Math.max(0, dashSection.getInt("invulnerability-ticks", 6)), loadParticleConfig(config, skillPath + ".dash.particle"), loadSoundConfig(config, skillPath + ".dash.sound", false, "ENTITY_PLAYER_ATTACK_SWEEP", 1.0F, 1.0F));
        List<RangeBuffSkill> rangeBuffs = new ArrayList<>();
        ConfigurationSection rangeSection = section.getConfigurationSection("range-buffs");
        if (rangeSection != null) for (String key : rangeSection.getKeys(false)) {
            ConfigurationSection node = rangeSection.getConfigurationSection(key);
            if (node == null) continue;
            List<BuffSkill> effects = new ArrayList<>();
            ConfigurationSection effectSection = node.getConfigurationSection("effects");
            if (effectSection != null) for (String effect : effectSection.getKeys(false)) {
                ConfigurationSection effectNode = effectSection.getConfigurationSection(effect);
                if (effectNode != null) effects.add(new BuffSkill(effect, effectNode.getInt("amplifier", 0), effectNode.getDouble("duration-seconds", 5)));
            }
            rangeBuffs.add(new RangeBuffSkill(Math.max(0.5, node.getDouble("radius", 6)), Math.max(0.5, node.getDouble("height", 3)), node.getString("target", "FRIENDLY"), node.getBoolean("include-self", true), Math.max(0.2, node.getDouble("refresh-seconds", 4)), effects, loadParticleConfig(config, skillPath + ".range-buffs." + key + ".particle")));
        }
        ShortbowSkill shortbow = loadShortbowSkill(config, skillPath + ".shortbow");
        DamageSkill dimensionalSlash = loadDimensionalSlash(config, skillPath + ".dimensional-slash");
        AttributeBoostSkill attributeBoost = loadAttributeBoost(config, skillPath + ".attribute-boost");
        SkillChain chain = loadSkillChain(config, skillPath + ".chain");
         ShockwaveSkill shockwave = loadShockwaveSkill(config, skillPath + ".shockwave");
         SweepSkill sweep = loadSweepSkill(config, skillPath + ".sweep");
         SwingSkill swing = loadSwingSkill(config, skillPath + ".swing");
         PhantomBladeSkill phantomBlade = loadPhantomBladeSkill(config, skillPath + ".phantom-blade");
         SwordAuraSkill swordAura = loadSwordAuraSkill(config, skillPath + ".sword-aura");
         CrossSlashSkill crossSlash = loadCrossSlashSkill(config, skillPath + ".cross-slash");
         MagicCircleSkill magicCircle = loadMagicCircleSkill(config, skillPath + ".magic-circle");
         GateOfBabylonSkill gateOfBabylon = loadGateOfBabylonSkill(config, skillPath + ".gate-of-babylon");
         BackstepSkill backstep = loadBackstepSkill(config, skillPath + ".backstep");
         UppercutSkill uppercut = loadUppercutSkill(config, skillPath + ".uppercut");
         LeapStrikeSkill leapStrike = loadLeapStrikeSkill(config, skillPath + ".leap-strike");
        return new FSkillConfig(cooldown, castTime, cooldownName == null ? "" : cooldownName.trim(), cooldownEnabled, attackSpeedCooldown,
                messagesEnabled, globalCooldown, globalCooldownSeconds, particles, switches, buffs, damages,
                sounds, broadcasts, messages, commands, chants, roll, dash, rangeBuffs, shortbow, dimensionalSlash, attributeBoost, chain, shockwave, sweep, swing, phantomBlade, swordAura, crossSlash, magicCircle, gateOfBabylon,
                backstep, uppercut, leapStrike,
                loadParticleConfig(config, skillPath + ".dimensional-slash.line-particle", "80,140,255"));
    }

    /**
     * 后撤：与突进同一套位移参数（distance / duration-ticks / invulnerability-ticks / particle / sound），
     * 区别只在方向固定向后，见 {@code FSkills.runBackstepSkill}。
     */
    private BackstepSkill loadBackstepSkill(YamlConfiguration config, String path) {
        ConfigurationSection node = config.getConfigurationSection(path);
        if (node == null) return null;
        return new BackstepSkill(Math.max(0, node.getDouble("distance", 5)),
                Math.max(1, node.getInt("duration-ticks", 8)),
                Math.max(0, node.getInt("invulnerability-ticks", 6)),
                loadParticleConfig(config, path + ".particle"),
                loadSoundConfig(config, path + ".sound", false, "ENTITY_PLAYER_ATTACK_SWEEP", 1.0F, 1.0F));
    }

    /**
     * 上挑：{@code launch} 是击飞初速度（越大越朝天上飞），其余与横扫同源的形状参数。
     */
    private UppercutSkill loadUppercutSkill(YamlConfiguration config, String path) {
        ConfigurationSection node = config.getConfigurationSection(path);
        if (node == null) return null;
        DamageSkill damage = loadShapeDamage(config, path, node, "PHYSICAL");
        return new UppercutSkill(damage,
                Math.max(0.5, node.getDouble("distance", 1.5)),
                Math.max(0.3, node.getDouble("radius", 1.6)),
                Math.max(0.5, node.getDouble("height", 2.2)),
                Math.max(0, node.getDouble("launch", 0.7)),
                loadParticleConfig(config, path + ".particle"),
                loadSoundConfig(config, path + ".sound", false, "ENTITY_PLAYER_ATTACK_SWEEP", 1.0F, 1.0F));
    }

    /**
     * 蹦山击：跳跃高度 / 俯冲角度 / 落地冲击波与爆炸。冲击波与爆炸各有自己的粒子段，
     * 因为「缩减后的震地粒子」和「落地小爆炸」是两个不同大小的效果。
     *
     * <p>音效分两段：{@code sound} = 起跳音，{@code impact-sound} = 落地音。
     * 没写 {@code impact-sound} 时沿用 {@code sound}（旧配置不改也能用）。
     */
    private LeapStrikeSkill loadLeapStrikeSkill(YamlConfiguration config, String path) {
        ConfigurationSection node = config.getConfigurationSection(path);
        if (node == null) return null;
        DamageSkill damage = loadShapeDamage(config, path, node, "PHYSICAL");
        SoundConfig launchSound = loadSoundConfig(config, path + ".sound", false, "ENTITY_GENERIC_EXPLODE", 1.0F, 1.0F);
        SoundConfig impactSound = config.getConfigurationSection(path + ".impact-sound") == null
                ? launchSound
                : loadSoundConfig(config, path + ".impact-sound", false, "ENTITY_GENERIC_EXPLODE", 1.0F, 1.0F);
        return new LeapStrikeSkill(damage,
                Math.max(0.5, node.getDouble("leap-height", 5)),
                Math.max(1, Math.min(89, node.getDouble("dive-angle-degrees", 45))),
                Math.max(0.5, node.getDouble("impact-radius", 3)),
                Math.max(0, node.getDouble("knock-up", 0.35)),
                Math.max(0.2, node.getDouble("explosion-radius", 1.5)),
                loadParticleConfig(config, path + ".shockwave-particle"),
                loadParticleConfig(config, path + ".explosion-particle"),
                launchSound, impactSound);
    }

    /**
     * Weapon appearances used when {@code weapon-materials} is missing, empty, or entirely invalid.
     * A typo in one material name should not silently turn the whole skill into a no-op, so the pool
     * falls back to this list and the bad entries are logged instead.
     * <p>
     * Ranged weapons are deliberately absent: a bow or crossbow sprite rotated to point along a flight
     * line reads as a floating plank, not as a thrown weapon, and the treasury theme is melee blades
     * anyway. Gold entries appear several times because the pool is weighted by repetition - see
     * {@link #loadGateOfBabylonSkill}.
     */
    private static final List<String> DEFAULT_GATE_WEAPON_MATERIALS = List.of(
            "GOLDEN_SWORD", "GOLDEN_SWORD", "GOLDEN_SWORD", "GOLDEN_SWORD", "GOLDEN_SWORD",
            "GOLDEN_AXE", "GOLDEN_AXE", "GOLDEN_AXE", "GOLDEN_AXE",
            "NETHERITE_SWORD", "DIAMOND_SWORD", "IRON_SWORD", "STONE_SWORD", "WOODEN_SWORD",
            "NETHERITE_AXE", "DIAMOND_AXE", "IRON_AXE", "STONE_AXE",
            "TRIDENT", "MACE");

    /**
     * Gold, the gate's signature colour, used as the fallback dust colour for every particle config
     * under {@code gate-of-babylon}. Without this a missing section falls back to the generic grey
     * {@code 128,128,128}, so the effect renders in a colour nobody chose and the bug reads as a
     * rendering fault rather than as an absent config key.
     */
    private static final String GATE_DEFAULT_PARTICLE_COLOR = "255,215,0";

    /**
     * Parses the {@code gate-of-babylon} section. The damage block is read with the same
     * {@link #loadShapeDamage} helper as every other shape skill, so {@code damage},
     * {@code weapon-percent}, {@code damage-type}, {@code can-crit} and {@code weapon-percent-sources}
     * behave identically here and the skill damage lands in the normal resistance / crit pipeline.
     */
    private GateOfBabylonSkill loadGateOfBabylonSkill(YamlConfiguration config, String path) {
        ConfigurationSection node = config.getConfigurationSection(path);
        if (node == null) return null;
        boolean hasDamageConfig = node.contains("damage") || node.contains("weapon-percent")
                || node.contains("weapon-percent-source") || node.contains("weapon-percent-sources");
        boolean hasWeaponPercent = node.contains("weapon-percent") || node.contains("weapon-percent-source") || node.contains("weapon-percent-sources");
        double defaultWeaponPercent = hasWeaponPercent || node.getDouble("damage", 0) > 0 ? 100 : 0;
        DamageSkill damage = hasDamageConfig ? loadShapeDamage(config, path, node, "PHYSICAL", defaultWeaponPercent) : null;

        List<Material> materials = new ArrayList<>();
        List<String> configuredMaterials = node.getStringList("weapon-materials");
        if (configuredMaterials.isEmpty()) {
            String scalar = node.getString("weapon-materials", "");
            if (scalar != null && !scalar.isBlank()) configuredMaterials = List.of(scalar);
        }
        List<String> invalid = new ArrayList<>();
        for (String name : configuredMaterials) {
            Material material = name == null ? null : Material.matchMaterial(name.trim());
            if (material == null || material.isAir()) {
                invalid.add(String.valueOf(name));
                continue;
            }
            // Duplicates are kept on purpose: the list is a weighted pool, so listing GOLDEN_SWORD
            // five times makes gold five times as likely. Deduplicating here (as this used to do)
            // silently threw away the only weighting mechanism the skill has.
            materials.add(material);
        }
        if (!invalid.isEmpty()) {
            plugin.getLogger().warning("gate-of-babylon at " + path + " 的 weapon-materials 有无法识别的材质，已跳过："
                    + invalid + "（可用值示例：" + DEFAULT_GATE_WEAPON_MATERIALS + "）");
        }
        if (materials.isEmpty()) {
            plugin.getLogger().warning("gate-of-babylon at " + path + " 没有有效的 weapon-materials，已回退到内置武器外观池。");
            for (String name : DEFAULT_GATE_WEAPON_MATERIALS) {
                Material material = Material.matchMaterial(name);
                if (material != null && !material.isAir()) materials.add(material);
            }
        }

        String spawnModeRaw = node.getString("spawn-mode", "behind");
        if (!GateOfBabylonSkill.SpawnMode.recognized(spawnModeRaw)) {
            plugin.getLogger().warning("gate-of-babylon at " + path + " 的 spawn-mode=\"" + spawnModeRaw
                    + "\" 无法识别，已回退到 behind。可用值：behind / sky / mixed（也接受 back / air / both 等别名）。");
        }
        GateOfBabylonSkill.SpawnMode spawnMode = GateOfBabylonSkill.SpawnMode.parse(spawnModeRaw);

        String ripplePlaneRaw = node.getString("ripple.plane", "blade");
        if (!GateOfBabylonSkill.RipplePlane.recognized(ripplePlaneRaw)) {
            plugin.getLogger().warning("gate-of-babylon at " + path + " 的 ripple.plane=\"" + ripplePlaneRaw
                    + "\" 无法识别，已回退到 blade。可用值：blade（垂直于剑刃）/ horizontal（水平）。");
        }
        GateOfBabylonSkill.RipplePlane ripplePlane = GateOfBabylonSkill.RipplePlane.parse(ripplePlaneRaw);

        return new GateOfBabylonSkill(
                damage,
                Math.max(1, Math.min(GateOfBabylonSkill.MAX_WEAPONS, node.getInt("weapon-count", 16))),
                spawnMode,
                Math.max(1, node.getDouble("distance", 16)),
                Math.max(0, node.getDouble("spread-radius", 2.5)),
                Math.max(0, node.getDouble("spawn-distance", 4.5)),
                Math.max(0, node.getDouble("spawn-height", 4.5)),
                Math.max(0, Math.min(360, node.getDouble("spawn-fan-degrees", 180))),
                Math.max(1, node.getDouble("sky-height", 14)),
                Math.max(0, node.getDouble("sky-offset", 2.5)),
                Math.max(0.1, node.getDouble("fall-speed", 1.2)),
                Math.max(0.05, node.getDouble("linger-seconds", 2)),
                Math.max(0, node.getInt("launch-delay-ticks", 15)),
                Math.max(0.5, node.getDouble("impact-radius", 2.5)),
                Math.max(0.5, node.getDouble("impact-height", 2)),
                Math.max(1, node.getInt("max-hits-per-target", 3)),
                // Off by default: the gate's identity is "the volley passes through the world and
                // lands on the crosshair". A rain of arrows wants the opposite, so it opts in.
                node.getBoolean("collide-with-entities", false),
                Math.max(0.1, node.getDouble("collision-radius", 1.0)),
                // 剑贴图的剑刃在显示实体里是斜的，不先摆正，后面「瞄准落点」就整体偏一个固定角度。
                // 实测两轮的结果都指向 135°（左上），所以默认 -45 把它摆正到 +Y；留成配置是为了
                // 再偏 45° 时不必重新编译。
                node.getDouble("blade-tilt-degrees", -45),
                // The gate's whole palette is gold, so every one of its particle configs defaults to
                // gold rather than the generic grey: an un-updated yml then still looks right instead
                // of rendering the ring in a colour nobody chose.
                loadParticleConfig(config, path + ".ripple", GATE_DEFAULT_PARTICLE_COLOR),
                ripplePlane,
                materials,
                loadTrailParticleConfig(config, path + ".trail", GATE_DEFAULT_PARTICLE_COLOR),
                loadParticleConfig(config, path + ".impact-particle", GATE_DEFAULT_PARTICLE_COLOR),
                loadParticleConfig(config, path + ".explosion-particle", GATE_DEFAULT_PARTICLE_COLOR),
                loadParticleConfig(config, path + ".spawn-particle", GATE_DEFAULT_PARTICLE_COLOR),
                loadSoundConfig(config, path + ".sound", true, "ENTITY_ENDER_DRAGON_FLAP", 1, 1),
                loadSoundConfig(config, path + ".impact-sound", true, "ENTITY_GENERIC_EXPLODE", 1, 1));
    }

    private MagicCircleSkill loadMagicCircleSkill(YamlConfiguration config, String path) {
        ConfigurationSection node = config.getConfigurationSection(path);
        if (node == null) return null;
        boolean hasDamageConfig = node.contains("damage") || node.contains("weapon-percent")
                || node.contains("weapon-percent-source") || node.contains("weapon-percent-sources");
        boolean hasWeaponPercent = node.contains("weapon-percent") || node.contains("weapon-percent-source") || node.contains("weapon-percent-sources");
        double defaultWeaponPercent = hasWeaponPercent || node.getDouble("damage", 0) > 0 ? 100 : 0;
        DamageSkill damage = hasDamageConfig ? loadShapeDamage(config, path, node, "MAGIC", defaultWeaponPercent) : null;
        Material material;
        try { material = Material.valueOf(node.getString("center-material", "PAPER").toUpperCase(Locale.ROOT)); }
        catch (Exception ignored) { material = Material.PAPER; }
        List<BuffSkill> buffs = new ArrayList<>();
        ConfigurationSection buffSection = node.getConfigurationSection("buffs");
        if (buffSection != null) for (String key : buffSection.getKeys(false)) {
            ConfigurationSection buff = buffSection.getConfigurationSection(key);
            if (buff != null) buffs.add(new BuffSkill(buff.getString("type", key), Math.max(0, buff.getInt("amplifier", 0)), Math.max(0.05, buff.getDouble("duration-seconds", node.getDouble("buff-duration-seconds", 10)))));
        }
        Map<String, Double> attributeValues = new HashMap<>();
        ConfigurationSection attributeSection = node.getConfigurationSection("attribute-boost");
        if (attributeSection != null) for (String key : attributeSection.getKeys(false)) attributeValues.put(key.toLowerCase(Locale.ROOT).replace("_", ""), attributeSection.getDouble(key));
        return new MagicCircleSkill(
                damage,
                Math.max(0.5, node.getDouble("distance", 6)),
                Math.max(0.5, node.getDouble("radius", 3)),
                Math.max(0.5, node.getDouble("height", 2)),
                Math.max(0.05, node.getDouble("duration-seconds", 5)),
                Math.max(0.05, node.getDouble("interval-seconds", 1)),
                Math.max(1, node.getInt("burst-count", 3)),
                loadParticleConfig(config, path + ".particle"),
                loadParticleConfig(config, path + ".burst-particle"),
                loadSoundConfig(config, path + ".sound", false, "BLOCK_ENCHANTMENT_TABLE_USE", 1, 1),
                node.getBoolean("use-armor-stand", true), material,
                node.getString("target", "ENEMY"), node.getBoolean("include-self", false), buffs,
                Math.max(0.05, node.getDouble("buff-duration-seconds", 10)),
                RpgStats.fromLore(attributeValues), Math.max(0.05, node.getDouble("attribute-duration-seconds", 10)));

    }
    private ShockwaveSkill loadShockwaveSkill(YamlConfiguration config, String path) {
        ConfigurationSection node = config.getConfigurationSection(path);
        if (node == null) return null;
        DamageSkill damage = loadShapeDamage(config, path, node, "PHYSICAL");
        return new ShockwaveSkill(damage, Math.max(1, node.getDouble("angle-degrees", 90)), Math.max(0.5, node.getDouble("radius", 5)), Math.max(0.5, node.getDouble("height", 2)), Math.max(0, node.getDouble("knockback", 5)), loadParticleConfig(config, path + ".particle"), loadSoundConfig(config, path + ".sound", false, "ENTITY_PLAYER_ATTACK_SWEEP", 1, 1));
    }

    private SweepSkill loadSweepSkill(YamlConfiguration config, String path) {
        ConfigurationSection node = config.getConfigurationSection(path);
        if (node == null) return null;
        DamageSkill damage = loadShapeDamage(config, path, node, "PHYSICAL");
        return new SweepSkill(damage, Math.max(0.5, node.getDouble("distance", 2)), Math.max(0.5, node.getDouble("radius", 2)), Math.max(0.5, node.getDouble("height", 2)), Math.max(1, node.getDouble("angle-degrees", 180)), loadParticleConfig(config, path + ".particle"), loadSoundConfig(config, path + ".sound", false, "ENTITY_PLAYER_ATTACK_SWEEP", 1, 1));
    }

    /**
     * 挥击：与横扫同源，但弧面会绕视线方向随机旋转一个倾斜角（0=水平，90=竖直）。
     * {@code tilt-min-degrees} / {@code tilt-max-degrees} 定义随机范围，默认 0..180
     * （与幻影剑舞的挥砍段一致，即每次都在整个范围内随机挑一个角度）。
     */
    private SwingSkill loadSwingSkill(YamlConfiguration config, String path) {
        ConfigurationSection node = config.getConfigurationSection(path);
        if (node == null) return null;
        DamageSkill damage = loadShapeDamage(config, path, node, "PHYSICAL");
        double tiltMin = Math.max(0, node.getDouble("tilt-min-degrees", 0));
        // 允许配置 0..180（弧面旋转 180° 后仍然合法，等于反向的弧），但拒绝负值和超过 180 的值。
        double tiltMax = Math.min(180, Math.max(tiltMin, node.getDouble("tilt-max-degrees", 180)));
        return new SwingSkill(damage, Math.max(0.5, node.getDouble("distance", 2)), Math.max(0.5, node.getDouble("radius", 2)), Math.max(0.5, node.getDouble("height", 2)), Math.max(1, node.getDouble("angle-degrees", 180)), tiltMin, tiltMax, loadParticleConfig(config, path + ".particle"), loadSoundConfig(config, path + ".sound", false, "ENTITY_PLAYER_ATTACK_SWEEP", 1, 1));
    }

    /**
     * 幻影剑舞：短时间连续挥砍（每次随机倾斜角 + 随机位置），挥砍结束后射出一道剑气飞向前方。
     * 挥砍参数直接写在 {@code phantom-blade} 节点下（与横扫一致），剑气写在 {@code phantom-blade.aura} 子节点。
     */
    private PhantomBladeSkill loadPhantomBladeSkill(YamlConfiguration config, String path) {
        ConfigurationSection node = config.getConfigurationSection(path);
        if (node == null) return null;
        // 每一下挥砍默认 30% 武器伤害，剑气默认 120%。
        DamageSkill slash = loadShapeDamage(config, path, node, "PHYSICAL", 30);
        ConfigurationSection auraNode = node.getConfigurationSection("aura");
        DamageSkill aura = auraNode == null ? null : loadShapeDamage(config, path + ".aura", auraNode, "PHYSICAL", 120);
        // 剑气不写 particle 时沿用挥砍段的粒子。需求是「粒子特效跟挥击一样」，
        // 这样管理员只改挥砍段的粒子，剑气会自动跟着变，不会两处不一致。
        if (aura != null && slash != null && auraNode.getConfigurationSection("particle") == null) {
            aura = withParticle(aura, slash.particle());
        }
        double tiltMin = Math.max(0, node.getDouble("tilt-min-degrees", 0));
        double tiltMax = Math.min(180, Math.max(tiltMin, node.getDouble("tilt-max-degrees", 180)));
        // 剑气飞出时的弧面朝向也是随机范围（默认 0..180，与挥砍段一致），
        // 否则剑气永远像横扫那样平着飞，和「跟挥砍一样随机」的要求不符。
        double auraTiltMin = Math.max(0, auraNode == null ? 0 : auraNode.getDouble("tilt-min-degrees", 0));
        double auraTiltMax = Math.min(180, Math.max(auraTiltMin,
                auraNode == null ? 180 : auraNode.getDouble("tilt-max-degrees", 180)));
        return new PhantomBladeSkill(
                slash,
                Math.max(1, node.getInt("slash-count", 5)),
                Math.max(1L, node.getLong("slash-interval-ticks", 3L)),
                Math.max(0.5, node.getDouble("distance", 2)),
                Math.max(0.5, node.getDouble("radius", 2)),
                Math.max(0.5, node.getDouble("height", 2)),
                Math.max(1, node.getDouble("angle-degrees", 180)),
                tiltMin,
                tiltMax,
                Math.max(0, node.getDouble("jitter", 0.6)),
                aura,
                Math.max(0.05, auraNode == null ? 1.2 : auraNode.getDouble("speed", 1.2)),
                Math.max(1, auraNode == null ? 16 : auraNode.getDouble("max-distance", 16)),
                Math.max(0.5, auraNode == null ? 1.5 : auraNode.getDouble("radius", 1.5)),
                // 剑气造型 = 挥击那一道弧：默认大小与张角和挥砍段一致，
                // 所以玩家看到的就是自己刚挥出的那道弧形整体飞出去。
                // 弧面朝向在 auraTiltMin..auraTiltMax 之间随机（与挥砍段一样）。
                Math.max(0.1, auraNode == null ? 2.0 : auraNode.getDouble("arc-radius", 2.0)),
                Math.max(10, auraNode == null ? 180 : auraNode.getDouble("angle-degrees", 180)),
                auraTiltMin,
                auraTiltMax,
                // 剑气版：每一道挥砍都换成会飞的剑气，且不再单独发射收尾剑气。
                node.getBoolean("slash-aura", false));
    }

    /**
     * 百人一太刀加载器：{@code sword-aura} 段是一道独立剑气的完整配置。
     * 伤害段（{@code damage} / {@code weapon-percent}，默认 200%）同时也是视觉与音效的宿主；
     * 粒子/音效直接写在本节下，不继承任何其他段。
     */
    private SwordAuraSkill loadSwordAuraSkill(YamlConfiguration config, String path) {
        ConfigurationSection node = config.getConfigurationSection(path);
        if (node == null) return null;
        DamageSkill damage = loadShapeDamage(config, path, node, "PHYSICAL", 200);
        double tiltMin = Math.max(0, node.getDouble("tilt-min-degrees", 0));
        double tiltMax = Math.min(180, Math.max(tiltMin, node.getDouble("tilt-max-degrees", 180)));
        return new SwordAuraSkill(damage,
                Math.max(0.5, node.getDouble("distance", 2)),
                Math.max(0, node.getDouble("jitter", 0.6)),
                Math.max(0.05, node.getDouble("speed", 1.2)),
                Math.max(1, node.getDouble("max-distance", 16)),
                Math.max(0.5, node.getDouble("radius", 1.5)),
                Math.max(0.1, node.getDouble("arc-radius", 2.0)),
                Math.max(10, node.getDouble("angle-degrees", 180)),
                tiltMin,
                tiltMax);
    }

    /** 复制一个 DamageSkill 但换掉粒子配置：用于剑气沿用挥砍段粒子的场景。 */
    private static DamageSkill withParticle(DamageSkill skill, ParticleConfig particle) {
        return new DamageSkill(skill.mode(), skill.damage(), skill.weaponPercent(), skill.length(), skill.radius(),
                skill.height(), skill.repeating(), skill.durationSeconds(), skill.intervalSeconds(),
                skill.hitOncePerCast(), skill.pierce(), skill.pierceBlocks(), skill.spreadCount(),
                skill.spreadAngle(), skill.shotgun(), skill.spreadRadius(), skill.damageType(),
                skill.weaponPercentSources(), skill.canCrit(), particle, skill.sound(), skill.knockback());
    }

    private CrossSlashSkill loadCrossSlashSkill(YamlConfiguration config, String path) {
        ConfigurationSection node = config.getConfigurationSection(path);
        if (node == null) return null;
        DamageSkill horizontal = loadShapeDamage(config, path + ".horizontal", node.getConfigurationSection("horizontal"), "PHYSICAL");
        DamageSkill vertical = loadShapeDamage(config, path + ".vertical", node.getConfigurationSection("vertical"), "PHYSICAL");
        SoundConfig fallbackSound = loadSoundConfig(config, path + ".sound", false, "ENTITY_PLAYER_ATTACK_SWEEP", 1, 1);
        SoundConfig horizontalSound = node.getConfigurationSection("horizontal.sound") == null ? fallbackSound : loadSoundConfig(config, path + ".horizontal.sound", false, "ENTITY_PLAYER_ATTACK_SWEEP", 1, 1);
        SoundConfig verticalSound = node.getConfigurationSection("vertical.sound") == null ? fallbackSound : loadSoundConfig(config, path + ".vertical.sound", false, "ENTITY_PLAYER_ATTACK_SWEEP", 1, 1);
        return new CrossSlashSkill(horizontal, vertical, Math.max(0.5, node.getDouble("distance", 2)), Math.max(0.5, node.getDouble("radius", 2)), Math.max(0.5, node.getDouble("height", 2)), Math.max(1, node.getDouble("angle-degrees", 100)), loadParticleConfig(config, path + ".horizontal.particle"), loadParticleConfig(config, path + ".vertical.particle"), horizontalSound, verticalSound);
    }

    /**
     * Reads the weapon-percent source list. The list is an <b>exclusion</b> list: every source
     * contributes by default and each entry removes that source (and therefore its damage type), so
     * an absent key or an explicit ALL means "exclude nothing". This replaced the old allow-list
     * behaviour whose implicit default was ATTACK only.
     * <p>
     * The scalar form is read with {@code getString} first. {@code getStringList} on a scalar node
     * does not reliably return a single-element list, so a server that had
     * {@code weapon-percent-sources: MAGIC_DAMAGE} written as a scalar could fall through to the
     * empty branch and be treated as "exclude nothing" instead of "exclude magic" - silently
     * reversing the intent of the exclusion.
     */
    private static List<String> weaponPercentSources(ConfigurationSection node) {
        // 传入 null 段必须安全返回：这个方法曾在拿到 null 时抛 NPE，
        // 而异常发生在技能加载阶段，会让整个 loadShortbowSkill 失败，
        // 症状是「某个技能直接识别不到」而不是「过滤没生效」——排查成本很高。
        if (node == null) return List.of("ALL");
        List<String> configured = node.getStringList("weapon-percent-sources");
        if (configured.isEmpty()) {
            String pluralScalar = node.getString("weapon-percent-sources", "");
            if (pluralScalar != null && !pluralScalar.isBlank()) return List.of(pluralScalar);
            String single = node.getString("weapon-percent-source", "");
            if (single != null && !single.isBlank()) configured = List.of(single);
        }
        return configured.isEmpty() ? List.of("ALL") : configured;
    }

    private DamageSkill loadShapeDamage(YamlConfiguration config, String path, ConfigurationSection node, String defaultType) {
        return loadShapeDamage(config, path, node, defaultType, 100);
    }

    private DamageSkill loadShapeDamage(YamlConfiguration config, String path, ConfigurationSection node, String defaultType, double defaultWeaponPercent) {
        if (node == null) return null;
        List<String> sources = weaponPercentSources(node);
        return new DamageSkill("shape", node.getDouble("damage", 0), node.getDouble("weapon-percent", defaultWeaponPercent), node.getDouble("distance", 2), node.getDouble("radius", 2), node.getDouble("height", 2), false, 0, 1, true, true, true, 1, 0, false, 0, node.getString("damage-type", defaultType), sources, node.getBoolean("can-crit", false), loadParticleConfig(config, path + ".particle"), loadSoundConfig(config, path + ".sound", false, "ENTITY_PLAYER_ATTACK_SWEEP", 1, 1), Math.max(0, node.getDouble("knockback", 0)));
    }

    private SkillChain loadSkillChain(YamlConfiguration config, String path) {
        ConfigurationSection node = config.getConfigurationSection(path);
        if (node == null) return null;
        return new SkillChain(node.getString("after", "release"), node.getString("next", ""), Math.max(0, node.getDouble("delay-seconds", 0)));
    }

    private AttributeBoostSkill loadAttributeBoost(YamlConfiguration config, String path) {
        ConfigurationSection node = config.getConfigurationSection(path);
        if (node == null) return null;
        Map<String, Double> values = new HashMap<>();
        for (String key : node.getKeys(false)) {
            if (!key.equalsIgnoreCase("duration-seconds") && !key.equalsIgnoreCase("stackable")) values.put(key.toLowerCase(Locale.ROOT).replace("_", ""), node.getDouble(key));
        }
        return new AttributeBoostSkill(RpgStats.fromLore(values), Math.max(0.05, node.getDouble("duration-seconds", 5)), node.getBoolean("stackable", false));
    }

    private ShortbowSkill loadShortbowSkill(YamlConfiguration config, String path) {
        ConfigurationSection node = config.getConfigurationSection(path);
        boolean attackSpeedCooldown = true;
        if (node == null && path.endsWith(".shortbow")) {
            node = config.getConfigurationSection(path + "-cd");
            attackSpeedCooldown = false;
        }
        if (node == null) return null;
        int count = Math.max(1, node.getInt("arrow-count", 1));
        double speed = Math.max(0.1, node.getDouble("speed", 3.0));
        String launchSoundName = node.getString("launch-sound", "ENTITY_ARROW_SHOOT");
        warnIfInvalidSound(path + ".launch-sound", launchSoundName);
        Sound sound = parseSound(launchSoundName);
        // 弹射物本体：不是原版投掷物，而是用物品图标做的 ItemDisplay（同「王之宝库」）。
        // projectile 直接写物品名（旧配置的 ARROW / SPECTRAL_ARROW 原样可用，它们本来就是物品名）；
        // projectile-item 段进一步配置穿透、方块碰撞、本体粒子与命中爆炸。
        String itemMaterial = node.getString("projectile", "ARROW").toUpperCase(Locale.ROOT);
        double hitRadius = Math.max(0.2, node.getDouble("projectile-item.hit-radius", 0.9));
        int pierce = Math.max(0, node.getInt("pierce-level", node.getInt("projectile-item.pierce", 0)));
        boolean collideBlocks = node.getBoolean("projectile-item.collide-blocks", true);
        // 本体缩放：只影响图标视觉大小，不影响 hit-radius 的判定范围。
        double scale = Math.max(0.1, node.getDouble("projectile-item.scale", 1.0));
        // 命中爆炸（爆炸箭）：写了 impact-explosion 段才启用。
        // sound 的默认 enabled 是 true：爆炸没有爆炸音是明显的体验错误，所以旧文件即使
        // 没写 sound 段（enabled 缺省 false 是通用默认）也会播默认爆炸音；显式写 false 才静音。
        ProjectileExplosion explosion = null;
        ConfigurationSection explosionSection = node.getConfigurationSection("impact-explosion");
        if (explosionSection != null) {
            String explosionSoundName = node.getString("impact-explosion.sound.sound", "ENTITY_GENERIC_EXPLODE");
            warnIfInvalidSound(path + ".impact-explosion.sound", explosionSoundName);
            // 注意这里必须基于 node 的「实际段路径」而不是 path：
            // path 传入的是 "....shortbow"，但固定冷却版本实际读的是 "....shortbow-cd"，
            // 此时 path + ".impact-explosion" 指向一个不存在的段。
            // 旧代码把 config.getConfigurationSection(path + ".impact-explosion") 直接传给
            // weaponPercentSources()，拿到 null → 方法内 node.getStringList() 抛 NPE
            // → 整个 loadShortbowSkill 失败 → 使用 shortbow-cd 的技能（爆炸箭）直接识别不到。
            String explosionPath = explosionSection.getCurrentPath();
            explosion = new ProjectileExplosion(
                    Math.max(0.5, node.getDouble("impact-explosion.radius", 3.0)),
                    Math.max(0, node.getDouble("impact-explosion.damage", 0)),
                    Math.max(0, node.getDouble("impact-explosion.weapon-percent", 60)),
                    loadParticleConfig(config, explosionPath + ".particle"),
                    loadSoundConfig(config, explosionPath + ".sound", true, "ENTITY_GENERIC_EXPLODE", 1.6F, 0.75F),
                    // 爆炸的伤害类型与来源过滤同样必须可配置：
                    // 原先结算处写死 PHYSICAL + 空排除列表，法师爆炸会打出物理伤害。
                    explosionSection.getString("damage-type", "PHYSICAL").toUpperCase(Locale.ROOT),
                    weaponPercentSources(explosionSection));
        }
        ItemProjectile projectile = new ItemProjectile(
                itemMaterial,
                node.getBoolean("projectile-item.glow", false),
                pierce, collideBlocks, hitRadius,
                Math.max(1, node.getDouble("projectile-item.max-distance", 48)),
                loadTrailParticleConfig(config, path + ".trail", "128,128,128"),
                loadParticleConfig(config, path + ".projectile-item.impact-particle"),
                loadSoundConfig(config, path + ".projectile-item.impact-sound", false, "ENTITY_ARROW_HIT", 1.0F, 1.0F),
                node.getDouble("projectile-item.tilt-degrees", -45),
                scale,
                explosion);
        // 伤害类型与来源过滤必须从配置读：原先 ShortbowSkill 没有这两项，
        // 结算时被写死成 PHYSICAL + 空排除列表，于是 yml 里写的
        // damage-type / weapon-percent-sources 完全无效，法师飞弹会打出物理伤害。
        // 默认仍是 PHYSICAL，保持旧配置（剑/弓武器里的 shortbow 段）行为不变。
        return new ShortbowSkill(count, speed, Math.max(0, node.getDouble("weapon-percent", 100)),
                sound, (float) node.getDouble("launch-volume", 1), (float) node.getDouble("launch-pitch", 1),
                attackSpeedCooldown, node.getBoolean("damage-enabled", true),
                node.getString("spread-mode", "line").toLowerCase(Locale.ROOT),
                Math.max(0, node.getDouble("spread-degrees", 12)),
                Math.max(1, node.getInt("volleys", 1)), Math.max(0, node.getInt("volley-interval-ticks", 0)),
                projectile,
                node.getString("damage-type", "PHYSICAL").toUpperCase(Locale.ROOT),
                weaponPercentSources(node));
    }

    private DamageSkill loadDimensionalSlash(YamlConfiguration config, String path) {
        ConfigurationSection node = config.getConfigurationSection(path);
        if (node == null) return null;
        ParticleConfig particle = config.getConfigurationSection(path + ".particle") == null
                ? new ParticleConfig(true, "END_ROD", "128,128,128", "sphere", 80, 3)
                : loadParticleConfig(config, path + ".particle");
        // Read the configured sources like every other shape loader does. This used to be hardcoded
        // to ATTACK, so "weapon-percent-sources" was silently ignored for dimensional-slash.
        List<String> sources = weaponPercentSources(node);
        return new DamageSkill("dimensional-slash", node.getDouble("damage", 10), node.getDouble("weapon-percent", 100), Math.max(0.5, node.getDouble("distance", 7)), Math.max(0.5, node.getDouble("radius", 3)), Math.max(0.5, node.getDouble("height", 3)), false, 0, 1, true, false, false, 1, 0, false, 0, node.getString("damage-type", "PHYSICAL"), sources, node.getBoolean("can-crit", true), loadParticleConfig(config, path + ".particle"), loadSoundConfig(config, path + ".sound", false, "ENTITY_ENDER_DRAGON_GROWL", 1, 1), Math.max(0, node.getDouble("knockback", 0)));
    }

    private Particle parseParticle(String value) { try { return Particle.valueOf(value.toUpperCase(Locale.ROOT)); } catch (Exception e) { return Particle.CRIT; } }
    private Sound parseSound(String value) { try { return Sound.valueOf(value.toUpperCase(Locale.ROOT)); } catch (Exception e) { return Sound.ENTITY_ARROW_SHOOT; } }

    /**
     * 加载时校验音效名，无效就<b>明确报出来</b>。
     * <p>
     * 之所以需要这个：{@link #parseSound} 在名字无效时会静默退回 {@code ENTITY_ARROW_SHOOT}，
     * 而运行时 {@code FSkills.playSound} 只在<b>技能真的施放那一刻</b>才打一行 warning。
     * 于是「模板里写了一个不存在的音效名」这种错误的表现是：进游戏听到一个不相干的音、
     * 或者干脆没声音，而控制台在没人施放技能之前一直是干净的。实测就踩过一次
     * （{@code ENTITY_WITHER_SKELETON_SHOOT} 在 1.21.1 并不存在，凋灵骷髅射击用的是
     * {@code ENTITY_WITHER_SHOOT}）。现在放在加载期，一次 reload 就能看到全部拼错的音效名。
     */
    private void warnIfInvalidSound(String path, String sound) {
        if (sound == null || sound.isBlank()) return;
        String name = sound.toUpperCase(Locale.ROOT);
        if (name.equals("ENTITY_TNT_EXPLODE")) return; // playSound 会把它映射成 ENTITY_GENERIC_EXPLODE
        try {
            Sound.valueOf(name);
        } catch (IllegalArgumentException invalid) {
            plugin.getLogger().warning("技能音效名无效，本次不会生效：" + path + " = " + sound
                    + "（该名字不在 1.21.1 的音效表里，请核对拼写）");
        }
    }

    private ParticleConfig loadParticleConfig(YamlConfiguration config, String path) {
        return loadParticleConfig(config, path, "128,128,128");
    }

    /**
     * 轨迹粒子（trail）专用的默认与上限半径。
     * <p>
     * <b>为什么轨迹不能用通用默认值</b>：{@code loadParticleConfig} 的通用默认 radius 是 {@code 1.5}，
     * 那是给<b>形状</b>类粒子用的（球形/环形，半径 1.5 格很正常）。但轨迹的 {@code radius}
     * 是<b>线的粗细</b>，而且 {@code playTrail} / {@code playJittered} 会在该点周围
     * <b>±radius 的立方体</b>内撒粒子 —— 于是「trail 段没写 radius」会静默变成
     * 1.5 → 被 {@code TRAIL_RADIUS_CAP} 截成 {@code 1.0} → 弹射物周围 ±1 格一团雾。
     * <p>
     * 玩家实测反馈原话：「拖尾粒子范围怎么是 1 格，他应该跟原版箭射出去差不多是 0.几格」——
     * 说的就是这个 {@code 1.0}。所以轨迹有自己的一套默认值与上限，不再继承形状的 1.5。
     */
    private static final double TRAIL_DEFAULT_RADIUS = 0.05;

    /** 轨迹半径上限：0.5 即「线的粗细」的合理尽头，再大就是云而不是轨迹了。 */
    private static final double TRAIL_MAX_RADIUS = 0.5;

    /**
     * 按<b>轨迹</b>语义加载粒子：默认半径 {@value #TRAIL_DEFAULT_RADIUS}，并把过大的半径收到
     * {@value #TRAIL_MAX_RADIUS} 并<b>明确告警</b>（不再静默变成一团雾）。
     */
    private ParticleConfig loadTrailParticleConfig(YamlConfiguration config, String path, String defaultBlock) {
        ParticleConfig trail = loadParticleConfig(config, path, defaultBlock, TRAIL_DEFAULT_RADIUS);
        if (trail.radius() <= TRAIL_MAX_RADIUS) return trail;
        plugin.getLogger().warning("轨迹粒子半径过大，已收窄到上限：" + path + ".radius = " + trail.radius()
                + " -> " + TRAIL_MAX_RADIUS + "（轨迹的 radius 是「线的粗细」而不是形状大小；"
                + "需要大范围请改用形状类粒子）");
        return new ParticleConfig(trail.enabled(), trail.particle(), trail.block(), trail.effect(),
                trail.count(), TRAIL_MAX_RADIUS);
    }

    /**
     * Same as {@link #loadParticleConfig(YamlConfiguration, String)} but with an explicit colour for
     * when the section is absent.
     * <p>
     * The generic default is grey, which is fine for an arbitrary effect but wrong for a themed one:
     * a missing section then renders in a colour nobody chose, and the symptom ("why is it grey?")
     * points at the renderer instead of at the absent config. Callers that know the intended colour
     * pass it here so an un-updated yml still looks right.
     *
     * @param defaultBlock {@code r,g,b} used when neither {@code block} nor {@code color} is present
     */
    private ParticleConfig loadParticleConfig(YamlConfiguration config, String path, String defaultBlock) {
        return loadParticleConfig(config, path, defaultBlock, 1.5);
    }

    /**
     * 与 {@link #loadParticleConfig(YamlConfiguration, String, String)} 相同，但可指定默认半径。
     * <p>
     * {@code radius} 的含义随用途而变，所以默认值不能只有一个：形状类粒子的 {@code 1.5}
     * 用在<b>轨迹</b>上会变成 ±1 格的一团雾（轨迹的 radius 是「线的粗细」）。
     * 轨迹调用方请走 {@link #loadTrailParticleConfig}。
     *
     * @param defaultRadius {@code radius} 缺省时的取值；形状类为 1.5，轨迹类见 {@link #TRAIL_DEFAULT_RADIUS}
     */
    private ParticleConfig loadParticleConfig(YamlConfiguration config, String path, String defaultBlock, double defaultRadius) {
        String root = path + ".";
        String fallbackParticle = path.equals("projectile-hit-particles") ? "FIREWORK" : path.equals("particles") ? "BLOCK" : "DUST";
        boolean defaultEnabled = !path.equals("particles") && !path.equals("projectile-hit-particles");
        boolean enabled = config.contains(root + "enabled")
                ? config.getBoolean(root + "enabled")
                : (!path.equals("particles") && plugin.getConfig().getBoolean(root + "enabled", defaultEnabled));
        return new ParticleConfig(
                enabled,
                config.getString(root + "particle", plugin.getConfig().getString(root + "particle", fallbackParticle)),
                config.getString(root + "block", config.getString(root + "color", plugin.getConfig().getString(root + "color", plugin.getConfig().getString(root + "block", defaultBlock)))),
                config.getString(root + "effect", plugin.getConfig().getString(root + "effect", "break")),
                config.getInt(root + "count", plugin.getConfig().getInt(root + "count", 12)),
                config.getDouble(root + "radius", plugin.getConfig().getDouble(root + "radius", defaultRadius)));
    }

    private SoundConfig loadSoundConfig(YamlConfiguration config, String path, boolean defaultEnabled, String defaultSound, float defaultVolume, float defaultPitch) {
        String root = path + ".";
        return new SoundConfig(
                config.getBoolean(root + "enabled", plugin.getConfig().getBoolean(root + "enabled", defaultEnabled)),
                config.getString(root + "sound", plugin.getConfig().getString(root + "sound", defaultSound)),
                (float) config.getDouble(root + "volume", plugin.getConfig().getDouble(root + "volume", defaultVolume)),
                (float) config.getDouble(root + "pitch", plugin.getConfig().getDouble(root + "pitch", defaultPitch)));
    }

    private List<BuffConfig> loadBuffs(YamlConfiguration config, String path) {
        List<BuffConfig> buffs = new ArrayList<>();
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null) return buffs;
        for (String key : section.getKeys(false)) buffs.add(new BuffConfig(key, section.getInt(key, 0)));
        return buffs;
    }

    private List<HitBuffConfig> loadHitBuffs(YamlConfiguration config) {
        List<HitBuffConfig> buffs = new ArrayList<>();
        ConfigurationSection section = config.getConfigurationSection("hit-buffs");
        if (section == null) return buffs;
        for (String key : section.getKeys(false)) {
            if (section.isConfigurationSection(key)) {
                ConfigurationSection buff = section.getConfigurationSection(key);
                buffs.add(new HitBuffConfig(key, buff.getInt("amplifier", 0), buff.getInt("duration-seconds", 5)));
            } else {
                buffs.add(new HitBuffConfig(key, section.getInt(key, 0), 5));
            }
        }
        return buffs;
    }

    private void createDirectoriesAndExamples() {
        plugin.getDataFolder().mkdirs();
        for (RpgItemType type : RpgItemType.values()) new File(plugin.getDataFolder(), type.getFolderName()).mkdirs();
        new File(plugin.getDataFolder(), "skills").mkdirs();
        createSkillExample("横扫.yml", sweepSkillExample());
        createSkillExample("挥击.yml", swingSkillExample());
        createSkillExample("幻影剑舞.yml", phantomBladeSkillExample());
        createSkillExample("幻影剑舞·剑气版.yml", phantomBladeAuraOnlySkillExample());
        createSkillExample("十字斩.yml", crossSlashSkillExample());
        createSkillExample("百人一太刀.yml", swordAuraSkillExample());
        createSkillExample("王之宝库.yml", gateOfBabylonSkillExample());
        createSkillExample("后撤.yml", backstepSkillExample());
        createSkillExample("次元斩.yml", dimensionalSlashSkillExample());
        createSkillExample("上挑.yml", uppercutSkillExample());
        createSkillExample("蹦山击.yml", leapStrikeSkillExample());
        createSkillExample("踢击.yml", kickSkillExample());
        createSkillExample("穿透箭.yml", piercingArrowSkillExample());
        createSkillExample("三连射.yml", tripleShotSkillExample());
        createSkillExample("爆炸箭.yml", explosiveArrowSkillExample());
        createSkillExample("分裂箭.yml", splitArrowSkillExample());
        exampleGenerationFolders = java.util.Arrays.stream(RpgItemType.values())
                .map(RpgItemType::getFolderName)
                .filter(this::hasNoItemConfigs)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        createExample("Weapon", "long_attack_speed_weapon.txt", weapon("long_attack_speed_weapon", "NETHERITE_SWORD", "&4\u957f\u653b\u901f\u91cd\u5251", "Attack: 18\n  CritChance: 10\n  CritDamage: 20\n  AttackSpeed: -60"));
        createExample("Weapon", "middle_attack_speed_weapon.txt", weapon("middle_attack_speed_weapon", "IRON_SWORD", "&f\u4e2d\u653b\u901f\u94c1\u5251", "Attack: 8\n  CritChance: 10\n  CritDamage: 12\n  AttackSpeed: 0"));
        createExample("Weapon", "test_backstrike_weapon.txt", weapon("test_backstrike_weapon", "IRON_SWORD", "&8\u80cc\u51fb\u4f24\u5bb3\u6d4b\u8bd5\u5251", "BackstrikeDamage: 10"));
        createExample("Weapon", "test_assassin_weapon.txt", weapon("test_assassin_weapon", "IRON_SWORD", "&5\u523a\u5ba2\u4f24\u5bb3\u6d4b\u8bd5\u5251", "AssDamage: 8\n  AssDamagePercent: 50"));
        createExample("Weapon", "exdraw8_bow.txt", rangedBase("exdraw8_bow", "BOW", "&aEXDraw8 Bow", "BowDamage: 8\n  EXDraw: 8\n  CritChance: 15\n  CritDamage: 12"));
        createExample("Weapon", "wither_launch_bow.txt", witherLaunchBow());
        createExample("Weapon", "magic_bow_sample.txt", rangedBase("magic_bow_sample", "BOW", "&dMagic Bow Sample", "BowDamage: 5\n  MagicDamage: 7\n  EXDraw: 4\n  CritChance: 20\n  CritDamage: 10"));
        createExample("Weapon", "magic_crossbow_sample.txt", rangedBase("magic_crossbow_sample", "CROSSBOW", "&5Magic Crossbow Sample", "BowDamage: 6\n  MagicDamage: 8\n  CritChance: 15\n  CritDamage: 10"));
        createExample("Weapon", "soulbound_sword_sample.txt", soulboundSword());
        createExample("Weapon", "xuan_iron_sword_sample_1.txt", xuanIronSword());
        createExample("Weapon", "test_pct_damage_bow.txt", rangedBase("test_pct_damage_bow", "BOW", "&cPctDamage Test Bow", "BowDamage: 1\n  PctDamage: 80"));
        createExample("Weapon", "test_pst_damage_bow.txt", rangedBase("test_pst_damage_bow", "BOW", "&cPstDamage Test Bow", "BowDamage: 1\n  PstDamage: 5"));
        createExample("Weapon", "test_attack_range_1_bow.txt", rangedBase("test_attack_range_1_bow", "BOW", "&6AttackRange 1 Test Bow", "BowDamage: 4\n  AttackRange: 1"));
        createExample("Weapon", "test_attack_range_2_crossbow.txt", rangedBase("test_attack_range_2_crossbow", "CROSSBOW", "&6AttackRange 2 Test Crossbow", "BowDamage: 4\n  AttackRange: 2"));
        createExample("Weapon", "test_pct_damage_sword.txt", weapon("test_pct_damage_sword", "IRON_SWORD", "&cPctDamage Test Sword", "Attack: 5\n  PctDamage: 80"));
        createExample("Weapon", "test_pst_damage_sword.txt", weapon("test_pst_damage_sword", "IRON_SWORD", "&cPstDamage Test Sword", "Attack: 5\n  PstDamage: 5"));
        createExample("Weapon", "test_attack_range_1_sword.txt", weapon("test_attack_range_1_sword", "IRON_SWORD", "&6AttackRange 1 Test Sword", "Attack: 5\n  AttackRange: 1"));
        createExample("Weapon", "test_attack_range_2_sword.txt", weapon("test_attack_range_2_sword", "IRON_SWORD", "&6AttackRange 2 Test Sword", "Attack: 5\n  AttackRange: 2"));
        createExample("Weapon", "test_ice_element_sword.txt", weapon("test_ice_element_sword", "DIAMOND_SWORD", "&9Ice Element Test Sword", "Attack: 5\n  IceDamage: 4\n  IceDamagePercent: 50"));
        createExample("Weapon", "test_fire_element_sword.txt", weapon("test_fire_element_sword", "DIAMOND_SWORD", "&cFire Element Test Sword", "Attack: 5\n  FireDamage: 4\n  FireDamagePercent: 50"));
        createExample("Weapon", "test_light_dark_element_bow.txt", rangedBase("test_light_dark_element_bow", "BOW", "&eLight Dark Element Test Bow", "BowDamage: 5\n  LightDamage: 3\n  LightDamagePercent: 50\n  DarkDamage: 3\n  DarkDamagePercent: 50"));
        createExample("Weapon", "test_parry_counter_weapon.txt", parryCounterWeapon());
        createExample("Armor", "test_element_armor.txt", item("test_element_armor", "DIAMOND_CHESTPLATE", "&bElement Armor Test Chestplate", "IceArmor: 3\n  IceArmorPercent: 20\n  FireArmor: 3\n  FireArmorPercent: 20\n  LightArmor: 3\n  LightArmorPercent: 20\n  DarkArmor: 3\n  DarkArmorPercent: 20"));
        createExample("Weapon", "test_attack_percent_sword.txt", item("test_attack_percent_sword", "DIAMOND_SWORD", "&cAttack Percent Test Sword", "Attack: 10\n  AttackPercent: 50"));
        createExample("Weapon", "test_magic_percent_sword.txt", item("test_magic_percent_sword", "GOLDEN_SWORD", "&dMagic Percent Test Sword", "MagicDamage: 10\n  MagicDamagePercent: 50"));
        createExample("Armor", "test_percent_armor.txt", item("test_percent_armor", "IRON_CHESTPLATE", "&9Percent Armor Test Chestplate", "Armor: 5\n  ArmorPercent: 25\n  EnvArmor: 2\n  EnvArmorPercent: 10\n  BowArmor: 3\n  BowArmorPercent: 20\n  CritArmor: 3\n  CritArmorPercent: 20"));
        createExample("Armor", "test_magic_armor.txt", item("test_magic_armor", "GOLDEN_CHESTPLATE", "&5Magic Armor Test Chestplate", "MagicArmor: 5\n  MagicArmorPercent: 30"));
        createExample("Armor", "test_negative_resistance.txt", item("test_negative_resistance", "LEATHER_CHESTPLATE", "&4Negative Resistance Test Chestplate", "Resistance: -50"));
        createExample("Weapon", "test_pct_pst_over_100_sword.txt", item("test_pct_pst_over_100_sword", "NETHERITE_SWORD", "&4Pct Pst Over 100 Test Sword", "Attack: 1\n  PctDamage: 150%\n  PstDamage: 125%"));
        createExample("Weapon", "test_pct_pst_skill_staff.txt", pctPstSkillStaff());
        createExample("Accessory", "test_ring_attack.txt", accessory("test_ring_attack", "EMERALD", "&a攻击戒指样本", "ring", "Attack: 3"));
        createExample("Accessory", "test_necklace_health.txt", accessory("test_necklace_health", "AMETHYST_SHARD", "&d生命项链样本", "necklace", "Health: 6\n  Recovery: 1"));
        createExample("Accessory", "test_dodge_ring.txt", accessory("test_dodge_ring", "FEATHER", "&b闪避戒指样本", "ring", "Dodge: 1"));
        createExample("Accessory", "test_block_hand.txt", accessory("test_block_hand", "SHIELD", "&f格挡手饰样本", "hand", "Block: 1"));
        createExample("Accessory", "test_determination_necklace.txt", accessory("test_determination_necklace", "HEART_OF_THE_SEA", "&a决意项链样本", "necklace", "Determination: 20%"));
        createExample("Accessory", "test_cad_earring.txt", accessory("test_cad_earring", "GOLD_NUGGET", "&6反击增伤耳饰样本", "earring", "CAD: 150%"));
        createExample("Weapon", "fskill_command_sample.txt", commandSkillSample());
        createExample("Weapon", "meteor_iron_sword_exchange_book.txt", meteorIronSwordExchangeBook());
        if (exampleGenerationFolders.contains("Weapon")) {
            repairCommandSkillSample();
            repairExcaliburSample();
        }
        createExample("Weapon", "fskill_particle_sword.txt", fskillParticleSword());
        createExample("Weapon", "fskill_wave_sword.txt", fskillWaveSword());
        createExample("Tool", "fskill_switch_tool_pickaxe.txt", fskillSwitchTool("fskill_switch_tool_pickaxe", "DIAMOND_PICKAXE", "fskill_switch_tool_axe"));
        createExample("Tool", "fskill_switch_tool_axe.txt", fskillSwitchTool("fskill_switch_tool_axe", "DIAMOND_AXE", "fskill_switch_tool_shovel"));
        createExample("Tool", "fskill_switch_tool_shovel.txt", fskillSwitchTool("fskill_switch_tool_shovel", "DIAMOND_SHOVEL", "fskill_switch_tool_hoe"));
        createExample("Tool", "fskill_switch_tool_hoe.txt", fskillSwitchTool("fskill_switch_tool_hoe", "DIAMOND_HOE", "fskill_switch_tool_pickaxe"));
        createExample("Tool", "farming_collection_hoe.txt", item("farming_collection_hoe", "DIAMOND_HOE", "&e农业采集锄", "FarmingSpeed: 20\n  FarmingFortune: 20"));
        createExample("Tool", "mining_collection_pickaxe.txt", item("mining_collection_pickaxe", "DIAMOND_PICKAXE", "&b陨铁采集镐", "MiningSpeed: 20\n  MiningFortune: 20"));
        createExample("Tool", "forest_collection_axe.txt", item("forest_collection_axe", "DIAMOND_AXE", "&6橡木采集斧", "ForestSpeed: 20\n  ForestFortune: 20"));
        // 采集等级工具（MiningLevel / ForestLevel / FarmingLevel）全部整合在一个
        // 工具 yml 里（Tool/collection_level_tools.yml，随 jar 打包、文件不存在才写），
        // 不再单独生成 txt 文件——见 writeDefaultLevelTools()。
        writeDefaultLevelTools();
        createExample("Items", "meteor_iron.txt", item("meteor_iron", "IRON_NUGGET", "&b陨铁", ""));
        // The default enhancement.yml points stone.item-id at this id. The example generator only fills
        // an *empty* Items folder, so on an install that already has items this file is written by
        // ensureRequiredItemFiles() instead — either way the configured stone ends up existing.
        createExample("Items", "enhance_stone.txt", item("enhance_stone", "AMETHYST_SHARD", "&d&l强化石", ""));
        // The three gem items. Their ids must match the entries in gemstones.yml, which is what
        // decides what each one grants once socketed; the item file only decides how it looks in a
        // backpack. Adding a gem therefore means adding one file here and one entry there.
        createExample("Items", "ruby.txt", gem("ruby", "RED_DYE", "&c红宝石"));
        createExample("Items", "sapphire.txt", gem("sapphire", "LAPIS_LAZULI", "&9蓝宝石"));
        createExample("Items", "emerald.txt", gem("emerald", "EMERALD", "&a绿宝石"));
        // A weapon with sockets, so the strip in the description and the gem station have something to
        // show. An item with no `sockets:` line has no sockets at all — a gem cannot be forced onto it.
        createExample("Weapon", "socketed_sword_sample.txt", socketedWeapon("socketed_sword_sample",
                "DIAMOND_SWORD", "&6宝石槽测试剑", "Attack: 12\n  Health: 4", 2));
        createExample("Armor", "socketed_chestplate_sample.txt", socketedWeapon("socketed_chestplate_sample",
                "DIAMOND_CHESTPLATE", "&b宝石槽测试胸甲", "Armor: 4\n  Health: 6", 1));
        createExample("Weapon", "fskill_area_bell_sword.txt", fskillAreaBellSword());
        createExample("Weapon", "fskill_sound_broadcast_wand.txt", fskillSoundBroadcastWand());
        createExample("Weapon", "fskill_message_dagger.txt", fskillMessageDagger());
        createExample("Weapon", "shotgun_staff.txt", shotgunStaff());
        createExample("Weapon", "diffusion_staff.txt", diffusionStaff());
        createExample("Weapon", "strike_staff.txt", strikeStaff());         createExample("Tool", "life_recovery_staff.yml", """
                 id: life_recovery_staff
                 material: BLAZE_ROD
                 Deputy: true
                 rskill:
                   cooldown-seconds: 6
                   range-buffs:
                     healing-aura:
                       radius: 6
                       height: 3
                       target: FRIENDLY
                       include-self: true
                       effects:
                         REGENERATION:
                           amplifier: 0
                           duration-seconds: 5
                       particle:
                         enabled: true
                         particle: HEART
                         effect: burst
                         count: 18
                 """);
         createExample("Tool", "roll_staff.yml", """
                 id: roll_staff
                 material: BLAZE_ROD
                 Deputy: true
                 rskill:
                   cooldown-seconds: 4
                   roll:
                     distance: 5
                     duration-ticks: 8
                     invulnerability-ticks: 6
                 """);
    }

    private static String commandSkillSample() {
        return """
                id: fskill_command_sample
                material: BLAZE_ROD
                display-name: "&6管理员指令技能样本"
                attributes:
                  MagicDamage: 3
                fskill:
                  cooldown-seconds: 5
                  commands:
                    self_effect:
                      as-op: true
                      commands:
                        - "effect give @self minecraft:speed 5 1 true"
                        - 'title @self actionbar {"text":"指令技能触发","color":"gold"}'
                description:
                  - "&7按F键后由控制台权限执行命令。"
                  - "&7@self 会替换为技能使用者的游戏ID。"
                """;
    }

    private static String meteorIronSwordExchangeBook() {
        return """
                id: meteor_iron_sword_exchange_book
                material: BOOK
                item-version: 1
                display-name: "&6陨铁剑兑换书"
                Consumables: true
                fskill:
                  cooldown-seconds: 1
                  cooldown-name: "陨铁剑兑换"
                  commands:
                    meteor_exchange:
                      as-op: true
                      commands:
                        - 'command{c="minecraft:playsound entity.ender_dragon.shoot master @a ~ ~ ~ 1 0.8 100"} @self'
                        - 'command{c="minecraft:playsound entity.ender_dragon.ambient master @a ~ ~ ~ 1 0.6 100"} @self'
                        - 'command{c="broadcast &6&l✦━━━━━━━━━━━━━━━━━━━━━━━━━━✦"} @self'
                        - 'command{c="broadcast &6&l✦  &e星陨之地传来古老的回响...  &6&l✦"} @self'
                        - 'command{c="broadcast &6&l✦  &f一本泛着金属光泽的古籍被翻开  &6&l✦"} @self'
                        - 'command{c="broadcast &6&l✦━━━━━━━━━━━━━━━━━━━━━━━━━━✦"} @self'
                        - "delay 60"
                        - 'command{c="broadcast &b&l✦━━━━━━━━━━━━━━━━━━━━━━━━━━✦"} @self'
                        - 'command{c="broadcast &b&l✦  &d星辰与陨铁在虚空中共鸣！  &b&l✦"} @self'
                        - 'command{c="broadcast &b&l✦  &f沉睡千年的剑刃开始苏醒...  &b&l✦"} @self'
                        - 'command{c="broadcast &b&l✦━━━━━━━━━━━━━━━━━━━━━━━━━━✦"} @self'
                        - "delay 60"
                        - 'command{c="broadcast &d&l✦━━━━━━━━━━━━━━━━━━━━━━━━━━✦"} @self'
                        - 'command{c="broadcast &d&l✦  &6&l陨铁剑 &f已认主！  &d&l✦"} @self'
                        - 'command{c="broadcast &d&l✦  &7恭喜 &e${player} &7获得传说之剑！ &d&l✦"} @self'
                        - 'command{c="broadcast &d&l✦  &8&o「星陨之地，剑指苍穹」  &d&l✦"} @self'
                        - 'command{c="broadcast &d&l✦━━━━━━━━━━━━━━━━━━━━━━━━━━✦"} @self'
                        - "delay 60"
                        - 'command{c="rpgitem give ${player} 陨铁剑"} @self'
                        - 'message{m="&a&l✦ 你已成功兑换 &6&l陨铁剑 &a&l！"} @self'
                description:
                  - "&7右键使用后开始兑换流程。"
                  - "&7兑换书为消耗品，使用后立即消耗。"
                  - "&7兑换流程共三段公告，最后发放陨铁剑。"
                """;
    }

    private static String fskillParticleSword() {
        return """
                id: fskill_particle_sword
                material: IRON_SWORD
                display-name: "&bFSkill粒子测试剑"
                attributes:
                  Attack: 6
                fskill:
                  cooldown-seconds: 4
                  particles:
                    burst:
                      enabled: true
                      particle: HEART
                      effect: grow
                      count: 40
                      radius: 2
                      repeating: true
                      duration-seconds: 3
                      interval-seconds: 0.5
                description:
                  - "&7按F键释放持续爱心粒子。"
                """;
    }

    private static String fskillWaveSword() {
        return """
                id: fskill_wave_sword
                material: DIAMOND_SWORD
                display-name: "&eFSkill光波测试剑"
                attributes:
                  Attack: 8
                fskill:
                  cooldown-seconds: 6
                  damages:
                    wave:
                      mode: wave
                      damage: 5
                      weapon-percent: 80
                      length: 7
                      radius: 0.8
                      repeating: false
                      particles:
                        enabled: true
                        particle: DUST
                        color: "255,255,0"
                        effect: line
                        count: 3
                        radius: 0.05
                description:
                  - "&7按F键向前发射一道光波。"
                """;
    }

    private static String fskillSwitchTool(String id, String material, String next) {
        return """
                id: %s
                material: %s
                display-name: "&aFSkill四形态工具-%s"
                attributes:
                  Fix/s: 1
                enchantments:
                  - FORTUNE:3
                  - EFFICIENCY:4
                fskill:
                  cooldown-seconds: 2
                  switches:
                    next:
                      rpgitem: %s
                      timed: false
                      switched-item-cooldown: false
                description:
                  - "&7按F键在斧、稿、铲、锄之间切换。"
                """.formatted(id, material, material, next);
    }

    private static String fskillAreaBellSword() {
        return """
                id: fskill_area_bell_sword
                material: GOLDEN_SWORD
                display-name: "&6钟鸣扩散剑"
                attributes:
                  Attack: 6
                fskill:
                  cooldown-seconds: 7
                  damages:
                    area:
                      mode: area
                      damage: 8
                      weapon-percent: 100
                      radius: 5
                      height: 5
                      repeating: false
                      hit-once-per-cast: true
                      particles:
                        enabled: true
                        particle: DUST
                        color: "255,215,0"
                        effect: circle
                        count: 96
                        radius: 5
                      sound:
                        enabled: true
                        sound: BLOCK_BELL_USE
                        volume: 1.2
                        pitch: 0.8
                  broadcasts:
                    title:
                      text: "钟鸣震地"
                      color: "&6"
                      duration-seconds: 3
                description:
                  - "&7按F键在脚下炸开金色圆环，并伴随钟鸣。"
                """;
    }

    private static String fskillSoundBroadcastWand() {
        return """
                id: fskill_sound_broadcast_wand
                material: BLAZE_ROD
                display-name: "&d星辉广播杖"
                attributes:
                  MagicDamage: 5
                fskill:
                  cooldown-seconds: 5
                  sounds:
                    chime:
                      enabled: true
                      sound: BLOCK_AMETHYST_BLOCK_CHIME
                      volume: 1.0
                      pitch: 1.4
                      repeating: true
                      duration-seconds: 2
                      interval-seconds: 0.4
                  particles:
                    stars:
                      enabled: true
                      particle: END_ROD
                      effect: rise
                      count: 50
                      radius: 2
                      repeating: false
                  broadcasts:
                    title:
                      text: "星辉降临"
                      color: "&d"
                      duration-seconds: 3
                description:
                  - "&7按F键播放连续清脆音效，并只给自己显示标题。"
                """;
    }

    private static String fskillMessageDagger() {
        return """
                id: fskill_message_dagger
                material: IRON_SWORD
                display-name: "&8影语短剑"
                attributes:
                  Attack: 5
                  AttackSpeed: 30
                fskill:
                  cooldown-seconds: 4
                  messages:
                    whisper:
                      self-only: false
                      radius: 10
                      repeating: true
                      duration-seconds: 3
                      interval-seconds: 1
                      texts:
                        - "&8{player} 的影子正在低语..."
                        - "&7空气被短剑切开了一道细缝。"
                  sounds:
                    whisper:
                      enabled: true
                      sound: ENTITY_ENDERMAN_AMBIENT
                      volume: 0.6
                      pitch: 1.6
                      repeating: false
                description:
                  - "&7按F键向周围玩家循环发送影语文本。"
                """;
    }

    private static String shotgunStaff() {
        return """
                id: shotgun_staff
                material: BLAZE_ROD
                display-name: "&6霰弹法杖"
                attributes:
                  Attack: 8
                  AttackSpeed: 20
                rskill:
                  cooldown-seconds: 3
                  damages:
                    shotgun:
                      mode: wave
                      damage: 5
                      weapon-percent: 80
                      length: 8
                      radius: 0.55
                      pierce: true
                      hit-once-per-cast: true
                      spread-count: 7
                      spread-angle: 45
                      shotgun: true
                      spread-radius: 2
                      particles:
                        enabled: true
                        particle: DUST
                        color: "255,120,20"
                        effect: line
                        count: 4
                        radius: 0.05
                      sound:
                        enabled: true
                        sound: ENTITY_FIREWORK_ROCKET_BLAST
                        volume: 1.0
                        pitch: 1.4
                description:
                  - "&7右键发射横向霰弹光波。"
                """;
    }

    private static String diffusionStaff() {
        return """
                id: diffusion_staff
                material: AMETHYST_SHARD
                display-name: "&d扩散法杖"
                attributes:
                  Attack: 6
                fskill:
                  cooldown-seconds: 5
                  damages:
                    diffusion:
                      mode: area
                      damage: 9
                      weapon-percent: 120
                      radius: 4
                      spread-radius: 3
                      height: 5
                      pierce: true
                      hit-once-per-cast: true
                      particles:
                        enabled: true
                        particle: DUST
                        color: "180,80,255"
                        effect: circle
                        count: 120
                        radius: 7
                      sound:
                        enabled: true
                        sound: BLOCK_AMETHYST_BLOCK_RESONATE
                        volume: 1.2
                        pitch: 0.9
                description:
                  - "&7按F键在脚下释放扩大扩散圆环。"
                """;
    }

    private static String strikeStaff() {
        return """
                id: strike_staff
                material: STICK
                display-name: "&b打击法杖"
                attributes:
                  Attack: 10
                  AttackSpeed: 0
                lskill:
                  damages:
                    strike:
                      mode: wave
                      damage: 12
                      weapon-percent: 100
                      length: 5
                      radius: 0.7
                      pierce: false
                      hit-once-per-cast: true
                      spread-count: 1
                      particles:
                        enabled: true
                        particle: CRIT
                        effect: line
                        count: 6
                        radius: 0.05
                      sound:
                        enabled: true
                        sound: ENTITY_PLAYER_ATTACK_STRONG
                        volume: 1.0
                        pitch: 0.7
                sounds:
                  enabled: true
                  sound: ENTITY_PLAYER_ATTACK_STRONG
                  volume: 1.0
                  pitch: 0.7
                description:
                  - "&7左键原地或命中时触发打击光波，命中第一个目标后停止。"
                """;
    }

    private static String pctPstSkillStaff() {
        return """
                id: test_pct_pst_skill_staff
                material: BLAZE_ROD
                display-name: "&d规模比例技能法杖"
                attributes:
                  Attack: 1
                  PctDamage: 150%
                  PstDamage: 125%
                  AttackSpeed: 0
                lskill:
                  damages:
                    health_percent_strike:
                      mode: wave
                      damage: 1
                      weapon-percent: 100
                      length: 6
                      radius: 0.6
                      pierce: true
                      hit-once-per-cast: true
                      particles:
                        enabled: true
                        particle: DUST
                        color: "220,80,255"
                        effect: line
                        count: 8
                        radius: 0.05
                      sound:
                        enabled: true
                        sound: ENTITY_EVOKER_CAST_SPELL
                        volume: 1.0
                        pitch: 1.0
                description:
                  - "&7左键技能同时触发150%规模伤害与125%比例伤害。"
                """;
    }

    private static String accessory(String id, String material, String name, String type, String attributes) {
        return """
                id: %s
                material: %s
                display-name: "%s"
                accessory-type: %s
                attributes:
                  %s
                custom-model-data: 0
                description:
                  - "&7饰品属性只有放入 /rpgitem accessorygui 后才会生效。"
                """.formatted(id, material, name, type, attributes);
    }

    private static String weapon(String id, String material, String name, String attributes) {
        return weaponTypeHeader(material) + item(id, material, name, attributes);
    }

    /**
     * 新版武器模板会带上 weapon-type（注册类型）。技能 supported-weapons 按它匹配，
     * 多个允许类型满足任一即可；没写这个字段的旧物品仍按材质推断，保持兼容。
     */
    private static String weaponTypeHeader(String material) {
        String value = material == null ? "" : material.toUpperCase(Locale.ROOT);
        String type = value.endsWith("_SWORD") ? "剑" : value.endsWith("_AXE") ? "斧"
                : value.equals("BOW") ? "弓" : value.equals("CROSSBOW") ? "弩"
                : value.contains("ROD") || value.contains("STAFF") ? "法杖" : "";
        return "# weapon-type 决定技能 supported-weapons 的匹配结果：剑/斧/弓/弩/法杖/匕首，\n"
                + "# 或 SWORD/AXE/BOW/CROSSBOW/STAFF/DAGGER，也可自定标签。填写后不再按材质猜测。\n"
                + "weapon-type: \"" + type + "\"\n";
    }

    private static String item(String id, String material, String name, String attributes) {
        return """
                id: %s
                material: %s
                display-name: "%s"
                attributes:
                  %s
                custom-model-data: 0
                hide-enchantments: false
                Soulbound: false
                unbreakable: false
                description:
                  - "&7%s"
                """.formatted(id, material, name, attributes, stripColor(name));
    }

    /**
     * A gem item: it declares no attributes of its own, because everything it grants lives in
     * {@code gemstones.yml} under this same id. The file here stays a purely cosmetic description of
     * how the gem looks in a backpack, which is why adding a gem means adding one file and one entry.
     */
    private static String gem(String id, String material, String name) {
        return """
                id: %s
                material: %s
                display-name: "%s"
                custom-model-data: 0
                description:
                  - "&7宝石：手持装备后用 /rpgitem gems 打开宝石台镶嵌。"
                  - "&7它加什么属性写在 gemstones.yml 里。"
                """.formatted(id, material, name);
    }

    /** An item template that declares gem sockets through the top-level {@code sockets} key. */
    private static String socketedWeapon(String id, String material, String name, String attributes, int sockets) {
        return """
                id: %s
                material: %s
                display-name: "%s"
                sockets: %d
                attributes:
                  %s
                custom-model-data: 0
                hide-enchantments: false
                Soulbound: false
                unbreakable: false
                description:
                  - "&7用 /rpgitem gems 打开宝石台镶嵌宝石。"
                """.formatted(id, material, name, sockets, attributes);
    }

    private static String rangedBase(String id, String material, String name, String attributes) {        return """
                id: %s
                material: %s
                display-name: "%s"
                attributes:
                  %s
                custom-model-data: 0
                hide-enchantments: false
                enchantments:
                  - UNBREAKING:3
                projectile-trail-particles:
                  enabled: true
                  particle: DUST
                  color: "128,128,128"
                  count: 2
                  radius: 0.05
                projectile-trail-sound:
                  enabled: true
                  sound: ENTITY_WITHER_SHOOT
                  volume: 0.18
                  pitch: 1.2
                 projectile-hit-particles:
                   enabled: false
                  particle: FIREWORK
                  effect: circle
                  count: 80
                  radius: 1.2
                 projectile-hit-sound:
                   enabled: false
                  sound: ENTITY_FIREWORK_ROCKET_BLAST
                  volume: 1.0
                  pitch: 1.0
                description:
                  - "&7%s"
                """.formatted(id, material, name, attributes, stripColor(name));
    }

    private static String witherLaunchBow() {
        return """
                id: wither_launch_bow
                material: BOW
                display-name: "&5闂佸憡鍨甸澶娒归崒鐐茬煑闁瑰瓨绻勫▓鎼佹⒒婵犲懏鐝柡鍛劚闇夐悗锝庡幘濡插摜鈧?
                attributes:
                  BowDamage: 8
                  EXDraw: 4
                  CritChance: 10
                  CritDamage: 8
                custom-model-data: 0
                hide-enchantments: false
                enchantments:
                  - UNBREAKING:3
                projectile-trail-particles:
                  enabled: true
                  particle: DUST
                  color: "128,128,128"
                  count: 2
                  radius: 0.05
                projectile-trail-sound:
                  enabled: true
                  sound: ENTITY_WITHER_SHOOT
                  volume: 1.0
                  pitch: 1.0
                 projectile-hit-particles:
                   enabled: false
                  particle: FIREWORK
                  effect: circle
                  count: 80
                  radius: 1.2
                 projectile-hit-sound:
                   enabled: false
                  sound: ENTITY_FIREWORK_ROCKET_BLAST
                  volume: 1.0
                  pitch: 1.0
                description:
                  - "&7闂佹椿娼块崝瀣姳椤掆偓闇夐悗锝庡幘濡插摜鈧鍠楅幐楣冾敊閸曨垰鐭楅柟瀛樼箘濞堟悂鏌￠崘銊ヮ暢婵炲牊鍨垮畷娆戔偓锝庡枟缁佲晛顭块懜鍨稇鐟滄澘鍊绘禍鎼佸礋椤栨粌寰撻梺杞扮閻楀嫰鍩€?
                  - "&8婵犵鈧啿鈧綊鎮樻径鎰闁靛鍎冲﹢浼存煕濞嗘劕鐏ラ柣銊у█濡懘鎼归锝嗙彙闁荤偞鍑归崑鍛涢锔界厐鐎广儱娲ㄩ弸鍌炴偡閺囩偞顥犳繛鎻掞躬閺佸秶浠﹂懖鈺冩闂佸憡鐟崹顖涚閹烘鍋ㄩ柕濠忓瘜閸斿啰鈧鍠楅幐绋跨暦閻斿吋鍋戞い鎺戝瀹曪綁鎮归崶锕傛闁?
                """;
    }

    private static String soulboundSword() {
        return """
                id: soulbound_sword_sample
                material: DIAMOND_SWORD
                display-name: "&5Soulbound Sword Sample"
                attributes:
                  Attack: 6
                  CritChance: 10
                Soulbound: true
                unbreakable: false
                enchantments:
                  - SHARPNESS:2
                description:
                  - "&7A test sword that binds to the first survival player using it."
                """;
    }

    private static String xuanIronSword() {
        return """
                id: xuan_iron_sword_sample_1
                material: IRON_SWORD
                display-name: "&8\u7384\u94c1\u5251\u6837\u672c<I>"
                attributes:
                  Attack: 9
                  CritChance: 12
                  CritDamage: 10
                  AttackSpeed: 0
                unbreakable: false
                enchantments:
                  - UNBREAKING:3
                  - SHARPNESS:4
                particles:
                  enabled: true
                  particle: BLOCK
                  block: IRON_BLOCK
                  effect: break
                  count: 18
                  radius: 1.8
                sounds:
                  enabled: true
                  sound: BLOCK_ANVIL_LAND
                  volume: 1.0
                  pitch: 0.8
                hit-buffs:
                  SLOWNESS:
                    amplifier: 0
                    duration-seconds: 5
                description:
                  - "&7\u5251\u8eab\u4ee5\u7384\u94c1\u53cd\u590d\u6298\u70bc\uff0c\u5203\u53e3\u4e0d\u4eae\uff0c\u5374\u6c89\u5f97\u50cf\u591c\u8272\u3002"
                  - "&8\u547d\u4e2d\u65f6\u4f1a\u9707\u788e\u94c1\u5c51\uff0c\u8ba9\u654c\u4eba\u7684\u811a\u6b65\u53d8\u5f97\u8fdf\u7f13\u3002"
                """;
    }

    private static String parryCounterWeapon() {
        return """
                id: test_parry_counter_weapon
                material: IRON_SWORD
                display-name: "&e\u62db\u67b6\u53cd\u51fb\u6d4b\u8bd5\u5251"
                attributes:
                  Attack: 5
                  Parry: 80
                  CounterAttack: 80
                unbreakable: false
                particles:
                  enabled: true
                  particle: BLOCK
                  block: STONE
                  effect: break
                  count: 18
                  radius: 1.2
                sounds:
                  enabled: false
                  sound: ENTITY_WITHER_HURT
                  volume: 1.0
                  pitch: 0.8
                description:
                  - "&7\u62db\u67b6\u4e0e\u53cd\u51fb\u6d4b\u8bd5\u6b66\u5668\uff0c\u9002\u5408\u6d4b\u8bd5\u683c\u6321\u4e0e\u53cd\u51fb\u673a\u5236\u3002"
                  - "&e\u6309 F \u952e\u91ca\u653e\u6280\u80fd\u3002"
                """;
    }

    private void repairCommandSkillSample() {
        File file = new File(new File(plugin.getDataFolder(), "Weapon"), "fskill_command_sample.txt");
        if (!file.exists()) return;
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            boolean changed = false;
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (!line.contains("title @self actionbar")) continue;
                String indent = line.substring(0, Math.max(0, line.indexOf('-')));
                String replacement = indent + "- 'title @self actionbar {\"text\":\"指令技能触发\",\"color\":\"gold\"}'";
                if (!line.equals(replacement)) {
                    lines.set(index, replacement);
                    changed = true;
                }
            }
            if (changed) Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            plugin.getLogger().warning("Cannot repair command skill sample: " + file.getPath());
        }
    }

    private void repairExcaliburSample() {
        File folder = new File(plugin.getDataFolder(), "Weapon");
        File[] files = folder.listFiles((dir, name) -> name.toLowerCase().endsWith(".txt"));
        if (files == null) return;
        for (File file : files) {
            try {
                List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
                boolean excalibur = lines.stream().anyMatch(line -> line.trim().equalsIgnoreCase("id: excalibur"));
                if (!excalibur) continue;
                boolean changed = false;
                for (int index = 0; index < lines.size(); index++) {
                    String line = lines.get(index);
                    if (line.trim().equalsIgnoreCase("sound: ENTITY_TNT_EXPLODE")) {
                        lines.set(index, line.substring(0, line.indexOf('s')) + "sound: ENTITY_GENERIC_EXPLODE");
                        changed = true;
                    } else if (line.startsWith("- ") && line.contains("按F键")) {
                        lines.set(index, "  " + line);
                        changed = true;
                    }
                }
                if (changed) Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
            } catch (IOException exception) {
                plugin.getLogger().warning("Cannot repair Excalibur sample: " + file.getPath());
            }
        }
    }

    private void createSkillExample(String fileName, String content) {
        File file = new File(new File(plugin.getDataFolder(), "skills"), fileName);
        if (file.exists()) return;
        try {
            Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            plugin.getLogger().warning("Cannot create example skill file: " + file.getPath());
        }
    }

    /**
     * 把随 jar 打包的「采集等级工具整合文件」写入 Tool 文件夹（文件不存在才写）。
     * 所有 MiningLevel / ForestLevel / FarmingLevel 工具都在这一个 yml 里，
     * 已有服务器的 Tool 文件夹即使已有别的模板也会补上这一份（不覆盖用户文件）。
     */
    private void writeDefaultLevelTools() {
        File target = new File(new File(plugin.getDataFolder(), "Tool"), "collection_level_tools.yml");
        if (target.exists()) return;
        try {
            plugin.saveResource("Tool/collection_level_tools.yml", false);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("无法写入默认采集等级工具文件：" + exception.getMessage());
        }
    }

    private String sweepSkillExample() {
        return """
                # 横扫 - 独立技能模板
                # 本文件位于 plugins/EarthRPGItem/skills/横扫.yml
                # 插件会自动把本文件注册为一个技能，技能 ID 就是文件名：横扫
                # 不需要在 config.yml 里添加任何配置。
                display-name: "&b横扫"
                material: ENCHANTED_BOOK
                skill-group: "技能组1"
                supported-weapons: [ANY]
                attack-speed-cooldown: true
                cooldown-name: "横扫"
                cooldown-enabled: true
                messages-enabled: true
                global-cooldown: false
                sweep:
                  damage: 30
                  weapon-percent: 80
                  distance: 2
                  radius: 2
                  height: 2
                  # 180 = 覆盖身前的整个半圆（左前 + 右前）；想要更窄的扇面就调小这个值。
                  angle-degrees: 180
                  damage-type: PHYSICAL
                  can-crit: true
                  # count = 整条弧的粒子总数（平均分配到弧上每个采样点），radius = 轨迹粗细。
                  # 不要写 weapon-percent-sources：默认即为全部来源，写了反而会取消列出的类型。
                  particle:
                    enabled: true
                    particle: CRIT
                    count: 160
                    radius: 0.08
                    effect: break
                  sound:
                    enabled: true
                    sound: ENTITY_PLAYER_ATTACK_SWEEP
                    volume: 1.0
                    pitch: 1.0
                """;
    }

    private String swingSkillExample() {
        return """
                # 挥击 - 独立技能模板
                # 本文件位于 plugins/EarthRPGItem/skills/挥击.yml
                # 插件会自动把本文件注册为一个技能，技能 ID 就是文件名：挥击
                # 不需要在 config.yml 里添加任何配置。
                display-name: "&d挥击"
                material: ENCHANTED_BOOK
                skill-group: "技能组1"
                supported-weapons: [ANY]
                attack-speed-cooldown: true
                cooldown-name: "挥击"
                cooldown-enabled: true
                messages-enabled: true
                global-cooldown: false
                swing:
                  damage: 30
                  weapon-percent: 80
                  distance: 2
                  radius: 2
                  height: 2
                  angle-degrees: 180
                  # 每次施放随机旋转弧面的倾斜角（绕视线方向）：
                  #   0  = 水平弧（和横扫一样平躺）
                  #   90 = 竖直弧（和十字斩的竖直段一样立起来）
                  # 默认 0..180，和幻影剑舞的挥砍段一致：每次都在整个范围内随机一个角度。
                  tilt-min-degrees: 0
                  tilt-max-degrees: 180
                  damage-type: PHYSICAL
                  can-crit: true
                  # count = 整条弧的粒子总数（平均分配到弧上每个采样点），radius = 轨迹粗细。
                  # 不要写 weapon-percent-sources：默认即为全部来源，写了反而会取消列出的类型。
                  particle:
                    enabled: true
                    particle: CRIT
                    count: 160
                    radius: 0.08
                    effect: break
                  sound:
                    enabled: true
                    sound: ENTITY_PLAYER_ATTACK_SWEEP
                    volume: 1.0
                    pitch: 1.0
                """;
    }

    private String phantomBladeSkillExample() {
        return """
                # 幻影剑舞 - 独立技能模板
                # 本文件位于 plugins/EarthRPGItem/skills/幻影剑舞.yml
                # 插件会自动把本文件注册为一个技能，技能 ID 就是文件名：幻影剑舞
                # 不需要在 config.yml 里添加任何配置。
                display-name: "&5幻影剑舞"
                material: ENCHANTED_BOOK
                skill-group: "技能组1"
                supported-weapons: [ANY]
                attack-speed-cooldown: true
                cooldown-name: "幻影剑舞"
                cooldown-enabled: true
                messages-enabled: true
                global-cooldown: false
                phantom-blade:
                  # ---- 挥砍段：短时间内连挥多下，每一下 30% ----
                  slash-count: 5            # 挥砍次数。把下面的 slash-aura 设成 true 就是剑气版：
                                            #   每一道挥砍都变成会飞的剑气，且不再有收尾剑气
                                            #   （现成配置见 skills/幻影剑舞·剑气版.yml）
                  slash-interval-ticks: 3   # 每一下之间间隔的 tick（20 tick = 1 秒）
                  damage: 0
                  weapon-percent: 30
                  distance: 2
                  radius: 2
                  height: 2
                  angle-degrees: 180
                  # 每一下随机旋转弧面（0=水平，90=竖直）并在随机位置出现，形成幻影感。
                  tilt-min-degrees: 0
                  tilt-max-degrees: 180
                  jitter: 0.6               # 随机位置的水平偏移半径（格）
                  damage-type: PHYSICAL
                  can-crit: true
                  # 粒子与挥击保持一致（挥击用的是 CRIT）。不要用 SWEEP_ATTACK：
                  # 那是原版「剑横扫」的粒子，看起来就是一次普通横扫，不是技能刀光。
                  particle:
                    enabled: true
                    particle: CRIT
                    count: 120
                    radius: 0.08
                    effect: break
                  sound:
                    enabled: true
                    sound: ENTITY_PLAYER_ATTACK_SWEEP
                    volume: 0.8
                    pitch: 1.2
                  # ---- 收尾剑气：挥砍结束后射出一道剑气飞向前方，120% ----
                  # 剑气就是「挥击那一道弧」整体飞出去：用与挥击/横扫完全相同的弧线绘制，
                  # 弧面包含飞行方向，所以看到的是自己刚挥出的那道弧形一路飞向远方。
                  aura:
                    damage: 0
                    weapon-percent: 120
                    damage-type: PHYSICAL
                    can-crit: true
                    speed: 1.2              # 每 tick 前进的格数
                    max-distance: 16        # 最远飞行距离（撞到方块会提前停下）
                    radius: 1.5             # 剑气判定半径（伤害判定，不是视觉大小）
                    # 下面三个是这道弧的视觉造型，默认与挥砍段一致，所以看起来就是同一道弧。
                    arc-radius: 2.0         # 弧的半径（视觉大小）
                    angle-degrees: 180      # 弧的张角（180 = 半圆，同横扫）
                    # 剑气飞出时的弧面朝向：与挥砍段一样「每次随机」，
                    # 所以剑气有时平着飞、有时斜着飞、有时立着飞，不会永远跟横扫一样水平。
                    # 想让剑气固定一个角度，把 min 和 max 写成同一个值即可。
                    tilt-min-degrees: 0
                    tilt-max-degrees: 180
                    # 不写 particle 时剑气会自动沿用挥砍段的粒子配置（需求：粒子特效跟挥击一样）。
                    # 想让剑气有独立颜色时才在这里写 particle。
                    sound:
                      enabled: true
                      sound: ENTITY_PLAYER_ATTACK_SWEEP
                      volume: 1.0
                      pitch: 0.8
                # ---- 技能强化成长（技能强化台：/rpgitem skillforge） ----
                # 等级属于「玩家 + 技能」而不是物品，所以取出/放回技能背包不会掉级，
                # 复制技能物品也刷不出等级。Lv.1 就是上面写的数值。
                leveling:
                  max-level: 10
                  # 每升一级叠加多少。字段路径就是上面 yml 里的路径。
                  per-level:
                    phantom-blade.slash-count: 1      # 每级 +1 挥砍次数（Lv.1=5 → Lv.9=13）
                    phantom-blade.weapon-percent: 2   # 每级 +2% 每道伤害
                  # 可选：某些等级改用【绝对值】，之后继续按 per-level 往上加。
                  levels:
                    10:
                      phantom-blade.slash-count: 15   # Lv.10 起挥砍次数直接为 15
                  # 可选：覆盖 skill-leveling.yml 里的全局消耗。
                  cost:
                    tiers:
                      - max-level: 5
                        gems:
                          ruby: 1
                      - max-level: 10
                        gems:
                          ruby: 2
                          sapphire: 1
                """;
    }

    private String phantomBladeAuraOnlySkillExample() {
        return """
                # 幻影剑舞·剑气版 - 独立技能模板
                # 本文件位于 plugins/EarthRPGItem/skills/幻影剑舞·剑气版.yml
                # 插件会自动把本文件注册为一个技能，技能 ID 就是文件名：幻影剑舞·剑气版
                # 不需要在 config.yml 里添加任何配置。
                # 效果：把幻影剑舞前面的每一道挥砍都换成剑气 —— 5 道剑气（每道 30%）
                #       隔一小段时间逐道射出，全都飞向前方。因为每一道都已经是剑气了，
                #       所以不再有收尾剑气（原来最后那道 120% 的剑气删掉了）。
                display-name: "&5幻影剑舞·剑气版"
                material: ENCHANTED_BOOK
                skill-group: "技能组1"
                supported-weapons: [ANY]
                attack-speed-cooldown: true
                cooldown-name: "幻影剑舞·剑气版"
                cooldown-enabled: true
                messages-enabled: true
                global-cooldown: false
                phantom-blade:
                  slash-count: 5        # 剑气数量（原来的挥砍次数，每一下都变成剑气）
                  slash-interval-ticks: 3
                  slash-aura: true      # 剑气版：每一下都是会飞的剑气；最后不再有收尾剑气
                  # 每道剑气的伤害（默认 30%，与幻影剑舞的挥砍段一致）
                  damage: 0
                  weapon-percent: 30
                  damage-type: PHYSICAL
                  can-crit: true
                  distance: 2           # 每道剑气从玩家前方几格处出发
                  jitter: 0.6           # 每道剑气出发位置的水平随机偏移（格）
                  # 每道剑气的粒子（不写 particle 时自动沿用这里）
                  particle:
                    enabled: true
                    particle: CRIT
                    count: 120
                    radius: 0.08
                    effect: break
                  sound:
                    enabled: true
                    sound: ENTITY_PLAYER_ATTACK_SWEEP
                    volume: 0.8
                    pitch: 1.2
                  aura:
                    # 剑气版里 aura 段只是「每道剑气怎么飞、怎么画」的参数，不再是收尾剑气。
                    speed: 1.2          # 每 tick 前进的格数
                    max-distance: 16    # 最远飞行距离（撞到方块提前停下）
                    radius: 1.5         # 剑气判定半径
                    arc-radius: 2.0     # 弧的半径（视觉大小）
                    angle-degrees: 180  # 弧的张角（180 = 半圆）
                    tilt-min-degrees: 0
                    tilt-max-degrees: 180
                    # 剑气不写 particle / sound：自动沿用挥砍段的粒子与音效。
                # ---- 技能强化成长（技能强化台：/rpgitem skillforge） ----
                leveling:
                  max-level: 10
                  per-level:
                    phantom-blade.slash-count: 1      # 每级 +1 道剑气（Lv.1=5 → Lv.10=14）
                    phantom-blade.weapon-percent: 2   # 每级 +2% 每道剑气伤害
                  cost:
                    tiers:
                      - max-level: 10
                        gems:
                          sapphire: 1
                          ruby: 1
                """;
    }

    private String swordAuraSkillExample() {
        return """
                # 百人一太刀 - 独立技能模板
                # 本文件位于 plugins/EarthRPGItem/skills/百人一太刀.yml
                # 插件会自动把本文件注册为一个技能，技能 ID 就是文件名：百人一太刀
                # 不需要在 config.yml 里添加任何配置。
                # 效果：直接朝视线前方发射一道剑气（就是挥击那一道弧整体飞出去），
                #       沿途对碰到的目标各造成一次伤害，撞到方块或飞满距离后消失。
                display-name: "&6百人一太刀"
                material: ENCHANTED_BOOK
                skill-group: "技能组3"
                supported-weapons: [ANY]
                attack-speed-cooldown: true
                cooldown-name: "百人一太刀"
                cooldown-enabled: true
                messages-enabled: true
                global-cooldown: false
                sword-aura:
                  # ---- 伤害（默认 200% 武器伤害） ----
                  damage: 0
                  weapon-percent: 200
                  damage-type: PHYSICAL
                  can-crit: true
                  distance: 2          # 剑气从玩家前方几格处出发
                  jitter: 0.6          # 出发位置的随机水平偏移半径（格）
                  speed: 1.2           # 每 tick 前进的格数
                  max-distance: 16     # 最远飞行距离（撞到方块会提前停下）
                  radius: 1.5          # 剑气判定半径（伤害判定，不是视觉大小）
                  # ---- 这道弧的视觉造型（与挥击/横扫/幻影剑舞剑气同一套） ----
                  arc-radius: 2.0      # 弧的半径（视觉大小）
                  angle-degrees: 180   # 弧的张角（180 = 半圆，同横扫）
                  # 剑气飞出时的弧面朝向：与挥砍一样「每次随机」。
                  tilt-min-degrees: 0
                  tilt-max-degrees: 180
                  # 粒子与音效直接写在这里（不继承任何其他段）。
                  particle:
                    enabled: true
                    particle: CRIT
                    count: 120
                    radius: 0.08
                    effect: break
                  sound:
                    enabled: true
                    sound: ENTITY_PLAYER_ATTACK_SWEEP
                    volume: 1.0
                    pitch: 1.0
                # ---- 技能强化成长（技能强化台：/rpgitem skillforge） ----
                # 用「每级 +X% 剑气伤害」这种通用成长，而不是改结构（例如剑气数量）。
                leveling:
                  max-level: 20
                  per-level:
                    sword-aura.weapon-percent: 10     # 每级 +10% 剑气伤害（Lv.1=200% → Lv.20=390%）
                    sword-aura.max-distance: 0.5      # 每级 +0.5 格射程
                """;
    }

    private String crossSlashSkillExample() {
        return """
                # 十字斩 - 独立技能模板
                # 本文件位于 plugins/EarthRPGItem/skills/十字斩.yml
                # 插件会自动把本文件注册为一个技能，技能 ID 就是文件名：十字斩
                # 不需要在 config.yml 里添加任何配置。
                display-name: "&d十字斩"
                material: ENCHANTED_BOOK
                skill-group: "技能组2"
                supported-weapons: [ANY]
                attack-speed-cooldown: true
                cooldown-name: "十字斩"
                cooldown-enabled: true
                messages-enabled: true
                global-cooldown: false
                cross-slash:
                  distance: 2
                  radius: 2
                  height: 2
                  angle-degrees: 110
                  horizontal:
                    damage: 25
                    weapon-percent: 70
                    damage-type: PHYSICAL
                    can-crit: true
                    # count = 整条水平弧的粒子总数，radius = 轨迹粗细。
                    particle:
                      enabled: true
                      particle: CRIT
                      count: 180
                      radius: 0.08
                      effect: break
                  vertical:
                    damage: 25
                    weapon-percent: 70
                    damage-type: PHYSICAL
                    can-crit: true
                    # count = 整条垂直弧的粒子总数，radius = 轨迹粗细。
                    particle:
                      enabled: true
                      particle: CRIT
                      count: 180
                      radius: 0.08
                      effect: break
                  sound:
                    enabled: true
                    sound: ENTITY_PLAYER_ATTACK_SWEEP
                    volume: 1.0
                    pitch: 1.15
                """;
    }

    /**
     * 后撤 - 独立技能模板。
     * <p>
     * 与突进同一套位移参数，唯一区别是方向固定向后（见 {@code FSkills.runBackstepSkill}）。
     */
    private String backstepSkillExample() {
        return """
                # 后撤 - 独立技能模板
                # 本文件位于 plugins/EarthRPGItem/skills/后撤.yml
                # 插件会自动把本文件注册为一个技能，技能 ID 就是文件名：后撤
                # 不需要在 config.yml 里添加任何配置。
                display-name: "&b后撤"
                material: ENCHANTED_BOOK
                skill-group: "技能组4"
                supported-weapons: [ANY]
                # 固定 5 秒冷却（不使用攻速冷却）。
                cooldown-seconds: 5
                # 释放时间 0.5 秒：这 0.5 秒内不能再放别的技能。
                cast-time-seconds: 0.5
                cooldown-name: "后撤"
                cooldown-enabled: true
                messages-enabled: true
                global-cooldown: false
                # 后撤：与突进同参数，方向固定向后（不看视线朝向，永远往身后退）。
                backstep:
                  # 位移距离（格）。实际速度 = distance / duration-ticks，所以调大 duration 会更平缓。
                  distance: 5
                  # 位移持续 tick（8 tick = 0.4 秒）。
                  duration-ticks: 8
                  # 位移期间的无敌帧（tick），防止后撤时被贴脸连击。
                  invulnerability-ticks: 6
                  particle:
                    enabled: true
                    particle: CLOUD
                    count: 12
                    radius: 0.5
                    effect: break
                  sound:
                    enabled: true
                    sound: ENTITY_PLAYER_ATTACK_SWEEP
                    volume: 1.0
                    pitch: 1.2
                """;
    }

    /**
     * 次元斩 - 独立技能模板。
     * <p>
     * 这个模板是「技能连锁」的样板：一次施放分两段完成。
     * <ol>
     *   <li><b>施放瞬间</b>：{@code backstep} 后撤一步，{@code buffs} 给自己挂 1 秒缓慢 IV；</li>
     *   <li><b>1 秒后</b>：{@code chain} 把 {@code dimensional-slash} 放出来。</li>
     * </ol>
     * 因此 {@code cast-time-seconds: 1}（释放时间 1 秒）与 {@code chain.delay-seconds: 1}
     * 必须一致：前者锁住这段时间不能再放别的技能，后者才是真正出刀的时刻。
     * <p>
     * 被 {@code chain.after: cast} 点名的 {@code dimensional-slash} 不会在施放瞬间提前放一次，
     * 见 {@code FSkills.delayedByCastChain}。
     */
    private String dimensionalSlashSkillExample() {
        return """
                # 次元斩 - 独立技能模板（技能连锁样板）
                # 本文件位于 plugins/EarthRPGItem/skills/次元斩.yml
                # 插件会自动把本文件注册为一个技能，技能 ID 就是文件名：次元斩
                # 不需要在 config.yml 里添加任何配置。
                display-name: "&5次元斩"
                material: ENCHANTED_BOOK
                skill-group: "技能组1"
                supported-weapons: [ANY]
                # 释放时间：这 1 秒内不能再释放其他技能。必须与 chain.delay-seconds 一致。
                cast-time-seconds: 1
                cooldown-seconds: 3
                cooldown-name: "次元斩"
                cooldown-enabled: true
                messages-enabled: true
                global-cooldown: false
                # 第一段（施放瞬间）：后撤步。
                backstep:
                  distance: 4
                  duration-ticks: 6
                  invulnerability-ticks: 6
                  particle:
                    enabled: true
                    particle: END_ROD
                    count: 10
                    radius: 0.4
                    effect: break
                  sound:
                    enabled: true
                    sound: ENTITY_ENDERMAN_TELEPORT
                    volume: 0.8
                    pitch: 1.4
                # 第二段（施放瞬间）：1 秒缓慢 IV。
                # SLOWNESS 的 amplifier 从 0 起算：缓慢 IV = amplifier 3。
                buffs:
                  slow-self:
                    type: SLOWNESS
                    amplifier: 3
                    duration-seconds: 1
                # 第三段（1 秒后）：真正的次元斩。
                dimensional-slash:
                  damage: 40
                  weapon-percent: 100
                  distance: 7
                  radius: 3
                  height: 3
                  damage-type: PHYSICAL
                  can-crit: true
                  # 爆炸本体：只有 1 颗 EXPLOSION 粒子（不再铺六圈白墙），就是爆点中心那一下。
                  # 这一段是「叠在爆点之上的彩色层」，与上面的白色爆炸是两回事。
                  particle:
                    enabled: true
                    particle: END_ROD
                    count: 40
                    radius: 3
                    effect: sphere
                  # 新型粒子：在爆炸范围附近撒出若干条【任意方向】的粒子线（随机朝向、不再固定竖着），
                  # 读作被切开的空间裂缝。默认颜色就是蓝色（80,140,255），不写 block/color 时也是蓝色。
                  line-particle:
                    enabled: true
                    particle: DUST
                    count: 160
                    radius: 3.0
                    color: "80,140,255"
                  sound:
                    enabled: true
                    sound: ENTITY_ENDER_DRAGON_GROWL
                    volume: 1.0
                    pitch: 1.0
                # 技能连锁：本次施放的即时段（后撤 + 缓慢）跑完后，等 1 秒再放次元斩。
                chain:
                  after: cast
                  next: dimensional-slash
                  delay-seconds: 1
                """;
    }

    /** 上挑 - 独立技能模板：固定上挑路线粒子 + 把命中的生物往天上击飞。 */
    private String uppercutSkillExample() {
        return """
                # 上挑 - 独立技能模板
                # 本文件位于 plugins/EarthRPGItem/skills/上挑.yml
                # 插件会自动把本文件注册为一个技能，技能 ID 就是文件名：上挑
                # 不需要在 config.yml 里添加任何配置。
                display-name: "&e上挑"
                material: ENCHANTED_BOOK
                skill-group: "技能组2"
                supported-weapons: [ANY]
                cooldown-seconds: 4
                # 释放时间 0.5 秒：这 0.5 秒内不能再放别的技能。
                cast-time-seconds: 0.5
                cooldown-name: "上挑"
                cooldown-enabled: true
                messages-enabled: true
                global-cooldown: false
                uppercut:
                  damage: 20
                  weapon-percent: 70
                  damage-type: PHYSICAL
                  can-crit: true
                  # 刀路出现在身前几格处（每次施放都完全一致，是固定路线，不带随机）。
                  distance: 1.5
                  # 判定半径（水平）与竖直高度：从身前低处一直判到 height 格高。
                  radius: 1.6
                  height: 2.2
                  # 击飞初速度（格/tick）。0.7 只把人略微带离地面（约 3 格高）：
                  # 竖直分量 = launch，水平分量 = 朝前的一小段（launch 的 0.4 倍），
                  # 读作「上挑把人带飞出去」，而不是原地直上直下。
                  launch: 0.7
                  # count = 整条上挑路线的粒子总数，radius = 轨迹粗细。
                  particle:
                    enabled: true
                    particle: CRIT
                    count: 140
                    radius: 0.08
                    effect: break
                  sound:
                    enabled: true
                    sound: ENTITY_PLAYER_ATTACK_SWEEP
                    volume: 1.0
                    pitch: 1.35
                """;
    }

    /** 蹦山击 - 独立技能模板：跃到 5 格高 → 45° 俯冲 → 落地冲击波（缩减）+ 轻微击飞 + 小爆炸。 */
    private String leapStrikeSkillExample() {
        return """
                # 蹦山击 - 独立技能模板
                # 本文件位于 plugins/EarthRPGItem/skills/蹦山击.yml
                # 插件会自动把本文件注册为一个技能，技能 ID 就是文件名：蹦山击
                # 不需要在 config.yml 里添加任何配置。
                display-name: "&c蹦山击"
                material: ENCHANTED_BOOK
                skill-group: "技能组3"
                supported-weapons: [ANY]
                cooldown-seconds: 6
                # 释放时间 2 秒：这段时间内不能再放别的技能（跳跃 + 俯冲 + 落地结算都在这 2 秒内）。
                cast-time-seconds: 2
                cooldown-name: "蹦山击"
                cooldown-enabled: true
                messages-enabled: true
                global-cooldown: false
                leap-strike:
                  damage: 35
                  weapon-percent: 90
                  damage-type: PHYSICAL
                  can-crit: true
                  # 跳跃高度（格）。默认 5，调大跳得更高、俯冲距离更远。
                  leap-height: 5
                  # 俯冲角度（度）。45 = 标准的斜向下砸；越接近 90 越像垂直下砸。
                  dive-angle-degrees: 45
                  # 落地判定半径（水平 + 竖直都用它）。
                  impact-radius: 3
                  # 轻微击飞：只把竖直速度抬到该值（0 = 不击飞）。默认很小，不做成击飞技能。
                  knock-up: 0.35
                  # 落地小爆炸的半径（与上面冲击波是两套独立粒子）。
                  explosion-radius: 1.5
                  # 冲击波粒子：沿用震地的「一圈圈扩散」造型，但半径与粒子数都被缩减，
                  # 不会再铺满半个屏幕。
                  shockwave-particle:
                    enabled: true
                    particle: CLOUD
                    count: 60
                    radius: 3.0
                    effect: break
                  # 落地小爆炸粒子。
                  explosion-particle:
                    enabled: true
                    particle: EXPLOSION
                    count: 8
                    radius: 1.5
                    effect: break
                  sound:
                    enabled: true
                    # 起跳音。注意不能用 ENTITY_PLAYER_JUMP —— 1.21.1 的音效表里没有这个名字
                    #（原版玩家跳跃本来就没有音效事件）；写不存在的名字会被静默忽略（等于没声音）。
                    # ENTITY_HORSE_JUMP 是音效表里最接近「跳跃」的真实音效。
                    sound: ENTITY_HORSE_JUMP
                    volume: 0.9
                    pitch: 0.9
                  # 落地音：与起跳音分开。不写这一段就沿用上面的 sound。
                  impact-sound:
                    enabled: true
                    sound: ENTITY_GENERIC_EXPLODE
                    volume: 1.2
                    pitch: 0.7
                """;
    }

    /** 踢击 - 独立技能模板：短距离小范围波 + 把命中的生物向后推开（damages 段新增 knockback）。 */
    private String kickSkillExample() {
        return """
                # 踢击 - 独立技能模板
                # 本文件位于 plugins/EarthRPGItem/skills/踢击.yml
                # 插件会自动把本文件注册为一个技能，技能 ID 就是文件名：踢击
                display-name: "&f踢击"
                material: ENCHANTED_BOOK
                skill-group: "游侠技能"
                supported-weapons: [ANY]
                cooldown-seconds: 3
                cooldown-name: "踢击"
                cooldown-enabled: true
                messages-enabled: true
                global-cooldown: false
                # 用通用 damages 段：朝准星打一发短距离的小范围波，命中后把生物从身边推开。
                # mode: wave = 沿方向的条形判定 / area = 以自身为圆心的范围判定。
                damages:
                  kick:
                    mode: wave
                    damage: 12
                    weapon-percent: 60
                    damage-type: PHYSICAL
                    can-crit: true
                    # 判定长度 / 半径 / 高度（格）
                    length: 2.5
                    radius: 1.5
                    height: 2.0
                    pierce: false
                    pierce-blocks: false
                    # 击退距离（格）：命中后把目标从玩家身边水平推开并稍微抬一点。0 = 不击退。
                    knockback: 2.2
                    particles:
                      enabled: true
                      particle: CLOUD
                      count: 12
                      radius: 0.6
                      effect: break
                    sound:
                      enabled: true
                      sound: ENTITY_PLAYER_ATTACK_STRONG
                      volume: 1.0
                      pitch: 1.2
                """;
    }

    /**
     * 穿透箭 - 独立技能模板：<b>插件自己的物品弹射物</b>（不是原版箭实体），
     * 本体就是光灵箭的物品图标，直线飞行并穿透多个生物；方块可挡也可穿。
     */
    private String piercingArrowSkillExample() {
        return """
                # 穿透箭 - 独立技能模板
                # 本文件位于 plugins/EarthRPGItem/skills/穿透箭.yml
                # 插件会自动把本文件注册为一个技能，技能 ID 就是文件名：穿透箭
                display-name: "&b穿透箭"
                material: ENCHANTED_BOOK
                skill-group: "游侠技能"
                supported-weapons: [BOW, CROSSBOW]
                cooldown-seconds: 4
                cooldown-name: "穿透箭"
                cooldown-enabled: true
                messages-enabled: true
                global-cooldown: false
                # 用短弓的固定冷却段（shortbow-cd）。
                # 注意：弹射物本体是【插件自己的物品弹射物】——用物品图标当箭（与王之宝库同一套机制），
                # 不是原版箭实体。所以穿透数量不受原版限制，命中判定也不会丢。
                shortbow-cd:
                  # projectile = 本体外观的物品名（SPECTRAL_ARROW = 光灵箭，自带高亮轮廓）
                  projectile: SPECTRAL_ARROW
                  arrow-count: 1
                  speed: 1.6
                  weapon-percent: 85
                  # 穿透箭是单支直线命中，用 line（水平方向），不分叉。
                  spread-mode: line
                  spread-degrees: 0
                  projectile-item:
                    # 本体是否发光（附魔光效）
                    glow: true
                    # 穿透生物数：命中一个后还能继续穿过 3 个。0 = 命中第一个就停。
                    pierce: 3
                    # 是否被方块挡住。true = 撞墙即停（推荐）；false = 穿墙。
                    collide-blocks: true
                    # 命中判定半径（格）。调大更容易打中，调小更考验准头。
                    hit-radius: 0.9
                    # 最大飞行距离（格），到达即消散。
                    max-distance: 48
                    # 贴图摆正角（度）。原版箭贴图的箭头是斜的，实测斜了只改这一个值。
                    tilt-degrees: -45
                    impact-particle:
                      enabled: true
                      particle: END_ROD
                      count: 10
                      radius: 0.4
                    impact-sound:
                      enabled: true
                      sound: ENTITY_ARROW_HIT
                      volume: 1.0
                      pitch: 1.2
                  trail:
                    enabled: true
                    particle: END_ROD
                    count: 2
                    radius: 0.1
                  launch-sound: ENTITY_ARROW_SHOOT
                  launch-volume: 1.0
                  launch-pitch: 0.9
                """;
    }

    /**
     * 三连射 - 独立技能模板：分三批连射，每批间隔可配；每一批各自播发射音效，
     * 且每一批都按玩家<b>当时</b>的视线重新瞄准（连发途中转向也能跟上）。
     */
    private String tripleShotSkillExample() {
        return """
                # 三连射 - 独立技能模板
                # 本文件位于 plugins/EarthRPGItem/skills/三连射.yml
                # 插件会自动把本文件注册为一个技能，技能 ID 就是文件名：三连射
                display-name: "&e三连射"
                material: ENCHANTED_BOOK
                skill-group: "游侠技能"
                supported-weapons: [BOW, CROSSBOW]
                cooldown-seconds: 6
                cooldown-name: "三连射"
                cooldown-enabled: true
                messages-enabled: true
                global-cooldown: false
                shortbow-cd:
                  projectile: ARROW
                  arrow-count: 1
                  # volleys = 打几批；volley-interval-ticks = 每批间隔（20 tick = 1 秒）。
                  # 每一批都会重新读一次视线方向，并各播一次 launch-sound。
                  volleys: 3
                  volley-interval-ticks: 6
                  speed: 2.4
                  weapon-percent: 70
                  # 三连射是「水平直线连发」：三支箭都沿当前准星方向飞，不左右分叉。
                  # 所以这里用 line（水平方向）而不是 cone（分裂箭的锥形霰弹）。
                  # 想把 3 支箭改成水平扇形散开：arrow-count 改成 3 + spread-degrees 调 8~12。
                  spread-mode: line
                  spread-degrees: 6
                  projectile-item:
                    glow: false
                    # 想每箭都穿透就改成 1 或更大
                    pierce: 0
                    collide-blocks: true
                    hit-radius: 0.8
                    max-distance: 48
                    tilt-degrees: -45
                    impact-particle:
                      enabled: true
                      particle: CRIT
                      count: 6
                      radius: 0.3
                    impact-sound:
                      enabled: true
                      sound: ENTITY_ARROW_HIT
                      volume: 0.9
                      pitch: 1.3
                  trail:
                    enabled: true
                    particle: CRIT
                    count: 2
                    radius: 0.08
                  launch-sound: ENTITY_ARROW_SHOOT
                  launch-volume: 1.0
                  launch-pitch: 1.0
                """;
    }

    /**
     * 爆炸箭 - 独立技能模板：命中（生物或方块）时粒子模拟爆炸 + 范围伤害，不炸方块。
     * 发射音用凋灵骷髅的射击音，命中时是爆炸音（不是箭矢命中音）。
     */
    private String explosiveArrowSkillExample() {
        return """
                # 爆炸箭 - 独立技能模板
                # 本文件位于 plugins/EarthRPGItem/skills/爆炸箭.yml
                # 插件会自动把本文件注册为一个技能，技能 ID 就是文件名：爆炸箭
                display-name: "&6爆炸箭"
                material: ENCHANTED_BOOK
                skill-group: "游侠技能"
                supported-weapons: [BOW, CROSSBOW]
                cooldown-seconds: 8
                cooldown-name: "爆炸箭"
                cooldown-enabled: true
                messages-enabled: true
                global-cooldown: false
                shortbow-cd:
                  projectile: ARROW
                  arrow-count: 1
                  speed: 1.5
                  weapon-percent: 50
                  # 爆炸箭是单支直线命中，用 line（水平方向），不分叉。
                  spread-mode: line
                  spread-degrees: 0
                  projectile-item:
                    glow: true
                    pierce: 0
                    collide-blocks: true
                    hit-radius: 0.9
                    max-distance: 48
                    tilt-degrees: -45
                  trail:
                    enabled: true
                    particle: FLAME
                    count: 3
                    radius: 0.12
                  # 发射音：凋灵骷髅的射击音，比普通拉弓更有「重箭」感。
                  # 注意必须是 ENTITY_WITHER_SHOOT —— 1.21.1 里【没有】ENTITY_WITHER_SKELETON_SHOOT，
                  # 写了不存在的音效名会被静默忽略（等于没声音）。
                  launch-sound: ENTITY_WITHER_SHOOT
                  launch-volume: 1.2
                  launch-pitch: 0.8
                  # 命中爆炸：命中生物或方块时在落点炸开。
                  # 不生成爆炸实体、不破坏方块、不点燃；观感完全由粒子模拟。
                  impact-explosion:
                    # 爆炸半径（格）
                    radius: 3.5
                    # 固定爆炸伤害 + 按武器伤害来源的百分比（可只写一个）
                    damage: 20
                    weapon-percent: 60
                    # particle 控制【火球体】的粒子与数量。
                    # 原版爆炸云（EXPLOSION_EMITTER）、黑烟、地面冲击环、火苗柱会由插件自动补齐，
                    # 不需要（也不能）在这里配 —— 爆炸的「像」靠层次，不靠堆颗数。
                    # 所以这里推荐用小贴图（FLAME / SOUL_FIRE_FLAME / SMOKE）；
                    # 写 EXPLOSION 这类整格大贴图时只会点 1..3 颗，否则会糊成一团白。
                    particle:
                      enabled: true
                      particle: FLAME
                      count: 60
                      radius: 3.5
                      effect: break
                    # 爆炸音效。这一段的 enabled 缺省就是 true（爆炸没声音是明显的体验问题），
                    # 所以旧文件即使没写 sound 段也会响；要静音请显式写 enabled: false。
                    sound:
                      enabled: true
                      sound: ENTITY_GENERIC_EXPLODE
                      volume: 1.6
                      pitch: 0.75
                """;
    }

    /**
     * 分裂箭 - 独立技能模板：霰弹式<b>锥形</b>散射——朝准星前方张开一个锥，
     * 抬头/低头时整个锥跟着视线抬起或压下（不是绕轴一圈的圆环）。
     */
    private String splitArrowSkillExample() {
        return """
                # 分裂箭 - 独立技能模板
                # 本文件位于 plugins/EarthRPGItem/skills/分裂箭.yml
                # 插件会自动把本文件注册为一个技能，技能 ID 就是文件名：分裂箭
                display-name: "&d分裂箭"
                material: ENCHANTED_BOOK
                skill-group: "游侠技能"
                supported-weapons: [BOW, CROSSBOW]
                cooldown-seconds: 10
                cooldown-name: "分裂箭"
                cooldown-enabled: true
                messages-enabled: true
                global-cooldown: false
                shortbow-cd:
                  projectile: ARROW
                  arrow-count: 10
                  speed: 2.0
                  weapon-percent: 45
                  # spread-mode: cone = 霰弹式锥形散布（默认，推荐）。
                  #   弹道顺着准星方向张开一个锥，抬头时整个锥跟着抬起。
                  #   与「绕瞄准轴转一圈的圆环」完全不同——圆环看起来是朝四面八方射。
                  # spread-mode: line = 水平一排（旧行为，用 spread-degrees 控制排开角度）。
                  spread-mode: cone
                  # 锥的张角（度）。调大 = 散得更开（霰弹），调小 = 更集中。
                  spread-degrees: 30
                  projectile-item:
                    glow: false
                    pierce: 0
                    collide-blocks: true
                    hit-radius: 0.8
                    max-distance: 32
                    tilt-degrees: -45
                    # 本体缩放。分裂箭一次出 10 支，按 1.0 画每个图标都挺大、还会互相挤在一起，
                    # 所以这里调小到 0.6，读起来更像「一蓬碎屑」而不是「一排大箭」。
                    # 只影响图标视觉大小，命中判定仍由 hit-radius 决定。
                    scale: 0.6
                    impact-particle:
                      enabled: true
                      particle: SPELL_WITCH
                      count: 5
                      radius: 0.3
                    impact-sound:
                      enabled: true
                      sound: ENTITY_ARROW_HIT
                      volume: 0.7
                      pitch: 1.5
                  # 轨迹粒子。这里刻意用 CRIT（很小的一颗星芒）而不是 SPELL_WITCH：
                  # 发射时每支箭都会在枪口画一颗轨迹粒子，10 支同时出膛就是 10 颗，
                  # 用大贴图会糊成一团 —— 换成小粒子后枪口就清爽了。
                  trail:
                    enabled: true
                    particle: CRIT
                    count: 1
                    radius: 0.04
                  launch-sound: ENTITY_ARROW_SHOOT
                  launch-volume: 1.0
                  launch-pitch: 1.4
                """;
    }

    private String gateOfBabylonSkillExample() {
        return """
                # 王之宝库 - 独立技能模板
                # 本文件位于 plugins/EarthRPGItem/skills/王之宝库.yml
                # 插件会自动把本文件注册为一个技能，技能 ID 就是文件名：王之宝库
                # 不需要在 config.yml 里添加任何配置。
                display-name: "&6王之宝库"
                material: ENCHANTED_BOOK
                skill-group: "技能组3"
                supported-weapons: [ANY]
                # 冷却使用玩家攻速（AttackSpeed）；此时不要写 cooldown-seconds。
                attack-speed-cooldown: true
                cooldown-name: "王之宝库"
                cooldown-enabled: true
                messages-enabled: true
                global-cooldown: false
                # 武器在施法者背后或落点上方的空中生成，短暂停留后射向准星所指的位置；飞行穿过方块。
                gate-of-babylon:
                  damage: 0
                  weapon-percent: 60
                  damage-type: PHYSICAL
                  can-crit: true
                  # 不要写 weapon-percent-sources：默认即为全部来源，写了反而会取消列出的类型。
                  weapon-count: 16
                  # 落点区域跟随准星：射线长度 + 落点散布半径（射线越长砸得越远）
                  distance: 16.0
                  spread-radius: 2.5
                  # 生成方式：behind 背后 / sky 天上 / mixed 两者随机
                  spawn-mode: mixed
                  # 背后生成：施法者背后扇形内随机
                  spawn-distance: 4.5
                  spawn-height: 4.5
                  spawn-fan-degrees: 180
                  # 天上生成：落点上方多少格 + 水平偏移半径
                  sky-height: 14.0
                  sky-offset: 2.5
                  # 生成后等待 0~15 tick 再发射（每把随机）
                  launch-delay-ticks: 15
                  # 飞行速度：调小能看清武器姿态，调大更像瞬间命中
                  fall-speed: 0.8
                  linger-seconds: 2.0
                  impact-radius: 2.5
                  impact-height: 2.0
                  # 16 把武器各结算一次会放大成 16 倍伤害，所以同一目标必须有上限。
                  max-hits-per-target: 3
                  # 剑贴图的剑刃是斜的，不先摆正就会整体偏一个固定角度（剑刃斜着不指落点）。
                  # 实测偏 45° 时改这个值即可，不需要重新编译。
                  blade-tilt-degrees: -45
                  # 同一材质写多次 = 提高出现概率；远程武器刻意不列。
                  weapon-materials: [GOLDEN_SWORD, GOLDEN_SWORD, GOLDEN_SWORD, GOLDEN_AXE, GOLDEN_AXE, NETHERITE_SWORD, DIAMOND_SWORD, IRON_SWORD, NETHERITE_AXE, DIAMOND_AXE, TRIDENT, MACE]
                  # count = 每经过一格路径撒多少粒子，radius = 轨迹粗细。
                  trail:
                    enabled: true
                    particle: DUST
                    block: "255,215,0"
                    count: 4
                    radius: 0.25
                  # 武器出现时的金色特效
                  spawn-particle:
                    enabled: true
                    particle: DUST
                    block: "255,215,0"
                    count: 8
                    radius: 0.4
                  # 悬停期在剑柄处画的平面金色圆环，模仿武器从宝库的圆形门里被拔出。
                  # count = 圆环粒子数，radius = 圆环半径（格）；plane: blade 垂直于剑刃 / horizontal 水平。
                  ripple:
                    enabled: true
                    particle: DUST
                    block: "255,215,0"
                    count: 12
                    radius: 0.35
                    plane: blade
                  # 落地金色上升粒子；radius 会被放大成上升高度（约 radius x 8）。
                  impact-particle:
                    enabled: true
                    particle: DUST
                    block: "255,215,0"
                    count: 6
                    radius: 0.4
                  # 落地爆炸：纯粒子、不破坏方块与掉落物。
                  # 粒子炸在落点本身（不再飘到上方一格），count 个粒子散布在 radius 格半径的球内。
                  # EXPLOSION = 原版白色爆炸烟团，这才是「爆炸」的观感（FLAME 只有火苗、不像爆炸）。
                  explosion-particle:
                    enabled: true
                    particle: EXPLOSION
                    count: 2
                    radius: 0.6
                  sound:
                    enabled: true
                    sound: ENTITY_ENDER_DRAGON_FLAP
                    volume: 1.0
                    pitch: 1.0
                  # 次元斩那套爆炸本身没有音效，所以这里补一个。
                  impact-sound:
                    enabled: true
                    sound: ENTITY_GENERIC_EXPLODE
                    volume: 0.7
                    pitch: 1.3
                """;
    }

    private void createExample(String folderName, String fileName, String content) {
        if (!exampleGenerationFolders.contains(folderName)) return;
        File file = new File(new File(plugin.getDataFolder(), folderName), fileName);
        if (file.exists()) return;
        try {
            Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            plugin.getLogger().warning("Cannot create example RPGItem file: " + file.getPath());
        }
    }

    private boolean hasNoItemConfigs(String folderName) {
        File folder = new File(plugin.getDataFolder(), folderName);
        File[] configs = folder.listFiles((directory, name) -> isItemConfigFile(name));
        return configs == null || configs.length == 0;
    }

    private FixConfig parseFixConfig(YamlConfiguration config) {
        ConfigurationSection attributes = config.getConfigurationSection("attributes");
        if (attributes != null) {
            Pattern pattern = Pattern.compile("(?i)^Fix/(\\d+)s$");
            for (String key : attributes.getKeys(false)) {
                Matcher matcher = pattern.matcher(key);
                if (!matcher.matches()) continue;
                int interval = Math.max(1, parseInt(matcher.group(1), 1));
                return new FixConfig(config.getDouble("attributes." + key, 0), interval);
            }
        }
        return new FixConfig(config.getDouble("attributes.Fix/s", config.getDouble("attributes.Fix", 0)), 1);
    }

    private record FixConfig(double amount, int intervalSeconds) { }

    private Color parseColor(String value) {
        if (value == null || value.isBlank()) return Color.fromRGB(160, 101, 64);
        String normalized = value.trim();
        try {
            if (normalized.startsWith("#")) return Color.fromRGB(Integer.parseInt(normalized.substring(1), 16));
            String[] rgb = normalized.split(",");
            if (rgb.length == 3) return Color.fromRGB(clampColor(rgb[0]), clampColor(rgb[1]), clampColor(rgb[2]));
        } catch (IllegalArgumentException ignored) {
        }
        plugin.getLogger().warning("Invalid leather armor color '" + value + "', using vanilla leather color.");
        return Color.fromRGB(160, 101, 64);
    }

    private int clampColor(String value) {
        return Math.max(0, Math.min(255, Integer.parseInt(value.trim())));
    }

    private List<String> renderAttributeLore(RpgItem item, Map<String, Integer> randomValues, BonusDisplay bonus, GemDisplay gems) {
        if (!plugin.isFeatureEnabled("lore-attributes") || !plugin.getConfig().getBoolean("lore-templates.enabled", true)) return List.of();
        List<String> lines = new ArrayList<>();
        addTemplateAttribute(lines, "attack", item.attack(), "攻击", "Attack", "", "attack", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "attackpercent", item.attackPercent(), "攻击百分比", "Attack Percent", "%", "attack", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "armor", item.armor(), "护甲", "Armor", "", "armor", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "armorpercent", item.armorPercent(), "护甲百分比", "Armor Percent", "%", "armor", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "health", item.health(), "生命值", "Health", "", "health", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "vitality", item.vitality(), "体力", "Vitality", "", "vitality", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "recovery", item.recovery(), "生命恢复", "Recovery", "/秒", "recovery", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "recoverypercent", item.recoveryPercent(), "百分比生命恢复", "Recovery Percent", "%/秒", "recovery", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "critchance", item.critChance(), "暴击概率", "CritChance", "%", "crit-chance", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "critdamage", item.critDamage(), "暴击伤害", "CritDamage", "", "crit-damage", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "critdamagepercent", item.critDamagePercent(), "暴击伤害百分比", "CritDamagePercent", "%", "crit-damage", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "attackspeed", item.attackSpeed(), "攻击速度", "AttackSpeed", "", "attack-speed", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "lifesteal", item.lifeSteal(), "生命偷取", "Life Steal", "", "life-steal", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "lifestealpercent", item.lifeStealPercent(), "生命偷取百分比", "Life Steal Percent", "%", "life-steal", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "magicdamage", item.magicDamage(), "魔法伤害", "Magic Damage", "", "magic-damage", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "magicdamagepercent", item.magicDamagePercent(), "魔法伤害百分比", "Magic Damage Percent", "%", "magic-damage", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "bowdamage", item.bowDamage(), "弓箭伤害", "BowDamage", "", "bow-damage", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "bowdamagepercent", item.bowDamagePercent(), "弓箭伤害百分比", "BowDamagePercent", "%", "bow-damage", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "exdraw", item.exDraw(), "追加拉弓", "Extra Draw", "", "exdraw", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "envarmor", item.envArmor(), "环境护甲", "Environment Armor", "", "env-armor", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "envarmorpercent", item.envArmorPercent(), "环境护甲百分比", "Environment Armor Percent", "%", "env-armor", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "magicarmor", item.magicArmor(), "魔法护甲", "Magic Armor", "", "magic-armor", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "magicarmorpercent", item.magicArmorPercent(), "魔法护甲百分比", "Magic Armor Percent", "%", "magic-armor", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "resistance", item.resistance(), "元素抗性", "Resistance", "%", "resistance", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "bowarmor", item.bowArmor(), "弓弩护甲", "Bow Armor", "", "bow-armor", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "bowarmorpercent", item.bowArmorPercent(), "弓弩护甲百分比", "Bow Armor Percent", "%", "bow-armor", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "critarmor", item.critArmor(), "暴击抗性", "Crit Armor", "", "crit-armor", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "critarmorpercent", item.critArmorPercent(), "暴击抗性百分比", "Crit Armor Percent", "%", "crit-armor", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "evasion", item.evasion(), "闪避", "Evasion", "%", "evasion", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "armorpen", item.armorPen(), "护甲穿透", "Armor Penetration", "%", "armor-pen", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "truedamage", item.trueDamage(), "真实伤害", "True Damage", "", "true-damage", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "backstrikedamage", item.backstrikeDamage(), "背刺伤害", "Backstrike Damage", "", "backstrike-damage", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "assdamage", item.assDamage(), "刺客伤害", "Assassin Damage", "", "ass-damage", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "pctdamage", item.pctDamage(), "百分比伤害", "Percent Damage", "%", "pct-damage", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "pstdamage", item.pstDamage(), "持续伤害", "Persistent Damage", "%", "pst-damage", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "backstrikedamagepercent", item.backstrikeDamagePercent(), "背刺伤害百分比", "Backstrike Damage Percent", "%", "backstrike-damage", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "assdamagepercent", item.assDamagePercent(), "刺客伤害百分比", "Assassin Damage Percent", "%", "ass-damage", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "icedamagepercent", item.iceDamagePercent(), "冰属性伤害百分比", "IceDamagePercent", "%", "ice-damage", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "firedamagepercent", item.fireDamagePercent(), "火属性伤害百分比", "FireDamagePercent", "%", "fire-damage", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "lightdamagepercent", item.lightDamagePercent(), "光属性伤害百分比", "LightDamagePercent", "%", "light-damage", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "darkdamagepercent", item.darkDamagePercent(), "暗属性伤害百分比", "DarkDamagePercent", "%", "dark-damage", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "iceArmor", item.iceArmor(), "冰属性护甲", "Ice Armor", "", "ice-armor", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "icearmorpercent", item.iceArmorPercent(), "冰属性护甲百分比", "IceArmorPercent", "%", "ice-armor", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "fireArmor", item.fireArmor(), "火属性护甲", "Fire Armor", "", "fire-armor", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "firearmorpercent", item.fireArmorPercent(), "火属性护甲百分比", "FireArmorPercent", "%", "fire-armor", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "lightArmor", item.lightArmor(), "光属性护甲", "Light Armor", "", "light-armor", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "lightarmorpercent", item.lightArmorPercent(), "光属性护甲百分比", "LightArmorPercent", "%", "light-armor", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "darkArmor", item.darkArmor(), "暗属性护甲", "Dark Armor", "", "dark-armor", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "darkarmorpercent", item.darkArmorPercent(), "暗属性护甲百分比", "DarkArmorPercent", "%", "dark-armor", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "attackRange", item.attackRange(), "攻击范围", "Attack Range", "", "attack-range", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "parry", item.parry(), "招架", "Parry", "%", "parry", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "counterattack", item.counterAttack(), "反击", "Counter Attack", "%", "counter-attack", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "dodge", item.dodge(), "闪避", "Dodge", "", "dodge", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "block", item.block(), "格挡", "Block", "", "block", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "determination", item.determination(), "决意", "Determination", "%", "determination", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "cad", item.cad(), "反击伤害", "Counter Attack Damage", "%", "cad", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "iceDamage", item.iceDamage(), "冰属性伤害", "Ice Damage", "", "ice-damage", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "fireDamage", item.fireDamage(), "火属性伤害", "Fire Damage", "", "fire-damage", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "lightDamage", item.lightDamage(), "光属性伤害", "Light Damage", "", "light-damage", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "darkDamage", item.darkDamage(), "暗属性伤害", "Dark Damage", "", "dark-damage", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "farmingSpeed", item.farmingSpeed(), "耕作速度", "Farming Speed", "", "farming-speed", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "miningSpeed", item.miningSpeed(), "采矿速度", "Mining Speed", "", "mining-speed", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "forestSpeed", item.forestSpeed(), "伐木速度", "Forest Speed", "", "forest-speed", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "farmingFortune", item.farmingFortune(), "耕作时运", "Farming Fortune", "", "farming-fortune", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "miningFortune", item.miningFortune(), "采矿时运", "Mining Fortune", "", "mining-fortune", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "forestFortune", item.forestFortune(), "伐木时运", "Forest Fortune", "", "forest-fortune", randomValues, bonus, gems, item);
        // 采集等级加成：手持工具时跨过 gathering.yml 的挖掘等级门槛。
        addTemplateAttribute(lines, "farmingLevel", item.farmingLevel(), "农业等级", "Farming Level", "", "farming-level", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "miningLevel", item.miningLevel(), "挖矿等级", "Mining Level", "", "mining-level", randomValues, bonus, gems, item);
        addTemplateAttribute(lines, "forestLevel", item.forestLevel(), "砍树等级", "Forest Level", "", "forest-level", randomValues, bonus, gems, item);
        if (item.level() > 0) addTemplateAttribute(lines, "level", item.level(), "战斗等级要求", "Combat Level", "", "level", randomValues, bonus, gems, item);
        if (item.deputy()) addTemplateFlag(lines, "deputy", "副手生效", "Deputy");
        addVanillaAttributeLore(lines, item);
        addBuffLore(lines, item);
        if (lines.isEmpty()) return lines;
        String top = plugin.getConfig().getString("lore-templates.attribute-box.top", "&7┌──────────────────────────────────────┐");
        String bottom = plugin.getConfig().getString("lore-templates.attribute-box.bottom", "&7└──────────────────────────────────────┘");
        lines.add(0, top);
        lines.add(bottom);
        return lines;
    }

    private void addTemplateAttribute(List<String> lines, String key, double baseValue, String zh, String en, String suffix, String feature,
                                      Map<String, Integer> randomValues, BonusDisplay bonus, GemDisplay gems, RpgItem item) {
        if (!plugin.isFeatureEnabled(feature)) return;
        String normalizedKey = key.toLowerCase(Locale.ROOT);
        RandomAttributeRange range = item.randomAttributes().get(normalizedKey);
        if (range == null) range = item.randomAttributes().get(key);
        Integer resolved = randomValues.get(normalizedKey);
        double value = resolved != null ? resolved : (range == null ? baseValue : range.minimum());
        // A gem can grant an attribute the item itself has none of — a ruby's Health on a plain sword —
        // so the line is kept whenever *either* source contributes. Skipping on the item's own zero
        // would hide the gem's bracket entirely and make a socketed gem look like it did nothing.
        double gemValue = gems.disabled() ? 0 : gems.values().getOrDefault(normalizedKey, 0.0);
        if (value == 0 && gemValue == 0 && (range == null || range.maximum() == 0)) return;
        // With reroll-on-use the value changes on every settlement, so the lore advertises the
        // range. Otherwise the value rolled at creation is what the player actually has, and
        // showing a range there would misdescribe the item.
        boolean ranged = range != null && range.minimum() != range.maximum() && rerollOnUse();
        // The base value only. The enhancement is printed as its own trailing bracket so the two are
        // never conflated — see bonusText.
        String displayValue = ranged
                ? trimNumber(range.minimum()) + "-" + trimNumber(range.maximum())
                : trimNumber(value);
        String path = "lore-templates.attributes." + normalizedKey;
        String icon = plugin.getConfig().getString(path + ".icon", plugin.getConfig().getString("lore-templates.defaults.icon", "&7◆"));
        String zhColor = plugin.getConfig().getString(path + ".zh-color", plugin.getConfig().getString("lore-templates.defaults.zh-color", "&6"));
        String enColor = plugin.getConfig().getString(path + ".en-color", plugin.getConfig().getString("lore-templates.defaults.en-color", "&7"));
        String valueColor = plugin.getConfig().getString(path + ".value-color", plugin.getConfig().getString("lore-templates.defaults.value-color", "&f"));
        String nameSeparator = plugin.getConfig().getString("lore-templates.defaults.name-separator", " ");
        String valuePrefix = value >= 0 ? plugin.getConfig().getString("lore-templates.defaults.positive-prefix", "+") : "";
        String customZh = plugin.getConfig().getString(path + ".zh", zh);
        String customEn = plugin.getConfig().getString(path + ".en", en);
        // An attribute the item itself has none of, kept only because a gem grants it, prints just the
        // gem bracket: "生命值 Health [+6]" rather than the noise of "+0" in front of it.
        String ownPart = !ranged && value == 0
                ? ""
                : " " + valueColor + valuePrefix + displayValue + suffix;
        lines.add(plugin.getConfig().getString("lore-templates.defaults.prefix", "  ") + icon + " " + zhColor + customZh + nameSeparator + enColor + customEn + ownPart + gemText(gems, normalizedKey, suffix) + bonusText(bonus, normalizedKey, suffix, ranged));
    }

    /**
     * The blue {@code [+N]} / {@code [+N%]} figure appended after one attribute line.
     * <p>
     * The {@code %} is decided by the attribute's own suffix rather than by the bonus value, so a
     * percentage attribute always reads as a percentage. Two deliberate asymmetries:
     * <ul>
     *   <li><b>A percentage that rounds below 1% still prints {@code [+0%]}.</b> Suppressing it would
     *       make the attribute look untouched, which is exactly what the bracket exists to disprove:
     *       an item with {@code CritChance 3} at a low level gains less than half a point, and the
     *       player should see that the stat <em>is</em> being enhanced rather than suspect it was
     *       skipped.</li>
     *   <li><b>A flat attribute that rounds to 0 prints nothing.</b> {@code [+0]} on a plain damage
     *       line carries no information and would be pure noise.</li>
     * </ul>
     * Both cases are already rounded to integers by {@link #scaleAttributes}, so what is printed
     * here is exactly what gets settled.
     */
    /**
     * The green {@code [+N]} figure for the gems feeding one attribute, or "" when none feed it.
     * <p>
     * Printed before the blue enhancement bracket so a line reads "own value, what the gems added,
     * what enhancing added". Unlike the enhancement there is no {@code [+0]} case: a gem either feeds
     * this attribute or it does not, and a bracket that grants nothing would be a false claim. The
     * keys come from {@link #gemValueMap(List)}, which keeps only attributes settlement recognises,
     * so a bracket can never be printed for something that is not actually granted.
     */
    private String gemText(GemDisplay gems, String key, String suffix) {
        if (gems.disabled()) return "";
        double amount = gems.values().getOrDefault(key, 0.0);
        if (amount == 0) return "";
        boolean percent = suffix != null && suffix.contains("%");
        return " " + gems.color() + "[+" + trimNumber(amount) + (percent ? "%" : "") + "]";
    }

    private String bonusText(BonusDisplay bonus, String key, String suffix, boolean ranged) {
        if (bonus.disabled()) return "";
        // contains, not equals: recovery-percent's suffix is "%/秒", so an exact match would
        // misclassify it as a flat stat and print [+2] instead of [+2%].
        boolean percent = suffix != null && suffix.contains("%");
        String tail = percent ? "%" : "";
        if (ranged) {
            double low = bonus.minimum().getOrDefault(key, 0.0);
            double high = bonus.maximum().getOrDefault(key, 0.0);
            if (low == 0 && high == 0 && !percent) return "";
            String body = low == high
                    ? "+" + trimNumber(low) + tail
                    : "+" + trimNumber(low) + tail + "~+" + trimNumber(high) + tail;
            return " " + bonus.color() + "[" + body + "]";
        }
        double amount = bonus.rolled().getOrDefault(key, 0.0);
        if (amount == 0 && !percent) return "";
        return " " + bonus.color() + "[+" + trimNumber(amount) + tail + "]";
    }

    /** Original-attribute modifiers (vanilla-attributes / minecraft-attributes) shown on the item. */
    private void addVanillaAttributeLore(List<String> lines, RpgItem item) {
        if (item.type() == RpgItemType.ACCESSORY || item.vanillaAttributes().isEmpty()) return;
        if (!plugin.getConfig().getBoolean("lore-templates.show-vanilla-attributes", true)) return;
        ConfigurationSection names = plugin.getConfig().getConfigurationSection("lore-templates.vanilla-attribute-names");
        String prefix = plugin.getConfig().getString("lore-templates.defaults.prefix", "  ");
        String icon = plugin.getConfig().getString("lore-templates.defaults.icon", "&7◆");
        String zhColor = plugin.getConfig().getString("lore-templates.defaults.zh-color", "&6");
        String enColor = plugin.getConfig().getString("lore-templates.defaults.en-color", "&7");
        String valueColor = plugin.getConfig().getString("lore-templates.defaults.value-color", "&f");
        for (VanillaAttributeConfig config : item.vanillaAttributes()) {
            if (config.amount() == 0) continue;
            String key = normalizeVanillaKey(config.attribute());
            String zhName = names == null ? "" : names.getString(key, "");
            if (zhName.isBlank()) zhName = config.attribute();
            boolean percent = config.operation().equalsIgnoreCase("ADD_SCALAR")
                    || config.operation().equalsIgnoreCase("MULTIPLY_SCALAR_1");
            double shown = config.amount() * (percent ? 100 : 1);
            String sign = config.amount() >= 0 ? "+" : "";
            String display = trimNumber(shown) + (percent ? "%" : "");
            lines.add(prefix + icon + " " + zhColor + zhName + " " + enColor + config.attribute() + " "
                    + valueColor + sign + display);
        }
    }

    /** Potion buffs (buffs config) shown on the item, e.g. Armor's 力量 buff. */
    private void addBuffLore(List<String> lines, RpgItem item) {
        if (item.buffs().isEmpty()) return;
        if (!plugin.getConfig().getBoolean("lore-templates.show-buffs", true)) return;
        String prefix = plugin.getConfig().getString("lore-templates.defaults.prefix", "  ");
        String icon = plugin.getConfig().getString("lore-templates.defaults.icon", "&7◆");
        String zhColor = plugin.getConfig().getString("lore-templates.defaults.zh-color", "&6");
        String enColor = plugin.getConfig().getString("lore-templates.defaults.en-color", "&7");
        String valueColor = plugin.getConfig().getString("lore-templates.defaults.value-color", "&f");
        for (BuffConfig buff : item.buffs()) {
            PotionEffectType type = PotionEffectType.getByName(buff.type().toUpperCase(Locale.ROOT));
            String zhName = type != null ? buffName(type) : buff.type();
            lines.add(prefix + icon + " " + zhColor + zhName + " " + enColor + "(" + buff.type().toUpperCase(Locale.ROOT) + ")"
                    + " " + valueColor + "+" + (buff.amplifier() + 1) + " 级");
        }
    }

    private String buffName(PotionEffectType type) {
        return switch (type.getName().toUpperCase(Locale.ROOT)) {
            case "STRENGTH", "INCREASE_DAMAGE" -> "力量";
            case "SPEED" -> "速度";
            case "HASTE" -> "急迫";
            case "RESISTANCE", "DAMAGE_RESISTANCE" -> "抗性提升";
            case "JUMP", "JUMP_BOOST" -> "跳跃提升";
            case "REGENERATION" -> "生命恢复";
            case "FIRE_RESISTANCE" -> "抗火";
            case "WATER_BREATHING" -> "水下呼吸";
            case "INVISIBILITY" -> "隐身";
            case "NIGHT_VISION" -> "夜视";
            case "HEALTH_BOOST" -> "生命提升";
            case "ABSORPTION" -> "伤害吸收";
            case "SATURATION" -> "饱和";
            case "GLOWING" -> "发光";
            case "LUCK" -> "幸运";
            case "SLOW_FALLING" -> "缓降";
            case "CONDUIT_POWER" -> "海灵祝福";
            case "DOLPHINS_GRACE" -> "海豚恩泽";
            case "BAD_OMEN" -> "不祥之兆";
            default -> type.getName();
        };
    }

    private static String normalizeVanillaKey(String raw) {
        if (raw == null) return "";
        return raw.toLowerCase(Locale.ROOT)
                .replace("minecraft:", "")
                .replace("attribute.", "")
                .replace("generic.", "")
                .replace("generic_", "");
    }

    private void addTemplateFlag(List<String> lines, String key, String zh, String en) {        String path = "lore-templates.attributes." + key;
        String icon = plugin.getConfig().getString(path + ".icon", plugin.getConfig().getString("lore-templates.defaults.icon", "&7◆"));
        String zhColor = plugin.getConfig().getString(path + ".zh-color", plugin.getConfig().getString("lore-templates.defaults.zh-color", "&6"));
        String enColor = plugin.getConfig().getString(path + ".en-color", plugin.getConfig().getString("lore-templates.defaults.en-color", "&7"));
        String separator = plugin.getConfig().getString("lore-templates.defaults.name-separator", " ");
        lines.add(plugin.getConfig().getString("lore-templates.defaults.prefix", "  ") + icon + " " + zhColor + zh + separator + enColor + en);
    }

    private static String stripColor(String value) { return value.replaceAll("&[0-9a-fk-or]", ""); }
    private int getAllItemCount() { return items.values().stream().mapToInt(List::size).sum(); }
    private void addLore(List<String> loreLines, String feature, double value, String prefix) { if (plugin.isFeatureEnabled(feature) && value != 0) loreLines.add(prefix + trimNumber(value)); }
    private static int parseInt(String value, int fallback) { try { return Integer.parseInt(value); } catch (NumberFormatException exception) { return fallback; } }
    private static double parsePercent(YamlConfiguration config, String path) {
        Object value = config.get(path);
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String text) {
            String normalized = text.trim();
            if (normalized.endsWith("%")) normalized = normalized.substring(0, normalized.length() - 1).trim();
            try { return Double.parseDouble(normalized); } catch (NumberFormatException ignored) { }
        }
        return 0;
    }
    private static String trimNumber(double value) { return value == Math.rint(value) ? String.valueOf((int) value) : String.valueOf(value); }
    private static Enchantment findEnchantment(String value) {
        Enchantment enchantment = Enchantment.getByKey(NamespacedKey.minecraft(value.toLowerCase().replace("minecraft:", "")));
        return enchantment != null ? enchantment : Enchantment.getByName(value.toUpperCase());
    }
    /**
     * 物品等级门槛实际比对的等级。
     *
     * <p>装了 EarthExp 时用它的<b>战斗等级</b>（{@code Stat.COMBAT}），否则退回原版等级。
     * 退回是必要的：EarthExp 是可选附属，没装时如果把所有人都判成 0 级，
     * 所有带 {@code level} 的物品会突然全部失效。
     */
    public int effectiveLevel(Player player) {
        if (player == null) return 0;
        EarthExpBridge bridge = plugin.getEarthExpBridge();
        int combat = bridge == null ? -1 : bridge.combatLevel(player);
        return combat >= 0 ? combat : player.getLevel();
    }

    /**
     * 等级不足的提示。
     *
     * <p>带上「需要 / 当前」两个数字：只说「等级不足」的话，玩家看不出差多少，
     * 也看不出插件比的是战斗等级还是原版等级。
     */
    private void sendLevelMessage(Player player, int required) {
        timedMessage(levelMessageTimes, player, "&c战斗等级不足：需要 &f" + required
                + " &c级，当前 &f" + effectiveLevel(player) + " &c级，属性未生效");
    }
    private void sendDurabilityMessage(Player player, String message) { timedMessage(durabilityMessageTimes, player, message); }
    private void timedMessage(Map<UUID, Long> times, Player player, String message) {
        long now = System.currentTimeMillis();
        long last = times.getOrDefault(player.getUniqueId(), 0L);
        if (now - last >= 1000) {
            player.sendMessage(Texts.color(message));
            times.put(player.getUniqueId(), now);
        }
    }
}