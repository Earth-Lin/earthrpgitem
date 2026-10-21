package EarthRPGItem.items;

public record ShockwaveSkill(DamageSkill damage, double angleDegrees, double radius, double height,
                             double knockback, ParticleConfig particle, SoundConfig sound) {
    public boolean enabled() {
        return damage != null && (damage.damage() > 0 || damage.weaponPercent() > 0);
    }
}
