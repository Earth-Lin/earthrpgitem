package EarthRPGItem.api;

import EarthRPGItem.collection.CollectionKind;
import EarthRPGItem.items.RpgStats;
import org.bukkit.Material;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Set;

/**
 * Attribute API for other plugins.
 * <p>
 * Obtain it from the service manager — never cast the plugin or reach into its managers, so this
 * contract stays stable:
 *
 * <pre>{@code
 * RegisteredServiceProvider<EarthRPGItemAPI> provider =
 *         Bukkit.getServicesManager().getRegistration(EarthRPGItemAPI.class);
 * if (provider == null) return; // EarthRPGItem is not installed
 * EarthRPGItemAPI api = provider.getProvider();
 * }</pre>
 *
 * <h2>Sources</h2>
 * Every modifier belongs to a caller-chosen {@code source} name, for example {@code "myplugin:aura"}.
 * Sources are independent: two plugins may each hold a modifier on the same player without
 * overwriting one another, and one source expiring never removes another's. Names must match
 * {@code [A-Za-z0-9_.:-]{1,64}} and are matched case-insensitively; the name {@code earthrpgitem}
 * is reserved for the plugin's own skills, so writing or removing it is rejected. Reading it is
 * allowed, and {@link #clearModifiers} leaves it in place.
 *
 * <h2>Duration</h2>
 * A {@code durationSeconds} of {@code 0} or less means <b>permanent</b>. For a player that is
 * written to their persistent data and survives a restart, and it stays until
 * {@link #removeModifier} or {@link #clearModifiers} is called. A positive duration expires on its
 * own and is never persisted.
 *
 * <h2>Replacing versus adding</h2>
 * {@link #setModifier} replaces this source's whole modifier, which makes it safe to call every
 * tick from an aura or a repeating task. {@link #addModifier} accumulates onto whatever this source
 * already holds, which is only appropriate for genuinely stacking effects.
 *
 * <h2>What takes effect when</h2>
 * Combat attributes (attack, crit, armor, magic and element damage, and so on) are read whenever
 * the plugin settles damage, so they apply immediately. {@code Health} and {@code Vitality} are
 * pushed to the player's maximum health by the plugin's one-second attribute task. Attributes that
 * have no RPG equivalent at all — movement speed, jump strength, knockback resistance — are not
 * part of a stat block; use {@link #setVanillaModifier} for those.
 *
 * <p>All methods must be called from the main server thread.
 */
public interface EarthRPGItemAPI {

    /**
     * The entity's full active stats: equipment, accessories, and every external modifier,
     * after the plugin's feature switches have been applied. Read-only.
     */
    RpgStats getStats(LivingEntity entity);

    /** What this one source currently contributes, or an empty block when it holds nothing. */
    RpgStats getModifier(LivingEntity entity, String source);

    /** Every source currently holding a modifier on this entity. */
    Set<String> getModifierSources(LivingEntity entity);

    /** True when this source holds a modifier that is not entirely zero. */
    boolean hasModifier(LivingEntity entity, String source);

    /**
     * Replaces this source's modifier. Passing a zeroed or empty block removes it.
     *
     * @param durationSeconds seconds until it expires, or {@code <= 0} for permanent
     */
    void setModifier(LivingEntity entity, String source, RpgStats stats, double durationSeconds);

    /**
     * Convenience form of {@link #setModifier} taking the raw attribute names, so callers do not
     * need to build an {@link RpgStats}. Keys are lowercase with no separators, exactly as the item
     * lore uses them: {@code attack}, {@code attackpercent}, {@code armor}, {@code critchance},
     * {@code critdamage}, {@code attackspeed}, {@code health}, {@code vitality}, {@code magicdamage},
     * {@code bowdamage}, {@code truedamage}, {@code armorpen}, and the rest of {@code RpgStats}.
     * Unknown keys are ignored.
     */
    void setModifier(LivingEntity entity, String source, Map<String, Double> attributes, double durationSeconds);

    /**
     * Adds onto whatever this source already holds instead of replacing it. Use only for effects
     * that are meant to stack; {@link #setModifier} is the safer default.
     *
     * @param durationSeconds seconds until it expires, or {@code <= 0} for permanent
     */
    void addModifier(LivingEntity entity, String source, RpgStats stats, double durationSeconds);

    /** Removes this source's modifier. Does nothing when the source holds nothing. */
    void removeModifier(LivingEntity entity, String source);

    /**
     * Removes every external modifier on this entity, including permanent ones. The reserved
     * {@code earthrpgitem} source is kept, so this cannot strip a skill's own boost.
     */
    void clearModifiers(LivingEntity entity);

    /**
     * Adds a vanilla entity attribute modifier for attributes with no RPG stat equivalent, such as
     * {@code movement_speed}, {@code jump_strength}, {@code knockback_resistance} or
     * {@code attack_knockback}. Accepted names are the same ones item configuration uses, with or
     * without the {@code minecraft:}/{@code generic.} prefix.
     * <p>
     * Calling this again for the same source and attribute replaces the previous value.
     *
     * @param operation        {@code ADD_NUMBER}, {@code ADD_SCALAR} or {@code MULTIPLY_SCALAR_1}
     * @param durationSeconds  seconds until it expires, or {@code <= 0} until removed
     */
    void setVanillaModifier(LivingEntity entity, String source, String attributeName, double amount,
                            AttributeModifier.Operation operation, double durationSeconds);

    /** Removes every vanilla attribute modifier this source added. */
    void removeVanillaModifiers(LivingEntity entity, String source);

    // ------------------------------------------------------------------
    // 采集经验 + 挖掘等级（EarthRPGItem 自己不处理经验等级）
    // ------------------------------------------------------------------

    /**
     * 注册一个采集等级 provider。你的经验插件实现 {@link GatheringLevelProvider} 并注册，
     * EarthRPGItem 会用它的返回值做「挖掘等级」门槛（{@code gathering.yml} 的
     * {@code required-level}）。多个 provider 注册时取每个分类的最大值。
     */
    void registerGatheringLevelProvider(GatheringLevelProvider provider);

    /** 注销之前注册的 provider。 */
    void unregisterGatheringLevelProvider(GatheringLevelProvider provider);

    /**
     * 玩家在某个分类（挖矿 / 砍树 / 农业）下的等级，不带手持工具加成。
     * 没有注册 provider 时返回 0。只读，本插件不存等级。
     */
    int getGatheringLevel(Player player, CollectionKind category);

    /**
     * 手动发放一次采集经验：构造并抛出 {@link GatheringExperienceEvent}，其他插件监听
     * 这个事件把经验加进自己的等级系统。方块被采集时的经验由本插件自动发，
     * 这个方法给游戏内代码（例如别的插件想送经验）用。
     *
     * @param blockType  关联的方块材质，用于事件上下文（可为 {@code AIR}）
     */
    void awardGatheringExperience(Player player, CollectionKind category, double amount, Material blockType);
}
