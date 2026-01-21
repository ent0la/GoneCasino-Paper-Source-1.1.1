package me.gonecasino.util;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/**
 * Namespaced keys used for PersistentDataContainer.
 */
public final class Keys {
    private Keys() {}

    private static Plugin plugin;

    public static NamespacedKey FISH;
    public static NamespacedKey FISH_WEIGHT;
    public static NamespacedKey FISH_POINTS;
    public static NamespacedKey FISH_RARITY;
    public static NamespacedKey FISH_COOKED;
    public static NamespacedKey FISH_VALUE;
    public static NamespacedKey FISH_SPECIES;

    public static NamespacedKey BAIT_TIER;

    public static NamespacedKey ROD_POWER;
    public static NamespacedKey ROD_LUCK;
    public static NamespacedKey STARTER_ROD;

    public static NamespacedKey ITEM_TYPE;
    public static NamespacedKey GF_TRADER;

    public static void init(Plugin p) {
        plugin = p;

        FISH = new NamespacedKey(plugin, "fish");
        FISH_WEIGHT = new NamespacedKey(plugin, "fish_weight");
        FISH_POINTS = new NamespacedKey(plugin, "fish_points");
        FISH_RARITY = new NamespacedKey(plugin, "fish_rarity");
        FISH_COOKED = new NamespacedKey(plugin, "fish_cooked");
        FISH_VALUE = new NamespacedKey(plugin, "fish_value");
        FISH_SPECIES = new NamespacedKey(plugin, "fish_species");

        BAIT_TIER = new NamespacedKey(plugin, "bait_tier");

        ROD_POWER = new NamespacedKey(plugin, "rod_power");
        ROD_LUCK = new NamespacedKey(plugin, "rod_luck");
        STARTER_ROD = new NamespacedKey(plugin, "starter_rod");

        ITEM_TYPE = new NamespacedKey(plugin, "item_type");
        GF_TRADER = new NamespacedKey(plugin, "gf_trader");
    }
}
