package me.gonecasino.casino;

import me.gonecasino.GoneCasinoPlugin;
import me.gonecasino.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class CasinoCommand implements CommandExecutor, TabCompleter {
    private final GoneCasinoPlugin plugin;

    public CasinoCommand(GoneCasinoPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(Text.info("/casino balance | /casino pay <ник> <сумма>"));
            player.sendMessage(Text.info("/casino gf join|leave|start|stop|status"));
            if (player.hasPermission("gonecasino.admin")) {
                player.sendMessage(Text.info("/casino setaltar"));
                player.sendMessage(Text.info("/casino settable <POKER|SLOT>"));
                player.sendMessage(Text.info("/casino deltable"));
            }
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "balance" -> {
                int bal = plugin.data().getChips(player.getUniqueId());
                player.sendMessage(Text.ok("Ваш баланс: " + bal + " фишек"));
                return true;
            }
            case "pay" -> {
                if (args.length < 3) {
                    player.sendMessage(Text.bad("Использование: /casino pay <ник> <сумма>"));
                    return true;
                }
                var target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    player.sendMessage(Text.bad("Игрок не найден."));
                    return true;
                }
                int amount;
                try {
                    amount = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    player.sendMessage(Text.bad("Сумма должна быть числом."));
                    return true;
                }
                if (amount <= 0) {
                    player.sendMessage(Text.bad("Сумма должна быть > 0."));
                    return true;
                }
                if (!plugin.data().takeChips(player.getUniqueId(), amount)) {
                    player.sendMessage(Text.bad("Недостаточно фишек."));
                    return true;
                }
                plugin.data().addChips(target.getUniqueId(), amount);
                player.sendMessage(Text.ok("Вы отправили " + amount + " фишек игроку " + target.getName()));
                target.sendMessage(Text.ok("Вы получили " + amount + " фишек от " + player.getName()));
                return true;
            }
            case "setaltar" -> {
                if (!player.hasPermission("gonecasino.admin")) {
                    player.sendMessage(Text.bad("Нет прав."));
                    return true;
                }
                Block b = player.getTargetBlockExact(6);
                if (b == null) {
                    player.sendMessage(Text.bad("Посмотрите на блок (до 6 блоков)."));
                    return true;
                }
                plugin.gf().setAltar(b.getLocation());
                player.sendMessage(Text.ok("Алтарь установлен: " + b.getX() + " " + b.getY() + " " + b.getZ()));
                return true;
            }
            case "settable" -> {
                if (!player.hasPermission("gonecasino.admin")) {
                    player.sendMessage(Text.bad("Нет прав."));
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage(Text.bad("Использование: /casino settable <POKER|SLOT>"));
                    return true;
                }
                TableType type;
                try {
                    type = TableType.valueOf(args[1].toUpperCase());
                } catch (IllegalArgumentException e) {
                    player.sendMessage(Text.bad("Тип должен быть POKER или SLOT"));
                    return true;
                }
                Block b = player.getTargetBlockExact(6);
                if (b == null) {
                    player.sendMessage(Text.bad("Посмотрите на блок (до 6 блоков)."));
                    return true;
                }
                if (!plugin.tables().setTable(b, type)) {
                    player.sendMessage(Text.bad("Не удалось установить стол."));
                    return true;
                }
                player.sendMessage(Text.ok("Стол " + type + " установлен."));
                return true;
            }
            case "deltable" -> {
                if (!player.hasPermission("gonecasino.admin")) {
                    player.sendMessage(Text.bad("Нет прав."));
                    return true;
                }
                Block b = player.getTargetBlockExact(6);
                if (b == null) {
                    player.sendMessage(Text.bad("Посмотрите на блок (до 6 блоков)."));
                    return true;
                }
                if (plugin.tables().delTable(b)) {
                    player.sendMessage(Text.ok("Стол удалён."));
                } else {
                    player.sendMessage(Text.bad("На этом блоке нет стола."));
                }
                return true;
            }
            case "gf" -> {
                if (args.length < 2) {
                    player.sendMessage(Text.info("/casino gf join|leave|start|stop|status"));
                    return true;
                }
                String a = args[1].toLowerCase();
                switch (a) {
                    case "join" -> plugin.gf().join(player);
                    case "leave" -> plugin.gf().leave(player);
                    case "start" -> {
                        if (!player.hasPermission("gonecasino.admin")) {
                            player.sendMessage(Text.bad("Нет прав."));
                            return true;
                        }
                        plugin.gf().start(player);
                    }
                    case "stop" -> {
                        if (!player.hasPermission("gonecasino.admin")) {
                            player.sendMessage(Text.bad("Нет прав."));
                            return true;
                        }
                        plugin.gf().stop(player);
                    }
                    case "status" -> plugin.gf().status(player);
                    default -> player.sendMessage(Text.bad("Неизвестно: " + a));
                }
                return true;
            }
        }

        player.sendMessage(Text.bad("Неизвестная команда."));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return prefix(args[0], Arrays.asList("balance", "pay", "gf", "setaltar", "settable", "deltable"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("gf")) {
            return prefix(args[1], Arrays.asList("join", "leave", "start", "stop", "status"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("settable")) {
            return prefix(args[1], Arrays.asList("POKER", "SLOT"));
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
