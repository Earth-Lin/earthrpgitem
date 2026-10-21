package EarthRPGItem.items;

public record SoundConfig(
        boolean enabled,
        String sound,
        float volume,
        float pitch
) {
}
