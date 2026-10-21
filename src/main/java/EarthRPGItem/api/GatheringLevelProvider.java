package EarthRPGItem.api;

import EarthRPGItem.collection.CollectionKind;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * 采集等级提供者（预留接口）：由「负责经验等级的插件」实现并注册。
 *
 * <p><b>注意：资源点的采掘等级门槛只看手持工具的采掘等级
 * （attributes 的 MiningLevel / ForestLevel / FarmingLevel），不读取 provider。</b>
 * 本接口保留用于 API 兼容与第三方查询——如果你要展示玩家等级、做独立统计，可以注册它，
 * 但 EarthRPGItem 的采集门槛判定不依赖它。
 *
 * <pre>{@code
 * EarthRPGItemAPI api = ...; // 通过 ServicesManager 获取
 * api.registerGatheringLevelProvider((player, kind) -> MyLevels.getLevel(player, kind));
 * }</pre>
 *
 * <p>EarthRPGItem 自己不处理经验等级：经验通过 {@link GatheringExperienceEvent} 发给你的
 * 插件，等级存在你那里，这里只是预留一个查询入口。
 */
public interface GatheringLevelProvider {

    /**
     * 玩家在该分类下的等级。
     *
     * @param player   玩家
     * @param category 挖矿 / 砍树 / 农业
     * @return 等级，推荐至少为 0。不追踪某个分类时请返回 0，
     *         不要返回负数（会被按 0 算），也不要抛异常
     */
    int getLevel(@NotNull Player player, @NotNull CollectionKind category);

    /**
     * 该 provider 是否负责某个分类。
     *
     * <p>默认实现返回 {@code true}（全部负责）。经验插件如果只做了挖矿等级、没做砍树/农业，
     * 可以重写它返回「只对自己做的分类返回 true」。
     *
     * @return true 表示这个 provider 愿意提供该分类的等级查询
     */
    default boolean supports(@NotNull CollectionKind category) {
        return true;
    }
}