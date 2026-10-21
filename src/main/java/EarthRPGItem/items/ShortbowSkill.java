package EarthRPGItem.items;

import org.bukkit.Particle;
import org.bukkit.Sound;

/**
 * 短弓（弹射物技能）。
 *
 * <p>弹射物本体由 {@link ItemProjectile} 描述：<b>不是原版投掷物实体</b>，而是用物品图标
 * 做的 {@code ItemDisplay}（与「王之宝库」同一套机制）。这样穿透不受原版限制、本体外观
 * 就是配置的物品、命中判定用线段不丢判定。</p>
 *
 * <p>旧配置（{@code projectile} / {@code arrow-count} / {@code spread-degrees} / {@code speed}）
 * 仍然可用：{@code projectile} 现在是「本体外观物品名」，{@code arrow-count} 是每批数量。</p>
 *
 * @param arrowCount          每批发射数量
 * @param speed               飞行速度（格/tick）
 * @param weaponPercent       按武器伤害来源计算的百分比
 * @param launchSound         发射音效（每一批都会播）
 * @param soundVolume         发射音量
 * @param soundPitch          发射音调
 * @param attackSpeedCooldown true = 走 RPG 攻速冷却（shortbow）；false = 固定技能冷却（shortbow-cd）
 * @param damageEnabled       命中后是否按技能管线结算伤害（false = 纯视觉）
 * @param spreadMode          cone = 霰弹锥形散布（默认）；line = 水平一排（旧行为）
 * @param spreadDegrees       散布张角（度）。cone 模式下是整个锥的张角；抬头时锥随视线方向翻转
 * @param volleys             连发批次（三连射：3 = 分三批打）
 * @param volleyIntervalTicks 每批之间的间隔 tick（20 tick = 1 秒）
 * @param projectile          弹射物本体（物品外观 + 穿透 + 命中效果）
 * @param damageType          命中伤害的类型（MAGIC / PHYSICAL / ...）。原文写死 PHYSICAL，
 *                            导致法师飞弹也按物理结算，打出「1 点物理伤害」
 * @param weaponPercentSources 倍率取用的伤害来源<b>排除列表</b>（空 = 不排除任何来源）。
 *                            原先写死空列表，于是 yml 里的 weapon-percent-sources 被完全忽略
 */
public record ShortbowSkill(int arrowCount, double speed, double weaponPercent,
                            Sound launchSound, float soundVolume, float soundPitch,
                            boolean attackSpeedCooldown, boolean damageEnabled,
                            String spreadMode, double spreadDegrees,
                            int volleys, int volleyIntervalTicks,
                            ItemProjectile projectile,
                            String damageType, java.util.List<String> weaponPercentSources) {
    public boolean enabled() { return arrowCount > 0 && projectile != null; }

    /** 霰弹锥形散布（默认）。 */
    public boolean coneSpread() { return !"line".equalsIgnoreCase(spreadMode); }
}
