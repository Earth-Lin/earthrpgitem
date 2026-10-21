package EarthRPGItem.gui;

import EarthRPGItem.items.RpgItemType;

public enum RpgMenuType {
    MAIN(null, "Arthritism"),
    SETTING(null, "Setting"),
    GROUPS(null, "Groups"),
    CATEGORY(null, "Category"),
    ARMOR(RpgItemType.ARMOR, "Armor"),
    TOOL(RpgItemType.TOOL, "Tool"),
    WEAPON(RpgItemType.WEAPON, "Weapon"),
    ITEMS(RpgItemType.ITEMS, "Items"),
    ACCESSORY(RpgItemType.ACCESSORY, "Accessory"),
    COLLECTION(null, "Collection"),
    SKILL_PANEL(null, "Skill Panel"),
    SKILL_PRESET(null, "Skill Preset"),
    SKILL_BACKPACK(null, "Skill Backpack"),
    SKILL_DELETE_CONFIRM(null, "确认删除技能"),
    SKILL_ITEMS(null, "Skill Items"),
    SKILL_LIBRARY(null, "Skill Library"),
    SKILL_FORGE(null, "Skill Forge"),
    SKILL_FORGE_DETAIL(null, "Skill Forge Detail"),
    ENHANCE(null, "Enhance Station"),
    GEMS(null, "Gem Station");

    private final RpgItemType itemType;
    private final String title;

    RpgMenuType(RpgItemType itemType, String title) {
        this.itemType = itemType;
        this.title = title;
    }

    public RpgItemType getItemType() {
        return itemType;
    }

    public String getTitle() {
        return title;
    }
}
