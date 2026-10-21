package EarthRPGItem.items;

import org.bukkit.Material;
import java.util.List;

public record MagicCircleSkill(
        DamageSkill damage,
        double distance,
        double radius,
        double height,
        double durationSeconds,
        double intervalSeconds,
        int burstCount,
        ParticleConfig areaParticle,
        ParticleConfig burstParticle,
        SoundConfig sound,
        boolean useArmorStand,
        Material centerMaterial,
        String target,
        boolean includeSelf,
        List<BuffSkill> buffs,
        double buffDurationSeconds,
        RpgStats attributeBoost,
        double attributeDurationSeconds
) {
    public boolean enabled() {
        boolean hasDamage = damage != null && (damage.damage() > 0 || damage.weaponPercent() > 0);
        return durationSeconds > 0 && (hasDamage || !buffs.isEmpty() || attributeBoost != null);
    }
}
