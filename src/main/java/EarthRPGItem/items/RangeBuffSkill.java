package EarthRPGItem.items;

import java.util.List;

public record RangeBuffSkill(double radius, double height, String target, boolean includeSelf, double refreshSeconds, List<BuffSkill> effects, ParticleConfig particle) {
}
