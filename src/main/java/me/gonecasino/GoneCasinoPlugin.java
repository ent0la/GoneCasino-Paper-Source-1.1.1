package me.gonecasino;

import me.gonecasino.casino.CasinoCommand;
import me.gonecasino.casino.PokerCommand;
import me.gonecasino.casino.TableManager;
import me.gonecasino.gf.GFManager;
import me.gonecasino.util.ChipBank;
import me.gonecasino.util.Keys;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class GoneCasinoPlugin extends JavaPlugin {

    private ChipBank chipBank;
    private TableManager tableManager;
    private GFManager gfManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.chipBank = new ChipBank(this);
        this.chipBank.setup();

        Keys.init(this);

        this.tableManager = new TableManager(this);
        this.tableManager.load();

        this.gfManager = new GFManager(this);
        this.gfManager.load();

        // Commands
        var casinoCmd = new CasinoCommand(this);
        getCommand("casino").setExecutor(casinoCmd);
        getCommand("casino").setTabCompleter(casinoCmd);

        var pokerCmd = new PokerCommand(this);
        getCommand("poker").setExecutor(pokerCmd);
        getCommand("poker").setTabCompleter(pokerCmd);

        // Register listeners
        Bukkit.getPluginManager().registerEvents(tableManager, this);
        Bukkit.getPluginManager().registerEvents(gfManager, this);

        getLogger().info("GoneCasino enabled.");
    }

    @Override
    public void onDisable() {
        try {
            if (gfManager != null) gfManager.save();
        } catch (Exception ignored) {}
        try {
            if (tableManager != null) tableManager.save();
        } catch (Exception ignored) {}
        getLogger().info("GoneCasino disabled.");
    }

    public ChipBank bank() {
        return chipBank;
    }

    public TableManager tables() {
        return tableManager;
    }

    public GFManager gf() {
        return gfManager;
    }
}
