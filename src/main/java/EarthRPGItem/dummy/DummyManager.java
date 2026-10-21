package EarthRPGItem.dummy;

import EarthRPGItem.EarthRPGItem;
import EarthRPGItem.gui.RpgMenu;
import EarthRPGItem.util.Texts;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.EntityEffect;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class DummyManager {
    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS_SLOT = 45;
    private static final int BACK_SLOT = 49;
    private static final int NEXT_SLOT = 53;
    private static final long PROGRESS_FLUSH_MILLIS = 1000L;
    private static final long NAME_UPDATE_MILLIS = 500L;

    private final EarthRPGItem plugin;
    private final NamespacedKey dummyKey;
    private final NamespacedKey damageLogKey;
    private final NamespacedKey virtualHealthKey;
    private final Map<UUID, DummyStats> dummyStats = new HashMap<>();

    public DummyManager(EarthRPGItem plugin) {
        this.plugin = plugin;
        this.dummyKey = new NamespacedKey(plugin, "dummy");
        this.damageLogKey = new NamespacedKey(plugin, "dummy_damage_log");
        this.virtualHealthKey = new NamespacedKey(plugin, "dummy_virtual_health");
    }

    public LivingEntity spawnDummy(Player player) {
        EntityType type = parseType(plugin.getConfig().getString("dummy.default-type", "ZOMBIE"));
        Location location = player.getLocation().add(player.getLocation().getDirection().normalize().multiply(2));
        LivingEntity entity = (LivingEntity) player.getWorld().spawnEntity(location, type);
        setupDummy(entity);
        player.sendMessage(Texts.color("&aDummy spawned."));
        return entity;
    }

    public int deleteNearby(Player player) {
        double radius = Math.max(1, plugin.getConfig().getDouble("dummy.clear-radius", 32));
        int removed = 0;
        for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            if (isDummy(entity)) {
                entity.remove();
                dummyStats.remove(entity.getUniqueId());
                removed++;
            }
        }
        player.sendMessage(Texts.color("&aRemoved &e" + removed + " &adummies."));
        return removed;
    }

    public void setupDummy(LivingEntity entity) {
        double virtualHealth = Math.max(20, plugin.getConfig().getDouble("dummy.max-health", 2048));
        var attribute = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        double physicalHealth = Math.min(2048, virtualHealth);
        if (attribute != null) {
            try {
                attribute.setBaseValue(physicalHealth);
            } catch (IllegalArgumentException exception) {
                attribute.setBaseValue(Math.min(1024, physicalHealth));
            }
            entity.setHealth(attribute.getValue());
        }
        entity.setAI(false);
        entity.setPersistent(true);
        entity.setRemoveWhenFarAway(false);
        entity.getPersistentDataContainer().set(dummyKey, PersistentDataType.INTEGER, 1);
        entity.getPersistentDataContainer().set(virtualHealthKey, PersistentDataType.DOUBLE, virtualHealth);
        updateName(entity, 0);
    }

    public boolean isDummy(Entity entity) {
        return entity instanceof LivingEntity && entity.getPersistentDataContainer().has(dummyKey, PersistentDataType.INTEGER);
    }

    public boolean absorbDamage(LivingEntity entity, double damage) {
        if (!isDummy(entity) || damage <= 0) return false;
        recordDamage(entity, damage);
        entity.playEffect(EntityEffect.HURT);
        entity.setNoDamageTicks(0);
        entity.setLastDamage(0);
        restorePhysicalHealth(entity);
        return true;
    }

    public void recordDamage(LivingEntity entity, double damage) {
        if (!isDummy(entity) || damage <= 0) return;
        DummyStats state = dummyStats.computeIfAbsent(entity.getUniqueId(), id -> new DummyStats());
        if (!state.loaded) {
            state.entries.addAll(getEntries(entity));
            state.loaded = true;
        }
        long now = System.currentTimeMillis();
        state.entries.addLast(new DamageEntry(now, damage));
        trim(state.entries, now);

        double total = 0;
        for (DamageEntry entry : state.entries) total += entry.damage();
        long seconds = state.entries.stream().map(entry -> entry.time() / 1000L).distinct().count();
        // At attack speed the per-hit parsing, NBT rewrite and entity rename were main-thread
        // churn. The 60s window makes a throttled display just as accurate as a per-hit one, and
        // absorbDamage already restores physical health synchronously, so no task is scheduled here.
        if (now - state.lastNameUpdate >= NAME_UPDATE_MILLIS) {
            state.lastNameUpdate = now;
            updateName(entity, total / Math.max(1, seconds));
        }
        if (now - state.lastFlush >= PROGRESS_FLUSH_MILLIS) {
            state.lastFlush = now;
            setEntries(entity, state.entries);
        }
    }

    public void openMenu(Player player, LivingEntity dummy, int page) {
        List<EntityType> types = switchableTypes();
        int maxPage = Math.max(1, (int) Math.ceil(types.size() / (double) PAGE_SIZE));
        int currentPage = Math.max(0, Math.min(page, maxPage - 1));
        Inventory inventory = Bukkit.createInventory(new DummyMenuHolder(dummy.getUniqueId(), currentPage), 54, Texts.color("DummyGui"));
        int start = currentPage * PAGE_SIZE;
        int end = Math.min(types.size(), start + PAGE_SIZE);
        for (int index = start; index < end; index++) inventory.setItem(index - start, egg(types.get(index)));
        inventory.setItem(PREVIOUS_SLOT, pageButton(currentPage > 0, false, currentPage + 1, maxPage));
        inventory.setItem(BACK_SLOT, button(Material.BARRIER, "&cClose", "&7Close DummyGui"));
        inventory.setItem(NEXT_SLOT, pageButton(currentPage + 1 < maxPage, true, currentPage + 1, maxPage));
        player.openInventory(inventory);
    }

    public void switchType(Player player, UUID dummyId, EntityType type) {
        Entity entity = Bukkit.getEntity(dummyId);
        if (!(entity instanceof LivingEntity oldDummy) || !isDummy(oldDummy)) return;
        Location location = oldDummy.getLocation();
        oldDummy.remove();
        dummyStats.remove(oldDummy.getUniqueId());
        LivingEntity newDummy = (LivingEntity) location.getWorld().spawnEntity(location, type);
        setupDummy(newDummy);
        player.sendMessage(Texts.color("&aDummy type switched to &e" + type.name()));
        openMenu(player, newDummy, 0);
    }

    public List<EntityType> switchableTypes() {
        List<EntityType> result = new ArrayList<>();
        for (EntityType type : EntityType.values()) {
            if (!type.isAlive() || !type.isSpawnable()) continue;
            if (eggMaterial(type) != null) result.add(type);
        }
        result.sort(Comparator.comparing(Enum::name));
        return result;
    }

    public EntityType typeAt(int page, int slot) {
        int index = page * PAGE_SIZE + slot;
        List<EntityType> types = switchableTypes();
        return index >= 0 && index < types.size() ? types.get(index) : null;
    }

    public boolean hasNextPage(int page) {
        return (page + 1) * PAGE_SIZE < switchableTypes().size();
    }

    private void updateName(LivingEntity entity, double dps) {
        double virtualHealth = entity.getPersistentDataContainer().getOrDefault(virtualHealthKey, PersistentDataType.DOUBLE, 2048.0);
        entity.customName(Texts.color("&eDummy &7[" + entity.getType().name() + "] &aHP: &f"
                + String.format(Locale.US, "%.0f", virtualHealth) + " &cDPS60: &f" + String.format(Locale.US, "%.2f", dps)));
        entity.setCustomNameVisible(true);
    }

    private void restorePhysicalHealth(LivingEntity entity) {
        if (entity == null || entity.isDead() || !entity.isValid()) return;
        var attribute = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        double maxHealth = attribute == null ? 20 : attribute.getValue();
        entity.setHealth(Math.max(1, maxHealth));
    }

    private ItemStack egg(EntityType type) {
        return button(eggMaterial(type), "&e" + type.name(), "&7Click to switch dummy type");
    }

    private Material eggMaterial(EntityType type) {
        return Material.matchMaterial(type.name() + "_SPAWN_EGG");
    }

    private ItemStack pageButton(boolean enabled, boolean next, int currentPage, int maxPage) {
        if (!enabled) return button(Material.BEDROCK, next ? "&7Next" : "&7Previous", "&8No more pages " + currentPage + "/" + maxPage);
        return button(next ? Material.GREEN_WOOL : Material.BLUE_WOOL, (next ? "&aNext " : "&9Previous ") + currentPage + "/" + maxPage, "&7Click to turn page");
    }

    private ItemStack button(Material material, String name, String lore) {
        ItemStack itemStack = new ItemStack(material == null ? Material.ZOMBIE_SPAWN_EGG : material);
        var meta = itemStack.getItemMeta();
        meta.displayName(Texts.color(name));
        meta.lore(List.of(Texts.color(lore)));
        itemStack.setItemMeta(meta);
        return itemStack;
    }

    private EntityType parseType(String value) {
        try {
            EntityType type = EntityType.valueOf(value.toUpperCase(Locale.ROOT));
            return type.isAlive() && type.isSpawnable() ? type : EntityType.ZOMBIE;
        } catch (IllegalArgumentException exception) {
            return EntityType.ZOMBIE;
        }
    }

    private Deque<DamageEntry> getEntries(LivingEntity entity) {
        Deque<DamageEntry> entries = new ArrayDeque<>();
        String raw = entity.getPersistentDataContainer().get(damageLogKey, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) return entries;
        for (String part : raw.split(";")) {
            String[] pieces = part.split(":", 2);
            if (pieces.length != 2) continue;
            try { entries.addLast(new DamageEntry(Long.parseLong(pieces[0]), Double.parseDouble(pieces[1]))); } catch (NumberFormatException ignored) { }
        }
        return entries;
    }

    private void setEntries(LivingEntity entity, Deque<DamageEntry> entries) {
        StringBuilder builder = new StringBuilder();
        for (DamageEntry entry : entries) {
            if (!builder.isEmpty()) builder.append(';');
            builder.append(entry.time()).append(':').append(entry.damage());
        }
        entity.getPersistentDataContainer().set(damageLogKey, PersistentDataType.STRING, builder.toString());
    }

    private void trim(Deque<DamageEntry> entries, long now) {
        long cutoff = now - 60_000L;
        while (!entries.isEmpty() && entries.peekFirst().time() < cutoff) entries.removeFirst();
    }

    /** Diagnostic counter for /rpgitem memdebug: dummies whose damage log is held in memory. */
    public int trackedDummyCount() {
        return dummyStats.size();
    }

    private record DamageEntry(long time, double damage) { }

    /** Per-dummy in-memory state so a hit does not re-parse and re-write the whole NBT log. */
    private static final class DummyStats {
        private final Deque<DamageEntry> entries = new ArrayDeque<>();
        private long lastFlush;
        private long lastNameUpdate;
        private boolean loaded;
    }
}
