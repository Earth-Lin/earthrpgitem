package EarthRPGItem.items;

import java.util.List;
import java.util.Map;

public record RpgItem(
        String id, RpgItemType type, String displayName, String material,
        String weaponType,
        double attack, double attackPercent, double armor, double armorPercent, double critChance, double critDamage, double critDamagePercent, double attackSpeed,
        double health, double vitality, double recovery, double recoveryPercent, double lifeSteal, double lifeStealPercent, double magicDamage, double magicDamagePercent,
        double bowDamage, double bowDamagePercent, double exDraw, double fixPerSecond, int fixIntervalSeconds, int extraDurability, boolean breakLimit,
        double envArmor, double envArmorPercent, double magicArmor, double magicArmorPercent, double resistance,
        double bowArmor, double bowArmorPercent, double critArmor, double critArmorPercent, double evasion,
        double armorPen, double trueDamage, double backstrikeDamage, double backstrikeDamagePercent, double assDamage, double assDamagePercent,
        double pctDamage, double pstDamage, double attackRange, double parry, double counterAttack,
        double dodge, double block, double determination, double cad,
        double iceDamage, double iceDamagePercent, double fireDamage, double fireDamagePercent,
        double lightDamage, double lightDamagePercent, double darkDamage, double darkDamagePercent,
        double iceArmor, double iceArmorPercent, double fireArmor, double fireArmorPercent,
        double lightArmor, double lightArmorPercent, double darkArmor, double darkArmorPercent,
        int level, boolean deputy, boolean soulbound, boolean consumables, boolean unbreakable, boolean hideEnchantments,
        int itemVersion, int customModelData, String leatherColor, String accessoryType, List<String> lore, Map<String, Integer> enchantments,
        Map<String, RandomAttributeRange> randomAttributes, List<VanillaAttributeConfig> vanillaAttributes,
        ParticleConfig particles, ParticleConfig slashParticles, double slashParticleLength, ParticleConfig projectileTrailParticles,
        ParticleConfig projectileHitParticles, SoundConfig sound, SoundConfig projectileTrailSound,
        SoundConfig projectileHitSound, List<BuffConfig> buffs, List<HitBuffConfig> hitBuffs,
        FSkillConfig fSkill, FSkillConfig sfSkill, FSkillConfig rSkill, FSkillConfig lSkill,
        FSkillConfig slSkill, FSkillConfig srSkill, FSkillConfig qSkill, FSkillConfig sqSkill,
        double farmingSpeed, double miningSpeed, double forestSpeed,
        double farmingFortune, double miningFortune, double forestFortune,
        // 手持该工具时给采集分类加的「挖掘等级」。用于跨过 gathering.yml 里
        // required-level 的门槛：有效等级 = 玩家等级(其他插件提供) + 这里的加成。
        double farmingLevel, double miningLevel, double forestLevel,
        int sockets
) {
}
