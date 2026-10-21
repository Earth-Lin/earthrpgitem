package EarthRPGItem.api;

import EarthRPGItem.EarthRPGItem;
import EarthRPGItem.collection.CollectionKind;
import EarthRPGItem.items.RpgStats;
import org.bukkit.Material;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Implementation of {@link EarthRPGItemAPI}, registered with the service manager on enable.
 * <p>
 * The state itself lives in {@link EarthRPGItem.items.RpgItemManager}, because that is where stats
 * are collected; this class only adapts the public contract to it and cleans up after quits.
 */
public final class EarthRPGItemApiImpl implements EarthRPGItemAPI, Listener {

    private final EarthRPGItem plugin;

    public EarthRPGItemApiImpl(EarthRPGItem plugin) {
        this.plugin = plugin;
    }

    @Override
    public RpgStats getStats(LivingEntity entity) {
        if (entity == null) return RpgStats.empty();
        return plugin.getRpgItemManager().collectActiveStats(entity, false);
    }

    @Override
    public RpgStats getModifier(LivingEntity entity, String source) {
        return plugin.getRpgItemManager().attributeModifier(entity, source);
    }

    @Override
    public Set<String> getModifierSources(LivingEntity entity) {
        return plugin.getRpgItemManager().attributeModifierSources(entity);
    }

    @Override
    public boolean hasModifier(LivingEntity entity, String source) {
        return plugin.getRpgItemManager().hasAttributeModifier(entity, source);
    }

    @Override
    public void setModifier(LivingEntity entity, String source, RpgStats stats, double durationSeconds) {
        plugin.getRpgItemManager().setAttributeModifier(entity, source, stats, durationSeconds);
    }

    @Override
    public void setModifier(LivingEntity entity, String source, Map<String, Double> attributes, double durationSeconds) {
        plugin.getRpgItemManager().setAttributeModifier(entity, source, fromAttributeNames(attributes), durationSeconds);
    }

    @Override
    public void addModifier(LivingEntity entity, String source, RpgStats stats, double durationSeconds) {
        plugin.getRpgItemManager().addAttributeModifier(entity, source, stats, durationSeconds);
    }

    @Override
    public void removeModifier(LivingEntity entity, String source) {
        plugin.getRpgItemManager().removeAttributeModifier(entity, source);
    }

    @Override
    public void clearModifiers(LivingEntity entity) {
        plugin.getRpgItemManager().clearAttributeModifiers(entity);
    }

    @Override
    public void setVanillaModifier(LivingEntity entity, String source, String attributeName, double amount,
                                   AttributeModifier.Operation operation, double durationSeconds) {
        plugin.getRpgItemManager().setVanillaModifier(entity, source, attributeName, amount, operation, durationSeconds);
    }

    @Override
    public void removeVanillaModifiers(LivingEntity entity, String source) {
        plugin.getRpgItemManager().removeVanillaModifiers(entity, source);
    }

    @Override
    public void registerGatheringLevelProvider(GatheringLevelProvider provider) {
        if (plugin.getGatheringSkillManager() != null) plugin.getGatheringSkillManager().registerProvider(provider);
    }

    @Override
    public void unregisterGatheringLevelProvider(GatheringLevelProvider provider) {
        if (plugin.getGatheringSkillManager() != null) plugin.getGatheringSkillManager().unregisterProvider(provider);
    }

    @Override
    public int getGatheringLevel(Player player, CollectionKind category) {
        if (plugin.getGatheringSkillManager() == null) return 0;
        return plugin.getGatheringSkillManager().levelOf(player, category);
    }

    @Override
    public void awardGatheringExperience(Player player, CollectionKind category, double amount, Material blockType) {
        if (plugin.getGatheringSkillManager() == null) return;
        plugin.getGatheringSkillManager().awardExperience(player, category, amount, blockType);
    }

    /**
     * Accepts the attribute names as written in item configuration, so {@code Attack}, {@code
     * crit-chance} and {@code critchance} all resolve to the same key.
     */
    private static RpgStats fromAttributeNames(Map<String, Double> attributes) {
        if (attributes == null || attributes.isEmpty()) return RpgStats.empty();
        Map<String, Double> normalized = new HashMap<>();
        for (Map.Entry<String, Double> entry : attributes.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            String key = entry.getKey().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").replace(" ", "");
            normalized.merge(key, entry.getValue(), Double::sum);
        }
        return RpgStats.fromLore(normalized);
    }

    /**
     * Modifier state is dropped when a player leaves. Permanent modifiers are not lost: they live in
     * the player's persistent data and are read back on the next join.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        plugin.getRpgItemManager().forgetAttributeModifiers(player);
    }
}
