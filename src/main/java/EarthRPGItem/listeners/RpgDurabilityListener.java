package EarthRPGItem.listeners;

import EarthRPGItem.EarthRPGItem;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemDamageEvent;

public class RpgDurabilityListener implements Listener {

    private final EarthRPGItem plugin;

    public RpgDurabilityListener(EarthRPGItem plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerItemDamage(PlayerItemDamageEvent event) {
        Player player = event.getPlayer();
        if (plugin.getRpgItemManager().applyDurabilityLoss(player, event.getItem(), event.getDamage())) {
            event.setCancelled(true);
        }
    }
}