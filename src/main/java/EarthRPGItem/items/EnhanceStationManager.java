package EarthRPGItem.items;

import EarthRPGItem.EarthRPGItem;
import EarthRPGItem.util.YamlStore;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.UUID;

/**
 * Per-player material stock for the enhancement station.
 * <p>
 * The station lets a player put material into a slot by hand, and the obvious implementation — keep
 * the real {@link org.bukkit.inventory.ItemStack} in that GUI slot — is the one that loses items: a
 * server crash, a kick mid-drag, or a plugin reload while the window is open takes the stack with
 * it, and there is no record that it ever existed. So this stores a <b>count</b> instead, exactly
 * like {@code SkillBackpackManager} stores skill ids rather than skill items. The slot in the GUI is
 * a rendering of this number, not a container, which makes it impossible to lose the contents and
 * impossible to duplicate them.
 * <p>
 * A deposit also remembers <b>which</b> item id it holds. That matters because {@code stone.item-id}
 * is configurable: without it, an admin changing the material would silently convert every
 * outstanding deposit from the old material into the new one, and changing it to an id that does not
 * exist would strand the stock with nothing to give back.
 */
public final class EnhanceStationManager {

    /** One player's stock: which material, and how much. */
    public record Deposit(String itemId, int count) {
        public static final Deposit EMPTY = new Deposit("", 0);

        public boolean isEmpty() {
            return count <= 0 || itemId == null || itemId.isBlank();
        }
    }

    private static final int MAX_DEPOSIT = 2304; // 36 stacks, i.e. a full inventory of one item

    private final EarthRPGItem plugin;
    private final File dataFile;
    private final YamlConfiguration data;

    public EnhanceStationManager(EarthRPGItem plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "enhance-station.yml");
        this.data = YamlStore.load(dataFile, plugin.getLogger());
    }

    /** What {@code playerId} currently has deposited. Never null. */
    public Deposit deposit(UUID playerId) {
        if (playerId == null) return Deposit.EMPTY;
        String itemId = data.getString(path(playerId) + ".item-id", "");
        int count = Math.max(0, data.getInt(path(playerId) + ".count", 0));
        if (itemId == null || itemId.isBlank() || count <= 0) return Deposit.EMPTY;
        return new Deposit(itemId, count);
    }

    /**
     * How much of {@code itemId} is deposited and spendable.
     * <p>
     * Returns 0 when the stored material is a different id, so a stock left over from a previous
     * {@code stone.item-id} can never be spent on the current one. The player keeps it and can
     * withdraw it once the old id is configured again.
     */
    public int available(UUID playerId, String itemId) {
        if (itemId == null || itemId.isBlank()) return 0;
        Deposit deposit = deposit(playerId);
        if (deposit.isEmpty() || !itemId.equals(deposit.itemId())) return 0;
        return deposit.count;
    }

    /**
     * Adds {@code amount} of {@code itemId} to the stock.
     *
     * @return how many were actually accepted: 0 when the id clashes with an existing deposit or the
     *         stock is full, and less than {@code amount} when the stock fills up part-way.
     */
    public int store(UUID playerId, String itemId, int amount) {
        if (playerId == null || itemId == null || itemId.isBlank() || amount <= 0) return 0;
        Deposit current = deposit(playerId);
        // Refusing a mismatched id rather than merging keeps the withdrawal path unambiguous: the
        // stock always has exactly one identity, so it can always be given back as that item.
        if (!current.isEmpty() && !itemId.equals(current.itemId())) return 0;
        int room = MAX_DEPOSIT - current.count;
        int accepted = Math.min(amount, Math.max(0, room));
        if (accepted <= 0) return 0;
        if (!write(playerId, itemId, current.count + accepted)) return 0;
        return accepted;
    }

    /**
     * Spends up to {@code amount} of {@code itemId} from the stock.
     *
     * @return how many were actually spent (0 when the stock holds a different id)
     */
    public int spend(UUID playerId, String itemId, int amount) {
        if (amount <= 0) return 0;
        int available = available(playerId, itemId);
        if (available <= 0) return 0;
        int spent = Math.min(amount, available);
        write(playerId, itemId, available - spent);
        return spent;
    }

    /**
     * Empties the stock and returns what was in it, so the caller can hand the items back.
     * The clear is only reported as successful if it was actually persisted.
     */
    public Deposit takeAll(UUID playerId) {
        Deposit current = deposit(playerId);
        if (current.isEmpty()) return Deposit.EMPTY;
        if (!write(playerId, current.itemId(), 0)) return Deposit.EMPTY;
        return current;
    }

    /** The stock as an attempt's material source, so the station spends deposited material only. */
    public EnhancementService.MaterialSource source(UUID playerId) {
        return new EnhancementService.MaterialSource() {
            @Override
            public int available(String itemId) {
                return EnhanceStationManager.this.available(playerId, itemId);
            }

            @Override
            public int consume(String itemId, int amount) {
                return spend(playerId, itemId, amount);
            }
        };
    }

    public int capacity() {
        return MAX_DEPOSIT;
    }

    /** Persists the stock atomically, rolling the in-memory change back when the save fails. */
    private boolean write(UUID playerId, String itemId, int count) {
        String base = path(playerId);
        String previousId = data.getString(base + ".item-id", "");
        int previousCount = data.getInt(base + ".count", 0);
        if (count <= 0) {
            data.set(base, null);
        } else {
            data.set(base + ".item-id", itemId);
            data.set(base + ".count", count);
        }
        if (YamlStore.save(data, dataFile, plugin.getLogger())) return true;
        // Rolled back so the in-memory view keeps agreeing with the file on disk.
        if (previousCount <= 0) {
            data.set(base, null);
        } else {
            data.set(base + ".item-id", previousId);
            data.set(base + ".count", previousCount);
        }
        return false;
    }

    private String path(UUID playerId) {
        return "players." + playerId;
    }
}
