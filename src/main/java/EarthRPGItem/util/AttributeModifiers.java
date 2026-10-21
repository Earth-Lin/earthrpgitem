package EarthRPGItem.util;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlotGroup;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 跨版本构造 {@link AttributeModifier} 的适配层。
 *
 * <p><b>背景</b>：1.21 起 {@code AttributeModifier} 新增了以 {@link NamespacedKey} 为标识的构造器，
 * 并且 {@link AttributeModifier#getKey()} 才存在。而 1.20.5 / 1.20.6 只有以 {@link UUID}
 * 为标识的构造器，没有 {@code getKey()}。两者**互不兼容**：直接用其中一版的 API 写代码，
 * 另一版在运行时就会 {@code NoSuchMethodError} 或编译不过。
 *
 * <p><b>做法</b>：编译期统一用 1.20.6 的 API（UUID 构造器），运行时用反射探测是否存在
 * {@code NamespacedKey} 构造器，存在就优先用它。这样：
 * <ul>
 *   <li>1.20.5 / 1.20.6：走 UUID 分支，行为与原版一致；</li>
 *   <li>1.21.x：走 NamespacedKey 分支，与插件其余部分（例如 {@code disableOutdatedVanillaAttributes}
 *       里按 key 前缀识别本插件的修饰符）保持一致。</li>
 * </ul>
 *
 * <p><b>为什么 UUID 分支也要用一个确定的算法</b>：修饰符需要「同样的物品 → 同样的 UUID」，
 * 否则每次刷新属性都会叠加出一个新的修饰符，属性会越刷越高。这里从 {@link NamespacedKey}
 * 的完整字符串稳定地推导出 UUID（名称型 UUID），保证同一 key 永远得到同一 UUID。
 */
public final class AttributeModifiers {

    private AttributeModifiers() {
    }

    /** 1.21+ 的 {@code AttributeModifier(NamespacedKey, double, Operation, EquipmentSlotGroup)}，老版本为 null。 */
    private static final Constructor<AttributeModifier> KEY_CONSTRUCTOR = findKeyConstructor();

    /** 是否运行在支持 NamespacedKey 标识的版本（1.21+）。 */
    public static boolean usesKeys() {
        return KEY_CONSTRUCTOR != null;
    }

    private static Constructor<AttributeModifier> findKeyConstructor() {
        try {
            Constructor<AttributeModifier> constructor = AttributeModifier.class.getConstructor(
                    NamespacedKey.class, double.class, AttributeModifier.Operation.class, EquipmentSlotGroup.class);
            constructor.setAccessible(true);
            return constructor;
        } catch (NoSuchMethodException | RuntimeException ignored) {
            return null;
        }
    }

    /**
     * 以 {@link NamespacedKey} 为标识创建修饰符，自动适配当前服务端版本。
     *
     * @param key       修饰符标识（插件命名空间下的键）
     * @param amount    数值
     * @param operation 运算方式
     * @param slot      生效槽位；{@code null} 表示不限制槽位
     */
    public static AttributeModifier create(NamespacedKey key, double amount,
                                           AttributeModifier.Operation operation, EquipmentSlotGroup slot) {
        if (KEY_CONSTRUCTOR != null) {
            try {
                return KEY_CONSTRUCTOR.newInstance(key, amount, operation, slot);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                // 理论到不了这里；退到 UUID 分支保证仍然可用。
            }
        }
        return createUuidBased(key, amount, operation, slot);
    }

    private static AttributeModifier createUuidBased(NamespacedKey key, double amount,
                                                     AttributeModifier.Operation operation, EquipmentSlotGroup slot) {
        UUID uuid = uuidFor(key);
        if (slot == null) return new AttributeModifier(uuid, key.getKey(), amount, operation);
        return new AttributeModifier(uuid, key.getKey(), amount, operation, slot);
    }

    /**
     * 由 {@link NamespacedKey} 稳定推导出 UUID（名称型 UUID，{@code UUID v3} 的算法）。
     *
     * <p>同一个 key 永远得到同一个 UUID —— 这是「反复刷新属性不会叠加修饰符」的前提。
     */
    public static UUID uuidFor(NamespacedKey key) {
        String source = key.getNamespace() + ":" + key.getKey();
        // UUID.nameUUIDFromBytes 用 MD5 生成名称型 UUID，跨 JVM 稳定。
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 取修饰符的标识串，自动适配版本。
     *
     * <p>1.21+ 返回 {@code namespace:key}；1.20.x 没有 key 概念，退回 UUID 字符串。
     * 调用方用它做前缀判断时，应同时兼容两种情况。
     */
    public static String identifierOf(AttributeModifier modifier) {
        if (modifier == null) return "";
        if (usesKeys()) {
            try {
                Object key = AttributeModifier.class.getMethod("getKey").invoke(modifier);
                if (key instanceof NamespacedKey namespacedKey) {
                    return namespacedKey.getNamespace() + ":" + namespacedKey.getKey();
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // 落到 UUID 分支
            }
        }
        UUID uniqueId = modifier.getUniqueId();
        return uniqueId == null ? "" : uniqueId.toString();
    }

    /**
     * 该修饰符是否属于本插件（按 {@link NamespacedKey} 的命名空间判断）。
     *
     * <p>1.20.x 无法从修饰符反查命名空间，只能靠 {@link #uuidFor(NamespacedKey)} 反推：
     * 调用方把自己用过的 key 逐个比对 UUID。这里提供便捷判断。
     */
    public static boolean belongsTo(NamespacedKey key, AttributeModifier modifier) {
        if (key == null || modifier == null) return false;
        if (usesKeys()) {
            try {
                Object actual = AttributeModifier.class.getMethod("getKey").invoke(modifier);
                return key.equals(actual);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // 落到 UUID 分支
            }
        }
        UUID expected = uuidFor(key);
        return expected.equals(modifier.getUniqueId());
    }
}
