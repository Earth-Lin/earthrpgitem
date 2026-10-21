package EarthRPGItem.items;

/**
 * 上挑：沿一条<b>固定</b>的上挑路线画粒子（从身前低处斜向上），命中的生物被击飞。
 * <p>
 * 击飞速度的竖直分量 = {@code launch}，水平分量 = 朝前（玩家朝向）的 {@code 0.4 × launch}，
 * 所以目标是被「略微带飞出去」而不是原地直上直下；水平分量小于竖直分量，仍以上挑为主。
 */
public record UppercutSkill(DamageSkill damage, double distance, double radius, double height,
                            double launch, ParticleConfig particle, SoundConfig sound) {
    public boolean enabled() {
        return damage != null && (damage.damage() > 0 || damage.weaponPercent() > 0);
    }
}
