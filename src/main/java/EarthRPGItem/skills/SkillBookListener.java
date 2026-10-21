package EarthRPGItem.skills;

import EarthRPGItem.EarthRPGItem;
import EarthRPGItem.util.Texts;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * 技能书右键直接学习：手持已注册技能的物品右键（空气或方块），技能直接加入技能背包
 * 并消耗一本技能书。
 *
 * <p>入包失败（重复 / 已满 / 保存失败）时<b>不</b>消耗技能书，也不取消事件，
 * 让原行为（比如放下物品）继续生效。
 */
public final class SkillBookListener implements Listener {

    private final EarthRPGItem plugin;

    public SkillBookListener(EarthRPGItem plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onRightClick(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();
        String id = plugin.getSkillPanelManager().registeredSkillId(held);
        if (id == null) return;
        SkillBackpackManager backpack = plugin.getSkillBackpackManager();
        if (!backpack.add(player.getUniqueId(), id)) {
            // 和技能背包里点物品放入同一套失败提示，玩家不会看到两套说法。
            if (backpack.contains(player.getUniqueId(), id)) {
                player.sendMessage(Texts.color("&c技能背包中已有相同技能，无法重复学习"));
            } else if (backpack.isFull(player.getUniqueId())) {
                player.sendMessage(Texts.color("&c技能背包已满"));
            } else {
                player.sendMessage(Texts.color("&c技能背包保存失败，技能书未收取，请稍后再试"));
            }
            return;
        }
        event.setCancelled(true);
        if (held.getAmount() > 1) {
            held.setAmount(held.getAmount() - 1);
            player.getInventory().setItemInMainHand(held);
        } else {
            player.getInventory().setItemInMainHand(null);
        }
        // The consumed book left the main hand without an inventory click, so the memoised
        // settlement still counts it. A book is not equipment, but the cache must not be allowed to
        // outlive any hand change at all.
        plugin.getRpgItemManager().invalidateStatsCache(player);
        player.sendMessage(Texts.color("&a已学习技能并放入技能背包: &f" + id));
    }
}