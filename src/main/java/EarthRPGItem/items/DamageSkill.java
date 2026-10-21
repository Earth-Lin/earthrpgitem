package EarthRPGItem.items;

public record DamageSkill(String mode, double damage, double weaponPercent, double length, double radius, double height,
                          boolean repeating, double durationSeconds, double intervalSeconds, boolean hitOncePerCast,
                          boolean pierce, boolean pierceBlocks, int spreadCount, double spreadAngle, boolean shotgun, double spreadRadius,
                          String damageType, java.util.List<String> weaponPercentSources, boolean canCrit,
                          ParticleConfig particle, SoundConfig sound, double knockback) {
}
