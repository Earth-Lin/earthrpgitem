package EarthRPGItem.listeners;

import EarthRPGItem.EarthRPGItem;
import EarthRPGItem.items.RpgItem;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import java.util.UUID;

public class RpgDropListener implements Listener {
    private final EarthRPGItem plugin;
    private final java.util.Set<java.util.UUID> openInventories = new java.util.HashSet<>();
    private final java.util.Map<java.util.UUID, Long> inventoryDropWindows = new java.util.HashMap<>();

    public RpgDropListener(EarthRPGItem plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) openInventories.add(player.getUniqueId());
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) openInventories.remove(player.getUniqueId());
    }

    /**
     * Without this, a player who disconnects (or is kicked) while any inventory is open stays in
     * {@link #openInventories} forever, which permanently disables the RPG item drop guard for them.
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        openInventories.remove(playerId);
        inventoryDropWindows.remove(playerId);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrop(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!isDropAction(event.getAction())) return;
        ItemStack candidate = event.getCurrentItem();
        if (candidate == null || candidate.getType().isAir()) candidate = event.getCursor();
        if (plugin.getRpgItemManager().hasRpgIdentity(candidate)) {
            inventoryDropWindows.put(player.getUniqueId(), System.currentTimeMillis() + 750L);
            event.setCancelled(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (plugin.getRpgItemManager().hasRpgIdentity(event.getOldCursor())) {
            event.setCancelled(false);
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerDrop(PlayerDropItemEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        Long inventoryDropUntil = inventoryDropWindows.get(playerId);
        if (openInventories.contains(playerId)
                || (inventoryDropUntil != null && inventoryDropUntil > System.currentTimeMillis())) {
            inventoryDropWindows.remove(playerId);
            return;
        }
        if (plugin.getRpgItemManager().findItemByIdentity(event.getItemDrop().getItemStack()) != null) {
            event.setCancelled(true);
        }
    }

    private boolean holdsMainHandRpgItem(Player player) {
        return plugin.getRpgItemManager().hasRpgIdentity(player.getInventory().getItemInMainHand());
    }

    private boolean isDropAction(InventoryAction action) {
        return action == InventoryAction.DROP_ALL_CURSOR || action == InventoryAction.DROP_ONE_CURSOR
                || action == InventoryAction.DROP_ALL_SLOT || action == InventoryAction.DROP_ONE_SLOT;
    }
}
