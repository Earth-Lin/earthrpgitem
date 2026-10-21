package EarthRPGItem.items;

/**
 * 幻影剑舞（Phantom Blade Dance）—— 短时间内连续挥砍多下，挥砍结束后再射出一道剑气飞向前方。
 *
 * <p>挥砍部分复用横扫的弧线：每一次挥砍都随机旋转弧面（{@code tiltMinDegrees}..{@code tiltMaxDegrees}，
 * 0＝水平、90＝竖直），并在随机位置生成（{@code jitter} 为随机水平偏移半径），所以看起来像多道
 * 幻影从不同角度劈过来。每一下单独结算伤害（{@code slash}），同一个目标会被多下连续命中。
 *
 * <p>挥砍全部结束后生成一道剑气（{@code aura}），从随机位置朝视线前方飞出，
 * 沿途对碰到的目标结算伤害，直到撞到方块或飞满 {@code auraMaxDistance}。
 *
 * <p><b>剑气就是「挥击那一道弧」整体飞出去</b>：飞行途中每一 tick 都用与挥击/横扫**完全相同**的
 * {@code drawTiltedArc} 画一条平面弧，弧面包含飞行方向，所以玩家看到的是自己刚挥出的那道弧形
 * 一路飞向远方，而不是一个立在飞行方向上、从背后看呈「^」形的月牙。
 * {@code auraArcRadius} / {@code auraArcAngleDegrees} 决定这道弧的大小与张角（默认与挥击一致，
 * 所以剑气看起来就是挥砍那道弧本身），{@code auraTiltMinDegrees}..{@code auraTiltMaxDegrees}
 * 决定弧面朝向的**随机范围**（默认 0..180，与挥砍段一致）——剑气飞出时随机一个朝向，
 * 所以不会永远像横扫那样平着飞。粒子同样沿用挥砍段的配置。
 *
 * <p><b>剑气版（{@code slashAura = true}）</b>：把前面的**每一道挥砍都换成剑气**——
 * 每一下不再是近战弧，而是射出一道会飞的剑气（用 {@code aura} 段的飞行与造型参数、
 * {@code slash} 段的伤害），按 {@code slashIntervalTicks} 间隔依次射出。
 * 因为每一道都已经是剑气了，所以**不再单独发射收尾剑气**（原来的最后一道剑气删除）。
 */
public record PhantomBladeSkill(DamageSkill slash, int slashCount, long slashIntervalTicks,
                                double distance, double radius, double height, double angleDegrees,
                                double tiltMinDegrees, double tiltMaxDegrees, double jitter,
                                DamageSkill aura, double auraSpeed, double auraMaxDistance, double auraRadius,
                                double auraArcRadius, double auraArcAngleDegrees,
                                double auraTiltMinDegrees, double auraTiltMaxDegrees,
                                boolean slashAura) {
    public boolean enabled() {
        return (slash != null && (slash.damage() > 0 || slash.weaponPercent() > 0))
                || (aura != null && (aura.damage() > 0 || aura.weaponPercent() > 0));
    }
}