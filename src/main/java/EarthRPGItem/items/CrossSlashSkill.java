package EarthRPGItem.items;

public record CrossSlashSkill(DamageSkill horizontalDamage, DamageSkill verticalDamage,
                              double distance, double radius, double height, double angleDegrees,
                              ParticleConfig horizontalParticle, ParticleConfig verticalParticle,
                              SoundConfig horizontalSound, SoundConfig verticalSound) {
    public boolean enabled() {
        return (horizontalDamage != null && (horizontalDamage.damage() > 0 || horizontalDamage.weaponPercent() > 0))
                || (verticalDamage != null && (verticalDamage.damage() > 0 || verticalDamage.weaponPercent() > 0));
    }
}
