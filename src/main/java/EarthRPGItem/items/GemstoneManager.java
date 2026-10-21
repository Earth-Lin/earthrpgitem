package EarthRPGItem.items;

import EarthRPGItem.EarthRPGItem;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Loads {@code gemstones.yml}: what each registered item id does when socketed into an item.
 * <p>
 * A gem's <b>physical form</b> is its own registered item file (so it is picked up, dropped and
 * counted like any other RPG item), while this table describes only what it <b>grants</b> and how
 * it <b>renders</b> once socketed. Splitting the two means a gem can be recognised by id without a
 * new item type, and every gem's bonus is defined in one place.
 */
public final class GemstoneManager {

    /** One gem definition. {@code stats} uses the same lowercase keys {@code RpgStats} reads. */
    public record Gemstone(String id, String displayName, String color, String icon, String material,
                           Map<String, Double> stats) {

        public String coloredIcon() {
            String resolved = icon == null || icon.isBlank() ? "◆" : icon;
            String colorCode = color == null || color.isBlank() ? "&f" : color;
            return colorCode + resolved;
        }

        public boolean isEmpty() {
            return id == null || id.isBlank();
        }
    }

    private final EarthRPGItem plugin;
    private final Map<String, Gemstone> gems = new LinkedHashMap<>();

    public GemstoneManager(EarthRPGItem plugin) {
        this.plugin = plugin;
    }

    /** (Re)reads {@code gemstones.yml}, creating it from the bundled default on first run. */
    public void reload() {
        gems.clear();
        File file = new File(plugin.getDataFolder(), "gemstones.yml");
        if (!file.exists()) {
            try {
                plugin.saveResource("gemstones.yml", false);
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("无法写入默认 gemstones.yml：" + exception.getMessage());
            }
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = config.getConfigurationSection("gems");
        if (section == null) {
            plugin.getLogger().warning("gemstones.yml 里没有 gems 段落。");
            return;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection node = section.getConfigurationSection(id);
            if (node == null) continue;
            String normalizedId = id.trim();
            // A socket list is stored comma-joined, so an id containing a comma would split into two
            // phantom gems. Refused loudly rather than silently corrupting every socketed item.
            if (normalizedId.contains(",")) {
                plugin.getLogger().warning("gemstones.yml 的宝石 id \"" + normalizedId + "\" 含有逗号，已跳过（逗号是槽位存储的分隔符）。");
                continue;
            }
            Map<String, Double> stats = readStats(node.getConfigurationSection("stats"));
            if (stats.isEmpty()) {
                plugin.getLogger().warning("gemstones.yml 的 " + normalizedId + " 没有配置任何 stats 属性。");
            }
            gems.put(normalizedId, new Gemstone(normalizedId,
                    node.getString("display-name", "&f" + normalizedId),
                    node.getString("color", "&f"),
                    node.getString("icon", "◆"),
                    // Only used to generate a placeholder item file when nothing defines this gem's id.
                    // Once an item file exists it is authoritative for the gem's appearance.
                    node.getString("material", "PAPER"),
                    stats));
        }
        plugin.getLogger().info("宝石系统：已加载 " + gems.size() + " 种宝石。");
    }

    private static Map<String, Double> readStats(ConfigurationSection stats) {
        Map<String, Double> result = new LinkedHashMap<>();
        if (stats == null) return result;
        for (String key : stats.getKeys(false)) {
            result.put(normalizeStatKey(key), stats.getDouble(key, 0));
        }
        return result;
    }

    /** Stat keys are matched the same way item configs and RpgStats read them. */
    public static String normalizeStatKey(String key) {
        return key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace("%", "percent");
    }

    /** The definition for {@code gemId}, or null when it is not registered as a gem. */
    public Gemstone get(String gemId) {
        if (gemId == null) return null;
        return gems.get(gemId);
    }

    /** Whether {@code gemId} is a registered gem. */
    public boolean isGem(String gemId) {
        return gemId != null && gems.containsKey(gemId);
    }

    /** Every registered gem id; stable order. */
    public java.util.Set<String> ids() {
        return Collections.unmodifiableSet(gems.keySet());
    }

    /** A fingerprint of the loaded table, folded into item signatures so editing it re-renders items. */
    public String revision() {
        StringBuilder builder = new StringBuilder();
        gems.forEach((id, gem) -> builder.append(id).append('=')
                .append(gem.color()).append('/')
                .append(gem.icon()).append('/')
                .append(gem.displayName()).append('=')
                .append(gem.stats())
                .append(';'));
        return builder.toString();
    }
}