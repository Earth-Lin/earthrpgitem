package EarthRPGItem.collection;

import org.bukkit.Material;

import java.util.List;
import java.util.Set;

public record CollectionDefinition(
        String id,
        String displayName,
        Set<Material> resourceMaterials,
        Material itemMaterial,
        Set<Material> supportMaterials,
        double hardness,
        long refreshTicks,
        Material depletedMaterial,
        CollectionKind kind,
        List<CollectionDrop> drops,
        // 采集该资源点成功一次发给外部插件的经验量（0 = 不发）。
        double experienceAmount,
        // 采集该资源点需要的等级（0 = 无门槛；有门槛时按 kind 分类用玩家等级 / 工具加成判定）。
        int requiredLevel
) {
    public Material resourceMaterial() {
        return resourceMaterials.iterator().next();
    }

    public Material defaultMaterial() {
        return resourceMaterial();
    }

    /** 这个资源点是否会发采集经验。 */
    public boolean givesExperience() {
        return experienceAmount > 0;
    }

    /** 这个资源点是否有等级门槛。 */
    public boolean hasLevelGate() {
        return requiredLevel > 0;
    }
}
