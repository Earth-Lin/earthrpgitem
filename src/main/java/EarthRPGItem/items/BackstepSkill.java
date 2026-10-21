package EarthRPGItem.items;

/**
 * 后撤：与突进（{@link DashSkill}）同一套位移参数，唯一的区别是方向固定为<b>向后</b>
 * （视线方向取反）。其余行为完全一致：位移、技能闪避、无敌帧、粒子、音效。
 */
public record BackstepSkill(double distance, int durationTicks, int invulnerabilityTicks,
                            ParticleConfig particle, SoundConfig sound) {
    public boolean enabled() {
        return distance > 0;
    }
}
