package EarthRPGItem.dummy;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class DummyMenuHolder implements InventoryHolder {
    private final UUID dummyId;
    private final int page;

    public DummyMenuHolder(UUID dummyId, int page) {
        this.dummyId = dummyId;
        this.page = page;
    }

    public UUID getDummyId() { return dummyId; }
    public int getPage() { return page; }

    @Override
    public @NotNull Inventory getInventory() {
        throw new UnsupportedOperationException("Dummy menu holder does not own inventory state.");
    }
}