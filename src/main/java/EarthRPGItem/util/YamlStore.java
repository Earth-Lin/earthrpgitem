package EarthRPGItem.util;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;

/**
 * YAML persistence helpers that protect against the two leading failure modes:
 * a crash that truncates a file (fixed by temp-file + atomic rename) and a corrupt
 * file being silently treated as empty and then overwritten (fixed by back-up first).
 */
public final class YamlStore {
    private YamlStore() {
    }

    public static YamlConfiguration load(File file, Logger logger) {
        if (file == null || !file.isFile()) return new YamlConfiguration();
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        if (!looksDamaged(file, config)) return config;
        File backup = new File(file.getParentFile(), file.getName() + ".corrupt-" + System.currentTimeMillis());
        try {
            Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            logger.severe("Damaged YAML detected in " + file.getName() + " — backup saved to "
                    + backup.getName() + ". The file will be treated as empty until fixed.");
        } catch (IOException exception) {
            logger.severe("Damaged YAML detected in " + file.getName()
                    + " and the backup failed: " + exception.getMessage());
        }
        return config;
    }

    /** @return true when the file is non-empty but parses to no data (i.e. corrupted). */
    private static boolean looksDamaged(File file, YamlConfiguration parsed) {
        if (!parsed.getKeys(true).isEmpty()) return false;
        String raw;
        try {
            raw = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return true;
        }
        for (String line : raw.split("\n")) {
            String trimmed = line.trim();
            // "{}" is what an intentionally empty config serialises to, so it is not damage.
            if (trimmed.isEmpty() || trimmed.equals("{}") || trimmed.startsWith("#")) continue;
            if (trimmed.equals("{") || trimmed.equals("}")) continue;
            if (trimmed.startsWith("'") || trimmed.startsWith("\"")) continue;
            return true;
        }
        return false;
    }

    /** Writes the config atomically (temp file + rename). @return false when the write failed. */
    public static boolean save(YamlConfiguration data, File file, Logger logger) {
        if (file == null) return false;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                logger.severe("Cannot create data folder: " + parent.getPath());
                return false;
            }
            Path target = file.toPath();
            Path temp = new File(parent, file.getName() + ".tmp").toPath();
            Files.writeString(temp, data.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException exception) {
            logger.severe("Cannot save " + file.getName() + ": " + exception.getMessage());
            return false;
        }
    }
}