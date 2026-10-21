package EarthRPGItem.items;

import java.util.List;

public record ChantSkill(
        double intervalSeconds,
        ParticleConfig defaultParticle,
        SoundConfig defaultSound,
        List<ChantStage> stages,
        boolean releaseAtStart,
        boolean releaseAtEnd,
        boolean interruptOnItemSwitch
) {
}
