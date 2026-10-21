package EarthRPGItem.items;

import java.util.List;

public record FSkillConfig(
        double cooldownSeconds,
        double castTimeSeconds,
        String cooldownName,
        boolean cooldownEnabled,
        boolean attackSpeedCooldown,
        boolean messagesEnabled,
        boolean globalCooldown,
        double globalCooldownSeconds,
        List<ParticleSkill> particles,
        List<SwitchSkill> switches,
        List<BuffSkill> buffs,
        List<DamageSkill> damages,
        List<SoundSkill> sounds,
        List<BroadcastSkill> broadcasts,
        List<MessageSkill> messages,
        List<CommandSkill> commands,
        List<ChantSkill> chants,
        RollSkill roll,
        DashSkill dash,
        List<RangeBuffSkill> rangeBuffs,
        ShortbowSkill shortbow,
        DamageSkill dimensionalSlash,
        AttributeBoostSkill attributeBoost,
        SkillChain chain,
        ShockwaveSkill shockwave,
        SweepSkill sweep,
        SwingSkill swing,
        PhantomBladeSkill phantomBlade,
        SwordAuraSkill swordAura,
        CrossSlashSkill crossSlash,
        MagicCircleSkill magicCircle,
        GateOfBabylonSkill gateOfBabylon,
        BackstepSkill backstep,
        UppercutSkill uppercut,
        LeapStrikeSkill leapStrike,
        /**
         * 次元斩的「随机方向粒子竖线」（默认蓝色）。单独一个字段而不是塞进
         * {@link DamageSkill}：它是叠在爆炸之上的一层新效果，有自己的颜色与数量。
         */
        ParticleConfig dimensionalLineParticle
) {
    public boolean enabled() {
        return !particles.isEmpty() || !switches.isEmpty() || !buffs.isEmpty() || !damages.isEmpty()
                || !sounds.isEmpty() || !broadcasts.isEmpty() || !messages.isEmpty() || !commands.isEmpty() || !chants.isEmpty()
                || (roll != null && roll.enabled()) || (dash != null && dash.enabled()) || !rangeBuffs.isEmpty()
                || (shortbow != null && shortbow.enabled()) || (dimensionalSlash != null && (dimensionalSlash.damage() > 0 || dimensionalSlash.weaponPercent() > 0))
                || (attributeBoost != null && attributeBoost.durationSeconds() > 0) || chain != null
                || (shockwave != null && shockwave.enabled()) || (sweep != null && sweep.enabled())
                || (swing != null && swing.enabled())
                || (phantomBlade != null && phantomBlade.enabled())
                || (swordAura != null && swordAura.enabled())
                || (crossSlash != null && crossSlash.enabled())
                || (magicCircle != null && magicCircle.enabled())
                || (gateOfBabylon != null && gateOfBabylon.enabled())
                || (backstep != null && backstep.enabled())
                || (uppercut != null && uppercut.enabled())
                || (leapStrike != null && leapStrike.enabled());
    }
}
