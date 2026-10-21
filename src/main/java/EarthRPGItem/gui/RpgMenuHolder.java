package EarthRPGItem.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

public class RpgMenuHolder implements InventoryHolder {
    private final RpgMenuType menuType;
    private final int page;
    private final String group;
    /** 技能背包的「删除模式」开关：开着时点击技能=删除（退还技能点），关着时只是提示。 */
    private boolean deleteMode;
    /** 删除确认界面是否已经处理过一次：防止连点/重复事件导致重复删除与重复退款。 */
    private boolean confirmHandled;
    /**
     * 技能强化台是从「技能面板」底行进来的。
     * <p>
     * 强化台有两个入口（主菜单按钮、技能面板按钮），「返回」该回哪里取决于从哪进来。
     * 技能背包和技能预设都返回技能面板，所以从面板进来的强化台也返回面板，保持一致；
     * 从主菜单进来的仍然回主菜单。
     */
    private boolean forgeFromPanel;

    public RpgMenuHolder(RpgMenuType menuType, int page) { this(menuType, page, null); }
    public RpgMenuHolder(RpgMenuType menuType, int page, String group) {
        this.menuType = menuType;
        this.page = page;
        this.group = group;
    }
    public RpgMenuType getMenuType() { return menuType; }
    public int getPage() { return page; }
    public String getGroup() { return group; }
    public boolean isDeleteMode() { return deleteMode; }
    public void setDeleteMode(boolean deleteMode) { this.deleteMode = deleteMode; }
    public boolean isConfirmHandled() { return confirmHandled; }
    public void setConfirmHandled(boolean confirmHandled) { this.confirmHandled = confirmHandled; }
    public boolean isForgeFromPanel() { return forgeFromPanel; }
    public void setForgeFromPanel(boolean forgeFromPanel) { this.forgeFromPanel = forgeFromPanel; }
    @Override public @NotNull Inventory getInventory() {
        throw new UnsupportedOperationException("RPG menu holder does not own inventory state.");
    }
}
