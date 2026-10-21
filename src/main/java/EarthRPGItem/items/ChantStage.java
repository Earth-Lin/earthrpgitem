package EarthRPGItem.items;

import java.util.List;

public record ChantStage(
        String text,
        ParticleConfig particle,
        SoundConfig sound,
        List<BuffSkill> buffs
) {
    public ChantStage(String text, ParticleConfig particle, SoundConfig sound) {
        this(text, particle, sound, List.of());
    }
}
