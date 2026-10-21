package EarthRPGItem.items;

import EarthRPGItem.EarthRPGItem;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Loads {@code enhancement.yml} and answers questions about a single enhancement level.
 * <p>
 * Deliberately owns no item state. An item's enhancement level lives in its own persistent data
 * (see {@link RpgItemManager}), and everything the level <em>means</em> — success rate, failure
 * penalty, and how much it scales the item's attributes — is read from this table on demand. That
 * split is what lets a rebalance here apply to every item already in the world instead of only to
 * items created afterwards.
 */
public final class EnhancementManager {

    /** What happens to an item when an attempt fails. */
    public enum Penalty {
        /** No penalty: the level is unchanged. The safe zone. */
        NONE,
        /** The item loses one level. */
        DOWNGRADE,
        /** The item is destroyed outright. */
        DESTROY;

        static Penalty parse(String raw, Penalty fallback) {
            if (raw == null || raw.isBlank()) return fallback;
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "none", "safe", "nothing", "无", "不惩罚", "安全" -> NONE;
                case "downgrade", "down", "drop", "掉级", "降级" -> DOWNGRADE;
                case "destroy", "break", "破坏", "碎裂", "消失" -> DESTROY;
                default -> fallback;
            };
        }
    }

    /**
     * One rung of the enhancement ladder.
     *
     * @param successRate chance in {@code [0,1]} that the attempt succeeds
     * @param penalty     applied when the attempt fails
     * @param percent     how much of each of the item's own attributes this level adds, as a
     *                    fraction: {@code 1.0} means +100% (the attribute doubles), {@code 3.0}
     *                    means +300%. It scales the item's existing values rather than replacing
     *                    them, so it needs no per-attribute table and automatically covers every
     *                    attribute an item happens to have.
     */
    public record Level(double successRate, Penalty penalty, double percent) {}

    /** Default fallback for {@code bonus-color}; blue reads as "this came from enhancement". */
    private static final String DEFAULT_BONUS_COLOR = "&9";

    private final EarthRPGItem plugin;
    private YamlConfiguration config = new YamlConfiguration();
    private final Map<Integer, Level> levels = new LinkedHashMap<>();
    /** Normalized attribute keys that enhancement must not scale. Overrides {@link #includedStats}. */
    private final Set<String> excludedStats = new TreeSet<>();
    /** Normalized attribute keys eligible for enhancement; empty means "everything not excluded". */
    private final Set<String> includedStats = new TreeSet<>();
    private boolean enabled;
    private int maxLevel;
    private String stoneItemId = "";
    private int stoneAmount = 1;

    public EnhancementManager(EarthRPGItem plugin) {
        this.plugin = plugin;
    }

    /** (Re)reads {@code enhancement.yml}, creating it from the bundled default on first run. */
    public void reload() {
        levels.clear();
        excludedStats.clear();
        includedStats.clear();
        File file = new File(plugin.getDataFolder(), "enhancement.yml");
        if (!file.exists()) {
            try {
                plugin.saveResource("enhancement.yml", false);
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("无法写入默认 enhancement.yml：" + exception.getMessage());
            }
        }
        config = YamlConfiguration.loadConfiguration(file);
        enabled = config.getBoolean("enabled", true);
        maxLevel = Math.max(0, config.getInt("max-level", 0));
        stoneItemId = config.getString("stone.item-id", "");
        if (stoneItemId == null) stoneItemId = "";
        stoneItemId = stoneItemId.trim();
        stoneAmount = Math.max(1, config.getInt("stone.amount", 1));
        for (String key : config.getStringList("excluded-stats")) {
            if (key != null && !key.isBlank()) excludedStats.add(normalize(key));
        }
        for (String key : config.getStringList("included-stats")) {
            if (key != null && !key.isBlank()) includedStats.add(normalize(key));
        }

        ConfigurationSection section = config.getConfigurationSection("levels");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                int level = parseInt(key, -1);
                if (level <= 0) {
                    plugin.getLogger().warning("enhancement.yml 的 levels 下有无法识别的等级键 '" + key + "'，已跳过。");
                    continue;
                }
                ConfigurationSection node = section.getConfigurationSection(key);
                if (node == null) {
                    plugin.getLogger().warning("enhancement.yml 的 levels." + key + " 不是一个段落，已跳过。");
                    continue;
                }
                // Failing safely out of the safe zone is the wrong direction, so the fallback for an
                // unstated penalty is "keep the level" below +5 and "lose a level" from +5 up.
                Penalty fallback = level <= 4 ? Penalty.NONE : Penalty.DOWNGRADE;
                Penalty penalty = Penalty.parse(node.getString("penalty"), fallback);
                double percent = Math.max(0.0, node.getDouble("percent", 0.0));
                if (percent <= 0) {
                    plugin.getLogger().warning("enhancement.yml 的 levels." + level
                            + " 没有配置 percent（或 <= 0），这一级不会提升任何属性。");
                }
                levels.put(level, new Level(clamp01(node.getDouble("success-rate", 1.0)), penalty, percent));
            }
        }
        if (enabled && levels.isEmpty()) {
            plugin.getLogger().warning("enhancement.yml 里 enabled=true 但 levels 是空的，强化将无法进行。");
        }
        plugin.getLogger().info("强化系统：" + (enabled ? "已开启" : "已关闭")
                + "，上限 +" + maxLevel + "，已定义 " + levels.size() + " 级"
                + "，强化属性 " + (includedStats.isEmpty()
                        ? "除 " + excludedStats.size() + " 项外全部"
                        : includedStats.size() + " 项（白名单）"));
    }

    /** Attribute names are matched the same way item configs are, so {@code AttackSpeed} works too. */
    private static String normalize(String key) {
        return key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public int maxLevel() {
        return maxLevel;
    }

    public String stoneItemId() {
        return stoneItemId;
    }

    public int stoneAmount() {
        return stoneAmount;
    }

    /** Whether an attempt at this level consumes an item at all. */
    public boolean consumesStone() {
        return !stoneItemId.isBlank();
    }

    /** The definition of {@code level}, or {@code null} when the level is not configured. */
    public Level level(int level) {
        return levels.get(level);
    }

    /** Whether {@code level} is a rung the ladder actually defines. */
    public boolean hasLevel(int level) {
        return levels.containsKey(level);
    }

    public Map<Integer, Level> levels() {
        return Map.copyOf(levels);
    }

    /**
     * Whether {@code key} may be scaled by enhancement.
     * <p>
     * Two lists decide this, and the precedence is deliberate:
     * <ol>
     *   <li>{@code included-stats}, when non-empty, is a <b>whitelist</b>: anything absent from it is
     *       left alone. This is the shipped default and it lists only damage attributes, because
     *       scaling probability and survivability stats along with damage inflates a character's
     *       power far faster than the damage numbers alone suggest.</li>
     *   <li>{@code excluded-stats} is a <b>hard veto that wins over the whitelist</b>. It exists so
     *       that adding a key back to the whitelist by accident — or a future default that includes
     *       more stats — still cannot re-enable something like attack speed, whose value is capped
     *       elsewhere and whose penalty on heavy weapons is intentional.</li>
     * </ol>
     * An empty {@code included-stats} restores the "everything except the veto list" behaviour.
     */
    public boolean isEnhanced(String key) {
        if (key == null) return false;
        String normalized = normalize(key);
        if (excludedStats.contains(normalized)) return false;
        return includedStats.isEmpty() || includedStats.contains(normalized);
    }

    public Set<String> excludedStats() {
        return Set.copyOf(excludedStats);
    }

    public Set<String> includedStats() {
        return Set.copyOf(includedStats);
    }

    public String bonusColor() {
        String color = config.getString("bonus-color", DEFAULT_BONUS_COLOR);
        return color == null || color.isBlank() ? DEFAULT_BONUS_COLOR : color;
    }

    /**
     * A stable fingerprint of the loaded table.
     * <p>
     * Participates in each item's attribute signature, so editing {@code enhancement.yml} invalidates
     * every item once and makes it re-render. That matters because the <em>stats</em> are derived
     * live on every settlement but the <em>lore</em> is baked into the item: without this, a
     * rebalance would leave the numbers printed on an item disagreeing with the numbers the player
     * actually has, which is worse than either value alone.
     */
    public String revision() {
        StringBuilder builder = new StringBuilder();
        builder.append(enabled).append('/').append(maxLevel).append('/')
                .append(bonusColor()).append('/')
                .append(config.getBoolean("lore.level-line-enabled", true)).append('/')
                .append(config.getString("lore.level-line", "")).append('/')
                .append(config.getBoolean("lore.show-bonus", true)).append(';')
                .append(includedStats).append(';')
                .append(excludedStats).append(';');
        new TreeMap<>(levels).forEach((level, definition) -> builder
                .append(level).append('=')
                .append(definition.successRate()).append(',')
                .append(definition.penalty()).append(',')
                .append(definition.percent())
                .append(';'));
        return builder.toString();
    }

    public boolean loreLevelLineEnabled() {
        return config.getBoolean("lore.level-line-enabled", true);
    }

    public String loreLevelLine(int level) {
        return config.getString("lore.level-line", "&6&l✦ 强化 +{level}").replace("{level}", Integer.toString(level));
    }

    /** Whether to append the blue {@code [+N]} figure after each attribute line. */
    public boolean showBonus() {
        return config.getBoolean("lore.show-bonus", true);
    }

    public String message(String key, String fallback) {
        return config.getString("messages." + key, fallback);
    }

    /** Replaces {@code {name}} placeholders; arguments are alternating name/value pairs. */
    public static String fill(String template, String... pairs) {
        if (template == null) return "";
        String result = template;
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            result = result.replace("{" + pairs[index] + "}", pairs[index + 1]);
        }
        return result;
    }
}
