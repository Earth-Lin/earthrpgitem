package EarthRPGItem.accessory;

import EarthRPGItem.EarthRPGItem;
import EarthRPGItem.collection.CollectionStats;
import EarthRPGItem.items.RpgItem;
import EarthRPGItem.items.RpgItemType;
import EarthRPGItem.items.RpgStats;
import EarthRPGItem.util.GuiSounds;
import EarthRPGItem.util.Texts;
import EarthRPGItem.util.YamlStore;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 饰品栏。
 *
 * <p>界面按「人体站立时佩戴位置」排布：耳饰在头两侧、项链在颈、手饰在身体两侧、胸饰居中、
 * 戒指在手上、披风在背后、腰饰在腰。空槽用<b>白色玻璃板</b>占位并写清可放入的类型，
 * 放入饰品后该槽直接显示饰品本体。
 *
 * <p>槽位交互全部由本类手动接管（事件一律取消后再自行搬运），因为占位用的白色玻璃板
 * 本身是真实物品：交给原版交换逻辑会出现「玻璃板被玩家拿走」或「玻璃板被当成饰品存进存档」。
 */
public class AccessoryManager implements Listener {

    public static final int SIZE = 54;

    /**
     * 饰品槽的人体布局（行 0 = 顶部，行 5 = 腰部；槽号 = 行 * 9 + 列）。
     *
     * <pre>
     * 列     0    1    2    3    4    5    6    7    8
     * 行1     ·   耳    ·    ·    ·    ·    ·   耳    ·     耳饰 10 / 16
     * 行2     ·    ·    ·    ·   项    ·    ·    ·    ·     项链 22
     * 行3    手    ·    ·    ·   胸    ·    ·    ·   手     手饰 27 / 35，胸饰 31
     * 行4     ·   戒    ·    ·   披    ·    ·   戒    ·     戒指 37 / 43，披风 40
     * 行5     ·    ·    ·    ·   腰    ·    ·    ·    ·     腰饰 49
     * </pre>
     */
    private static final Map<Integer, String> SLOT_TYPES = Map.ofEntries(
            Map.entry(10, "earring"), Map.entry(16, "earring"),
            Map.entry(22, "necklace"),
            Map.entry(27, "hand"), Map.entry(35, "hand"),
            Map.entry(31, "chest"),
            Map.entry(37, "ring"), Map.entry(43, "ring"),
            Map.entry(40, "cloak"),
            Map.entry(49, "belt")
    );

    /** 旧版（3.2bate 之前）的槽位表，仅用于迁移时识别「这个槽原本是干什么的」。 */
    private static final Map<Integer, String> LEGACY_SLOT_TYPES = Map.ofEntries(
            Map.entry(10, "ring"), Map.entry(11, "ring"),
            Map.entry(14, "earring"), Map.entry(15, "earring"),
            Map.entry(19, "chest"), Map.entry(22, "hand"),
            Map.entry(23, "hand"), Map.entry(28, "necklace"),
            Map.entry(31, "belt"), Map.entry(34, "cloak")
    );

    /**
     * 类型展示顺序：从上到下（头 → 颈 → 手 → 胸 → 手 → 背 → 腰）。
     * <p>
     * <b>必须声明在 {@link #TYPE_SLOTS} 之前</b>：静态字段按书写顺序初始化，
     * {@code TYPE_SLOTS = buildTypeSlots()} 会读它；写在后面会让它在初始化时仍是 null，
     * 类初始化直接抛 {@code ExceptionInInitializerError}，整个插件起不来。
     */
    private static final List<String> TYPE_ORDER = List.of(
            "earring", "necklace", "hand", "chest", "ring", "cloak", "belt");

    /** 每种类型在界面里的槽位，顺序固定，用于迁移时稳定地「填第一个空位」。 */
    private static final Map<String, List<Integer>> TYPE_SLOTS = buildTypeSlots();

    private static final Map<String, String> FALLBACK_LABELS = Map.of(
            "ring", "戒指", "earring", "耳饰", "chest", "胸饰",
            "hand", "手饰", "necklace", "项链", "belt", "腰饰", "cloak", "披风"
    );

    /**
     * 用于还原历史乱码的 GBK 字符集；JVM 没带这个字符集时为 null（此时直接走内置中文名）。
     */
    private static final Charset GBK = resolveGbk();

    /** 迁移孤儿饰品的存档根键（前缀 .. 保证不会和玩家 UUID 键冲突）。 */
    private static final String ORPHAN_ROOT = "..orphans.";

    private final EarthRPGItem plugin;
    private final File dataFile;
    private final YamlConfiguration data;
    private final Map<UUID, ItemStack[]> cache = new HashMap<>();

    /** 迁移时找不到合适槽位的饰品，等玩家在线时归还，避免直接丢物品。 */
    private final Map<UUID, List<ItemStack>> pendingReturns = new HashMap<>();

    private final NamespacedKey placeholderKey;

    public AccessoryManager(EarthRPGItem plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "accessories.yml");
        this.data = YamlStore.load(dataFile, plugin.getLogger());
        this.placeholderKey = new NamespacedKey(plugin, "accessory_slot_placeholder");
    }

    private static Map<String, List<Integer>> buildTypeSlots() {
        Map<String, List<Integer>> map = new LinkedHashMap<>();
        for (String type : TYPE_ORDER) map.put(type, new ArrayList<>());
        List<Integer> slots = new ArrayList<>(SLOT_TYPES.keySet());
        slots.sort(Integer::compareTo);
        for (int slot : slots) map.get(SLOT_TYPES.get(slot)).add(slot);
        return map;
    }

    private static Charset resolveGbk() {
        try {
            return Charset.forName("GBK");
        } catch (RuntimeException exception) {
            // 极少数裁剪过的 JRE 不带 GBK；此时没有可靠的还原依据，直接走内置中文名。
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 打开界面
    // ------------------------------------------------------------------

    /** 玩家打开自己的饰品栏。 */
    public void open(Player player) {
        open(player, player);
    }

    /**
     * 打开某个玩家的饰品栏。
     *
     * @param viewer 正在看界面的人（决定标题）
     * @param owner  饰品栏属于谁；属性结算、灵魂绑定校验、关闭时持久化都用这个玩家
     */
    public void open(Player viewer, Player owner) {
        boolean self = viewer.getUniqueId().equals(owner.getUniqueId());
        String title = self ? "&8饰品栏" : "&8饰品栏 &7- &f" + owner.getName();
        Inventory inventory = Bukkit.createInventory(
                new AccessoryMenuHolder(owner.getUniqueId()), SIZE, Texts.color(title));
        fillBackground(inventory);

        ItemStack[] stored = getContents(owner.getUniqueId());
        for (Map.Entry<Integer, String> entry : SLOT_TYPES.entrySet()) {
            ItemStack itemStack = stored[entry.getKey()];
            if (!isEmpty(itemStack)) inventory.setItem(entry.getKey(), itemStack.clone());
        }
        givePendingReturns(owner);
        viewer.openInventory(inventory);
    }

    /** 白色玻璃板占位：空槽显示类型名，其余格子是黑色玻璃板。 */
    private void fillBackground(Inventory inventory) {
        ItemStack background = plainPane(Material.BLACK_STAINED_GLASS_PANE);
        for (int slot = 0; slot < SIZE; slot++) inventory.setItem(slot, background);
        for (Map.Entry<Integer, String> entry : SLOT_TYPES.entrySet()) {
            inventory.setItem(entry.getKey(), placeholder(entry.getValue()));
        }
    }

    private ItemStack placeholder(String type) {
        String label = label(type);
        ItemStack itemStack = new ItemStack(Material.WHITE_STAINED_GLASS_PANE);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(Texts.color("&f" + label));
            meta.lore(List.of(
                    Texts.color("&7这一格放&f" + label + "&7类饰品"),
                    Texts.color("&7放进来后这一格会显示饰品本体")
            ));
            meta.getPersistentDataContainer().set(placeholderKey, PersistentDataType.INTEGER, 1);
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    private ItemStack plainPane(Material material) {
        ItemStack itemStack = new ItemStack(material);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(Texts.color("&8 "));
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    /**
     * 槽位显示名。
     *
     * <p>顺序：config 的值 → 乱码还原后的 config 值 → 内置中文名。
     *
     * <p>这里<b>不能</b>靠「是不是可打印字符」来判断乱码：旧版 config.yml 里的
     * {@code ring: "鎴掓寚 Ring"} 每个字都是<b>合法汉字</b>（U+4E00 区），
     * 既不是替换符也不在私用区，任何字符范围检查都会放行它。
     * 真正的判据是「这串字符的 GBK 字节恰好是合法 UTF-8」——见 {@link #repairMojibake(String)}。
     */
    private String label(String type) {
        if (type == null) return "";
        String configured = plugin.getConfig().getString("accessory.types." + type, "");
        if (configured != null) {
            String repaired = repairMojibake(configured.trim());
            if (isUsable(repaired)) return repaired;
        }
        return FALLBACK_LABELS.getOrDefault(type, type);
    }

    /**
     * 还原「UTF-8 字节被按 GBK 解读」造成的乱码。
     *
     * <p>原理：中文经 UTF-8 编码后是 3 字节，被当成 GBK 双字节读就变成 1.5 个汉字，
     * 例如 {@code 戒指} → {@code 鎴掓寚}。反向操作（按 GBK 取回原字节，再按 UTF-8 解码）
     * 就能还原。
     *
     * <p>判据很严格：只有「按 GBK 取回的字节是<b>合法</b> UTF-8 序列（不产生替换符）」
     * 才认为确实是这种乱码。正常中文的 GBK 字节首字节落在 0x81–0xBF，
     * 是 UTF-8 的续字节区间，必然解码失败 → 原样返回，不会被误改。
     *
     * @return 还原后的文本；不是这种乱码时原样返回 {@code value}
     */
    static String repairMojibake(String value) {
        if (value == null || value.isEmpty() || GBK == null) return value;
        byte[] bytes;
        try {
            bytes = value.getBytes(GBK);
        } catch (RuntimeException exception) {
            return value;
        }
        String candidate = new String(bytes, StandardCharsets.UTF_8);
        // 出现替换符 = 这些字节不是合法 UTF-8，说明原值本来就是正常文本，别动它。
        if (candidate.indexOf('\uFFFD') >= 0) return value;
        // 含 GBK 无法表示的字符（如 emoji）时会被编码成 '?'，那种情况也不能拿去做候选值。
        if (containsUnmappable(value, bytes)) return value;
        return candidate;
    }

    /** 编码后再解码是否丢了字符（emoji 等 GBK 外字符会被压成 '?'）。 */
    private static boolean containsUnmappable(String value, byte[] bytes) {
        return !new String(bytes, GBK).equals(value);
    }

    /** 结果是否可以直接显示：非空、无替换符、无控制字符、无私用区字符。 */
    private static boolean isUsable(String value) {
        if (value == null || value.isBlank()) return false;
        for (char c : value.toCharArray()) {
            if (c == '\uFFFD' || Character.isISOControl(c)) return false;
            if (c >= '\uE000' && c <= '\uF8FF') return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 属性结算
    // ------------------------------------------------------------------

    public RpgStats collectStats(Player player) {
        if (!plugin.isFeatureEnabled("accessory")) return RpgStats.empty();
        RpgStats stats = RpgStats.empty();
        ItemStack[] stored = getContents(player.getUniqueId());
        boolean changed = updateOutdatedItems(stored);
        for (int slot : SLOT_TYPES.keySet()) {
            RpgItem item = plugin.getRpgItemManager().findItem(stored[slot]);
            if (item != null && item.type() == RpgItemType.ACCESSORY && matchesSlot(item, SLOT_TYPES.get(slot))) {
                stats = stats.addAccessoryStats(plugin.getRpgItemManager().collectItemStats(stored[slot], item));
            }
        }
        if (changed) saveContents(player.getUniqueId(), stored);
        return stats;
    }

    public CollectionStats collectCollectionStats(Player player) {
        if (!plugin.isFeatureEnabled("accessory")) return CollectionStats.empty();
        CollectionStats stats = CollectionStats.empty();
        ItemStack[] stored = getContents(player.getUniqueId());
        boolean changed = updateOutdatedItems(stored);
        for (int slot : SLOT_TYPES.keySet()) {
            RpgItem item = plugin.getRpgItemManager().findItem(stored[slot]);
            if (item != null && item.type() == RpgItemType.ACCESSORY && matchesSlot(item, SLOT_TYPES.get(slot))) {
                stats = stats.add(plugin.getRpgItemManager().collectItemCollectionStats(stored[slot], item));
            }
        }
        if (changed) saveContents(player.getUniqueId(), stored);
        return stats;
    }

    public void updateOutdatedItems(Player player) {
        ItemStack[] stored = getContents(player.getUniqueId());
        if (updateOutdatedItems(stored)) saveContents(player.getUniqueId(), stored);
    }

    private boolean updateOutdatedItems(ItemStack[] stored) {
        boolean changed = false;
        for (int slot : SLOT_TYPES.keySet()) {
            if (plugin.getRpgItemManager().updateOutdatedItem(stored[slot])) changed = true;
        }
        return changed;
    }

    /** 把一件饰品塞进该类型的第一个空槽（/rpgitem puton 用）。 */
    public boolean equipAccessory(Player player, ItemStack itemStack) {
        RpgItem item = plugin.getRpgItemManager().findItem(itemStack);
        if (item == null || item.type() != RpgItemType.ACCESSORY) return false;
        if (!plugin.getRpgItemManager().ensureSoulboundOwner(player, itemStack)) return false;
        ItemStack[] stored = getContents(player.getUniqueId());
        for (int slot : TYPE_SLOTS.getOrDefault(normalize(item.accessoryType()), List.of())) {
            if (isEmpty(stored[slot])) {
                stored[slot] = itemStack.clone();
                saveContents(player.getUniqueId(), stored);
                return true;
            }
        }
        return false;
    }

    /**
     * The cache is keyed by player UUID and used to grow by one ItemStack[54] for every
     * distinct player that ever joined, for the entire uptime of the server. Contents are
     * already written to accessories.yml by saveContents(), so dropping the cached copy on
     * quit is safe: it is reloaded from disk on the next join.
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cache.remove(event.getPlayer().getUniqueId());
        pendingReturns.remove(event.getPlayer().getUniqueId());
    }

    /** Number of players whose accessory contents are currently held in memory. */
    public int cachedPlayerCount() {
        return cache.size();
    }

    // ------------------------------------------------------------------
    // 槽位交互（全部手动接管）
    // ------------------------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof AccessoryMenuHolder holder)) return;
        if (!(event.getWhoClicked() instanceof Player viewer)) return;

        int rawSlot = event.getRawSlot();
        boolean accessorySlot = rawSlot >= 0 && rawSlot < SIZE;

        if (!accessorySlot) {
            // 玩家自己的背包区域：<b>不能一律取消</b>。
            // 取消掉的话饰品永远拿不到光标上，也就拖不到玻璃板里；取下饰品后光标上的物品
            // 同样放不回背包，只能关掉界面——这正是「物品粘在物品栏里」的原因。
            // 所以普通点击完全交给原版，只拦 shift 点击（它会把物品直接推进上面的饰品槽）。
            if (!event.isShiftClick()) return;
            event.setCancelled(true);
            Player owner = Bukkit.getPlayer(holder.getOwner());
            if (owner == null) return;
            ItemStack moving = event.getCurrentItem();
            if (isEmpty(moving) || !isValidAccessory(owner, moving, null)) return;
            // Shift 点击背包里的饰品：放进该类型的第一个空槽。
            for (int slot : TYPE_SLOTS.getOrDefault(normalize(findType(owner, moving)), List.of())) {
                if (isEmpty(event.getView().getTopInventory().getItem(slot))
                        || isPlaceholder(event.getView().getTopInventory().getItem(slot))) {
                    event.getView().getTopInventory().setItem(slot, moving.clone());
                    event.setCurrentItem(null);
                    GuiSounds.place(viewer);
                    viewer.updateInventory();
                    return;
                }
            }
            return;
        }

        // 饰品槽：一律先取消，再由本类决定实际效果。占位白色玻璃板是真实物品，交给原版搬运
        // 会产生「玻璃板被拿走」/「玻璃板被当成饰品存档」这类问题，所以不依赖原版交换逻辑。
        // 取消必须发生在校验之前：主人已离线时也要拦住，否则占位玻璃板会被拿走。
        event.setCancelled(true);
        Player owner = Bukkit.getPlayer(holder.getOwner());
        if (owner == null) return;
        handleAccessorySlot(event, viewer, owner, rawSlot);
    }

    private void handleAccessorySlot(InventoryClickEvent event, Player viewer, Player owner, int slot) {
        String slotType = SLOT_TYPES.get(slot);
        if (slotType == null) return;
        Inventory inventory = event.getView().getTopInventory();
        ItemStack slotItem = inventory.getItem(slot);
        ItemStack cursor = event.getCursor();
        boolean slotIsAccessory = isPlacedAccessory(owner, slotItem, slotType);

        if (event.getClick() == ClickType.NUMBER_KEY || event.getClick() == ClickType.SWAP_OFFHAND) {
            // 数字键 / 副手交换：来源不是光标，验证来源物品后再搬运。
            ItemStack source = hotbarItem(event, viewer);
            if (isEmpty(source)) {
                if (slotIsAccessory) {
                    inventory.setItem(slot, placeholder(slotType));
                    giveOrDrop(viewer, slotItem);
                    GuiSounds.remove(viewer);
                    viewer.updateInventory();
                }
                return;
            }
            if (!isValidAccessory(owner, source, slotType)) return;
            inventory.setItem(slot, source.clone());
            if (slotIsAccessory) giveOrDrop(viewer, slotItem);
            clearHotbarSource(event, viewer);
            GuiSounds.place(viewer);
            viewer.updateInventory();
            return;
        }

        boolean keepOldToCursor = event.getClick() == ClickType.RIGHT || event.getClick() == ClickType.SHIFT_RIGHT;

        if (isEmpty(cursor)) {
            if (!slotIsAccessory) return;
            // 取出：槽位恢复成白色玻璃板，物品进光标（右键取一半则只取一个）。
            if (keepOldToCursor && slotItem.getAmount() > 1) {
                ItemStack one = slotItem.clone();
                one.setAmount(1);
                slotItem.setAmount(slotItem.getAmount() - 1);
                inventory.setItem(slot, slotItem);
                viewer.setItemOnCursor(one);
            } else {
                inventory.setItem(slot, placeholder(slotType));
                viewer.setItemOnCursor(slotItem);
            }
            GuiSounds.remove(viewer);
            viewer.updateInventory();
            return;
        }

        // 光标上有东西：必须是对应类型的合法饰品，右键只放一个。
        if (!isValidAccessory(owner, cursor, slotType)) return;
        if (keepOldToCursor && cursor.getAmount() > 1) {
            ItemStack one = cursor.clone();
            one.setAmount(1);
            if (slotIsAccessory) {
                giveOrDrop(viewer, slotItem);
            }
            inventory.setItem(slot, one);
            cursor.setAmount(cursor.getAmount() - 1);
            viewer.setItemOnCursor(cursor);
            GuiSounds.place(viewer);
            viewer.updateInventory();
            return;
        }
        if (slotIsAccessory) giveOrDrop(viewer, slotItem);
        inventory.setItem(slot, cursor.clone());
        viewer.setItemOnCursor(null);
        GuiSounds.place(viewer);
        viewer.updateInventory();
    }

    /**
     * 拖拽：只有<b>碰到饰品槽</b>时才需要接管。
     *
     * <p>原来这里把整个拖拽事件无条件取消，连玩家在<b>自己背包里</b>拖动都被拦了，
     * 于是「把饰品拖到玻璃板上」根本不可能。现在只取消落在饰品槽上的那部分，
     * 并在松手时按原版习惯往目标槽放一个。
     */
    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof AccessoryMenuHolder holder)) return;
        boolean touchesAccessorySlot = event.getRawSlots().stream().anyMatch(slot -> slot >= 0 && slot < SIZE);
        if (!touchesAccessorySlot) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player viewer)) return;
        Player owner = Bukkit.getPlayer(holder.getOwner());
        if (owner == null) return;
        ItemStack dragged = event.getOldCursor();
        if (isEmpty(dragged)) return;
        // 按槽位升序处理：getRawSlots() 是 Set，迭代顺序不固定，不排序的话
        // 同一个拖拽动作可能落到不同槽位。
        for (int slot : event.getRawSlots().stream().sorted().toList()) {
            if (slot < 0 || slot >= SIZE) continue;
            String slotType = SLOT_TYPES.get(slot);
            if (slotType == null) continue;
            Inventory inventory = event.getView().getTopInventory();
            ItemStack existing = inventory.getItem(slot);
            if (!isEmpty(existing) && !isPlaceholder(existing)) continue;
            if (!isValidAccessory(owner, dragged, slotType)) continue;
            ItemStack one = dragged.clone();
            one.setAmount(1);
            inventory.setItem(slot, one);
            int left = dragged.getAmount() - 1;
            if (left <= 0) {
                viewer.setItemOnCursor(null);
            } else {
                ItemStack remaining = dragged.clone();
                remaining.setAmount(left);
                viewer.setItemOnCursor(remaining);
            }
            GuiSounds.place(viewer);
            viewer.updateInventory();
            return;
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof AccessoryMenuHolder holder)) return;
        if (!(event.getPlayer() instanceof Player)) return;
        Player owner = Bukkit.getPlayer(holder.getOwner());
        if (owner == null) return;
        ItemStack[] stored = new ItemStack[SIZE];
        for (int slot : SLOT_TYPES.keySet()) {
            ItemStack itemStack = event.getInventory().getItem(slot);
            if (isEmpty(itemStack) || isPlaceholder(itemStack)) continue;
            if (isPlacedAccessory(owner, itemStack, SLOT_TYPES.get(slot))) {
                stored[slot] = itemStack;
                continue;
            }
            // 第二道防线：任何不合法的物品退还玩家，绝不写进饰品存档。
            event.getInventory().setItem(slot, null);
            giveOrDrop((Player) event.getPlayer(), itemStack);
        }
        saveContents(holder.getOwner(), stored);
    }

    private ItemStack hotbarItem(InventoryClickEvent event, Player viewer) {
        if (event.getClick() == ClickType.SWAP_OFFHAND) return viewer.getInventory().getItemInOffHand();
        int button = event.getHotbarButton();
        return button < 0 ? viewer.getInventory().getItemInOffHand() : viewer.getInventory().getItem(button);
    }

    private void clearHotbarSource(InventoryClickEvent event, Player viewer) {
        if (event.getClick() == ClickType.SWAP_OFFHAND) {
            viewer.getInventory().setItemInOffHand(null);
            return;
        }
        int button = event.getHotbarButton();
        if (button >= 0) viewer.getInventory().setItem(button, null);
    }

    // ------------------------------------------------------------------
    // 校验
    // ------------------------------------------------------------------

    private boolean isPlaceholder(ItemStack itemStack) {
        if (isEmpty(itemStack) || itemStack.getType() != Material.WHITE_STAINED_GLASS_PANE) return false;
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) return false;
        Integer tag = meta.getPersistentDataContainer().get(placeholderKey, PersistentDataType.INTEGER);
        return tag != null && tag == 1;
    }

    /** 槽里放着的确实是「该槽类型」的合法饰品（不做灵魂绑定写入）。 */
    private boolean isPlacedAccessory(Player owner, ItemStack itemStack, String slotType) {
        RpgItem item = plugin.getRpgItemManager().findItem(itemStack);
        return item != null && item.type() == RpgItemType.ACCESSORY && matchesSlot(item, slotType)
                && !plugin.getRpgItemManager().isSoulboundToOther(owner, itemStack);
    }

    /**
     * 校验并（在需要时）写入灵魂绑定归属。
     *
     * @param slotType 要求的槽位类型；{@code null} 表示只校验「是不是饰品」并读取它的类型
     */
    private boolean isValidAccessory(Player owner, ItemStack itemStack, String slotType) {
        RpgItem item = plugin.getRpgItemManager().findItem(itemStack);
        if (item == null || item.type() != RpgItemType.ACCESSORY) return false;
        if (slotType != null && !matchesSlot(item, slotType)) return false;
        return plugin.getRpgItemManager().ensureSoulboundOwner(owner, itemStack);
    }

    private String findType(Player owner, ItemStack itemStack) {
        RpgItem item = plugin.getRpgItemManager().findItem(itemStack);
        return item == null ? "" : item.accessoryType();
    }

    private boolean matchesSlot(RpgItem item, String slotType) {
        return normalize(item.accessoryType()).equals(normalize(slotType));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // 存储 / 迁移
    // ------------------------------------------------------------------

    private ItemStack[] getContents(UUID uuid) {
        return cache.computeIfAbsent(uuid, this::loadContents);
    }

    /** 某个玩家饰品栏里的物品快照（供指令/调试读取，不触发迁移写盘）。 */
    public Map<Integer, ItemStack> snapshot(UUID uuid) {
        ItemStack[] stored = getContents(uuid);
        Map<Integer, ItemStack> result = new LinkedHashMap<>();
        for (int slot : SLOT_TYPES.keySet()) {
            if (!isEmpty(stored[slot])) result.put(slot, stored[slot].clone());
        }
        return result;
    }

    /**
     * 读取并<b>按饰品类型迁移</b>旧存档。
     *
     * <p>槽位重排后槽号含义变了（旧 31 是腰饰、新 31 是胸饰；旧 22 是手饰、新 22 是项链），
     * 所以绝不能按旧槽号搬运，否则所有饰品都会落到错误类型上。这里只看物品自身声明的
     * {@code accessory-type}，把它放进该类型的第一个空槽：先尝试原位保留（槽号与类型都对得上），
     * 再按固定顺序找空位。迁移是幂等的——迁移完槽号与类型一致，下次读取原样保留。
     */
    private ItemStack[] loadContents(UUID uuid) {
        ItemStack[] stored = new ItemStack[SIZE];
        List<String> lines = data.getStringList(uuid.toString());
        List<Integer> parsedSlots = new ArrayList<>();
        List<ItemStack> parsedItems = new ArrayList<>();
        for (String line : lines) {
            String[] parts = line.split(":", 2);
            if (parts.length != 2) continue;
            try {
                int slot = Integer.parseInt(parts[0]);
                ItemStack itemStack = deserialize(parts[1]);
                if (itemStack == null) continue;
                parsedSlots.add(slot);
                parsedItems.add(itemStack);
            } catch (NumberFormatException ignored) {
            }
        }
        if (parsedItems.isEmpty()) return stored;

        int[] oldSlots = new int[parsedItems.size()];
        String[] declaredTypes = new String[parsedItems.size()];
        for (int index = 0; index < parsedItems.size(); index++) {
            oldSlots[index] = parsedSlots.get(index);
            declaredTypes[index] = declaredType(parsedItems.get(index));
        }
        int[] targets = planMigration(oldSlots, declaredTypes);

        List<ItemStack> orphans = new ArrayList<>();
        boolean dirty = false;
        for (int index = 0; index < parsedItems.size(); index++) {
            int target = targets[index];
            if (target >= 0) {
                stored[target] = parsedItems.get(index);
                if (target != oldSlots[index]) dirty = true;
            } else {
                orphans.add(parsedItems.get(index));
                dirty = true;
                plugin.getLogger().warning("饰品迁移：玩家 " + uuid + " 有一件饰品找不到可放入的槽位"
                        + "（类型 " + (declaredTypes[index].isEmpty() ? "未知" : declaredTypes[index])
                        + "），已改为归还给玩家。");
            }
        }

        for (ItemStack orphan : orphans) pendingReturns.computeIfAbsent(uuid, key -> new ArrayList<>()).add(orphan);
        if (dirty) {
            persist(uuid, stored);
            persistOrphans(uuid);
            YamlStore.save(data, dataFile, plugin.getLogger());
        }
        return stored;
    }

    /**
     * 槽位迁移的<b>纯逻辑</b>（不碰 Bukkit，便于回归测试）。
     *
     * <p>只按物品自己声明的 {@code accessory-type} 找槽位，绝不按旧槽号搬运 —— 重排后
     * 旧 31 是腰饰、新 31 是胸饰，旧 22 是手饰、新 22 是项链，按槽号搬会把饰品全放到错误类型上。
     * <ol>
     *   <li>第一遍：槽号与类型本来就对得上的<b>原位保留</b>（保证迁移幂等，重复执行不变）；</li>
     *   <li>第二遍：其余按类型放进该类型的第一个空槽。</li>
     * </ol>
     *
     * @param oldSlots      旧存档里的槽号（只用于第一遍的原位保留判断）
     * @param declaredTypes 每件物品声明的饰品类型（已小写；空串表示无法识别）
     * @return 与输入等长的新槽号；{@code -1} 表示无处存放（调用方负责退还玩家）
     */
    static int[] planMigration(int[] oldSlots, String[] declaredTypes) {
        int[] targets = new int[oldSlots.length];
        boolean[] taken = new boolean[SIZE];
        Arrays.fill(targets, -1);

        for (int index = 0; index < oldSlots.length; index++) {
            int slot = oldSlots[index];
            String type = normalizeType(declaredTypes[index]);
            if (SLOT_TYPES.containsKey(slot) && normalizeType(SLOT_TYPES.get(slot)).equals(type) && !taken[slot]) {
                targets[index] = slot;
                taken[slot] = true;
            }
        }

        for (int index = 0; index < oldSlots.length; index++) {
            if (targets[index] >= 0) continue;
            String type = normalizeType(declaredTypes[index]);
            if (type.isEmpty()) continue;
            for (int slot : TYPE_SLOTS.getOrDefault(type, List.of())) {
                if (!taken[slot]) {
                    targets[index] = slot;
                    taken[slot] = true;
                    break;
                }
            }
        }
        return targets;
    }

    private static String normalizeType(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 物品自己声明的饰品类型。优先用注册表里的定义（含 {@code accessory-type}），
     * 定义缺失时退回旧槽位语义——迁移时至少能保住物品。
     */
    private String declaredType(ItemStack itemStack) {
        RpgItem item = plugin.getRpgItemManager().findItem(itemStack);
        if (item != null && item.type() == RpgItemType.ACCESSORY) return normalize(item.accessoryType());
        return "";
    }

    private void givePendingReturns(Player player) {
        UUID uuid = player.getUniqueId();
        List<ItemStack> pending = pendingReturns.remove(uuid);
        if (pending == null) pending = readOrphans(uuid);
        if (pending == null || pending.isEmpty()) return;
        for (ItemStack itemStack : pending) giveOrDrop(player, itemStack);
        data.set(ORPHAN_ROOT + uuid, null);
        YamlStore.save(data, dataFile, plugin.getLogger());
        player.sendMessage(Texts.color("&e饰品栏调整后，有 &f" + pending.size()
                + " &e件饰品无处存放，已退还到你的背包（背包满则掉在脚下）。"));
    }

    /**
     * 迁移时「找不到合适槽位」的饰品必须落盘，否则服务端重启后会静默丢失。
     * 单独存 {@code ..orphans.<uuid>} 键，等玩家下次打开饰品栏时退还。
     */
    private void persistOrphans(UUID uuid) {
        List<ItemStack> pending = pendingReturns.get(uuid);
        if (pending == null || pending.isEmpty()) return;
        List<String> encoded = new ArrayList<>();
        for (ItemStack itemStack : pending) encoded.add(serialize(itemStack));
        data.set(ORPHAN_ROOT + uuid, encoded);
    }

    private List<ItemStack> readOrphans(UUID uuid) {
        List<String> encoded = data.getStringList(ORPHAN_ROOT + uuid);
        if (encoded.isEmpty()) return List.of();
        List<ItemStack> items = new ArrayList<>();
        for (String value : encoded) {
            ItemStack itemStack = deserialize(value);
            if (itemStack != null) items.add(itemStack);
        }
        return items;
    }

    private void persist(UUID uuid, ItemStack[] stored) {
        List<String> lines = new ArrayList<>();
        for (int slot : SLOT_TYPES.keySet()) {
            ItemStack itemStack = stored[slot];
            if (!isEmpty(itemStack)) lines.add(slot + ":" + serialize(itemStack));
        }
        data.set(uuid.toString(), lines);
    }

    private void saveContents(UUID uuid, ItemStack[] stored) {
        cache.put(uuid, stored);
        persist(uuid, stored);
        YamlStore.save(data, dataFile, plugin.getLogger());
    }

    private void giveOrDrop(Player player, ItemStack itemStack) {
        if (isEmpty(itemStack)) return;
        for (ItemStack leftover : player.getInventory().addItem(itemStack).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    private boolean isEmpty(ItemStack itemStack) {
        return itemStack == null || itemStack.getType().isAir();
    }

    private String serialize(ItemStack itemStack) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); BukkitObjectOutputStream output = new BukkitObjectOutputStream(bytes)) {
            output.writeObject(itemStack);
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException exception) {
            return "";
        }
    }

    private ItemStack deserialize(String value) {
        try (ByteArrayInputStream bytes = new ByteArrayInputStream(Base64.getDecoder().decode(value)); BukkitObjectInputStream input = new BukkitObjectInputStream(bytes)) {
            return (ItemStack) input.readObject();
        } catch (IOException | ClassNotFoundException | IllegalArgumentException exception) {
            return null;
        }
    }

    /** 供指令/文档展示：类型中文名。 */
    public String typeLabel(String type) {
        return label(type);
    }

    /** 供指令/文档展示：类型顺序。 */
    public static List<String> typeOrder() {
        return TYPE_ORDER;
    }

    /**
     * 文本形式的饰品清单（{@code /rpgitem accessory <玩家> list} 用）。
     *
     * <p>只读缓存/存档，**不要求目标玩家在线**，方便管理员排查离线玩家。
     * 返回的每一行已经带颜色代码。
     */
    public List<String> describe(UUID uuid) {
        Map<Integer, ItemStack> slots = snapshot(uuid);
        List<String> lines = new ArrayList<>();
        if (slots.isEmpty()) {
            lines.add("&7（空）");
            return lines;
        }
        for (String type : TYPE_ORDER) {
            List<String> names = new ArrayList<>();
            for (int slot : TYPE_SLOTS.getOrDefault(type, List.of())) {
                ItemStack itemStack = slots.get(slot);
                if (isEmpty(itemStack)) continue;
                names.add("&f" + displayName(itemStack) + "&8[&7#" + slot + "&8]");
            }
            if (!names.isEmpty()) {
                lines.add("&e" + label(type) + "&7: " + String.join("&7, ", names));
            }
        }
        // 类型不在标准表中的物品也列出来，避免「看得见却不在清单里」。
        for (Map.Entry<Integer, ItemStack> entry : slots.entrySet()) {
            String slotType = normalize(SLOT_TYPES.get(entry.getKey()));
            if (!TYPE_SLOTS.containsKey(slotType)) {
                lines.add("&c未知类型&7[&7#" + entry.getKey() + "&8]: &f" + displayName(entry.getValue()));
            }
        }
        return lines;
    }

    /** 清空某个玩家的饰品栏，返回被清掉的件数。 */
    public int clear(UUID uuid) {
        ItemStack[] stored = getContents(uuid);
        int removed = 0;
        for (int slot : SLOT_TYPES.keySet()) {
            if (!isEmpty(stored[slot])) {
                stored[slot] = null;
                removed++;
            }
        }
        if (removed > 0) saveContents(uuid, stored);
        return removed;
    }

    private String displayName(ItemStack itemStack) {
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return itemStack.getType().name();
        String plain = meta.getDisplayName().replaceAll("(?i)\u00a7[0-9a-fk-or]", "");
        return plain.isBlank() ? itemStack.getType().name() : plain;
    }

    /** 供测试与迁移验算：新布局的槽位表（只读副本）。 */
    public static Map<Integer, String> slotLayout() {
        return Map.copyOf(SLOT_TYPES);
    }

    /** 供测试与迁移验算：旧布局的槽位表。 */
    public static Map<Integer, String> legacySlotLayout() {
        return Map.copyOf(LEGACY_SLOT_TYPES);
    }

    /** 供测试与迁移验算：每种类型对应的槽位（按顺序）。 */
    public static Map<String, List<Integer>> typeSlots() {
        Map<String, List<Integer>> copy = new LinkedHashMap<>();
        TYPE_SLOTS.forEach((type, slots) -> copy.put(type, List.copyOf(slots)));
        return copy;
    }
}
