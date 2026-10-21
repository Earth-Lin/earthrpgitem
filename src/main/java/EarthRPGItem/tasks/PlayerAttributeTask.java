package EarthRPGItem.tasks;

import EarthRPGItem.EarthRPGItem;
import EarthRPGItem.items.BuffConfig;
import EarthRPGItem.items.RpgItem;
import EarthRPGItem.items.RpgStats;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public class PlayerAttributeTask implements Runnable {

    private final EarthRPGItem plugin;
    private int buffTickCounter;
    private long elapsedSeconds;

    public PlayerAttributeTask(EarthRPGItem plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        buffTickCounter++;
        elapsedSeconds++;
        int refreshSeconds = Math.max(1, plugin.getConfig().getInt("buffs.refresh-interval-seconds", 10));
        boolean refreshBuffs = buffTickCounter >= refreshSeconds;
        if (refreshBuffs) {
            buffTickCounter = 0;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            // Stable random values here: this task rewrites max health from stats.health() every
            // second, so re-rolling a Health range would drain the player's health continuously.
            RpgStats stats = plugin.getRpgItemManager().collectActiveStats(player, false, false);
            updateHealth(player, stats);
            recoverHealth(player, stats);
            processFixDurability(player);
            if (refreshBuffs) {
                applyBuffs(player);
            }
        }
    }

    private void updateHealth(Player player, RpgStats stats) {
        AttributeInstance attribute = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (attribute == null) {
            return;
        }
        double maxHealth = Math.max(1, (20 + stats.health()) * (1 + stats.vitality() / 100.0));
        attribute.setBaseValue(maxHealth);
        double effectiveMaxHealth = attribute.getValue();
        if (player.getHealth() > effectiveMaxHealth) {
            player.setHealth(effectiveMaxHealth);
        }
    }

    private void recoverHealth(Player player, RpgStats stats) {
        if (stats.recovery() <= 0 && stats.recoveryPercent() <= 0 || player.isDead()) return;
        AttributeInstance attribute = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        double maxHealth = attribute == null ? 20 : attribute.getValue();
        double fixedHeal = Math.max(0, stats.recovery());
        double percentHeal = maxHealth * Math.min(100, Math.max(0, stats.recoveryPercent())) / 100.0;
        player.setHealth(Math.min(maxHealth, player.getHealth() + fixedHeal + percentHeal));
    }

    private void processFixDurability(Player player) {
        for (ItemStack itemStack : player.getInventory().getArmorContents()) {
            processFixItem(player, itemStack);
        }
        processFixItem(player, player.getInventory().getItemInMainHand());
        processFixItem(player, player.getInventory().getItemInOffHand());
    }

    private void processFixItem(Player player, ItemStack itemStack) {
        RpgItem item = plugin.getRpgItemManager().findItem(itemStack);
        double fixAmount = item == null ? 0 : plugin.getRpgItemManager().getRolledAttribute(itemStack, "fix", item.fixPerSecond());
        if (item == null || fixAmount == 0 || elapsedSeconds % Math.max(1, item.fixIntervalSeconds()) != 0) {
            return;
        }
        int amount = Math.max(1, (int) Math.round(Math.abs(fixAmount)));
        if (fixAmount > 0) {
            plugin.getRpgItemManager().repairDurability(itemStack, amount);
        } else {
            plugin.getRpgItemManager().damageDurability(player, itemStack, amount);
        }
    }

    private void applyBuffs(Player player) {
        if (!plugin.isFeatureEnabled("buffs") || !plugin.getConfig().getBoolean("buffs.enabled", true)) {
            return;
        }
        int durationTicks = Math.max(1, plugin.getConfig().getInt("buffs.duration-seconds", 13) * 20);
        for (RpgItem item : plugin.getRpgItemManager().collectActiveItems(player, false)) {
            for (BuffConfig buff : item.buffs()) {
                PotionEffectType type = PotionEffectType.getByName(buff.type().toUpperCase());
                if (type != null) {
                    player.addPotionEffect(new PotionEffect(type, durationTicks, Math.max(0, buff.amplifier()), true, false, true));
                }
            }
        }
    }
}
