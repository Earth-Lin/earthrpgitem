package EarthRPGItem.effects;

import EarthRPGItem.EarthRPGItem;
import EarthRPGItem.items.SoundConfig;
import org.bukkit.Location;
import org.bukkit.Sound;

public class SoundEffectPlayer {

    private final EarthRPGItem plugin;

    public SoundEffectPlayer(EarthRPGItem plugin) {
        this.plugin = plugin;
    }

    public void play(Location location, SoundConfig config) {
        if (!plugin.isFeatureEnabled("sounds") || !plugin.getConfig().getBoolean("sounds.enabled", true) || !config.enabled()) {
            return;
        }
        Sound sound = parseSound(config.sound());
        if (sound == null) {
            return;
        }
        location.getWorld().playSound(location, sound, config.volume(), config.pitch());
    }

    private Sound parseSound(String value) {
        try {
            return Sound.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
