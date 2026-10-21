package EarthRPGItem.skills;

import EarthRPGItem.EarthRPGItem;
import EarthRPGItem.items.GemstoneManager;
import EarthRPGItem.items.RpgItem;
import EarthRPGItem.util.YamlStore;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.*;

/**
 * 技能强化：给每个技能按等级成长，成长规则由技能自己的 yml 描述。
 *
 * <p><b>为什么规则写在技能 yml 里</b>：需求是「支持更改特定数值」——例如幻影剑舞升一级
 * 加 1 次挥砍。把「每级改哪个字段、改多少」放在技能文件里，意味着任何技能的任何数值字段
 * 都能被升级，<b>不需要改任何加载代码</b>，以后新增技能也自动支持。
 *
 * <p><b>等级怎么变成实际效果</b>：{@link #overridesFor} 把「技能 ID + 等级」算成一组
 * {@code 字段路径 -> 数值} 的覆盖，交给 {@code RpgItemManager} 在解析技能配置时打补丁。
 * 因为覆盖结果只跟等级有关，每个等级解析一次后就被缓存，施放时没有任何额外开销。
 *
 * <p><b>等级属于玩家而不是物品</b>：技能背包存的是技能 ID，物品只是取出/放回的凭证。
 * 所以等级按「玩家 + 技能 ID」存在 {@code skill-levels.yml}，取出放回不掉级，
 * 复制物品也刷不出等级。
 */
public final class SkillLevelManager {

    /** 一个消耗段：目标等级不超过 {@code maxLevel} 时使用 {@code gems}。 */
    public record CostTier(int maxLevel, Map<String, Integer> gems) {}

    /** 等级下限。Lv.1 就是技能文件里原本写的值。 */
    public static final int MIN_LEVEL = 1;

    private final EarthRPGItem plugin;
    private final File dataFile;
    private final YamlConfiguration data;

    private boolean enabled = true;
    private int defaultMaxLevel = 20;
    private List<CostTier> defaultTiers = List.of();
    private final Map<String, String> messages = new HashMap<>();
    /** 技能 ID -> 成长规则；reload 时清空。 */
    private final Map<String, Rules> rulesCache = new HashMap<>();

    /** 一个技能的成长规则。 */
    private record Rules(int maxLevel, Map<String, Double> perLevel,
                         NavigableMap<Integer, Map<String, Double>> absolute, List<CostTier> tiers) {
        boolean empty() { return perLevel.isEmpty() && absolute.isEmpty(); }
    }

    public SkillLevelManager(EarthRPGItem plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "skill-levels.yml");
        this.data = YamlStore.load(dataFile, plugin.getLogger());
    }

    public void reload() {
        rulesCache.clear();
        messages.clear();
        defaultTiers = List.of();
        File file = new File(plugin.getDataFolder(), "skill-leveling.yml");
        if (!file.exists()) {
            try {
                plugin.saveResource("skill-leveling.yml", false);
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("无法写入默认 skill-leveling.yml：" + exception.getMessage());
            }
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        enabled = config.getBoolean("enabled", true);
        defaultMaxLevel = Math.max(MIN_LEVEL, config.getInt("max-level", 10));
        defaultTiers = readTiers(config.getMapList("cost.tiers"));
        ConfigurationSection messageSection = config.getConfigurationSection("messages");
        if (messageSection != null) {
            for (String key : messageSection.getKeys(false)) {
                messages.put(key, messageSection.getString(key, ""));
            }
        }
        plugin.getLogger().info("技能强化系统：" + (enabled ? "已开启" : "已关闭")
                + "，默认上限 Lv." + defaultMaxLevel + "，消耗段 " + defaultTiers.size() + " 个");
    }

    /** 配置重载时清掉规则缓存（技能文件与 skill-leveling.yml 都可能变了）。 */
    public void invalidate() {
        rulesCache.clear();
    }

    public boolean enabled() { return enabled; }

    public int defaultMaxLevel() { return defaultMaxLevel; }

    public String message(String key, String fallback) {
        String value = messages.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    // ------------------------------------------------------------------
    // 等级读写
    // ------------------------------------------------------------------

    /** 当前等级，至少为 {@link #MIN_LEVEL} 且不超过该技能上限。没升过级就是 Lv.1。 */
    public int level(UUID playerId, String skillId) {
        if (skillId == null || skillId.isBlank()) return MIN_LEVEL;
        for (String entry : stored(playerId)) {
            int split = entry.lastIndexOf('|');
            if (split <= 0) continue;
            if (!entry.substring(0, split).equalsIgnoreCase(skillId)) continue;
            try {
                int parsed = Integer.parseInt(entry.substring(split + 1).trim());
                // 上限调低（例如默认 20 → 10）时，已超出的旧等级按新上限显示。
                return Math.max(MIN_LEVEL, Math.min(parsed, maxLevel(skillId)));
            } catch (NumberFormatException ignored) {
                return MIN_LEVEL;
            }
        }
        return MIN_LEVEL;
    }

    /** @return false when the level could not be persisted. */
    public boolean setLevel(UUID playerId, String skillId, int level) {
        if (skillId == null || skillId.isBlank()) return false;
        int clamped = Math.max(MIN_LEVEL, level);
        List<String> updated = new ArrayList<>();
        boolean replaced = false;
        for (String entry : stored(playerId)) {
            int split = entry.lastIndexOf('|');
            if (split <= 0) continue;
            if (entry.substring(0, split).equalsIgnoreCase(skillId)) {
                updated.add(skillId + "|" + clamped);
                replaced = true;
            } else {
                updated.add(entry);
            }
        }
        if (!replaced) updated.add(skillId + "|" + clamped);
        return write(playerId, updated);
    }

    private List<String> stored(UUID playerId) {
        List<String> raw = data.getStringList("players." + playerId);
        List<String> result = new ArrayList<>(raw.size());
        for (String entry : raw) if (entry != null && !entry.isBlank()) result.add(entry);
        return result;
    }

    /**
     * 一次性把该玩家的等级表写成 {@code target}（整份替换），<b>只落盘一次</b>。
     *
     * <p>技能预设的天赋重分配必须走这个方法，而不是连着调好几次 {@link #setLevel}：
     * 每次 {@code setLevel} 都是一次独立的文件写入，中途任何一次失败都会留下
     * 「一部分技能已经打回 Lv.1、另一部分还没恢复」的半成品状态——玩家的点数就白丢了。
     * 这里先把整张目标表在内存里算好，再交给 {@link #write} 原子落盘；写失败时
     * {@code write} 会把内存回滚，磁盘上仍是改动前的完整状态，不会出现半成品。
     *
     * @param target 技能 ID（保留原始大小写）-> 等级；不在表里的技能会被移除
     * @return false 表示落盘失败，此时磁盘上的数据没有被改动
     */
    public boolean setLevels(UUID playerId, Map<String, Integer> target) {
        List<String> updated = new ArrayList<>();
        if (target != null) {
            for (Map.Entry<String, Integer> entry : target.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) continue;
                int level = entry.getValue() == null ? MIN_LEVEL : entry.getValue();
                updated.add(entry.getKey() + "|" + Math.max(MIN_LEVEL, level));
            }
        }
        return write(playerId, updated);
    }

    /**
     * 该玩家所有已存储的等级：技能 ID -> 等级。
     *
     * <p>与 {@link #level} 的单点查询不同，这里要的是「全部」，因为技能预设重分配时必须把
     * <b>每一个</b>已投入点数的技能都打回 Lv.1 才能把点数释放出来——漏掉任何一个，
     * 算出来的可用点数就会偏少，加载预设会被误判成「点数不够」。
     *
     * @return 技能 ID 原样保留大小写；上限调低时同样按新上限收敛
     */
    public Map<String, Integer> levels(UUID playerId) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String entry : stored(playerId)) {
            int split = entry.lastIndexOf('|');
            if (split <= 0) continue;
            String skillId = entry.substring(0, split);
            try {
                int parsed = Integer.parseInt(entry.substring(split + 1).trim());
                result.put(skillId, Math.max(MIN_LEVEL, Math.min(parsed, maxLevel(skillId))));
            } catch (NumberFormatException ignored) {
                // 坏行按 Lv.1 处理，和 level() 的兜底保持一致。
                result.putIfAbsent(skillId, MIN_LEVEL);
            }
        }
        return result;
    }

    private boolean write(UUID playerId, List<String> entries) {
        String path = "players." + playerId;
        List<String> previous = data.getStringList(path);
        data.set(path, entries);
        if (YamlStore.save(data, dataFile, plugin.getLogger())) return true;
        data.set(path, previous);
        return false;
    }

    // ------------------------------------------------------------------
    // 成长规则
    // ------------------------------------------------------------------

    /** 该技能是否配置了成长规则。没有规则的技能不能强化。 */
    public boolean hasRules(String skillId) {
        return !rules(skillId).empty();
    }

    public int maxLevel(String skillId) {
        return rules(skillId).maxLevel();
    }

    /** 每级叠加量：字段路径 -> 每级增量。 */
    public Map<String, Double> perLevel(String skillId) {
        return rules(skillId).perLevel();
    }

    private Rules rules(String skillId) {
        if (skillId == null || skillId.isBlank()) return new Rules(defaultMaxLevel, Map.of(), new TreeMap<>(), defaultTiers);
        Rules cached = rulesCache.get(skillId);
        if (cached != null) return cached;
        Rules built = buildRules(skillId);
        rulesCache.put(skillId, built);
        return built;
    }

    private Rules buildRules(String skillId) {
        YamlConfiguration file = skillFileConfig(skillId);
        ConfigurationSection node = file == null ? null : file.getConfigurationSection("leveling");
        if (node == null) {
            // 没有 leveling 段：规则为空，但保留默认上限与默认消耗，界面才好解释原因。
            return new Rules(defaultMaxLevel, Map.of(), new TreeMap<>(), defaultTiers);
        }
        int maxLevel = Math.max(MIN_LEVEL, node.getInt("max-level", defaultMaxLevel));

        Map<String, Double> perLevel = new LinkedHashMap<>();
        ConfigurationSection perLevelNode = node.getConfigurationSection("per-level");
        if (perLevelNode != null) {
            // 字段路径写法（sword-aura.weapon-percent），Bukkit 已经把它拆成了嵌套段
            // （per-level → sword-aura → weapon-percent）。必须递归到叶子再拼回点号路径，
            // 否则 getKeys(false) 只拿到 "sword-aura" 这一层段名，perLevel 全是空，
            // 强化台就会误报「这个技能没有配置成长规则」。
            collectNumericLeaves(perLevelNode, "", perLevel,
                    "技能 " + skillId + " 的 leveling.per-level.");
        }

        NavigableMap<Integer, Map<String, Double>> absolute = new TreeMap<>();
        ConfigurationSection levelsNode = node.getConfigurationSection("levels");
        if (levelsNode != null) {
            for (String key : levelsNode.getKeys(false)) {
                int level = parseLevel(key);
                ConfigurationSection entry = levelsNode.getConfigurationSection(key);
                if (level < MIN_LEVEL || entry == null) {
                    plugin.getLogger().warning("技能 " + skillId + " 的 leveling.levels." + key
                            + " 不是有效等级或不是段落，已跳过。");
                    continue;
                }
                Map<String, Double> values = new LinkedHashMap<>();
                collectNumericLeaves(entry, "", values,
                        "技能 " + skillId + " 的 leveling.levels." + key + ".");
                if (!values.isEmpty()) absolute.put(level, values);
            }
        }

        List<CostTier> tiers = node.isList("cost.tiers") ? readTiers(node.getMapList("cost.tiers")) : defaultTiers;
        if (tiers.isEmpty()) tiers = defaultTiers;
        return new Rules(maxLevel, perLevel, absolute, tiers);
    }

    /**
     * 递归收集一个段落下面所有「数字叶子」，键名是拼回点号的完整字段路径。
     *
     * <p>技能文件里成长字段写的是 {@code phantom-blade.slash-count} 这种字段路径，Bukkit 读进来
     * 会把它拆成嵌套段。旧代码只看直系子键，于是拿到的是 {@code phantom-blade} 这种段名而不是数字，
     * 全部被跳过 —— 表现就是「注册了 leveling 段但技能强化台说没有成长规则」。
     *
     * @param prefix 当前层已累积的点号路径（顶层传空串）
     * @param warningPrefix 警告文案前缀
     */
    private void collectNumericLeaves(ConfigurationSection section, String prefix,
                                      Map<String, Double> out, String warningPrefix) {
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            if (key == null || key.isBlank()) continue;
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object raw = section.get(key);
            if (raw instanceof ConfigurationSection nested) {
                collectNumericLeaves(nested, path, out, warningPrefix);
            } else if (raw instanceof Number number) {
                out.put(path, number.doubleValue());
            } else {
                plugin.getLogger().warning(warningPrefix + path
                        + " 不是数字（" + raw + "），已跳过。");
            }
        }
    }

    /** 技能文件里 leveling 段的路径必须是数字字段，否则打补丁会把技能改坏。 */
    private YamlConfiguration skillFileConfig(String skillId) {
        SkillPanelManager.SkillRegistration registration = plugin.getSkillPanelManager().registration(skillId);
        if (registration == null || registration.skillFile().isBlank()) return null;
        return plugin.getRpgItemManager().skillFileConfig(registration.skillFile());
    }

    /**
     * 算出某等级要覆盖哪些字段、覆盖成什么值。
     *
     * <p>规则：Lv.1 用技能文件原本的值；之后每级加 {@code per-level}。
     * 若某个 {@code levels.<N>} 定义了该字段，则从 Lv.N 起以那个绝对值为基准继续叠加。
     *
     * @return 字段路径 -> 该等级的目标数值；空表示不需要打补丁
     */
    public Map<String, Double> overridesFor(String skillId, int level) {
        Rules rules = rules(skillId);
        if (rules.empty() || level <= MIN_LEVEL) return Map.of();
        Set<String> paths = new LinkedHashSet<>(rules.perLevel().keySet());
        for (Map.Entry<Integer, Map<String, Double>> tier : rules.absolute().entrySet()) {
            if (tier.getKey() <= level) paths.addAll(tier.getValue().keySet());
        }
        Map<String, Double> result = new LinkedHashMap<>();
        for (String path : paths) {
            double base = baseValue(skillId, path);
            double delta = rules.perLevel().getOrDefault(path, 0.0);
            Integer tierLevel = null;
            Double tierValue = null;
            for (Map.Entry<Integer, Map<String, Double>> tier : rules.absolute().entrySet()) {
                if (tier.getKey() > level) break;
                Double value = tier.getValue().get(path);
                if (value != null) { tierLevel = tier.getKey(); tierValue = value; }
            }
            double value = tierValue == null
                    ? base + (level - MIN_LEVEL) * delta
                    : tierValue + (level - tierLevel) * delta;
            result.put(path, clamp(base, value));
        }
        return result;
    }

    /**
     * 技能文件里该字段原本的数值。
     * <p>
     * 只在顶层找：技能文件的内容会被包装进 8 个触发段，所以顶层路径就是所有触发共用的那一份。
     */
    private double baseValue(String skillId, String path) {
        YamlConfiguration file = skillFileConfig(skillId);
        if (file == null) return 0;
        Object raw = file.get(path);
        if (raw instanceof Number number) return number.doubleValue();
        // 数值字段被写成字符串时也认（YAML 里 "30" 很常见）。
        if (raw != null) {
            try {
                return Double.parseDouble(raw.toString().trim());
            } catch (NumberFormatException ignored) {
                // 落到下面的警告
            }
        }
        plugin.getLogger().warning("技能 " + skillId + " 的成长规则引用了不存在的数值字段 '"
                + path + "'（已按 0 处理）。请检查字段路径拼写。");
        return 0;
    }

    /**
     * 成长不能把技能改成无效值：原本 >= 1 的字段不允许掉到 1 以下
     * （例如挥砍次数变成 0 会改变技能行为），原本 >= 0 的字段不允许变成负数。
     */
    private static double clamp(double base, double value) {
        if (base >= 1.0) return Math.max(1.0, value);
        if (base >= 0.0) return Math.max(0.0, value);
        return value;
    }

    // ------------------------------------------------------------------
    // 消耗
    // ------------------------------------------------------------------

    /** 升到 {@code targetLevel} 需要的宝石：宝石 id（小写）-> 数量。 */
    public Map<String, Integer> costFor(String skillId, int targetLevel) {
        Rules rules = rules(skillId);
        List<CostTier> tiers = rules.tiers();
        if (tiers.isEmpty()) return Map.of();
        CostTier chosen = null;
        for (CostTier tier : tiers) {
            if (targetLevel <= tier.maxLevel()) { chosen = tier; break; }
        }
        if (chosen == null) chosen = tiers.get(tiers.size() - 1);
        return chosen.gems();
    }

    private List<CostTier> readTiers(List<Map<?, ?>> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        List<CostTier> tiers = new ArrayList<>();
        for (Map<?, ?> entry : raw) {
            if (entry == null) continue;
            int maxLevel = defaultMaxLevel;
            Object maxRaw = entry.get("max-level");
            if (maxRaw instanceof Number number) maxLevel = number.intValue();
            Map<String, Integer> gems = new LinkedHashMap<>();
            Object gemsRaw = entry.get("gems");
            if (gemsRaw instanceof Map<?, ?> gemMap) {
                for (Map.Entry<?, ?> gem : gemMap.entrySet()) {
                    if (gem.getKey() == null || !(gem.getValue() instanceof Number number)) continue;
                    int amount = number.intValue();
                    if (amount > 0) gems.put(gem.getKey().toString().trim().toLowerCase(Locale.ROOT), amount);
                }
            }
            tiers.add(new CostTier(maxLevel, Map.copyOf(gems)));
        }
        tiers.sort(Comparator.comparingInt(CostTier::maxLevel));
        return List.copyOf(tiers);
    }

    /** 玩家背包里各宝石的数量（按已注册物品 id 统计）。 */
    public Map<String, Integer> countGems(Player player) {
        Map<String, Integer> counts = new HashMap<>();
        for (ItemStack stack : player.getInventory().getContents()) {
            String id = itemId(stack);
            if (id == null) continue;
            counts.merge(id.toLowerCase(Locale.ROOT), stack.getAmount(), Integer::sum);
        }
        return counts;
    }

    /** 材料是否够。 */
    public boolean hasGems(Player player, Map<String, Integer> need) {
        Map<String, Integer> have = countGems(player);
        for (Map.Entry<String, Integer> entry : need.entrySet()) {
            if (have.getOrDefault(entry.getKey(), 0) < entry.getValue()) return false;
        }
        return true;
    }

    /**
     * 扣掉材料。先整体校验再扣，所以不会出现「扣了红宝石才发现蓝宝石不够」的半途状态。
     *
     * @return false 表示材料不足，此时背包没有任何改动
     */
    public boolean takeGems(Player player, Map<String, Integer> need) {
        if (need.isEmpty()) return true;
        if (!hasGems(player, need)) return false;
        Map<String, Integer> remaining = new HashMap<>(need);
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            String id = itemId(stack);
            if (id == null) continue;
            Integer left = remaining.get(id.toLowerCase(Locale.ROOT));
            if (left == null || left <= 0) continue;
            int take = Math.min(left, stack.getAmount());
            remaining.put(id.toLowerCase(Locale.ROOT), left - take);
            int rest = stack.getAmount() - take;
            if (rest <= 0) {
                player.getInventory().setItem(slot, null);
            } else {
                // 复制而不是就地改：背包里的 ItemStack 可能被视图共享。
                ItemStack copy = stack.clone();
                copy.setAmount(rest);
                player.getInventory().setItem(slot, copy);
            }
            if (remaining.values().stream().allMatch(value -> value <= 0)) break;
        }
        return true;
    }

    private String itemId(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return null;
        RpgItem item = plugin.getRpgItemManager().findItem(stack);
        return item == null ? null : item.id();
    }

    /**
     * 把材料还给玩家，用于「材料扣了但等级没写成功」的退还。
     * <p>
     * 装不下的部分掉在脚下而不是被丢弃：升级失败本身已经很不愉快了，
     * 再吞掉材料是不可接受的。
     */
    public void giveGems(Player player, Map<String, Integer> gems) {
        if (gems == null || gems.isEmpty()) return;
        for (Map.Entry<String, Integer> entry : gems.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) continue;
            RpgItem item = plugin.getRpgItemManager().findById(entry.getKey());
            if (item == null) {
                plugin.getLogger().warning("无法退还材料：物品 " + entry.getKey() + " 已不在配置中（数量 "
                        + entry.getValue() + "）。");
                continue;
            }
            ItemStack stack = plugin.getRpgItemManager().createItem(item);
            stack.setAmount(entry.getValue());
            for (ItemStack leftover : player.getInventory().addItem(stack).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        }
    }

    // ------------------------------------------------------------------
    // 技能点（EarthExp 附属：战斗天赋 = 技能点）
    // ------------------------------------------------------------------

    /** EarthExp 已接入：技能升级改用技能点，不再消耗宝石。 */
    public boolean usesSkillPoints() {
        return plugin.getEarthExpBridge().isAvailable();
    }

    /**
     * 技能点是否<b>真的</b>会被扣掉（EarthExp 的 API 上确实存在 {@code spendTalentPoint}）。
     *
     * <p>{@link #usesSkillPoints()} 只说明「接入了 EarthExp」；它的 API 还没实装扣点方法时，
     * 升级只是校验一下点数、并不会真的扣。此时「重置技能等级就能退还点数」并不成立——
     * 见 {@code EarthExpBridge.deductsTalentPoints()}。技能预设的天赋重分配必须按这个判断，
     * 否则重置等级只会白白洗掉玩家的进度。
     */
    public boolean deductsSkillPoints() {
        return plugin.getEarthExpBridge().deductsTalentPoints();
    }

    /** 玩家当前可用的战斗天赋点（COMBAT 池）。EarthExp 当前语义：天赋点 = 等级。 */
    public int availableSkillPoints(Player player) {
        return plugin.getEarthExpBridge().talentPoints(player);
    }

    /** 升到第 {@code targetLevel} 级需要的技能点：第 N 级花 N 点（1→2 花 2 … 9→10 花 10）。 */
    public static int pointsCost(int targetLevel) {
        return Math.max(MIN_LEVEL, targetLevel);
    }

    /** 把技能升到 {@code level} 级累计花掉的技能点（Lv.1 免费，因为技能本身是书学会的）。 */
    public static int cumulativePoints(int level) {
        int clamped = Math.max(MIN_LEVEL, level);
        return clamped * (clamped + 1) / 2 - 1;
    }

    /** 玩家是否付得起 {@code need} 点。 */
    public boolean hasSkillPoints(Player player, int need) {
        return usesSkillPoints() && availableSkillPoints(player) >= need;
    }

    /**
     * 花费技能点。EarthExp 0.5.0+ 会真的扣；更早的版本没有扣点接口，仅校验后返回 true。
     *
     * @return false 表示 EarthExp 明确拒绝：点数不足，或<b>当前配置的轨道没有开放外部消费</b>
     *         （EarthExp 目前只给冒险轨道开了口子，配成 COMBAT 时一定会被拒）
     */
    public boolean spendPoints(Player player, int points) {
        return plugin.getEarthExpBridge().spend(player, points);
    }

    /**
     * 退还技能点，返回<b>实际</b>退回的数量。
     *
     * <p>EarthExp 把退款上限卡在「本插件实际扣过的量」上，所以这个返回值可能小于
     * {@code points}，甚至为 0。调用方<b>必须</b>拿它来显示提示，不能直接报
     * {@code points}——那会报出一个根本没退的数字。
     */
    public int refundPoints(Player player, int points) {
        return plugin.getEarthExpBridge().refund(player, points);
    }

    /**
     * 本插件在 EarthExp 账上<b>实际扣掉</b>的技能点。
     *
     * <p>这是「重置技能等级能退回多少点」的唯一可靠上限。注意它<b>不等于</b>
     * {@link #levels(UUID)} 求和算出来的累计点数：升级发生在扣点接口出现之前的技能
     * 从没被扣过，账上没有记录，那些等级退不出任何点数。
     */
    public int spentSkillPoints(Player player) {
        return plugin.getEarthExpBridge().spentTalentPoints(player);
    }

    /** 技能点需求的玩家可读文本，例如 {@code 技能点 ×3}。 */
    public static String describePointsCost(int points) {
        return "技能点 &f×" + points;
    }

    // ------------------------------------------------------------------
    // 展示
    // ------------------------------------------------------------------

    /** 材料需求的玩家可读文本，例如 {@code 红宝石 ×2, 蓝宝石 ×1}。 */
    public String describeCost(Map<String, Integer> need) {
        if (need.isEmpty()) return "无";
        List<String> parts = new ArrayList<>();
        GemstoneManager gems = plugin.getGemstoneManager();
        for (Map.Entry<String, Integer> entry : need.entrySet()) {
            GemstoneManager.Gemstone gem = gems == null ? null : gems.get(entry.getKey());
            String name = gem == null ? entry.getKey() : gem.displayName();
            parts.add(name + " &7×" + entry.getValue());
        }
        return String.join("&7, ", parts);
    }

    /**
     * 「这一级改了什么」的预览：字段 -> 从多少变成多少。
     * <p>
     * 只列出真正变化的字段，所以玩家看到的永远是这一级实际生效的成长，
     * 而不是配置里所有 per-level 条目。
     */
    public List<String> previewLines(String skillId, int fromLevel, int toLevel) {
        Map<String, Double> before = overridesFor(skillId, fromLevel);
        Map<String, Double> after = overridesFor(skillId, toLevel);
        Set<String> paths = new LinkedHashSet<>(after.keySet());
        List<String> lines = new ArrayList<>();
        for (String path : paths) {
            double from = before.containsKey(path) ? before.get(path) : baseValue(skillId, path);
            double to = after.get(path);
            if (Math.abs(from - to) < 1e-9) continue;
            lines.add("&7" + fieldName(path) + ": &f" + format(from) + " &7→ &a" + format(to));
        }
        return lines;
    }

    /**
     * 字段路径 -&gt; 中文名。
     * <p>
     * 先查精确路径字典 {@link #FIELD_NAMES}（收录的路径用人工校对过的名字）；没收录的
     * 按<b>段</b>翻译并拼接（{@link #SEGMENT_NAMES}）。这样任何「技能段.字段」组合都能显示中文，
     * 不再依赖把每条全路径都手工列全 —— 否则新技能（短弓 / 踢击 / 宝库……）的成长永远缺条目，
     * 而它们正好是用户最先加成长规则的那批。
     * <p>
     * 一个段都没翻译出来的路径（拼错 / 全新字段）回退成原始英文路径，避免显示编造的名字；
     * 部分翻译的路径（如 {@code shortbow-cd.mystery-key} -&gt; {@code 短弓·mystery-key}）
     * 保留原始段，方便一眼看出哪一段没收录。
     */
    public static String fieldName(String path) {
        if (path == null || path.isBlank()) return path;
        String exact = FIELD_NAMES.get(path);
        if (exact != null) return exact;
        String[] segments = path.split("\\.");
        String[] names = new String[segments.length];
        int translated = 0;
        for (int index = 0; index < segments.length; index++) {
            String name = SEGMENT_NAMES.get(segments[index]);
            if (name == null) {
                names[index] = segments[index];
            } else {
                names[index] = name;
                translated++;
            }
        }
        if (translated == 0) return path;
        return String.join("·", names);
    }

    private static final Map<String, String> FIELD_NAMES = Map.ofEntries(
            Map.entry("cooldown-seconds", "冷却(秒)"),
            Map.entry("cast-time-seconds", "施法时间(秒)"),
            Map.entry("phantom-blade.slash-count", "挥砍次数"),
            Map.entry("phantom-blade.slash-interval-ticks", "挥砍间隔(tick)"),
            Map.entry("phantom-blade.weapon-percent", "每道伤害(%)"),
            Map.entry("phantom-blade.damage", "每道固定伤害"),
            Map.entry("phantom-blade.jitter", "随机偏移(格)"),
            Map.entry("phantom-blade.distance", "生成距离(格)"),
            Map.entry("phantom-blade.radius", "挥砍半径(格)"),
            Map.entry("phantom-blade.height", "挥砍高度(格)"),
            Map.entry("phantom-blade.angle-degrees", "挥砍张角(°)"),
            Map.entry("phantom-blade.aura.weapon-percent", "剑气伤害(%)"),
            Map.entry("phantom-blade.aura.speed", "剑气速度"),
            Map.entry("phantom-blade.aura.max-distance", "剑气射程(格)"),
            Map.entry("phantom-blade.aura.radius", "剑气判定半径(格)"),
            Map.entry("phantom-blade.aura.arc-radius", "剑气弧半径(格)"),
            Map.entry("sweep.weapon-percent", "横扫伤害(%)"),
            Map.entry("sweep.distance", "横扫距离(格)"),
            Map.entry("sweep.radius", "横扫半径(格)"),
            Map.entry("sweep.height", "横扫高度(格)"),
            Map.entry("sweep.angle-degrees", "横扫张角(°)"),
            Map.entry("swing.weapon-percent", "挥击伤害(%)"),
            Map.entry("swing.radius", "挥击半径(格)"),
            Map.entry("swing.angle-degrees", "挥击张角(°)"),
            Map.entry("sword-aura.weapon-percent", "剑气伤害(%)"),
            Map.entry("sword-aura.damage", "剑气固定伤害"),
            Map.entry("sword-aura.speed", "剑气速度"),
            Map.entry("sword-aura.max-distance", "剑气射程(格)"),
            Map.entry("sword-aura.radius", "剑气判定半径(格)"),
            Map.entry("sword-aura.arc-radius", "剑气弧半径(格)"),
            Map.entry("cross-slash.horizontal.weapon-percent", "横斩伤害(%)"),
            Map.entry("cross-slash.vertical.weapon-percent", "竖斩伤害(%)"),
            Map.entry("cross-slash.angle-degrees", "十字斩张角(°)"),
            Map.entry("shockwave.radius", "冲击波半径(格)"),
            Map.entry("shockwave.height", "冲击波高度(格)"),
            Map.entry("shockwave.angle-degrees", "冲击波张角(°)"),
            Map.entry("shockwave.knockback", "冲击波击退"),
            Map.entry("shortbow-cd.arrow-count", "箭矢数量"),
            Map.entry("shortbow-cd.weapon-percent", "箭矢伤害(%)"),
            Map.entry("dimensional-slash.weapon-percent", "次元斩伤害(%)"),
            Map.entry("dimensional-slash.distance", "次元斩距离(格)"),
            Map.entry("backstep.distance", "后撤距离(格)"),
            Map.entry("backstep.duration-ticks", "后撤时长(tick)"),
            Map.entry("backstep.invulnerability-ticks", "后撤无敌帧(tick)"),
            Map.entry("uppercut.weapon-percent", "上挑伤害(%)"),
            Map.entry("uppercut.damage", "上挑固定伤害"),
            Map.entry("uppercut.radius", "上挑判定半径(格)"),
            Map.entry("uppercut.height", "上挑高度(格)"),
            Map.entry("uppercut.launch", "上挑击飞力度"),
            Map.entry("leap-strike.weapon-percent", "蹦山击伤害(%)"),
            Map.entry("leap-strike.damage", "蹦山击固定伤害"),
            Map.entry("leap-strike.leap-height", "蹦山击跳跃高度(格)"),
            Map.entry("leap-strike.dive-angle-degrees", "蹦山击俯冲角度(°)"),
            Map.entry("leap-strike.impact-radius", "蹦山击落地半径(格)"),
            Map.entry("leap-strike.knock-up", "蹦山击击飞力度"),
            Map.entry("leap-strike.explosion-radius", "蹦山击爆炸半径(格)"),
            Map.entry("gate-of-babylon.weapon-percent", "宝库伤害(%)"),
            Map.entry("magic-circle.weapon-percent", "魔法阵伤害(%)"));

    /**
     * 字段路径的<b>按段</b>翻译，给 {@link #fieldName} 做拼接兜底。
     * <p>
     * <b>为什么按段而不是按全路径</b>：全路径是「技能段 × 子段 × 字段」的笛卡尔积，
     * 每加一个技能就要手工补一批条目，漏一条那个技能的成长就整条显示英文 ——
     * 短弓（{@code shortbow-cd.*}）、踢击（{@code damages.kick.*}）、宝库的子字段
     * 就是这样漏掉的。按段翻译只需要覆盖有限的段名，新技能自动就有中文。
     * <p>
     * 名字尽量短：「段·段·字段」拼起来要能在物品的一行 lore 里读完。
     */
    private static final Map<String, String> SEGMENT_NAMES = Map.ofEntries(
            // ---- 技能段 ----
            Map.entry("phantom-blade", "幻影剑舞"),
            Map.entry("sweep", "横扫"),
            Map.entry("swing", "挥击"),
            Map.entry("sword-aura", "剑气"),
            Map.entry("cross-slash", "十字斩"),
            Map.entry("horizontal", "横斩"),
            Map.entry("vertical", "竖斩"),
            Map.entry("shockwave", "冲击波"),
            Map.entry("shortbow-cd", "短弓"),
            Map.entry("damages", "伤害段"),
            Map.entry("kick", "踢击"),
            Map.entry("leap-strike", "蹦山击"),
            Map.entry("uppercut", "上挑"),
            Map.entry("backstep", "后撤"),
            Map.entry("dimensional-slash", "次元斩"),
            Map.entry("gate-of-babylon", "宝库"),
            Map.entry("magic-circle", "魔法阵"),
            // ---- 子段 ----
            Map.entry("aura", "剑气"),
            Map.entry("slash-aura", "剑气版"),
            Map.entry("projectile-item", "本体"),
            Map.entry("impact-explosion", "命中爆炸"),
            Map.entry("trail", "轨迹"),
            Map.entry("particles", "粒子"),
            Map.entry("shockwave-particle", "冲击波粒子"),
            Map.entry("explosion-particle", "爆炸粒子"),
            Map.entry("spawn-particle", "生成粒子"),
            Map.entry("impact-particle", "命中粒子"),
            Map.entry("ripple", "悬停圆环"),
            Map.entry("spawn-mode", "生成方式"),
            Map.entry("weapon-materials", "武器池"),
            // ---- 数值字段 ----
            Map.entry("cooldown-seconds", "冷却(秒)"),
            Map.entry("cast-time-seconds", "施法时间(秒)"),
            Map.entry("slash-count", "挥砍次数"),
            Map.entry("slash-interval-ticks", "挥砍间隔(tick)"),
            Map.entry("arrow-count", "箭矢数量"),
            Map.entry("weapon-count", "武器数量"),
            Map.entry("volleys", "连射批数"),
            Map.entry("volley-interval-ticks", "批次间隔(tick)"),
            Map.entry("weapon-percent", "伤害(%)"),
            Map.entry("damage", "固定伤害"),
            Map.entry("damage-type", "伤害类型"),
            Map.entry("can-crit", "可暴击"),
            Map.entry("speed", "速度"),
            Map.entry("fall-speed", "下落速度"),
            Map.entry("distance", "距离(格)"),
            Map.entry("max-distance", "最大距离(格)"),
            Map.entry("spawn-distance", "生成距离(格)"),
            Map.entry("spawn-height", "生成高度(格)"),
            Map.entry("spawn-fan-degrees", "生成扇形角(°)"),
            Map.entry("sky-height", "天际高度(格)"),
            Map.entry("sky-offset", "天际偏移(格)"),
            Map.entry("launch-delay-ticks", "发射延迟(tick)"),
            Map.entry("linger-seconds", "停留(秒)"),
            Map.entry("height", "高度(格)"),
            Map.entry("impact-height", "落地高度(格)"),
            Map.entry("radius", "半径(格)"),
            Map.entry("hit-radius", "命中半径(格)"),
            Map.entry("impact-radius", "落地半径(格)"),
            Map.entry("explosion-radius", "爆炸半径(格)"),
            Map.entry("spread-radius", "落点散布(格)"),
            Map.entry("arc-radius", "弧半径(格)"),
            Map.entry("length", "长度(格)"),
            Map.entry("angle-degrees", "张角(°)"),
            Map.entry("spread-degrees", "散布角(°)"),
            Map.entry("dive-angle-degrees", "俯冲角(°)"),
            Map.entry("blade-tilt-degrees", "剑刃摆正角(°)"),
            Map.entry("tilt-degrees", "摆正角(°)"),
            Map.entry("tilt-min-degrees", "倾斜下限(°)"),
            Map.entry("tilt-max-degrees", "倾斜上限(°)"),
            Map.entry("jitter", "随机偏移(格)"),
            Map.entry("knockback", "击退(格)"),
            Map.entry("knock-up", "击飞力度"),
            Map.entry("launch", "击飞力度"),
            Map.entry("leap-height", "跳跃高度(格)"),
            Map.entry("pierce", "穿透数"),
            Map.entry("pierce-blocks", "穿墙"),
            Map.entry("collide-blocks", "撞墙即停"),
            Map.entry("glow", "发光"),
            Map.entry("scale", "图标大小"),
            Map.entry("max-hits-per-target", "单体命中上限"),
            Map.entry("duration-ticks", "时长(tick)"),
            Map.entry("invulnerability-ticks", "无敌帧(tick)"),
            Map.entry("count", "数量"),
            Map.entry("enabled", "开关"),
            Map.entry("mode", "模式"),
            Map.entry("spread-mode", "散布模式"),
            Map.entry("plane", "圆环朝向"),
            Map.entry("volume", "音量"),
            Map.entry("pitch", "音调"),
            Map.entry("launch-volume", "发射音量"),
            Map.entry("launch-pitch", "发射音调"),
            Map.entry("launch-sound", "发射音"),
            Map.entry("sound", "音效"),
            Map.entry("impact-sound", "命中音"),
            Map.entry("particle", "粒子"),
            Map.entry("effect", "形态"),
            Map.entry("block", "样式"));

    /** 整数就显示成整数，否则保留两位小数——避免界面出现 "6.0" 这种噪声。 */
    public static String format(double value) {
        if (Math.abs(value - Math.rint(value)) < 1e-9) return String.valueOf((long) Math.rint(value));
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static int parseLevel(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (RuntimeException exception) {
            return -1;
        }
    }
}
