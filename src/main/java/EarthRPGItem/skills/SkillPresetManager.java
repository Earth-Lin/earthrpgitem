package EarthRPGItem.skills;

import EarthRPGItem.EarthRPGItem;
import EarthRPGItem.items.FSkillConfig;
import EarthRPGItem.items.RpgItem;
import EarthRPGItem.util.YamlStore;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 技能预设：把「一整套技能配置」存成一个预设，之后一键还原。
 *
 * <p><b>一个预设保存什么</b>：八个键位各自的技能绑定与等级、该键位的「启用 / 释放提示 /
 * 冷却提示 / 就绪提示」四个开关，以及技能面板总开关。也就是说加载预设之后，玩家看到的
 * 面板与保存那一刻完全一致——这正是「预设」相对于「只记键位」的意义。
 *
 * <p><b>加载时的天赋重分配</b>：预设记录的是技能等级。玩家可能在保存之后升级了别的技能，
 * 或者把预设里的技能升到了别的等级，于是当前的天赋分配与预设对不上。加载时会
 * <b>先把所有技能等级重置回 Lv.1（释放出已投入的点数），再按预设把点数分配给预设里的技能</b>。
 * 分配后剩下的点数<b>不会</b>被删掉或强行花掉，留在玩家手里自己决定怎么用。
 *
 * <p>这一步是破坏性的（会把不在预设里的技能打回 Lv.1），所以采用<b>先算后做</b>：
 * 点数不够时直接拒绝加载、什么都不改，并把差多少点告诉玩家，而不是重置到一半卡住。
 *
 * <p>只有接入 EarthExp（技能点模式）时才做等级重分配。宝石模式下等级靠宝石买，
 * 没有「天赋」可重置，此时加载预设只还原绑定与开关，等级保持原样。
 *
 * <p>预设失效：只要预设里有一个技能已经不在玩家的技能背包（被删除），或该技能不再提供
 * 它当初占用的键位，这个预设就<b>直接无法使用</b>——加载会被拒绝并指出是哪个技能出的问题。
 */
public final class SkillPresetManager {

    /** 预设数量：一排 9 个。 */
    public static final int PRESET_COUNT = 9;

    private static final DateTimeFormatter SAVED_AT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** 一个键位在预设里的状态。 */
    public record PresetSlot(String skillId, int level, boolean triggerEnabled,
                             boolean activationMessages, boolean cooldownMessages, boolean readyMessages) {
        public boolean bound() {
            return skillId != null && !skillId.isBlank();
        }
    }

    /** 一个已保存的预设。{@code savedAt <= 0} 表示这个位置是空的。 */
    public record Preset(long savedAt, boolean panelEnabled, Map<String, PresetSlot> slots) {
        public boolean isEmpty() {
            return savedAt <= 0;
        }
    }

    public enum LoadResult {
        /** 还原成功。 */
        OK,
        /** 这个位置还没有保存过预设。 */
        EMPTY,
        /** 预设里的某个技能已经不在技能背包 / 注册失效 / 不再提供该键位，预设不可用。 */
        BROKEN,
        /** 天赋点不足以按预设分配，已取消，未改动任何东西。 */
        NOT_ENOUGH_POINTS,
        /**
         * EarthExp <b>拒绝</b>了这次扣点，已把刚退的点数原样塞回去，什么都没改。
         *
         * <p>常见原因：当前配置的轨道没有开放外部消费（EarthExp 目前只给冒险轨道开了口子），
         * 或点数在退与扣之间被别的插件用掉了。
         */
        SPEND_REJECTED,
        /** 等级表落盘失败，已把点数改动全部回滚，磁盘上仍是改动前的状态。 */
        SAVE_FAILED
    }

    /**
     * 加载结果。
     *
     * <p>{@code required} / {@code available} / {@code remaining} 只在<b>真的做了重分配</b>时才有意义：
     * {@code remaining} 是分配完预设后仍然留在玩家手里的点数（「多余的不删除」）。
     *
     * @param reallocated    是否真的做了「重置全部等级 + 按预设重新分配」
     * @param skillPointMode 是否处于技能点模式（已接入 EarthExp）；为 true 但
     *                       {@code reallocated} 为 false，说明 EarthExp 还没实装扣点，
     *                       此时只套用预设等级、不重置其他技能
     */
    public record LoadOutcome(LoadResult result, List<String> problems, int required, int available,
                              boolean reallocated, boolean skillPointMode, int remaining) {
    }

    public enum SaveResult { OK, EMPTY_PRESET, SAVE_FAILED }

    private final EarthRPGItem plugin;
    private final File dataFile;
    /**
     * 玩家预设数据。刻意<b>不是 final</b>：{@link #reload()} 会整份重新读盘。
     *
     * <p>必须支持重读，因为管理手册里写了「管理员可以直接看/改 skill-presets.yml」。
     * 只在构造时读一次的话，管理员的手工修改会被下一次 {@link #save} 整份覆盖掉——
     * {@code YamlStore.save} 写的是内存里这个对象，而不是「只改动了的那几个键」。
     */
    private YamlConfiguration data;

    public SkillPresetManager(EarthRPGItem plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "skill-presets.yml");
        this.data = YamlStore.load(dataFile, plugin.getLogger());
    }

    /**
     * 重新从磁盘读取预设文件。{@code /rpgitem reload} 时调用。
     *
     * <p>没做「先保存再重读」：内存里的改动在每次写操作里已经落盘了，这里重读只会拿到
     * 同一份内容；而如果上一次落盘失败，重读正好把内存里那份没写进去的状态丢掉，
     * 回到磁盘上的真实状态，反而是对的。
     */
    public void reload() {
        this.data = YamlStore.load(dataFile, plugin.getLogger());
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /** 读取一个预设；这个位置没有保存过时返回 null。 */
    public Preset preset(Player player, int index) {
        if (!valid(index) || player == null) return null;
        ConfigurationSection node = data.getConfigurationSection(basePath(player.getUniqueId(), index));
        if (node == null) return null;
        long savedAt = node.getLong("saved-at", 0L);
        if (savedAt <= 0) return null;
        ConfigurationSection slotSection = node.getConfigurationSection("slots");
        Map<String, PresetSlot> slots = new LinkedHashMap<>();
        for (String trigger : SkillPanelManager.TRIGGERS) {
            ConfigurationSection slot = slotSection == null ? null : slotSection.getConfigurationSection(trigger);
            if (slot == null) {
                // 缺段按「空槽位 + 开关全开」补，读到的预设永远有完整的八个键位，
                // 调用方就不用到处判空。
                slots.put(trigger, new PresetSlot("", SkillLevelManager.MIN_LEVEL, true, true, true, true));
                continue;
            }
            String skillId = slot.getString("skill", "");
            slots.put(trigger, new PresetSlot(
                    skillId,
                    clampedLevel(skillId, slot.getInt("level", SkillLevelManager.MIN_LEVEL)),
                    slot.getBoolean("enabled", true),
                    slot.getBoolean("activation", true),
                    slot.getBoolean("cooldown", true),
                    slot.getBoolean("ready", true)));
        }
        return new Preset(savedAt, node.getBoolean("panel-enabled", false), slots);
    }

    /**
     * 预设里记的等级要按<b>当前</b>上限收敛。
     *
     * <p>预设可能是在上限还很高的时候存的（例如默认 20），之后管理员把上限调到 10。
     * 不收敛的话，点数需求会按旧等级算（Lv.20 需要 209 点），而实际套用时
     * {@code setLevel} 会被夹到 Lv.10——玩家付了 209 点只拿到 10 级。
     * 这和 {@code SkillLevelManager.level()} 对已存等级的收敛是同一套规则。
     */
    private int clampedLevel(String skillId, int level) {
        int clamped = Math.max(SkillLevelManager.MIN_LEVEL, level);
        SkillLevelManager levels = plugin.getSkillLevelManager();
        if (levels == null || skillId == null || skillId.isBlank()) return clamped;
        return Math.min(clamped, Math.max(SkillLevelManager.MIN_LEVEL, levels.maxLevel(skillId)));
    }

    /** 该预设保存时间的人可读文本。 */
    public String formatSavedAt(long savedAt) {
        if (savedAt <= 0) return "未保存";
        return SAVED_AT_FORMAT.format(Instant.ofEpochMilli(savedAt));
    }

    /**
     * 是否处于技能点模式（已接入 EarthExp）。
     * <p>
     * 注意这<b>不等于</b>「加载预设会扣点」——还要看 {@link #reallocatesOnLoad()}。
     */
    public boolean usesSkillPoints() {
        SkillLevelManager levels = plugin.getSkillLevelManager();
        return levels != null && levels.usesSkillPoints();
    }

    /**
     * 加载预设时是否会真的做天赋重分配（重置全部等级 + 按预设分配点数）。
     *
     * <p>界面用它决定要不要显示「加载需要技能点」：接了 EarthExp 但它的 API 还没实装扣点时，
     * 加载并不扣点（见 {@link #load} 的模式 2），此时显示一个点数需求只会误导玩家。
     */
    public boolean reallocatesOnLoad() {
        SkillLevelManager levels = plugin.getSkillLevelManager();
        return levels != null && levels.usesSkillPoints() && levels.deductsSkillPoints();
    }

    /**
     * 预设里无法还原的部分。空列表 = 这个预设可以正常加载。
     *
     * <p>三种情况都算失效：技能被从技能背包删除、注册定义/技能模板已不存在、
     * 该技能现在已经不提供它当初占用的那个键位。最后一种如果不拦，加载出来的就是一个
     * 「看着绑好了、按了没反应」的死槽位。
     */
    public List<String> problems(Player player, Preset preset) {
        List<String> problems = new ArrayList<>();
        if (player == null || preset == null) return problems;
        UUID playerId = player.getUniqueId();
        SkillPanelManager panel = plugin.getSkillPanelManager();
        // 技能组占用：同组同时装在两个【启用】的键位上，两个键位都会失效。
        // 保存时不可能出现（装备阶段就拦了），但保存之后技能 yml 的 skill-group 被改过就会。
        // 这种失效最难查——面板看着一切正常，按下去毫无反应——所以在这里一并拦下。
        Map<String, String> groupOwner = new LinkedHashMap<>();
        for (String trigger : SkillPanelManager.TRIGGERS) {
            PresetSlot slot = preset.slots().get(trigger);
            if (slot == null || !slot.bound()) continue;
            String id = slot.skillId();
            if (!plugin.getSkillBackpackManager().contains(playerId, id)) {
                problems.add(id + "（不在技能背包）");
                continue;
            }
            RpgItem item = panel.registeredItem(id);
            if (item == null || panel.registration(id) == null) {
                problems.add(id + "（注册定义或技能模板已失效）");
                continue;
            }
            FSkillConfig config = panel.skillConfig(item, trigger);
            if (config == null || !config.enabled()) {
                problems.add(id + "（该技能不再提供 " + SkillPanelManager.triggerLabel(trigger) + "）");
                continue;
            }
            // 只有启用的键位才会互相顶掉，和 SkillPanelManager.hasGroupConflict 的判定保持一致。
            if (!slot.triggerEnabled()) continue;
            String group = panel.skillGroup(id).toLowerCase(Locale.ROOT);
            if (group.isBlank()) continue;
            String owner = groupOwner.putIfAbsent(group, trigger);
            if (owner != null) {
                problems.add("技能组「" + panel.skillGroup(id) + "」同时被 "
                        + SkillPanelManager.triggerLabel(owner) + " 与 "
                        + SkillPanelManager.triggerLabel(trigger) + " 占用（同组会让两个键位都失效）");
            }
        }
        return problems;
    }

    /**
     * 预设要恢复成的「技能 -> 等级」表，按技能 ID 去重（同一技能占多个键位时取<b>最高</b>等级）。
     *
     * <p>扣点（{@link #requiredPoints}）和套用（{@link #applyLevels}）必须共用这一张表：
     * 如果扣点按最高等级算、套用却按遍历顺序让最后一个键位覆盖，同一个技能在预设里出现两次
     * 且等级不同时，玩家会按高等级付点数、却只拿到低等级。正常流程下同一个技能不可能装进
     * 两个键位（{@code equip()} 会拦），但预设文件是允许管理员手工编辑的，所以这里必须自洽。
     *
     * <p>返回的 key 是小写技能 ID（去重键），value 是等级；调用方回写时用 {@link #skillIdOf}
     * 拿原始大小写，避免把玩家数据里的技能 ID 改成小写。
     */
    private Map<String, Integer> presetLevels(Preset preset) {
        Map<String, Integer> bySkill = new LinkedHashMap<>();
        if (preset == null) return bySkill;
        for (PresetSlot slot : preset.slots().values()) {
            if (!slot.bound()) continue;
            bySkill.merge(slot.skillId().toLowerCase(Locale.ROOT), slot.level(), Math::max);
        }
        return bySkill;
    }

    /** 去重表里的原始大小写技能 ID：回写玩家数据时用它，别用 key（key 是小写的）。 */
    private String skillIdOf(Preset preset, String lowerKey) {
        if (preset == null) return lowerKey;
        for (PresetSlot slot : preset.slots().values()) {
            if (slot.bound() && slot.skillId().toLowerCase(Locale.ROOT).equals(lowerKey)) return slot.skillId();
        }
        return lowerKey;
    }

    /** 按预设把技能恢复到这些等级一共需要多少技能点。 */
    public int requiredPoints(Preset preset) {
        int total = 0;
        for (int level : presetLevels(preset).values()) total += SkillLevelManager.cumulativePoints(level);
        return total;
    }

    // 这里刻意<b>没有</b>「把 skill-levels.yml 的等级求和当作已投入点数」的方法。
    // 那个和是个陷阱：升级发生在 EarthExp 扣点接口出现之前的技能从没被扣过，
    // 求和会算出一个退不出来的数字。可退的上限只有 EarthExp 的账本说了算，
    // 走 SkillLevelManager.spentSkillPoints(player)。

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    /** 把玩家当前的整套技能配置保存到 {@code index}（覆盖原有内容）。 */
    public SaveResult save(Player player, int index) {
        if (!valid(index) || player == null) return SaveResult.SAVE_FAILED;
        SkillPanelManager panel = plugin.getSkillPanelManager();
        SkillLevelManager levels = plugin.getSkillLevelManager();
        UUID playerId = player.getUniqueId();
        Map<String, PresetSlot> slots = new LinkedHashMap<>();
        boolean anyBound = false;
        for (String trigger : SkillPanelManager.TRIGGERS) {
            String id = panel.getSkillId(player, trigger);
            if (id != null && !id.isBlank()) anyBound = true;
            slots.put(trigger, new PresetSlot(
                    id == null ? "" : id,
                    levels == null ? SkillLevelManager.MIN_LEVEL : levels.level(playerId, id),
                    panel.isTriggerEnabled(player, trigger),
                    panel.isActivationMessagesEnabled(player, trigger),
                    panel.isCooldownMessagesEnabled(player, trigger),
                    panel.isReadyMessagesEnabled(player, trigger)));
        }
        // 一个技能都没绑的「预设」没有任何意义，存下来只会让玩家以后加载到一个空面板。
        if (!anyBound) return SaveResult.EMPTY_PRESET;

        String base = basePath(playerId, index);
        // 落盘失败时要能原样还原，所以先按「深层键 -> 值」把旧预设整棵拍下来。
        // 只拍叶子值：父节点会在还原时由子键自动重建，把 ConfigurationSection 对象塞回去反而会出错。
        Map<String, Object> previous = new LinkedHashMap<>();
        ConfigurationSection old = data.getConfigurationSection(base);
        if (old != null) {
            for (String key : old.getKeys(true)) {
                Object value = old.get(key);
                if (value instanceof ConfigurationSection) continue;
                previous.put(key, value);
            }
        }

        data.set(base, null);
        data.set(base + ".saved-at", System.currentTimeMillis());
        data.set(base + ".panel-enabled", panel.isEnabled(player));
        for (Map.Entry<String, PresetSlot> entry : slots.entrySet()) {
            String path = base + ".slots." + entry.getKey();
            PresetSlot slot = entry.getValue();
            data.set(path + ".skill", slot.skillId());
            data.set(path + ".level", slot.level());
            data.set(path + ".enabled", slot.triggerEnabled());
            data.set(path + ".activation", slot.activationMessages());
            data.set(path + ".cooldown", slot.cooldownMessages());
            data.set(path + ".ready", slot.readyMessages());
        }
        if (YamlStore.save(data, dataFile, plugin.getLogger())) return SaveResult.OK;
        // 落盘失败：把内存里的改动回滚，避免「内存有、文件没有」的假象。
        data.set(base, null);
        for (Map.Entry<String, Object> entry : previous.entrySet()) {
            data.set(base + "." + entry.getKey(), entry.getValue());
        }
        return SaveResult.SAVE_FAILED;
    }

    /** 删除一个预设。 */
    public boolean delete(Player player, int index) {
        if (!valid(index) || player == null) return false;
        String base = basePath(player.getUniqueId(), index);
        ConfigurationSection old = data.getConfigurationSection(base);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        if (old != null) {
            for (String key : old.getKeys(true)) {
                Object value = old.get(key);
                if (value instanceof ConfigurationSection) continue;
                snapshot.put(key, value);
            }
        }
        data.set(base, null);
        if (YamlStore.save(data, dataFile, plugin.getLogger())) return true;
        for (Map.Entry<String, Object> entry : snapshot.entrySet()) {
            data.set(base + "." + entry.getKey(), entry.getValue());
        }
        return false;
    }

    /**
     * 加载一个预设：还原键位绑定与全部开关，并把等级按预设套用。
     *
     * <p><b>三种模式，刻意区分开</b>：
     * <ol>
     *   <li><b>技能点真的会扣</b>（EarthExp 的 API 上有 {@code spendTalentPoint}）：
     *       先把所有技能打回 Lv.1 释放点数，再按预设重新分配。这是需求里说的
     *       「强行重置天赋并分配给当前技能」。</li>
     *   <li><b>接了 EarthExp 但还不扣点</b>：不能重置。因为点数从来没有真的被扣过，
     *       重置等级<b>换不回任何点数</b>，只会白洗掉玩家的进度。此时直接套用预设等级、
     *       不碰其他技能、也不扣点。</li>
     *   <li><b>宝石模式</b>：等级是拿宝石买的，同样不重置、不套用（否则等于白送宝石买来的等级），
     *       只还原绑定与开关。</li>
     * </ol>
     *
     * <p>所有会改变数据的动作都在校验通过之后才执行，所以「点数不够」这类失败不会留下
     * 半成品状态——玩家的技能等级要么完全按预设，要么完全没动。
     */
    public LoadOutcome load(Player player, int index) {
        Preset preset = preset(player, index);
        if (preset == null) return new LoadOutcome(LoadResult.EMPTY, List.of(), 0, 0, false, false, 0);
        List<String> problems = problems(player, preset);
        if (!problems.isEmpty()) {
            return new LoadOutcome(LoadResult.BROKEN, problems, 0, 0, false, false, 0);
        }

        SkillLevelManager levels = plugin.getSkillLevelManager();
        boolean skillPointMode = levels != null && levels.usesSkillPoints();
        // 只有「接口真的会扣点」才敢重置：见方法注释第 2 条。
        boolean reallocate = skillPointMode && levels.deductsSkillPoints();
        int required = 0;
        int available = 0;
        int remaining = 0;

        if (reallocate) {
            required = requiredPoints(preset);
            // 可用的点数 = 现在手里的 + 【本插件账上实际扣过的】。
            // 后者必须走 EarthExp 的账本，不能用 skill-levels.yml 求和：升级发生在扣点接口
            // 出现之前的技能从没被扣过，账上没有记录，求和会算出一个退不出来的数字。
            available = levels.availableSkillPoints(player) + levels.spentSkillPoints(player);
            if (required > available) {
                return new LoadOutcome(LoadResult.NOT_ENOUGH_POINTS, List.of(), required, available, false, true, 0);
            }
            remaining = available - required;
            // 先动点数与等级（会失败的那部分），成功了再改绑定——失败时玩家完全没被动过。
            LoadResult allocation = applyLevels(player, preset, required);
            if (allocation != LoadResult.OK) {
                return new LoadOutcome(allocation, List.of(), required, available, false, true, 0);
            }
        } else if (skillPointMode) {
            if (!applyPresetLevels(player, preset)) {
                return new LoadOutcome(LoadResult.SAVE_FAILED, List.of(), 0, 0, false, true, 0);
            }
        }

        applyBindings(player, preset);
        return new LoadOutcome(LoadResult.OK, List.of(), required, available, reallocate, skillPointMode, remaining);
    }

    /** 还原八个键位的绑定与四个开关，外加面板总开关。 */
    private void applyBindings(Player player, Preset preset) {
        SkillPanelManager panel = plugin.getSkillPanelManager();
        panel.setEnabled(player, preset.panelEnabled());
        for (String trigger : SkillPanelManager.TRIGGERS) {
            PresetSlot slot = preset.slots().get(trigger);
            if (slot == null) {
                panel.setSkillId(player, trigger, null);
                continue;
            }
            panel.setSkillId(player, trigger, slot.bound() ? slot.skillId() : null);
            panel.setTriggerEnabled(player, trigger, slot.triggerEnabled());
            panel.setActivationMessagesEnabled(player, trigger, slot.activationMessages());
            panel.setCooldownMessagesEnabled(player, trigger, slot.cooldownMessages());
            panel.setReadyMessagesEnabled(player, trigger, slot.readyMessages());
        }
    }

    /**
     * 天赋重分配：把所有技能打回 Lv.1（释放点数），再按预设把点数花到预设技能上。
     *
     * <p><b>只有确认 EarthExp 真的会扣点时才允许调用</b>（{@link #load} 负责把关）：
     * 点数从没被扣过时，把等级打回 Lv.1 是纯粹的进度损失，一分点数也换不回来。
     *
     * <p>调用前还必须已经确认点数足够（同样由 {@link #load} 把关）。
     *
     * <p><b>三步都是可回滚的</b>，顺序刻意是「先挪点数、最后写等级」：
     * <ol>
     *   <li>退：把 EarthExp 账上本插件实际扣过的点数要回来（上限由账本卡住）；</li>
     *   <li>扣：扣不动就立刻把刚退的原样塞回去并返回 {@link LoadResult#SPEND_REJECTED}；</li>
     *   <li>写：等级表<b>一次原子落盘</b>，失败就把点数改动整个回滚并返回
     *       {@link LoadResult#SAVE_FAILED}。</li>
     * </ol>
     * 任何一步失败，玩家都是「什么都没发生」，绝不会出现等级洗了、点数没到手。
     *
     * <p>等级表用 {@code SkillLevelManager.setLevels} 一次写完：连着调好几次
     * {@code setLevel} 会在中途失败时留下「一部分已重置、一部分没恢复」的半成品。
     *
     * @return {@link LoadResult#OK} 表示全部成功；否则数据未被改动
     */
    private LoadResult applyLevels(Player player, Preset preset, int required) {
        SkillLevelManager levels = plugin.getSkillLevelManager();
        UUID playerId = player.getUniqueId();

        // 目标表：先把当前所有技能都设为 Lv.1（释放点数）……
        Map<String, Integer> target = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : levels.levels(playerId).entrySet()) {
            target.put(entry.getKey(), SkillLevelManager.MIN_LEVEL);
        }
        // ……再按预设把预设里的技能设回保存的等级（与扣点共用同一张去重表）。
        for (Map.Entry<String, Integer> entry : presetLevels(preset).entrySet()) {
            target.put(skillIdOf(preset, entry.getKey()), entry.getValue());
        }

        // 顺序是刻意的：先把点数挪到位（会失败的部分），最后才写等级。
        // 这样任何一步失败都能把点数原样退回去，玩家不会出现「等级洗了、点数没到手」。

        // 1) 退：把本插件账上实际扣过的点数要回来。上限由 EarthExp 的账本卡住，
        //    没扣过的（老玩家、升级发生在扣点接口之前）退不出任何点数。
        int reclaimable = levels.spentSkillPoints(player);
        int refunded = reclaimable > 0 ? levels.refundPoints(player, reclaimable) : 0;

        // 2) 扣：EarthExp 拒绝（点数不足，或这条轨道没开放外部消费）就收手，
        //    把刚退的点数原样塞回去，什么都不改。
        if (required > 0 && !levels.spendPoints(player, required)) {
            if (refunded > 0) levels.spendPoints(player, refunded);
            return LoadResult.SPEND_REJECTED;
        }

        // 3) 落等级：写盘失败就把点数改动整个回滚，磁盘上仍是改动前的状态。
        if (!levels.setLevels(playerId, target)) {
            if (required > 0) levels.refundPoints(player, required);
            if (refunded > 0) levels.spendPoints(player, refunded);
            return LoadResult.SAVE_FAILED;
        }
        return LoadResult.OK;
    }

    /**
     * 直接套用预设等级：把预设里的技能设成保存的等级，<b>不碰</b>其他技能、不扣点。
     *
     * <p>用于「接了 EarthExp 但它的接口还没实装扣点」那一档。此时没有天赋经济可重置，
     * 重置只会白洗进度；而套用等级本身不产生任何费用，是唯一既不破坏数据、
     * 又能让预设真正生效的做法。
     *
     * <p>按 {@link #presetLevels} 去重后的表写入，保证与扣点用的是同一份等级，
     * 不会出现「按高等级付费、只拿到低等级」。
     *
     * @return false 表示落盘失败，等级没有被改动
     */
    private boolean applyPresetLevels(Player player, Preset preset) {
        SkillLevelManager levels = plugin.getSkillLevelManager();
        UUID playerId = player.getUniqueId();
        // 保留玩家已有的其他技能，只覆盖预设点名的那些：这一步不重置任何东西。
        Map<String, Integer> target = new LinkedHashMap<>(levels.levels(playerId));
        for (Map.Entry<String, Integer> entry : presetLevels(preset).entrySet()) {
            target.put(skillIdOf(preset, entry.getKey()), entry.getValue());
        }
        return levels.setLevels(playerId, target);
    }

    private boolean valid(int index) {
        return index >= 0 && index < PRESET_COUNT;
    }

    private String basePath(UUID playerId, int index) {
        // 索引按 1..9 存，和界面上显示的「预设 1」对齐，管理员看文件时不用换算。
        return "players." + playerId + ".presets." + (index + 1);
    }
}
