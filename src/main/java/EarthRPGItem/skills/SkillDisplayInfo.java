package EarthRPGItem.skills;

import EarthRPGItem.items.DamageSkill;
import EarthRPGItem.items.FSkillConfig;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Read-only summary of a skill, used for the lore lines that are shown on skill items only.
 *
 * <p>同时带上<b>固定伤害</b>与<b>攻击倍率</b>：技能的实际伤害是「固定伤害 + 攻击力 × 倍率」
 * 两部分之和，只显示倍率会让纯固定伤害的技能看起来是 0，也会让
 * {@code damage: 10, weapon-percent: 100} 这种配置少显示一半。
 *
 * <h2>为什么用一张规则表</h2>
 * 原来「技能类型」和「主伤害」是两条各写一遍的 if 链，新增技能时很容易只加一条——
 * 王之宝库就是这样漏掉的：类型显示成「通用」、攻击倍率显示成 0%，
 * 而技能本身其实工作正常。现在两者都从 {@link #RULES} 这一张有序表推导，
 * <b>结构上不可能再对不上</b>。
 */
public record SkillDisplayInfo(String skillType, double cooldownSeconds, boolean attackSpeedCooldown,
                               String skillGroup, double flatDamage, double weaponPercent, String damageType,
                               String disabledSection) {

    private record DamageView(double flatDamage, double weaponPercent, String damageType) {
    }

    private record SectionView(String typeName, DamageView damage) {
    }

    /** 一条识别规则：类型名 + 「配置里有没有这一段」+ 「这一段是否真的会运行」+ 怎么取主伤害。 */
    private record Rule(String typeName,
                        Predicate<FSkillConfig> present,
                        Predicate<FSkillConfig> active,
                        Function<FSkillConfig, DamageView> damage) {
    }

    /**
     * 具名技能段的识别顺序，<b>同时</b>决定「技能类型」与「主伤害」。
     *
     * <p>顺序有意义：一个键位同时配了多个技能段时，靠前的那个算主技能。
     */
    private static final List<Rule> RULES = List.of(
            new Rule("横扫", c -> c.sweep() != null, c -> c.sweep().enabled(),
                    c -> view(c.sweep().damage())),
            new Rule("挥击", c -> c.swing() != null, c -> c.swing().enabled(),
                    c -> view(c.swing().damage())),
            new Rule("幻影剑舞", c -> c.phantomBlade() != null, c -> c.phantomBlade().enabled(),
                    c -> view(c.phantomBlade().slash() != null ? c.phantomBlade().slash() : c.phantomBlade().aura())),
            new Rule("百人一太刀", c -> c.swordAura() != null, c -> c.swordAura().enabled(),
                    c -> view(c.swordAura().damage())),
            new Rule("十字斩", c -> c.crossSlash() != null, c -> c.crossSlash().enabled(),
                    c -> view(c.crossSlash().horizontalDamage() != null
                            ? c.crossSlash().horizontalDamage() : c.crossSlash().verticalDamage())),
            // 短弓只有倍率，没有固定伤害那一项。
            new Rule("短弓", c -> c.shortbow() != null, c -> c.shortbow().enabled(),
                    c -> new DamageView(0, c.shortbow().weaponPercent(), damageTypeName("PHYSICAL"))),
            new Rule("冲击波", c -> c.shockwave() != null, c -> c.shockwave().enabled(),
                    c -> view(c.shockwave().damage())),
            // 次元斩没有 enabled()，按「伤害是否大于 0」判断。
            new Rule("次元斩", c -> c.dimensionalSlash() != null,
                    c -> c.dimensionalSlash().damage() > 0 || c.dimensionalSlash().weaponPercent() > 0,
                    c -> view(c.dimensionalSlash())),
            new Rule("魔法阵", c -> c.magicCircle() != null, c -> c.magicCircle().enabled(),
                    c -> view(c.magicCircle().damage())),
            // 王之宝库的伤害挂在 gate-of-babylon.damage 下。这一条原来缺失，
            // 于是模板里的 weapon-percent: 60 显示成了 0%。
            new Rule("王之宝库", c -> c.gateOfBabylon() != null, c -> c.gateOfBabylon().enabled(),
                    c -> view(c.gateOfBabylon().damage())),
            new Rule("蹦山击", c -> c.leapStrike() != null, c -> c.leapStrike().enabled(),
                    c -> view(c.leapStrike().damage())),
            new Rule("上挑", c -> c.uppercut() != null, c -> c.uppercut().enabled(),
                    c -> view(c.uppercut().damage())),
            new Rule("伤害波", c -> !c.damages().isEmpty(), c -> !c.damages().isEmpty(),
                    c -> view(c.damages().get(0))));

    public static SkillDisplayInfo from(FSkillConfig config, String skillGroup, boolean attackSpeedTrigger) {
        String group = skillGroup == null ? "" : skillGroup;
        if (config == null) {
            return new SkillDisplayInfo("未知", 0, attackSpeedTrigger, group, 0, 0,
                    damageTypeName("PHYSICAL"), "");
        }
        SectionView selected = section(config, true);
        boolean attackSpeed = attackSpeedTrigger || config.attackSpeedCooldown()
                || (config.shortbow() != null && config.shortbow().attackSpeedCooldown());
        DamageView damage = selected == null
                ? new DamageView(0, 0, damageTypeName("PHYSICAL"))
                : selected.damage();
        return new SkillDisplayInfo(selected == null ? fallbackType(config) : selected.typeName(),
                config.cooldownSeconds(), attackSpeed, group,
                damage.flatDamage(), damage.weaponPercent(), damage.damageType(),
                disabledSection(config, selected));
    }

    /**
     * 玩家可读的伤害构成，例如「固定 10 + 攻击 100%」。
     *
     * <p>只有真的配了某一项才显示那一项：固定伤害为 0 时不写「固定 0」，
     * 两项都为 0 时说明这是纯特效 / 增益技能，而不是留一个让人误解的 0。
     */
    public String damageBreakdown() {
        boolean hasFlat = flatDamage > 0;
        boolean hasPercent = weaponPercent > 0;
        if (hasFlat && hasPercent) {
            return "固定 " + trim(flatDamage) + " + 攻击 " + trim(weaponPercent) + "%";
        }
        if (hasPercent) return "攻击 " + trim(weaponPercent) + "%";
        if (hasFlat) return "固定 " + trim(flatDamage);
        return "无（纯特效 / 增益技能）";
    }

    /**
     * 有配置、但因为 {@code enabled()=false} 而不会运行的具名技能段。
     *
     * <p>这是「伤害显示为 0」最常见的第二个原因：段落在，但某个必填项（武器数量、
     * 持续时间、材质列表）不满足，技能被判定为未启用。返回空串表示没有这种情况。
     */
    public boolean hasDisabledSection() {
        return !disabledSection.isBlank();
    }

    /** 整数不带小数点、小数不留尾随 0：避免 "10.0" 与 "2.50" 这类噪声。 */
    private static String trim(double value) {
        if (!Double.isFinite(value)) return "0";
        if (value == Math.floor(value)) return String.valueOf((long) value);
        String text = String.format(Locale.US, "%.2f", value);
        while (text.endsWith("0")) text = text.substring(0, text.length() - 1);
        if (text.endsWith(".")) text = text.substring(0, text.length() - 1);
        return text;
    }

    /** Damage types are shown in Chinese so the lore is readable in game. */
    public static String damageTypeName(String configuredType) {
        String type = configuredType == null || configuredType.isBlank()
                ? "PHYSICAL" : configuredType.toUpperCase(Locale.ROOT).replace("-", "_").replace(" ", "_");
        return switch (type) {
            case "PHYSICAL" -> "物理";
            case "MAGIC" -> "魔法";
            case "POTION" -> "药水";
            case "TRUE" -> "真实";
            case "BOW" -> "弓";
            case "PROJECTILE" -> "弹射物";
            case "ICE" -> "冰";
            case "FIRE" -> "火";
            case "LIGHT" -> "光";
            case "DARK" -> "暗";
            default -> type;
        };
    }

    /** 按 {@link #RULES} 的顺序取第一个命中的技能段；{@code requireEnabled=false} 时只要求「配置里在」。 */
    private static SectionView section(FSkillConfig config, boolean requireEnabled) {
        for (Rule rule : RULES) {
            if (!rule.present().test(config)) continue;
            if (requireEnabled && !rule.active().test(config)) continue;
            return new SectionView(rule.typeName(), rule.damage().apply(config));
        }
        return null;
    }

    /**
     * 找出「配置里在、但没启用」的那个技能段，用来解释伤害为什么是 0。
     *
     * <p>只报第一个，且已经选中的那个不算——玩家看到的类型与伤害必须自洽。
     */
    private static String disabledSection(FSkillConfig config, SectionView selected) {
        SectionView present = section(config, false);
        if (present == null) return "";
        if (selected != null && present.typeName().equals(selected.typeName())) return "";
        return present.typeName();
    }

    /** 没有可识别的具名技能段时，退回到这些「没有主伤害」的类型名。 */
    private static String fallbackType(FSkillConfig config) {
        if (!config.rangeBuffs().isEmpty()) return "范围增益";
        if (config.roll() != null && config.roll().enabled()) return "翻滚";
        if (config.dash() != null && config.dash().enabled()) return "冲刺";
        if (config.backstep() != null && config.backstep().enabled()) return "后撤";
        if (!config.buffs().isEmpty()) return "增益";
        if (!config.particles().isEmpty()) return "特效";
        return "通用";
    }

    private static DamageView view(DamageSkill damage) {
        if (damage == null) return new DamageView(0, 0, damageTypeName("PHYSICAL"));
        return new DamageView(damage.damage(), damage.weaponPercent(), damageTypeName(damage.damageType()));
    }
}
