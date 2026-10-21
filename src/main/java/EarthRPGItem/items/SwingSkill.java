package EarthRPGItem.items;

/**
 * 挥击（Swing）—— 一次挥出一道弧形刀光。与横扫同源，但每次施放都会随机旋转弧面，
 * 因此既可能是横扫那种水平弧，也可能是竖直斩，更多时候是两者之间的斜弧。
 *
 * <p>{@code tiltMinDegrees} / {@code tiltMaxDegrees} 定义弧面绕视线方向随机旋转的角度范围：
 * <ul>
 *   <li>0 = 水平弧（和横扫一样平躺）</li>
 *   <li>90 = 竖直弧（和十字斩的竖直段一样立起来）</li>
 *   <li>0..90 = 随机打出水平到竖直之间的斜弧</li>
 * </ul>
 * 默认范围 0..90，即每次都在水平与竖直之间随机取一个倾斜角。
 */
public record SwingSkill(DamageSkill damage, double distance, double radius, double height,
                         double angleDegrees, double tiltMinDegrees, double tiltMaxDegrees,
                         ParticleConfig particle, SoundConfig sound) {
    public boolean enabled() {
        return damage != null && (damage.damage() > 0 || damage.weaponPercent() > 0);
    }
}