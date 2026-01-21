package me.gonecasino.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

public final class Text {
    private Text() {}

    public static Component title(String s) {
        return Component.text(s, NamedTextColor.GOLD)
                .decoration(TextDecoration.BOLD, true);
    }

    public static Component info(String s) {
        return Component.text(s, NamedTextColor.YELLOW);
    }

    public static Component ok(String s) {
        return Component.text(s, NamedTextColor.GREEN);
    }

    public static Component bad(String s) {
        return Component.text(s, NamedTextColor.RED);
    }
}
