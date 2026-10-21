package EarthRPGItem.skills;

import EarthRPGItem.EarthRPGItem;
import EarthRPGItem.items.FSkillConfig;
import EarthRPGItem.items.RpgItem;
import EarthRPGItem.items.RpgItemType;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

public final class SkillPanelManager {
    public static final List<String> TRIGGERS = List.of("Q", "SQ", "F", "SF", "R", "SR", "L", "SL");

    /**
     * {@link #diagnose} 里「主手没有拿着 RPG 物品」这条原因。
     *
     * <p>触发路径对它保持<b>静默</b>：玩家拿着食物右键时本来只是想吃饭，
     * 每 1.5 秒弹一句「无法释放 R 技能」比技能误触发还烦。
     * 想知道为什么放不出技能，用 {@code /rpgitem skillcheck}。
     */
    public static final String REASON_NO_HELD_ITEM = "主手没有拿着 RPG 物品";

    /**
     * The real key behind each trigger, so a panel label can read "Q 丢弃键" instead of just "Q".
     * <p>
     * The abbreviations mean nothing on their own - nothing in the game calls the drop key "Q" - so
     * every label that names a trigger carries the key's actual name next to it. The four base keys
     * are the ones the plugin listens to: Q drops the held item, F swaps it with the offhand, and
     * R / L are the right- and left-click bindings. The S-prefixed variants are the crouching
     * (sneaking) versions of those same four.
     */
    public static String triggerName(String trigger) {
        return switch (trigger == null ? "" : trigger.toUpperCase(Locale.ROOT)) {
            case "Q" -> "丢弃键";
            case "SQ" -> "潜行+丢弃键";
            case "F" -> "副手交换键";
            case "SF" -> "潜行+副手交换键";
            case "R" -> "右键";
            case "SR" -> "潜行+右键";
            case "L" -> "左键";
            case "SL" -> "潜行+左键";
            default -> "键位";
        };
    }

    /** The abbreviation plus the key's real name, e.g. {@code "Q 丢弃键"}, for titles and lore. */
    public static String triggerLabel(String trigger) {
        return trigger + " " + triggerName(trigger);
    }
    private final EarthRPGItem plugin;
    private final NamespacedKey enabledKey;
    private final Map<String, NamespacedKey> slotKeys = new HashMap<>();
    private final Map<String, NamespacedKey> triggerEnabledKeys = new HashMap<>();
    private final Map<String, NamespacedKey> cooldownMessageKeys = new HashMap<>();
    private final Map<String, NamespacedKey> readyMessageKeys = new HashMap<>();
    private final Map<String, NamespacedKey> activationMessageKeys = new HashMap<>();

    public SkillPanelManager(EarthRPGItem plugin) {
        this.plugin = plugin;
        enabledKey = new NamespacedKey(plugin, "skill_panel_enabled");
        for (String trigger : TRIGGERS) {
            String normalized = trigger.toLowerCase(Locale.ROOT);
            slotKeys.put(trigger, new NamespacedKey(plugin, "skill_panel_" + normalized));
            triggerEnabledKeys.put(trigger, new NamespacedKey(plugin, "skill_panel_" + normalized + "_enabled"));
            cooldownMessageKeys.put(trigger, new NamespacedKey(plugin, "skill_panel_" + normalized + "_cooldown_messages"));
            readyMessageKeys.put(trigger, new NamespacedKey(plugin, "skill_panel_" + normalized + "_ready_messages"));
            activationMessageKeys.put(trigger, new NamespacedKey(plugin, "skill_panel_" + normalized + "_activation_messages"));
        }
    }

    /**
     * Whether this key's "released the skill" message is wanted. Independent per key, so a player
     * can silence the cast spam on one trigger while keeping it on another.
     */
    public boolean isActivationMessagesEnabled(Player player, String trigger) {
        NamespacedKey key = activationMessageKeys.get(trigger.toUpperCase(Locale.ROOT));
        return key != null && player.getPersistentDataContainer().getOrDefault(key, PersistentDataType.BYTE,
                (byte) (plugin.getConfig().getBoolean("skill-panel.default-activation-message-enabled", true) ? 1 : 0)) != 0;
    }

    public void setActivationMessagesEnabled(Player player, String trigger, boolean value) {
        NamespacedKey key = activationMessageKeys.get(trigger.toUpperCase(Locale.ROOT));
        if (key != null) player.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) (value ? 1 : 0));
    }

    /**
     * Whether this key's "still cooling down" style messages are wanted. Every key is independent,
     * so a player can silence the spam on one trigger and keep it on another.
     */
    public boolean isCooldownMessagesEnabled(Player player, String trigger) {
        NamespacedKey key = cooldownMessageKeys.get(trigger.toUpperCase(Locale.ROOT));
        return key != null && player.getPersistentDataContainer().getOrDefault(key, PersistentDataType.BYTE,
                (byte) (plugin.getConfig().getBoolean("skill-panel.default-cooldown-message-enabled", true) ? 1 : 0)) != 0;
    }

    public void setCooldownMessagesEnabled(Player player, String trigger, boolean value) {
        NamespacedKey key = cooldownMessageKeys.get(trigger.toUpperCase(Locale.ROOT));
        if (key != null) player.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) (value ? 1 : 0));
    }

    /** Whether this key's "skill is ready" message is wanted. Independent per key, as above. */
    public boolean isReadyMessagesEnabled(Player player, String trigger) {
        NamespacedKey key = readyMessageKeys.get(trigger.toUpperCase(Locale.ROOT));
        return key != null && player.getPersistentDataContainer().getOrDefault(key, PersistentDataType.BYTE,
                (byte) (plugin.getConfig().getBoolean("skill-panel.default-ready-message-enabled", true) ? 1 : 0)) != 0;
    }

    public void setReadyMessagesEnabled(Player player, String trigger, boolean value) {
        NamespacedKey key = readyMessageKeys.get(trigger.toUpperCase(Locale.ROOT));
        if (key != null) player.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) (value ? 1 : 0));
    }
    public boolean isEnabled(Player player) {
        return player.getPersistentDataContainer().getOrDefault(enabledKey, PersistentDataType.BYTE,
                (byte) (plugin.getConfig().getBoolean("skill-panel.default-enabled", false) ? 1 : 0)) != 0;
    }
    public void setEnabled(Player player, boolean value) { player.getPersistentDataContainer().set(enabledKey, PersistentDataType.BYTE, (byte) (value ? 1 : 0)); }
    public boolean isTriggerEnabled(Player player, String trigger) {
        NamespacedKey key = triggerEnabledKeys.get(trigger.toUpperCase(Locale.ROOT));
        return key != null && player.getPersistentDataContainer().getOrDefault(key, PersistentDataType.BYTE,
                (byte) (plugin.getConfig().getBoolean("skill-panel.default-trigger-enabled", true) ? 1 : 0)) != 0;
    }
    public void setTriggerEnabled(Player player, String trigger, boolean value) {
        NamespacedKey key = triggerEnabledKeys.get(trigger.toUpperCase(Locale.ROOT));
        if (key != null) player.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) (value ? 1 : 0));
    }
    public String getSkillId(Player player, String trigger) { NamespacedKey key = slotKeys.get(trigger.toUpperCase(Locale.ROOT)); return key == null ? null : player.getPersistentDataContainer().get(key, PersistentDataType.STRING); }
    public void setSkillId(Player player, String trigger, String id) { NamespacedKey key = slotKeys.get(trigger.toUpperCase(Locale.ROOT)); if (key == null) return; if (id == null || id.isBlank()) player.getPersistentDataContainer().remove(key); else player.getPersistentDataContainer().set(key, PersistentDataType.STRING, id); }
    /**
     * One registered skill. Entries come either from skill-panel.skills in config.yml or,
     * when no config entry references it, from a .yml file discovered in the skills folder.
     */
    public record SkillRegistration(String id, String skillFile, String rpgItemId, String displayName,
                                    String materialName, String skillGroup, List<String> supportedWeapons,
                                    List<String> description, boolean fromConfig) {
    }

    private Map<String, SkillRegistration> registrations;

    public void invalidateRegistrations() { registrations = null; }

    public Map<String, SkillRegistration> registrations() {
        if (registrations != null) return registrations;
        Map<String, SkillRegistration> built = new LinkedHashMap<>();
        Set<String> referencedFiles = new HashSet<>();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("skill-panel.skills");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                ConfigurationSection node = definition(id);
                if (node == null) continue;
                String file = node.getString("skill-file", "").trim();
                if (!file.isBlank()) referencedFiles.add(file.toLowerCase(Locale.ROOT));
                built.put(id, new SkillRegistration(id, file, node.getString("rpgitem", "").trim(),
                        node.getString("display-name", "&d" + id),
                        node.getString("display-material", node.getString("material", "ENCHANTED_BOOK")),
                        node.getString("skill-group", "").trim(),
                        node.getStringList("supported-weapons"),
                        registrationDescription(node.getStringList("description"), node.getStringList("lore")), true));
            }
        }
        // Auto-discovery: every skills file the config does not reference becomes its own skill,
        // so adding a skill only requires dropping a .yml into the skills folder.
        for (String fileName : plugin.getRpgItemManager().listSkillFiles()) {
            if (referencedFiles.contains(fileName.toLowerCase(Locale.ROOT))) continue;
            String id = fileName.substring(0, fileName.length() - 4);
            if (built.containsKey(id)) continue;
            YamlConfiguration fileConfig = plugin.getRpgItemManager().skillFileConfig(fileName);
            String display = fileConfig == null ? id
                    : fileConfig.getString("display-name", fileConfig.getString("cooldown-name", id));
            built.put(id, new SkillRegistration(id, fileName, "",
                    display == null || display.isBlank() ? id : display,
                    fileConfig == null ? "ENCHANTED_BOOK" : fileConfig.getString("material", "ENCHANTED_BOOK"),
                    fileConfig == null ? "" : fileConfig.getString("skill-group", "").trim(),
                    fileConfig == null ? List.of() : fileConfig.getStringList("supported-weapons"),
                    fileConfig == null ? List.of()
                            : registrationDescription(fileConfig.getStringList("description"), fileConfig.getStringList("lore")),
                    false));
        }
        registrations = built;
        return registrations;
    }

    public SkillRegistration registration(String id) {
        return id == null ? null : registrations().get(id);
    }

    /** 注册表里的描述：{@code description} 优先，没有时退回 {@code lore}，都没有就是空列表。 */
    private static List<String> registrationDescription(List<String> description, List<String> lore) {
        if (description != null && !description.isEmpty()) return List.copyOf(description);
        if (lore != null && !lore.isEmpty()) return List.copyOf(lore);
        return List.of();
    }

    public List<String> registeredSkillIds() {
        return registrations().keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public RpgItem registeredItem(String id) {
        return registeredItem(id, SkillLevelManager.MIN_LEVEL);
    }

    /**
     * 该技能在 {@code level} 等级下的实际定义：等级成长已经叠加进去。
     *
     * <p>等级只影响「解析出什么配置」，不影响注册信息本身，所以等级不同的同一技能
     * 仍然共享同一个注册定义、同一个技能组、同一份 supported-weapons。
     */
    public RpgItem registeredItem(String id, int level) {
        SkillRegistration registration = registration(id);
        if (registration == null) return null;
        if (!registration.rpgItemId().isBlank()) {
            RpgItem linked = plugin.getRpgItemManager().findById(registration.rpgItemId());
            if (linked != null) return linked;
        }
        if (registration.skillFile().isBlank()) return null;
        SkillLevelManager levels = plugin.getSkillLevelManager();
        Map<String, Double> overrides = levels == null ? Map.of() : levels.overridesFor(id, level);
        return plugin.getRpgItemManager().loadIndependentSkill(registration.id(), registration.displayName(),
                registeredMaterial(id).name(), registration.skillFile(), overrides);
    }

    /** 玩家当前拥有的该技能等级（未升级为 Lv.1）。 */
    public int skillLevel(Player player, String id) {
        SkillLevelManager levels = plugin.getSkillLevelManager();
        return levels == null || id == null ? SkillLevelManager.MIN_LEVEL : levels.level(player.getUniqueId(), id);
    }

    public Material registeredMaterial(String id) {
        SkillRegistration registration = registration(id);
        String name = registration == null ? "ENCHANTED_BOOK" : registration.materialName();
        Material material = Material.matchMaterial(name == null ? "ENCHANTED_BOOK" : name.trim());
        return material == null || material.isAir() ? Material.ENCHANTED_BOOK : material;
    }
    /**
     * The registration a skill item belongs to, or null when the stack is not a skill item.
     * <p>
     * The item's persistent id is compared directly instead of being resolved through the item
     * registry: independent skills (every skills/*.yml the config does not reference) are built
     * on demand and never registered there, so a registry lookup always missed them and clicking
     * such an item did nothing.
     */
    public String registeredSkillId(ItemStack stack) {
        String identity = plugin.getRpgItemManager().identityId(stack);
        if (identity == null || identity.isBlank()) return null;
        String match = null;
        for (String id : registeredSkillIds()) {
            if (!registeredIdentityId(id).equalsIgnoreCase(identity)) continue;
            // Two registrations sharing one underlying item are ambiguous, so refuse to guess.
            if (match != null) return null;
            match = id;
        }
        return match;
    }

    /** The persistent id a skill item of this registration carries. */
    private String registeredIdentityId(String id) {
        SkillRegistration registration = registration(id);
        if (registration == null) return "";
        if (!registration.rpgItemId().isBlank()) {
            RpgItem linked = plugin.getRpgItemManager().findById(registration.rpgItemId());
            return linked == null ? "" : linked.id();
        }
        return registration.id();
    }
    public String skillGroup(String id) {
        SkillRegistration registration = registration(id);
        return registration == null ? "" : registration.skillGroup();
    }

    public String registeredSkillFile(String id) {
        SkillRegistration registration = registration(id);
        return registration == null ? "" : registration.skillFile();
    }

    public List<String> supportedWeapons(String id) {
        SkillRegistration registration = registration(id);
        return registration == null ? List.of() : registration.supportedWeapons();
    }

    private String normalizedSkillGroup(String id) {
        return skillGroup(id).toLowerCase(Locale.ROOT);
    }

    public boolean hasGroupConflict(Player player, String trigger, String id) {
        String group = normalizedSkillGroup(id);
        if (group.isBlank()) return false;
        for (String other : TRIGGERS) {
            String otherId = getSkillId(player, other);
            if (!other.equalsIgnoreCase(trigger) && isTriggerEnabled(player, other)
                    && otherId != null && !otherId.equalsIgnoreCase(id)
                    && group.equals(normalizedSkillGroup(otherId))) return true;
        }
        return false;
    }

    /**
     * 面板键位在「这次操作」下能不能真的放出技能。
     *
     * <p>返回 null 的每一种原因都必须让调用方<b>不要取消事件</b>：玩家可能只是在吃食物、
     * 丢垃圾或普通攻击，取消掉就等于把原版操作也一起废了。
     *
     * @param heldOverride Q/SQ 专用。丢弃事件触发时物品已经离开主手，
     *                     调用方把「被丢的那件」传进来，否则这条限制会把丢弃键的技能一并废掉。
     */
    public RpgItem resolve(Player player, String trigger, ItemStack heldOverride) {
        // 插件总开关（config.yml 的 features.fskill）。放在最前面：关掉后不只是不放技能，
        // 也不能再拦截右键——否则玩家连食物都吃不了。
        if (!plugin.isFeatureEnabled("fskill")) return null;
        if (!isEnabled(player) || !isTriggerEnabled(player, trigger)) return null;
        // 「仅手持 RPG 物品才能放技能」。不这么做的话，绑了右键技能的玩家一吃食物就会放技能，
        // 而且事件被取消、食物还吃不到。
        if (requiresHeldRpgItem() && !holdsRpgItem(player, heldOverride)) return null;
        String id = getSkillId(player, trigger);
        if (id == null || !plugin.getSkillBackpackManager().contains(player.getUniqueId(), id)) return null;
        if (registration(id) == null || hasGroupConflict(player, trigger, id)) return null;
        RpgItem item = registeredItem(id, skillLevel(player, id));
        if (item == null || !triggerEnabled(item, trigger)) return null;
        return matchesWeapon(player, supportedWeapons(id)) ? item : null;
    }

    public RpgItem resolve(Player player, String trigger) {
        return resolve(player, trigger, null);
    }

    /**
     * 「仅手持 RPG 物品才能放技能」这条限制是否生效（config.yml，默认开启）。
     *
     * <p>关掉就回到旧行为：面板键位不看手里拿什么，随时可放。
     */
    public boolean requiresHeldRpgItem() {
        return plugin.getConfig().getBoolean("skill-panel.require-held-rpgitem", true);
    }

    /** 主手是否拿着本插件的 RPG 物品（饰品不算——它是纯被动的）。 */
    public boolean holdsRpgItem(Player player) {
        return holdsRpgItem(player, null);
    }

    /**
     * @param heldOverride 非 null 时用它代替主手判定（Q/SQ：物品已经离开主手）
     */
    public boolean holdsRpgItem(Player player, ItemStack heldOverride) {
        ItemStack held = heldOverride != null ? heldOverride : player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) return false;
        if (!plugin.getRpgItemManager().hasRpgIdentity(held)) return false;
        // 认不出模板（id 没注册、或物品属性签名过期需要 /rpgitem update）时仍然算「拿着 RPG 物品」：
        // 手里确实拿着本插件的东西，此时静默不放技能会让玩家以为技能坏了。
        RpgItem item = plugin.getRpgItemManager().findItem(held);
        return item == null || item.type() != RpgItemType.ACCESSORY;
    }

    public String diagnose(Player player, String trigger) {
        if (!plugin.isFeatureEnabled("fskill")) return "插件总开关关闭（features.fskill）";
        if (!isEnabled(player)) return "总开关关闭";
        if (!isTriggerEnabled(player, trigger)) return "该键位开关关闭";
        String id = getSkillId(player, trigger);
        if (id == null || id.isBlank()) return "未绑定技能";
        if (!plugin.getSkillBackpackManager().contains(player.getUniqueId(), id)) return "技能不在技能背包中: " + id;
        if (registration(id) == null) return "注册定义缺失: " + id;
        if (hasGroupConflict(player, trigger, id)) return "技能组冲突: " + skillGroup(id);
        RpgItem item = registeredItem(id);
        if (item == null) return "技能模板无效或 skills 文件缺失";
        if (!triggerEnabled(item, trigger)) return "该技能未提供此触发方式";
        // 放在最后：上面那些「绑定本身有问题」的原因对玩家更有用，先报那个。
        if (requiresHeldRpgItem() && !holdsRpgItem(player)) return REASON_NO_HELD_ITEM;
        if (!matchesWeapon(player, supportedWeapons(id))) return "当前手持武器不符合限制";
        return "正常";
    }
    private ConfigurationSection definition(String id) { return id == null ? null : plugin.getConfig().getConfigurationSection("skill-panel.skills." + id); }
    private boolean triggerEnabled(RpgItem item, String trigger) {
        if (item == null) return false;
        FSkillConfig skill = switch (trigger.toUpperCase(Locale.ROOT)) {
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
        return skill != null && skill.enabled();
    }

    public enum EquipResult { OK, NOT_IN_BACKPACK, ALREADY_EQUIPPED, GROUP_CONFLICT, TRIGGER_UNSUPPORTED, INVALID_SKILL }

    /**
     * 找出与 {@code id} 同技能组、且已经占用另一个启用键位的技能所在键位。
     *
     * <p>同一个技能组在两个键位上同时装备时，{@link #hasGroupConflict} 会让**两个键位都失效**，
     * 玩家看到的现象就是「技能按了没反应」。所以装备时先用这个方法拦下来并给出提示，
     * 而不是等玩家进游戏按了没反应再排查。
     *
     * @return 冲突的键位名，没有冲突时返回 null
     */
    public String groupConflictSlot(Player player, String trigger, String id) {
        String group = normalizedSkillGroup(id);
        if (group.isBlank()) return null;
        for (String other : TRIGGERS) {
            if (other.equalsIgnoreCase(trigger) || !isTriggerEnabled(player, other)) continue;
            String otherId = getSkillId(player, other);
            if (otherId == null || otherId.isBlank() || otherId.equalsIgnoreCase(id)) continue;
            if (group.equals(normalizedSkillGroup(otherId))) return other;
        }
        return null;
    }

    /**
     * 装备成功后的「武器类型」提醒：技能限制了 {@code supported-weapons}，而玩家当前手持的武器不在其中。
     * 这是提醒而不是拦截——玩家可能是先装备、之后换武器再用。
     *
     * @return 提醒文本，没有问题时返回 null
     */
    public String weaponMismatchHint(Player player, String id) {
        List<String> weapons = supportedWeapons(id);
        if (weapons.isEmpty() || matchesWeapon(player, weapons)) return null;
        return "该技能限制武器类型：" + String.join(" / ", weapons) + "，当前手持武器不符合";
    }

    /** Panel slots may only hold skills that are inside the player's own skill backpack. */
    public EquipResult equip(Player player, String trigger, String id) {
        if (id == null || id.isBlank() || registeredItem(id) == null) return EquipResult.INVALID_SKILL;
        if (!plugin.getSkillBackpackManager().contains(player.getUniqueId(), id)) return EquipResult.NOT_IN_BACKPACK;
        String owner = equippedTrigger(player, id);
        if (owner != null && !owner.equalsIgnoreCase(trigger)) return EquipResult.ALREADY_EQUIPPED;
        // 同技能组会在运行时互相顶掉（两个键位都失效），所以装备阶段就拦住。
        if (groupConflictSlot(player, trigger, id) != null) return EquipResult.GROUP_CONFLICT;
        // 技能必须真的提供这个触发方式：把只写了 fskill 的技能装到 Q 槽位，
        // 结果就是一个永远不可能触发的死槽位（resolve() 里 triggerEnabled 判定不过）。
        RpgItem item = registeredItem(id);
        if (item == null || !triggerEnabled(item, trigger)) return EquipResult.TRIGGER_UNSUPPORTED;
        setSkillId(player, trigger, id);
        return EquipResult.OK;
    }

    /** @return the trigger that already holds this skill id, or null when it is not equipped. */
    public String equippedTrigger(Player player, String id) {
        if (id == null || id.isBlank()) return null;
        for (String trigger : TRIGGERS) {
            String bound = getSkillId(player, trigger);
            if (bound != null && bound.equalsIgnoreCase(id)) return trigger;
        }
        return null;
    }

    public List<String> backpackIds(Player player) {
        return plugin.getSkillBackpackManager().contents(player.getUniqueId());
    }

    /**
     * 清空所有绑定了 {@code skillId} 的面板槽位。
     *
     * <p>技能被从技能背包取走后，槽位里残留的 id 会让它「看着还在面板上、按了却没反应」——
     * 因为 {@link #resolve} 会先检查技能是否还在背包里，不在就直接返回 null。
     * 所以取出的同时必须把槽位一起清掉，不能只删背包数据。
     *
     * @return 被清空的键位列表；没有任何槽位绑定该技能时返回空列表
     */
    public List<String> clearSlotsFor(Player player, String skillId) {
        if (skillId == null || skillId.isBlank()) return List.of();
        List<String> cleared = new ArrayList<>();
        for (String trigger : TRIGGERS) {
            String bound = getSkillId(player, trigger);
            if (bound == null || !bound.equalsIgnoreCase(skillId)) continue;
            setSkillId(player, trigger, null);
            cleared.add(trigger);
        }
        return cleared;
    }

    /**
     * 清理面板上已经无效的绑定：技能不在技能背包里，或注册定义已不存在（技能文件被删/改名/配置被移除）。
     *
     * <p>这类槽位本来就不会生效（{@link #resolve} 返回 null），但面板仍然把它们画成正常绑定，
     * 玩家只能得到「按了没反应」。面板每次打开时顺手清掉，保证面板显示的就是实际能用的东西。
     * 这也是「取出技能时清槽位」之外的安全网：离线期间被改动的数据、旧版本残留的绑定都会在这里被清掉。
     *
     * @return 被清理的绑定描述（如 {@code F: 横扫（不在技能背包）}）；没有无效绑定时返回空列表
     */
    public List<String> pruneInvalidSlots(Player player) {
        List<String> pruned = new ArrayList<>();
        for (String trigger : TRIGGERS) {
            String bound = getSkillId(player, trigger);
            if (bound == null || bound.isBlank()) continue;
            // 与 resolve() 用同一套「可用」判定，保证面板显示的槽位一定能触发。
            RpgItem item = registeredItem(bound);
            boolean owned = plugin.getSkillBackpackManager().contains(player.getUniqueId(), bound);
            if (item != null && owned && triggerEnabled(item, trigger)) continue;
            setSkillId(player, trigger, null);
            pruned.add(trigger + ": " + bound + (item == null
                    ? "（注册定义或技能模板已失效）"
                    : !owned ? "（不在技能背包）" : "（该技能不提供此键位）"));
        }
        return pruned;
    }

    public FSkillConfig skillConfig(RpgItem item, String trigger) {
        if (item == null || trigger == null) return null;
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

    public FSkillConfig firstEnabledSkill(RpgItem item) {
        if (item == null) return null;
        for (String trigger : TRIGGERS) {
            FSkillConfig config = skillConfig(item, trigger);
            if (config != null && config.enabled()) return config;
        }
        return null;
    }

    /** Effective cooldown mode of a registered skill, for diagnostics and lore. */
    public String cooldownMode(String id) {
        RpgItem item = registeredItem(id);
        if (item == null) return "未知";
        FSkillConfig config = firstEnabledSkill(item);
        if (config == null) return "未知";
        if (isAttackSpeedCooldown(config)) return "攻速冷却（不使用冷却时间）";
        return "固定冷却 " + formatSeconds(config.cooldownSeconds()) + " 秒";
    }

    private boolean isAttackSpeedCooldown(FSkillConfig config) {
        return config.attackSpeedCooldown()
                || (config.shortbow() != null && config.shortbow().attackSpeedCooldown());
    }

    /** Skill description lines. Applied to skill items only, never to normal RPG items. */
    public List<String> skillLore(String id, String trigger) {
        List<String> lore = new ArrayList<>();
        if (registration(id) == null) {
            lore.add("&c注册定义缺失: " + id);
            return lore;
        }
        RpgItem item = registeredItem(id);
        if (item == null) {
            lore.add("&c技能模板无效或 skills 文件缺失");
            return lore;
        }
        // 描述：注册表里写的优先，没写就用技能文件里的 description（loadItem 会把它读进
        // RpgItem.lore）。原先技能物品的 lore 完全由下面的属性行生成，把物品自身的描述整段
        // 丢掉了，所以「注册表/技能文件里填的描述」在技能物品上永远看不到。
        List<String> description = registration(id).description();
        if (description.isEmpty()) description = item.lore() == null ? List.of() : item.lore();
        if (!description.isEmpty()) {
            lore.add("&8&m------------------------");
            lore.addAll(description);
        }
        boolean attackSpeedTrigger = trigger != null
                && (trigger.equalsIgnoreCase("L") || trigger.equalsIgnoreCase("SL"));
        FSkillConfig config = skillConfig(item, trigger);
        if (config == null || !config.enabled()) config = firstEnabledSkill(item);
        SkillDisplayInfo info = SkillDisplayInfo.from(config, skillGroup(id), attackSpeedTrigger);
        lore.add("&8&m------------------------");
        lore.add("&7技能类型: &f" + info.skillType());
        if (info.attackSpeedCooldown()) {
            lore.add("&7冷却触发: &f攻速冷却（AttackSpeed）");
            lore.add("&7冷却时间: &f由攻速属性决定");
        } else {
            lore.add("&7冷却触发: &f固定冷却");
            lore.add("&7冷却时间: &f" + formatSeconds(info.cooldownSeconds()) + " &7秒");
        }
        lore.add("&7技能组: &f" + (info.skillGroup().isBlank() ? "无" : info.skillGroup()));
        lore.add("&7武器类型: &f" + weaponTypeName(supportedWeapons(id)));
        // 技能伤害 = 固定伤害 + 攻击力 × 倍率，两部分都要显示，否则纯固定伤害的技能
        // 看起来是 0，而「固定 + 倍率」的技能会少显示一半。
        lore.add("&7伤害构成: &f" + info.damageBreakdown());
        lore.add("&7伤害类型: &f" + info.damageType());
        if (info.hasDisabledSection()) {
            // 段落配了但没启用：技能不会运行，伤害自然是 0。不说清楚的话，
            // 玩家只会看到「伤害 0」然后以为技能坏了。
            lore.add("&c注意: &7" + info.disabledSection() + " 段落已配置但未启用，技能不会释放");
        }
        return lore;
    }

    /** Weapon restriction shown on the skill item. Empty or ANY means no restriction. */
    public String weaponTypeName(List<String> allowed) {
        if (allowed == null || allowed.isEmpty() || allowed.stream().anyMatch(v -> "ANY".equals(canonicalWeaponType(v)))) {
            return "默认（不限武器）";
        }
        List<String> names = new ArrayList<>();
        for (String value : allowed) {
            if (value == null || value.isBlank()) continue;
            names.add(weaponTypeLabel(value.trim()));
        }
        return names.isEmpty() ? "默认（不限武器）" : String.join(" / ", names);
    }

    private String weaponTypeLabel(String value) {
        if (value == null) return "";
        String canonical = canonicalWeaponType(value);
        if (canonical == null) return value;
        return switch (canonical) {
            case "SWORD" -> "剑";
            case "AXE" -> "斧";
            case "BOW" -> "弓";
            case "CROSSBOW" -> "弩";
            case "STAFF" -> "法杖";
            case "DAGGER" -> "匕首";
            case "ANY" -> "不限";
            default -> value;
        };
    }

    private String formatSeconds(double value) {
        if (value == Math.floor(value)) return String.valueOf((long) value);
        return String.format(Locale.US, "%.2f", value);
    }

    private boolean matchesWeapon(Player player, List<String> allowed) {
        if (allowed == null || allowed.isEmpty() || allowed.stream().anyMatch(v -> "ANY".equals(canonicalWeaponType(v)))) return true;
        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();
        return matchesWeaponStack(main, allowed) || matchesWeaponStack(off, allowed);
    }

    /**
     * 一个手持物品是否符合技能允许的武器列表（「任一满足即可」）。
     * <p>
     * 按以下顺序解析该物品的武器类型：
     * <ol>
     *   <li>注册表声明的 {@code weapon-type}（物品 yml 里写的，例如 剑/法杖/弓/弩/匕首）；</li>
     *   <li>回退：按 Material 名字推断（DIAMOND_SWORD→剑、BOW→弓……）。</li>
     * </ol>
     * 配置值同时接受中文与英文（剑/SWORD、法杖/STAFF、弓/BOW、弩/CROSSBOW、匕首/DAGGER），
     * 以及任意自定义标签（物品 weapon-type 写什么，技能 supported-weapons 就写什么，大小写不敏感）。
     */
    private boolean matchesWeaponStack(ItemStack stack, List<String> allowed) {
        if (stack == null || stack.getType().isAir()) return false;
        String registered = plugin.getRpgItemManager().registeredWeaponType(stack);
        boolean declared = registered != null && !registered.isBlank();
        String type = canonicalWeaponType(declared ? registered : inferWeapon(stack.getType()));
        if (type == null) return false;
        for (String value : allowed) {
            if (value == null) continue;
            String wanted = canonicalWeaponType(value);
            if (wanted == null) continue;
            // A declared registry type is authoritative; material aliases apply only to legacy fallback.
            if (wanted.equals(type)) return true;
            if (!declared && wanted.equalsIgnoreCase(stack.getType().name())) return true;
        }
        return false;
    }

    /** 材质推断（兼容旧回退）：_SWORD→剑、_AXE→斧、BOW→弓、CROSSBOW→弩、含 ROD/STAFF→法杖、兜底→不限。 */
    private String inferWeapon(Material material) {
        String name = material.name();
        if (name.endsWith("_SWORD")) return "SWORD";
        if (name.endsWith("_AXE")) return "AXE";
        if (name.equals("BOW")) return "BOW";
        if (name.equals("CROSSBOW")) return "CROSSBOW";
        if (name.contains("ROD") || name.contains("STAFF")) return "STAFF";
        return "ANY";
    }

    /**
     * 把中文/英文类别名归一成英文规范形（剑↔SWORD、法杖↔STAFF、弓↔BOW、弩↔CROSSBOW、
     * 匕首↔DAGGER）；自定义标签原样返回（已 trim、大写）。ANY/不限 归一到 ANY。
     */
    private String canonicalWeaponType(String raw) {
        if (raw == null) return null;
        String value = raw.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "ANY", "不限", "任意" -> "ANY";
            case "SWORD", "剑" -> "SWORD";
            case "AXE", "斧" -> "AXE";
            case "BOW", "弓" -> "BOW";
            case "CROSSBOW", "弩", "驽" -> "CROSSBOW";
            case "STAFF", "法杖", "杖" -> "STAFF";
            case "DAGGER", "匕首", "短刀" -> "DAGGER";
            default -> value;
        };
    }
}

