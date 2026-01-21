package me.gonecasino.gf;

import me.gonecasino.util.Keys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GFItems {
    private GFItems() {}

    private static final DecimalFormat DF = new DecimalFormat("0.00");
    private static final int RAW_FISH_MODEL_BASE = 1000;
    private static final int COOKED_FISH_MODEL_BASE = 2000;

    public static final String TYPE_BAIT = "bait";
    public static final String TYPE_ROD_POWER = "rod_power";
    public static final String TYPE_ROD_LUCK = "rod_luck";
    public static final String TYPE_QUOTA_REDUCER = "quota_reducer";
    public static final String TYPE_CAMPFIRE_RECALL = "campfire_recall";
    public static final String TYPE_AMULET_SILENCE = "amulet_silence";
    public static final String TYPE_WINDOW_BOOST = "window_boost";
    public static final String TYPE_CATCH_BONUS = "catch_bonus";
    public static final String TYPE_PULL_COOLDOWN = "pull_cooldown";
    public static final String TYPE_PULL_REDUCTION = "pull_reduction";
    public static final String TYPE_STARTER_ROD = "starter_rod";

    private static final List<String> COMMON_SPECIES = List.of(
            "Треска",
            "Карась",
            "Плотва",
            "Окунь",
            "Краснопёрка",
            "Ёрш",
            "Горчак",
            "Уклейка",
            "Подлещик",
            "Сопа"
    );
    private static final List<String> UNCOMMON_SPECIES = List.of(
            "Лосось",
            "Сиг",
            "Речной форель",
            "Щука",
            "Голавль",
            "Язь",
            "Хариус",
            "Судак",
            "Лещ",
            "Налим"
    );
    private static final List<String> RARE_SPECIES = List.of(
            "Глубинная рыба",
            "Серебряный сом",
            "Голубой линь",
            "Чернопёрый окунь",
            "Песчаный карп",
            "Болотный осётр",
            "Золотой карась",
            "Синий сом"
    );
    private static final List<String> EPIC_SPECIES = List.of(
            "Тёмный карп",
            "Лунный осётр",
            "Сонный угорь",
            "Призрачный лосось",
            "Северная щука-гигант",
            "Коралловый сом",
            "Грозовой угорь",
            "Алый марлин"
    );
    private static final List<String> LEGENDARY_SPECIES = List.of(
            "Рыба-кошмар",
            "Клинок-рыба",
            "Звёздный скат",
            "Хрустальный осётр",
            "Морозная щука",
            "Песнь бездны",
            "Сердце океана",
            "Владыка глубин"
    );
    private static final List<NightFishSpec> NIGHT_FISH_POOL = List.of(
            new NightFishSpec("Лунная щука", FishRarity.RARE, 3.0, 6.5),
            new NightFishSpec("Тень-угорь", FishRarity.EPIC, 6.5, 12.0),
            new NightFishSpec("Бездна-катран", FishRarity.LEGENDARY, 11.0, 19.0),
            new NightFishSpec("Шепчущий сом", FishRarity.RARE, 2.5, 6.0),
            new NightFishSpec("Глаз бездны", FishRarity.LEGENDARY, 12.0, 20.0)
    );

    private static final Map<String, Integer> FISH_MODEL_INDEX = buildFishModelIndex();

    public static ItemStack createBait(int tier, int amount) {
        Material mat = switch (tier) {
            case 1 -> Material.WHEAT_SEEDS;
            case 2 -> Material.GLOW_BERRIES;
            default -> Material.HEART_OF_THE_SEA;
        };
        String name = switch (tier) {
            case 1 -> "Приманка: Простая";
            case 2 -> "Приманка: Хорошая";
            default -> "Приманка: Легендарная";
        };

        ItemStack it = new ItemStack(mat, Math.max(1, amount));
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Держи в ЛЕВОЙ руке.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Тир: " + tier, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(Keys.ITEM_TYPE, PersistentDataType.STRING, TYPE_BAIT);
        pdc.set(Keys.BAIT_TIER, PersistentDataType.INTEGER, tier);
        it.setItemMeta(meta);
        return it;
    }

    public static ItemStack createStarterRod() {
        ItemStack it = new ItemStack(Material.FISHING_ROD, 1);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text("Стартовая удочка", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Командные улучшения отображаются", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("после покупки у торговца.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(Keys.ITEM_TYPE, PersistentDataType.STRING, TYPE_STARTER_ROD);
        meta.getPersistentDataContainer().set(Keys.STARTER_ROD, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    public static int getBaitTier(ItemStack offhand) {
        if (offhand == null) return 0;
        ItemMeta meta = offhand.getItemMeta();
        if (meta == null) return 0;
        var pdc = meta.getPersistentDataContainer();
        String type = pdc.get(Keys.ITEM_TYPE, PersistentDataType.STRING);
        if (!TYPE_BAIT.equals(type)) return 0;
        Integer t = pdc.get(Keys.BAIT_TIER, PersistentDataType.INTEGER);
        return t == null ? 0 : t;
    }

    public static ItemStack createRodUpgradePower() {
        ItemStack it = new ItemStack(Material.ENCHANTED_BOOK, 1);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text("Улучшение удочек: Сила", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("ПКМ: общее усиление для команды", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Эффект: легче вытаскивать тяжёлую рыбу", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        meta.addEnchant(Enchantment.LURE, 1, true);
        meta.getPersistentDataContainer().set(Keys.ITEM_TYPE, PersistentDataType.STRING, TYPE_ROD_POWER);
        it.setItemMeta(meta);
        return it;
    }

    public static ItemStack createRodUpgradeLuck() {
        ItemStack it = new ItemStack(Material.ENCHANTED_BOOK, 1);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text("Улучшение удочек: Удача", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("ПКМ: общее усиление для команды", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Эффект: чаще попадается редкая рыба", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        meta.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
        meta.getPersistentDataContainer().set(Keys.ITEM_TYPE, PersistentDataType.STRING, TYPE_ROD_LUCK);
        it.setItemMeta(meta);
        return it;
    }

    public static ItemStack createQuotaReducer(int amount) {
        ItemStack it = new ItemStack(Material.PAPER, 1);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text("Купон: Снижение квоты", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("ПКМ в руке: уменьшает квоту", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("следующего дня на " + amount, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(Keys.ITEM_TYPE, PersistentDataType.STRING, TYPE_QUOTA_REDUCER);
        it.setItemMeta(meta);
        return it;
    }

    public static ItemStack createFishingWindowBoost(int bonusMs) {
        ItemStack it = new ItemStack(Material.CLOCK, 1);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text("Ритуал: Спокойная вода", NamedTextColor.BLUE).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("ПКМ: увеличивает окно вываживания", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("на +" + bonusMs + " мс для всех", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(Keys.ITEM_TYPE, PersistentDataType.STRING, TYPE_WINDOW_BOOST);
        it.setItemMeta(meta);
        return it;
    }

    public static ItemStack createCatchBonus(int percent) {
        ItemStack it = new ItemStack(Material.PRISMARINE_SHARD, 1);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text("Ритуал: Богатый улов", NamedTextColor.DARK_AQUA).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("ПКМ: больше очков и цены", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("+" + percent + "% для всей команды", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(Keys.ITEM_TYPE, PersistentDataType.STRING, TYPE_CATCH_BONUS);
        it.setItemMeta(meta);
        return it;
    }

    public static ItemStack createPullCooldownBoost(int bonusMs) {
        ItemStack it = new ItemStack(Material.RABBIT_FOOT, 1);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text("Ритуал: Быстрый рывок", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("ПКМ: уменьшает задержку рывков", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("на " + bonusMs + " мс для всех", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(Keys.ITEM_TYPE, PersistentDataType.STRING, TYPE_PULL_COOLDOWN);
        it.setItemMeta(meta);
        return it;
    }

    public static ItemStack createPullReductionBoost(int amount) {
        ItemStack it = new ItemStack(Material.STRING, 1);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text("Ритуал: Крепкая леска", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("ПКМ: снижает сложность вываживания", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("на " + amount + " рывка для команды", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(Keys.ITEM_TYPE, PersistentDataType.STRING, TYPE_PULL_REDUCTION);
        it.setItemMeta(meta);
        return it;
    }

    public static ItemStack createCampfireRecall() {
        ItemStack it = new ItemStack(Material.ENDER_PEARL, 1);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text("Зов костра", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("ПКМ: телепорт в безопасный дом", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(Keys.ITEM_TYPE, PersistentDataType.STRING, TYPE_CAMPFIRE_RECALL);
        it.setItemMeta(meta);
        return it;
    }

    public static ItemStack createAmuletSilence() {
        ItemStack it = new ItemStack(Material.AMETHYST_SHARD, 1);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text("Амулет тишины", NamedTextColor.DARK_AQUA).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("ПКМ: краткая скрытность ночью", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(Keys.ITEM_TYPE, PersistentDataType.STRING, TYPE_AMULET_SILENCE);
        it.setItemMeta(meta);
        return it;
    }

    public static boolean isCustomFish(ItemStack it) {
        if (it == null) return false;
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return false;
        Byte b = meta.getPersistentDataContainer().get(Keys.FISH, PersistentDataType.BYTE);
        return b != null && b == (byte) 1;
    }

    public static FishData readFish(ItemStack it) {
        if (!isCustomFish(it)) return null;
        ItemMeta meta = it.getItemMeta();
        var pdc = meta.getPersistentDataContainer();
        Double w = pdc.get(Keys.FISH_WEIGHT, PersistentDataType.DOUBLE);
        Integer pts = pdc.get(Keys.FISH_POINTS, PersistentDataType.INTEGER);
        Integer rar = pdc.get(Keys.FISH_RARITY, PersistentDataType.INTEGER);
        Integer val = pdc.get(Keys.FISH_VALUE, PersistentDataType.INTEGER);
        Integer qual = pdc.get(Keys.FISH_QUALITY, PersistentDataType.INTEGER);
        Byte cooked = pdc.get(Keys.FISH_COOKED, PersistentDataType.BYTE);
        String name = pdc.get(Keys.FISH_SPECIES, PersistentDataType.STRING);
        if (name == null || name.isBlank()) {
            name = "Рыба";
        }

        FishRarity r = FishRarity.COMMON;
        if (rar != null) {
            int idx = Math.max(0, Math.min(FishRarity.values().length - 1, rar));
            r = FishRarity.values()[idx];
        }

        FishQuality q = FishQuality.NORMAL;
        if (qual != null) {
            int idx = Math.max(0, Math.min(FishQuality.values().length - 1, qual));
            q = FishQuality.values()[idx];
        }

        return new FishData(r, q, w == null ? 0 : w, pts == null ? 0 : pts, val == null ? 0 : val, cooked != null && cooked == (byte) 1, name);
    }

    public static ItemStack createFishItem(FishData fish) {
        Material mat = Material.COD;
        if (fish.cooked()) mat = Material.COOKED_COD;

        ItemStack it = new ItemStack(mat, 1);
        ItemMeta meta = it.getItemMeta();

        String cookedPrefix = fish.cooked() ? "Жареная " : "";
        Component display = Component.text(cookedPrefix + fish.speciesName(), fish.rarity().color)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, fish.rarity() == FishRarity.LEGENDARY);
        meta.displayName(display);

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Редкость: " + fish.rarity().ruName, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Качество: " + fish.quality().ruName, fish.quality().color).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Вес: " + DF.format(fish.weightKg()) + " кг", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Очки: " + fish.points(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Цена: " + fish.value() + " фишек", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        Integer modelData = getFishModelData(fish.speciesName(), fish.cooked());
        if (modelData != null) {
            meta.setCustomModelData(modelData);
        }

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(Keys.FISH, PersistentDataType.BYTE, (byte) 1);
        pdc.set(Keys.FISH_WEIGHT, PersistentDataType.DOUBLE, fish.weightKg());
        pdc.set(Keys.FISH_POINTS, PersistentDataType.INTEGER, fish.points());
        pdc.set(Keys.FISH_RARITY, PersistentDataType.INTEGER, fish.rarity().ordinal());
        pdc.set(Keys.FISH_QUALITY, PersistentDataType.INTEGER, fish.quality().ordinal());
        pdc.set(Keys.FISH_VALUE, PersistentDataType.INTEGER, fish.value());
        pdc.set(Keys.FISH_SPECIES, PersistentDataType.STRING, fish.speciesName());
        pdc.set(Keys.FISH_COOKED, PersistentDataType.BYTE, (byte) (fish.cooked() ? 1 : 0));

        it.setItemMeta(meta);
        return it;
    }

    public static List<String> getSpeciesPool(FishRarity rarity) {
        return switch (rarity) {
            case COMMON -> COMMON_SPECIES;
            case UNCOMMON -> UNCOMMON_SPECIES;
            case RARE -> RARE_SPECIES;
            case EPIC -> EPIC_SPECIES;
            case LEGENDARY -> LEGENDARY_SPECIES;
        };
    }

    public static List<NightFishSpec> getNightFishPool() {
        return NIGHT_FISH_POOL;
    }

    public static boolean hasType(ItemStack it, String type) {
        if (it == null) return false;
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return false;
        String t = meta.getPersistentDataContainer().get(Keys.ITEM_TYPE, PersistentDataType.STRING);
        return type.equals(t);
    }

    public static String getType(ItemStack it) {
        if (it == null) return null;
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(Keys.ITEM_TYPE, PersistentDataType.STRING);
    }

    public static boolean isStarterRod(ItemStack rod) {
        if (rod == null || rod.getType() != Material.FISHING_ROD) return false;
        ItemMeta meta = rod.getItemMeta();
        if (meta == null) return false;
        Byte v = meta.getPersistentDataContainer().get(Keys.STARTER_ROD, PersistentDataType.BYTE);
        return v != null && v == (byte) 1;
    }

    public static void updateStarterRod(ItemStack rod, int sharedPower, int sharedLuck) {
        if (!isStarterRod(rod)) return;
        ItemMeta meta = rod.getItemMeta();
        if (meta == null) return;

        meta.displayName(Component.text("Стартовая удочка", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Командные улучшения:", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Сила: +" + sharedPower, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Удача: +" + sharedLuck, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        if (sharedPower == 0 && sharedLuck == 0) {
            lore.add(Component.text("Пока без улучшений.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);

        meta.getEnchants().keySet().forEach(meta::removeEnchant);
        if (sharedPower > 0) {
            meta.addEnchant(Enchantment.LURE, Math.min(3, sharedPower), true);
        }
        if (sharedLuck > 0) {
            meta.addEnchant(Enchantment.LUCK_OF_THE_SEA, Math.min(3, sharedLuck), true);
        }
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        rod.setItemMeta(meta);
    }

    public static int getRodPower(ItemStack rod) {
        if (rod == null || rod.getType() != Material.FISHING_ROD) return 0;
        ItemMeta meta = rod.getItemMeta();
        if (meta == null) return 0;
        Integer v = meta.getPersistentDataContainer().get(Keys.ROD_POWER, PersistentDataType.INTEGER);
        return v == null ? 0 : v;
    }

    public static int getRodLuck(ItemStack rod) {
        if (rod == null || rod.getType() != Material.FISHING_ROD) return 0;
        ItemMeta meta = rod.getItemMeta();
        if (meta == null) return 0;
        Integer v = meta.getPersistentDataContainer().get(Keys.ROD_LUCK, PersistentDataType.INTEGER);
        return v == null ? 0 : v;
    }

    public static boolean applyUpgradeToRod(ItemStack upgrade, ItemStack rod) {
        if (upgrade == null || rod == null) return false;
        if (rod.getType() != Material.FISHING_ROD) return false;
        String type = getType(upgrade);
        if (type == null) return false;

        ItemMeta rmeta = rod.getItemMeta();
        if (rmeta == null) return false;
        PersistentDataContainer pdc = rmeta.getPersistentDataContainer();

        if (TYPE_ROD_POWER.equals(type)) {
            int cur = getRodPower(rod);
            pdc.set(Keys.ROD_POWER, PersistentDataType.INTEGER, cur + 1);
            rod.setItemMeta(rmeta);
            return true;
        }
        if (TYPE_ROD_LUCK.equals(type)) {
            int cur = getRodLuck(rod);
            pdc.set(Keys.ROD_LUCK, PersistentDataType.INTEGER, cur + 1);
            rod.setItemMeta(rmeta);
            return true;
        }
        return false;
    }

    private static Integer getFishModelData(String speciesName, boolean cooked) {
        if (speciesName == null || speciesName.isBlank()) return null;
        Integer index = FISH_MODEL_INDEX.get(speciesName);
        if (index == null) return null;
        return (cooked ? COOKED_FISH_MODEL_BASE : RAW_FISH_MODEL_BASE) + index;
    }

    private static Map<String, Integer> buildFishModelIndex() {
        Map<String, Integer> map = new LinkedHashMap<>();
        int idx = 1;
        for (String name : COMMON_SPECIES) {
            map.put(name, idx++);
        }
        for (String name : UNCOMMON_SPECIES) {
            map.put(name, idx++);
        }
        for (String name : RARE_SPECIES) {
            map.put(name, idx++);
        }
        for (String name : EPIC_SPECIES) {
            map.put(name, idx++);
        }
        for (String name : LEGENDARY_SPECIES) {
            map.put(name, idx++);
        }
        for (NightFishSpec spec : NIGHT_FISH_POOL) {
            if (!map.containsKey(spec.name())) {
                map.put(spec.name(), idx++);
            }
        }
        return map;
    }

    public record NightFishSpec(String name, FishRarity rarity, double minWeight, double maxWeight) {}
}
