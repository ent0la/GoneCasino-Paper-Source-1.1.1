package me.gonecasino.gf;

import net.kyori.adventure.text.format.NamedTextColor;

public enum FishQuality {
    NORMAL("Обычное", NamedTextColor.WHITE, 1.0),
    SILVER("Серебро", NamedTextColor.GRAY, 1.25),
    GOLD("Золото", NamedTextColor.GOLD, 1.5),
    IRIDIUM("Иридий", NamedTextColor.LIGHT_PURPLE, 2.0);

    public final String ruName;
    public final NamedTextColor color;
    public final double valueMult;

    FishQuality(String ruName, NamedTextColor color, double valueMult) {
        this.ruName = ruName;
        this.color = color;
        this.valueMult = valueMult;
    }
}
