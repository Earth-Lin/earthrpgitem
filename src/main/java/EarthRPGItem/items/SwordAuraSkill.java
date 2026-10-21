package EarthRPGItem.items;

/**
 * 百人一太刀 —— 直接发射一道剑气飞向前方，对沿途碰到的目标结算伤害。
 *
 * <p>「一道剑气」与幻影剑舞的收尾剑气是同一个东西：飞行途中每一 tick 都用与挥击/横扫**完全相同**
 * 的 {@code drawTiltedArc} 画一条平面弧（弧面包含飞行方向），所以玩家看到的是自己挥出的那道弧形
 * 一路飞向远方。{@code arcRadius} / {@code arcAngleDegrees} 决定这道弧的大小与张角，
 * {@code tiltMinDegrees}..{@code tiltMaxDegrees} 决定弧面朝向的随机范围（默认 0..180，与挥砍一致），
 * 粒子与音效直接写在本节下（{@code particle} / {@code sound}），不继承任何其他段。
 *
 * <p>伤害由 {@code damage}（一个 {@link DamageSkill}）描述，默认 200% 武器伤害；
 * 命中判定与幻影剑舞剑气共用 {@code scheduleAuraFlight}：按目标的包围盒判定
 * （竖直重叠 + 水平距离），不会因为剑气在眼睛高度飞行而漏掉脚下的地面怪。
 */
public record SwordAuraSkill(DamageSkill damage, double distance, double jitter,
                             double speed, double maxDistance, double auraRadius,
                             double arcRadius, double arcAngleDegrees,
                             double tiltMinDegrees, double tiltMaxDegrees) {
    public boolean enabled() {
        return damage != null && (damage.damage() > 0 || damage.weaponPercent() > 0);
    }
}
