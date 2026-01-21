package me.gonecasino.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Simple persistent storage (data.yml).
 */
public final class DataStore {
    private final Plugin plugin;
    private final File file;
    private YamlConfiguration yml;

    private final Map<UUID, Integer> chips = new HashMap<>();

    public DataStore(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    public void load() {
        if (!file.getParentFile().exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.getParentFile().mkdirs();
        }
        this.yml = YamlConfiguration.loadConfiguration(file);

        chips.clear();
        var sec = yml.getConfigurationSection("chips");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    int val = sec.getInt(key, 0);
                    chips.put(uuid, Math.max(0, val));
                } catch (IllegalArgumentException ignored) {}
            }
        }
    }

    public void save() {
        if (yml == null) yml = new YamlConfiguration();

        yml.set("chips", null);
        for (var e : chips.entrySet()) {
            yml.set("chips." + e.getKey(), e.getValue());
        }

        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save data.yml: " + e.getMessage());
        }
    }

    public int getChips(UUID uuid) {
        return chips.getOrDefault(uuid, 0);
    }

    public void setChips(UUID uuid, int amount) {
        chips.put(uuid, Math.max(0, amount));
    }

    public boolean takeChips(UUID uuid, int amount) {
        if (amount <= 0) return true;
        int bal = getChips(uuid);
        if (bal < amount) return false;
        setChips(uuid, bal - amount);
        return true;
    }

    public void addChips(UUID uuid, int amount) {
        if (amount <= 0) return;
        setChips(uuid, getChips(uuid) + amount);
    }
}
