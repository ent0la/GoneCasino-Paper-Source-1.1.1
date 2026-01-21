package me.gonecasino.gf;

import org.bukkit.Location;
import org.bukkit.util.BoundingBox;

public record HouseInfo(
        Location homeSpawn,
        Location campfire,
        BoundingBox safeZone,
        Location slotMachine,
        Location starterChest
) {
}
