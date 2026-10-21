package EarthRPGItem.api;

import EarthRPGItem.collection.CollectionKind;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * 采集经验事件：一个方块被成功采集后触发，把这次采集的经验交给其他插件。
 * <p>
 * <b>EarthRPGItem 自己不处理经验等级。</b>它只负责算「这次采集给多少经验、属于哪个分类」，
 * 然后把这个事件抛出去，由你的经验插件监听并加进它自己的等级系统，例如：
 *
 * <pre>{@code
 * @EventHandler
 * public void onGathering(GatheringExperienceEvent event) {
 *     MyLevels.addExperience(event.getPlayer(), event.getCategory(), event.getAmount());
 * }
 * }</pre>
 *
 * <h2>来源</h2>
 * 事件只在一条路径上触发：**资源点采集完成**（{@code CollectionManager} 走完进度条后发一次）。
 * 原版方块（普通挖矿 / 砍树 / 收作物）不会触发本事件——原版经验已禁用。
 * 另外 {@code EarthRPGItemAPI#awardGatheringExperience} 手动发放时也会触发本事件
 * （此时 {@link #isCollectionPoint()} 为 false）。
 *
 * <p>事件只在<b>主线程</b>触发：资源点完成在服务器 tick 里、手动发放请从主线程调用，
 * 监听器可以直接安全地操作 Bukkit API。{@link #getAmount()} 固定为资源点里配置的数值，
 * 本插件不做任何缩放/暴击/倍率——那属于经验插件自己的事。
 */
public class GatheringExperienceEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final CollectionKind category;
    private final double amount;
    private final Material blockType;
    private final boolean collectionPoint;

    public GatheringExperienceEvent(Player player, CollectionKind category, double amount,
                                    Material blockType, boolean collectionPoint) {
        // Paper：super(true) 是「只能从异步线程触发」的事件，从主线程（资源点完成的
        // 服务器 tick）触发会抛 IllegalStateException。这里必须是同步事件。
        super(false);
        this.player = player;
        this.category = category;
        this.amount = amount;
        this.blockType = blockType;
        this.collectionPoint = collectionPoint;
    }

    /** 获得经验的玩家。 */
    @NotNull
    public Player getPlayer() {
        return player;
    }

    /** 本次经验属于哪个分类：挖矿 / 砍树 / 农业。 */
    @NotNull
    public CollectionKind getCategory() {
        return category;
    }

    /** 经验数值（配置里写的就是多少）。 */
    public double getAmount() {
        return amount;
    }

    /** 被采集的方块材质。 */
    @NotNull
    public Material getBlockType() {
        return blockType;
    }

    /** true = 来自资源点采集完成；false = 来自 API 手动发放（原版方块不会触发）。 */
    public boolean isCollectionPoint() {
        return collectionPoint;
    }

    @Override
    @NotNull
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}