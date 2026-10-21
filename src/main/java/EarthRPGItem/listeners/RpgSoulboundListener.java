package EarthRPGItem.listeners;

import EarthRPGItem.EarthRPGItem;
import EarthRPGItem.util.Texts;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;

public class RpgSoulboundListener implements Listener {

    private final EarthRPGItem plugin;

    public RpgSoulboundListener(EarthRPGItem plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof org.bukkit.entity.Player player)) return;
        if (!plugin.getRpgItemManager().isSoulboundToOther(player, event.getItem().getItemStack())) return;
        event.setCancelled(true);
        player.sendMessage(Texts.color("&c这个灵魂绑定物品不属于你，无法拾取。"));
    }
}
