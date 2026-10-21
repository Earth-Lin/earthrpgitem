package EarthRPGItem.items;

/**
 * 弹射物技能的「物品弹射物」外观与命中行为。
 *
 * <p>这里刻意<b>不用原版箭实体</b>：原版箭的穿透要满蓄力/暴击才生效、命中即被移除、
 * 也无法用任意物品图标当本体。本段用 {@code ItemDisplay} + 物品图标做弹射物本体
 * （与「王之宝库」同一套机制），自己推进轨迹、自己做线段命中判定，于是：
 * <ul>
 *   <li>本体外观就是配置的物品（光灵箭 / 箭 / 下界之星……）；</li>
 *   <li>{@code pierce} 想穿几个就穿几个，不受原版限制；</li>
 *   <li>命中判定用<b>线段</b>而非点，高速飞行也不会穿过目标；</li>
 *   <li>可配置是否被方块挡住（{@code collide-blocks}）。</li>
 * </ul>
 *
 * @param itemMaterial    弹射物本体外观的物品材质（如 SPECTRAL_ARROW = 光灵箭）
 * @param glow            是否附魔光效（让本体发亮）
 * @param pierce          能穿透几个生物。0 = 命中第一个就停；3 = 命中后还能再穿 3 个
 * @param collideBlocks   是否被方块挡住（true = 撞墙即停/爆，false = 穿墙）
 * @param hitRadius       命中判定半径（格）
 * @param maxDistance     最大飞行距离（格），到达即消散
 * @param trailParticle   飞行轨迹粒子
 * @param impactParticle  命中/消散时的粒子
 * @param impactSound     命中/消散时的音效
 * @param tiltDegrees     贴图摆正角（度）。原版箭/光灵箭贴图里的箭头是<b>斜的</b>（约 45°），
 *                        不先摆正，本体就会斜着飞。实测偏了改这个值即可，不需要重新编译
 *                        （与王之宝库的 {@code blade-tilt-degrees} 同一套做法）。
 * @param scale           本体缩放（默认 1.0）。<1 变小（霰弹/分裂箭的碎屑感），>1 变大。
 *                        注意缩放的是<b>物品图标</b>，不影响命中判定半径（那由 hit-radius 决定）。
 * @param explosion       命中爆炸（爆炸箭）；null = 无
 */
public record ItemProjectile(
        String itemMaterial,
        boolean glow,
        int pierce,
        boolean collideBlocks,
        double hitRadius,
        double maxDistance,
        ParticleConfig trailParticle,
        ParticleConfig impactParticle,
        SoundConfig impactSound,
        double tiltDegrees,
        double scale,
        ProjectileExplosion explosion
) {
    /** 是否会在命中时爆炸。 */
    public boolean explosive() {
        return explosion != null && explosion.enabled();
    }
}
