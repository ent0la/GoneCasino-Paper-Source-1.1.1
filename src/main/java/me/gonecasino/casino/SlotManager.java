package me.gonecasino.casino;

import me.gonecasino.GoneCasinoPlugin;
import me.gonecasino.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Random;

public final class SlotManager {
    private final GoneCasinoPlugin plugin;
    private final Random random = new Random();
    private final List<SlotSymbol> symbols = List.of(
            new SlotSymbol("🍒", "Вишня", 42, 3),
            new SlotSymbol("🍋", "Лимон", 28, 4),
            new SlotSymbol("🔔", "Колокольчик", 15, 6),
            new SlotSymbol("⭐", "Звезда", 10, 9),
            new SlotSymbol("💎", "Алмаз", 4, 14),
            new SlotSymbol("👑", "Корона", 1, 22)
    );

    public SlotManager(GoneCasinoPlugin plugin) {
        this.plugin = plugin;
    }

    public void spin(Player player) {
        int bet = 50;
        if (!plugin.bank().isAvailable()) {
            player.sendMessage(Text.bad("Экономика недоступна (Vault/EssentialsX)."));
            return;
        }
        if (!plugin.bank().take(bet)) {
            player.sendMessage(Text.bad("Недостаточно фишек. Ставка: " + bet));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 0.8f);
            return;
        }

        SlotSymbol a = rollSymbol();
        SlotSymbol b = rollSymbol();
        SlotSymbol c = rollSymbol();

        int payout = 0;
        boolean triple = a == b && b == c;
        boolean pair = !triple && (a == b || b == c || a == c);
        if (triple) {
            payout = bet * a.payoutMult;
        } else if (pair) {
            payout = bet * 2;
        }

        Component line = Component.text(a.icon + " " + b.icon + " " + c.icon, NamedTextColor.GOLD);

        if (payout > 0) {
            plugin.bank().give(payout);
            player.showTitle(net.kyori.adventure.title.Title.title(
                    Component.text("🎰 СЛОТЫ", NamedTextColor.GOLD),
                    Component.text("Выигрыш: " + payout + " фишек", NamedTextColor.GREEN).append(Component.text(" • ", NamedTextColor.DARK_GRAY)).append(line),
                    net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(200), java.time.Duration.ofMillis(1200), java.time.Duration.ofMillis(200))
            ));
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        } else {
            player.showTitle(net.kyori.adventure.title.Title.title(
                    Component.text("🎰 СЛОТЫ", NamedTextColor.GOLD),
                    Component.text("Не повезло... ", NamedTextColor.RED).append(line),
                    net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(200), java.time.Duration.ofMillis(1200), java.time.Duration.ofMillis(200))
            ));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.7f);
        }
    }

    private SlotSymbol rollSymbol() {
        int total = symbols.stream().mapToInt(symbol -> symbol.weight).sum();
        int roll = random.nextInt(total) + 1;
        int acc = 0;
        for (SlotSymbol symbol : symbols) {
            acc += symbol.weight;
            if (roll <= acc) {
                return symbol;
            }
        }
        return symbols.get(0);
    }

    private record SlotSymbol(String icon, String name, int weight, int payoutMult) {}
}
