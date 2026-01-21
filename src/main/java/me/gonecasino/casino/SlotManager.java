package me.gonecasino.casino;

import me.gonecasino.GoneCasinoPlugin;
import me.gonecasino.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.Random;

public final class SlotManager {
    private final GoneCasinoPlugin plugin;
    private final Random random = new Random();

    public SlotManager(GoneCasinoPlugin plugin) {
        this.plugin = plugin;
    }

    public void spin(Player player) {
        int bet = 50;
        if (!plugin.bank().isAvailable()) {
            player.sendMessage(Text.bad("Экономика недоступна (Vault)."));
            return;
        }
        if (!plugin.bank().take(bet)) {
            player.sendMessage(Text.bad("Недостаточно фишек. Ставка: " + bet));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 0.8f);
            return;
        }

        int a = random.nextInt(5);
        int b = random.nextInt(5);
        int c = random.nextInt(5);

        int payout = 0;
        if (a == b && b == c) {
            payout = bet * (3 + a);
        } else if (a == b || b == c || a == c) {
            payout = bet * 2;
        }

        if (payout > 0) {
            plugin.bank().give(payout);
            player.showTitle(net.kyori.adventure.title.Title.title(
                    Component.text("SLOTS"),
                    Component.text("Выигрыш: " + payout + " фишек"),
                    net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(200), java.time.Duration.ofMillis(1200), java.time.Duration.ofMillis(200))
            ));
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        } else {
            player.showTitle(net.kyori.adventure.title.Title.title(
                    Component.text("SLOTS"),
                    Component.text("Не повезло..."),
                    net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(200), java.time.Duration.ofMillis(1200), java.time.Duration.ofMillis(200))
            ));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.7f);
        }
    }
}
