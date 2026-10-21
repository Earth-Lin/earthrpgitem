package EarthRPGItem.dummy;

import EarthRPGItem.EarthRPGItem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;

public class DummyListener implements Listener {
    private final EarthRPGItem plugin;

    public DummyListener(EarthRPGItem plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!event.getPlayer().isSneaking()) return;
        if (!(event.getRightClicked() instanceof LivingEntity livingEntity)) return;
        if (!plugin.getDummyManager().isDummy(livingEntity)) return;
        event.setCancelled(true);
        if (!event.getPlayer().isOp()) return;
        plugin.getDummyManager().openMenu(event.getPlayer(), livingEntity, 0);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void protectDummy(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity livingEntity) || !plugin.getDummyManager().isDummy(livingEntity)) return;
        double damage = event.getFinalDamage();
        event.setCancelled(true);
        plugin.getDummyManager().absorbDamage(livingEntity, damage);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof DummyMenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot == 49) { player.closeInventory(); return; }
        if (slot == 45 && holder.getPage() > 0) {
            var entity = org.bukkit.Bukkit.getEntity(holder.getDummyId());
            if (entity instanceof LivingEntity livingEntity) plugin.getDummyManager().openMenu(player, livingEntity, holder.getPage() - 1);
            return;
        }
        if (slot == 53 && plugin.getDummyManager().hasNextPage(holder.getPage())) {
            var entity = org.bukkit.Bukkit.getEntity(holder.getDummyId());
            if (entity instanceof LivingEntity livingEntity) plugin.getDummyManager().openMenu(player, livingEntity, holder.getPage() + 1);
            return;
        }
        if (slot >= 0 && slot < 45) {
            var type = plugin.getDummyManager().typeAt(holder.getPage(), slot);
            if (type != null) plugin.getDummyManager().switchType(player, holder.getDummyId(), type);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof DummyMenuHolder) event.setCancelled(true);
    }
}
