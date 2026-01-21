package me.gonecasino.casino;

import me.gonecasino.GoneCasinoPlugin;
import me.gonecasino.util.LocUtil;
import me.gonecasino.util.Text;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stores casino tables placed on blocks.
 */
public final class TableManager implements Listener {
    private final GoneCasinoPlugin plugin;
    private final File file;
    private YamlConfiguration yml;

    private final Map<String, TableType> tables = new HashMap<>(); // key: serialized block loc

    private final SlotManager slotManager;
    private final PokerManager pokerManager;

    public TableManager(GoneCasinoPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "tables.yml");
        this.slotManager = new SlotManager(plugin);
        this.pokerManager = new PokerManager(plugin);
    }

    public void load() {
        if (!file.getParentFile().exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.getParentFile().mkdirs();
        }
        this.yml = YamlConfiguration.loadConfiguration(file);

        tables.clear();
        var sec = yml.getConfigurationSection("tables");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                String typeStr = sec.getString(key, "");
                try {
                    TableType type = TableType.valueOf(typeStr);
                    tables.put(key, type);
                } catch (IllegalArgumentException ignored) {}
            }
        }
    }

    public void save() {
        if (yml == null) yml = new YamlConfiguration();
        yml.set("tables", null);
        for (var e : tables.entrySet()) {
            yml.set("tables." + e.getKey(), e.getValue().name());
        }
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save tables.yml: " + e.getMessage());
        }
    }

    public boolean setTable(Block block, TableType type) {
        if (block == null || block.getType() == Material.AIR) return false;
        String key = LocUtil.serializeBlock(block.getLocation());
        tables.put(key, type);
        save();
        return true;
    }

    public boolean delTable(Block block) {
        if (block == null) return false;
        String key = LocUtil.serializeBlock(block.getLocation());
        if (!tables.containsKey(key)) return false;
        tables.remove(key);
        save();
        return true;
    }

    public TableType getTableType(Location blockLoc) {
        if (blockLoc == null) return null;
        return tables.get(LocUtil.serializeBlock(blockLoc));
    }

    public List<Location> getTablesOfType(TableType type) {
        List<Location> res = new ArrayList<>();
        if (type == null) return res;
        for (var e : tables.entrySet()) {
            if (e.getValue() != type) continue;
            Location loc = LocUtil.deserializeBlock(e.getKey());
            if (loc != null) res.add(loc);
        }
        return res;
    }

    public PokerManager poker() {
        return pokerManager;
    }

    public SlotManager slots() {
        return slotManager;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null) return;

        TableType type = getTableType(block.getLocation());
        if (type == null) return;

        Player player = event.getPlayer();

        // Avoid interfering with placing blocks on tables
        ItemStack hand = event.getItem();
        if (hand != null && hand.getType().isBlock()) {
            // allow shift-click to interact anyway
            if (!player.isSneaking()) return;
        }

        event.setCancelled(true);

        switch (type) {
            case SLOT -> slotManager.spin(player);
            case POKER -> pokerManager.toggleSeat(player, block.getLocation());
        }
    }

    public void msg(Player p, String s) {
        p.sendMessage(Text.info(s));
    }
}
