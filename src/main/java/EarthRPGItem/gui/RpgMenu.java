package EarthRPGItem.gui;

import EarthRPGItem.EarthRPGItem;
import EarthRPGItem.collection.CollectionDefinition;
import EarthRPGItem.items.EnhanceStationManager;
import EarthRPGItem.items.EnhancementManager;
import EarthRPGItem.items.EnhancementService;
import EarthRPGItem.items.GemstoneManager;
import EarthRPGItem.items.RpgItem;
import EarthRPGItem.skills.SkillLevelManager;
import EarthRPGItem.skills.SkillPanelManager;
import EarthRPGItem.skills.SkillPresetManager;
import EarthRPGItem.util.Texts;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public class RpgMenu {

    public static final int SIZE = 54;
    public static final int PAGE_SIZE = 45;
    public static final int PREVIOUS_SLOT = 45;
    public static final int BACK_SLOT = 49;
    public static final int NEXT_SLOT = 53;
    public static final String BACKPACK_MANAGE = "MANAGE";

    /**
     * Enhancement station layout. The item being enhanced sits in the middle with its numbers on
     * either side, the material slot and the button sit together on the row below, and the controls
     * stay on the bottom row like every other menu.
     * <p>
     * Shared with {@code RpgMenuListener}, so drawing and click handling cannot drift: a slot that is
     * drawn in one place and read from another is exactly how a GUI ends up firing the wrong action.
     */
    public static final int ENHANCE_EQUIPMENT_SLOT = 20;
    public static final int ENHANCE_LEVEL_SLOT = 21;
    public static final int ENHANCE_RATE_SLOT = 22;
    public static final int ENHANCE_PENALTY_SLOT = 23;
    public static final int ENHANCE_PREVIEW_SLOT = 24;
    public static final int ENHANCE_MATERIAL_SLOT = 30;
    public static final int ENHANCE_BUTTON_SLOT = 32;
    /**
     * Close is the only bottom-row control. There is deliberately <b>no</b> "back to main menu"
     * button: the main GUI ({@code /rpgitem gui}) is the admin interface and is gated behind
     * {@code earthrpgitem.gui}, while this station is player-facing. A player clicking "back" would
     * land in a menu they have no permission for — the item browser and the reload switch — so the
     * only way out of a station is to close it.
     */
    public static final int ENHANCE_CLOSE_SLOT = 49;

    /**
     * Gem-station layout. The equipment copy sits at the centre and the sockets run along one row
     * below it, in slot order, so the row reads left-to-right as the sockets are numbered.
     * <p>
     * {@link #GEM_SOCKET_MAX} caps what the station displays; an item declaring more sockets than
     * this keeps them (they still grant their stats) but only the first nine are reachable here.
     * Shared with {@code RpgMenuListener} for the same reason as the enhancement slots.
     */
    public static final int GEM_EQUIPMENT_SLOT = 20;
    public static final int GEM_INFO_SLOT = 22;
    public static final int GEM_HELP_SLOT = 24;
    public static final int GEM_SOCKET_FIRST_SLOT = 30;
    public static final int GEM_SOCKET_MAX = 9;
    /** Close only, for the same reason as {@link #ENHANCE_CLOSE_SLOT}: this station is player-facing. */
    public static final int GEM_CLOSE_SLOT = 49;

    /**
     * Skill-panel layout. Each of the eight triggers owns one column, so a key reads vertically: its
     * slot on the top row, then its four switches below. The panel-level controls sit on the bottom
     * row, which is what frees the top row for the slots.
     * <p>
     * These are shared with {@code RpgMenuListener} so the drawing and the click handling cannot drift
     * apart - the old layout hard-coded the ranges in both places.
     */
    public static final int PANEL_SLOT_BASE = 0;
    public static final int PANEL_TRIGGER_BASE = 9;
    public static final int PANEL_ACTIVATION_BASE = 18;
    public static final int PANEL_COOLDOWN_BASE = 27;
    public static final int PANEL_READY_BASE = 36;
    /** Panel-wide on/off switch, at the right end of the slot row. */
    public static final int PANEL_MASTER_SLOT = 8;
    public static final int PANEL_BACKPACK_SLOT = 45;
    /** 技能面板底行的「技能预设」入口，夹在「技能背包」和「关闭」之间。 */
    public static final int PANEL_PRESET_SLOT = 47;
    public static final int PANEL_CLOSE_SLOT = 49;
    /**
     * 技能面板底行的「技能强化台」入口，放在「关闭」右边。
     * <p>
     * 刻意<b>不动</b>已有的 45/47/49 三个槽位：玩家已经记住位置，而且老版本的前进/后退按钮
     * 和文档都按这几个值写过。新增入口用新槽位，是纯增量改动。
     */
    public static final int PANEL_FORGE_SLOT = 51;

    /**
     * 技能强化台的权限节点。
     * <p>
     * 定义在这里而不是 {@code RpgMenuListener} 里，是因为现在有两个入口要用它：主菜单的
     * 「技能强化台」按钮，以及技能面板底行新增的同一个入口。两处各写一遍字面量迟早会漂移
     * （改了一处、另一处忘了改，表现就是「一个入口能进、另一个说没权限」）。
     */
    public static final String SKILL_FORGE_PERMISSION = "earthrpgitem.skillforge";

    /**
     * 技能预设界面：9 个预设占满第 0 行（一排），控制按钮在底行。
     * <p>
     * 预设数量固定在 {@link EarthRPGItem.skills.SkillPresetManager#PRESET_COUNT}，
     * 界面直接按它铺满一排；改数量只需要改那一个常量，绘制与点击都跟着走。
     */
    public static final int PRESET_BASE = 0;
    public static final int PRESET_BACK_SLOT = 45;
    /** 删除模式开关，与技能背包同一个交互习惯：开着时点击预设=删除。 */
    public static final int PRESET_DELETE_MODE_SLOT = 47;
    public static final int PRESET_CLOSE_SLOT = 49;

    /** 技能背包底部行的「删除模式」开关。技能不能再取出；删除模式开着时点击技能=删除（退还技能点）。 */
    public static final int BACKPACK_DELETE_MODE_SLOT = 47;

    /** 删除技能确认界面：中间展示要删的技能，下方是「确认删除」和「取消」。 */
    public static final int DELETE_CONFIRM_SKILL_SLOT = 22;
    public static final int DELETE_CONFIRM_YES_SLOT = 30;
    public static final int DELETE_CONFIRM_NO_SLOT = 32;
    public static final int DELETE_CONFIRM_CLOSE_SLOT = 49;

    /**
     * 技能强化台（列表页）：0..44 是玩家已拥有技能的列表，翻页/返回放在底行。
     * <p>
     * 只列出玩家【已拥有】的技能（技能背包里有的），因为等级属于「玩家 + 技能 ID」，
     * 没有的技能既不能强化也没有等级可显示。
     */
    public static final int FORGE_LIST_BASE = 0;
    public static final int FORGE_PREVIOUS_SLOT = 45;
    public static final int FORGE_RESET_SLOT = 47;
    public static final int FORGE_BACK_SLOT = 49;
    public static final int FORGE_NEXT_SLOT = 53;

    /** 技能强化台（详情页）：完整成长表 + 强化按钮。 */
    public static final int FORGE_DETAIL_SKILL_SLOT = 4;
    public static final int FORGE_DETAIL_BUTTON_SLOT = 22;
    public static final int FORGE_DETAIL_BACK_SLOT = 45;
    public static final int FORGE_DETAIL_CLOSE_SLOT = 53;

    public static final String[] SETTING_PATHS = {
            "features.rpgitems", "features.gui", "features.accessory", "features.attack", "features.armor",
            "features.crit-chance", "features.crit-damage", "features.attack-speed", "features.health", "features.recovery",
            "features.life-steal", "features.magic-damage", "features.bow-damage", "features.exdraw", "features.fix",
            "features.ex-dur", "features.break-limit", "features.env-armor", "features.magic-armor", "features.resistance", "features.bow-armor",
            "features.crit-armor", "features.evasion", "features.armor-pen", "features.true-damage", "features.backstrike-damage",
            "features.ass-damage", "features.pct-damage", "features.pst-damage", "features.attack-range", "features.parry",
            "features.counter-attack", "features.ice-damage", "features.fire-damage", "features.light-damage", "features.dark-damage",
            "features.ice-armor", "features.fire-armor", "features.light-armor", "features.dark-armor", "features.hit-buffs",
            "features.slash-particles", "features.projectile-trail-particles", "features.projectile-hit-particles", "features.projectile-sounds",
            "features.parry-particles", "features.particles", "features.sounds", "features.buffs",
            "features.dodge", "features.block", "features.determination", "features.cad", "rpgitem.prevent-hotkey-drop",
            "features.vitality", "features.lore-attributes", "features.physical-crit", "features.magic-crit",
            "features.collection", "features.farming-speed", "features.mining-speed", "features.forest-speed",
            "features.farming-fortune", "features.mining-fortune", "features.forest-fortune"
    };

    private static final String[] SETTING_NAMES = {
            "&aRPGItem 总开关 / Master", "&aGUI 菜单 / GUI", "&dAccessory 饰品 / Accessory", "&c+Attack 攻击 / Attack", "&9+Armor 防御 / Armor",
            "&6+CritChance 暴击率 / Crit Chance", "&e+CritDamage 暴击伤害 / Crit Damage", "&a+AttackSpeed 攻速 / Attack Speed", "&c+Health 生命 / Health", "&a+Recovery 回复 / Recovery",
            "&4+LifeSteal 吸血 / Life Steal", "&d+MagicDamage 魔法 / Magic", "&a+BowDamage 弓伤 / Bow Damage", "&a+EXDraw 追加拉弓 / Extra Draw", "&f+Fix/s 修复 / Fix",
            "&f+ExDur 附加耐久 / Extra Durability", "&c+BreakLimit 损毁保护 / Break Limit", "&2+EnvArmor 环境护甲 / Env Armor", "&5+MagicArmor 魔法护甲 / Magic Armor", "&3+Resistance 完全抵抗 / Resistance", "&b+BowArmor 弹射物护甲 / Bow Armor",
            "&6+CritArmor 暴击护甲 / Crit Armor", "&f+Evasion 闪避 / Evasion", "&7+ArmorPen 穿透 / Armor Pen", "&4+TrueDamage 真实 / True Damage", "&8+BackstrikeDamage 背击 / Backstrike",
            "&5+AssDamage 刺客 / Assassin", "&c+PctDamage 规模 / Pct Damage", "&c+PstDamage 比例 / Pst Damage", "&6+AttackRange 范围 / Range", "&e+Parry 格挡 / Parry",
            "&e+CounterAttack 反击 / Counter", "&9+IceDamage 冰伤 / Ice Damage", "&c+FireDamage 火伤 / Fire Damage", "&e+LightDamage 光伤 / Light Damage", "&0+DarkDamage 暗伤 / Dark Damage",
            "&9+IceArmor 冰甲 / Ice Armor", "&c+FireArmor 火甲 / Fire Armor", "&e+LightArmor 光甲 / Light Armor", "&0+DarkArmor 暗甲 / Dark Armor", "&5Hit Buffs 命中药水 / Hit Buffs",
            "&7Slash Particles 挥砍粒子 / Slash", "&aProjectile Trail 弹道粒子 / Trail", "&aProjectile Hit 命中粒子 / Projectile Hit", "&eProjectile Sounds 弹射物声音 / Projectile Sounds",
            "&6Parry Particles 格挡粒子 / Parry Particles", "&bHit Particles 打击粒子 / Hit Particles", "&eSounds 打击声音 / Sounds", "&dEquipment Buffs 装备Buff / Buffs"
    };

    private final EarthRPGItem plugin;

    public RpgMenu(EarthRPGItem plugin) {
        this.plugin = plugin;
    }

    public void openMain(Player player) {
        Inventory inventory = Bukkit.createInventory(new RpgMenuHolder(RpgMenuType.MAIN, 0), SIZE, Texts.color("Arthritism"));
        inventory.setItem(20, button(Material.COMPARATOR, "&cSetting 设置", "&7Open setting GUI / 打开设置"));
        inventory.setItem(22, button(Material.DIAMOND_CHESTPLATE, "&bArmor 护甲", "&7Open armor items / 打开护甲"));
        inventory.setItem(24, button(Material.GOLDEN_PICKAXE, "&eTool 工具", "&7Open tool items / 打开工具"));
        inventory.setItem(26, button(Material.DIAMOND_SWORD, "&cWeapon 武器", "&7Open weapon items / 打开武器"));
        inventory.setItem(30, button(Material.EMERALD, "&dAccessory 饰品", "&7Open accessory items / 打开饰品"));
        inventory.setItem(28, button(Material.CHEST, "&fItems / 物品", "&7Open item items / 打开物品"));
        inventory.setItem(32, button(Material.GRASS_BLOCK, "&aCollection / 采集", "&7Open collection points / 打开采集"));
        inventory.setItem(34, button(Material.ENCHANTED_BOOK, "&bSkills 技能面板", "&7Open skill panel / 打开技能面板"));
        inventory.setItem(36, button(Material.CHEST_MINECART, "&dSkill Items 技能物品", "&7领取已注册技能物品 / Get registered skills"));
        inventory.setItem(38, button(Material.ANVIL, "&6Enhance 强化台", "&7Open enhancement station / 打开强化台"));
        inventory.setItem(40, button(Material.DIAMOND, "&bGems 宝石台", "&7Open gem station / 打开宝石台"));
        inventory.setItem(42, button(Material.SMITHING_TABLE, "&5Skill Forge 技能强化台", "&7Level up skills / 提升技能等级"));
        player.openInventory(inventory);
    }

    public void openSetting(Player player) { openSetting(player, 0); }

    public void openSetting(Player player, int page) {
        int maxPage = Math.max(1, (int) Math.ceil(SETTING_PATHS.length / (double) PAGE_SIZE));
        int currentPage = Math.max(0, Math.min(page, maxPage - 1));
        Inventory inventory = Bukkit.createInventory(new RpgMenuHolder(RpgMenuType.SETTING, currentPage), SIZE, Texts.color("Setting " + (currentPage + 1) + "/" + maxPage));
        int start = currentPage * PAGE_SIZE;
        int end = Math.min(SETTING_PATHS.length, start + PAGE_SIZE);
        for (int index = start; index < end; index++) {
            String name = settingDisplayName(index, SETTING_PATHS[index]);
            inventory.setItem(index - start, featureButton(SETTING_PATHS[index], name));
        }
        inventory.setItem(PREVIOUS_SLOT, pageButton(currentPage > 0, false, currentPage + 1, maxPage));
        inventory.setItem(BACK_SLOT, button(Material.BARRIER, "&cBack 返回", "&7Back to main GUI / 返回主菜单"));
        inventory.setItem(NEXT_SLOT, pageButton(currentPage + 1 < maxPage, true, currentPage + 1, maxPage));
        player.openInventory(inventory);
    }

    private String settingName(String path) {
        return switch (path) {
            case "features.dodge" -> "&b+Dodge 闪避 / Dodge";
            case "features.block" -> "&f+Block 格挡 / Block";
            case "features.determination" -> "&a+Determination 决意 / Determination";
            case "features.cad" -> "&6+CAD 反击增伤 / Counter Damage";
            case "rpgitem.prevent-hotkey-drop" -> "&c快捷丢弃保护 / Hotkey Drop Guard";
            case "features.vitality" -> "&c+Vitality 百分比生命 / Vitality";
            case "features.lore-attributes" -> "&e普通物品描述属性 / Lore Attributes";
            case "features.physical-crit" -> "&6物理暴击开关 / Physical Crit";
            case "features.magic-crit" -> "&d魔法暴击开关 / Magic Crit";
            case "features.collection" -> "&aCollection / 采集";
            case "features.farming-speed" -> "&eFarmingSpeed / 农业速度";
            case "features.mining-speed" -> "&7MiningSpeed / 挖掘速度";
            case "features.forest-speed" -> "&6ForestSpeed / 伐木速度";
            case "features.farming-fortune" -> "&eFarmingFortune / 农业时运";
            case "features.mining-fortune" -> "&7MiningFortune / 挖掘时运";
            case "features.forest-fortune" -> "&6ForestFortune / 伐木时运";
            default -> "&f" + path;
        };
    }

    private String settingDisplayName(int index, String path) {
        if (path.equals("features.parry")) return "&e+Parry 招架 / Parry";
        return index < SETTING_NAMES.length ? SETTING_NAMES[index] : settingName(path);
    }

    public void openGroups(Player player, RpgMenuType menuType, int page) {
        List<String> groups = plugin.getRpgItemManager().getItemGroups(menuType.getItemType());
        int maxPage = Math.max(1, (int) Math.ceil(groups.size() / (double) PAGE_SIZE));
        int currentPage = Math.max(0, Math.min(page, maxPage - 1));
        Inventory inventory = Bukkit.createInventory(new RpgMenuHolder(RpgMenuType.GROUPS, currentPage, menuType.name()), SIZE, Texts.color(menuType.getTitle() + " 文件分类"));
        int start = currentPage * PAGE_SIZE;
        int end = Math.min(groups.size(), start + PAGE_SIZE);
        for (int index = start; index < end; index++) inventory.setItem(index - start, button(Material.BOOK, "&e" + groups.get(index), "&7点击打开此文件中的物品"));
        inventory.setItem(PREVIOUS_SLOT, pageButton(currentPage > 0, false, currentPage + 1, maxPage));
        inventory.setItem(BACK_SLOT, button(Material.BARRIER, "&cBack 返回", "&7Back to main GUI / 返回主菜单"));
        inventory.setItem(NEXT_SLOT, pageButton(currentPage + 1 < maxPage, true, currentPage + 1, maxPage));
        player.openInventory(inventory);
    }

    public void openCategory(Player player, RpgMenuType menuType, int page) {
        openCategory(player, menuType, page, null);
    }

    public void openCategory(Player player, RpgMenuType menuType, int page, String group) {
        List<RpgItem> items = plugin.getRpgItemManager().getItems(menuType.getItemType(), group);
        int maxPage = Math.max(1, (int) Math.ceil(items.size() / (double) PAGE_SIZE));
        int currentPage = Math.max(0, Math.min(page, maxPage - 1));
        Inventory inventory = Bukkit.createInventory(new RpgMenuHolder(RpgMenuType.CATEGORY, currentPage, menuType.name() + "\n" + group), SIZE, Texts.color(menuType.getTitle() + " / " + group));
        int start = currentPage * PAGE_SIZE;
        int end = Math.min(items.size(), start + PAGE_SIZE);
        for (int index = start; index < end; index++) inventory.setItem(index - start, plugin.getRpgItemManager().createItem(items.get(index)));
        inventory.setItem(PREVIOUS_SLOT, pageButton(currentPage > 0, false, currentPage + 1, maxPage));
        inventory.setItem(BACK_SLOT, button(Material.BARRIER, "&cBack 返回", "&7Back to file groups / 返回文件分类"));
        inventory.setItem(NEXT_SLOT, pageButton(currentPage + 1 < maxPage, true, currentPage + 1, maxPage));
        player.openInventory(inventory);
    }

    public void openCollectionGroups(Player player, int page) {
        List<String> groups = plugin.getCollectionManager().getDefinitionGroups();
        int maxPage = Math.max(1, (int) Math.ceil(groups.size() / (double) PAGE_SIZE));
        int currentPage = Math.max(0, Math.min(page, maxPage - 1));
        Inventory inventory = Bukkit.createInventory(new RpgMenuHolder(RpgMenuType.GROUPS, currentPage, "COLLECTION"), SIZE, Texts.color("Collection 文件分类"));
        int start = currentPage * PAGE_SIZE;
        int end = Math.min(groups.size(), start + PAGE_SIZE);
        for (int index = start; index < end; index++) inventory.setItem(index - start, button(Material.BOOK, "&e" + groups.get(index), "&7点击打开采集点配置"));
        inventory.setItem(PREVIOUS_SLOT, pageButton(currentPage > 0, false, currentPage + 1, maxPage));
        inventory.setItem(BACK_SLOT, button(Material.BARRIER, "&cBack 返回", "&7返回主菜单"));
        inventory.setItem(NEXT_SLOT, pageButton(currentPage + 1 < maxPage, true, currentPage + 1, maxPage));
        player.openInventory(inventory);
    }

    public void openCollectionCategory(Player player, int page, String group) {
        List<CollectionDefinition> definitions = plugin.getCollectionManager().getDefinitions(group);
        int maxPage = Math.max(1, (int) Math.ceil(definitions.size() / (double) PAGE_SIZE));
        int currentPage = Math.max(0, Math.min(page, maxPage - 1));
        Inventory inventory = Bukkit.createInventory(new RpgMenuHolder(RpgMenuType.COLLECTION, currentPage, group), SIZE, Texts.color("Collection / " + group));
        int start = currentPage * PAGE_SIZE;
        int end = Math.min(definitions.size(), start + PAGE_SIZE);
        for (int index = start; index < end; index++) inventory.setItem(index - start, plugin.getCollectionManager().createDisplayItem(definitions.get(index)));
        inventory.setItem(PREVIOUS_SLOT, pageButton(currentPage > 0, false, currentPage + 1, maxPage));
        inventory.setItem(BACK_SLOT, button(Material.BARRIER, "&cBack 返回", "&7返回采集分类"));
        inventory.setItem(NEXT_SLOT, pageButton(currentPage + 1 < maxPage, true, currentPage + 1, maxPage));
        player.openInventory(inventory);
    }

    public void openCollection(Player player, int page) {
        openCollectionCategory(player, page, null);
    }

    public void openSkillPanel(Player player) {
        // 打开面板时先清掉已经无效的绑定（技能已不在技能背包 / 注册定义已不存在）。
        // 这类槽位本来就不会生效（resolve() 返回 null），但旧版仍把它们画成正常绑定，
        // 玩家看到的现象就是「技能还在面板上，按了却没反应」。
        List<String> pruned = plugin.getSkillPanelManager().pruneInvalidSlots(player);
        if (!pruned.isEmpty()) {
            player.sendMessage(Texts.color("&e已自动卸下无效的技能绑定：&f" + String.join("&7, &f", pruned)));
        }
        Inventory inventory = Bukkit.createInventory(new RpgMenuHolder(RpgMenuType.SKILL_PANEL, 0), SIZE, Texts.color("Skill Panel 技能面板"));
        List<String> triggers = SkillPanelManager.TRIGGERS;
        for (int index = 0; index < triggers.size(); index++) {
            String trigger = triggers.get(index);
            // Every label carries the key's real name ("Q 丢弃键"), because the abbreviation alone
            // tells a player nothing about which key to press.
            String label = SkillPanelManager.triggerLabel(trigger);
            String id = plugin.getSkillPanelManager().getSkillId(player, trigger);
            boolean triggerEnabled = plugin.getSkillPanelManager().isTriggerEnabled(player, trigger);
            // Top row: the slots themselves, so what is bound where is the first thing on screen.
            List<String> slotLore = new ArrayList<>();
            slotLore.add("&8键位: &f" + label);
            slotLore.add("&7左键更换，右键卸下");
            // 直接把这个键位当前的真实状态写在槽位上：技能组冲突、武器类型不符这类问题
            // 不用等玩家按键（或去翻 /rpgitem skillcheck）才看得到。
            if (id != null && !id.isBlank()) {
                SkillLevelManager levels = plugin.getSkillLevelManager();
                slotLore.add("&8等级: &fLv." + levels.level(player.getUniqueId(), id)
                        + " &7/ &8上限 Lv." + levels.maxLevel(id));
                String reason = plugin.getSkillPanelManager().diagnose(player, trigger);
                if (!"正常".equals(reason)) slotLore.add("&c当前无法释放：&f" + reason);
            }
            ItemStack item = id == null || id.isBlank()
                    ? button(Material.GRAY_STAINED_GLASS_PANE, "&7" + label + " &8空槽",
                             "&7左键打开技能背包装备技能", "&8技能必须先在技能背包中")
                    // The raw trigger still goes to skillItem: skillLore uses it to look the skill up.
                    : skillItem(id, trigger, slotLore);
            if (id != null && !id.isBlank()) item = withKeyPrefix(item, label);
            inventory.setItem(PANEL_SLOT_BASE + index, item);
            // One switch per key per row, each in that key's own column, so a column is one key.
            inventory.setItem(PANEL_TRIGGER_BASE + index, button(
                    triggerEnabled ? Material.LIME_WOOL : Material.RED_WOOL,
                    (triggerEnabled ? "&a" : "&c") + label + (triggerEnabled ? " 键位已启用" : " 键位已禁用"),
                    "&7点击切换此键位，不影响其他键位"));
            boolean activationMessages = plugin.getSkillPanelManager().isActivationMessagesEnabled(player, trigger);
            inventory.setItem(PANEL_ACTIVATION_BASE + index, button(
                    activationMessages ? Material.LIME_DYE : Material.GRAY_DYE,
                    (activationMessages ? "&a" : "&c") + label + " 释放提示: " + (activationMessages ? "开启" : "关闭"),
                    "&7" + label + " 成功释放技能时的提示",
                    "&8只影响提示，不影响技能效果"));
            boolean cooldownMessages = plugin.getSkillPanelManager().isCooldownMessagesEnabled(player, trigger);
            inventory.setItem(PANEL_COOLDOWN_BASE + index, button(
                    cooldownMessages ? Material.LIME_DYE : Material.GRAY_DYE,
                    (cooldownMessages ? "&a" : "&c") + label + " 冷却提示: " + (cooldownMessages ? "开启" : "关闭"),
                    "&7" + label + " 冷却中的剩余秒数提示",
                    "&7以及该键位的全局冷却与施法等待提示",
                    "&8只影响提示，不影响冷却计算"));
            boolean readyMessages = plugin.getSkillPanelManager().isReadyMessagesEnabled(player, trigger);
            inventory.setItem(PANEL_READY_BASE + index, button(
                    readyMessages ? Material.LIME_DYE : Material.GRAY_DYE,
                    (readyMessages ? "&a" : "&c") + label + " 就绪提示: " + (readyMessages ? "开启" : "关闭"),
                    "&7" + label + "「技能已就绪」提示",
                    "&8只影响提示，不影响冷却计算"));
        }
        boolean panelEnabled = plugin.getSkillPanelManager().isEnabled(player);
        inventory.setItem(PANEL_MASTER_SLOT, button(panelEnabled ? Material.LIME_WOOL : Material.RED_WOOL,
                panelEnabled ? "&a技能面板已启用" : "&c技能面板已禁用",
                "&7点击切换总开关；总开关关闭时所有面板技能停用"));
        // Bottom row: the panel-level controls, so the whole slot row stays free for the slots.
        inventory.setItem(PANEL_BACKPACK_SLOT, button(Material.CHEST, "&e技能背包 / Skill Backpack",
                "&7存放玩家已获得的技能物品",
                "&7只有技能背包中的技能才能装备到槽位"));
        inventory.setItem(PANEL_PRESET_SLOT, button(Material.WRITABLE_BOOK, "&d技能预设 / Skill Preset",
                "&7把当前整套技能配置存成预设，之后一键还原",
                "&7保存内容：八个键位的技能与等级、键位启用、",
                "&7释放提示、冷却提示、就绪提示、面板总开关",
                "&8共 9 个预设"));
        inventory.setItem(PANEL_CLOSE_SLOT, button(Material.BARRIER, "&c关闭", "&7关闭技能面板"));
        // 「技能强化台」入口：从技能面板直接进强化台，不用退回主菜单再点一次。
        // 用主菜单同一个材质（SMITHING_TABLE）和同一个标题，玩家一眼能认出是同一个地方。
        List<String> forgeLore = new ArrayList<>();
        forgeLore.add("&7提升已拥有技能的等级");
        forgeLore.add("&7技能等级存在玩家数据里，界面不会保管你的技能物品");
        if (!plugin.getSkillLevelManager().enabled()) {
            forgeLore.add("&c技能强化系统当前已关闭");
        }
        if (!player.hasPermission(SKILL_FORGE_PERMISSION)) {
            forgeLore.add("&c你没有使用技能强化台的权限");
        }
        inventory.setItem(PANEL_FORGE_SLOT, button(Material.SMITHING_TABLE,
                "&5Skill Forge 技能强化台", forgeLore.toArray(new String[0])));
        player.openInventory(inventory);
    }

    /**
     * 技能预设界面：一排 9 个预设，底行是返回 / 删除模式 / 关闭。
     * <p>
     * 这个界面不承载任何物品（技能等级与绑定都存在玩家数据里），所以没有「界面开着时崩溃
     * 会吞掉物品」的窗口——和技能强化台同一个理由。
     * <p>
     * 点击语义刻意做成「空位保存、已保存的加载」：玩家点一个没保存过的格子就是想存进去，
     * 点一个已经存过的格子就是想用起来。要覆盖保存用右键，避免误触把存好的预设冲掉。
     */
    public void openSkillPreset(Player player) { openSkillPreset(player, false); }

    public void openSkillPreset(Player player, boolean deleteMode) {
        SkillPresetManager presets = plugin.getSkillPresetManager();
        RpgMenuHolder holder = new RpgMenuHolder(RpgMenuType.SKILL_PRESET, 0);
        holder.setDeleteMode(deleteMode);
        Inventory inventory = Bukkit.createInventory(holder, SIZE, Texts.color("Skill Preset 技能预设"));
        ItemStack filler = button(Material.BLACK_STAINED_GLASS_PANE, "&8");
        for (int slot = 0; slot < SIZE; slot++) inventory.setItem(slot, filler);

        for (int index = 0; index < SkillPresetManager.PRESET_COUNT; index++) {
            inventory.setItem(PRESET_BASE + index, presetButton(player, presets, index, deleteMode));
        }

        inventory.setItem(PRESET_BACK_SLOT, button(Material.ARROW, "&e返回技能面板", "&7返回技能面板"));
        inventory.setItem(PRESET_DELETE_MODE_SLOT, button(
                deleteMode ? Material.RED_WOOL : Material.GRAY_WOOL,
                deleteMode ? "&c删除模式：开" : "&7删除模式：关",
                deleteMode ? "&c开着时点击预设 = 删除该预设" : "&7点击开启删除模式",
                "&7删除预设不会影响技能等级与绑定",
                "&8只是把存下来的这套配置删掉"));
        inventory.setItem(PRESET_CLOSE_SLOT, button(Material.BARRIER, "&c关闭", "&7关闭技能预设"));
        player.openInventory(inventory);
    }

    /** 一个预设格子：空位、可用、失效（技能被删）三种样子，外加删除模式的第四种。 */
    private ItemStack presetButton(Player player, SkillPresetManager presets, int index, boolean deleteMode) {
        String label = "预设 " + (index + 1);
        SkillPresetManager.Preset preset = presets.preset(player, index);
        if (preset == null) {
            return button(Material.GRAY_DYE, "&7" + label + " &8（空）",
                    "&a左键：把当前技能配置保存到这里",
                    "&8保存内容：八个键位的技能与等级、键位启用、",
                    "&8释放提示、冷却提示、就绪提示、面板总开关");
        }
        List<String> problems = presets.problems(player, preset);
        boolean broken = !problems.isEmpty();
        List<String> lore = new ArrayList<>();
        lore.add("&8保存时间: &f" + presets.formatSavedAt(preset.savedAt()));
        lore.add("&8面板总开关: " + (preset.panelEnabled() ? "&a开启" : "&c关闭"));
        lore.add("&8&m------------------------");
        for (String trigger : SkillPanelManager.TRIGGERS) {
            SkillPresetManager.PresetSlot slot = preset.slots().get(trigger);
            if (slot == null || !slot.bound()) {
                lore.add("&8" + SkillPanelManager.triggerLabel(trigger) + ": &7（空）");
                continue;
            }
            boolean gone = problems.stream().anyMatch(problem -> problem.startsWith(slot.skillId() + "（"));
            lore.add((slot.triggerEnabled() ? "&a" : "&c") + SkillPanelManager.triggerLabel(trigger) + ": "
                    + (gone ? "&c&m" + slot.skillId() + "&r&c（不可用）" : "&f" + slot.skillId() + " &7Lv." + slot.level()));
            lore.add("&8   提示 释放" + onOff(slot.activationMessages())
                    + " 冷却" + onOff(slot.cooldownMessages())
                    + " 就绪" + onOff(slot.readyMessages()));
        }
        lore.add("&8&m------------------------");
        if (deleteMode) {
            lore.add("&c点击：删除这个预设");
        } else if (broken) {
            lore.add("&c这个预设已失效，无法加载：");
            for (String problem : problems) lore.add("&c  · &f" + problem);
            lore.add("&7把技能重新放回技能背包，");
            lore.add("&e右键：用当前配置覆盖保存");
        } else {
            if (presets.reallocatesOnLoad()) {
                lore.add("&8加载需要技能点: &f" + presets.requiredPoints(preset));
                lore.add("&8加载会重置其他技能的等级，把点数腾给这个预设");
            }
            lore.add("&a左键：加载这个预设");
            lore.add("&e右键：用当前配置覆盖保存");
        }
        return button(broken ? Material.RED_WOOL : deleteMode ? Material.ORANGE_WOOL : Material.LIME_WOOL,
                (broken ? "&c" : "&a") + label + (broken ? " &c（已失效）" : ""),
                lore.toArray(String[]::new));
    }

    private static String onOff(boolean value) {
        return value ? "&a✔" : "&c✘";
    }

    /**
     * Puts the key label in front of a skill slot's name, e.g. {@code [Q 丢弃键] 技能名}, so which key
     * fires a skill is visible without hovering it. Only the title changes; the skill's own name is
     * kept verbatim after the prefix.
     */
    private ItemStack withKeyPrefix(ItemStack item, String label) {
        var meta = item.getItemMeta();
        var current = meta.displayName();
        if (current != null) meta.displayName(Texts.color("&8[" + label + "] ").append(current));
        item.setItemMeta(meta);
        return item;
    }

    public void openSkillBackpack(Player player, String mode, int page) {
        openSkillBackpack(player, mode, page, false);
    }

    public void openSkillBackpack(Player player, String mode, int page, boolean deleteMode) {
        String resolvedMode = mode == null || mode.isBlank() ? BACKPACK_MANAGE : mode;
        boolean equipMode = !resolvedMode.equals(BACKPACK_MANAGE);
        List<String> ids = plugin.getSkillPanelManager().backpackIds(player);
        int maxPage = Math.max(1, (int) Math.ceil(ids.size() / (double) PAGE_SIZE));
        int currentPage = Math.max(0, Math.min(page, maxPage - 1));
        String title = equipMode ? "Skill Backpack 装备到 " + resolvedMode : "Skill Backpack 技能背包";
        RpgMenuHolder holder = new RpgMenuHolder(RpgMenuType.SKILL_BACKPACK, currentPage, resolvedMode);
        holder.setDeleteMode(deleteMode);
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                Texts.color(title + " " + (currentPage + 1) + "/" + maxPage));
        int start = currentPage * PAGE_SIZE;
        int end = Math.min(ids.size(), start + PAGE_SIZE);
        for (int index = start; index < end; index++) {
            String id = ids.get(index);
            SkillLevelManager levels = plugin.getSkillLevelManager();
            List<String> hint = new ArrayList<>();
            hint.add("&8等级: &fLv." + levels.level(player.getUniqueId(), id)
                    + " &7/ &8上限 Lv." + levels.maxLevel(id));
            if (equipMode) {
                hint.add("&a左键装备到 &f" + resolvedMode);
                hint.add("&8一种技能只能占用一个槽位");
            } else if (deleteMode) {
                hint.add("&c点击删除该技能（退还已花费的技能点）");
            } else {
                hint.add("&e技能无法取出；开启删除模式后可删除");
            }
            if (levels.hasRules(id)) hint.add("&d可在技能强化台升级（/rpgitem skillforge）");
            inventory.setItem(index - start, skillItem(id, equipMode ? resolvedMode : null, hint));
        }
        if (ids.isEmpty()) {
            inventory.setItem(22, button(Material.HOPPER, "&7技能背包为空",
                    "&7手持技能书右键即可直接学习入包",
                    "&7相同技能不能重复放入"));
        }
        inventory.setItem(PREVIOUS_SLOT, pageButton(currentPage > 0, false, currentPage + 1, maxPage));
        inventory.setItem(BACK_SLOT, button(Material.BARRIER, "&c返回技能面板", "&7返回"));
        inventory.setItem(NEXT_SLOT, pageButton(currentPage + 1 < maxPage, true, currentPage + 1, maxPage));
        if (!equipMode) {
            inventory.setItem(BACKPACK_DELETE_MODE_SLOT, button(
                    deleteMode ? Material.RED_WOOL : Material.GRAY_WOOL,
                    deleteMode ? "&c删除模式：开" : "&7删除模式：关",
                    deleteMode
                            ? "&c开着时点击技能 = 删除该技能"
                            : "&7点击开启删除模式",
                    "&7删除会退还该技能已花费的技能点",
                    "&7（仅接入 EarthExp 时退还；技能点模式外不退还）",
                    "&7技能不能取出，删除是唯一移除途径"));
        }
        player.openInventory(inventory);
    }

    /**
     * 删除技能确认界面。玩家在技能背包删除模式下点击技能后进入这里，看清楚要删的是谁再决定，
     * 防止「手贱」误删。返回后回到技能背包删除模式（保持删除模式，方便一次删多个）。
     *
     * @param skillId    要删的技能 ID
     * @param returnPage 技能背包当前页（确认/取消后回到同页）
     */
    public void openSkillDeleteConfirm(Player player, String skillId, int returnPage) {
        SkillLevelManager levels = plugin.getSkillLevelManager();
        int level = levels.level(player.getUniqueId(), skillId);
        RpgMenuHolder holder = new RpgMenuHolder(RpgMenuType.SKILL_DELETE_CONFIRM, returnPage, skillId);
        Inventory inventory = Bukkit.createInventory(holder, SIZE, Texts.color("确认删除技能"));
        ItemStack filler = button(Material.BLACK_STAINED_GLASS_PANE, "&8");
        for (int slot = 0; slot < SIZE; slot++) inventory.setItem(slot, filler);

        List<String> lore = new ArrayList<>();
        lore.add("&8技能 ID: &f" + skillId);
        lore.add("&8当前等级: &fLv." + level + (levels.hasRules(skillId) ? " &7/ 上限 Lv." + levels.maxLevel(skillId) : ""));
        if (levels.usesSkillPoints()) {
            int expectedRefund = SkillLevelManager.cumulativePoints(level);
            lore.add("&7预计退还已花费的技能点 &f×" + expectedRefund);
            lore.add("&7实际退款以 EarthExp 记录为准");
        } else {
            lore.add("&7未接入 EarthExp，删除不退还宝石");
        }
        lore.add("&c确认后将无法恢复");
        inventory.setItem(DELETE_CONFIRM_SKILL_SLOT, skillItem(skillId, null, lore));

        inventory.setItem(DELETE_CONFIRM_YES_SLOT, button(Material.RED_WOOL,
                "&c确认删除",
                "&7删除该技能（技能等级回到 Lv.1）",
                "&7退还已花费的技能点",
                "&c此操作不可撤销"));
        inventory.setItem(DELETE_CONFIRM_NO_SLOT, button(Material.LIME_WOOL,
                "&a取消",
                "&7返回技能背包，不删除"));
        inventory.setItem(DELETE_CONFIRM_CLOSE_SLOT, button(Material.BARRIER,
                "&c关闭", "&7关闭并取消删除"));
        player.openInventory(inventory);
    }

    public void openSkillItems(Player player, String trigger) {
        openSkillItems(player, trigger, 0);
    }

    public void openSkillItems(Player player, String trigger, int page) {
        List<String> ids = plugin.getSkillPanelManager().registeredSkillIds();
        int maxPage = Math.max(1, (int) Math.ceil(ids.size() / (double) PAGE_SIZE));
        int currentPage = Math.max(0, Math.min(page, maxPage - 1));
        Inventory inventory = Bukkit.createInventory(new RpgMenuHolder(RpgMenuType.SKILL_ITEMS, currentPage, trigger), SIZE,
                Texts.color("Skill Items / " + trigger + " " + (currentPage + 1) + "/" + maxPage));
        int start = currentPage * PAGE_SIZE;
        int end = Math.min(ids.size(), start + PAGE_SIZE);
        for (int index = start; index < end; index++) {
            RpgItem item = plugin.getSkillPanelManager().registeredItem(ids.get(index));
            inventory.setItem(index - start, skillDisplayItem(ids.get(index)));
        }
        inventory.setItem(PREVIOUS_SLOT, pageButton(currentPage > 0, false, currentPage + 1, maxPage));
        inventory.setItem(BACK_SLOT, button(Material.BARRIER, "&c返回技能面板", "&7返回"));
        inventory.setItem(NEXT_SLOT, pageButton(currentPage + 1 < maxPage, true, currentPage + 1, maxPage));
        player.openInventory(inventory);
    }

    public void openSkillLibrary(Player player, int page) {
        List<String> ids = plugin.getSkillPanelManager().registeredSkillIds();
        int maxPage = Math.max(1, (int) Math.ceil(ids.size() / (double) PAGE_SIZE));
        int currentPage = Math.max(0, Math.min(page, maxPage - 1));
        Inventory inventory = Bukkit.createInventory(new RpgMenuHolder(RpgMenuType.SKILL_LIBRARY, currentPage), SIZE,
                Texts.color("Registered Skill Items / " + (currentPage + 1) + "/" + maxPage));
        int start = currentPage * PAGE_SIZE;
        int end = Math.min(ids.size(), start + PAGE_SIZE);
        for (int index = start; index < end; index++) {
            RpgItem item = plugin.getSkillPanelManager().registeredItem(ids.get(index));
            inventory.setItem(index - start, skillDisplayItem(ids.get(index)));
        }
        inventory.setItem(PREVIOUS_SLOT, pageButton(currentPage > 0, false, currentPage + 1, maxPage));
        inventory.setItem(BACK_SLOT, button(Material.BARRIER, "&cBack 返回", "&7Back to main GUI / 返回主菜单"));
        inventory.setItem(NEXT_SLOT, pageButton(currentPage + 1 < maxPage, true, currentPage + 1, maxPage));
        player.openInventory(inventory);
    }

    /**
     * 技能强化台（列表页）。
     * <p>
     * 和强化台/宝石台一样，技能物品<b>不会</b>被放进这个界面：等级存在玩家数据里，
     * 界面只是把它渲染出来。所以这里没有任何「界面开着时崩溃会吞掉物品」的窗口。
     * <p>
     * 只列出玩家已拥有的技能（技能背包里有的）——等级属于「玩家 + 技能 ID」，
     * 没拥有的技能既没有等级也不该被强化。
     */
    public void openSkillForge(Player player) { openSkillForge(player, 0, false); }

    public void openSkillForge(Player player, int page) { openSkillForge(player, page, false); }

    /**
     * 从技能面板底行进强化台：唯一的区别是「返回」回技能面板而不是主菜单，
     * 与技能背包 / 技能预设的行为保持一致。
     */
    public void openSkillForgeFromPanel(Player player) { openSkillForge(player, 0, true); }

    public void openSkillForge(Player player, int page, boolean fromPanel) {
        SkillLevelManager levels = plugin.getSkillLevelManager();
        List<String> owned = plugin.getSkillPanelManager().backpackIds(player);
        int maxPage = Math.max(1, (int) Math.ceil(owned.size() / (double) PAGE_SIZE));
        int currentPage = Math.max(0, Math.min(page, maxPage - 1));
        RpgMenuHolder holder = new RpgMenuHolder(RpgMenuType.SKILL_FORGE, currentPage);
        holder.setForgeFromPanel(fromPanel);
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                Texts.color("Skill Forge 技能强化台 " + (currentPage + 1) + "/" + maxPage));
        int start = currentPage * PAGE_SIZE;
        int end = Math.min(owned.size(), start + PAGE_SIZE);
        for (int index = start; index < end; index++) {
            inventory.setItem(FORGE_LIST_BASE + index - start, forgeEntry(player, owned.get(index)));
        }
        if (owned.isEmpty()) {
            inventory.setItem(22, button(Material.BARRIER, "&c你还没有任何技能",
                    "&7技能等级属于「玩家 + 技能」，所以要先拥有技能才能强化",
                    "&7先手持技能书右键把技能放进技能背包"));
        }
        if (levels != null && !levels.enabled()) {
            inventory.setItem(4, button(Material.RED_WOOL, "&c技能强化系统已关闭",
                    "&7已升过的等级仍然保留并生效", "&7只是不能再升级"));
        }
        // 一键重置：把所有已升级技能回到 Lv.1，退还已花费的战斗天赋点。
        int resetCount = 0;
        int resetPoints = 0;
        for (String id : owned) {
            int level = levels.level(player.getUniqueId(), id);
            if (level <= SkillLevelManager.MIN_LEVEL) continue;
            resetCount++;
            resetPoints += SkillLevelManager.cumulativePoints(level);
        }
        if (levels.usesSkillPoints()) {
            inventory.setItem(FORGE_RESET_SLOT, button(
                    resetCount == 0 ? Material.GRAY_WOOL : Material.REDSTONE_BLOCK,
                    resetCount == 0 ? "&7一键重置技能点（无可重置）" : "&c一键重置技能点",
                    "&7把 " + resetCount + " 个已升级技能全部回到 Lv.1",
                    "&7退还已花费的战斗天赋点 &f×" + resetPoints,
                    "&c点击立即生效"));
        } else {
            inventory.setItem(FORGE_RESET_SLOT, button(Material.BARRIER, "&8一键重置技能点（未接入 EarthExp）",
                    "&7装了 EarthExp 就会自动接入，不用配 config",
                    "&7仍显示未接入请看启动日志的那一行状态",
                    "&7或执行 &f/rpgitem reload&7 查看"));
        }
        inventory.setItem(FORGE_PREVIOUS_SLOT, pageButton(currentPage > 0, false, currentPage + 1, maxPage));
        inventory.setItem(FORGE_BACK_SLOT, button(Material.BARRIER, "&cBack 返回", "&7Back to main GUI / 返回主菜单"));
        inventory.setItem(FORGE_NEXT_SLOT, pageButton(currentPage + 1 < maxPage, true, currentPage + 1, maxPage));
        player.openInventory(inventory);
    }

    /** 列表里的一个技能：等级、这一级会改什么、消耗、够不够。 */
    private ItemStack forgeEntry(Player player, String id) {
        SkillLevelManager levels = plugin.getSkillLevelManager();
        int level = levels.level(player.getUniqueId(), id);
        int max = levels.maxLevel(id);
        List<String> lore = new ArrayList<>();
        lore.add("&8当前等级: &fLv." + level + " &7/ &8上限 Lv." + max);
        if (!levels.hasRules(id)) {
            lore.add("&c这个技能没有配置成长规则（leveling 段）");
            lore.add("&7在技能的 yml 里加上 leveling 段即可强化");
        } else if (level >= max) {
            lore.add("&6已达到等级上限");
        } else {
            lore.add("&8&m------------------------");
            lore.add("&8升级预览 &7Lv." + level + " → &fLv." + (level + 1));
            List<String> preview = levels.previewLines(id, level, level + 1);
            if (preview.isEmpty()) lore.add("&7（这一级没有数值变化）");
            else lore.addAll(preview);
            if (levels.usesSkillPoints()) {
                int needPoints = levels.pointsCost(level + 1);
                boolean affordable = levels.hasSkillPoints(player, needPoints);
                lore.add("&8消耗: " + (affordable ? "&a" : "&c") + levels.describePointsCost(needPoints)
                        + "&7（当前 " + levels.availableSkillPoints(player) + "）");
                lore.add(affordable ? "&a左键：强化到 Lv." + (level + 1) : "&c战斗天赋点不足");
            } else {
                Map<String, Integer> need = levels.costFor(id, level + 1);
                boolean affordable = levels.hasGems(player, need);
                lore.add("&8消耗: " + (need.isEmpty() ? "&a无" : (affordable ? "&a" : "&c") + levels.describeCost(need)));
                lore.add(affordable ? "&a左键：强化到 Lv." + (level + 1) : "&c材料不足");
            }
        }
        lore.add("&e右键：查看完整成长表");
        return skillItem(id, null, lore);
    }

    /** 技能强化台（详情页）：把每一级改什么列全，方便管理员和玩家核对成长曲线。 */
    public void openSkillForgeDetail(Player player, String skillId, int returnPage) {
        openSkillForgeDetail(player, skillId, returnPage, false);
    }

    public void openSkillForgeDetail(Player player, String skillId, int returnPage, boolean fromPanel) {
        SkillLevelManager levels = plugin.getSkillLevelManager();
        int level = levels.level(player.getUniqueId(), skillId);
        int max = levels.maxLevel(skillId);
        RpgMenuHolder holder = new RpgMenuHolder(RpgMenuType.SKILL_FORGE_DETAIL, returnPage, skillId);
        holder.setForgeFromPanel(fromPanel);
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                Texts.color("Skill Forge 技能强化台"));

        List<String> lore = new ArrayList<>();
        lore.add("&8当前等级: &fLv." + level + " &7/ &8上限 Lv." + max);
        lore.add("&8&m------------------------");
        if (!levels.hasRules(skillId)) {
            lore.add("&c这个技能没有配置成长规则（leveling 段）");
            lore.add("&7在技能的 yml 里加上 leveling 段即可强化");
        } else {
            lore.add("&8完整成长表 &7（Lv.1 = 技能文件原本的数值）");
            for (int target = 2; target <= max; target++) {
                List<String> lines = levels.previewLines(skillId, target - 1, target);
                String head = (target <= level ? "&a✔ &fLv." + target : "&7Lv." + target) + "&8: ";
                lore.add(head + (lines.isEmpty() ? "&8(无变化)" : String.join("&8 | ", lines)));
            }
        }
        inventory.setItem(FORGE_DETAIL_SKILL_SLOT, skillItem(skillId, null, lore));

        boolean canUpgrade = levels.enabled() && levels.hasRules(skillId) && level < max;
        List<String> buttonLore = new ArrayList<>();
        if (!levels.enabled()) {
            buttonLore.add("&c技能强化系统当前已关闭");
        } else if (!levels.hasRules(skillId)) {
            buttonLore.add("&c这个技能没有配置成长规则");
        } else if (level >= max) {
            buttonLore.add("&6已经达到上限 Lv." + max);
        } else if (levels.usesSkillPoints()) {
            int needPoints = levels.pointsCost(level + 1);
            boolean affordable = levels.hasSkillPoints(player, needPoints);
            buttonLore.add("&8Lv." + level + " → &fLv." + (level + 1));
            buttonLore.addAll(levels.previewLines(skillId, level, level + 1));
            buttonLore.add("&8消耗: " + (affordable ? "&a" : "&c") + levels.describePointsCost(needPoints)
                    + "&7（当前 " + levels.availableSkillPoints(player) + "）");
            inventory.setItem(FORGE_DETAIL_BUTTON_SLOT, button(
                    canUpgrade && affordable ? Material.LIME_WOOL : Material.RED_WOOL,
                    canUpgrade && affordable ? "&a强化 &7Lv." + level + " → &aLv." + (level + 1) : "&c无法强化",
                    buttonLore.toArray(new String[0])));
        } else {
            Map<String, Integer> need = canUpgrade ? levels.costFor(skillId, level + 1) : Map.of();
            boolean affordable = !canUpgrade || levels.hasGems(player, need);
            buttonLore.add("&8Lv." + level + " → &fLv." + (level + 1));
            buttonLore.addAll(levels.previewLines(skillId, level, level + 1));
            buttonLore.add("&8消耗: " + (need.isEmpty() ? "&a无" : (affordable ? "&a" : "&c") + levels.describeCost(need)));
            inventory.setItem(FORGE_DETAIL_BUTTON_SLOT, button(
                    canUpgrade && affordable ? Material.LIME_WOOL : Material.RED_WOOL,
                    canUpgrade && affordable ? "&a强化 &7Lv." + level + " → &aLv." + (level + 1) : "&c无法强化",
                    buttonLore.toArray(new String[0])));
        }
        inventory.setItem(FORGE_DETAIL_BACK_SLOT, button(Material.ARROW, "&e返回技能列表", "&7Back to skill list"));
        inventory.setItem(FORGE_DETAIL_CLOSE_SLOT, button(Material.BARRIER, "&c关闭", "&7Close"));
        player.openInventory(inventory);
    }

    /**
     * Enhancement station.
     * <p>
     * The item being enhanced is <b>never</b> moved into this screen: it stays in the player's main
     * hand and this only renders a copy. That is the whole reason the station cannot lose an item —
     * there is no window between "the GUI is open" and "the item is safe" during which a crash, a
     * kick, or a reload could swallow it. The material is the only thing a player hands over, and it
     * is stored as a count through {@link EnhanceStationManager} rather than as a loose stack in a
     * slot, for the same reason.
     */
    public void openEnhance(Player player) {
        EnhancementManager enhancement = plugin.getEnhancementManager();
        EnhanceStationManager station = plugin.getEnhanceStationManager();
        Inventory inventory = Bukkit.createInventory(new RpgMenuHolder(RpgMenuType.ENHANCE, 0), SIZE,
                Texts.color("Enhance Station 强化台"));
        ItemStack filler = button(Material.BLACK_STAINED_GLASS_PANE, "&8");
        for (int slot = 0; slot < SIZE; slot++) inventory.setItem(slot, filler);
        inventory.setItem(ENHANCE_CLOSE_SLOT, button(Material.BARRIER, "&c关闭",
                "&7关闭强化台",
                "&8这个界面没有「返回主菜单」——主菜单是管理员界面"));

        // The system being switched off is not a per-item condition, so it takes over the screen
        // instead of leaving a station whose button silently does nothing.
        if (enhancement == null || !enhancement.enabled()) {
            inventory.setItem(ENHANCE_BUTTON_SLOT, button(Material.RED_WOOL, "&c强化系统未开启",
                    "&7把 enhancement.yml 的 &fenabled &7设为 &ftrue&7，",
                    "&7然后执行 &f/rpgitem reload &7即可。"));
            player.openInventory(inventory);
            return;
        }

        ItemStack held = player.getInventory().getItemInMainHand();
        boolean hasHeld = held != null && !held.getType().isAir();
        RpgItem registered = hasHeld ? plugin.getRpgItemManager().findItem(held) : null;

        if (!hasHeld) {
            inventory.setItem(ENHANCE_EQUIPMENT_SLOT, button(Material.BARRIER, "&c主手是空的",
                    "&7把要强化的装备拿在主手，再打开强化台",
                    "&8装备始终留在你手上，强化台不会保管它"));
        } else if (registered == null) {
            inventory.setItem(ENHANCE_EQUIPMENT_SLOT, button(Material.BARRIER, "&c这不是 RPG 物品",
                    "&7只有已注册的 RPG 物品可以强化"));
        } else {
            inventory.setItem(ENHANCE_EQUIPMENT_SLOT, equipmentDisplay(held));
        }

        int current = registered == null ? 0 : plugin.getRpgItemManager().enhanceLevel(held);
        int max = enhancement.maxLevel();
        int next = current + 1;
        EnhancementManager.Level definition = enhancement.level(next);

        inventory.setItem(ENHANCE_LEVEL_SLOT, button(Material.EXPERIENCE_BOTTLE,
                "&6强化等级 &f+" + current + " &7/ &6+" + max,
                "&7当前：&6+" + current,
                "&7上限：&6+" + max,
                current >= max ? "&a已达到强化上限" : "&7目标：&6+" + next));

        if (registered == null) {
            inventory.setItem(ENHANCE_RATE_SLOT, button(Material.GRAY_DYE, "&7成功率 &8--",
                    "&7先手持一件 RPG 装备"));
        } else if (definition == null) {
            inventory.setItem(ENHANCE_RATE_SLOT, button(Material.REDSTONE_BLOCK, "&c下一级未定义",
                    "&7enhancement.yml 的 &flevels &7里没有 &f+" + next + "&7，",
                    "&7所以这一级无法强化。"));
        } else {
            long rate = Math.round(definition.successRate() * 100);
            inventory.setItem(ENHANCE_RATE_SLOT, button(
                    rate >= 100 ? Material.LIME_DYE : rate >= 50 ? Material.YELLOW_DYE : Material.RED_DYE,
                    "&e成功率 &f" + rate + "%",
                    "&7强化到 &6+" + next + " &7的成功概率",
                    "&8失败也要消耗材料"));
        }

        inventory.setItem(ENHANCE_PENALTY_SLOT, definition == null
                ? button(Material.GRAY_DYE, "&7失败惩罚 &8--", "&7先手持一件 RPG 装备")
                : button(penaltyIcon(definition.penalty()), "&c失败惩罚",
                        "&7" + EnhancementService.penaltyName(definition.penalty()),
                        "&8安全区不会掉级，高等级失败可能破坏物品"));

        inventory.setItem(ENHANCE_PREVIEW_SLOT, previewDisplay(held, registered, next, definition));

        if (!enhancement.consumesStone()) {
            inventory.setItem(ENHANCE_MATERIAL_SLOT, button(Material.GLASS_PANE, "&7本配置不需要强化石",
                    "&7enhancement.yml 的 &fstone.item-id &7是空的"));
        } else {
            inventory.setItem(ENHANCE_MATERIAL_SLOT,
                    materialDisplay(player, enhancement, station, registered != null));
        }

        boolean hasLevel = registered != null && definition != null && current < max;
        boolean hasMaterial = !enhancement.consumesStone()
                || station == null
                || station.available(player.getUniqueId(), enhancement.stoneItemId()) >= enhancement.stoneAmount();
        inventory.setItem(ENHANCE_BUTTON_SLOT, hasLevel && hasMaterial
                ? button(Material.ANVIL, "&a&l点击强化",
                        "&7尝试把 &f" + registered.displayName() + " &7强化到 &6+" + next,
                        "&8成功与失败都会消耗材料")
                : button(Material.BARRIER, "&c无法强化", refusalReason(hasHeld, registered, definition, current, max, hasMaterial)));
        player.openInventory(inventory);
    }

    /** The reason the button is disabled, so a dead button always explains itself. */
    private String refusalReason(boolean hasHeld, RpgItem registered, EnhancementManager.Level definition,
                                 int current, int max, boolean hasMaterial) {
        if (!hasHeld) return "&7主手是空的，请手持装备";
        if (registered == null) return "&7主手物品不是已注册的 RPG 物品";
        if (current >= max) return "&7已经满强化了";
        if (definition == null) return "&7配置里没有定义下一级";
        if (!hasMaterial) return "&7强化石不够，请先存入材料";
        return "&7当前无法强化";
    }

    /** A copy of the held item with station hints appended; the real item is never touched. */
    private ItemStack equipmentDisplay(ItemStack held) {
        ItemStack display = held.clone();
        var meta = display.getItemMeta();
        if (meta == null) return display;
        List<Component> lore = new ArrayList<>();
        if (meta.lore() != null) lore.addAll(meta.lore());
        lore.add(Texts.color("&8&m------------------------"));
        lore.add(Texts.color("&7强化台只读取主手物品"));
        lore.add(Texts.color("&8装备不会离开你的手，关掉界面也不会丢"));
        meta.lore(lore);
        display.setItemMeta(meta);
        return display;
    }

    /**
     * The per-attribute numbers this level would actually grant, for the item in hand.
     * <p>
     * The percentage alone does not tell a player whether <em>their</em> item gains anything, because
     * only whitelisted attributes are scaled. Printing the real numbers here is the difference
     * between "the button says +9%" and "this sword gains 26 Attack".
     */
    private ItemStack previewDisplay(ItemStack held, RpgItem registered, int next,
                                     EnhancementManager.Level definition) {
        if (registered == null || definition == null) {
            return button(Material.PAPER, "&7实际加成 &8--", "&7先手持一件 RPG 装备");
        }
        Map<String, Double> bonus = plugin.getRpgItemManager().enhancementBonusMap(held, registered, next);
        List<String> lore = new ArrayList<>();
        if (bonus.isEmpty()) {
            lore.add("&c这件物品没有可强化的属性");
            lore.add("&8只有白名单里的伤害类属性会被强化");
        } else {
            lore.add("&7强化到 &6+" + next + " &7后，这件装备实际获得：");
            for (Map.Entry<String, Double> entry : new TreeMap<>(bonus).entrySet()) {
                lore.add("&9+" + trimNumber(entry.getValue()) + " &f" + entry.getKey());
            }
        }
        return button(Material.PAPER, "&b实际加成预览 &7(+" + next + ")", lore.toArray(String[]::new));
    }

    /** The material slot: how much is stored, how much is needed, and how to move it in or out. */
    private ItemStack materialDisplay(Player player, EnhancementManager enhancement,
                                      EnhanceStationManager station, boolean hasItem) {
        String stoneId = enhancement.stoneItemId();
        int need = enhancement.stoneAmount();
        int deposited = station == null ? 0 : station.available(player.getUniqueId(), stoneId);
        int inBag = plugin.getEnhancementService().countInInventory(player, stoneId);
        boolean enough = deposited >= need;
        List<String> lore = new ArrayList<>();
        lore.add("&7材料：&f" + stoneId);
        lore.add("&7每次消耗：&f" + need);
        lore.add("&7已存入：&f" + deposited + (enough ? " &a(足够)" : " &c(不足)"));
        lore.add("&7背包中：&f" + inBag);
        lore.add("&8--------------------");
        lore.add("&a光标拿着强化石左键：全部存入");
        lore.add("&a光标拿着强化石右键：存入 1 个");
        lore.add("&e空手左键：取回全部已存入的强化石");
        return button(hasItem && enough ? Material.LIME_STAINED_GLASS_PANE : Material.ORANGE_STAINED_GLASS_PANE,
                "&6强化石槽位", lore.toArray(String[]::new));
    }

    private Material penaltyIcon(EnhancementManager.Penalty penalty) {
        return switch (penalty) {
            case NONE -> Material.LIME_DYE;
            case DOWNGRADE -> Material.ORANGE_DYE;
            case DESTROY -> Material.RED_DYE;
        };
    }

    /** Trims a trailing ".0" so a bonus reads "200" rather than "200.0". */
    private static String trimNumber(double value) {
        if (!Double.isFinite(value)) return "0";
        if (value == Math.rint(value)) return Long.toString((long) value);
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /**
     * The gem station: sockets gems into the item in hand without ever taking it out of that hand.
     * <p>
     * The same safety decision as the enhancement station — the real item stays in the main hand and
     * only a copy is drawn — so the item cannot be lost to a crash, a kick or a reload while this
     * screen is open. Gems are taken from the cursor and never from a slot, so no gem is ever held by
     * this inventory either; there is nothing here worth losing.
     */
    public void openGems(Player player) {
        GemstoneManager gems = plugin.getGemstoneManager();
        Inventory inventory = Bukkit.createInventory(new RpgMenuHolder(RpgMenuType.GEMS, 0), SIZE,
                Texts.color("Gem Station 宝石台"));
        ItemStack filler = button(Material.BLACK_STAINED_GLASS_PANE, "&8");
        for (int slot = 0; slot < SIZE; slot++) inventory.setItem(slot, filler);
        inventory.setItem(GEM_CLOSE_SLOT, button(Material.BARRIER, "&c关闭",
                "&7关闭宝石台",
                "&8这个界面没有「返回主菜单」——主菜单是管理员界面"));

        // Switched off is not a per-item condition, so it takes over the screen rather than leaving a
        // station whose every slot silently does nothing.
        if (gems == null || !plugin.isFeatureEnabled("gemstones")) {
            inventory.setItem(GEM_INFO_SLOT, button(Material.RED_WOOL, "&c宝石系统未开启",
                    "&7把 config.yml 的 &ffeatures.gemstones &7设为 &ftrue&7，",
                    "&7然后执行 &f/rpgitem reload &7即可。"));
            player.openInventory(inventory);
            return;
        }

        ItemStack held = player.getInventory().getItemInMainHand();
        boolean hasHeld = held != null && !held.getType().isAir();
        RpgItem registered = hasHeld ? plugin.getRpgItemManager().findItem(held) : null;
        int capacity = registered == null ? 0 : registered.sockets();
        List<String> sockets = registered == null ? List.of() : plugin.getRpgItemManager().socketedGems(held);

        if (!hasHeld) {
            inventory.setItem(GEM_EQUIPMENT_SLOT, button(Material.BARRIER, "&c主手是空的",
                    "&7把要镶嵌的装备拿在主手，再打开宝石台",
                    "&8装备始终留在你手上，宝石台不会保管它"));
        } else if (registered == null) {
            inventory.setItem(GEM_EQUIPMENT_SLOT, button(Material.BARRIER, "&c这不是 RPG 物品",
                    "&7只有已注册的 RPG 物品可以镶嵌宝石"));
        } else {
            inventory.setItem(GEM_EQUIPMENT_SLOT, equipmentDisplay(held));
        }

        int filled = 0;
        for (String gemId : sockets) {
            if (gemId != null && !gemId.isBlank()) filled++;
        }
        inventory.setItem(GEM_INFO_SLOT, button(capacity > 0 ? Material.EMERALD : Material.GRAY_DYE,
                "&6宝石槽位 &f" + filled + " &7/ &6" + capacity,
                capacity > 0 ? "&7已镶嵌：&f" + filled + " &7个" : "&7这件装备没有宝石槽位",
                "&8槽位数由物品文件的 &fsockets: &8决定"));

        inventory.setItem(GEM_HELP_SLOT, button(Material.BOOK, "&e怎么镶嵌",
                "&a光标拿着宝石，左键点空槽位：镶嵌",
                "&e右键点已镶嵌的槽位：卸下回背包",
                "&8宝石从光标消耗，不经过任何槽位",
                "&8装备始终留在主手，不会丢"));

        for (int index = 0; index < GEM_SOCKET_MAX; index++) {
            int slot = GEM_SOCKET_FIRST_SLOT + index;
            if (registered == null || index >= capacity) {
                inventory.setItem(slot, button(Material.GRAY_STAINED_GLASS_PANE, "&8[   ]",
                        "&7这件装备没有第 &f" + (index + 1) + " &7个槽位"));
                continue;
            }
            String gemId = index < sockets.size() ? sockets.get(index) : "";
            if (gemId == null || gemId.isBlank()) {
                inventory.setItem(slot, button(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&f[   ]",
                        "&7第 &f" + (index + 1) + " &7个槽位：空",
                        "&a光标拿着宝石左键：镶嵌"));
            } else {
                inventory.setItem(slot, socketDisplay(gemId, index, gems));
            }
        }
        player.openInventory(inventory);
    }

    /**
     * One filled socket, drawn as the gem's own item so its real icon and name are what is shown.
     * <p>
     * A gem id that is no longer in {@code gemstones.yml} is still drawn, as an explicit unknown: it
     * is still stored in that socket, and drawing the slot as empty would invite the player to
     * overwrite a gem that is physically there.
     */
    private ItemStack socketDisplay(String gemId, int index, GemstoneManager gems) {
        GemstoneManager.Gemstone gem = gems.get(gemId);
        RpgItem registeredGem = plugin.getRpgItemManager().findById(gemId);
        ItemStack display = registeredGem == null
                ? button(Material.PAPER, gem == null ? "&7?" : gem.coloredIcon())
                : plugin.getRpgItemManager().createItem(registeredGem);
        var meta = display.getItemMeta();
        if (meta == null) return display;
        List<Component> lore = new ArrayList<>();
        if (meta.lore() != null) lore.addAll(meta.lore());
        lore.add(Texts.color("&8&m------------------------"));
        lore.add(Texts.color("&7第 &f" + (index + 1) + " &7个槽位"));
        if (gem == null) {
            lore.add(Texts.color("&c未知宝石 &f" + gemId));
            lore.add(Texts.color("&7gemstones.yml 里已经没有这个 id，"));
            lore.add(Texts.color("&7它目前不提供任何属性"));
        } else {
            lore.add(Texts.color("&7镶嵌后提供："));
            for (Map.Entry<String, Double> entry : new TreeMap<>(gem.stats()).entrySet()) {
                lore.add(Texts.color("&a+" + trimNumber(entry.getValue()) + " &f" + entry.getKey()));
            }
        }
        lore.add(Texts.color("&e右键：卸下这颗宝石"));
        meta.lore(lore);
        display.setItemMeta(meta);
        return display;
    }

    private ItemStack skillDisplayItem(String id) {
        return skillItem(id, null, null);
    }

    /**
     * Skill item rendering. The skill description lines are applied here only, so normal
     * RPG items created elsewhere keep their original lore.
     */
    public ItemStack skillItem(String id, String trigger, List<String> extraLore) {
        RpgItem item = plugin.getSkillPanelManager().registeredItem(id);
        Material material = plugin.getSkillPanelManager().registeredMaterial(id);
        ItemStack display = item == null ? new ItemStack(material) : plugin.getRpgItemManager().createItem(item);
        display.setType(material);
        var meta = display.getItemMeta();
        List<String> lore = new ArrayList<>(plugin.getSkillPanelManager().skillLore(id, trigger));
        if (extraLore != null && !extraLore.isEmpty()) {
            lore.add("&8&m------------------------");
            lore.addAll(extraLore);
        }
        meta.lore(lore.stream().map(Texts::color).toList());
        if (item == null) meta.displayName(Texts.color("&d" + id));
        display.setItemMeta(meta);
        return display;
    }

    private ItemStack pageButton(boolean enabled, boolean next, int currentPage, int maxPage) {
        if (!enabled) return button(Material.BEDROCK, next ? "&7Next 下一页" : "&7Previous 上一页", "&8No more pages / 没有更多页 " + currentPage + "/" + maxPage);
        Material material = next ? Material.GREEN_WOOL : Material.BLUE_WOOL;
        String name = next ? "&aNext 下一页" : "&9Previous 上一页";
        return button(material, name + " &f" + currentPage + "/" + maxPage, "&7Click to turn page / 点击翻页");
    }

    private ItemStack featureButton(String path, String name) {
        boolean enabled = plugin.getConfig().getBoolean(path, true);
        return button(enabled ? Material.LIME_WOOL : Material.RED_WOOL, name, enabled ? "&a已开启 Enabled，点击关闭" : "&c已关闭 Disabled，点击开启");
    }

    private ItemStack button(Material material, String name, String... lore) {
        ItemStack itemStack = new ItemStack(material);
        var meta = itemStack.getItemMeta();
        meta.displayName(Texts.color(name));
        meta.lore(java.util.Arrays.stream(lore).map(Texts::color).toList());
        itemStack.setItemMeta(meta);
        return itemStack;
    }
}
