package EarthRPGItem;

import EarthRPGItem.commands.EarthRPGItemCommand;
import EarthRPGItem.api.EarthRPGItemAPI;
import EarthRPGItem.api.EarthRPGItemApiImpl;
import EarthRPGItem.combat.AttackCooldownManager;
import EarthRPGItem.accessory.AccessoryManager;
import EarthRPGItem.collection.CollectionManager;
import EarthRPGItem.dummy.DummyListener;
import EarthRPGItem.dummy.DummyManager;
import EarthRPGItem.gui.RpgMenuListener;
import EarthRPGItem.fskills.FSkills;
import EarthRPGItem.items.EnhanceStationManager;
import EarthRPGItem.items.EnhancementManager;
import EarthRPGItem.items.EnhancementService;
import EarthRPGItem.items.GemstoneManager;
import EarthRPGItem.items.RpgItemManager;
import EarthRPGItem.skills.GatheringSkillManager;
import EarthRPGItem.skills.EarthExpBridge;
import EarthRPGItem.skills.SkillBackpackManager;
import EarthRPGItem.skills.SkillBookListener;
import EarthRPGItem.skills.SkillLevelManager;
import EarthRPGItem.skills.SkillPanelManager;
import EarthRPGItem.skills.SkillPresetManager;
import EarthRPGItem.listeners.PlayerJoinListener;
import EarthRPGItem.listeners.StatsCacheListener;
import EarthRPGItem.listeners.ActiveDefenseListener;
import EarthRPGItem.listeners.RpgDropListener;
import EarthRPGItem.listeners.RpgCombatListener;
import EarthRPGItem.listeners.RpgDurabilityListener;
import EarthRPGItem.listeners.RpgProjectileListener;
import EarthRPGItem.listeners.RpgSoulboundListener;
import EarthRPGItem.tasks.PlayerAttributeTask;
import EarthRPGItem.util.Texts;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;

public final class EarthRPGItem extends JavaPlugin {

    private RpgItemManager rpgItemManager;
    private EnhancementManager enhancementManager;
    private EnhancementService enhancementService;
    private EnhanceStationManager enhanceStationManager;
    private GemstoneManager gemstoneManager;
    private DummyManager dummyManager;
    private AccessoryManager accessoryManager;
    private AttackCooldownManager attackCooldownManager;
    private CollectionManager collectionManager;
    private RpgProjectileListener rpgProjectileListener;
    private ActiveDefenseListener activeDefenseListener;
    private SkillPanelManager skillPanelManager;
    private SkillBackpackManager skillBackpackManager;
    private SkillLevelManager skillLevelManager;
    private SkillPresetManager skillPresetManager;
    private EarthExpBridge earthExpBridge;
    private GatheringSkillManager gatheringSkillManager;
    private FSkills fSkills;
    private EarthRPGItemApiImpl api;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        migrateConfig();

        // Loaded before the item manager so that anything building or refreshing an item during
        // startup can already resolve enhancement levels.
        enhancementManager = new EnhancementManager(this);
        enhancementManager.reload();

        // Also loaded before the item manager: the gem table is part of an item's rendered signature,
        // so an item built before it loads would be stamped "unloaded" and rebuilt on first look.
        gemstoneManager = new GemstoneManager(this);
        gemstoneManager.reload();

        rpgItemManager = new RpgItemManager(this);
        rpgItemManager.initialize();
        enhancementService = new EnhancementService(this);
        enhanceStationManager = new EnhanceStationManager(this);
        collectionManager = new CollectionManager(this);
        collectionManager.initialize();
        // 采集经验 + 挖掘等级门槛。必须在 collectionManager 之后注册监听：两者的破坏
        // 处理都是 HIGHEST，但采集点处理器 ignoreCancelled=false（先跑并取消事件），
        // 本管理器的原版门槛处理器 ignoreCancelled=true（跳过已取消的）——顺序反了的话
        // 采集点方块也会先吃一道原版门槛。普通方块不涉及经验（原版经验已禁用）。
        gatheringSkillManager = new GatheringSkillManager(this);
        gatheringSkillManager.reload();
        dummyManager = new DummyManager(this);
        accessoryManager = new AccessoryManager(this);
        attackCooldownManager = new AttackCooldownManager(this);
        skillPanelManager = new SkillPanelManager(this);
        skillBackpackManager = new SkillBackpackManager(this);
        // Constructed after the panel manager: it resolves a skill's growth rules through the
        // registration table, so the table has to exist first (rules are read lazily, not here).
        skillLevelManager = new SkillLevelManager(this);
        skillLevelManager.reload();
        // EarthExp 附属（战斗天赋 = 技能点）：类名在 config.yml 的 earthexp 段配置，
        // 没配/没装时 isAvailable() 为 false，技能升级自动退回宝石消耗。
        earthExpBridge = new EarthExpBridge(this);
        // 先注册监听再 reload：本插件与 EarthExp 互为 softdepend（成环），加载顺序无保证，
        // 本插件可能先启用、那时 EarthExp 还没注册 EarthExpAPI。监听它的启用事件补一次接入。
        Bukkit.getPluginManager().registerEvents(earthExpBridge, this);
        earthExpBridge.reload();
        // 技能预设：只读写自己的 skill-presets.yml，构造顺序无所谓，
        // 但加载预设时会用到技能背包 / 面板 / 等级三个管理器，所以放在它们之后。
        skillPresetManager = new SkillPresetManager(this);

        // Logged because this setting silently decides whether a range like "40-60" is re-rolled
        // for every settlement or frozen to the value rolled at item creation.
        getLogger().info("Random attributes: reroll-on-use = " + rpgItemManager.rerollOnUse()
                + (rpgItemManager.rerollOnUse() ? " (ranges are re-rolled per settlement)" : " (ranges are fixed at item creation)"));

        Bukkit.getPluginManager().registerEvents(new PlayerJoinListener(this), this);
        // Keeps the per-tick stat memo honest: any event that can change a player's kit drops it.
        Bukkit.getPluginManager().registerEvents(new StatsCacheListener(this), this);
        rpgProjectileListener = new RpgProjectileListener(this);
        Bukkit.getPluginManager().registerEvents(rpgProjectileListener, this);
        activeDefenseListener = new ActiveDefenseListener(this);
        Bukkit.getPluginManager().registerEvents(activeDefenseListener, this);
        Bukkit.getPluginManager().registerEvents(new RpgCombatListener(this), this);
        Bukkit.getPluginManager().registerEvents(new RpgDurabilityListener(this), this);
        Bukkit.getPluginManager().registerEvents(new RpgSoulboundListener(this), this);
        Bukkit.getPluginManager().registerEvents(new RpgDropListener(this), this);
        Bukkit.getPluginManager().registerEvents(new RpgMenuListener(this), this);
        Bukkit.getPluginManager().registerEvents(new DummyListener(this), this);
        Bukkit.getPluginManager().registerEvents(new SkillBookListener(this), this);
        fSkills = new FSkills(this);
        fSkills.validateCritExclusionConfig();
        Bukkit.getPluginManager().registerEvents(fSkills, this);
        Bukkit.getPluginManager().registerEvents(accessoryManager, this);
        Bukkit.getPluginManager().registerEvents(collectionManager, this);
        // After collectionManager: see the comment where it is constructed.
        Bukkit.getPluginManager().registerEvents(gatheringSkillManager, this);
        Bukkit.getScheduler().runTaskTimer(this, new PlayerAttributeTask(this), 20L, 20L);

        var command = getCommand("EarthRPGitem");
        if (command != null) {
            EarthRPGItemCommand executor = new EarthRPGItemCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        // Registered so other plugins can add and remove attributes through a stable interface
        // instead of casting this plugin and reaching into its managers.
        api = new EarthRPGItemApiImpl(this);
        Bukkit.getServicesManager().register(EarthRPGItemAPI.class, api, this, ServicePriority.Normal);
        Bukkit.getPluginManager().registerEvents(api, this);
        getLogger().info("Registered EarthRPGItemAPI for other plugins.");

        printStartupMessage();
    }

    @Override
    public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);
        getLogger().info("EarthRPGItem disabled.");
    }

    public void reloadPluginConfig() {
        reloadConfig();
        migrateConfig();
        // Reloaded before the item manager so the enhancement revision it folds into every item
        // signature is already up to date when items re-initialise.
        if (enhancementManager != null) enhancementManager.reload();
        // Same reason as the enhancement table: the gem table is part of an item's rendered signature,
        // so it has to be re-read before items re-initialise or every item keeps the old gem bracket.
        if (gemstoneManager != null) gemstoneManager.reload();
        rpgItemManager.initialize();
        skillPanelManager.invalidateRegistrations();
        // 插件更新/技能文件增删后：背包里已不存在的注册 id 会被清掉，面板打开的
        // pruneInvalidSlots() 再兜底清理失效槽位——描述/形态/数值都按新注册重新解析。
        skillBackpackManager.pruneMissing(new HashSet<>(skillPanelManager.registeredSkillIds()));
        // After the registration table and the skill-file cache are rebuilt: the level rules are
        // read from skill files through the registration table, so a stale rules cache would keep
        // serving the pre-reload growth table.
        if (skillLevelManager != null) skillLevelManager.reload();
        if (earthExpBridge != null) earthExpBridge.reload();
        // 预设文件允许管理员手工编辑，所以 reload 必须重读，否则手改会在下一次保存时被整份覆盖。
        if (skillPresetManager != null) skillPresetManager.reload();
        // Re-read after the item manager: tool level bonuses come from item configs, and the
        // gathering tables themselves live in their own file.
        if (gatheringSkillManager != null) gatheringSkillManager.reload();
        if (collectionManager != null) collectionManager.reload();
    }

    /**
     * Config schema version. Bump this whenever a value already present in an existing config.yml
     * must be corrected, rather than merely added: {@code copyDefaults} only fills in missing keys
     * and never overwrites a value the server already has.
     */
    private static final int CONFIG_VERSION = 2;

    /**
     * Corrects stored config values that an upgrade made wrong.
     * <p>
     * Version 2 fixes {@code rpgitem.random-attributes.reroll-on-use}. Before this version no code
     * read that key at all, so every value already in a config.yml is meaningless — and every
     * shipped config had {@code false}. Since {@code copyDefaults} never overwrites an existing
     * key, an upgrading server kept that {@code false} forever, which silently froze each random
     * range to the single value rolled at item creation: {@code BowDamage: "40-60"} always dealt
     * the same damage and the stat panel showed one number instead of a range. Overwriting it is
     * safe precisely because no user could have been relying on a setting that did nothing.
     */
    private void migrateConfig() {
        int stored = getConfig().getInt("config-version", 1);
        if (stored >= CONFIG_VERSION) return;
        getConfig().set("rpgitem.random-attributes.reroll-on-use", true);
        getConfig().set("config-version", CONFIG_VERSION);
        saveConfig();
        getLogger().info("Config migrated to version " + CONFIG_VERSION
                + ": rpgitem.random-attributes.reroll-on-use set to true"
                + " (the key was never read before this version, so its old value had no effect).");
    }

    public RpgItemManager getRpgItemManager() { return rpgItemManager; }
    public EnhancementManager getEnhancementManager() { return enhancementManager; }
    public EnhancementService getEnhancementService() { return enhancementService; }
    public EnhanceStationManager getEnhanceStationManager() { return enhanceStationManager; }
    public GemstoneManager getGemstoneManager() { return gemstoneManager; }
    public DummyManager getDummyManager() { return dummyManager; }
    public AccessoryManager getAccessoryManager() { return accessoryManager; }
    public AttackCooldownManager getAttackCooldownManager() { return attackCooldownManager; }
    public CollectionManager getCollectionManager() { return collectionManager; }
    public RpgProjectileListener getRpgProjectileListener() { return rpgProjectileListener; }
    public ActiveDefenseListener getActiveDefenseListener() { return activeDefenseListener; }
    public SkillPanelManager getSkillPanelManager() { return skillPanelManager; }
    public SkillBackpackManager getSkillBackpackManager() { return skillBackpackManager; }
    public SkillLevelManager getSkillLevelManager() { return skillLevelManager; }
    public SkillPresetManager getSkillPresetManager() { return skillPresetManager; }
    public EarthExpBridge getEarthExpBridge() { return earthExpBridge; }
    public GatheringSkillManager getGatheringSkillManager() { return gatheringSkillManager; }
    public FSkills getFSkills() { return fSkills; }

    public boolean isFeatureEnabled(String key) {
        return getConfig().getBoolean("features." + key, true);
    }

    private void printStartupMessage() {
        if (!getConfig().getBoolean("startup-message.enabled", true)) return;
        for (String line : getConfig().getStringList("startup-message.lines")) {
            Bukkit.getConsoleSender().sendMessage(Texts.color(line.replace("${version}", getDescription().getVersion())));
        }
    }
}
