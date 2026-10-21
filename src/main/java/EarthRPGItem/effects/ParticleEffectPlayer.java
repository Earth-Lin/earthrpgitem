package EarthRPGItem.effects;

import EarthRPGItem.EarthRPGItem;
import EarthRPGItem.items.ParticleConfig;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Color;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.Locale;
import java.util.function.Predicate;
import java.util.concurrent.ThreadLocalRandom;

public class ParticleEffectPlayer {

    private final EarthRPGItem plugin;

    /**
     * Radius caps, per entry point. They differ on purpose because the configured {@code radius} means
     * something different in each one; the caps only exist so a stray large value cannot fill the
     * arena. The numbers used to be inline and unexplained, so the same {@code radius} value behaved
     * differently depending on which entry point drew it, with nothing to say why.
     * <ul>
     *   <li>{@link #SHAPE_RADIUS_CAP} - {@code playAlways} spreads particles over the radius as a
     *       shape, so a multi-block value is normal (a sphere of radius 3, for example).</li>
     *   <li>{@link #BURST_RADIUS_CAP} - {@code playBurst} draws expanding rings, which stay readable
     *       at a larger size than a trail.</li>
     *   <li>{@link #TRAIL_RADIUS_CAP} - {@code playJittered} / {@code playTrail} use the radius as the
     *       <b>thickness</b> of a line, so beyond about a block it stops reading as a trail.</li>
     *   <li>{@link #RIPPLE_RADIUS_CAP} - {@code playGateRipple} uses the radius as the radius of a
     *       flat ring drawn around a weapon's hilt, so past a couple of blocks it stops reading as a
     *       gate the weapon is coming out of.</li>
     * </ul>
     */
    private static final double SHAPE_RADIUS_CAP = 3;
    private static final double BURST_RADIUS_CAP = 6;
    private static final double TRAIL_RADIUS_CAP = 1;
    private static final double RIPPLE_RADIUS_CAP = 2;

    /** 次元斩裂缝的默认颜色（蓝）。见 {@link #playRandomLineBurst}。 */
    private static final String DEFAULT_DIMENSIONAL_COLOR = "80,140,255";

    public ParticleEffectPlayer(EarthRPGItem plugin) {
        this.plugin = plugin;
    }

    public void play(Location center, ParticleConfig itemConfig) {
        if (!plugin.isFeatureEnabled("particles") || !plugin.getConfig().getBoolean("particles.enabled", true) || !itemConfig.enabled()) {
            return;
        }
        playAlways(center, itemConfig);
    }

    public void playAlways(Location center, ParticleConfig itemConfig) {
        if (!itemConfig.enabled()) {
            return;
        }

        Particle particle = parseParticle(itemConfig.particle());
        int count = Math.max(1, itemConfig.count());
        double radius = Math.min(SHAPE_RADIUS_CAP, Math.max(0.1, itemConfig.radius()));
        String effect = itemConfig.effect().toLowerCase(Locale.ROOT);
        Object data = particle == Particle.BLOCK ? blockData(itemConfig.block()) : particle == Particle.DUST ? dust(itemConfig.block()) : null;

        for (int index = 0; index < count; index++) {
            Location location = effectLocation(center, effect, radius, index, count);
            spawn(location, particle, data);
        }
    }

    public void playSingle(Location location, ParticleConfig itemConfig) {
        if (itemConfig == null || !itemConfig.enabled()) return;
        Particle particle = parseParticle(itemConfig.particle());
        Object data = particle == Particle.BLOCK ? blockData(itemConfig.block()) : particle == Particle.DUST ? dust(itemConfig.block()) : null;
        spawn(location, particle, data);
    }
    /**
     * Spawns exactly {@code particleCount} particles scattered inside the configured radius around
     * one point. Shape drawers such as the sweep / cross-slash arcs sample many points along the
     * arc and share the configured {@code count} between them, so an arc honours {@code count} as
     * "particles in the whole effect" and {@code radius} as the thickness of the trail. Reading
     * {@code count} per point instead made a large count saturate into blobs and threw
     * {@code radius} away completely.
     * <p>
     * The radius is capped by {@link #TRAIL_RADIUS_CAP} because here it is the thickness of a line,
     * not the size of a shape; use the shape's own {@code radius} to size the effect.
     */
    public void playJittered(Location location, ParticleConfig itemConfig, int particleCount) {
        if (itemConfig == null || !itemConfig.enabled() || particleCount <= 0) return;
        Particle particle = parseParticle(itemConfig.particle());
        Object data = particle == Particle.BLOCK ? blockData(itemConfig.block()) : particle == Particle.DUST ? dust(itemConfig.block()) : null;
        double radius = Math.min(TRAIL_RADIUS_CAP, Math.max(0, itemConfig.radius()));
        for (int index = 0; index < particleCount; index++) {
            spawnForced(location.clone().add(randomOffset(radius), randomOffset(radius), randomOffset(radius)), particle, data);
        }
    }
    public void playRisingBurst(Location ground, ParticleConfig itemConfig) {
        if (itemConfig == null || !itemConfig.enabled()) return;
        Particle particle = parseParticle(itemConfig.particle());
        Object data = particle == Particle.BLOCK ? blockData(itemConfig.block()) : particle == Particle.DUST ? dust(itemConfig.block()) : null;
        int count = Math.max(1, itemConfig.count());
        double height = Math.max(0.35, itemConfig.radius() * 8.0);
        for (int index = 0; index < count; index++) {
            double progress = ThreadLocalRandom.current().nextDouble();
            Location point = ground.clone().add(randomOffset(Math.max(0.02, itemConfig.radius())), progress * height, randomOffset(Math.max(0.02, itemConfig.radius())));
            spawnForced(point, particle, data);
        }
    }

    public void playTrail(Location center, ParticleConfig itemConfig) {
        if (!itemConfig.enabled()) {
            return;
        }

        Particle particle = parseParticle(itemConfig.particle());
        int count = Math.max(1, itemConfig.count());
        double radius = Math.min(TRAIL_RADIUS_CAP, Math.max(0, itemConfig.radius()));
        Object data = particle == Particle.BLOCK ? blockData(itemConfig.block()) : particle == Particle.DUST ? dust(itemConfig.block()) : null;
        for (int index = 0; index < count; index++) {
            Location location = center.clone().add(randomOffset(radius), randomOffset(radius), randomOffset(radius));
            spawnForced(location, particle, data);
        }
    }

    public void playExplosionExpansion(Location center, ParticleConfig extra) {
        int count = extra == null ? 48 : Math.max(24, extra.count());
        double radius = extra == null ? 3 : Math.max(0.5, extra.radius());
        for (int ring = 1; ring <= 6; ring++) {
            double progress = ring / 6.0;
            double currentRadius = radius * progress;
            int ringCount = Math.max(6, count / 6);
            for (int index = 0; index < ringCount; index++) {
                double angle = Math.PI * 2 * index / ringCount;
                Location point = center.clone().add(Math.cos(angle) * currentRadius, 0.15 + progress * 0.8, Math.sin(angle) * currentRadius);
                spawn(point, Particle.EXPLOSION, null);
            }
        }
        if (extra != null && extra.enabled()) playBurst(center, extra);
    }
    /**
     * Draws an impact explosion centred exactly on {@code center}, honouring {@code count} and
     * {@code radius} literally.
     * <p>
     * This exists because {@link #playBurst} is the wrong tool for a ground impact in two ways.
     * First, it only draws expanding rings when {@code effect} is circle/sphere/round, and silently
     * falls through to {@link #playAlways} otherwise - and {@code playAlways} places its default
     * shape one block <i>above</i> the centre (that offset is right for item particle clouds, wrong
     * for a weapon hitting the floor). A configured explosion therefore drifted up off the ground.
     * Second, its ring path forces {@code count} up to at least 12, which for a full-size sprite like
     * {@code EXPLOSION} is a wall of white across the crosshair.
     * <p>
     * Here the particles are spread over a sphere of exactly the configured radius, scaled by the
     * configured count, and never offset off the impact point.
     */
    public void playImpactBurst(Location center, ParticleConfig itemConfig) {
        if (itemConfig == null || !itemConfig.enabled()) return;
        Particle particle = parseParticle(itemConfig.particle());
        Object data = particle == Particle.BLOCK ? blockData(itemConfig.block())
                : particle == Particle.DUST ? dust(itemConfig.block()) : null;
        int count = Math.max(1, itemConfig.count());
        double radius = Math.min(BURST_RADIUS_CAP, Math.max(0.05, itemConfig.radius()));
        for (int index = 0; index < count; index++) {
            double yaw = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
            // Biased upward slightly so the burst reads as coming off the ground rather than
            // sinking into it: the lower half is kept but compressed.
            double pitch = ThreadLocalRandom.current().nextDouble(-0.6, Math.PI / 2);
            double spread = radius * Math.cbrt(ThreadLocalRandom.current().nextDouble());
            double x = Math.cos(pitch) * Math.cos(yaw) * spread;
            double y = Math.sin(pitch) * spread;
            double z = Math.cos(pitch) * Math.sin(yaw) * spread;
            spawnForced(center.clone().add(x, y, z), particle, data);
        }
    }

    /**
     * Draws one <b>flat ring</b> of particles centred on {@code center} and lying in the plane
     * perpendicular to {@code axis}.
     * <p>
     * This is the "weapon being drawn out of the treasury" visual: the ring sits at the weapon's hilt
     * with the weapon hanging through it, so a volley reads as weapons being pulled out of circular
     * gates rather than as sprites appearing in mid-air. It differs from {@link #playBurst} in the two
     * ways that matter here - it draws a single flat ring instead of a sequence of expanding spheres,
     * and it draws at exactly the given point instead of delegating to {@code playAlways}, which
     * offsets particles one block up. That keeps the ring locked to the hilt for as long as the
     * weapon hovers.
     * <p>
     * {@code count} particles are spaced evenly around the circle and {@code radius} is the ring's
     * radius; both are taken literally.
     *
     * @param axis  the ring's normal. A null or zero vector falls back to a horizontal ring.
     * @param phase rotation of the first particle around the ring, in radians. Callers advance this
     *              as time passes to make the ring appear to spin.
     */
    public void playGateRipple(Location center, Vector axis, ParticleConfig itemConfig, double phase) {
        if (itemConfig == null || !itemConfig.enabled()) return;
        Particle particle = parseParticle(itemConfig.particle());
        Object data = particle == Particle.BLOCK ? blockData(itemConfig.block())
                : particle == Particle.DUST ? dust(itemConfig.block()) : null;
        int count = Math.max(6, itemConfig.count());
        double radius = Math.min(RIPPLE_RADIUS_CAP, Math.max(0.05, itemConfig.radius()));

        // Two unit vectors spanning the ring's plane. Any vector that is not parallel to the normal
        // works as the starting axis; the cross products then produce an orthonormal pair.
        Vector normal = axis == null || axis.lengthSquared() < 1.0E-6
                ? new Vector(0, 1, 0) : axis.clone().normalize();
        Vector helper = Math.abs(normal.getX()) < 0.9 ? new Vector(1, 0, 0) : new Vector(0, 1, 0);
        Vector u = helper.crossProduct(normal).normalize();
        Vector v = normal.clone().crossProduct(u).normalize();

        for (int index = 0; index < count; index++) {
            double angle = phase + Math.PI * 2 * index / count;
            Vector offset = u.clone().multiply(Math.cos(angle) * radius)
                    .add(v.clone().multiply(Math.sin(angle) * radius));
            spawnForced(center.clone().add(offset), particle, data);
        }
    }

    public void playBurst(Location center, ParticleConfig itemConfig) {
        if (!itemConfig.enabled()) {
            return;
        }
        String effect = itemConfig.effect().toLowerCase(Locale.ROOT);
        if (!effect.equals("circle") && !effect.equals("sphere") && !effect.equals("round") && !effect.equals("圆形")) {
            playAlways(center, itemConfig);
            return;
        }

        Particle particle = parseParticle(itemConfig.particle());
        int count = Math.max(12, itemConfig.count());
        double radius = Math.min(BURST_RADIUS_CAP, Math.max(0.1, itemConfig.radius()));
        Object data = particle == Particle.BLOCK ? blockData(itemConfig.block()) : particle == Particle.DUST ? dust(itemConfig.block()) : null;
        int steps = 6;
        for (int step = 1; step <= steps; step++) {
            double progress = step / (double) steps;
            double currentRadius = radius * progress;
            int currentCount = Math.max(4, count / steps);
            for (int index = 0; index < currentCount; index++) {
                double yaw = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
                double pitch = ThreadLocalRandom.current().nextDouble(-Math.PI / 2, Math.PI / 2);
                double x = Math.cos(pitch) * Math.cos(yaw) * currentRadius;
                double y = Math.sin(pitch) * currentRadius;
                double z = Math.cos(pitch) * Math.sin(yaw) * currentRadius;
                spawn(center.clone().add(x, y, z), particle, data);
            }
        }
    }

    public void playGroundExpansion(Location center, ParticleConfig itemConfig) {
        playGroundExpansion(center, itemConfig, location -> true);
    }

    public void playGroundExpansion(Location center, ParticleConfig itemConfig, Predicate<Location> visible) {
        if (!itemConfig.enabled()) return;
        Particle particle = parseParticle(itemConfig.particle());
        int configuredCount = Math.max(24, itemConfig.count());
        double radius = Math.min(12, Math.max(0.1, itemConfig.radius()));
        Object data = particle == Particle.BLOCK ? blockData(itemConfig.block()) : particle == Particle.DUST ? dust(itemConfig.block()) : null;
        int rings = 8;
        int particlesPerRing = Math.max(18, (int) Math.ceil(configuredCount / (double) rings) * 2);
        for (int ring = 1; ring <= rings; ring++) {
            double currentRadius = radius * ring / rings;
            for (int index = 0; index < particlesPerRing; index++) {
                double angle = Math.PI * 2 * index / particlesPerRing;
                Location location = center.clone().add(Math.cos(angle) * currentRadius, 0.03, Math.sin(angle) * currentRadius);
                if (visible.test(location)) spawn(location, particle, data);
            }
        }
    }

    public BukkitTask playContract(Player player, ParticleConfig itemConfig, double durationSeconds) {
        if (!itemConfig.enabled()) return null;
        Particle particle = parseParticle(itemConfig.particle());
        int totalTicks = Math.max(1, (int) Math.round(durationSeconds * 20));
        int periodTicks = totalTicks <= 4 ? 1 : 2;
        int particlesPerStep = Math.min(48, Math.max(8, itemConfig.count() / Math.max(1, totalTicks / periodTicks)));
        double configuredRadius = Math.min(12, Math.max(0.1, itemConfig.radius()));
        Object data = particle == Particle.BLOCK ? blockData(itemConfig.block()) : particle == Particle.DUST ? dust(itemConfig.block()) : null;

        return new BukkitRunnable() {
            private int elapsedTicks;

            @Override
            public void run() {
                if (!player.isOnline() || player.isDead()) {
                    cancel();
                    return;
                }
                double progress = Math.min(1.0, elapsedTicks / (double) totalTicks);
                double currentRadius = configuredRadius * (1.0 - progress);
                Location feet = player.getLocation().clone().add(0, 0.05, 0);
                for (int index = 0; index < particlesPerStep; index++) {
                    double angle = Math.PI * 2 * index / particlesPerStep + progress * Math.PI * 2;
                    double y = (1.0 - progress) * (0.4 + ThreadLocalRandom.current().nextDouble(1.8));
                    double jitter = ThreadLocalRandom.current().nextDouble(0.82, 1.18);
                    spawnForced(feet.clone().add(Math.cos(angle) * currentRadius * jitter, y, Math.sin(angle) * currentRadius * jitter), particle, data);
                }
                if (elapsedTicks >= totalTicks) {
                    for (int index = 0; index < Math.min(24, Math.max(8, itemConfig.count())); index++) spawnForced(feet, particle, data);
                    cancel();
                    return;
                }
                elapsedTicks += periodTicks;
            }
        }.runTaskTimer(plugin, 0L, periodTicks);
    }

    /**
     * 次元斩的专属效果：在爆炸范围附近撒出若干条<b>任意方向</b>的粒子线。
     * <p>
     * 每条线的起点随机、朝向随机（拒绝采样球面均匀随机，不偏向任何轴），读起来像被切开
     * 的空间裂缝，而不是一圈爆炸，也不再是固定竖直的线。
     * <p>
     * <b>颜色默认蓝色</b>：{@code block} 留空时用 {@link #DEFAULT_DIMENSIONAL_COLOR}
     * （80,140,255）。这个默认值不能走通用的灰色兜底，否则「次元的裂缝」会变成一撮灰烟。
     */
    public void playRandomLineBurst(Location center, ParticleConfig itemConfig) {
        if (itemConfig == null || !itemConfig.enabled()) return;
        Particle particle = parseParticle(itemConfig.particle());
        String color = itemConfig.block();
        Object data = particle == Particle.BLOCK ? blockData(color)
                : particle == Particle.DUST
                ? dust(color == null || color.isBlank() ? DEFAULT_DIMENSIONAL_COLOR : color)
                : null;
        double radius = Math.min(BURST_RADIUS_CAP, Math.max(0.5, itemConfig.radius()));
        // count 是整场的粒子总量，按每条线约 8 个点折算条数，并限制在 4..32 条之间。
        int lines = Math.max(4, Math.min(32, Math.max(1, itemConfig.count()) / 8));
        for (int line = 0; line < lines; line++) {
            // 任意朝向：随机单位向量作为线方向，不再固定竖着。
            Vector direction = randomUnitVector();
            // 起点在爆炸范围附近随机偏移。
            Location base = center.clone()
                    .add(randomOffset(radius * 0.8), randomOffset(radius * 0.3), randomOffset(radius * 0.8));
            double length = radius * (0.7 + ThreadLocalRandom.current().nextDouble(0.9));
            int steps = Math.max(3, (int) Math.round(length / 0.25));
            for (int step = 0; step <= steps; step++) {
                double progress = step / (double) steps;
                spawnForced(base.clone().add(direction.clone().multiply(length * progress)), particle, data);
            }
        }
    }

    /** 拒绝采样得到的球面均匀随机单位向量：任意朝向，不偏向任何轴。 */
    private Vector randomUnitVector() {
        double x, y, z, lenSq;
        do {
            x = ThreadLocalRandom.current().nextDouble(-1, 1);
            y = ThreadLocalRandom.current().nextDouble(-1, 1);
            z = ThreadLocalRandom.current().nextDouble(-1, 1);
            lenSq = x * x + y * y + z * z;
        } while (lenSq > 1 || lenSq < 1e-6);
        double len = Math.sqrt(lenSq);
        return new Vector(x / len, y / len, z / len);
    }

    /**
     * 缩减版地面冲击波：与 {@link #playGroundExpansion} 同一套「一圈圈扩散」的造型，
     * 但环数、每环粒子数、半径都按传入值缩小，用于「落地冲击」这类小范围效果——
     * 用原版那套参数会铺满半个屏幕。
     */
    public void playCompactShockwave(Location center, ParticleConfig itemConfig, double radius) {
        if (itemConfig == null || !itemConfig.enabled()) return;
        Particle particle = parseParticle(itemConfig.particle());
        Object data = particle == Particle.BLOCK ? blockData(itemConfig.block())
                : particle == Particle.DUST ? dust(itemConfig.block()) : null;
        double outer = Math.min(6, Math.max(0.3, radius));
        int rings = 3;
        int perRing = Math.max(8, Math.min(20, Math.max(6, itemConfig.count()) / rings));
        for (int ring = 1; ring <= rings; ring++) {
            double currentRadius = outer * ring / rings;
            for (int index = 0; index < perRing; index++) {
                double angle = Math.PI * 2 * index / perRing;
                spawnForced(center.clone().add(Math.cos(angle) * currentRadius, 0.04, Math.sin(angle) * currentRadius), particle, data);
            }
        }
    }

    /**
     * 一颗「大爆炸」：原版爆炸云 + 火球 + 黑烟 + 地面冲击环 + 火苗上升柱。
     * <p>
     * 这里解决的是两件不同的事。
     * <p>
     * <b>一是量。</b>{@link #playImpactBurst} 严格按配置的 {@code count} 撒点，所以 {@code count: 40}
     * 就真的只有 40 颗——半径调到 3 格以上时看上去就是稀稀拉拉几粒。
     * <p>
     * <b>二是「像不像爆炸」。</b>第一版把配置的粒子乘 4 铺满球体，结果配置成 {@code EXPLOSION}
     * （整格大的白色贴图）时画出来是几百颗白贴图糊成一团，完全看不出是爆炸——而这个坑代码库
     * 早就踩过一次（见王之宝库的更新记录：「36 颗白色爆炸贴图就是一坨白糊在准星正前方」）。
     * 爆炸之所以一眼能认出来，靠的是<b>层次</b>而不是总颗数：一道亮闪 + 火球 + 黑烟 + 冲击环。
     * 所以现在：
     * <ul>
     *   <li><b>原版爆炸云</b>：固定 1 颗 {@code EXPLOSION_EMITTER}，这是唯一「一眼认出是爆炸」
     *       的粒子，配置成什么都照放；</li>
     *   <li><b>火球体</b>：配置的粒子，按 {@code count} 如实铺在球体内（{@code cbrt} 取样保证
     *       是「一团」而不是一层壳）；配置成大贴图时只取极少几颗，避免糊成白墙；</li>
     *   <li><b>黑烟</b>：{@code LARGE_SMOKE} 向上堆；</li>
     *   <li><b>地面冲击环</b>：贴地 4 圈 {@code SMOKE} 由内向外扩散；</li>
     *   <li><b>火苗上升柱</b>：{@code FLAME} 向上飘。</li>
     * </ul>
     * 总量有硬上限，避免配置写大后一次施放把客户端刷爆。
     *
     * @param itemConfig 控制<b>火球体</b>的粒子与数量；
     *                   原版爆炸云 / 黑烟 / 冲击环 / 火苗柱由本方法自动补齐
     */
    public void playBigExplosion(Location center, ParticleConfig itemConfig, double radius) {
        if (itemConfig == null || !itemConfig.enabled()) return;
        Particle particle = parseParticle(itemConfig.particle());
        Object data = particle == Particle.BLOCK ? blockData(itemConfig.block())
                : particle == Particle.DUST ? dust(itemConfig.block()) : null;
        double outer = Math.min(BURST_RADIUS_CAP, Math.max(0.3, radius));
        int base = Math.max(1, itemConfig.count());

        // 1) 原版爆炸云。EXPLOSION_EMITTER 会自己铺开整团云，一颗就够；放多了反而糊。
        spawnForced(center.clone().add(0, 0.35, 0), Particle.EXPLOSION_EMITTER, null);

        // 2) 火球体：配置的粒子。大贴图只点几下——爆炸云与黑烟已经负责「体量」了。
        int body = isBulkySprite(particle)
                ? Math.min(3, base)
                : Math.min(MAX_EXPLOSION_PARTICLES, base);
        for (int index = 0; index < body; index++) {
            double yaw = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
            // 略微偏上：爆炸是「从落点鼓起来」，不是埋进地里。
            double pitch = ThreadLocalRandom.current().nextDouble(-0.6, Math.PI / 2);
            double distance = outer * Math.cbrt(ThreadLocalRandom.current().nextDouble());
            double horizontal = Math.cos(pitch) * distance;
            spawnForced(center.clone().add(
                    Math.cos(yaw) * horizontal,
                    Math.sin(pitch) * distance + 0.15,
                    Math.sin(yaw) * horizontal), particle, data);
        }

        // 3) 黑烟：爆炸之后留在原地并往上翻的那一层，是「爆炸感」的主要来源。
        int smoke = Math.max(6, Math.min(24, base / 4));
        for (int index = 0; index < smoke; index++) {
            double yaw = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
            double distance = outer * 0.85 * Math.cbrt(ThreadLocalRandom.current().nextDouble());
            double rise = outer * ThreadLocalRandom.current().nextDouble(0.15, 1.1);
            spawnForced(center.clone().add(
                    Math.cos(yaw) * distance, rise, Math.sin(yaw) * distance), Particle.LARGE_SMOKE, null);
        }

        // 4) 地面冲击环：贴地由内向外 4 圈，给「炸开」一个贴地的方向感。
        int rings = 4;
        int perRing = Math.max(8, Math.min(20, base / 5));
        for (int ring = 1; ring <= rings; ring++) {
            double currentRadius = outer * ring / rings;
            for (int index = 0; index < perRing; index++) {
                double angle = Math.PI * 2 * index / perRing;
                spawnForced(center.clone().add(
                        Math.cos(angle) * currentRadius, 0.06, Math.sin(angle) * currentRadius),
                        Particle.SMOKE, null);
            }
        }

        // 5) 火苗上升柱：少量 FLAME 往上飘，补一层「还在烧」的观感。
        int plume = Math.max(6, Math.min(18, base / 4));
        for (int index = 0; index < plume; index++) {
            double yaw = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
            double distance = outer * 0.45 * Math.sqrt(ThreadLocalRandom.current().nextDouble());
            spawnForced(center.clone().add(
                    Math.cos(yaw) * distance,
                    outer * ThreadLocalRandom.current().nextDouble(0.3, 1.4),
                    Math.sin(yaw) * distance), Particle.FLAME, null);
        }
    }

    /** 单次爆炸的粒子硬上限，防止配置写大后刷爆客户端。 */
    private static final int MAX_EXPLOSION_PARTICLES = 320;

    /**
     * 是否是「占满一整格」的大贴图粒子。
     * <p>
     * 这类粒子靠颗数堆量只会糊成一片白：{@code EXPLOSION} 的贴图接近一格见方，
     * 十几颗就能把准星盖住。所以火球体对它们只取 1..3 颗，体量交给爆炸云和黑烟。
     */
    private static boolean isBulkySprite(Particle particle) {
        return particle == Particle.EXPLOSION || particle == Particle.EXPLOSION_EMITTER;
    }

    private void spawn(Location location, Particle particle, Object data) {
        if (data == null) {
            location.getWorld().spawnParticle(particle, location, 1, 0, 0, 0, 0);
            return;
        }
        location.getWorld().spawnParticle(particle, location, 1, 0, 0, 0, 0, data);
    }

    private void spawnForced(Location location, Particle particle, Object data) {
        if (data == null) {
            location.getWorld().spawnParticle(particle, location, 1, 0, 0, 0, 0, null, true);
            return;
        }
        location.getWorld().spawnParticle(particle, location, 1, 0, 0, 0, 0, data, true);
    }

    private Location effectLocation(Location center, String effect, double radius, int index, int count) {
        double angle = (Math.PI * 2 / Math.max(1, count)) * index;
        double random = ThreadLocalRandom.current().nextDouble(0, radius);
        return switch (effect) {
            case "splash", "飞溅" -> center.clone().add(randomOffset(radius), 1 + randomOffset(radius / 2), randomOffset(radius));
            case "contract", "收缩" -> center.clone().add(Math.cos(angle) * (radius - random), 1, Math.sin(angle) * (radius - random));
            case "fall", "跌落" -> center.clone().add(randomOffset(radius), 1.8 - random, randomOffset(radius));
            case "rise", "飞升" -> center.clone().add(randomOffset(radius), 0.2 + random, randomOffset(radius));
            case "spin", "旋转" -> center.clone().add(Math.cos(angle) * radius, 1 + index / (double) count, Math.sin(angle) * radius);
            case "compress", "压缩" -> center.clone().add(Math.cos(angle) * radius * 0.35, 1, Math.sin(angle) * radius * 0.35);
            case "grow", "变大" -> center.clone().add(Math.cos(angle) * random, 1, Math.sin(angle) * random);
            default -> center.clone().add(randomOffset(radius), 1 + randomOffset(radius / 2), randomOffset(radius));
        };
    }

    private Particle parseParticle(String value) {
        try {
            return Particle.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return Particle.BLOCK;
        }
    }

    private BlockData blockData(String value) {
        Material material = Material.matchMaterial(value);
        if (material == null || !material.isBlock()) {
            material = Material.IRON_BLOCK;
        }
        return material.createBlockData();
    }

    /**
     * DUST 的默认颗粒大小。
     * <p>
     * <b>为什么需要这个常量</b>：DUST 有两个互相独立的「大小」概念，非常容易搞混——
     * <ul>
     *   <li>{@code radius}（配置键）= <b>散布抖动</b>：粒子撒在多宽的范围里，由
     *       {@link #playJittered} 等使用；</li>
     *   <li>{@code size}（本常量）= <b>粒子自身的大小</b>：每一颗 DUST 点有多大。</li>
     * </ul>
     * 轨迹看起来「范围太大」时，几乎总是 {@code size} 大而不是 {@code radius} 大，
     * 所以把 {@code radius} 调成 0 <b>不会有任何变化</b>——这一点被实际反馈踩过。
     * <p>
     * <b>为什么是 0.55</b>：这里曾经和 {@code SlashParticleEffectPlayer} 各写一份 {@code dust()}，
     * 一个 {@code 1.0F} 一个 {@code 0.55F}。同样是 DUST 轨迹，弹射物（箭雨）拖出来的
     * 比剑气粗了近一倍，玩家反馈「弹射物的粒子范围怎么这么大，之前那个完美模拟原版」。
     * 现在两处共用 {@link #dustOptions(String)} 与这一个默认值，不可能再分叉。
     */
    public static final float DEFAULT_DUST_SIZE = 0.55F;

    /** 颗粒大小上下限：上限 4（原版红石粉是 1.0），下限 0.05（再小就看不见了）。 */
    private static final float MIN_DUST_SIZE = 0.05F;
    private static final float MAX_DUST_SIZE = 4.0F;

    /**
     * 解析 DUST 的颜色与颗粒大小，<b>全插件唯一实现</b>（{@code SlashParticleEffectPlayer} 也调它）。
     * <p>
     * 写法：{@code "r,g,b"} 或 {@code "r,g,b,size"}，size 可省略（省略即 {@link #DEFAULT_DUST_SIZE}）。
     * 颜色非法时回退灰色；只有 size 非法时保留颜色、回退默认大小，不会把颜色一起丢掉。
     */
    public static Particle.DustOptions dustOptions(String value) {
        String[] parts = value == null ? new String[0] : value.split(",");
        if (parts.length >= 3) {
            try {
                float size = DEFAULT_DUST_SIZE;
                if (parts.length >= 4) {
                    try {
                        size = (float) Math.max(MIN_DUST_SIZE, Math.min(MAX_DUST_SIZE, Double.parseDouble(parts[3].trim())));
                    } catch (NumberFormatException ignoredSize) {
                        // 颜色照用，只是大小回退默认值。
                    }
                }
                return new Particle.DustOptions(
                        Color.fromRGB(clampChannel(parts[0]), clampChannel(parts[1]), clampChannel(parts[2])), size);
            } catch (NumberFormatException ignoredColor) {
            }
        }
        return new Particle.DustOptions(Color.GRAY, DEFAULT_DUST_SIZE);
    }

    private static int clampChannel(String value) {
        return Math.max(0, Math.min(255, Integer.parseInt(value.trim())));
    }

    private Particle.DustOptions dust(String value) {
        return dustOptions(value);
    }

    private double randomOffset(double radius) {
        return ThreadLocalRandom.current().nextDouble(-radius, radius);
    }
}
