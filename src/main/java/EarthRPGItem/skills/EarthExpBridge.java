package EarthRPGItem.skills;

import EarthRPGItem.EarthRPGItem;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * 战斗天赋 = 技能点：通过 Bukkit 服务管理器拿 {@code EarthExpAPI}（反射，不硬依赖 EarthExp）。
 *
 * <p>类名（含包名）在 config.yml 的 {@code earthexp} 段配置；没配或没装 EarthExp 时
 * {@link #isAvailable()} 返回 false，技能升级自动退回宝石消耗。
 *
 * <h2>扣点接口（EarthExp 0.5.0 起）</h2>
 *
 * <p>真实签名是<b>复数、带来源名</b>，别写成单数：
 * <pre>
 *   boolean spendTalentPoints (Player, Stat, int amount, String source)
 *   int     refundTalentPoints(Player, Stat, int amount, String source)  // 返回【实际】退回量
 *   int     getSpentTalentPoints(Player, Stat, String source)
 * </pre>
 * 早期版本没有这些方法，{@link #spend} 会退回「仅校验、不扣」的老行为。
 *
 * <p>扣点必须带 {@link #SPEND_SOURCE}：EarthExp 按来源分账，退款上限就是该来源实际扣过的量，
 * 所以「没扣过却退款」在结构上退不出任何点数。
 *
 * <h2>轨道（COMBAT / ADVENTURE）</h2>
 *
 * <p>EarthExp 有天赋点的轨道只有<b>战斗</b>（{@code Stat.COMBAT}）与<b>冒险</b>
 * （{@code Stat.ADVENTURE}）两条；其余轨道（挖矿/砍树/农业）没有天赋池，
 * 配上去扣点必然失败。
 *
 * <p>两条轨道各有自己的池子，按配置走哪条就扣哪条：
 * <ul>
 *   <li>EarthExp <b>0.5.1 起</b>：外部扣点<b>按轨道分开记账</b>，COMBAT 扣战斗池
 *       （{@code eexp_combat_tp}）、ADVENTURE 扣冒险池（{@code eexp_adv_tp}），
 *       互不干扰，退款也不能跨轨道；</li>
 *   <li>EarthExp <b>0.5.0</b>：外部扣点只允许 ADVENTURE，配成 COMBAT 时方法存在、
 *       但 {@link #spend} 会返回 false、{@link #refund} 返回 0。</li>
 * </ul>
 *
 * <p>所以「接口在不在」（{@link #deductsTalentPoints()}）和「这次扣点会不会被受理」
 * （{@link #spend} 的返回值）是两件事，调用方必须按后者来判断。
 *
 * <p>所有方法必须在主线程调用（计分板读写不是线程安全的）。
 */
public final class EarthExpBridge implements Listener {

    /**
     * 外部扣点来源名：EarthExp 按它分账，<b>跨重启必须稳定</b>——改名等于换一本账，
     * 已扣的点会找不回来（退款上限跟着账走）。
     *
     * <p>EarthExp 要求 {@code [a-z0-9_-]{1,32}}，且不能是 {@code earthrpgitem}
     * （属性 API 的保留名）也不能以 {@code earthexp} 开头。
     */
    public static final String SPEND_SOURCE = "earthrpgitem_skills";

    /**
     * EarthExp 的已知类名（0.5.x 起未变）。
     *
     * <p>config.yml 的 {@code earthexp.api-class} / {@code stat-class} <b>留空时按这两个名字自动探测</b>：
     * 装了 EarthExp 就等于「已接入」，不需要管理员手填类名——之前留空会被当成「未接入」，
     * 于是出现「明明装了 EarthExp，界面还显示未接入」。
     */
    public static final String DEFAULT_API_CLASS = "com.earthexp.api.EarthExpAPI";
    public static final String DEFAULT_STAT_CLASS = "com.earthexp.api.Stat";

    private final EarthRPGItem plugin;

    private String apiClassName = "";
    private String statClassName = "";
    private String combatStatName = "COMBAT";
    private Class<?> apiType;
    private Class<?> statType;
    private Object combatStat;
    /**
     * 「战斗等级」用的轨道常量（固定 COMBAT）。
     *
     * <p>和 {@link #combatStat} 分开：那条轨道是给<b>技能点</b>用的，可以在 config 里配成
     * ADVENTURE；而物品等级门槛要的是<b>战斗等级</b>，不该跟着技能点轨道跑。
     */
    private Object levelStat;
    private Object api;
    private Method getTalentPoints;
    private Method spendTalentPoints;
    private Method refundTalentPoints;
    private Method spentTalentPointsBySource;
    private Method getLevel;
    /** 人类可读的接入状态，供 {@code /rpgitem skillcheck} 直接显示，省得管理员去翻日志。 */
    private String status = "未接入（尚未初始化）";

    public EarthExpBridge(EarthRPGItem plugin) {
        this.plugin = plugin;
    }

    /** 接入状态说明（中文，一行）。 */
    public String status() {
        return status;
    }

    /**
     * 解析 EarthExp 的类：<b>优先用 EarthExp 自己的类加载器</b>，而不是本插件的 {@code Class.forName}。
     *
     * <p>为什么不直接 {@code Class.forName}：Bukkit 的插件类加载器只允许访问
     * {@code depend}/{@code softdepend} 里声明过的插件的类，而<b>本插件与 EarthExp 互为
     * softdepend</b>（本插件 softdepend EarthExp，EarthExp 也 softdepend EarthRPGItem），
     * 这是一个<b>环</b>。环上的加载顺序由 Bukkit 任意决定，所以「谁先加载」和
     * 「类看不看得见」都<b>不能</b>靠 plugin.yml 保证——这正是「装了 EarthExp 却一直显示
     * 未接入」的第二个原因，光加 softdepend 并不够。
     *
     * <p>从 EarthExp 的 {@code Plugin} 实例拿它的类加载器来 {@code loadClass}，
     * 上述两个问题就都不存在了：只要 EarthExp 装上了，它的类就一定找得到。
     */
    private Class<?> resolveClass(String className) throws ClassNotFoundException {
        Plugin earthExp = Bukkit.getPluginManager().getPlugin("EarthExp");
        if (earthExp != null) {
            ClassLoader loader = earthExp.getClass().getClassLoader();
            if (loader != null) {
                try {
                    return loader.loadClass(className);
                } catch (ClassNotFoundException ignored) {
                    // EarthExp 在，但这个类名不在它里面（例如包名对不上），落到下面的通用查找。
                }
            }
        }
        return Class.forName(className);
    }

    /**
     * EarthExp 启用后自动重试接入。
     *
     * <p>因为上面那个依赖环，本插件完全可能<b>先于</b> EarthExp 启用：那时 EarthExp 还没把
     * {@code EarthExpAPI} 注册进服务管理器，启动时那一次 {@link #reload()} 必然读到 null。
     * 监听它的启用事件补一次，管理员就不必手动 {@code /rpgitem reload}。
     *
     * <p>已在接入状态时重复 reload 是无害的（幂等）。
     */
    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (!"EarthExp".equalsIgnoreCase(event.getPlugin().getName())) return;
        reload();
    }

    /** 读取 config.yml 的 earthexp 段并尝试解析类名与注册的服务。reload 时重新执行。 */
    public void reload() {
        api = null;
        apiType = null;
        statType = null;
        combatStat = null;
        levelStat = null;
        getTalentPoints = null;
        spendTalentPoints = null;
        refundTalentPoints = null;
        spentTalentPointsBySource = null;
        getLevel = null;
        apiClassName = plugin.getConfig().getString("earthexp.api-class", "").trim();
        statClassName = plugin.getConfig().getString("earthexp.stat-class", "").trim();
        // 留空 = 自动探测（推荐）：装了 EarthExp 就直接接入，不用手填类名。
        // 显式填写则尊重填写的值（例如 EarthExp 换了包名）。
        boolean autoDetect = apiClassName.isEmpty() && statClassName.isEmpty();
        if (apiClassName.isEmpty()) apiClassName = DEFAULT_API_CLASS;
        if (statClassName.isEmpty()) statClassName = DEFAULT_STAT_CLASS;
        // 优先读新的 skill-point-stat；没写就退回旧的 combat-stat（老配置不改也能用）。
        // 两个键名并存是因为旧名 combat-stat 现在会误导人。
        // 默认 COMBAT：技能点就是战斗天赋点。EarthExp 0.5.1 起外部扣点按轨道分开记账，
        // COMBAT 与 ADVENTURE 各自扣自己的池子；0.5.0 及更早只允许 ADVENTURE。
        combatStatName = plugin.getConfig().getString("earthexp.skill-point-stat", "").trim();
        if (combatStatName.isEmpty()) {
            combatStatName = plugin.getConfig().getString("earthexp.combat-stat", "COMBAT").trim();
        }
        // 总开关：false = 强制用宝石消耗（即使装了 EarthExp）。默认 true。
        if (!plugin.getConfig().getBoolean("earthexp.enabled", true)) {
            status = "已关闭（earthexp.enabled=false）：升级消耗宝石";
            plugin.getLogger().info("earthexp.enabled=false：技能升级使用宝石消耗（已跳过 EarthExp 接入）。");
            return;
        }
        try {
            apiType = resolveClass(apiClassName);
            statType = resolveClass(statClassName);
        } catch (ClassNotFoundException exception) {
            if (autoDetect) {
                // 自动探测失败 = 没装 EarthExp。它是可选附属，用 info 而不是可操作的告警。
                status = "未接入：没找到 EarthExp 的类，升级消耗宝石";
                plugin.getLogger().info("未检测到 EarthExp（可选附属）：技能升级使用宝石消耗。"
                        + "装了 EarthExp 仍看到这行，请检查它是否真的加载成功。");
            } else {
                status = "未接入：config 里填的类名找不到（" + apiClassName + "）";
                plugin.getLogger().warning("找不到 EarthExp 的类（" + apiClassName + " / " + statClassName
                        + "）：技能升级将使用宝石消耗。请检查 config.yml 的 earthexp 段类名是否写错，"
                        + "或把它留空改用自动探测。");
            }
            return;
        }
        Object found = null;
        for (Object constant : statType.getEnumConstants()) {
            if (constant != null && constant.toString().equalsIgnoreCase(combatStatName)) {
                found = constant;
                break;
            }
        }
        if (found == null) {
            status = "未接入：EarthExp 的 Stat 枚举里没有 " + combatStatName;
            plugin.getLogger().warning("EarthExp 的 Stat 枚举里没有 " + combatStatName + "，技能点功能不可用。");
            return;
        }
        combatStat = found;
        // 物品等级门槛固定看战斗等级，与上面技能点轨道无关。
        for (Object constant : statType.getEnumConstants()) {
            if (constant != null && "COMBAT".equalsIgnoreCase(constant.toString())) {
                levelStat = constant;
                break;
            }
        }
        api = Bukkit.getServicesManager().load(apiType);
        if (api == null) {
            // 类找到了、但服务没注册：典型原因是 EarthExp 没有真的启用成功（它自己报错了），
            // 或者本插件在 EarthExp 之前启用（plugin.yml 的 softdepend 就是为这个加的）。
            status = "未接入：EarthExp 的类在，但服务没注册（EarthExp 可能没启用成功）";
            plugin.getLogger().warning("找到了 " + apiClassName + "，但它没有把 EarthExpAPI 注册到服务管理器："
                    + "技能升级将使用宝石消耗。请确认 EarthExp 已正常启用（看它自己的启动日志有没有报错），"
                    + "并确认 EarthRPGItem 的 plugin.yml 里有 softdepend: [EarthExp]。"
                    + "若刚装好，执行一次 /rpgitem reload 也会重新尝试接入。");
            return;
        }
        // 配成没有天赋池的轨道时提前喊出来。EarthExp 只有战斗（COMBAT）与冒险（ADVENTURE）
        // 两条轨道有天赋点，其余轨道（挖矿/砍树/农业）的 talentObjective 为 null，
        // 扣点一定失败。这个校验与 EarthExp 版本无关，写成哪条轨道都成立。
        if (deductsTalentPoints() && !"COMBAT".equalsIgnoreCase(combatStatName)
                && !"ADVENTURE".equalsIgnoreCase(combatStatName)) {
            plugin.getLogger().warning("技能点轨道配置为 " + combatStatName
                    + "，但 EarthExp 只有战斗（COMBAT）与冒险（ADVENTURE）两条轨道有天赋点："
                    + "技能升级与预设重分配的点数扣减都会失败。"
                    + "把 config.yml 的 earthexp.skill-point-stat 改成 COMBAT 或 ADVENTURE。");
        }
        // 接入成功也打一行，管理员一眼就能确认状态，不用去猜「到底接上没有」。
        String trackLabel = "ADVENTURE".equalsIgnoreCase(combatStatName) ? "冒险" : "战斗";
        status = "已接入：升级消耗【" + trackLabel + "】天赋点（轨道 " + combatStatName + "）"
                + (deductsTalentPoints() ? "" : "；该版本无扣点接口，升级只校验不扣点");
        plugin.getLogger().info("EarthExp 已接入：技能升级消耗【" + trackLabel
                + "天赋点】（轨道 " + combatStatName + "）"
                + (deductsTalentPoints() ? "。" : "；该 EarthExp 版本没有扣点接口，升级只校验不扣点。"));
    }

    /** EarthExp 已接入（类名配置正确且服务管理器能拿到实例）。 */
    public boolean isAvailable() {
        return api != null && apiType != null && statType != null && combatStat != null;
    }

    /**
     * EarthExp 的 API 上是否<b>存在</b>扣点方法（{@code spendTalentPoints}，0.5.0+）。
     *
     * <p>这个判断必须独立于 {@link #isAvailable()}：接入了 EarthExp、但它的 API 还没有实装扣点
     * 方法时，{@link #spend} 会走「找不到方法 → 不扣、仅校验」那条分支；此时
     * {@code getTalentPoints} 返回的可用点数<b>并不包含</b>本插件花掉的技能点，
     * 于是「把技能等级重置回 Lv.1 就能退还点数」这个前提根本不成立。
     * 技能预设靠它决定要不要做天赋重分配。
     *
     * <p><b>它只回答「接口在不在」，不回答「这次扣点会不会被受理」</b>：点数不足、
     * 配到没有天赋池的轨道，或（0.5.0 及更早）配成非 ADVENTURE 时，本方法返回 true、
     * 但 {@link #spend} 会返回 false。
     * 凡是「动了数据之后要靠扣点成功来兜底」的地方，都必须看 {@link #spend} 的返回值。
     */
    public boolean deductsTalentPoints() {
        return spendMethod() != null;
    }

    /** 战斗天赋点（COMBAT 池）。EarthExp 当前语义：天赋点 = 等级。 */
    public int talentPoints(Player player) {
        if (!isAvailable() || player == null) return 0;
        try {
            Method method = getTalentPoints;
            if (method == null) {
                method = apiType.getMethod("getTalentPoints", Player.class, statType);
                getTalentPoints = method;
            }
            Object result = method.invoke(api, player, combatStat);
            return result instanceof Number number ? number.intValue() : 0;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return 0;
        }
    }

    /**
     * 玩家的<b>战斗等级</b>（EarthExp 的 {@code Stat.COMBAT} 轨道）。
     *
     * <p>用于 RPG 物品的等级门槛：{@code level} 字段以前比的是原版等级
     * （{@code Player#getLevel()}），现在改成比 EarthExp 的战斗等级。
     *
     * <p>EarthExp 没装、没接入或调用失败时返回 {@code -1}，调用方据此退回原版等级，
     * 而不是把所有人都判成 0 级、让所有带等级要求的物品直接失效。
     */
    public int combatLevel(Player player) {
        if (!isAvailable() || player == null || levelStat == null) return -1;
        try {
            Method method = getLevel;
            if (method == null) {
                method = apiType.getMethod("getLevel", Player.class, statType);
                getLevel = method;
            }
            Object result = method.invoke(api, player, levelStat);
            return result instanceof Number number ? number.intValue() : -1;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return -1;
        }
    }

    /**
     * 花费 {@code points} 点。
     *
     * <p>EarthExp 0.5.0 起走 {@code spendTalentPoints(Player, Stat, int, String)}；
     * 更早的版本没有这个方法，此时<b>不扣、仅校验</b>（接口只增不改），返回 true。
     *
     * @return false 表示 EarthExp 明确拒绝了这次扣点：可用点数不足，
     *         或该轨道<b>没有天赋池</b>（只有 COMBAT / ADVENTURE 有），
     *         或（仅 0.5.0 及更早）配置的轨道没有开放外部消费
     */
    public boolean spend(Player player, int points) {
        if (points <= 0) return true;
        if (!isAvailable() || player == null) return false;
        Method method = spendMethod();
        if (method == null) {
            // 老版 EarthExp 没有扣点接口：不扣，仅校验。
            return true;
        }
        try {
            Object result = method.invoke(api, player, combatStat, points, SPEND_SOURCE);
            return Boolean.TRUE.equals(result);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return false;
        }
    }

    /**
     * 退还 {@code points} 点，返回<b>实际</b>退回的数量（可能小于请求值，甚至为 0）。
     *
     * <p>EarthExp 把退款上限卡在该来源<b>实际扣过</b>的量上，所以没扣过就退不出任何点数——
     * 「凭空发点」在结构上被堵住了，不依赖调用方自觉。这里额外再确认一次「接口真的存在」，
     * 属于冗余保险：万一将来出现「只有 refund 没有 spend」的版本，也不会变成发点接口。
     *
     * @return 实际退回的点数；接口不存在或调用失败时为 0
     */
    public int refund(Player player, int points) {
        if (points <= 0) return 0;
        if (!isAvailable() || player == null) return 0;
        if (spendMethod() == null) return 0;
        Method method = refundMethod();
        if (method == null) return 0;
        try {
            Object result = method.invoke(api, player, combatStat, points, SPEND_SOURCE);
            return result instanceof Number number ? number.intValue() : 0;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return 0;
        }
    }

    /**
     * 本插件（{@link #SPEND_SOURCE} 名下）在 EarthExp 账上<b>实际扣掉</b>的点数。
     *
     * <p>这是「重置技能等级能退回多少点」的<b>唯一可靠上限</b>：升级发生在扣点接口出现之前
     * 的老玩家，账上根本没有记录，他们的等级退不出点数。用本插件自己的
     * {@code skill-levels.yml} 求和会算出一个退不出来的数字。
     */
    public int spentTalentPoints(Player player) {
        if (!isAvailable() || player == null) return 0;
        Method method = spentBySourceMethod();
        if (method == null) return 0;
        try {
            Object result = method.invoke(api, player, combatStat, SPEND_SOURCE);
            return result instanceof Number number ? number.intValue() : 0;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return 0;
        }
    }

    private Method spendMethod() {
        if (!isAvailable()) return null;
        Method method = spendTalentPoints;
        if (method == null) {
            try {
                method = apiType.getMethod("spendTalentPoints", Player.class, statType,
                        int.class, String.class);
            } catch (NoSuchMethodException exception) {
                return null;
            }
            spendTalentPoints = method;
        }
        return method;
    }

    private Method refundMethod() {
        if (!isAvailable()) return null;
        Method method = refundTalentPoints;
        if (method == null) {
            try {
                method = apiType.getMethod("refundTalentPoints", Player.class, statType,
                        int.class, String.class);
            } catch (NoSuchMethodException exception) {
                return null;
            }
            refundTalentPoints = method;
        }
        return method;
    }

    private Method spentBySourceMethod() {
        if (!isAvailable()) return null;
        Method method = spentTalentPointsBySource;
        if (method == null) {
            try {
                method = apiType.getMethod("getSpentTalentPoints", Player.class, statType, String.class);
            } catch (NoSuchMethodException exception) {
                return null;
            }
            spentTalentPointsBySource = method;
        }
        return method;
    }
}
