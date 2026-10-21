package EarthRPGItem.items;

/**
 * 蹦山击：先把玩家抛到 {@code leapHeight} 格高（默认 5），随后以 {@code diveAngleDegrees}
 * 角度（默认 45°）朝视线前方俯冲；落地时
 * <ul>
 *   <li>以落点为中心画一圈<b>缩减过的</b>冲击波粒子（沿用震地的造型，只是更小）；</li>
 *   <li>对范围内的生物造成伤害并<b>轻微</b>击飞（{@code knockUp} 很小）；</li>
 *   <li>再放一次小的落地爆炸（{@code explosionParticle}）。</li>
 * </ul>
 */
public record LeapStrikeSkill(DamageSkill damage, double leapHeight, double diveAngleDegrees,
                              double impactRadius, double knockUp, double explosionRadius,
                              ParticleConfig shockwaveParticle, ParticleConfig explosionParticle,
                              SoundConfig sound, SoundConfig impactSound) {
    public boolean enabled() {
        return damage != null && (damage.damage() > 0 || damage.weaponPercent() > 0);
    }
}
