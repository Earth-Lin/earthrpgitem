package EarthRPGItem.items;

public enum RpgItemType {
    ARMOR("Armor"),
    TOOL("Tool"),
    WEAPON("Weapon"),
    ITEMS("Items"),
    ACCESSORY("Accessory");

    private final String folderName;

    RpgItemType(String folderName) {
        this.folderName = folderName;
    }

    public String getFolderName() {
        return folderName;
    }
}
