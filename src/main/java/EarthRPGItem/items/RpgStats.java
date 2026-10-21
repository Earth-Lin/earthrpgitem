package EarthRPGItem.items;

import java.util.LinkedHashMap;
import java.util.Map;

public record RpgStats(
        double attack, double attackPercent, double armor, double armorPercent, double critChance, double critDamage, double critDamagePercent, double attackSpeed,
        double health, double vitality, double recovery, double recoveryPercent, double lifeSteal, double lifeStealPercent, double magicDamage, double magicDamagePercent,
        double bowDamage, double bowDamagePercent, double exDraw, double fixPerSecond, int extraDurability, double envArmor, double envArmorPercent,
        double magicArmor, double magicArmorPercent, double resistance, double bowArmor, double bowArmorPercent,
        double critArmor, double critArmorPercent, double evasion, double armorPen,
        double trueDamage, double backstrikeDamage, double backstrikeDamagePercent, double assDamage, double assDamagePercent,
        double pctDamage, double pstDamage, double attackRange, double parry, double counterAttack,
        double dodge, double block, double determination, double cad,
        double iceDamage, double iceDamagePercent, double fireDamage, double fireDamagePercent,
        double lightDamage, double lightDamagePercent, double darkDamage, double darkDamagePercent,
        double iceArmor, double iceArmorPercent, double fireArmor, double fireArmorPercent,
        double lightArmor, double lightArmorPercent, double darkArmor, double darkArmorPercent
) {

    public static RpgStats empty() {
        return fromLore(Map.of());
    }

    public static RpgStats attackOnly(double attack) {
        return fromLore(Map.of("attack", attack)); /* 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0); */
    }

    public static RpgStats fromLore(Map<String, Double> values) {
        return new RpgStats(
                value(values, "attack"), value(values, "attackpercent"), value(values, "armor"), value(values, "armorpercent"), value(values, "critchance"), value(values, "critdamage"), value(values, "critdamagepercent"), value(values, "attackspeed"),
                value(values, "health"), value(values, "vitality"), value(values, "recovery"), value(values, "recoverypercent"), value(values, "lifesteal"), value(values, "lifestealpercent"), value(values, "magicdamage"), value(values, "magicdamagepercent"),
                value(values, "bowdamage"), value(values, "bowdamagepercent"), value(values, "exdraw"), value(values, "fix"), (int) Math.round(value(values, "exdur")), value(values, "envarmor"), value(values, "envarmorpercent"),
                value(values, "magicarmor"), value(values, "magicarmorpercent"), value(values, "resistance"), value(values, "bowarmor"), value(values, "bowarmorpercent"),
                value(values, "critarmor"), value(values, "critarmorpercent"), value(values, "evasion"), value(values, "armorpen"),
                value(values, "truedamage"), value(values, "backstrikedamage"), value(values, "backstrikedamagepercent"), value(values, "assdamage"), value(values, "assdamagepercent"),
                value(values, "pctdamage"), value(values, "pstdamage"), value(values, "attackrange"), value(values, "parry"), value(values, "counterattack"),
                value(values, "dodge"), value(values, "block"), value(values, "determination"), value(values, "cad"),
                value(values, "icedamage"), value(values, "icedamagepercent"), value(values, "firedamage"), value(values, "firedamagepercent"),
                value(values, "lightdamage"), value(values, "lightdamagepercent"), value(values, "darkdamage"), value(values, "darkdamagepercent"),
                value(values, "icearmor"), value(values, "icearmorpercent"), value(values, "firearmor"), value(values, "firearmorpercent"),
                value(values, "lightarmor"), value(values, "lightarmorpercent"), value(values, "darkarmor"), value(values, "darkarmorpercent")
        );
    }

    private static double value(Map<String, Double> values, String key) {
        return values.getOrDefault(key, 0.0);
    }

    /**
     * The non-zero components under exactly the lowercase keys {@link #fromLore(Map)} reads, so a
     * stat block survives a round trip through a string. Zero components are omitted because a
     * missing key already reads back as zero, which keeps a persisted modifier small.
     */
    public Map<String, Double> toMap() {
        Map<String, Double> values = new LinkedHashMap<>();
        put(values, "attack", attack); put(values, "attackpercent", attackPercent); put(values, "armor", armor); put(values, "armorpercent", armorPercent);
        put(values, "critchance", critChance); put(values, "critdamage", critDamage); put(values, "critdamagepercent", critDamagePercent); put(values, "attackspeed", attackSpeed);
        put(values, "health", health); put(values, "vitality", vitality); put(values, "recovery", recovery); put(values, "recoverypercent", recoveryPercent);
        put(values, "lifesteal", lifeSteal); put(values, "lifestealpercent", lifeStealPercent); put(values, "magicdamage", magicDamage); put(values, "magicdamagepercent", magicDamagePercent);
        put(values, "bowdamage", bowDamage); put(values, "bowdamagepercent", bowDamagePercent); put(values, "exdraw", exDraw); put(values, "fix", fixPerSecond); put(values, "exdur", extraDurability);
        put(values, "envarmor", envArmor); put(values, "envarmorpercent", envArmorPercent);
        put(values, "magicarmor", magicArmor); put(values, "magicarmorpercent", magicArmorPercent); put(values, "resistance", resistance); put(values, "bowarmor", bowArmor); put(values, "bowarmorpercent", bowArmorPercent);
        put(values, "critarmor", critArmor); put(values, "critarmorpercent", critArmorPercent); put(values, "evasion", evasion); put(values, "armorpen", armorPen);
        put(values, "truedamage", trueDamage); put(values, "backstrikedamage", backstrikeDamage); put(values, "backstrikedamagepercent", backstrikeDamagePercent); put(values, "assdamage", assDamage); put(values, "assdamagepercent", assDamagePercent);
        put(values, "pctdamage", pctDamage); put(values, "pstdamage", pstDamage); put(values, "attackrange", attackRange); put(values, "parry", parry); put(values, "counterattack", counterAttack);
        put(values, "dodge", dodge); put(values, "block", block); put(values, "determination", determination); put(values, "cad", cad);
        put(values, "icedamage", iceDamage); put(values, "icedamagepercent", iceDamagePercent); put(values, "firedamage", fireDamage); put(values, "firedamagepercent", fireDamagePercent);
        put(values, "lightdamage", lightDamage); put(values, "lightdamagepercent", lightDamagePercent); put(values, "darkdamage", darkDamage); put(values, "darkdamagepercent", darkDamagePercent);
        put(values, "icearmor", iceArmor); put(values, "icearmorpercent", iceArmorPercent); put(values, "firearmor", fireArmor); put(values, "firearmorpercent", fireArmorPercent);
        put(values, "lightarmor", lightArmor); put(values, "lightarmorpercent", lightArmorPercent); put(values, "darkarmor", darkArmor); put(values, "darkarmorpercent", darkArmorPercent);
        return values;
    }

    private static void put(Map<String, Double> values, String key, double value) {
        if (value != 0) values.put(key, value);
    }

    public RpgStats add(RpgItem item) {
        return add(item, Map.of());
    }

    public RpgStats add(RpgItem item, Map<String, Integer> rolled) {
        return new RpgStats(
                attack + value(rolled, "attack", item.attack()), attackPercent + value(rolled, "attackpercent", item.attackPercent()), armor + value(rolled, "armor", item.armor()), armorPercent + value(rolled, "armorpercent", item.armorPercent()), critChance + value(rolled, "critchance", item.critChance()), critDamage + value(rolled, "critdamage", item.critDamage()), critDamagePercent + value(rolled, "critdamagepercent", item.critDamagePercent()), attackSpeed + value(rolled, "attackspeed", item.attackSpeed()),
                health + value(rolled, "health", item.health()), vitality + value(rolled, "vitality", item.vitality()), recovery + value(rolled, "recovery", item.recovery()), recoveryPercent + value(rolled, "recoverypercent", item.recoveryPercent()), lifeSteal + value(rolled, "lifesteal", item.lifeSteal()), lifeStealPercent + value(rolled, "lifestealpercent", item.lifeStealPercent()), magicDamage + value(rolled, "magicdamage", item.magicDamage()), magicDamagePercent + value(rolled, "magicdamagepercent", item.magicDamagePercent()),
                bowDamage + value(rolled, "bowdamage", item.bowDamage()), bowDamagePercent + value(rolled, "bowdamagepercent", item.bowDamagePercent()), exDraw + value(rolled, "exdraw", item.exDraw()), fixPerSecond + value(rolled, "fix", item.fixPerSecond()), (int) Math.round(extraDurability + value(rolled, "exdur", item.extraDurability())), envArmor + value(rolled, "envarmor", item.envArmor()), envArmorPercent + value(rolled, "envarmorpercent", item.envArmorPercent()),
                magicArmor + value(rolled, "magicarmor", item.magicArmor()), magicArmorPercent + value(rolled, "magicarmorpercent", item.magicArmorPercent()), resistance + value(rolled, "resistance", item.resistance()), bowArmor + value(rolled, "bowarmor", item.bowArmor()), bowArmorPercent + value(rolled, "bowarmorpercent", item.bowArmorPercent()),
                critArmor + value(rolled, "critarmor", item.critArmor()), critArmorPercent + value(rolled, "critarmorpercent", item.critArmorPercent()), evasion + value(rolled, "evasion", item.evasion()), armorPen + value(rolled, "armorpen", item.armorPen()),
                trueDamage + value(rolled, "truedamage", item.trueDamage()), backstrikeDamage + value(rolled, "backstrikedamage", item.backstrikeDamage()), backstrikeDamagePercent + value(rolled, "backstrikedamagepercent", item.backstrikeDamagePercent()), assDamage + value(rolled, "assdamage", item.assDamage()), assDamagePercent + value(rolled, "assdamagepercent", item.assDamagePercent()),
                pctDamage + value(rolled, "pctdamage", item.pctDamage()), pstDamage + value(rolled, "pstdamage", item.pstDamage()), attackRange + value(rolled, "attackrange", item.attackRange()), parry + value(rolled, "parry", item.parry()), counterAttack + value(rolled, "counterattack", item.counterAttack()),
                dodge + value(rolled, "dodge", item.dodge()), block + value(rolled, "block", item.block()), determination + value(rolled, "determination", item.determination()), cad + value(rolled, "cad", item.cad()),
                iceDamage + value(rolled, "icedamage", item.iceDamage()), iceDamagePercent + value(rolled, "icedamagepercent", item.iceDamagePercent()), fireDamage + value(rolled, "firedamage", item.fireDamage()), fireDamagePercent + value(rolled, "firedamagepercent", item.fireDamagePercent()),
                lightDamage + value(rolled, "lightdamage", item.lightDamage()), lightDamagePercent + value(rolled, "lightdamagepercent", item.lightDamagePercent()), darkDamage + value(rolled, "darkdamage", item.darkDamage()), darkDamagePercent + value(rolled, "darkdamagepercent", item.darkDamagePercent()),
                iceArmor + value(rolled, "icearmor", item.iceArmor()), iceArmorPercent + value(rolled, "icearmorpercent", item.iceArmorPercent()), fireArmor + value(rolled, "firearmor", item.fireArmor()), fireArmorPercent + value(rolled, "firearmorpercent", item.fireArmorPercent()),
                lightArmor + value(rolled, "lightarmor", item.lightArmor()), lightArmorPercent + value(rolled, "lightarmorpercent", item.lightArmorPercent()), darkArmor + value(rolled, "darkarmor", item.darkArmor()), darkArmorPercent + value(rolled, "darkarmorpercent", item.darkArmorPercent())
        );
    }

    private static double value(Map<String, Integer> rolled, String key, double fallback) {
        Integer value = rolled.get(key);
        return value == null ? fallback : value;
    }

    public RpgStats addAccessoryStats(RpgStats other) {
        return new RpgStats(
                attack + other.attack(), attackPercent + other.attackPercent(), armor + other.armor(), armorPercent + other.armorPercent(), critChance + other.critChance(), critDamage + other.critDamage(), critDamagePercent + other.critDamagePercent(), attackSpeed + other.attackSpeed(),
                health + other.health(), vitality + other.vitality(), recovery + other.recovery(), recoveryPercent + other.recoveryPercent(), lifeSteal + other.lifeSteal(), lifeStealPercent + other.lifeStealPercent(), magicDamage + other.magicDamage(), magicDamagePercent + other.magicDamagePercent(),
                bowDamage + other.bowDamage(), bowDamagePercent + other.bowDamagePercent(), exDraw + other.exDraw(), fixPerSecond + other.fixPerSecond(), extraDurability + other.extraDurability(), envArmor + other.envArmor(), envArmorPercent + other.envArmorPercent(),
                magicArmor + other.magicArmor(), magicArmorPercent + other.magicArmorPercent(), resistance + other.resistance(), bowArmor + other.bowArmor(), bowArmorPercent + other.bowArmorPercent(),
                critArmor + other.critArmor(), critArmorPercent + other.critArmorPercent(), evasion + other.evasion(), armorPen + other.armorPen(),
                trueDamage + other.trueDamage(), backstrikeDamage + other.backstrikeDamage(), backstrikeDamagePercent + other.backstrikeDamagePercent(), assDamage + other.assDamage(), assDamagePercent + other.assDamagePercent(),
                pctDamage + other.pctDamage(), pstDamage + other.pstDamage(), attackRange + other.attackRange(), parry + other.parry(), counterAttack + other.counterAttack(),
                dodge + other.dodge(), block + other.block(), determination + other.determination(), cad + other.cad(),
                iceDamage + other.iceDamage(), iceDamagePercent + other.iceDamagePercent(), fireDamage + other.fireDamage(), fireDamagePercent + other.fireDamagePercent(),
                lightDamage + other.lightDamage(), lightDamagePercent + other.lightDamagePercent(), darkDamage + other.darkDamage(), darkDamagePercent + other.darkDamagePercent(),
                iceArmor + other.iceArmor(), iceArmorPercent + other.iceArmorPercent(), fireArmor + other.fireArmor(), fireArmorPercent + other.fireArmorPercent(),
                lightArmor + other.lightArmor(), lightArmorPercent + other.lightArmorPercent(), darkArmor + other.darkArmor(), darkArmorPercent + other.darkArmorPercent()
        );
    }

    public boolean hasAny() {
        return attack != 0 || attackPercent != 0 || armor != 0 || armorPercent != 0 || critChance != 0 || critDamage != 0 || attackSpeed != 0
                || health != 0 || vitality != 0 || recovery != 0 || recoveryPercent != 0 || lifeSteal != 0 || lifeStealPercent != 0 || magicDamage != 0 || magicDamagePercent != 0
                || bowDamage != 0 || bowDamagePercent != 0 || exDraw != 0 || fixPerSecond != 0 || extraDurability != 0 || envArmor != 0 || envArmorPercent != 0
                || magicArmor != 0 || magicArmorPercent != 0 || resistance != 0 || bowArmor != 0 || bowArmorPercent != 0
                || critArmor != 0 || critArmorPercent != 0 || evasion != 0 || armorPen != 0
                || trueDamage != 0 || backstrikeDamage != 0 || backstrikeDamagePercent != 0 || assDamage != 0 || assDamagePercent != 0
                || pctDamage != 0 || pstDamage != 0 || attackRange != 0 || parry != 0 || counterAttack != 0
                || dodge != 0 || block != 0 || determination != 0 || cad != 0
                || iceDamage != 0 || iceDamagePercent != 0 || fireDamage != 0 || fireDamagePercent != 0
                || lightDamage != 0 || lightDamagePercent != 0 || darkDamage != 0 || darkDamagePercent != 0
                || iceArmor != 0 || iceArmorPercent != 0 || fireArmor != 0 || fireArmorPercent != 0
                || lightArmor != 0 || lightArmorPercent != 0 || darkArmor != 0 || darkArmorPercent != 0;
    }

    public boolean hasOffensiveStats() {
        return attack != 0 || attackPercent != 0 || critChance != 0 || critDamage != 0 || attackSpeed != 0
                || magicDamage != 0 || magicDamagePercent != 0 || bowDamage != 0 || bowDamagePercent != 0 || armorPen != 0 || trueDamage != 0
                || backstrikeDamage != 0 || assDamage != 0 || assDamagePercent != 0
                || pctDamage != 0 || pstDamage != 0 || attackRange != 0
                || iceDamage != 0 || iceDamagePercent != 0 || fireDamage != 0 || fireDamagePercent != 0
                || lightDamage != 0 || lightDamagePercent != 0 || darkDamage != 0 || darkDamagePercent != 0;
    }

    public double effectiveAttack() {
        return attack * (1 + attackPercent / 100.0);
    }

    public double effectiveMagicDamage() {
        return magicDamage * (1 + magicDamagePercent / 100.0);
    }
}
