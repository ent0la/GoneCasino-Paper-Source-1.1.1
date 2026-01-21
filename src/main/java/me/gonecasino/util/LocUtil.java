package me.gonecasino.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;

public final class LocUtil {
    private LocUtil() {}

    public static String serializeBlock(Location loc) {
        if (loc == null || loc.getWorld() == null) return "";
        return loc.getWorld().getName()
                + ";" + loc.getBlockX()
                + ";" + loc.getBlockY()
                + ";" + loc.getBlockZ();
    }

    public static Location deserializeBlock(String s) {
        if (s == null || s.isBlank()) return null;
        var parts = s.split(";");
        if (parts.length != 4) return null;
        var world = Bukkit.getWorld(parts[0]);
        if (world == null) return null;
        try {
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            return new Location(world, x, y, z);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
