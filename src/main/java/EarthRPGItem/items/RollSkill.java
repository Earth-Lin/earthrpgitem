package EarthRPGItem.items;

public record RollSkill(double distance, int durationTicks, int invulnerabilityTicks, ParticleConfig particle, SoundConfig sound) {
    public boolean enabled() { return distance > 0; }
}
