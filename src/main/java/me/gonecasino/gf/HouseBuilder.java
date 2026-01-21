package me.gonecasino.gf;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Door;
import org.bukkit.util.BoundingBox;

/**
 * Builds a simple safe house near the altar.
 */
public final class HouseBuilder {
    private HouseBuilder() {}

    public static HouseInfo computeInfo(World world, Location altarBlock, int offsetX, int offsetZ) {
        if (world == null || altarBlock == null) return null;
        int baseX = altarBlock.getBlockX() + offsetX;
        int baseY = altarBlock.getBlockY();
        int baseZ = altarBlock.getBlockZ() + offsetZ;

        int w = 9;
        int l = 7;
        int h = 4;

        int cx = baseX + 4;
        int cz = baseZ + 3;

        Location home = new Location(world, baseX + 4.5, baseY + 1.0, baseZ + 3.5);
        Location campfireLoc = new Location(world, cx + 0.5, baseY + 1.0, cz + 0.5);
        BoundingBox box = new BoundingBox(baseX, baseY, baseZ, baseX + w, baseY + h + 3, baseZ + l);
        Location slotLoc = new Location(world, baseX + 7, baseY + 1, baseZ + 3);
        Location chestLoc = new Location(world, baseX + 2, baseY + 1, baseZ + 5);
        return new HouseInfo(home, campfireLoc, box, slotLoc, chestLoc);
    }

    public static HouseInfo build(World world, Location altarBlock, int offsetX, int offsetZ) {
        HouseInfo info = computeInfo(world, altarBlock, offsetX, offsetZ);
        if (info == null) return null;

        int baseX = altarBlock.getBlockX() + offsetX;
        int baseY = altarBlock.getBlockY();
        int baseZ = altarBlock.getBlockZ() + offsetZ;

        int w = 9;
        int l = 7;
        int h = 4;

        // Floor + clear space
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < l; z++) {
                set(world, baseX + x, baseY, baseZ + z, Material.SPRUCE_PLANKS);
                for (int y = 1; y <= h + 2; y++) {
                    set(world, baseX + x, baseY + y, baseZ + z, Material.AIR);
                }
            }
        }

        // Walls
        for (int x = 0; x < w; x++) {
            for (int y = 1; y <= h; y++) {
                set(world, baseX + x, baseY + y, baseZ, Material.SPRUCE_LOG);
                set(world, baseX + x, baseY + y, baseZ + (l - 1), Material.SPRUCE_LOG);
            }
        }
        for (int z = 0; z < l; z++) {
            for (int y = 1; y <= h; y++) {
                set(world, baseX, baseY + y, baseZ + z, Material.SPRUCE_LOG);
                set(world, baseX + (w - 1), baseY + y, baseZ + z, Material.SPRUCE_LOG);
            }
        }

        // Door opening (front wall)
        set(world, baseX + 4, baseY + 1, baseZ, Material.AIR);
        set(world, baseX + 4, baseY + 2, baseZ, Material.AIR);

        Block doorBottom = world.getBlockAt(baseX + 4, baseY + 1, baseZ);
        doorBottom.setType(Material.SPRUCE_DOOR, false);
        var doorData = (Door) doorBottom.getBlockData();
        doorData.setFacing(org.bukkit.block.BlockFace.SOUTH);
        doorData.setHalf(Bisected.Half.BOTTOM);
        doorBottom.setBlockData(doorData, false);

        Block doorTop = world.getBlockAt(baseX + 4, baseY + 2, baseZ);
        doorTop.setType(Material.SPRUCE_DOOR, false);
        var doorTopData = (Door) doorTop.getBlockData();
        doorTopData.setFacing(org.bukkit.block.BlockFace.SOUTH);
        doorTopData.setHalf(Bisected.Half.TOP);
        doorTop.setBlockData(doorTopData, false);

        // Roof (full block roof)
        for (int x = -1; x <= w; x++) {
            for (int z = -1; z <= l; z++) {
                Block roof = world.getBlockAt(baseX + x, baseY + h + 1, baseZ + z);
                roof.setType(Material.DARK_OAK_PLANKS, false);
            }
        }

        // Windows
        set(world, baseX + 2, baseY + 2, baseZ, Material.GLASS);
        set(world, baseX + 6, baseY + 2, baseZ, Material.GLASS);
        set(world, baseX + 2, baseY + 2, baseZ + (l - 1), Material.GLASS);
        set(world, baseX + 6, baseY + 2, baseZ + (l - 1), Material.GLASS);
        set(world, baseX, baseY + 2, baseZ + 2, Material.GLASS);
        set(world, baseX, baseY + 2, baseZ + 4, Material.GLASS);
        set(world, baseX + (w - 1), baseY + 2, baseZ + 2, Material.GLASS);
        set(world, baseX + (w - 1), baseY + 2, baseZ + 4, Material.GLASS);

        // Beds (4) - corners
        placeBed(world, baseX + 1, baseY + 1, baseZ + 2, org.bukkit.block.BlockFace.SOUTH);
        placeBed(world, baseX + 7, baseY + 1, baseZ + 2, org.bukkit.block.BlockFace.SOUTH);
        placeBed(world, baseX + 1, baseY + 1, baseZ + 4, org.bukkit.block.BlockFace.NORTH);
        placeBed(world, baseX + 7, baseY + 1, baseZ + 4, org.bukkit.block.BlockFace.NORTH);

        // Campfire
        int cx = baseX + 4;
        int cz = baseZ + 3;
        set(world, cx, baseY + 1, cz, Material.CAMPFIRE);

        // Slot machine
        set(world, baseX + 7, baseY + 1, baseZ + 3, Material.DISPENSER);
        set(world, baseX + 7, baseY + 2, baseZ + 3, Material.REDSTONE_LAMP);

        // Starter chest with rods + bait
        Block chestBlock = world.getBlockAt(baseX + 2, baseY + 1, baseZ + 5);
        chestBlock.setType(Material.CHEST, true);
        if (chestBlock.getState() instanceof Chest chest) {
            chest.getInventory().clear();
            for (int i = 0; i < 4; i++) {
                chest.getInventory().addItem(GFItems.createStarterRod());
            }
            chest.getInventory().addItem(GFItems.createBait(1, 8));
            chest.update(true, false);
        }

        return info;
    }

    private static void placeBed(World world, int x, int y, int z, org.bukkit.block.BlockFace facing) {
        Block head = world.getBlockAt(x, y, z);
        head.setType(Material.WHITE_BED, false);
        Bed bed = (Bed) head.getBlockData();
        bed.setFacing(facing);
        bed.setPart(Bed.Part.HEAD);
        head.setBlockData(bed, false);

        int dz = (facing == org.bukkit.block.BlockFace.NORTH) ? 1 : -1;
        Block foot = world.getBlockAt(x, y, z + dz);
        foot.setType(Material.WHITE_BED, false);
        Bed bed2 = (Bed) foot.getBlockData();
        bed2.setFacing(facing);
        bed2.setPart(Bed.Part.FOOT);
        foot.setBlockData(bed2, false);
    }

    private static void set(World w, int x, int y, int z, Material m) {
        w.getBlockAt(x, y, z).setType(m, false);
    }
}
