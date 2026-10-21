package EarthRPGItem.listeners;

import EarthRPGItem.EarthRPGItem;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

/**
 * Invalidates the per-tick stat settlement memo whenever a player's kit can change.
 *
 * <p>{@link EarthRPGItem.items.RpgItemManager#collectActiveStats} reuses this tick's result instead
 * of re-reading the whole equipment set, which is what keeps a single damage event from settling the
 * same player three times. That is only correct as long as nothing can change the kit inside one
 * tick, so every path that can is listed here.
 *
 * <p><b>Why each event matters</b>:
 * <ul>
 *   <li>{@link PlayerItemHeldEvent} — hotbar switch swaps main hand for an offhand weapon.</li>
 *   <li>{@link PlayerSwapHandItemsEvent} — F-key swap, same effect.</li>
 *   <li>{@link InventoryClickEvent} / {@link InventoryDragEvent} — equipping armour, moving a ring,
 *       or any click that can end with a different item in a slot. Fires on player, chest and shop
 *       inventories alike, so the hook does not have to know which menus exist.</li>
 *   <li>{@link PlayerQuitEvent} — drops the entry so the map cannot grow without bound.</li>
 * </ul>
 *
 * <p>Clicks are handled at {@link EventPriority#MONITOR} and with {@code ignoreCancelled = false} on
 * purpose: the point is to learn that a slot may now differ, not to act on the click, and a click
 * cancelled by another plugin can still have moved items before it was cancelled. The handler only
 * removes one map entry, so running it on every click is cheaper than the settlement it prevents.
 */
public class StatsCacheListener implements Listener {

    private final EarthRPGItem plugin;

    public StatsCacheListener(EarthRPGItem plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onItemHeld(PlayerItemHeldEvent event) {
        invalidate(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        invalidate(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        // A cancelled click almost never moves anything, but shift-click and number-key swaps can
        // have already been applied by the time another plugin cancels, so this does not filter on
        // isCancelled(). The cost is one HashMap.remove on a click, not a settlement.
        invalidate(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        invalidate(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onQuit(PlayerQuitEvent event) {
        invalidate(event.getPlayer());
    }

    private void invalidate(Player player) {
        plugin.getRpgItemManager().invalidateStatsCache(player);
    }
}
