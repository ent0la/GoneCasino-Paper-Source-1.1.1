package me.gonecasino.util;

import me.gonecasino.GoneCasinoPlugin;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

public final class ChipBank {
    private final GoneCasinoPlugin plugin;
    private Economy economy;
    private boolean bankSupported;
    private String bankName;
    private OfflinePlayer fallbackAccount;

    public ChipBank(GoneCasinoPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean setup() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            plugin.getLogger().warning("Vault не найден. Экономика отключена.");
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            plugin.getLogger().warning("Vault найден, но экономика не подключена.");
            return false;
        }

        this.economy = rsp.getProvider();
        if (economy != null) {
            String provider = economy.getName();
            if (provider != null && provider.toLowerCase().contains("essentials")) {
                plugin.getLogger().info("Экономика подключена через EssentialsX (Vault).");
            } else {
                plugin.getLogger().info("Экономика подключена через Vault: " + provider);
            }
        }
        this.bankName = plugin.getConfig().getString("economy.bank_name", "gonecasino");
        this.bankSupported = economy.hasBankSupport();
        if (bankSupported) {
            if (!economy.hasBank(bankName)) {
                economy.createBank(bankName, Bukkit.getOfflinePlayer(bankName));
            }
        } else {
            this.fallbackAccount = Bukkit.getOfflinePlayer(bankName);
        }
        return true;
    }

    public boolean isAvailable() {
        return economy != null;
    }

    public double getBalance() {
        if (!isAvailable()) return 0.0;
        if (bankSupported) {
            return economy.bankBalance(bankName).balance;
        }
        return economy.getBalance(fallbackAccount);
    }

    public boolean take(double amount) {
        if (amount <= 0) return true;
        if (!isAvailable()) return false;
        EconomyResponse resp = bankSupported
                ? economy.bankWithdraw(bankName, amount)
                : economy.withdrawPlayer(fallbackAccount, amount);
        return resp.transactionSuccess();
    }

    public void give(double amount) {
        if (amount <= 0) return;
        if (!isAvailable()) return;
        if (bankSupported) {
            economy.bankDeposit(bankName, amount);
        } else {
            economy.depositPlayer(fallbackAccount, amount);
        }
    }

    public double clear() {
        if (!isAvailable()) return 0.0;
        double balance = getBalance();
        if (balance <= 0) return 0.0;
        take(balance);
        return balance;
    }
}
