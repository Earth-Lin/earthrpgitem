package EarthRPGItem.commands;

import EarthRPGItem.EarthRPGItem;
import EarthRPGItem.collection.CollectionDefinition;
import EarthRPGItem.collection.CollectionKind;
import EarthRPGItem.collection.CollectionStats;
import EarthRPGItem.gui.RpgMenu;
import EarthRPGItem.items.EnhancementManager;
import EarthRPGItem.items.EnhancementService;
import EarthRPGItem.items.RpgItem;
import EarthRPGItem.items.RandomAttributeRange;
import EarthRPGItem.items.RpgItemManager;
import EarthRPGItem.items.RpgStats;
import EarthRPGItem.skills.GatheringSkillManager;
import EarthRPGItem.skills.SkillBackpackManager;
import EarthRPGItem.skills.SkillPanelManager;
import EarthRPGItem.util.Texts;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.ProxiedCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class EarthRPGItemCommand implements CommandExecutor, TabCompleter {

    private final EarthRPGItem plugin;

    public EarthRPGItemCommand(EarthRPGItem plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) return reload(sender);
        if (args.length > 0 && args[0].equalsIgnoreCase("my")) return showMyStats(sender);
        if (args.length > 0 && args[0].equalsIgnoreCase("dummy")) return dummy(sender, args);
        if (args.length > 0 && args[0].equalsIgnoreCase("give")) return give(sender, args);
        if (args.length > 0 && (args[0].equalsIgnoreCase("puton") || args[0].equalsIgnoreCase("poton"))) return puton(sender, args);
        if (args.length > 0 && args[0].equalsIgnoreCase("fix")) return fix(sender, false);
        if (args.length > 0 && args[0].equalsIgnoreCase("fixs")) return fix(sender, true);
        if (args.length > 0 && args[0].equalsIgnoreCase("collection")) return collection(sender, args);
        if (args.length > 0 && args[0].equalsIgnoreCase("accessorygui")) return accessoryGui(sender);
        if (args.length > 0 && args[0].equalsIgnoreCase("accessory")) return accessory(sender, args);
        if (args.length > 0 && args[0].equalsIgnoreCase("skills")) return skills(sender);
        if (args.length > 0 && args[0].equalsIgnoreCase("skillcheck")) return skillCheck(sender);
        if (args.length > 0 && args[0].equalsIgnoreCase("skilldamage")) return skillDamage(sender);
        if (args.length > 0 && args[0].equalsIgnoreCase("magicdebug")) return magicDebug(sender);
        if (args.length > 0 && args[0].equalsIgnoreCase("memdebug")) return memDebug(sender);
        if (args.length > 0 && args[0].equalsIgnoreCase("enhance")) return enhance(sender, args);
        if (args.length > 0 && args[0].equalsIgnoreCase("gems")) return gems(sender);
        if (args.length > 0 && args[0].equalsIgnoreCase("skillforge")) return skillForge(sender);
        if (args.length > 0 && args[0].equalsIgnoreCase("gathering")) return gathering(sender, args);
        if (args.length == 0 || args[0].equalsIgnoreCase("gui")) return openGui(sender);
        sender.sendMessage(Texts.color("&eUsage: /rpgitem my | gui | gems | accessorygui | collection refresh | dummy | give | puton | reload"));
        return true;
    }

    private boolean collection(CommandSender sender, String[] args) {
        if (!sender.hasPermission("earthrpgitem.admin")) {
            sender.sendMessage(Texts.color("&cNo permission."));
            return true;
        }
        if (args.length < 2 || !args[1].equalsIgnoreCase("refresh")) {
            sender.sendMessage(Texts.color("&eUsage: /rpgitem collection refresh [world|all]"));
            return true;
        }
        if (args.length > 2 && args[2].equalsIgnoreCase("all")) {
            int count = plugin.getCollectionManager().refreshAllWorlds();
            sender.sendMessage(Texts.color("&a已刷新所有世界的采集点: &f" + count));
            return true;
        }
        World world;
        if (args.length > 2) {
            world = Bukkit.getWorld(args[2]);
            if (world == null) {
                sender.sendMessage(Texts.color("&c世界不存在: " + args[2]));
                return true;
            }
        } else if (sender instanceof Player player) {
            world = player.getWorld();
        } else {
            sender.sendMessage(Texts.color("&c控制台请指定世界名，或使用 all。"));
            return true;
        }
        int count = plugin.getCollectionManager().refreshWorld(world);
        sender.sendMessage(Texts.color("&a已刷新世界 &f" + world.getName() + " &a的采集点: &f" + count));
        return true;
    }

    // ---- 强化 ---------------------------------------------------------------------------------

    /**
     * {@code /rpgitem enhance} 把手中的物品强化一级；{@code enhance set <等级>} 直接设定等级（测试用）；
     * {@code enhance info} 查看当前等级与下一级的成功率、惩罚和加成。
     * <p>
     * 结算全部在服务端完成：概率用 {@link ThreadLocalRandom} 抽取，材料在抽取之前扣除。
     * 失败惩罚为 DESTROY 时物品会被直接清空——这是配置里写明的后果，不是异常路径。
     */
    private boolean enhance(CommandSender sender, String[] args) {
        // The station is player-facing, so it is handled *before* the admin gate guarding the direct
        // paths below. Those enhance straight from the inventory and bypass the station entirely, so
        // they stay admin-only; the station deliberately does not.
        if (args.length >= 2 && (args[1].equalsIgnoreCase("gui") || args[1].equalsIgnoreCase("station"))) {
            if (!(sender instanceof Player viewer)) {
                sender.sendMessage(Texts.color("&c该指令只能由玩家执行。"));
                return true;
            }
            if (!viewer.hasPermission("earthrpgitem.enhance")) {
                sender.sendMessage(Texts.color("&c你没有使用强化台的权限。"));
                return true;
            }
            // Opened even when the system is switched off: the station then says so on screen, which
            // answers "why is nothing happening" better than a refusal here would.
            new RpgMenu(plugin).openEnhance(viewer);
            return true;
        }
        if (!sender.hasPermission("earthrpgitem.admin")) {
            sender.sendMessage(Texts.color("&cNo permission."));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Texts.color("&c该指令只能由玩家执行。"));
            return true;
        }
        EnhancementManager enhancement = plugin.getEnhancementManager();
        if (enhancement == null) {
            sender.sendMessage(Texts.color("&c强化系统未加载。"));
            return true;
        }
        if (!enhancement.enabled()) {
            sender.sendMessage(Texts.color(enhancement.message("disabled", "&c强化系统当前未开启。")));
            return true;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            sender.sendMessage(Texts.color(enhancement.message("no-item", "&c请手持一件可强化的 RPG 物品。")));
            return true;
        }
        RpgItemManager items = plugin.getRpgItemManager();
        RpgItem registered = items.findItem(held);
        if (registered == null) {
            sender.sendMessage(Texts.color(enhancement.message("not-rpg-item", "&c这个物品不是 RPG 物品，无法强化。")));
            return true;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("set")) return enhanceSet(sender, player, held, enhancement, items, args);
        if (args.length >= 2 && args[1].equalsIgnoreCase("info")) return enhanceInfo(sender, player, registered, enhancement, items, held);
        return enhanceAttempt(sender, player, held, registered, enhancement, items);
    }

    private boolean enhanceSet(CommandSender sender, Player player, ItemStack held, EnhancementManager enhancement,
                               RpgItemManager items, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Texts.color("&eUsage: /rpgitem enhance set <等级>"));
            return true;
        }
        int requested;
        try {
            requested = Integer.parseInt(args[2].trim());
        } catch (NumberFormatException exception) {
            sender.sendMessage(Texts.color("&c等级必须是整数。"));
            return true;
        }
        int level = Math.max(0, Math.min(requested, enhancement.maxLevel()));
        if (level != requested) {
            sender.sendMessage(Texts.color("&e等级被限制在 0 ~ " + enhancement.maxLevel() + " 之间。"));
        }
        if (!items.setEnhanceLevel(held, level)) {
            sender.sendMessage(Texts.color("&c设置失败：这不是一件已注册的 RPG 物品。"));
            return true;
        }
        player.updateInventory();
        sender.sendMessage(Texts.color("&a已把该物品的强化等级设为 &6+" + level
                + " &7（属性与 lore 已同步刷新）"));
        return true;
    }

    // ---- 宝石 ---------------------------------------------------------------------------------

    /**
     * {@code /rpgitem gems} 打开宝石台。
     * <p>
     * 和强化台一样是面向玩家的入口，所以刻意放在 admin 门槛之前。宝石台只读取主手物品，不会把装备
     * 从玩家手里拿走；宝石也由玩家自己拿到光标上，插件不从背包里直接扣。
     */
    private boolean gems(CommandSender sender) {
        if (!(sender instanceof Player viewer)) {
            sender.sendMessage(Texts.color("&c该指令只能由玩家执行。"));
            return true;
        }
        if (!viewer.hasPermission("earthrpgitem.gem")) {
            sender.sendMessage(Texts.color("&c你没有使用宝石台的权限。"));
            return true;
        }
        new RpgMenu(plugin).openGems(viewer);
        return true;
    }

    /**
     * 技能强化台的指令入口。
     * <p>
     * 和强化台/宝石台一样面向玩家：技能物品不会被拿进界面，等级存在玩家数据里，
     * 所以这个入口没有吞掉玩家物品的可能。也刻意放在 admin 门槛之前。
     */
    private boolean skillForge(CommandSender sender) {
        if (!(sender instanceof Player viewer)) {
            sender.sendMessage(Texts.color("&c该指令只能由玩家执行。"));
            return true;
        }
        if (!viewer.hasPermission("earthrpgitem.skillforge")) {
            sender.sendMessage(Texts.color("&c你没有使用技能强化台的权限。"));
            return true;
        }
        new RpgMenu(plugin).openSkillForge(viewer);
        return true;
    }

    /**
     * 采集诊断：打印玩家在三个分类下的等级、工具加成、有效等级，以及两张表的规模。
     * <p>
     * 刻意做成只读：本插件不存经验等级，所以这里显示的是「从别的插件读到了什么」。
     * 它是排查「为什么这块矿物挖不动」的第一站——能一眼看出是 provider 没注册、
     * 等级确实不够，还是工具加成没生效。
     */
    private boolean gathering(CommandSender sender, String[] args) {
        GatheringSkillManager gathering = plugin.getGatheringSkillManager();
        if (gathering == null) {
            sender.sendMessage(Texts.color("&c采集系统尚未初始化。"));
            return true;
        }
        Player target;
        if (args.length >= 2) {
            if (!sender.hasPermission("earthrpgitem.admin")) {
                sender.sendMessage(Texts.color("&c查看其他玩家的采集信息需要 earthrpgitem.admin 权限。"));
                return true;
            }
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(Texts.color("&c找不到在线玩家: " + args[1]));
                return true;
            }
        } else if (sender instanceof Player viewer) {
            target = viewer;
        } else {
            sender.sendMessage(Texts.color("&c控制台请指定玩家: /rpgitem gathering <玩家>"));
            return true;
        }
        sender.sendMessage(Texts.color("&6&l=== 采集诊断 ==="));
        sender.sendMessage(Texts.color("&7玩家：&f" + target.getName() + " &8| &7主手：&f"
                + target.getInventory().getItemInMainHand().getType().name()
                + " &8| &7门槛只看手持工具的采掘等级"));
        for (CollectionKind kind : CollectionKind.values()) {
            sender.sendMessage(Texts.color("&7- &f" + kind.displayName() + "&7：手持工具采掘等级 &f"
                    + gathering.toolLevel(target, kind)));
        }
        // 经验与采掘等级门槛都挂在资源点上，统计资源点的规模最有用。
        List<CollectionDefinition> definitions = plugin.getCollectionManager().getDefinitions();
        long xpPoints = definitions.stream().filter(CollectionDefinition::givesExperience).count();
        long gatedPoints = definitions.stream().filter(CollectionDefinition::hasLevelGate).count();
        sender.sendMessage(Texts.color("&7资源点 &f" + definitions.size() + " &7个（带经验 &f" + xpPoints
                + " &7/ 带门槛 &f" + gatedPoints + " &7） &8| &7原版方块门槛 &f"
                + gathering.vanillaGateCount() + " &7项（默认空，原版经验已禁用）"));
        return true;
    }

    private boolean enhanceInfo(CommandSender sender, Player player, RpgItem registered, EnhancementManager enhancement,
                                RpgItemManager items, ItemStack held) {
        int current = items.enhanceLevel(held);
        int max = enhancement.maxLevel();
        sender.sendMessage(Texts.color("&6&l=== 强化信息 ==="));
        sender.sendMessage(Texts.color("&7物品：&f" + registered.displayName()
                + " &8(" + registered.type().name() + ")"));
        sender.sendMessage(Texts.color("&7当前强化：&6+" + current + " &7/ 上限 &6+" + max));
        int next = current + 1;
        EnhancementManager.Level definition = enhancement.level(next);
        if (definition == null) {
            sender.sendMessage(Texts.color("&7下一级：&c配置里没有定义 +" + next + " 这一级"));
            return true;
        }
        sender.sendMessage(Texts.color("&7下一级：&6+" + next
                + " &7成功率 &f" + Math.round(definition.successRate() * 100) + "%"
                + " &7失败惩罚 &f" + penaltyName(definition.penalty())));
        sender.sendMessage(Texts.color("&7该级全属性 &f+" + Math.round(definition.percent() * 100) + "%"
                + " &8（可强化 " + (enhancement.includedStats().isEmpty()
                        ? "除排除表外全部"
                        : enhancement.includedStats().size() + " 项")
                + "）"));
        // The percentage alone does not say whether this particular item gains anything, so the
        // actual per-attribute numbers are printed for the item in hand.
        Map<String, Double> bonus = items.enhancementBonusMap(held, registered, next);
        sender.sendMessage(Texts.color("&7该级实际加成：" + (bonus.isEmpty()
                ? "&c这件物品没有可强化的属性"
                : "&f" + new TreeMap<>(bonus))));
        if (enhancement.consumesStone()) {
            String stoneId = enhancement.stoneItemId();
            sender.sendMessage(Texts.color("&7消耗：&f" + stoneId + " &7×&f" + enhancement.stoneAmount()
                    + " &7（背包中 &f" + countMaterial(player, items, stoneId) + "&7）"));
        } else {
            sender.sendMessage(Texts.color("&7消耗：&f无（未配置强化石）"));
        }
        return true;
    }

    private boolean enhanceAttempt(CommandSender sender, Player player, ItemStack held, RpgItem registered,
                                   EnhancementManager enhancement, RpgItemManager items) {
        // The whole attempt — when the material is charged, what the roll is compared against, and
        // what each penalty does to the item — lives in EnhancementService so that this command and
        // the enhancement station cannot drift apart on a rule that costs the player an item.
        plugin.getEnhancementService().attempt(player, held,
                plugin.getEnhancementService().inventorySource(player));
        return true;
    }

    private static String penaltyName(EnhancementManager.Penalty penalty) {
        return EnhancementService.penaltyName(penalty);
    }

    /** How many items matching {@code itemId} the player is carrying (storage slots only). */
    private int countMaterial(Player player, RpgItemManager items, String itemId) {
        return plugin.getEnhancementService().countInInventory(player, itemId);
    }

    /**
     * Turns magic circle tracing on or off for the sender. Kept as a command rather than a config
     * switch so diagnosing a circle does not require editing and reloading a file.
     */
    private boolean magicDebug(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Texts.color("&cPlayers only."));
            return true;
        }
        boolean enabled = plugin.getFSkills().toggleMagicCircleDebug(player);
        player.sendMessage(Texts.color(enabled
                ? "&a法阵诊断已开启&7：现在施放法阵，然后查看服务端控制台。"
                : "&7法阵诊断已关闭。"));
        if (enabled) {
            player.sendMessage(Texts.color("&7控制台会打印：中心坐标、伤害配置、范围内每个实体、筛选结果、结算结果。"));
            player.sendMessage(Texts.color("&7再次输入 &f/rpgitem magicdebug &7可关闭。"));
        }
        return true;
    }

    private boolean reload(CommandSender sender) {        if (!sender.hasPermission("earthrpgitem.admin")) {
            sender.sendMessage(Texts.color("&cNo permission."));
            return true;
        }
        plugin.reloadPluginConfig();
        sender.sendMessage(Texts.color("&aEarthRPGItem reloaded."));
        // reload 后立刻回报 EarthExp 接入状态：这是排查「装了 EarthExp 却显示未接入」的第一手信息，
        // 而且控制台也能看到（skillcheck 需要玩家身份）。
        var bridge = plugin.getEarthExpBridge();
        sender.sendMessage(Texts.color("&7EarthExp 接入: " + (bridge.isAvailable() ? "&a" : "&e") + bridge.status()));
        return true;
    }

    private boolean dummy(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Texts.color("&cPlayers only."));
            return true;
        }
        if (!sender.hasPermission("earthrpgitem.dummy") && !sender.hasPermission("earthrpgitem.admin")) {
            sender.sendMessage(Texts.color("&cNo permission."));
            return true;
        }
        if (args.length > 1 && args[1].equalsIgnoreCase("del")) {
            plugin.getDummyManager().deleteNearby(player);
            return true;
        }
        plugin.getDummyManager().spawnDummy(player);
        return true;
    }

    private boolean showMyStats(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Texts.color("&cPlayers only."));
            return true;
        }
        if (!sender.hasPermission("earthrpgitem.my")) {
            sender.sendMessage(Texts.color("&cNo permission."));
            return true;
        }
        RpgStats stats = plugin.getRpgItemManager().collectActiveStats(player, true);
        CollectionStats collectionStats = plugin.getRpgItemManager().collectActiveCollectionStats(player, true);
        player.sendMessage(Texts.color("&6==== EarthRPGItem Active Stats ===="));
        // Stated explicitly so that a fixed number where a range was expected explains itself.
        player.sendMessage(Texts.color("&7随机属性模式: &f" + (plugin.getRpgItemManager().rerollOnUse()
                ? "每次结算重掷（显示范围 最小值-最大值）" : "生成时固定（显示保存值）")));
        var randomRanges = plugin.getRpgItemManager().collectConfiguredRandomRanges(player);
        List<String> lines = statLines(stats, randomRanges);
        addCollectionLines(lines, collectionStats);
        // External modifiers are listed by source so a plugin author can confirm their own modifier
        // landed, and can tell theirs apart from another plugin's.
        Set<String> modifierSources = plugin.getRpgItemManager().attributeModifierSources(player);
        if (!modifierSources.isEmpty()) {
            player.sendMessage(Texts.color("&6---- 外部属性加成 / External modifiers ----"));
            for (String source : modifierSources) {
                player.sendMessage(Texts.color("&7来源 &f" + source + " &7-> &f"
                        + plugin.getRpgItemManager().attributeModifier(player, source).toMap()));
            }
        }
        if (lines.isEmpty()) {
            player.sendMessage(Texts.color("&7No active RPGItem stats."));
            return true;
        }
        for (String line : lines) player.sendMessage(Texts.color(line));
        return true;
    }

    private boolean skillCheck(CommandSender sender) {
        if (!(sender instanceof Player player)) { sender.sendMessage(Texts.color("&cPlayers only.")); return true; }
        if (!sender.hasPermission("earthrpgitem.skills")) { sender.sendMessage(Texts.color("&cNo permission.")); return true; }
        player.sendMessage(Texts.color("&6==== 技能面板诊断 / Skill panel check ===="));
        // 放在最前面：它决定「升级消耗技能点还是宝石」，是排查升级问题时第一个要看的东西。
        var bridge = plugin.getEarthExpBridge();
        player.sendMessage(Texts.color("&7EarthExp 接入: " + (bridge.isAvailable() ? "&a" : "&e") + bridge.status()));
        player.sendMessage(Texts.color("&7总开关: " + (plugin.getSkillPanelManager().isEnabled(player) ? "&a开启" : "&c关闭")));
        for (String trigger : SkillPanelManager.TRIGGERS) {
            String id = plugin.getSkillPanelManager().getSkillId(player, trigger);
            String reason = plugin.getSkillPanelManager().diagnose(player, trigger);
            String color = reason.equals("正常") ? "&a" : "&e";
            player.sendMessage(Texts.color("&f" + trigger + " &7绑定: &f" + (id == null || id.isBlank() ? "无" : id) + " &7状态: " + color + reason));
        }
        player.sendMessage(Texts.color("&6---- 已注册技能 / Registered skills ----"));
        List<String> registered = plugin.getSkillPanelManager().registeredSkillIds();
        if (registered.isEmpty()) {
            player.sendMessage(Texts.color("&c未注册任何技能，请检查 skill-panel.skills 配置"));
        } else {
            for (String id : registered) {
                boolean resolvable = plugin.getSkillPanelManager().registeredItem(id) != null;
                String file = plugin.getSkillPanelManager().registeredSkillFile(id);
                var registration = plugin.getSkillPanelManager().registration(id);
                String source = registration != null && registration.fromConfig() ? "config" : "自动扫描";
                String error = resolvable ? null : plugin.getRpgItemManager().skillLoadError(file);
                player.sendMessage(Texts.color("&f" + id
                        + " &7[" + (file.isBlank() ? "无 skill-file" : file) + "]"
                        + " &7来源: &f" + source
                        + " &7组: &f" + (plugin.getSkillPanelManager().skillGroup(id).isBlank()
                                ? "无" : plugin.getSkillPanelManager().skillGroup(id))
                        + " &7冷却: &f" + plugin.getSkillPanelManager().cooldownMode(id)
                        + " &7-> " + (resolvable ? "&a可解析"
                                : "&c无法解析" + (error == null ? "" : " &7" + error))));
                // The skill item's own lore is generated from the *loaded* skill config, so it
                // reveals what the plugin actually parsed for this skill (cooldown mode, weapon
                // percent, damage type) rather than what the file was supposed to say.
                if (resolvable) {
                    for (String lore : plugin.getSkillPanelManager().skillLore(id, null)) {
                        player.sendMessage(Texts.color("   " + lore));
                    }
                }
            }
        }
        player.sendMessage(Texts.color("&7技能背包: &f" + plugin.getSkillPanelManager().backpackIds(player).size()
                + " &7/ &f" + SkillBackpackManager.CAPACITY));
        return true;
    }

    /**
     * Prints every input behind weapon-percent skill damage, so a wrong number can be traced to
     * the exact source instead of guessed at.
     */
    private boolean skillDamage(CommandSender sender) {
        if (!(sender instanceof Player player)) { sender.sendMessage(Texts.color("&cPlayers only.")); return true; }
        if (!sender.hasPermission("earthrpgitem.skills")) { sender.sendMessage(Texts.color("&cNo permission.")); return true; }
        player.sendMessage(Texts.color("&6==== 技能伤害诊断 / Skill damage ===="));
        for (String line : plugin.getFSkills().weaponDamageDebug(player)) player.sendMessage(Texts.color(line));
        for (String line : plugin.getFSkills().magicCircleDebug(player)) player.sendMessage(Texts.color(line));
        return true;
    }

    private boolean skills(CommandSender sender) {
        if (!(sender instanceof Player player)) { sender.sendMessage(Texts.color("&cPlayers only.")); return true; }
        if (!sender.hasPermission("earthrpgitem.skills")) { sender.sendMessage(Texts.color("&cNo permission.")); return true; }
        new RpgMenu(plugin).openSkillPanel(player);
        return true;
    }

    private boolean openGui(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Texts.color("&cPlayers only."));
            return true;
        }
        if (!sender.hasPermission("earthrpgitem.gui") && !sender.hasPermission("earthrpgitem.admin")) {
            sender.sendMessage(Texts.color("&cAdmin only."));
            return true;
        }
        if (!plugin.isFeatureEnabled("gui")) {
            sender.sendMessage(Texts.color("&cGUI is disabled."));
            return true;
        }
        new RpgMenu(plugin).openMain(player);
        return true;
    }

    private boolean accessoryGui(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Texts.color("&cPlayers only."));
            return true;
        }
        if (!plugin.isFeatureEnabled("accessory")) {
            sender.sendMessage(Texts.color("&cAccessory system is disabled."));
            return true;
        }
        plugin.getAccessoryManager().open(player);
        return true;
    }

    /**
     * {@code /rpgitem accessory <玩家> [list|clear]}：管理某个玩家的饰品栏。
     *
     * <p>不带子指令时打开该玩家的饰品栏界面（在线玩家可增删改，离线只读）。
     * {@code list} 是纯文本清单，离线玩家也能看；{@code clear} 清空。
     *
     * <p>权限：查看/编辑他人需要 {@code earthrpgitem.accessory.admin}；只查看自己的
     * （{@code /rpgitem accessory <自己>} 或 {@code accessorygui}）不需要额外权限。
     */
    private boolean accessory(CommandSender sender, String[] args) {
        if (!plugin.isFeatureEnabled("accessory")) {
            sender.sendMessage(Texts.color("&c饰品系统当前已关闭（features.accessory）。"));
            return true;
        }
        if (args.length < 2) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Texts.color("&e用法: /rpgitem accessory <玩家> [list|clear]"));
                return true;
            }
            plugin.getAccessoryManager().open(player);
            return true;
        }

        String action = args.length >= 3 ? args[2].toLowerCase(java.util.Locale.ROOT) : "open";
        String targetName = args[1];
        boolean self = sender instanceof Player player && player.getName().equalsIgnoreCase(targetName);
        if (!self && !sender.hasPermission("earthrpgitem.accessory.admin") && !sender.hasPermission("earthrpgitem.admin")) {
            sender.sendMessage(Texts.color("&c查看/管理他人饰品需要 earthrpgitem.accessory.admin 权限。"));
            return true;
        }

        Player online = Bukkit.getPlayerExact(targetName);
        UUID targetId = online != null ? online.getUniqueId() : resolveOfflineId(targetName);
        if (targetId == null) {
            sender.sendMessage(Texts.color("&c找不到玩家: " + targetName + "（离线玩家需至少进服过一次）"));
            return true;
        }

        if (action.equals("list")) {
            sender.sendMessage(Texts.color("&8&m-------- &f" + targetName + " &7的饰品栏 &8&m--------"));
            for (String line : plugin.getAccessoryManager().describe(targetId)) {
                sender.sendMessage(Texts.color("&7" + line));
            }
            return true;
        }
        if (action.equals("clear")) {
            int removed = plugin.getAccessoryManager().clear(targetId);
            sender.sendMessage(Texts.color("&a已清空 &f" + targetName + " &a的饰品栏，共移除 &f" + removed + " &a件。"));
            return true;
        }
        if (!action.equals("open")) {
            sender.sendMessage(Texts.color("&e用法: /rpgitem accessory <玩家> [list|clear]"));
            return true;
        }

        if (!(sender instanceof Player viewer)) {
            sender.sendMessage(Texts.color("&c控制台只能使用 list / clear；打开界面请以玩家身份执行。"));
            return true;
        }
        if (online == null) {
            // 离线玩家不打开界面：饰品栏数据以「槽号:物品」写在 accessories.yml 里，
            // 对着离线数据开一个可编辑界面，一旦和玩家上线后的读取交错就会互相覆盖。
            sender.sendMessage(Texts.color("&e" + targetName + " 不在线，无法打开饰品栏界面。"
                    + "请用 &f/rpgitem accessory " + targetName + " list &e查看清单。"));
            return true;
        }
        // 管理员打开他人饰品栏时可编辑（权限已在上方校验）；owner 仍是目标玩家，
        // 所以属性结算与灵魂绑定都按目标玩家判定。
        plugin.getAccessoryManager().open(viewer, online);
        return true;
    }

    /** 离线玩家的 UUID：优先 Bukkit 的玩家缓存，用来读取 accessories.yml 里的旧数据。 */
    private UUID resolveOfflineId(String name) {
        try {
            var offline = Bukkit.getOfflinePlayer(name);
            return offline == null ? null : offline.getUniqueId();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private boolean fix(CommandSender sender, boolean all) {
        Player player = fixTarget(sender);
        if (player == null) {
            sender.sendMessage(Texts.color("&cNo player target found."));
            return true;
        }
        if (!sender.isOp() && !(sender instanceof BlockCommandSender)) {
            sender.sendMessage(Texts.color("&cOnly OP or command block can use this command."));
            return true;
        }
        if (!all) {
            plugin.getRpgItemManager().restoreAllDurability(player.getInventory().getItemInMainHand());
            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 1.0f, 1.0f);
            player.sendMessage(Texts.color("&a手持 RPGItem 耐久已回满。"));
            return true;
        }
        for (ItemStack itemStack : player.getInventory().getContents()) plugin.getRpgItemManager().restoreAllDurability(itemStack);
        for (ItemStack itemStack : player.getInventory().getArmorContents()) plugin.getRpgItemManager().restoreAllDurability(itemStack);
        plugin.getRpgItemManager().restoreAllDurability(player.getInventory().getItemInOffHand());
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 1.0f, 1.0f);
        player.sendMessage(Texts.color("&a全身和背包 RPGItem 耐久已回满。"));
        return true;
    }

    private Player fixTarget(CommandSender sender) {
        if (sender instanceof Player player) return player;
        if (sender instanceof BlockCommandSender blockSender) {
            return blockSender.getBlock().getWorld().getNearbyPlayers(blockSender.getBlock().getLocation(), 8).stream().findFirst().orElse(null);
        }
        return null;
    }

    private boolean give(CommandSender sender, String[] args) {
        if (!sender.hasPermission("earthrpgitem.admin")) {
            sender.sendMessage(Texts.color("&cNo permission."));
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(Texts.color("&eUsage: /rpgitem give <player> <rpgitem>"));
            return true;
        }
        RpgItem item = plugin.getRpgItemManager().findById(args[2]);
        if (item == null) {
            sender.sendMessage(Texts.color("&cRPGItem ID 错误或物品未注册: &f" + args[2]));
            return true;
        }
        List<Player> targets = resolveTargets(sender, args[1]).stream().filter(Player.class::isInstance).map(Player.class::cast).toList();
        if (targets.isEmpty()) {
            sender.sendMessage(Texts.color("&c玩家目标错误或玩家不在线: &f" + args[1]));
            return true;
        }
        for (Player target : targets) target.getInventory().addItem(plugin.getRpgItemManager().createItem(item));
        sender.sendMessage(Texts.color("&a已将 &e" + item.id() + " &a给予 &e" + targets.size() + " &a名玩家。"));
        return true;
    }

    private boolean puton(CommandSender sender, String[] args) {
        if (!sender.hasPermission("earthrpgitem.admin")) {
            sender.sendMessage(Texts.color("&cNo permission."));
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(Texts.color("&eUsage: /rpgitem puton <target|@self|selector> <rpgitem>"));
            return true;
        }
        RpgItem item = plugin.getRpgItemManager().findById(args[2]);
        if (item == null) {
            sender.sendMessage(Texts.color("&cRPGItem not found."));
            return true;
        }
        List<LivingEntity> targets = resolveTargets(sender, args[1]);
        if (targets.isEmpty()) {
            sender.sendMessage(Texts.color("&cNo living target found."));
            return true;
        }
        for (LivingEntity target : targets) equip(target, plugin.getRpgItemManager().createItem(item));
        sender.sendMessage(Texts.color("&aEquipped &e" + item.id() + " &ato &e" + targets.size() + " &atarget(s)."));
        return true;
    }

    private List<LivingEntity> resolveTargets(CommandSender sender, String selector) {
        Entity self = senderEntity(sender);
        if (isSelfSelector(selector)) {
            return self instanceof LivingEntity livingEntity ? List.of(livingEntity) : List.of();
        }
        String normalized = unwrapTarget(selector);
        Player player = Bukkit.getPlayerExact(normalized);
        if (player != null) return List.of(player);
        try {
            Collection<Entity> entities = Bukkit.selectEntities(sender, normalized);
            return entities.stream().filter(LivingEntity.class::isInstance).map(LivingEntity.class::cast).toList();
        } catch (IllegalArgumentException exception) {
            return List.of();
        }
    }

    private Entity senderEntity(CommandSender sender) {
        if (sender instanceof Entity entity) return entity;
        if (sender instanceof ProxiedCommandSender proxied && proxied.getCallee() instanceof Entity entity) return entity;
        return null;
    }

    private boolean isSelfSelector(String selector) {
        return selector.equalsIgnoreCase("@self")
                || selector.equalsIgnoreCase("{player}")
                || selector.equalsIgnoreCase("<player>");
    }

    private String unwrapTarget(String selector) {
        if (selector.length() > 2 && ((selector.startsWith("<") && selector.endsWith(">"))
                || (selector.startsWith("{") && selector.endsWith("}")))) {
            return selector.substring(1, selector.length() - 1);
        }
        return selector;
    }

    private void equip(LivingEntity target, ItemStack itemStack) {
        if (target instanceof Player player && plugin.getAccessoryManager().equipAccessory(player, itemStack)) return;
        EntityEquipment equipment = target.getEquipment();
        if (equipment == null) return;
        Material material = itemStack.getType();
        String name = material.name();
        if (name.endsWith("_HELMET")) equipment.setHelmet(itemStack);
        else if (name.endsWith("_CHESTPLATE")) equipment.setChestplate(itemStack);
        else if (name.endsWith("_LEGGINGS")) equipment.setLeggings(itemStack);
        else if (name.endsWith("_BOOTS")) equipment.setBoots(itemStack);
        else equipment.setItemInMainHand(itemStack);
    }

    private List<String> statLines(RpgStats stats, java.util.Map<String, RandomAttributeRange> randomRanges) {
        List<String> lines = new ArrayList<>();
        addFlatPercentRanged(lines, "&c+Attack: &f", randomRanges, "attack", "attackpercent", stats.attack(), stats.attackPercent());
        addFlatPercentRanged(lines, "&9+Armor: &f", randomRanges, "armor", "armorpercent", stats.armor(), stats.armorPercent());
        if (stats.critChance() != 0) {
            double overflowDamage = Math.max(0, stats.critChance() - 100) * 10;
            String critText = randomText(randomRanges, "critchance");
            lines.add("&6+CritChance: &f" + (critText != null ? critText : trimNumber(Math.min(100, stats.critChance()))) + "%");
            if (overflowDamage > 0) lines.add("&eCrit overflow damage: &f+" + trimNumber(overflowDamage));
        }
        addFlatPercentRanged(lines, "&e+CritDamage: &f", randomRanges, "critdamage", "critdamagepercent", stats.critDamage(), stats.critDamagePercent());
        if (plugin.isFeatureEnabled("attack-speed")) {
            String speedText = randomText(randomRanges, "attackspeed");
            lines.add("&a+AttackSpeed: &f" + (speedText != null ? speedText : trimNumber(stats.attackSpeed())));
        }
        addRanged(lines, "&c+Health: &f", randomRanges, "health", stats.health(), "");
        addRanged(lines, "&c+Vitality: &f", randomRanges, "vitality", stats.vitality(), "%");
        addRanged(lines, "&a+Recovery: &f", randomRanges, "recovery", stats.recovery(), "");
        addRanged(lines, "&a+RecoveryPercent: &f", randomRanges, "recoverypercent", stats.recoveryPercent(), "");
        addFlatPercentRanged(lines, "&4+LifeSteal: &f", randomRanges, "lifesteal", "lifestealpercent", stats.lifeSteal(), stats.lifeStealPercent());
        addFlatPercentRanged(lines, "&d+MagicDamage: &f", randomRanges, "magicdamage", "magicdamagepercent", stats.magicDamage(), stats.magicDamagePercent());
        addFlatPercentRanged(lines, "&a+BowDamage: &f", randomRanges, "bowdamage", "bowdamagepercent", stats.bowDamage(), stats.bowDamagePercent());
        addRanged(lines, "&a+EXDraw: &f", randomRanges, "exdraw", stats.exDraw(), "");
        addRanged(lines, "&f+Fix/s: &f", randomRanges, "fix", stats.fixPerSecond(), "");
        addRanged(lines, "&f+ExDur: &f", randomRanges, "exdur", stats.extraDurability(), "");
        addFlatPercentRanged(lines, "&2+EnvArmor: &f", randomRanges, "envarmor", "envarmorpercent", stats.envArmor(), stats.envArmorPercent());
        addFlatPercentRanged(lines, "&5+MagicArmor: &f", randomRanges, "magicarmor", "magicarmorpercent", stats.magicArmor(), stats.magicArmorPercent());
        addRanged(lines, "&3+Resistance: &f", randomRanges, "resistance", stats.resistance(), "");
        addFlatPercentRanged(lines, "&b+BowArmor: &f", randomRanges, "bowarmor", "bowarmorpercent", stats.bowArmor(), stats.bowArmorPercent());
        addFlatPercentRanged(lines, "&6+CritArmor: &f", randomRanges, "critarmor", "critarmorpercent", stats.critArmor(), stats.critArmorPercent());
        addRanged(lines, "&f+Evasion: &f", randomRanges, "evasion", stats.evasion(), "");
        addRanged(lines, "&7+ArmorPen: &f", randomRanges, "armorpen", stats.armorPen(), "");
        addRanged(lines, "&4+TrueDamage: &f", randomRanges, "truedamage", stats.trueDamage(), "");
        addFlatPercentRanged(lines, "&8+BackstrikeDamage: &f", randomRanges, "backstrikedamage", "backstrikedamagepercent", stats.backstrikeDamage(), stats.backstrikeDamagePercent());
        addFlatPercentRanged(lines, "&5+AssDamage: &f", randomRanges, "assdamage", "assdamagepercent", stats.assDamage(), stats.assDamagePercent());
        addRanged(lines, "&c+PctDamage: &f", randomRanges, "pctdamage", stats.pctDamage(), "");
        addRanged(lines, "&c+PstDamage: &f", randomRanges, "pstdamage", stats.pstDamage(), "");
        addRanged(lines, "&6+AttackRange: &f", randomRanges, "attackrange", stats.attackRange(), "");
        addRanged(lines, "&e+Parry 招架: &f", randomRanges, "parry", stats.parry(), "");
        addRanged(lines, "&e+CounterAttack: &f", randomRanges, "counterattack", stats.counterAttack(), "");
        addRanged(lines, "&b+Dodge 闪避: &f", randomRanges, "dodge", stats.dodge(), "");
        addRanged(lines, "&f+Block 格挡: &f", randomRanges, "block", stats.block(), "");
        addRanged(lines, "&a+Determination: &f", randomRanges, "determination", stats.determination(), "");
        addRanged(lines, "&6+CAD: &f", randomRanges, "cad", stats.cad(), "");
        addFlatPercentRanged(lines, "&9+IceDamage: &f", randomRanges, "icedamage", "icedamagepercent", stats.iceDamage(), stats.iceDamagePercent());
        addFlatPercentRanged(lines, "&c+FireDamage: &f", randomRanges, "firedamage", "firedamagepercent", stats.fireDamage(), stats.fireDamagePercent());
        addFlatPercentRanged(lines, "&e+LightDamage: &f", randomRanges, "lightdamage", "lightdamagepercent", stats.lightDamage(), stats.lightDamagePercent());
        addFlatPercentRanged(lines, "&0+DarkDamage: &f", randomRanges, "darkdamage", "darkdamagepercent", stats.darkDamage(), stats.darkDamagePercent());
        addFlatPercentRanged(lines, "&9+IceArmor: &f", randomRanges, "icearmor", "icearmorpercent", stats.iceArmor(), stats.iceArmorPercent());
        addFlatPercentRanged(lines, "&c+FireArmor: &f", randomRanges, "firearmor", "firearmorpercent", stats.fireArmor(), stats.fireArmorPercent());
        addFlatPercentRanged(lines, "&e+LightArmor: &f", randomRanges, "lightarmor", "lightarmorpercent", stats.lightArmor(), stats.lightArmorPercent());
        addFlatPercentRanged(lines, "&0+DarkArmor: &f", randomRanges, "darkarmor", "darkarmorpercent", stats.darkArmor(), stats.darkArmorPercent());
        return lines;
    }

    private void add(List<String> lines, String prefix, double value) { if (value != 0) lines.add(prefix + trimNumber(value)); }
    private void addFlatPercent(List<String> lines, String prefix, double flat, double percent) { if (flat != 0 || percent != 0) lines.add(prefix + trimNumber(flat) + " + " + trimNumber(percent) + "%"); }

    /**
     * The "40-60" text for an attribute whose value is drawn again on every settlement, or null
     * when the caller should show the settled value instead. Keys are the canonical lowercase
     * attribute keys used by {@code RpgItemManager}: "%" becomes "percent" and "/" is dropped.
     */
    private String randomText(java.util.Map<String, RandomAttributeRange> ranges, String key) {
        RandomAttributeRange range = ranges.get(key);
        if (range == null || range.minimum() == range.maximum()) return null;
        if (!plugin.getRpgItemManager().rerollOnUse()) return null;
        return range.minimum() + "-" + range.maximum();
    }

    private void addRanged(List<String> lines, String prefix, java.util.Map<String, RandomAttributeRange> ranges, String key, double value, String suffix) {
        String text = randomText(ranges, key);
        if (text == null) {
            if (value != 0) lines.add(prefix + trimNumber(value) + suffix);
            return;
        }
        lines.add(prefix + text + suffix);
    }

    private void addFlatPercentRanged(List<String> lines, String prefix, java.util.Map<String, RandomAttributeRange> ranges, String flatKey, String percentKey, double flat, double percent) {
        String flatText = randomText(ranges, flatKey);
        String percentText = randomText(ranges, percentKey);
        if (flatText == null && percentText == null) {
            addFlatPercent(lines, prefix, flat, percent);
            return;
        }
        lines.add(prefix + (flatText != null ? flatText : trimNumber(flat)) + " + " + (percentText != null ? percentText : trimNumber(percent)) + "%");
    }
    private void addCollectionLines(List<String> lines, CollectionStats stats) {
        add(lines, "&e+FarmingSpeed: &f", stats.farmingSpeed());
        add(lines, "&7+MiningSpeed: &f", stats.miningSpeed());
        add(lines, "&6+ForestSpeed: &f", stats.forestSpeed());
        add(lines, "&e+FarmingFortune: &f", stats.farmingFortune());
        add(lines, "&7+MiningFortune: &f", stats.miningFortune());
        add(lines, "&6+ForestFortune: &f", stats.forestFortune());
    }
    private String trimNumber(double value) { return value == Math.rint(value) ? String.valueOf((int) value) : String.valueOf(value); }

    /**
     * Snapshot of everything in this plugin that can grow without bound. Run it twice, a minute
     * apart, while doing the action that seems to leak: whichever line went up is the culprit.
     */
    private boolean memDebug(CommandSender sender) {
        if (!sender.hasPermission("earthrpgitem.admin")) {
            sender.sendMessage(Texts.color("&cNo permission."));
            return true;
        }
        Runtime runtime = Runtime.getRuntime();
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1048576L;
        sender.sendMessage(Texts.color("&6===== EarthRPGItem 内存诊断 ====="));
        sender.sendMessage(Texts.color("&7堆内存: &f" + usedMb + " MB &7/ 已分配 &f" + (runtime.totalMemory() / 1048576L)
                + " MB &7/ 上限 &f" + (runtime.maxMemory() / 1048576L) + " MB"));
        sender.sendMessage(Texts.color("&7待执行调度任务: &f" + Bukkit.getScheduler().getPendingTasks().size()
                + " &7(反复执行本命令，若此数持续上涨即为任务泄漏)"));

        Map<String, Integer> entities = new TreeMap<>();
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                entities.merge(entity.getType().name(), 1, Integer::sum);
            }
        }
        int totalEntities = entities.values().stream().mapToInt(Integer::intValue).sum();
        sender.sendMessage(Texts.color("&7全服实体总数: &f" + totalEntities));
        for (String tracked : List.of("TEXT_DISPLAY", "ARMOR_STAND", "ITEM_DISPLAY", "BLOCK_DISPLAY")) {
            int count = entities.getOrDefault(tracked, 0);
            sender.sendMessage(Texts.color((count > 0 ? "&e" : "&7") + "  " + tracked + ": &f" + count));
        }
        entities.entrySet().stream()
                .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                .limit(8)
                .forEach(entry -> sender.sendMessage(Texts.color("&7  最多: " + entry.getKey() + " &f" + entry.getValue())));

        sender.sendMessage(Texts.color("&7法阵残留实体: &f" + plugin.getFSkills().magicCircleEntityCount()));
        sender.sendMessage(Texts.color("&7王之宝库残留武器: &f" + plugin.getFSkills().gateOfBabylonEntityCount()));
        sender.sendMessage(Texts.color("&7饰品缓存玩家数: &f" + plugin.getAccessoryManager().cachedPlayerCount()));
        sender.sendMessage(Texts.color("&7训练假人记录数: &f" + plugin.getDummyManager().trackedDummyCount()));
        sender.sendMessage(Texts.color("&7外部属性修饰符: &f" + plugin.getRpgItemManager().trackedModifierEntities()
                + " &7个实体 / &f" + plugin.getRpgItemManager().trackedModifierSources()
                + " &7条 / 待过期任务 &f" + plugin.getRpgItemManager().pendingModifierExpiryTasks()));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) return List.of("my", "gui", "skills", "skillcheck", "skilldamage", "skillforge", "gathering", "magicdebug", "memdebug", "enhance", "gems", "accessorygui", "collection", "dummy", "give", "puton", "poton", "fix", "fixs", "reload").stream().filter(value -> value.startsWith(args[0].toLowerCase())).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("gathering")) return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase())).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("accessory")) return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase())).toList();
        if (args.length == 3 && args[0].equalsIgnoreCase("accessory")) return List.of("open", "list", "clear").stream().filter(value -> value.startsWith(args[2].toLowerCase())).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("enhance")) return List.of("set", "info", "gui").stream().filter(value -> value.startsWith(args[1].toLowerCase())).toList();
        if (args.length == 3 && args[0].equalsIgnoreCase("enhance") && args[1].equalsIgnoreCase("set")) {
            int max = plugin.getEnhancementManager() == null ? 0 : plugin.getEnhancementManager().maxLevel();
            List<String> levels = new ArrayList<>();
            for (int level = 0; level <= max; level++) levels.add(Integer.toString(level));
            return levels.stream().filter(value -> value.startsWith(args[2])).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("dummy")) return List.of("del").stream().filter(value -> value.startsWith(args[1].toLowerCase())).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("collection")) return List.of("refresh").stream().filter(value -> value.startsWith(args[1].toLowerCase())).toList();
        if (args.length == 3 && args[0].equalsIgnoreCase("collection") && args[1].equalsIgnoreCase("refresh")) {
            List<String> worlds = new ArrayList<>(List.of("all"));
            worlds.addAll(Bukkit.getWorlds().stream().map(World::getName).toList());
            return worlds.stream().filter(value -> value.toLowerCase().startsWith(args[2].toLowerCase())).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("puton") || args[0].equalsIgnoreCase("poton"))) {
            List<String> targets = new ArrayList<>(List.of("@self", "{player}", "<player>", "@a", "@p"));
            targets.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
            return targets.stream().filter(value -> value.toLowerCase().startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("puton") || args[0].equalsIgnoreCase("poton"))) {
            return plugin.getRpgItemManager().getAllItems().stream().map(RpgItem::id).filter(value -> value.toLowerCase().startsWith(args[2].toLowerCase())).toList();
        }
        return List.of();
    }
}
