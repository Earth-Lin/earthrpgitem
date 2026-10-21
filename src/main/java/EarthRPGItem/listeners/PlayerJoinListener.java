package EarthRPGItem.listeners;

import EarthRPGItem.EarthRPGItem;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class PlayerJoinListener implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final EarthRPGItem plugin;

    public PlayerJoinListener(EarthRPGItem plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("join-broadcast.enabled", true)) {
            return;
        }

        Player player = event.getPlayer();
        String template = plugin.getConfig().getString(
                "join-broadcast.message",
                "&a欢迎 &e{player} &a加入游戏"
        );

        if (template == null || template.isBlank()) {
            return;
        }

        String rawMessage = template
                .replace("{player}", player.getName())
                .replace("%player%", player.getName());

        Bukkit.getServer().sendMessage(LEGACY.deserialize(rawMessage));
    }
}
