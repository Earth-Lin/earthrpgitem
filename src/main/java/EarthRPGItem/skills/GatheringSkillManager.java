package EarthRPGItem.skills;

import EarthRPGItem.EarthRPGItem;
import EarthRPGItem.api.GatheringExperienceEvent;
import EarthRPGItem.api.GatheringLevelProvider;
import EarthRPGItem.collection.CollectionKind;
import EarthRPGItem.items.RpgItem;
import EarthRPGItem.util.Texts;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 采集经验 + 资源点采掘等级门槛。
 *
 * <p><b>门槛看的是手持工具的采掘等级，不是任何插件的等级。</b>
 * <ul>
 *   <li>资源点的 {@code required-level} 直接和<b>主手工具</b>的
 *       {@code MiningLevel / ForestLevel / FarmingLevel}（attributes 里的采掘等级档位）
 *       比较：工具采掘等级 ≥ 资源点要求 才能开始采集。没有达标工具 = 采不动。</li>
 *   <li>经验：资源点自己的 {@code experience} 配置，采集完成时由
 *       {@code CollectionManager} 调 {@link #awardCollectionPointExperience} 抛
 *       {@link GatheringExperienceEvent} 给别的插件（主线程同步事件）；
 *       本插件不存经验、不算升级。</li>
 *   <li>原版矿物不参与：不给经验、不设门槛。仍想给某些原版方块设采掘等级门槛，
 *       可以在 {@code gathering.yml} 的 {@code required-level} 里配置（可选，默认空），
 *       {@link #onBlockBreak} 会用主手工具的采掘等级拦截。</li>
 *   <li>{@link GatheringLevelProvider} 注册接口保留（API 兼容，供其他插件调用），
 *       但它<b>不参与门槛判定</b>——门槛只读手持工具的采掘等级。</li>
 * </ul>
 */
public final class GatheringSkillManager implements Listener {

    /** 一个原版方块的采掘等级门槛：分类 + 需要的工具采掘等级（可选，默认表为空）。 */
    public record RequirementEntry(CollectionKind category, int level) {}

    private final EarthRPGItem plugin;
    private final File configFile;

    private boolean enabled = true;
    private long denyMessageCooldownMillis = 1500L;
    private String denyMessage = "&c采集失败：需要 &f{category}采掘等级 ≥ {level}&c，你手里的工具是 &f{effective}&c。";

    /** 可选的原版方块采掘门槛表（默认空）。 */
    private final Map<Material, RequirementEntry> requirements = new HashMap<>();
    /** 保留的 provider 注册表（API 兼容，不参与门槛判定）。 */
    private final CopyOnWriteArrayList<GatheringLevelProvider> providers = new CopyOnWriteArrayList<>();
    private final Map<UUID, Long> lastDenyMessage = new HashMap<>();

    public GatheringSkillManager(EarthRPGItem plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "gathering.yml");
    }

    public void reload() {
        requirements.clear();
        if (!configFile.exists()) {
            try {
                plugin.saveResource("gathering.yml", false);
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("无法写入默认 gathering.yml：" + exception.getMessage());
            }
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        enabled = config.getBoolean("enabled", true);
        denyMessageCooldownMillis = Math.max(0L, config.getLong("deny-message-cooldown-ms", 1500L));
        denyMessage = config.getString("messages.level-too-low",
                "&c采集失败：需要 &f{category}采掘等级 ≥ {level}&c，你手里的工具是 &f{effective}&c。");
        readRequirements(config.getConfigurationSection("required-level"));
        plugin.getLogger().info("采集等级系统就绪：原版方块门槛 " + requirements.size() + " 项"
                + "（资源点门槛按手持工具的采掘等级判定）");
    }

    /** 可选的原版方块采掘门槛表（gathering.yml 的 required-level，默认空）。 */
    private void readRequirements(ConfigurationSection section) {
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            Material material = Material.matchMaterial(key);
            if (material == null) {
                plugin.getLogger().warning("gathering.yml required-level 里的 '" + key + "' 不是有效方块，已跳过。");
                continue;
            }
            ConfigurationSection node = section.getConfigurationSection(key);
            if (node == null) {
                plugin.getLogger().warning("gathering.yml required-level 里的 '" + key + "' 不是段落，已跳过。");
                continue;
            }
            CollectionKind category = CollectionKind.parse(node.getString("category", "MINING"));
            int level = Math.max(1, node.getInt("level", 1));
            requirements.put(material, new RequirementEntry(category, level));
        }
    }

    // ------------------------------------------------------------------
    // 工具采掘等级（门槛只读这里）
    // ------------------------------------------------------------------

    /**
     * 主手工具的采掘等级（MiningLevel / ForestLevel / FarmingLevel）。
     * 这就是资源点 required-level 的比对对象——玩家的等级、经验插件的等级一律不参与。
     */
    public int toolLevel(Player player, CollectionKind category) {
        ItemStack held = player.getInventory().getItemInMainHand();
        RpgItem item = plugin.getRpgItemManager().findItem(held);
        if (item == null) return 0;
        return switch (category) {
            case MINING -> featureBonus("mining-level", item.miningLevel());
            case FOREST -> featureBonus("forest-level", item.forestLevel());
            case FARMING -> featureBonus("farming-level", item.farmingLevel());
        };
    }

    private int featureBonus(String feature, double value) {
        return plugin.isFeatureEnabled(feature) ? (int) Math.max(0, Math.round(value)) : 0;
    }

    /** 主手工具是否具备采掘该分类的能力（level > 0，且对应开关开着）。 */
    public boolean hasToolLevel(Player player, CollectionKind category) {
        return toolLevel(player, category) > 0;
    }

    // ------------------------------------------------------------------
    // 门槛（资源点按分类；原版方块按材质）
    // ------------------------------------------------------------------

    /** 资源点门槛：主手工具的采掘等级 ≥ required 才放行。 */
    public boolean canHarvest(Player player, CollectionKind category, int required) {
        if (!plugin.isFeatureEnabled("gathering-levels")) return true;
        if (required <= 0) return true;
        return toolLevel(player, category) >= required;
    }

    /** 原版方块门槛（可选表）：按材质查。没配门槛 = 放行。 */
    public boolean canHarvest(Player player, Material material) {
        RequirementEntry requirement = requirements.get(material);
        if (requirement == null) return true;
        return canHarvest(player, requirement.category(), requirement.level());
    }

    /** 门槛拒绝时调用：取消 + 提示（带节流，避免连挖刷屏）。 */
    public void deny(Player player, CollectionKind category, int required, int effective) {
        long now = System.currentTimeMillis();
        Long last = lastDenyMessage.get(player.getUniqueId());
        if (last != null && now - last < denyMessageCooldownMillis) return;
        lastDenyMessage.put(player.getUniqueId(), now);
        player.sendMessage(Texts.color(denyMessage
                .replace("{category}", category.displayName())
                .replace("{level}", String.valueOf(required))
                .replace("{effective}", String.valueOf(effective))));
    }

    // ------------------------------------------------------------------
    // 发经验
    // ------------------------------------------------------------------

    /**
     * 资源点采集完成时发经验：按资源点自己的 {@code experience} 配置抛事件
     * （主线程同步事件，原版方块永远不会走这里）。
     */
    public void awardCollectionPointExperience(Player player, CollectionKind category, double amount,
                                               Material blockType) {
        if (!plugin.isFeatureEnabled("gathering-xp")) return;
        if (category == null || amount <= 0) return;
        Bukkit.getPluginManager().callEvent(
                new GatheringExperienceEvent(player, category, amount, blockType, true));
    }

    /** 手动发一次经验（API / 游戏内代码用），来源标记为「非采集点」。 */
    public void awardExperience(Player player, CollectionKind category, double amount, Material blockType) {
        if (!plugin.isFeatureEnabled("gathering-xp")) return;
        if (category == null || amount <= 0) return;
        Bukkit.getPluginManager().callEvent(
                new GatheringExperienceEvent(player, category, amount, blockType, false));
    }

    // ------------------------------------------------------------------
    // 保留的 provider 注册表（API 兼容，不参与门槛判定）
    // ------------------------------------------------------------------

    /** 注册一个等级 provider。保留用于 API 兼容；门槛判定不读取它。 */
    public void registerProvider(GatheringLevelProvider provider) {
        if (provider != null && !providers.contains(provider)) providers.add(provider);
    }

    /** 注销 provider。 */
    public void unregisterProvider(GatheringLevelProvider provider) {
        providers.remove(provider);
    }

    public boolean hasProvider() {
        return !providers.isEmpty();
    }

    /** 查询某个分类的 provider 等级（诊断用；不参与门槛判定）。 */
    public int levelOf(Player player, CollectionKind category) {
        int level = 0;
        for (GatheringLevelProvider provider : providers) {
            if (!provider.supports(category)) continue;
            try {
                level = Math.max(level, Math.max(0, provider.getLevel(player, category)));
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("等级 provider 查询 " + category + " 等级时出错："
                        + exception.getClass().getSimpleName() + ": " + exception.getMessage());
            }
        }
        return level;
    }

    /** 可选原版门槛表的条目数（诊断用；默认空）。 */
    public int vanillaGateCount() {
        return requirements.size();
    }

    // ------------------------------------------------------------------
    // 监听：可选的原版方块采掘门槛（不涉及经验）
    // ------------------------------------------------------------------

    /**
     * 只处理 {@code gathering.yml} required-level 里配置过的原版方块（可选，默认表空）。
     * 原版方块破坏不给经验——采集经验只来自资源点。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!enabled) return;
        Player player = event.getPlayer();
        Material material = event.getBlock().getType();
        RequirementEntry requirement = requirements.get(material);
        if (requirement == null) return;
        int tool = toolLevel(player, requirement.category());
        if (tool < requirement.level()) {
            event.setCancelled(true);
            deny(player, requirement.category(), requirement.level(), tool);
        }
    }
}