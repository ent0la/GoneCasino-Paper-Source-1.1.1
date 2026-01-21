package me.gonecasino.gf;

import net.kyori.adventure.text.format.NamedTextColor;

public enum FishRarity {
    COMMON("Обычная", NamedTextColor.WHITE, 1.0),
    UNCOMMON("Необычная", NamedTextColor.GREEN, 1.15),
    RARE("Редкая", NamedTextColor.AQUA, 1.35),
    EPIC("Эпическая", NamedTextColor.LIGHT_PURPLE, 1.65),
    LEGENDARY("Легендарная", NamedTextColor.GOLD, 2.2);

    public final String ruName;
    public final NamedTextColor color;
    public final double valueMult;

    FishRarity(String ruName, NamedTextColor color, double valueMult) {
        this.ruName = ruName;
        this.color = color;
        this.valueMult = valueMult;
    }
}
