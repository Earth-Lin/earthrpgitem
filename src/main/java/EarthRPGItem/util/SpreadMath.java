package EarthRPGItem.util;

import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 弹射物散布方向的几何计算。
 *
 * <p>抽成独立工具类是为了<b>可测试</b>：这两种散布的差别很容易写错，而写错的表现
 * （「分裂箭朝四面八方射」）只有进游戏才看得出来。这里把几何单独拿出来，
 * 就能用纯数学断言守住「锥形」与「圆环」的区别。
 */
public final class SpreadMath {

    private SpreadMath() {
    }

    /**
     * 霰弹式<b>锥形</b>散布：朝 {@code aim} 前方张开一个张角为 {@code spreadDegrees} 的锥，
     * 弹道在锥内均匀分布。
     * <p>
     * 关键性质：<b>所有方向与瞄准方向的夹角都不超过锥的半角</b>，所以抬头时整个锥跟着抬起
     * （{@code aim} 朝上时每个方向的 Y 分量都为正）。这正是它与「圆环」的区别——圆环是
     * 垂直于瞄准方向的平面，瞄准朝上时方向全部水平，看起来就是朝四面八方射。
     * <p>
     * 半径取样用 {@code sqrt(random)}：让落点在锥的<b>截面圆面积</b>上均匀，
     * 而不是往中心堆（线性取样会堆在中心，直接取 random 会堆在外圈）。
     *
     * @param aim           瞄准方向（会被归一化）
     * @param count         方向数量；{@code <= 1} 时直接返回瞄准方向
     * @param spreadDegrees 锥的<b>全</b>张角（度）。0 = 完全不分叉
     * @param random        随机源，便于测试注入固定种子
     */
    public static List<Vector> cone(Vector aim, int count, double spreadDegrees, Random random) {
        List<Vector> directions = new ArrayList<>();
        Vector forward = normalize(aim);
        if (count <= 1) {
            directions.add(forward);
            return directions;
        }
        // 上限 89°：90° 的半角是 45°，再大就会往侧后方射，不再是「朝前散」。
        double radians = Math.toRadians(Math.max(0, Math.min(89, spreadDegrees)));
        double tan = Math.tan(radians / 2.0);
        Vector axisA = perpendicular(forward, 0);
        Vector axisB = perpendicular(forward, 1);
        for (int index = 0; index < count; index++) {
            double theta = random.nextDouble() * Math.PI * 2;
            double r = Math.sqrt(random.nextDouble()) * tan;
            directions.add(forward.clone()
                    .add(axisA.clone().multiply(Math.cos(theta) * r))
                    .add(axisB.clone().multiply(Math.sin(theta) * r))
                    .normalize());
        }
        return directions;
    }

    /**
     * 水平一排：只在一个水平方向上把弹道排开（旧行为，保持向后兼容）。
     * 注意它<b>只水平</b>展开，所以抬头时这一排仍然是水平的。
     */
    public static List<Vector> line(Vector aim, int count, double spreadDegrees) {
        List<Vector> directions = new ArrayList<>();
        Vector base = normalize(aim);
        if (count <= 1) {
            directions.add(base);
            return directions;
        }
        Vector horizontal = new Vector(-base.getZ(), 0, base.getX());
        if (horizontal.lengthSquared() < 1.0E-6) horizontal = new Vector(1, 0, 0);
        horizontal.normalize();
        double tan = Math.tan(Math.toRadians(Math.max(0, spreadDegrees)));
        for (int index = 0; index < count; index++) {
            double ratio = index / (double) (count - 1) - 0.5;
            directions.add(base.clone().add(horizontal.clone().multiply(tan * ratio)).normalize());
        }
        return directions;
    }

    /** 一组正交基：{@code index} 0/1 分别给出两个互相垂直、且都垂直于 {@code forward} 的单位向量。 */
    public static Vector perpendicular(Vector forward, int index) {
        Vector reference = Math.abs(forward.getY()) > 0.99 ? new Vector(1, 0, 0) : new Vector(0, 1, 0);
        Vector side = forward.clone().crossProduct(reference);
        if (side.lengthSquared() < 1.0E-6) side = forward.clone().crossProduct(new Vector(1, 0, 0));
        side.normalize();
        if (index == 0) return side;
        Vector other = forward.clone().crossProduct(side);
        if (other.lengthSquared() < 1.0E-6) return side;
        return other.normalize();
    }

    private static Vector normalize(Vector vector) {
        if (vector == null || vector.lengthSquared() < 1.0E-9) return new Vector(0, 0, 1);
        return vector.clone().normalize();
    }
}
