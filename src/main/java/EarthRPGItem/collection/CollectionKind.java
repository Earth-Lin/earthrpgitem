package EarthRPGItem.collection;

import java.util.Locale;

/**
 * 采集分类：农业 / 挖矿 / 砍树。
 * <p>
 * 这套分类既驱动采集点（{@code CollectionManager}）的产出，也驱动采集经验与
 * 「挖掘等级」门槛：被采集的方块按分类发经验（通过 {@code GatheringExperienceEvent}
 * 交给其他插件），玩家在该分类下的等级（由其他插件经
 * {@code GatheringLevelProvider} 提供，加上手持工具的加成）决定能否采集对应方块。
 */
public enum CollectionKind {
    FARMING("农业"),
    MINING("挖矿"),
    FOREST("砍树");

    private final String displayName;

    CollectionKind(String displayName) {
        this.displayName = displayName;
    }

    /** 中文显示名，例如「挖矿」。 */
    public String displayName() {
        return displayName;
    }

    /** 配置里可写的别名：WOODCUTTING = 砍树 = FOREST。 */
    public static CollectionKind parse(String value) {
        if (value == null) return MINING;
        String key = value.trim().toUpperCase(Locale.ROOT);
        if (key.equals("WOODCUTTING")) return FOREST;
        if (key.equals("WOOD")) return FOREST;
        if (key.equals("LOGGING")) return FOREST;
        try {
            return valueOf(key);
        } catch (IllegalArgumentException ignored) {
            return MINING;
        }
    }
}
