package EarthRPGItem.items;

/**
 * 弹射物命中爆炸（爆炸箭）。作为 {@link ItemProjectile} 的一个可选段。
 *
 * <p>命中生物或方块时，在落点做一次<b>粒子模拟的爆炸</b>：不生成爆炸实体、不破坏方块、
 * 不点燃，只画粒子、放音效，并对 {@code radius} 范围内的生物按
 * {@code damage + weapon-percent} 结算一次技能伤害。伤害走与其它技能相同的管线
 * （伤害来源、破甲、暴击、伤害数字等）。
 *
 * @param radius        爆炸半径（格）
 * @param damage        固定爆炸伤害（可 0，只靠 weapon-percent）
 * @param weaponPercent 按武器伤害来源计算的百分比（可 0，只靠固定伤害）
 * @param particle      模拟爆炸的粒子
 * @param sound         爆炸音效
 * @param damageType    爆炸伤害类型（MAGIC / PHYSICAL / ...）。原先是写死的 PHYSICAL，
 *                      导致法师的爆炸类法术也按物理结算
 * @param weaponPercentSources 倍率取用的伤害来源<b>排除列表</b>（空 = 不排除任何来源）
 */
public record ProjectileExplosion(double radius, double damage, double weaponPercent,
                                  ParticleConfig particle, SoundConfig sound,
                                  String damageType, java.util.List<String> weaponPercentSources) {
    public boolean enabled() {
        return radius > 0 && (damage > 0 || weaponPercent > 0);
    }
}
