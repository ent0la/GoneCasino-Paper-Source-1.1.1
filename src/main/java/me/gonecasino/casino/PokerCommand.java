package me.gonecasino.casino;

import me.gonecasino.GoneCasinoPlugin;
import me.gonecasino.util.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

public final class PokerCommand implements CommandExecutor, TabCompleter {
    private final GoneCasinoPlugin plugin;

    public PokerCommand(GoneCasinoPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (args.length == 0) {
            player.sendMessage(Text.info("/poker start"));
            return true;
        }
        if (args[0].equalsIgnoreCase("start")) {
            var candidates = plugin.tables().getTablesOfType(TableType.POKER);
            if (candidates.isEmpty()) {
                player.sendMessage(Text.bad("На сервере нет ни одного покерного стола."));
                return true;
            }
            plugin.tables().poker().startNearestTable(player, candidates);
            return true;
        }

        player.sendMessage(Text.bad("Неизвестная команда."));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return List.of("start");
        }
        return List.of();
    }
}
