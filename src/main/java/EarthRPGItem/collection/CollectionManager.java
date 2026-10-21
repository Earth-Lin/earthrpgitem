package EarthRPGItem.collection;

import EarthRPGItem.EarthRPGItem;
import EarthRPGItem.items.RpgItem;
import EarthRPGItem.items.RpgItemManager;
import EarthRPGItem.skills.GatheringSkillManager;
import EarthRPGItem.util.Texts;
import EarthRPGItem.util.YamlStore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class CollectionManager implements Listener {
    private static final int ENFORCE_INTERVAL_TICKS = 10;
    private static final int POINTS_SAVE_INTERVAL_TICKS = 20;
    private static final double PROGRESS_VIEW_RADIUS = 32.0;
    /** 射线检测距离，与采集会话判定保持一致。 */
    private static final int MINING_TARGET_RANGE = 8;
    /**
     * 挖掘疲劳等级。原版公式里 {@code amplifier >= 3} 就已经是最高档
     * （挖掘速度 × 0.00081，石头从 0.4 秒变成约 19 分钟），4 等价但更直观。
     */
    private static final int MINING_FATIGUE_AMPLIFIER = 4;
    /** 疲劳持续时间：每 tick 续期，所以只要 1 秒——停手后最多残留这么久。 */
    private static final int MINING_FATIGUE_DURATION_TICKS = 20;
    /** 剩余时间低于这个值才重发效果包，避免每 tick 都发一次。 */
    private static final int MINING_FATIGUE_REFRESH_TICKS = 10;
    /** 连续这么多 tick 没瞄着这个采集点就撤销追踪（BlockDamageAbortEvent 漏发时的兜底）。 */
    private static final long MINING_GRACE_TICKS = 20L;
    private final EarthRPGItem plugin;
    private final RpgItemManager itemManager;
    private final NamespacedKey collectionItemKey;
    private final Map<String, CollectionDefinition> definitions = new HashMap<>();
    private final Map<String, List<CollectionDefinition>> definitionGroups = new HashMap<>();
    private final Map<String, CollectionPoint> points = new HashMap<>();
    private final Map<UUID, HarvestSession> sessions = new HashMap<>();
    private final Map<UUID, Long> lastSeenTicks = new HashMap<>();
    /** 采集点 key -> 破坏动画 breaker id（每点唯一的负数，见 {@link #breakAnimationId(String)}）。 */
    private final Map<String, Integer> breakAnimationIds = new HashMap<>();
    private int nextBreakAnimationId = -1;
    /**
     * 玩家 -> 他此刻正在挖的采集点 key。
     *
     * <p>用来每 tick 纠正客户端。原版客户端会<b>自己预测</b>挖掘：按「方块硬度 ÷ 手里工具」
     * 算进度，够了就在本地把方块删掉并喷破坏粒子。服务端取消 {@code BlockBreakEvent} 并不会
     * 告诉客户端「你猜错了」，所以方块会在客户端一直处于已破坏状态，裂纹也按原版硬度走。
     * 只有持续把方块真实状态与裂纹槽位重发回去，才能做到「方块不破、只显示我们的动画」。
     */
    private final Map<UUID, String> miningTargets = new HashMap<>();
    /** 玩家 -> 上一次「确实还瞄着那个采集点」的 tick（兜底撤销用，见 {@link #guardMiningClients()}）。 */
    private final Map<UUID, Long> lastMiningTicks = new HashMap<>();
    private File pointsFile;
    private long tick;
    private long lastPointsSaveTick;
    private boolean pointsSavePending;

    public CollectionManager(EarthRPGItem plugin) {
        this.plugin = plugin;
        this.itemManager = plugin.getRpgItemManager();
        this.collectionItemKey = new NamespacedKey(plugin, "collection_item");
    }

    public void initialize() {
        File folder = new File(plugin.getDataFolder(), "Collection");
        folder.mkdirs();
        pointsFile = new File(plugin.getDataFolder(), "collection-points.yml");
        createExamples(folder);
        writeDefaultLevelPoints(folder);
        loadDefinitions(folder);
        loadPoints();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void reload() {
        File folder = new File(plugin.getDataFolder(), "Collection");
        folder.mkdirs();
        loadDefinitions(folder);
        loadPoints();
    }

    public List<CollectionDefinition> getDefinitions() {
        return definitions.values().stream().sorted(java.util.Comparator.comparing(CollectionDefinition::id, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    public List<String> getDefinitionGroups() {
        return definitionGroups.keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public List<CollectionDefinition> getDefinitions(String group) {
        return definitionGroups.getOrDefault(group, Collections.emptyList());
    }

    public ItemStack createDisplayItem(CollectionDefinition definition) {
        ItemStack item = new ItemStack(definition.resourceMaterial());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Texts.item(definition.displayName()));
        meta.lore(List.of(Texts.item("&7类型 / Type: &f" + definition.kind().name()), Texts.item("&7放置后创建采集刷新点 / Place to create a point")));
        meta.getPersistentDataContainer().set(collectionItemKey, PersistentDataType.STRING, definition.id());
        item.setItemMeta(meta);
        return item;
    }

    private void loadDefinitions(File folder) {
        definitions.clear();
        definitionGroups.clear();
        File[] files = folder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".txt")
                || name.toLowerCase(Locale.ROOT).endsWith(".yml")
                || name.toLowerCase(Locale.ROOT).endsWith(".yaml"));
        if (files == null) return;
        java.util.Arrays.sort(files, java.util.Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File file : files) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            Set<String> before = new HashSet<>(definitions.keySet());
            ConfigurationSection section = config.getConfigurationSection("collections");
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    ConfigurationSection node = section.getConfigurationSection(key);
                    if (node != null) addDefinition(key, node);
                }
            } else if (config.contains("id")) {
                addDefinition(config.getString("id", file.getName()), config);
            } else {
                for (String key : config.getKeys(false)) {
                    ConfigurationSection node = config.getConfigurationSection(key);
                    if (node != null && (node.contains("material") || node.contains("materials"))) addDefinition(key, node);
                }
            }
            String group = file.getName().replaceFirst("(?i)\\.(txt|ya?ml)$", "");
            List<CollectionDefinition> loaded = definitions.entrySet().stream().filter(entry -> !before.contains(entry.getKey())).map(Map.Entry::getValue).toList();
            if (!loaded.isEmpty()) definitionGroups.put(group, new ArrayList<>(loaded));
        }
        plugin.getLogger().info("Loaded " + definitions.size() + " collection definitions.");
    }

    private void createExamples(File folder) {
        File[] existing = folder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".txt")
                || name.toLowerCase(Locale.ROOT).endsWith(".yml")
                || name.toLowerCase(Locale.ROOT).endsWith(".yaml"));
        if (existing != null && existing.length > 0) return;
        writeExample(new File(folder, "wheat_collection.txt"), """
                id: wheat_collection
                display-name: "&e小麦采集点"
                type: FARMING
                materials:
                  - WHEAT
                item-material: WHEAT_SEEDS
                support-materials:
                  - FARMLAND
                hardness: 0.8
                refresh-seconds: 60
                depleted-material: AIR
                drops:
                  wheat:
                    item: WHEAT
                    amount: 3.0
                    chance: 100
                """);
        writeExample(new File(folder, "meteor_iron_collection.yml"), """
                id: meteor_iron_collection
                display-name: "&b陨铁采集点"
                type: MINING
                materials: [IRON_ORE, DEEPSLATE_IRON_ORE]
                item-material: IRON_ORE
                support-materials: [GRASS_BLOCK, STONE]
                hardness: 2.1
                refresh-seconds: 60
                depleted-material: AIR
                drops:
                  meteor_iron:
                    item: meteor_iron
                    amount: 3.0
                    chance: 100
                  stone:
                    item: COBBLESTONE
                    amount: 1.0
                    chance: 10
                """);
        writeExample(new File(folder, "oak_collection.txt"), """
                id: oak_collection
                display-name: "&6橡木采集点"
                type: FOREST
                materials: [OAK_LOG, OAK_WOOD]
                item-material: OAK_LOG
                support-materials: [DIRT, GRASS_BLOCK]
                hardness: 2.0
                refresh-seconds: 60
                depleted-material: AIR
                drops:
                  oak_log:
                    item: OAK_LOG
                    amount: 7.4
                    chance: 100
                """);
    }

    private void writeExample(File file, String content) {
        try { java.nio.file.Files.writeString(file.toPath(), content, StandardCharsets.UTF_8); }
        catch (IOException exception) { plugin.getLogger().warning("Cannot create collection example: " + file.getName()); }
    }

    /**
     * 把随 jar 打包的「等级资源点整合文件」写入 Collection 文件夹（文件不存在才写）。
     * 这份文件整合了所有带经验 / 等级门槛的资源点模板，已有服务器的 Collection 文件夹
     * 里即使已经有旧示例文件，也会补上这一份（不会覆盖用户改过的文件）。
     */
    private void writeDefaultLevelPoints(File folder) {
        File target = new File(folder, "collection_level_points.yml");
        if (target.exists()) return;
        try {
            plugin.saveResource("Collection/collection_level_points.yml", false);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("无法写入默认等级资源点文件：" + exception.getMessage());
        }
    }

    private void addDefinition(String fallbackId, ConfigurationSection section) {
        String id = section.getString("id", fallbackId);
        Set<Material> resources = readMaterials(section, "materials", section.getString("material", "STONE"));
        Material itemMaterial = material(section.getString("item-material", null), resources.iterator().next());
        Set<Material> supports = readMaterials(section, "support-materials", section.getString("support-material", "AIR"));
        if (resources.isEmpty()) return;
        double hardness = Math.max(0.01, section.getDouble("hardness", 1.0));
        long refreshTicks = Math.max(1L, Math.round(section.getDouble("refresh-seconds", 60) * 20));
        Material depleted = material(section.getString("depleted-material", "AIR"), Material.AIR);
        CollectionKind kind = CollectionKind.parse(section.getString("type", "MINING"));
        // 采集经验 + 等级门槛：这两个字段写在这个资源点自己的 yml 里（经验由资源点编辑，
        // 不再按原版矿物配置）。experience 是该资源点采集成功一次给的经验；required-level
        // 是采集它需要的等级（按 kind 分类判定，玩家等级 + 手持工具加成）。
        double experienceAmount = Math.max(0, section.getDouble("experience", 0));
        int requiredLevel = Math.max(0, section.getInt("required-level", 0));
        List<CollectionDrop> drops = new ArrayList<>();
        ConfigurationSection dropSection = section.getConfigurationSection("drops");
        if (dropSection != null) for (String key : dropSection.getKeys(false)) {
            ConfigurationSection node = dropSection.getConfigurationSection(key);
            if (node == null) continue;
            drops.add(new CollectionDrop(node.getString("item", key), Math.max(0, node.getDouble("amount", 1)), Math.max(0, Math.min(100, node.getDouble("chance", 100)))));
        }
        definitions.put(id.toLowerCase(Locale.ROOT), new CollectionDefinition(id, section.getString("display-name", "&f" + id), resources, itemMaterial, supports, hardness, refreshTicks, depleted, kind, drops, experienceAmount, requiredLevel));
    }

    private Set<Material> readMaterials(ConfigurationSection section, String listKey, String fallback) {
        List<String> values = section.getStringList(listKey);
        if (values.isEmpty() && fallback != null) values = List.of(fallback);
        Set<Material> materials = new LinkedHashSet<>();
        for (String value : values) {
            Material material = material(value, null);
            if (material != null) materials.add(material);
        }
        return materials;
    }

    private Material material(String value, Material fallback) {
        if (value == null) return fallback;
        Material material = Material.matchMaterial(value.trim());
        return material == null ? fallback : material;
    }

    private void loadPoints() {
        points.clear();
        if (pointsFile == null || !pointsFile.exists()) return;
        YamlConfiguration config = YamlStore.load(pointsFile, plugin.getLogger());
        ConfigurationSection section = config.getConfigurationSection("points");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            ConfigurationSection node = section.getConfigurationSection(key);
            if (node == null) continue;
            String worldName = node.getString("world", "");
            World world = plugin.getServer().getWorld(worldName);
            if (world == null) continue;
            String definitionId = node.getString("id", "");
            CollectionDefinition definition = definitions.get(definitionId.toLowerCase(Locale.ROOT));
            Material savedMaterial = material(node.getString("material", "STONE"), definition == null ? Material.STONE : definition.resourceMaterial());
            Material resourceMaterial = definition == null || definition.resourceMaterials().contains(savedMaterial)
                    ? savedMaterial : definition.resourceMaterial();
            int x = node.getInt("x", 0);
            int y = node.getInt("y", 0);
            int z = node.getInt("z", 0);
            CollectionPoint point = new CollectionPoint(worldName, x, y, z, definition == null ? definitionId : definition.id(), resourceMaterial, node.getBoolean("ready", true), node.getLong("refresh-at", 0));
            points.put(pointKey(world.getBlockAt(x, y, z)), point);
        }
    }

    private void savePoints() {
        if (pointsFile == null) return;
        YamlConfiguration config = new YamlConfiguration();
        int index = 0;
        for (CollectionPoint point : points.values()) {
            String path = "points." + index++;
            config.set(path + ".world", point.world());
            config.set(path + ".x", point.x());
            config.set(path + ".y", point.y());
            config.set(path + ".z", point.z());
            config.set(path + ".id", point.definitionId());
            config.set(path + ".material", point.material().name());
            config.set(path + ".ready", point.ready());
            config.set(path + ".refresh-at", point.refreshAt());
        }
        YamlStore.save(config, pointsFile, plugin.getLogger());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlace(BlockPlaceEvent event) {
        String id = collectionId(event.getItemInHand());
        if (id == null) return;
        Player player = event.getPlayer();
        if (!player.getGameMode().equals(org.bukkit.GameMode.CREATIVE)) {
            event.setCancelled(true);
            player.sendMessage(Texts.color("&c采集点只能由创造模式放置。"));
            return;
        }
        CollectionDefinition definition = definitions.get(id.toLowerCase(Locale.ROOT));
        if (definition == null) {
            event.setCancelled(true);
            player.sendMessage(Texts.color("&c采集点配置不存在: " + id));
            return;
        }
        Block block = event.getBlockPlaced();
        block.setType(definition.resourceMaterial(), false);
        CollectionPoint point = new CollectionPoint(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), definition.id(), definition.resourceMaterial(), true, 0);
        points.put(pointKey(block), point);
        savePoints();
        player.sendMessage(Texts.color("&a采集点已成功放置: &f" + definition.displayName()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onManualPlace(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) return;

        CollectionPoint clickedPoint = pointAt(event.getClickedBlock());
        if (clickedPoint != null) {
            CollectionDefinition pointDefinition = definitions.get(clickedPoint.definitionId().toLowerCase(Locale.ROOT));
            boolean leftClick = event.getAction() == org.bukkit.event.block.Action.LEFT_CLICK_BLOCK;
            boolean rightClick = event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK;
            if (leftClick && event.getPlayer().getGameMode().equals(org.bukkit.GameMode.CREATIVE)) {
                event.setCancelled(true);
                event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
                event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
                removePoint(event.getPlayer(), event.getClickedBlock());
                return;
            }
            boolean hasSpeed = pointDefinition != null && collectionSpeed(event.getPlayer(), pointDefinition.kind()) > 0;
            if (pointDefinition != null && (leftClick || rightClick)
                    && (pointDefinition.kind() == CollectionKind.FARMING || (leftClick && !hasSpeed))) {
                event.setCancelled(true);
                event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
                event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
                if (rightClick && pointDefinition.kind() == CollectionKind.FARMING && clickedPoint.ready()) {
                    if (hasSpeed) {
                        startHarvest(event.getPlayer(), event.getClickedBlock(), clickedPoint);
                    } else {
                        clearProgress(event.getClickedBlock(), event.getPlayer());
                    }
                } else if (leftClick) {
                    clearProgress(event.getClickedBlock(), event.getPlayer());
                }
                return;
            }
        }

        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        String id = collectionId(event.getItem());
        if (id == null) return;
        CollectionDefinition definition = definitions.get(id.toLowerCase(Locale.ROOT));
        if (definition == null || definition.resourceMaterial().isSolid()) return;

        Player player = event.getPlayer();
        event.setCancelled(true);
        if (!player.getGameMode().equals(org.bukkit.GameMode.CREATIVE)) {
            player.sendMessage(Texts.color("&c采集点只能由创造模式放置。"));
            return;
        }
        Block supportBlock = event.getClickedBlock();
        Block block = supportBlock.getRelative(event.getBlockFace());
        if (!block.getType().isAir() && !block.isReplaceable()) {
            player.sendMessage(Texts.color("&c采集点无法放置: 目标位置已有方块。"));
            return;
        }
        CollectionPoint point = new CollectionPoint(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), definition.id(), definition.resourceMaterial(), true, 0);
        points.put(pointKey(block), point);
        block.setType(definition.resourceMaterial(), false);
        savePoints();
        player.sendMessage(Texts.color("&a采集点已成功放置: &f" + definition.displayName()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDamage(BlockDamageEvent event) {
        CollectionPoint point = pointAt(event.getBlock());
        if (point == null) {
            // 玩家开始挖的不是采集点：撤销追踪，否则会把上一个采集点的方块一直按回去，
            // 而且挖掘疲劳会一直续期（玩家就再也挖不动普通方块了）。
            stopMiningTracking(event.getPlayer().getUniqueId());
            return;
        }
        // 记下「他在挖这个点」，tick 里每刻纠正一次客户端。
        miningTargets.put(event.getPlayer().getUniqueId(), pointKey(event.getBlock()));
        event.setCancelled(true);
        if (!point.ready()) return;
        CollectionDefinition definition = definitions.get(point.definitionId().toLowerCase(Locale.ROOT));
        if (definition != null && definition.kind() == CollectionKind.FARMING) {
            clearProgress(event.getBlock(), event.getPlayer());
            return;
        }
        if (definition == null || collectionSpeed(event.getPlayer(), definition.kind()) <= 0) {
            Player player = event.getPlayer();
            Block block = event.getBlock();
            clearProgress(block, player);
            // 客户端收到方块包后还会再画一帧本地预测，所以下一 tick 补擦一次。
            Bukkit.getScheduler().runTask(plugin, () -> clearProgress(block, player));
            return;
        }
        startHarvest(event.getPlayer(), event.getBlock(), point);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBreak(BlockBreakEvent event) {
        CollectionPoint point = pointAt(event.getBlock());
        if (point == null) {
            if (pointAt(event.getBlock().getRelative(0, 1, 0)) != null) event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
        // 瞬间挖穿（工具够快时 BlockDamageEvent 那条路不一定走过）也要记下追踪，
        // 否则客户端本地预测照样会把方块删掉而我们不知道要纠正。
        miningTargets.put(event.getPlayer().getUniqueId(), pointKey(event.getBlock()));
        CollectionDefinition definition = definitions.get(point.definitionId().toLowerCase(Locale.ROOT));
        if (event.getPlayer().getGameMode().equals(org.bukkit.GameMode.CREATIVE)) {
            removePoint(event.getPlayer(), event.getBlock());
            return;
        }
        if (definition != null && definition.kind() == CollectionKind.FARMING) {
            clearProgress(event.getBlock(), event.getPlayer());
            return;
        }
        if (point.ready()) startHarvest(event.getPlayer(), event.getBlock(), point);
    }

    private void removePoint(Player player, Block block) {
        String key = pointKey(block);
        if (!points.containsKey(key)) return;
        sessions.values().removeIf(session -> session.pointKey().equals(key));
        block.setType(Material.AIR, false);
        points.remove(key);
        breakAnimationIds.remove(key);
        savePoints();
        player.sendMessage(Texts.color("&a采集点已成功删除。"));
    }

    @EventHandler
    public void onAbort(BlockDamageAbortEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
        lastSeenTicks.remove(event.getPlayer().getUniqueId());
        stopMiningTracking(event.getPlayer().getUniqueId());
        clearProgress(event.getPlayer());
    }

    @EventHandler
    public void onPhysics(BlockPhysicsEvent event) {
        if (nearCollectionPoint(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> pointAt(block) != null || pointAt(block.getRelative(0, 1, 0)) != null)) event.setCancelled(true);
    }

    @EventHandler
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> pointAt(block) != null || pointAt(block.getRelative(0, 1, 0)) != null)) event.setCancelled(true);
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        Bukkit.getScheduler().runTask(plugin, this::loadPoints);
    }

    private void startHarvest(Player player, Block block, CollectionPoint point) {
        CollectionDefinition definition = definitions.get(point.definitionId().toLowerCase(Locale.ROOT));
        if (definition == null) return;
        // 采掘等级门槛由【资源点自己】的 required-level 决定（经验也由资源点配置）。
        // 门槛只比【主手工具的采掘等级】——工具 attributes 里的 MiningLevel /
        // ForestLevel / FarmingLevel，和其他插件的玩家等级无关。没有达标工具 = 采不动。
        GatheringSkillManager gathering = plugin.getGatheringSkillManager();
        if (gathering != null && definition.hasLevelGate()
                && !gathering.canHarvest(player, definition.kind(), definition.requiredLevel())) {
            gathering.deny(player, definition.kind(), definition.requiredLevel(),
                    gathering.toolLevel(player, definition.kind()));
            clearProgress(block, player);
            return;
        }
        double speed = collectionSpeed(player, definition.kind());
        if (speed <= 0) return;
        String key = pointKey(block);
        // 采集所需 tick：硬度 × 20（硬度 1 ≈ 1 秒基准），速度是百分比加成（speed 20 = +20%）。
        // 这样硬度永远有明显作用：硬度翻倍时间翻倍，不会因为速度大就四舍五入成 0 而秒挖。
        long required = Math.max(1L, Math.round(definition.hardness() * 20.0 / (1 + speed / 100.0)));
        sessions.put(player.getUniqueId(), new HarvestSession(player.getUniqueId(), key, tick, required));
        lastSeenTicks.put(player.getUniqueId(), tick);
    }

    /**
     * 每 tick 把「玩家正在挖的采集点」在客户端上按回真实状态。
     *
     * <p>解决的问题全部来自原版客户端的<b>本地预测</b>——服务端取消事件是拦不住它的：
     * <ol>
     *   <li><b>方块被挖掉</b>：客户端按「方块硬度 ÷ 手里工具」自己算进度，够了就在本地把
     *       方块删掉并喷破坏粒子。我们取消了 {@code BlockBreakEvent}，但服务端「什么都没发生」，
     *       所以它<b>不会</b>发任何纠正包——方块就在客户端一直是空气，直到我们主动重发一次
     *       方块状态。重发即可把它按回去。</li>
     *   <li><b>裂纹速度不对</b>：客户端把自己的预测进度写在「玩家自己实体 id」的槽位里，
     *       和我们用的假 breaker id 是<b>两个</b>条目，两个都会画，谁进度高看起来就是谁。
     *       重发方块 + 擦掉它那个槽位，剩下的就只有我们的动画。</li>
     * </ol>
     *
     * <p>顺序很重要：先重发方块、再擦本地预测槽；我们自己的进度由采集循环在这之后发，
     * 所以不会被这一步擦掉。只给挖掘者本人发包——附近其他玩家看到的本来就是服务端进度。
     *
     * <p>同一个循环里还负责上<b>挖掘疲劳</b>（{@code features.collection-mining-fatigue}）。
     * 这是比「事后纠正」更根本的一层：疲劳让客户端<b>自己</b>算不出「挖穿了」，
     * 于是它压根不会删方块，也就不存在要按回去的问题。
     */
    private void guardMiningClients() {
        if (miningTargets.isEmpty()) return;
        boolean guard = plugin.isFeatureEnabled("collection-client-guard");
        boolean fatigue = plugin.isFeatureEnabled("collection-mining-fatigue");
        if (!guard && !fatigue) return;
        // 迭代器：条目会因为「点被删了 / 玩家下线 / 不再挖了」在循环里被清掉。
        var iterator = miningTargets.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Player player = plugin.getServer().getPlayer(entry.getKey());
            Block block = blockOf(entry.getValue());
            if (player == null || block == null) {
                iterator.remove();
                lastMiningTicks.remove(entry.getKey());
                continue;
            }
            // 兜底：BlockDamageAbortEvent 万一漏发，疲劳就会一直续期下去（玩家永远挖不动）。
            // 所以自己再核一次「他还瞄着这个点吗」，连续 20 tick 没瞄着就撤销追踪。
            // 给宽限是因为射线检测在贴身 / 极扁方块上会瞬时不中，不能一帧不中就撤销。
            Block target = player.getTargetBlockExact(MINING_TARGET_RANGE);
            if (target != null && target.equals(block)) {
                lastMiningTicks.put(player.getUniqueId(), tick);
            } else {
                Long last = lastMiningTicks.putIfAbsent(player.getUniqueId(), tick);
                if (last != null && tick - last > MINING_GRACE_TICKS) {
                    iterator.remove();
                    lastMiningTicks.remove(player.getUniqueId());
                    continue;
                }
            }            if (guard) {
                player.sendBlockChange(block.getLocation(), block.getBlockData());
                player.sendBlockDamage(block.getLocation(), 0F);
            }
            if (fatigue) applyMiningFatigue(player);
        }
    }

    /**
     * 给正在挖采集点的玩家续一次挖掘疲劳。
     *
     * <p>原版客户端在本地预测挖掘时会套用挖掘疲劳：{@code amplifier >= 3} 时挖掘速度乘
     * <b>0.00081</b>，石头从 0.4 秒变成约 19 分钟。客户端永远等不到那一刻，也就不会在本地
     * 删掉方块——这正是「方块不破、只显示我们的动画」最干净的实现方式。
     *
     * <p>效果参数：{@code ambient=false, particles=false, icon=false}，玩家看不到粒子与
     * HUD 图标。持续时间只有 1 秒且每 tick 续期，所以停手后最多残留 1 秒就自己消失
     * ——不主动 {@code removePotionEffect}，避免误删别的插件给的同名效果。
     *
     * <p>已经有效果、且强度不低于我们、剩余时间还够，就跳过（省包）。
     * 别的插件给了<b>更强</b>的疲劳时我们直接让位，不会把它顶掉。
     */
    private void applyMiningFatigue(Player player) {
        PotionEffect current = player.getPotionEffect(PotionEffectType.MINING_FATIGUE);
        if (current != null && current.getAmplifier() >= MINING_FATIGUE_AMPLIFIER
                && current.getDuration() > MINING_FATIGUE_REFRESH_TICKS) {
            return;
        }
        player.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE,
                MINING_FATIGUE_DURATION_TICKS, MINING_FATIGUE_AMPLIFIER, false, false, false));
    }

    /**
     * 撤销「客户端纠正 + 挖掘疲劳」的追踪。
     *
     * <p>两张表必须<b>一起</b>清：只清 {@code miningTargets} 的话 {@code lastMiningTicks}
     * 会留下陈旧条目，而这个循环是靠它判断「还瞄着吗」，条目错乱会让疲劳多续几轮。
     */
    private void stopMiningTracking(UUID playerId) {
        miningTargets.remove(playerId);
        lastMiningTicks.remove(playerId);
    }

    /** 按采集点 key 取回当前方块（点被删除或世界未加载时返回 null）。 */
    private Block blockOf(String pointKey) {
        CollectionPoint point = points.get(pointKey);
        if (point == null) return null;
        World world = plugin.getServer().getWorld(point.world());
        return world == null ? null : world.getBlockAt(point.x(), point.y(), point.z());
    }

    private void tick() {
        tick++;

        // 先纠正客户端，再由下面的采集循环发我们自己的裂纹进度。
        guardMiningClients();

        // Time-critical: bring depleted points back the moment their timer elapses.
        for (CollectionPoint point : points.values()) {
            if (point.ready() || point.refreshAt() > System.currentTimeMillis()) continue;
            World world = plugin.getServer().getWorld(point.world());
            if (world == null) continue;
            point.restore();
            world.getBlockAt(point.x(), point.y(), point.z()).setType(point.material(), false);
            pointsSavePending = true;
        }

        // Slower sweep: re-assert the expected block so a broken or replaced one is repaired,
        // without reading every point's block twenty times per second.
        if (tick % ENFORCE_INTERVAL_TICKS == 0) {
            for (CollectionPoint point : points.values()) {
                World world = plugin.getServer().getWorld(point.world());
                if (world == null) continue;
                CollectionDefinition definition = definitions.get(point.definitionId().toLowerCase(Locale.ROOT));
                if (definition == null) continue;
                Material expected = point.ready() ? point.material() : definition.depletedMaterial();
                Block block = world.getBlockAt(point.x(), point.y(), point.z());
                if (block.getType() != expected) block.setType(expected, false);
            }
        }

        for (HarvestSession session : new ArrayList<>(sessions.values())) {
            Player player = plugin.getServer().getPlayer(session.player());
            CollectionPoint point = points.get(session.pointKey());
            if (player == null || point == null || !point.ready()) { removeSession(session); continue; }
            World world = plugin.getServer().getWorld(point.world());
            Block block = world == null ? null : world.getBlockAt(point.x(), point.y(), point.z());
            Block target = player.getTargetBlockExact(8);
            if (block == null || target == null || !target.equals(block)) {
                if (tick - lastSeenTicks.getOrDefault(session.player(), tick) > 20) removeSession(session);
                continue;
            }
            lastSeenTicks.put(session.player(), tick);
            float progress = Math.min(1F, (tick - session.startedAt()) / (float) session.requiredTicks());
            // 破坏动画可以关（features.collection-break-animation），但「清进度」不受它影响：
            // 关掉动画后仍然要能把客户端上残留的裂纹擦掉。
            if (plugin.isFeatureEnabled("collection-break-animation")) {
                sendProgress(block.getLocation(), progress, breakAnimationId(session.pointKey()));
            }
            if (progress >= 1F) completeHarvest(player, block, point);
        }

        // Refresh writes are coalesced: at most one save per second instead of one per point.
        if (pointsSavePending && tick - lastPointsSaveTick >= POINTS_SAVE_INTERVAL_TICKS) {
            pointsSavePending = false;
            lastPointsSaveTick = tick;
            savePoints();
        }
    }

    /**
     * 破坏纹理（裂纹）动画的 breaker id。
     * <p>
     * <b>不能用玩家自己的实体 id</b>：Paper 的 {@code sendBlockDamage(loc, progress)} 单参重载
     * 用的就是「这个查看者自己的实体 id」，而原版客户端把「玩家自己按住左键挖矿」的本地预测
     * 进度也写在同一个 id 的槽位里，每 tick 覆盖一次。结果是：服务端按采集速度算好的进度
     * （硬度 × 20 ÷ 速度）根本显示不出来，玩家看到的是原版硬度的速度，甚至一闪就没。
     * <p>
     * 这里给每个采集点分配一个<b>唯一的负数</b> id：实体 id 不会为负，所以永远不会和任何玩家 /
     * 实体撞车，服务端动画也就不会被客户端本地预测覆盖。必须每点唯一——客户端一个 breaker id
     * 只记一个方块，两个点共用一个 id 会互相顶掉裂纹。
     */
    private int breakAnimationId(String pointKey) {
        Integer existing = breakAnimationIds.get(pointKey);
        if (existing != null) return existing;
        int id = nextBreakAnimationId--;
        breakAnimationIds.put(pointKey, id);
        return id;
    }

    /** Progress packets only matter to players close enough to see the block. */
    private void sendProgress(Location location, float progress, int breakerId) {
        World world = location.getWorld();
        if (world == null) return;
        double radiusSquared = PROGRESS_VIEW_RADIUS * PROGRESS_VIEW_RADIUS;
        for (Player viewer : world.getPlayers()) {
            if (viewer.getLocation().distanceSquared(location) > radiusSquared) continue;
            viewer.sendBlockDamage(location, progress, breakerId);
        }
    }

    /** 该查看者是否近到能看见这个方块的破坏动画（跨世界直接 false，避免 distanceSquared 抛错）。 */
    private static boolean withinProgressView(Player viewer, Location location) {
        if (viewer.getWorld() != location.getWorld()) return false;
        return viewer.getLocation().distanceSquared(location) <= PROGRESS_VIEW_RADIUS * PROGRESS_VIEW_RADIUS;
    }

    private void completeHarvest(Player player, Block block, CollectionPoint point) {
        CollectionDefinition definition = definitions.get(point.definitionId().toLowerCase(Locale.ROOT));
        if (definition == null || !point.ready()) return;
        point.deplete(System.currentTimeMillis() + definition.refreshTicks() * 50L);
        block.setType(definition.depletedMaterial(), false);
        for (UUID uuid : new HashSet<>(sessions.keySet())) {
            HarvestSession session = sessions.get(uuid);
            if (session != null && session.pointKey().equals(pointKey(block))) removeSession(session);
        }
        giveDrops(player, definition);
        // 采集经验由【资源点自己】的 experience 字段决定（按资源点编辑，不是按原版矿物）。
        // 采集点走的是「取消破坏 + 自己计时」，经验在这里发，只发一次。
        GatheringSkillManager gathering = plugin.getGatheringSkillManager();
        if (gathering != null) gathering.awardCollectionPointExperience(player,
                definition.kind(), definition.experienceAmount(), definition.resourceMaterial());
        savePoints();
    }

    private void giveDrops(Player player, CollectionDefinition definition) {
        double fortune = collectionFortune(player, definition.kind());
        for (CollectionDrop drop : definition.drops()) {
            if (ThreadLocalRandom.current().nextDouble(100) >= drop.chance()) continue;
            double multiplier = 1 + Math.floor(Math.max(0, fortune) / 100.0);
            double remainder = Math.max(0, fortune) % 100.0;
            if (ThreadLocalRandom.current().nextDouble(100) < remainder) multiplier += 1;
            double quantity = drop.amount() * multiplier;
            int amount = (int) Math.floor(quantity);
            if (ThreadLocalRandom.current().nextDouble() < quantity - amount) amount++;
            giveDrop(player, drop.item(), amount);
        }
    }

    private void giveDrop(Player player, String itemName, int amount) {
        RpgItem rpgItem = itemManager.findById(itemName);
        Material material = Material.matchMaterial(itemName);
        if (rpgItem == null && material == null) return;
        while (amount > 0) {
            int stackAmount = Math.min(64, amount);
            ItemStack item = rpgItem == null ? new ItemStack(material, stackAmount) : itemManager.createItem(rpgItem);
            item.setAmount(stackAmount);
            player.getInventory().addItem(item);
            amount -= stackAmount;
        }
    }

    private double collectionSpeed(Player player, CollectionKind kind) {
        if (!plugin.isFeatureEnabled("collection")) return 0;
        double value = 0;
        for (RpgItem item : itemManager.collectActiveItems(player, false)) {
            value += switch (kind) {
                case FARMING -> plugin.isFeatureEnabled("farming-speed") ? item.farmingSpeed() : 0;
                case MINING -> plugin.isFeatureEnabled("mining-speed") ? item.miningSpeed() : 0;
                case FOREST -> plugin.isFeatureEnabled("forest-speed") ? item.forestSpeed() : 0;
            };
        }
        return value;
    }

    private double collectionFortune(Player player, CollectionKind kind) {
        if (!plugin.isFeatureEnabled("collection")) return 0;
        double value = 0;
        for (RpgItem item : itemManager.collectActiveItems(player, false)) {
            value += switch (kind) {
                case FARMING -> plugin.isFeatureEnabled("farming-fortune") ? item.farmingFortune() : 0;
                case MINING -> plugin.isFeatureEnabled("mining-fortune") ? item.miningFortune() : 0;
                case FOREST -> plugin.isFeatureEnabled("forest-fortune") ? item.forestFortune() : 0;
            };
        }
        return value;
    }

    public CollectionStats collectActiveStats(Player player) {
        CollectionStats stats = CollectionStats.empty();
        for (RpgItem item : itemManager.collectActiveItems(player, false)) {
            stats = stats.add(CollectionStats.from(item, Map.of()));
        }
        return stats;
    }

    public int refreshWorld(World world) {
        if (world == null) return 0;
        Set<String> worldPointKeys = new HashSet<>();
        int refreshed = 0;
        for (CollectionPoint point : points.values()) {
            if (!point.world().equals(world.getName())) continue;
            point.restore();
            world.getBlockAt(point.x(), point.y(), point.z()).setType(point.material(), false);
            worldPointKeys.add(pointKey(world.getBlockAt(point.x(), point.y(), point.z())));
            refreshed++;
        }
        sessions.values().removeIf(session -> worldPointKeys.contains(session.pointKey()));
        if (refreshed > 0) {
            savePoints();
            for (Player player : Bukkit.getOnlinePlayers()) clearProgress(player);
        }
        return refreshed;
    }

    public int refreshAllWorlds() {
        int refreshed = 0;
        for (World world : Bukkit.getWorlds()) refreshed += refreshWorld(world);
        return refreshed;
    }

    private void removeSession(HarvestSession session) {
        sessions.remove(session.player());
        lastSeenTicks.remove(session.player());
        Player player = plugin.getServer().getPlayer(session.player());
        if (player != null) clearProgress(player);
    }

    /**
     * 擦掉这个玩家客户端上所有采集点的破坏进度。两个槽位都要擦：
     * <ul>
     *   <li>插件动画用的假 breaker id（见 {@link #breakAnimationId(String)}）；</li>
     *   <li>玩家自己的实体 id —— 原版客户端的本地预测就写在这个槽位，没有采集速度属性 /
     *       门槛不达标时玩家仍会看到原版破坏进度，2.5 的修复就是靠擦这个槽位。</li>
     * </ul>
     * 只发给看得见的采集点：原来的写法不看距离，100 个点就是 100 个包。
     */
    private void clearProgress(Player player) {
        for (CollectionPoint point : points.values()) {
            World world = plugin.getServer().getWorld(point.world());
            if (world == null) continue;
            Block block = world.getBlockAt(point.x(), point.y(), point.z());
            if (!withinProgressView(player, block.getLocation())) continue;
            player.sendBlockDamage(block.getLocation(), 0F, breakAnimationId(pointKey(block)));
            player.sendBlockDamage(block.getLocation(), 0F);
        }
    }

    private boolean nearCollectionPoint(Block block) {
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (pointAt(block.getRelative(x, y, z)) != null) return true;
                }
            }
        }
        return false;
    }

    /**
     * 擦掉这个方块上的破坏进度：向附近玩家清插件动画槽，并额外清一次「操作者自己」的槽位
     * ——原版本地预测那个槽（玩家没速度属性 / 门槛不达标时，客户端会一直显示原版破坏进度）。
     */
    private void clearProgress(Block block, Player player) {
        sendProgress(block.getLocation(), 0F, breakAnimationId(pointKey(block)));
        if (player != null) player.sendBlockDamage(block.getLocation(), 0F);
    }

    private String collectionId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(collectionItemKey, PersistentDataType.STRING);
    }

    private CollectionPoint pointAt(Block block) {
        return points.get(pointKey(block));
    }

    private String pointKey(Block block) {
        return block.getWorld().getName() + "|" + block.getX() + "|" + block.getY() + "|" + block.getZ();
    }
}
