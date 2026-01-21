package me.gonecasino.gf;

public record FishData(
        FishRarity rarity,
        double weightKg,
        int points,
        int value,
        boolean cooked,
        String speciesName
) {
}
