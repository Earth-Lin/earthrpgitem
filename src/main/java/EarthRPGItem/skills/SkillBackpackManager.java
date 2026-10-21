package EarthRPGItem.skills;

import EarthRPGItem.EarthRPGItem;
import EarthRPGItem.util.YamlStore;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Per-player skill backpack. Stores registration ids only, so the same registered
 * skill can never be stored twice and no physical item can be duplicated.
 */
public final class SkillBackpackManager {

    public static final int CAPACITY = 45;

    private final EarthRPGItem plugin;
    private final File dataFile;
    private final YamlConfiguration data;

    public SkillBackpackManager(EarthRPGItem plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "skill-backpack.yml");
        this.data = YamlStore.load(dataFile, plugin.getLogger());
    }

    public List<String> contents(UUID playerId) {
        List<String> stored = data.getStringList(path(playerId));
        List<String> result = new ArrayList<>(stored.size());
        for (String id : stored) {
            if (id != null && !id.isBlank() && !result.contains(id)) result.add(id);
        }
        return result;
    }

    public boolean contains(UUID playerId, String skillId) {
        if (skillId == null || skillId.isBlank()) return false;
        for (String id : contents(playerId)) if (id.equalsIgnoreCase(skillId)) return true;
        return false;
    }

    /** @return false when the skill is already stored, the backpack is full, or the save failed. */
    public boolean add(UUID playerId, String skillId) {
        if (skillId == null || skillId.isBlank()) return false;
        if (contains(playerId, skillId)) return false;
        List<String> stored = contents(playerId);
        if (stored.size() >= CAPACITY) return false;
        List<String> updated = new ArrayList<>(stored);
        updated.add(skillId);
        return write(playerId, updated);
    }

    public boolean remove(UUID playerId, String skillId) {
        if (skillId == null || skillId.isBlank()) return false;
        List<String> stored = contents(playerId);
        boolean removed = stored.removeIf(id -> id.equalsIgnoreCase(skillId));
        if (!removed) return false;
        if (!write(playerId, stored)) return false;
        // 取出技能后必须把面板槽位一起清掉：否则面板上还挂着这个技能，按了却毫无反应
        // （resolve() 会因为它不在背包而返回 null）。离线玩家读不到 PDC，
        // 这种情况由面板打开时的 SkillPanelManager.pruneInvalidSlots() 兜底清理。
        Player player = plugin.getServer().getPlayer(playerId);
        if (player != null) plugin.getSkillPanelManager().clearSlotsFor(player, skillId);
        return true;
    }

    public boolean isFull(UUID playerId) {
        return contents(playerId).size() >= CAPACITY;
    }

    /**
     * 移除所有玩家背包里已不在注册表中的技能 id（技能文件被删/改名、配置被移除时清理残留）。
     * <p>
     * 背包存的是「注册 id」，插件更新后描述/形态/数值都跟着新注册走；这里保证已不存在的
     * 注册不会永远留在玩家数据里。
     */
    public void pruneMissing(Set<String> validIds) {
        ConfigurationSection players = data.getConfigurationSection("players");
        if (players == null) return;
        Set<String> lower = new HashSet<>();
        for (String valid : validIds) {
            if (valid != null && !valid.isBlank()) lower.add(valid.toLowerCase(Locale.ROOT));
        }
        for (String key : players.getKeys(false)) {
            UUID playerId;
            try {
                playerId = UUID.fromString(key);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            List<String> stored = contents(playerId);
            List<String> kept = new ArrayList<>(stored.size());
            for (String id : stored) {
                if (id != null && !id.isBlank() && lower.contains(id.toLowerCase(Locale.ROOT))) kept.add(id);
            }
            if (kept.size() != stored.size()) write(playerId, kept);
        }
    }

    /** Persists the new list and rolls the in-memory change back if the save fails. */
    private boolean write(UUID playerId, List<String> stored) {
        String path = path(playerId);
        List<String> previous = data.getStringList(path);
        data.set(path, stored);
        if (YamlStore.save(data, dataFile, plugin.getLogger())) return true;
        data.set(path, previous);
        return false;
    }

    private String path(UUID playerId) {
        return "players." + playerId;
    }
}
