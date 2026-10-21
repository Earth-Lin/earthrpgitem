package EarthRPGItem.effects;

import EarthRPGItem.EarthRPGItem;
import EarthRPGItem.util.Texts;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class DamageDisplay {

    private final EarthRPGItem plugin;

    public DamageDisplay(EarthRPGItem plugin) {
        this.plugin = plugin;
    }

    public void show(Location baseLocation, List<String> lines) {
        if (!plugin.getConfig().getBoolean("damage-display.enabled", true) || lines.isEmpty()) {
            return;
        }
        double range = Math.min(3, Math.max(0, plugin.getConfig().getDouble("damage-display.random-range", 1.0)));
        Location location = baseLocation.clone().add(randomOffset(range), 1.2 + ThreadLocalRandom.current().nextDouble(0.6), randomOffset(range));
        int durationTicks = Math.max(1, plugin.getConfig().getInt("damage-display.duration-seconds", 3) * 20);
        TextDisplay display;
        try {
            display = location.getWorld().spawn(location, TextDisplay.class, entity -> {
                entity.text(Texts.color(String.join("\n", lines)));
                entity.setBillboard(Display.Billboard.CENTER);
                entity.setGravity(false);
                entity.setPersistent(false);
                entity.setInvulnerable(true);
                entity.setShadowed(false);
                entity.setSeeThrough(true);
            });
        } catch (RuntimeException exception) {
            // A damage number is decoration: if the world refuses the spawn (chunk unloading, a world
            // that is already gone, a plugin vetoing entity creation) the hit itself must still be
            // scored. Swallowing it here also keeps one failed number from aborting the whole AoE
            // loop, which is what used to happen when this call sat inside the damage path.
            return;
        }
        if (display == null || !display.isValid()) return;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (display.isValid()) display.remove();
        }, durationTicks);
    }

    public String format(String formatPath, String colorPath, String fallbackFormat, long damage) {
        String color = plugin.getConfig().getString(colorPath, fallbackColor(colorPath));
        String prefixPath = formatPath.endsWith("-format")
                ? formatPath.substring(0, formatPath.length() - "-format".length()) + "-prefix"
                : formatPath + "-prefix";
        if (plugin.getConfig().getBoolean("damage-display.prefix-enabled", false)
                && plugin.getConfig().contains(prefixPath)) {
            return color + plugin.getConfig().getString(prefixPath, "") + damage;
        }
        String format = plugin.getConfig().getString(formatPath, fallbackFormat);
        return color + format.replace("{damage}", String.valueOf(damage));
    }

    private String fallbackColor(String colorPath) {
        return switch (colorPath) {
            case "damage-display.magic-color" -> "&d";
            case "damage-display.magic-crit-color" -> "&5";
            case "damage-display.physical-crit-color" -> "&6";
            case "damage-display.true-color" -> "&c";
            case "damage-display.bow-color" -> "&a";
            case "damage-display.bow-crit-color" -> "&2";
            case "damage-display.ice-color" -> "&9";
            case "damage-display.fire-color" -> "&c";
            case "damage-display.dark-color" -> "&0";
            case "damage-display.backstrike-color" -> "&e";
            case "damage-display.assassin-color" -> "&c";
            case "damage-display.light-color" -> "&e";
            default -> "&f";
        };
    }

    private double randomOffset(double range) {
        return ThreadLocalRandom.current().nextDouble(-range, range);
    }
}
