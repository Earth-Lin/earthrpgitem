package EarthRPGItem.combat;

import EarthRPGItem.EarthRPGItem;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AttackCooldownManager {

    private final EarthRPGItem plugin;
    private final Map<UUID, Long> readyTimes = new HashMap<>();

    public AttackCooldownManager(EarthRPGItem plugin) {
        this.plugin = plugin;
    }

    public boolean isReady(Player player) {
        return System.currentTimeMillis() >= readyAt(player);
    }

    public long readyAt(Player player) {
        return readyTimes.getOrDefault(player.getUniqueId(), 0L);
    }

    public long start(Player player, double attackSpeed) {
        long cooldownMillis = cooldownMillis(attackSpeed);
        readyTimes.put(player.getUniqueId(), System.currentTimeMillis() + cooldownMillis);
        ItemStack itemStack = player.getInventory().getItemInMainHand();
        if (!itemStack.getType().isAir()) {
            player.setCooldown(itemStack.getType(), Math.max(1, (int) Math.ceil(cooldownMillis / 50.0)));
        }
        return cooldownMillis;
    }

    public void reset(Player player) {
        readyTimes.remove(player.getUniqueId());
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (!mainHand.getType().isAir()) player.setCooldown(mainHand.getType(), 0);
    }

    /** Drops the stored ready-time so the map does not grow with every UUID that ever attacked. */
    public void clear(UUID uuid) {
        readyTimes.remove(uuid);
    }

    public long cooldownMillis(double attackSpeed) {
        if (!plugin.isFeatureEnabled("attack-speed")) return 1000;
        if (attackSpeed >= 0) return Math.round(1000 - Math.min(100, attackSpeed) / 100.0 * 950);
        return Math.round(1000 + Math.min(100, Math.abs(attackSpeed)) / 100.0 * 9000);
    }
}
