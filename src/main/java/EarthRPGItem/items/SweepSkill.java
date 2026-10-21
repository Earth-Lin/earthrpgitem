package EarthRPGItem.items;

public record SweepSkill(DamageSkill damage, double distance, double radius, double height,
                         double angleDegrees, ParticleConfig particle, SoundConfig sound) {
    public boolean enabled() {
        return damage != null && (damage.damage() > 0 || damage.weaponPercent() > 0);
    }
}
