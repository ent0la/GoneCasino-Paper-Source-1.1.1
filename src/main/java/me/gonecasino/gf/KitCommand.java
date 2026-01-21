package me.gonecasino.gf;

import me.gonecasino.GoneCasinoPlugin;
import me.gonecasino.util.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class KitCommand implements CommandExecutor, TabCompleter {
    private final GoneCasinoPlugin plugin;

    public KitCommand(GoneCasinoPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(Text.info("/kit start"));
            return true;
        }

        if (!player.hasPermission("gonecasino.admin")) {
            player.sendMessage(Text.bad("Нет прав."));
            return true;
        }

        if (!args[0].equalsIgnoreCase("start")) {
            player.sendMessage(Text.bad("Неизвестная команда."));
            return true;
        }

        int given = plugin.gf().giveStarterRodsToOnlinePlayers();
        int total = org.bukkit.Bukkit.getOnlinePlayers().size();
        int skipped = Math.max(0, total - given);
        player.sendMessage(Text.ok("Выдано удочек: " + given + ". Уже были: " + skipped + "."));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return prefix(args[0], List.of("start"));
        }
        return List.of();
    }

    private static List<String> prefix(String p, List<String> all) {
        String x = p == null ? "" : p.toLowerCase();
        List<String> out = new ArrayList<>();
        for (String s : all) {
            if (s.toLowerCase().startsWith(x)) out.add(s);
        }
        return out;
    }
}
