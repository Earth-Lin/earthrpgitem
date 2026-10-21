package EarthRPGItem.util;

import org.bukkit.Sound;
import org.bukkit.entity.Player;

/**
 * 界面音效统一入口。
 *
 * <p>所有 GUI 操作都从这里发声，好处是音量/音调一致，不会出现「这个按钮很响、
 * 那个按钮没声音」；要整体调整或静音，只改这一个文件。
 *
 * <p>{@link Player#playSound(org.bukkit.Location, Sound, float, float)} 是只发给
 * 该玩家本人的重载，所以不会吵到旁边的玩家。
 */
public final class GuiSounds {

    private GuiSounds() {
    }

    private static void play(Player player, Sound sound, float volume, float pitch) {
        if (player == null || !player.isOnline()) return;
        player.playSound(player.getLocation(), sound, volume, pitch);
    }

    /** 普通按钮：翻页、返回、开关切换。音量刻意压低，避免连点时吵。 */
    public static void click(Player player) {
        play(player, Sound.UI_BUTTON_CLICK, 0.55f, 1.4f);
    }

    /** 操作成功：强化成功、镶嵌成功、装备成功。 */
    public static void success(Player player) {
        play(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f);
    }

    /** 操作失败：条件不满足、被拒绝。用低音提示，不刺耳。 */
    public static void fail(Player player) {
        play(player, Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.7f);
    }

    /** 放入类动作：装备饰品、镶嵌宝石、存入材料。 */
    public static void place(Player player) {
        play(player, Sound.ITEM_ARMOR_EQUIP_GENERIC, 0.7f, 1.2f);
    }

    /** 取出类动作：卸下饰品、卸下宝石、取下技能、取回材料。 */
    public static void remove(Player player) {
        play(player, Sound.ITEM_ARMOR_EQUIP_GENERIC, 0.7f, 0.85f);
    }

    /** 删除类动作：删除技能、删除预设。 */
    public static void delete(Player player) {
        play(player, Sound.ENTITY_ITEM_BREAK, 0.7f, 0.9f);
    }
}
