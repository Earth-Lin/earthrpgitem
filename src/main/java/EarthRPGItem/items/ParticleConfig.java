package EarthRPGItem.items;

public record ParticleConfig(
        boolean enabled,
        String particle,
        String block,
        String effect,
        int count,
        double radius
) {
}
