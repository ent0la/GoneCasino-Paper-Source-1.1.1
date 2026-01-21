package me.gonecasino.gf;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class FishCatalog {
    private FishCatalog() {}

    public record FishModel(String name, String id, int modelData, FishRarity rarity) {}
    public record NightFishDefinition(String name, FishRarity rarity, double minWeight, double maxWeight) {}

    public static final List<FishModel> FISH_MODELS = List.of(
            new FishModel("Треска", "treska", 1001, FishRarity.COMMON),
            new FishModel("Карась", "karas", 1002, FishRarity.COMMON),
            new FishModel("Плотва", "plotva", 1003, FishRarity.COMMON),
            new FishModel("Окунь", "okun", 1004, FishRarity.COMMON),
            new FishModel("Пескарь", "peskar", 1005, FishRarity.COMMON),
            new FishModel("Ёрш", "ersh", 1006, FishRarity.COMMON),
            new FishModel("Ротан", "rotan", 1007, FishRarity.COMMON),
            new FishModel("Снеток", "snetok", 1008, FishRarity.COMMON),
            new FishModel("Лосось", "losos", 1009, FishRarity.UNCOMMON),
            new FishModel("Сиг", "sig", 1010, FishRarity.UNCOMMON),
            new FishModel("Речной форель", "rechnoy_forel", 1011, FishRarity.UNCOMMON),
            new FishModel("Щука", "shchuka", 1012, FishRarity.UNCOMMON),
            new FishModel("Голавль", "golavl", 1013, FishRarity.UNCOMMON),
            new FishModel("Жерех", "zheryekh", 1014, FishRarity.UNCOMMON),
            new FishModel("Налим", "nalim", 1015, FishRarity.UNCOMMON),
            new FishModel("Язь", "yaz", 1016, FishRarity.UNCOMMON),
            new FishModel("Глубинная рыба", "glubinnaya_ryba", 1017, FishRarity.RARE),
            new FishModel("Серебряный сом", "serebryany_som", 1018, FishRarity.RARE),
            new FishModel("Голубой линь", "goluboy_lin", 1019, FishRarity.RARE),
            new FishModel("Золотой карась", "zolotoy_karas", 1020, FishRarity.RARE),
            new FishModel("Речной угорь", "rechnoy_ugor", 1021, FishRarity.RARE),
            new FishModel("Мраморный осётр", "mramornyy_osetr", 1022, FishRarity.RARE),
            new FishModel("Полосатый таймень", "polosatyy_taymen", 1023, FishRarity.RARE),
            new FishModel("Тёмный карп", "temnyy_karp", 1024, FishRarity.EPIC),
            new FishModel("Лунный осётр", "lunnyy_osetr", 1025, FishRarity.EPIC),
            new FishModel("Сонный угорь", "sonnyy_ugor", 1026, FishRarity.EPIC),
            new FishModel("Изумрудный окунь", "izumrudnyy_okun", 1027, FishRarity.EPIC),
            new FishModel("Кристальный сом", "kristalnyy_som", 1028, FishRarity.EPIC),
            new FishModel("Сумеречный лосось", "sumerechnyy_losos", 1029, FishRarity.EPIC),
            new FishModel("Королевская щука", "korolevskaya_shchuka", 1030, FishRarity.EPIC),
            new FishModel("Рыба-кошмар", "ryba_koshmar", 1031, FishRarity.LEGENDARY),
            new FishModel("Клинок-рыба", "klinok_ryba", 1032, FishRarity.LEGENDARY),
            new FishModel("Звёздный скат", "zvezdnyy_skat", 1033, FishRarity.LEGENDARY),
            new FishModel("Сияющая акула", "siyayushchaya_akula", 1034, FishRarity.LEGENDARY),
            new FishModel("Имперский марлин", "imperskiy_marlin", 1035, FishRarity.LEGENDARY),
            new FishModel("Призрачная мурена", "prizrachnaya_murena", 1036, FishRarity.LEGENDARY),
            new FishModel("Громовой тунец", "gromovoy_tunets", 1037, FishRarity.LEGENDARY),
            new FishModel("Лунная щука", "lunnaya_shchuka", 1038, FishRarity.RARE),
            new FishModel("Тень-угорь", "ten_ugor", 1039, FishRarity.EPIC),
            new FishModel("Бездна-катран", "bezdna_katran", 1040, FishRarity.LEGENDARY),
            new FishModel("Ледяной скат", "ledyanoy_skat", 1041, FishRarity.EPIC),
            new FishModel("Кровавый марлин", "krovavyy_marlin", 1042, FishRarity.LEGENDARY)
    );

    private static final Map<String, FishModel> BY_NAME;
    private static final Map<FishRarity, List<String>> SPECIES_BY_RARITY;

    static {
        Map<String, FishModel> byName = new java.util.HashMap<>();
        for (FishModel model : FISH_MODELS) {
            byName.put(model.name(), model);
        }
        BY_NAME = Map.copyOf(byName);

        Map<FishRarity, List<String>> species = new EnumMap<>(FishRarity.class);
        species.put(FishRarity.COMMON, List.of(
                "Треска",
                "Карась",
                "Плотва",
                "Окунь",
                "Пескарь",
                "Ёрш",
                "Ротан",
                "Снеток"
        ));
        species.put(FishRarity.UNCOMMON, List.of(
                "Лосось",
                "Сиг",
                "Речной форель",
                "Щука",
                "Голавль",
                "Жерех",
                "Налим",
                "Язь"
        ));
        species.put(FishRarity.RARE, List.of(
                "Глубинная рыба",
                "Серебряный сом",
                "Голубой линь",
                "Золотой карась",
                "Речной угорь",
                "Мраморный осётр",
                "Полосатый таймень"
        ));
        species.put(FishRarity.EPIC, List.of(
                "Тёмный карп",
                "Лунный осётр",
                "Сонный угорь",
                "Изумрудный окунь",
                "Кристальный сом",
                "Сумеречный лосось",
                "Королевская щука"
        ));
        species.put(FishRarity.LEGENDARY, List.of(
                "Рыба-кошмар",
                "Клинок-рыба",
                "Звёздный скат",
                "Сияющая акула",
                "Имперский марлин",
                "Призрачная мурена",
                "Громовой тунец"
        ));
        SPECIES_BY_RARITY = Map.copyOf(species);
    }

    public static List<String> getSpeciesForRarity(FishRarity rarity) {
        return SPECIES_BY_RARITY.getOrDefault(rarity, List.of("Рыба"));
    }

    public static Integer getModelData(String species) {
        FishModel model = BY_NAME.get(species);
        return model == null ? null : model.modelData();
    }

    public static List<NightFishDefinition> getNightFish() {
        return List.of(
                new NightFishDefinition("Лунная щука", FishRarity.RARE, 3.0, 6.5),
                new NightFishDefinition("Тень-угорь", FishRarity.EPIC, 6.5, 12.0),
                new NightFishDefinition("Бездна-катран", FishRarity.LEGENDARY, 11.0, 19.0),
                new NightFishDefinition("Ледяной скат", FishRarity.EPIC, 5.5, 10.5),
                new NightFishDefinition("Кровавый марлин", FishRarity.LEGENDARY, 13.0, 21.0)
        );
    }
}
