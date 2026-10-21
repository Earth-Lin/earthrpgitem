package EarthRPGItem.items;

import EarthRPGItem.EarthRPGItem;
import EarthRPGItem.util.Texts;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.concurrent.ThreadLocalRandom;

/**
 * The single implementation of "spend the material, roll the dice, apply the outcome".
 * <p>
 * Extracted from the {@code /rpgitem enhance} command when the enhancement station GUI was added.
 * The GUI and the command must agree on every rule that can cost a player something — when the
 * material is charged, what the roll is compared against, and what each penalty does to the item —
 * so there is deliberately only <b>one</b> copy of that logic. Two copies drift, and the direction
 * they drift in is either a player losing an item or a player getting free attempts.
 * <p>
 * The caller supplies where the material comes from, because the two entry points genuinely differ:
 * the command takes it from the inventory, while the station takes it from the player's deposited
 * stock. Everything after that decision is shared.
 */
public final class EnhancementService {

    /** What an attempt did, so a caller can react without re-deriving the outcome. */
    public enum Outcome {
        SUCCESS,
        /** Failed inside the safe zone: the level is unchanged. */
        FAIL_SAFE,
        FAIL_DOWNGRADE,
        FAIL_DESTROY,
        /** The system is switched off in {@code enhancement.yml}. */
        DISABLED,
        /** Nothing usable in hand. */
        NO_ITEM,
        NOT_RPG_ITEM,
        ALREADY_MAX,
        /** The next rung is missing from {@code levels}. */
        NO_LEVEL_DATA,
        /** Not enough material. Nothing was charged. */
        NO_MATERIAL;

        /** Whether the attempt actually happened, as opposed to being refused up front. */
        public boolean attempted() {
            return this == SUCCESS || this == FAIL_SAFE || this == FAIL_DOWNGRADE || this == FAIL_DESTROY;
        }

        /** Whether the item survived the attempt. */
        public boolean itemSurvives() {
            return this != FAIL_DESTROY;
        }
    }

    /** Where an attempt's material comes from. */
    public interface MaterialSource {
        /** How many of {@code itemId} are available to spend right now. */
        int available(String itemId);

        /** Spends up to {@code amount}; returns how many were actually consumed. */
        int consume(String itemId, int amount);
    }

    private final EarthRPGItem plugin;

    public EnhancementService(EarthRPGItem plugin) {
        this.plugin = plugin;
    }

    public EnhancementManager manager() {
        return plugin.getEnhancementManager();
    }

    /** Human-readable name of a penalty, shared so both entry points word it identically. */
    public static String penaltyName(EnhancementManager.Penalty penalty) {
        return switch (penalty) {
            case NONE -> "&a无（等级不变）";
            case DOWNGRADE -> "&c掉 1 级";
            case DESTROY -> "&4物品破坏";
        };
    }

    /** Material taken straight from the player's inventory; this is what the command uses. */
    public MaterialSource inventorySource(Player player) {
        return new MaterialSource() {
            @Override
            public int available(String itemId) {
                return countInInventory(player, itemId);
            }

            @Override
            public int consume(String itemId, int amount) {
                return consumeFromInventory(player, itemId, amount);
            }
        };
    }

    /**
     * Runs one attempt.
     * <p>
     * The material is charged <b>before</b> the roll, so a failed attempt still costs it. Refunding
     * on failure would make a high-level attempt free to retry and delete the entire risk that the
     * {@code success-rate} curve exists to express.
     *
     * @param held      the item being enhanced; mutated in place on success or downgrade, and
     *                  cleared from the hand on destruction
     * @param materials where the material is taken from
     */
    public Outcome attempt(Player player, ItemStack held, MaterialSource materials) {
        EnhancementManager enhancement = manager();
        if (enhancement == null) {
            player.sendMessage(Texts.color("&c强化系统未加载。"));
            return Outcome.DISABLED;
        }
        if (!enhancement.enabled()) {
            player.sendMessage(Texts.color(enhancement.message("disabled", "&c强化系统当前未开启。")));
            return Outcome.DISABLED;
        }
        if (held == null || held.getType().isAir()) {
            player.sendMessage(Texts.color(enhancement.message("no-item", "&c请手持一件可强化的 RPG 物品。")));
            return Outcome.NO_ITEM;
        }
        RpgItemManager items = plugin.getRpgItemManager();
        RpgItem registered = items.findItem(held);
        if (registered == null) {
            player.sendMessage(Texts.color(enhancement.message("not-rpg-item", "&c这个物品不是 RPG 物品，无法强化。")));
            return Outcome.NOT_RPG_ITEM;
        }
        int current = items.enhanceLevel(held);
        if (current >= enhancement.maxLevel()) {
            player.sendMessage(Texts.color(EnhancementManager.fill(
                    enhancement.message("already-max", "&e已经满强化了。"),
                    "level", Integer.toString(current))));
            return Outcome.ALREADY_MAX;
        }
        int target = current + 1;
        EnhancementManager.Level definition = enhancement.level(target);
        if (definition == null) {
            player.sendMessage(Texts.color(EnhancementManager.fill(
                    enhancement.message("no-level-data", "&c配置里没有定义 +{level} 这一级，无法继续强化。"),
                    "level", Integer.toString(target))));
            return Outcome.NO_LEVEL_DATA;
        }
        if (enhancement.consumesStone()) {
            String stoneId = enhancement.stoneItemId();
            int need = enhancement.stoneAmount();
            if (materials.available(stoneId) < need) {
                player.sendMessage(Texts.color(EnhancementManager.fill(
                        enhancement.message("no-stone", "&c强化需要 {amount} 个 {stone}。"),
                        "amount", Integer.toString(need), "stone", stoneId)));
                return Outcome.NO_MATERIAL;
            }
            materials.consume(stoneId, need);
        }
        String name = registered.displayName();
        String rate = Long.toString(Math.round(definition.successRate() * 100));
        String percent = Long.toString(Math.round(definition.percent() * 100));
        if (ThreadLocalRandom.current().nextDouble() < definition.successRate()) {
            items.setEnhanceLevel(held, target);
            player.updateInventory();
            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.8F, 1.4F);
            player.sendMessage(Texts.color(EnhancementManager.fill(
                    enhancement.message("success", "&a&l强化成功！&r &f{item} &7→ &6+{level}"),
                    "item", name, "level", Integer.toString(target), "current", Integer.toString(current),
                    "rate", rate, "percent", percent)));
            return Outcome.SUCCESS;
        }
        switch (definition.penalty()) {
            case NONE -> {
                player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.6F, 0.7F);
                player.sendMessage(Texts.color(EnhancementManager.fill(
                        enhancement.message("fail-safe", "&c强化失败。&7（安全区，等级不变）&f{item} &7仍然 &6+{current}"),
                        "item", name, "level", Integer.toString(target), "current", Integer.toString(current), "rate", rate)));
                return Outcome.FAIL_SAFE;
            }
            case DOWNGRADE -> {
                int downgraded = Math.max(0, current - 1);
                items.setEnhanceLevel(held, downgraded);
                player.updateInventory();
                player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.9F, 0.6F);
                player.sendMessage(Texts.color(EnhancementManager.fill(
                        enhancement.message("fail-downgrade", "&c&l强化失败！&r &f{item} &7掉到了 &c+{current}"),
                        "item", name, "level", Integer.toString(target), "current", Integer.toString(downgraded), "rate", rate)));
                return Outcome.FAIL_DOWNGRADE;
            }
            case DESTROY -> {
                player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
                // The enhanced weapon is gone from the main hand without an inventory click.
                plugin.getRpgItemManager().invalidateStatsCache(player);
                player.updateInventory();
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0F, 0.8F);
                player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.7F, 1.2F);
                player.sendMessage(Texts.color(EnhancementManager.fill(
                        enhancement.message("fail-destroy", "&4&l强化失败！&r &f{item} &4被破坏了……"),
                        "item", name, "level", Integer.toString(target), "current", Integer.toString(current), "rate", rate)));
                return Outcome.FAIL_DESTROY;
            }
        }
        // Unreachable: every Penalty constant is handled above.
        return Outcome.FAIL_SAFE;
    }

    /** How many items matching {@code itemId} the player is carrying (storage slots only). */
    public int countInInventory(Player player, String itemId) {
        if (player == null || itemId == null || itemId.isBlank()) return 0;
        RpgItemManager items = plugin.getRpgItemManager();
        int total = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir()) continue;
            RpgItem item = items.findItem(stack);
            if (item != null && itemId.equals(item.id())) total += stack.getAmount();
        }
        return total;
    }

    /** Removes up to {@code amount} items matching {@code itemId}; returns how many were taken. */
    public int consumeFromInventory(Player player, String itemId, int amount) {
        if (player == null || itemId == null || itemId.isBlank() || amount <= 0) return 0;
        RpgItemManager items = plugin.getRpgItemManager();
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int index = 0; index < contents.length && remaining > 0; index++) {
            ItemStack stack = contents[index];
            if (stack == null || stack.getType().isAir()) continue;
            RpgItem item = items.findItem(stack);
            if (item == null || !itemId.equals(item.id())) continue;
            int taken = Math.min(remaining, stack.getAmount());
            remaining -= taken;
            stack.setAmount(stack.getAmount() - taken);
            if (stack.getAmount() <= 0) contents[index] = null;
        }
        player.getInventory().setStorageContents(contents);
        return amount - remaining;
    }
}
