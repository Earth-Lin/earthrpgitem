package EarthRPGItem.accessory;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * 饰品栏界面标识。
 *
 * <p>{@code owner} 是饰品栏<b>属于谁</b>——属性结算、灵魂绑定校验、关闭时的持久化都用它，
 * 而不是「是谁打开的界面」。管理员编辑别人饰品时必须用目标玩家，
 * 否则所有饰品都会因为「灵魂绑定不属于你」被判非法。
 */
public class AccessoryMenuHolder implements InventoryHolder {

    private final UUID owner;

    public AccessoryMenuHolder(UUID owner) {
        this.owner = owner;
    }

    public UUID getOwner() {
        return owner;
    }

    @Override
    public @NotNull Inventory getInventory() {
        throw new UnsupportedOperationException("Accessory menu holder does not own inventory state.");
    }
}
