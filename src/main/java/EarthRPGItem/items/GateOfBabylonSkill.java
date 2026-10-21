package EarthRPGItem.items;

import org.bukkit.Material;

import java.util.List;
import java.util.Locale;

/**
 * 「王之宝库」：在施法者背后或落点上方的空中展开一批武器，短暂停留后射向准星所指的位置，
 * 落地时产生一次不破坏方块的爆炸，落地停留一段时间再消失。
 * <p>
 * 几何关系是「准星决定落点、{@link SpawnMode} 决定生成点」，两者相互独立：
 * <ul>
 *   <li>落点区域由视线射线决定（{@code distance} + {@code spreadRadius}），射线只用来定位 X/Z 与
 *       地面扫描的起点，不限制飞行路径；</li>
 *   <li>生成点要么在施法者背后的扇形内随机（{@code spawnDistance} / {@code spawnHeight} /
 *       {@code spawnFanDegrees}），要么在落点正上方的空中（{@code skyHeight} / {@code skyOffset}）。</li>
 * </ul>
 * 因为飞行是生成点→落点的直线插值且**不与方块做碰撞检测**，武器可以穿过天花板、墙等任何方块到达落点。
 * <p>
 * 生物是另一回事：{@code collide-with-entities} 打开后，飞行途中碰到生物就**就地命中**并消失，
 * 而不是穿过去砸在后面地上。默认关闭，因为「武器穿过方块直击准星落点」正是王之宝库的观感；
 * 打开它的是箭雨类技能（代达罗斯弓），箭穿过目标却什么都没发生才是最违和的。
 * 判定用的是**线段**而不是当前位置的点：{@code fall-speed} 大于 1 时一 tick 会跳过好几格，
 * 只测当前位置会让高速箭矢直接穿过站位不巧的目标。
 * <p>
 * 这里没有真正的物理，也没有真正的爆炸实体：落地效果只是粒子，落点伤害是范围结算，走与其它技能
 * 相同的伤害管线。
 *
 * @param damage            落点结算的技能伤害，走与其它技能相同的 {@code damageTarget} 管线
 * @param weaponCount       武器数量，会被 {@link #MAX_WEAPONS} 截断
 * @param spawnMode         生成方式：背后 / 天上 / 两者随机混合
 * @param distance          准星射线长度，决定落点区域离施法者多远
 * @param spreadRadius      落点相对准星落点的散布半径
 * @param spawnDistance     背后生成时，相对施法者背后的水平距离（会乘以 0.5~1.5 的随机系数）
 * @param spawnHeight       背后生成时，相对施法者脚上方的高度（会乘以 0.4~1.4 的随机系数）
 * @param spawnFanDegrees   背后生成时，相对「正后方」的扇形张角，360 = 整圈随机
 * @param skyHeight         天上生成时，落点上方多少格（会乘以 0.85~1.15 的随机系数）
 * @param skyOffset         天上生成时，相对落点的水平偏移半径
 * @param fallSpeed         飞行时每 tick 前进多少格
 * @param lingerSeconds     落地后停留多少秒再消失
 * @param launchDelayTicks  生成后最长等待多少 tick 再发射，每把武器随机取值
 * @param impactRadius      落点伤害半径
 * @param impactHeight      落点伤害高度
 * @param maxHitsPerTarget  同一目标在本次施放中最多被打中几次
 * @param collideWithEntities 飞行途中碰到生物是否立即命中。见 {@link #collideWithEntities}。
 * @param collisionRadius   判定半径（格），见 {@link #collisionRadius}。
 * @param bladeTiltDegrees  贴图摆正角（度）。剑贴图里的剑刃是**斜的**，不先把它摆到 +Y，后面「瞄准落点」
 *                          就整体偏一个固定角度。原版剑贴图在显示实体里读出来是 135°（左上），所以默认
 *                          **-45**；如果实测发现剑刃偏了 45°，改这个值即可，不需要重新编译。
 * @param rippleParticle    悬停期在**剑柄**处画的平面圆环粒子。它的 {@code count} 是圆环上的粒子数、
 *                          {@code radius} 是圆环半径，都按字面生效。
 * @param ripplePlane       圆环所在的平面，见 {@link RipplePlane}。
 * @param weaponMaterials   武器外观池，每次生成从中随机取一个。**同一个材质写多次就是提高权重**，
 *                          所以「金制武器多一点」只需要把金剑金斧多列几行，不需要额外的权重参数。
 */
public record GateOfBabylonSkill(
        DamageSkill damage,
        int weaponCount,
        SpawnMode spawnMode,
        double distance,
        double spreadRadius,
        double spawnDistance,
        double spawnHeight,
        double spawnFanDegrees,
        double skyHeight,
        double skyOffset,
        double fallSpeed,
        double lingerSeconds,
        int launchDelayTicks,
        double impactRadius,
        double impactHeight,
        int maxHitsPerTarget,
        boolean collideWithEntities,
        double collisionRadius,
        double bladeTiltDegrees,
        ParticleConfig rippleParticle,
        RipplePlane ripplePlane,
        List<Material> weaponMaterials,
        ParticleConfig trailParticle,
        ParticleConfig impactParticle,
        ParticleConfig explosionParticle,
        ParticleConfig spawnParticle,
        SoundConfig castSound,
        SoundConfig impactSound
) {
    /**
     * Hard ceiling on one cast. Every weapon is a real {@code ItemDisplay} entity, so this is the
     * number that decides whether the skill is a spectacle or a lag spike; 32 keeps a single cast
     * under a tick budget that a normal server can absorb.
     */
    public static final int MAX_WEAPONS = 32;

    /**
     * 武器从哪里出现。两种生成点用的是两套互不相干的参数，所以切换方式不需要改动另一套的数值。
     */
    public enum SpawnMode {
        /** 只在施法者背后的扇形内生成，像身后的宝库门打开。 */
        BEHIND,
        /** 只在落点上方的空中生成，垂直俯冲砸下。 */
        SKY,
        /** 每把武器随机二选一，两套参数同时生效。 */
        MIXED;

        /**
         * 把配置里的字符串翻成枚举。别名是刻意保留的：`both` / `random` 这类写法比 `mixed` 更直觉，
         * 写错时回退到 {@link #BEHIND}（与不写该键的行为一致），由调用方决定是否警告。
         */
        public static SpawnMode parse(String raw) {
            SpawnMode mode = alias(raw);
            return mode == null ? BEHIND : mode;
        }

        /** 配置里写的值是否能被识别；用于在加载时对拼写错误发出警告而不是静默回退。 */
        public static boolean recognized(String raw) {
            return alias(raw) != null;
        }

        private static SpawnMode alias(String raw) {
            if (raw == null || raw.isBlank()) return null;
            return switch (raw.trim().toUpperCase(Locale.ROOT)) {
                case "BEHIND", "BACK", "REAR", "B" -> BEHIND;
                case "SKY", "AIR", "ABOVE", "TOP", "S" -> SKY;
                case "MIXED", "MIX", "BOTH", "RANDOM", "M" -> MIXED;
                default -> null;
            };
        }
    }

    /**
     * 剑柄处那个金色圆环所在的平面。
     * <p>
     * 默认的 {@link #BLADE} 是「武器从圆形门里被拔出来」的观感：圆环垂直于剑刃，剑身正好穿过环心。
     * {@link #HORIZONTAL} 则是平躺的水平圆环，更像脚下展开的魔法阵。两者只影响圆环朝向，不影响剑的姿态。
     */
    public enum RipplePlane {
        /** 垂直于剑刃（默认）：圆环套在剑柄上，像武器正从宝库的圆形门里抽出。 */
        BLADE,
        /** 水平：圆环平躺，像脚下的法阵。 */
        HORIZONTAL;

        /**
         * 把配置里的字符串翻成枚举。写错时回退到 {@link #BLADE}（与不写该键的行为一致），由调用方决定是否警告。
         */
        public static RipplePlane parse(String raw) {
            RipplePlane plane = alias(raw);
            return plane == null ? BLADE : plane;
        }

        /** 配置里写的值是否能被识别；用于在加载时对拼写错误发出警告而不是静默回退。 */
        public static boolean recognized(String raw) {
            return alias(raw) != null;
        }

        private static RipplePlane alias(String raw) {
            if (raw == null || raw.isBlank()) return null;
            return switch (raw.trim().toUpperCase(Locale.ROOT)) {
                case "BLADE", "SWORD", "PERPENDICULAR", "VERTICAL", "B" -> BLADE;
                case "HORIZONTAL", "FLAT", "GROUND", "H" -> HORIZONTAL;
                default -> null;
            };
        }
    }

    /**
     * Whether this skill should run at all. Requires weapons to render and damage to deal: a
     * gate that spawns nothing, or that lands for zero damage, is a configuration mistake rather
     * than a valid visual-only skill, so it is reported as disabled by {@code /rpgitem magicdebug}
     * instead of silently doing nothing.
     */
    public boolean enabled() {
        if (weaponCount <= 0 || weaponMaterials.isEmpty()) return false;
        return damage != null && (damage.damage() > 0 || damage.weaponPercent() > 0);
    }
}
