package me.gonecasino.gf;

import me.gonecasino.GoneCasinoPlugin;
import me.gonecasino.gf.fishing.GFFishingSession;
import me.gonecasino.util.LocUtil;
import me.gonecasino.util.Keys;
import me.gonecasino.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.Event;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GONE Fishing game loop + trader + fishing overhaul.
 */
public final class GFManager implements Listener {
    private final GoneCasinoPlugin plugin;

    // persistence
    private final File file;
    private YamlConfiguration yml;

    // altar/house
    private Location altarBlock; // block location
    private HouseInfo houseInfo;

    // session
    private final Set<UUID> players = new LinkedHashSet<>();
    private boolean running = false;
    private boolean isNight = false;
    private int day = 0;
    private int quotaRequired = 0;
    private int quotaProgress = 0;
    private boolean quotaMet = false;
    private int pendingQuotaReduction = 0;

    private int sharedRodPower = 0;
    private int sharedRodLuck = 0;
    private int sharedWindowBonusMs = 0;
    private double sharedValueMultiplier = 1.0;
    private double sharedPointsMultiplier = 1.0;
    private int sharedPullCooldownReductionMs = 0;
    private int sharedPullReduction = 0;

    private org.bukkit.boss.BossBar bossBar;

    private final Set<String> kitChestLocations = new LinkedHashSet<>();
    private final Map<String, Integer> upgradePurchaseCounts = new HashMap<>();
    private static final Set<String> UPGRADE_TYPES = Set.of(
            GFItems.TYPE_ROD_POWER,
            GFItems.TYPE_ROD_LUCK,
            GFItems.TYPE_WINDOW_BOOST,
            GFItems.TYPE_CATCH_BONUS,
            GFItems.TYPE_PULL_COOLDOWN,
            GFItems.TYPE_PULL_REDUCTION
    );

    // trader
    private UUID traderUuid;
    private static final String TRADER_TITLE = "Странный торговец";
    private static final String SHOP_TITLE = "Торговец у Алтаря";
    private static final double TRADER_SCAN_RADIUS = 6.0;

    // fishing
    private static final DecimalFormat DF = new DecimalFormat("0.00");
    private static final int ACTIONBAR_WIDTH = 30;
    private static final Component BAR_OPEN = Component.text("[", NamedTextColor.DARK_GRAY);
    private static final Component BAR_CLOSE = Component.text("]", NamedTextColor.DARK_GRAY);
    private static final String BACKGROUND_CHAR = "·";
    private static final String BAR_CHAR = "█";
    private static final String FISH_CHAR = "●";

    private final Map<UUID, GFFishingSession> fishingSessions = new ConcurrentHashMap<>();
    private final Map<UUID, org.bukkit.boss.BossBar> fishingBossBars = new ConcurrentHashMap<>();
    private BukkitTask fishingTickTask;

    // cooking tasks
    private final Map<UUID, BukkitTask> cooking = new HashMap<>();

    // periodic tasks
    private BukkitTask tickTask;
    private BukkitTask flameTask;
    private BukkitTask purgeTask;
    private BukkitTask horrorTask;
    private BukkitTask monsterTask;
    private UUID lakeMonsterUuid;

    private final Random random = new Random();

    public GFManager(GoneCasinoPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "gf.yml");
    }

    public void load() {
        if (!file.getParentFile().exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.getParentFile().mkdirs();
        }
        this.yml = YamlConfiguration.loadConfiguration(file);

        String altar = yml.getString("altar", "");
        this.altarBlock = LocUtil.deserializeBlock(altar);

        this.pendingQuotaReduction = yml.getInt("shared.next_day_quota_reduction", 0);
        this.sharedRodPower = yml.getInt("shared.rod_power", 0);
        this.sharedRodLuck = yml.getInt("shared.rod_luck", 0);
        this.sharedWindowBonusMs = yml.getInt("shared.window_bonus_ms", 0);
        this.sharedValueMultiplier = yml.getDouble("shared.value_multiplier", 1.0);
        this.sharedPointsMultiplier = yml.getDouble("shared.points_multiplier", 1.0);
        this.sharedPullCooldownReductionMs = yml.getInt("shared.pull_cooldown_reduction_ms", 0);
        this.sharedPullReduction = yml.getInt("shared.pull_reduction", 0);
        this.kitChestLocations.clear();
        this.kitChestLocations.addAll(yml.getStringList("kit_chests"));
        this.upgradePurchaseCounts.clear();
        var upgradeSection = yml.getConfigurationSection("shop_upgrade_counts");
        if (upgradeSection != null) {
            for (String key : upgradeSection.getKeys(false)) {
                this.upgradePurchaseCounts.put(key, upgradeSection.getInt(key, 0));
            }
        }

        if (altarBlock != null && altarBlock.getWorld() != null) {
            int ox = plugin.getConfig().getInt("house.offset_x", 8);
            int oz = plugin.getConfig().getInt("house.offset_z", 0);
            this.houseInfo = HouseBuilder.computeInfo(altarBlock.getWorld(), altarBlock, ox, oz);
            bindHouseSlotMachine();
            ensureStarterChestContents();
            updateTeamRods();
        }
        updateKitChestRods();

        // start always-on tasks (they do nothing if not running)
        startTasks();

        logFishModelInfo();
    }

    public void save() {
        if (yml == null) yml = new YamlConfiguration();
        yml.set("altar", altarBlock == null ? "" : LocUtil.serializeBlock(altarBlock));
        yml.set("shared.next_day_quota_reduction", pendingQuotaReduction);
        yml.set("shared.rod_power", sharedRodPower);
        yml.set("shared.rod_luck", sharedRodLuck);
        yml.set("shared.window_bonus_ms", sharedWindowBonusMs);
        yml.set("shared.value_multiplier", sharedValueMultiplier);
        yml.set("shared.points_multiplier", sharedPointsMultiplier);
        yml.set("shared.pull_cooldown_reduction_ms", sharedPullCooldownReductionMs);
        yml.set("shared.pull_reduction", sharedPullReduction);
        yml.set("kit_chests", new ArrayList<>(kitChestLocations));
        for (String type : UPGRADE_TYPES) {
            yml.set("shop_upgrade_counts." + type, upgradePurchaseCounts.getOrDefault(type, 0));
        }
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save gf.yml: " + e.getMessage());
        }
    }

    public void setAltar(Location blockLoc) {
        if (blockLoc == null || blockLoc.getWorld() == null) return;
        this.altarBlock = new Location(blockLoc.getWorld(), blockLoc.getBlockX(), blockLoc.getBlockY(), blockLoc.getBlockZ());

        int ox = plugin.getConfig().getInt("house.offset_x", 8);
        int oz = plugin.getConfig().getInt("house.offset_z", 0);
        this.houseInfo = HouseBuilder.build(altarBlock.getWorld(), altarBlock, ox, oz);
        ensureStarterChestContents();
        updateTeamRods();
        bindHouseSlotMachine();

        save();

        // (re)spawn trader if currently day and running
        if (running && !isNight) {
            spawnTrader();
        }
    }

    public boolean clearAltar(Block block) {
        if (altarBlock == null || block == null) return false;
        if (!block.getWorld().equals(altarBlock.getWorld())) return false;
        if (block.getX() != altarBlock.getBlockX()
                || block.getY() != altarBlock.getBlockY()
                || block.getZ() != altarBlock.getBlockZ()) {
            return false;
        }
        removeHouse();
        despawnTrader();
        altarBlock = null;
        houseInfo = null;
        save();
        return true;
    }

    public boolean populateKitChest(Block block) {
        if (!(block.getState() instanceof org.bukkit.block.Chest chest)) return false;
        Inventory inv = chest.getInventory();
        int rodCount = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it == null) continue;
            if (GFItems.isStarterRod(it)) {
                if (rodCount >= 4) {
                    inv.setItem(i, null);
                } else {
                    rodCount++;
                    GFItems.updateStarterRod(it, sharedRodPower, sharedRodLuck);
                }
            }
        }
        for (int i = rodCount; i < 4; i++) {
            ItemStack rod = GFItems.createStarterRod();
            GFItems.updateStarterRod(rod, sharedRodPower, sharedRodLuck);
            inv.addItem(rod);
        }
        chest.update();

        String key = LocUtil.serializeBlock(block.getLocation());
        if (!key.isBlank()) {
            kitChestLocations.add(key);
            save();
        }
        return true;
    }

    public void join(Player p) {
        if (!running) {
            p.sendMessage(Text.bad("Режим GONE Fishing сейчас не запущен. Попросите админа: /casino gf start"));
            return;
        }
        if (players.contains(p.getUniqueId())) {
            p.sendMessage(Text.info("Вы уже в игре."));
            return;
        }
        players.add(p.getUniqueId());
        p.sendMessage(Text.ok("Вы вошли в GONE Fishing. Игроков: " + players.size()));
        if (bossBar != null) bossBar.addPlayer(p);
        applyResourcePack(p);
    }

    public void joinAll(Player requester) {
        if (!running) {
            requester.sendMessage(Text.bad("Режим GONE Fishing сейчас не запущен. Попросите админа: /casino gf start"));
            return;
        }
        int joined = 0;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (players.add(online.getUniqueId())) {
                joined++;
                online.sendMessage(Text.ok("Вы вошли в GONE Fishing."));
                if (bossBar != null) bossBar.addPlayer(online);
                applyResourcePack(online);
            }
        }
        requester.sendMessage(Text.ok("Подключено игроков: " + joined));
    }

    public void leave(Player p) {
        players.remove(p.getUniqueId());
        cancelFishingSession(p, "Вы вышли из GONE Fishing.");
        if (bossBar != null) bossBar.removePlayer(p);
        p.sendMessage(Text.info("Вы вышли из GONE Fishing."));
    }

    public void start(Player admin) {
        if (altarBlock == null) {
            admin.sendMessage(Text.bad("Сначала установите алтарь: /casino setaltar"));
            return;
        }
        if (running) {
            admin.sendMessage(Text.info("Уже запущено."));
            return;
        }
        running = true;
        day = 0;

        // determine current day/night
        isNight = altarBlock.getWorld().getTime() >= 12000;

        bossBar = Bukkit.createBossBar("", org.bukkit.boss.BarColor.YELLOW, org.bukkit.boss.BarStyle.SEGMENTED_10);
        for (UUID uuid : players) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                bossBar.addPlayer(p);
                applyResourcePack(p);
            }
        }

        if (isNight) {
            onNightStart();
        } else {
            onDayStart();
        }

        admin.sendMessage(Text.ok("GONE Fishing запущен."));
    }

    public void stop(Player admin) {
        running = false;
        quotaMet = false;
        quotaProgress = 0;
        quotaRequired = 0;

        despawnTrader();
        clearNightMonsters();

        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }
        clearFishingSessions();

        admin.sendMessage(Text.ok("GONE Fishing остановлен."));
    }

    public void resetProgress(Player admin) {
        double cleared = 0.0;
        if (plugin.bank().isAvailable()) {
            cleared = plugin.bank().clear();
        }

        for (BukkitTask task : cooking.values()) {
            task.cancel();
        }
        cooking.clear();
        clearFishingSessions();

        pendingQuotaReduction = 0;
        sharedRodPower = 0;
        sharedRodLuck = 0;
        sharedWindowBonusMs = 0;
        sharedValueMultiplier = 1.0;
        sharedPointsMultiplier = 1.0;
        sharedPullCooldownReductionMs = 0;
        sharedPullReduction = 0;
        upgradePurchaseCounts.clear();
        quotaProgress = 0;
        quotaRequired = 0;
        quotaMet = false;
        day = 0;

        if (running) {
            isNight = false;
            onDayStart();
        } else {
            updateBossbar();
        }

        updateTeamRods();
        save();

        if (plugin.bank().isAvailable()) {
            admin.sendMessage(Text.ok("Прогресс сброшен. Фишки удалены: " + (int) Math.floor(cleared) + ". День 1."));
        } else {
            admin.sendMessage(Text.ok("Прогресс сброшен. День 1."));
        }
    }

    public void status(Player p) {
        p.sendMessage(Text.info("GONE Fishing: " + (running ? "ON" : "OFF")));
        p.sendMessage(Text.info("Игроков: " + players.size() + "/4"));
        if (running) {
            p.sendMessage(Text.info("День: " + day + (isNight ? " (ночь)" : " (день)")));
            p.sendMessage(Text.info("Квота: " + quotaProgress + "/" + quotaRequired + (quotaMet ? " (закрыто)" : "")));
            p.sendMessage(Text.info("Общие улучшения: сила удочки +" + sharedRodPower
                    + ", удача удочки +" + sharedRodLuck
                    + ", окно +" + sharedWindowBonusMs + "мс"
                    + ", ценность x" + DF.format(sharedValueMultiplier)
                    + ", очки x" + DF.format(sharedPointsMultiplier)));
        }
    }

    public boolean isInGame(Player p) {
        return players.contains(p.getUniqueId());
    }

    // === tasks ===

    private void startTasks() {
        if (tickTask != null) return;

        // Day/night watcher + trader management + quota check
        tickTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!running || altarBlock == null || altarBlock.getWorld() == null) return;
                World w = altarBlock.getWorld();
                boolean nowNight = w.getTime() >= 12000;
                if (nowNight != isNight) {
                    isNight = nowNight;
                    if (isNight) onNightStart();
                    else onDayStart();
                }
                updateBossbar();
            }
        }.runTaskTimer(plugin, 20L, 20L);

        // Altar flame particles
        flameTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (altarBlock == null || altarBlock.getWorld() == null) return;
                World w = altarBlock.getWorld();
                Location p = altarBlock.clone().add(0.5, 1.2, 0.5);
                Particle particle = quotaMet ? Particle.SOUL_FIRE_FLAME : Particle.FLAME;
                w.spawnParticle(particle, p, 10, 0.15, 0.25, 0.15, 0.0);
            }
        }.runTaskTimer(plugin, 10L, 10L);

        // Safe zone purge
        int interval = plugin.getConfig().getInt("house.purge_interval_ticks", 20);
        purgeTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (houseInfo == null || altarBlock == null || altarBlock.getWorld() == null) return;
                BoundingBox box = houseInfo.safeZone();
                World w = altarBlock.getWorld();
                for (Entity e : w.getEntities()) {
                    if (!(e instanceof Monster)) continue;
                    if (box.contains(e.getLocation().toVector())) {
                        e.remove();
                    }
                }
            }
        }.runTaskTimer(plugin, interval, interval);

        // Horror ambience at night
        horrorTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!running || !isNight) return;
                if (random.nextDouble() < 0.18) {
                    forEachGamePlayer(pp -> {
                        pp.playSound(pp.getLocation(), Sound.AMBIENT_CAVE, 0.6f, 0.8f);
                    });
                }
                if (random.nextDouble() < 0.12) {
                    forEachGamePlayer(pp -> {
                        pp.playSound(pp.getLocation(), Sound.ENTITY_WARDEN_LISTENING, 0.5f, 0.6f);
                        pp.spawnParticle(Particle.SOUL, pp.getLocation().add(0, 1.1, 0), 12, 0.35, 0.2, 0.35, 0.0);
                        pp.sendActionBar(Component.text("Ты слышишь шёпот у воды...", NamedTextColor.DARK_PURPLE));
                    });
                }
            }
        }.runTaskTimer(plugin, 20L * 20, 20L * 20); // every 20s

    }

    private void onDayStart() {
        day++;
        quotaProgress = 0;
        quotaMet = false;
        int base = plugin.getConfig().getInt("quota.base", 40);
        int inc = plugin.getConfig().getInt("quota.increase_per_day", 15);
        int rawQuota = base + (day - 1) * inc;
        int reduce = Math.min(rawQuota, Math.max(0, pendingQuotaReduction));
        quotaRequired = Math.max(0, rawQuota - reduce);
        if (reduce > 0) {
            pendingQuotaReduction = Math.max(0, pendingQuotaReduction - reduce);
        }

        clearNightMonsters();

        spawnTrader();

        forEachGamePlayer(p -> {
            if (reduce > 0) {
                p.sendMessage(Text.ok("День " + day + ". Квота снижена на " + reduce + ": " + quotaRequired + " очков."));
            } else {
                p.sendMessage(Text.ok("День " + day + ". Новая квота: " + quotaRequired + " очков."));
            }
            p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 20 * 4, 0, true, false, true));
        });

        updateBossbar();
    }

    private void onNightStart() {
        despawnTrader();

        forEachGamePlayer(p -> {
            p.showTitle(Title.title(
                    Component.text("НАСТУПАЕТ НОЧЬ", NamedTextColor.RED),
                    Component.text("Вернись к дому или закрывай квоту", NamedTextColor.DARK_RED),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(1600), Duration.ofMillis(400))
            ));
            p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 0.6f, 0.8f);
            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 20 * 3, 0, true, false, true));
        });

        if (!quotaMet) {
            spawnLakeMonster();
        }

        updateBossbar();
    }

    private void spawnLakeMonster() {
        if (altarBlock == null || altarBlock.getWorld() == null) return;
        if (Bukkit.getOnlinePlayers().isEmpty()) return;
        World w = altarBlock.getWorld();
        Location spawn = altarBlock.clone().add(3, 0, 3);
        spawn.setY(w.getHighestBlockYAt(spawn) + 1);

        Ravager r = (Ravager) w.spawnEntity(spawn, EntityType.RAVAGER);
        r.customName(Component.text("Озёрное Чудовище", NamedTextColor.DARK_RED));
        r.setCustomNameVisible(true);
        r.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 120, 1));
        r.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 20 * 120, 0));
        r.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 20 * 120, 1));
        r.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 20 * 120, 0));
        r.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 20 * 120, 0));
        r.setSilent(false);

        lakeMonsterUuid = r.getUniqueId();
        startMonsterTask();

        forEachGamePlayer(p -> {
            p.sendMessage(Text.bad("Квота не закрыта. Охота началась..."));
            p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.8f, 0.6f);
        });
    }

    private void startMonsterTask() {
        if (monsterTask != null) {
            monsterTask.cancel();
        }
        monsterTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!running || !isNight || lakeMonsterUuid == null) return;
                if (Bukkit.getOnlinePlayers().isEmpty()) {
                    stopLakeMonster();
                    return;
                }
                Entity e = Bukkit.getEntity(lakeMonsterUuid);
                if (!(e instanceof Ravager monster) || monster.isDead()) return;
                if (random.nextDouble() < 0.5) {
                    forEachGamePlayer(p -> {
                        Location target = p.getLocation().clone();
                        double angle = random.nextDouble() * Math.PI * 2;
                        double dist = 6 + random.nextDouble() * 6;
                        Location tp = target.clone().add(Math.cos(angle) * dist, 0, Math.sin(angle) * dist);
                        tp.setY(tp.getWorld().getHighestBlockYAt(tp) + 1);
                        monster.teleport(tp);
                        p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_SCREAM, 0.8f, 0.7f);
                        p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_AMBIENT, 0.6f, 0.6f);
                        p.spawnParticle(Particle.LARGE_SMOKE, p.getLocation().add(0, 0.6, 0), 16, 0.4, 0.5, 0.4, 0.01);
                        p.spawnParticle(Particle.SOUL, p.getLocation().add(0, 0.8, 0), 10, 0.5, 0.5, 0.5, 0.02);
                        if (p.getLocation().distanceSquared(monster.getLocation()) < 64) {
                            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 40, 0, true, false, true));
                        }
                    });
                }
            }
        }.runTaskTimer(plugin, 40L, 60L);
    }

    private void bindHouseSlotMachine() {
        if (houseInfo == null || houseInfo.slotMachine() == null) return;
        Block slotBlock = houseInfo.slotMachine().getBlock();
        if (slotBlock.getType() == Material.AIR) return;
        plugin.tables().setTable(slotBlock, me.gonecasino.casino.TableType.SLOT);
    }

    private void updateTeamRods() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            for (ItemStack it : p.getInventory().getContents()) {
                if (it == null) continue;
                if (!GFItems.isStarterRod(it)) continue;
                GFItems.updateStarterRod(it, sharedRodPower, sharedRodLuck);
            }
            ItemStack offhand = p.getInventory().getItemInOffHand();
            if (GFItems.isStarterRod(offhand)) {
                GFItems.updateStarterRod(offhand, sharedRodPower, sharedRodLuck);
            }
        }

        if (houseInfo != null && houseInfo.starterChest() != null) {
            Block chestBlock = houseInfo.starterChest().getBlock();
            if (chestBlock.getState() instanceof org.bukkit.block.Chest chest) {
                for (ItemStack it : chest.getInventory().getContents()) {
                    if (it == null) continue;
                    if (!GFItems.isStarterRod(it)) continue;
                    GFItems.updateStarterRod(it, sharedRodPower, sharedRodLuck);
                }
                chest.update();
            }
        }

        updateKitChestRods();
    }

    public int giveStarterRodsToOnlinePlayers() {
        int given = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (hasFishingRod(player)) {
                continue;
            }
            ItemStack rod = GFItems.createStarterRod();
            GFItems.updateStarterRod(rod, sharedRodPower, sharedRodLuck);
            player.getInventory().addItem(rod);
            given++;
        }
        return given;
    }

    private static boolean hasFishingRod(Player player) {
        if (player == null) return false;
        ItemStack main = player.getInventory().getItemInMainHand();
        if (main != null && main.getType() == Material.FISHING_ROD) return true;
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand != null && offhand.getType() == Material.FISHING_ROD) return true;
        for (ItemStack it : player.getInventory().getContents()) {
            if (it == null) continue;
            if (it.getType() == Material.FISHING_ROD) return true;
        }
        return false;
    }

    private void updateKitChestRods() {
        if (kitChestLocations.isEmpty()) return;
        boolean dirty = false;
        Iterator<String> it = kitChestLocations.iterator();
        while (it.hasNext()) {
            Location loc = LocUtil.deserializeBlock(it.next());
            if (loc == null) {
                it.remove();
                dirty = true;
                continue;
            }
            Block block = loc.getBlock();
            if (!(block.getState() instanceof org.bukkit.block.Chest chest)) {
                it.remove();
                dirty = true;
                continue;
            }
            for (ItemStack item : chest.getInventory().getContents()) {
                if (item == null) continue;
                if (!GFItems.isStarterRod(item)) continue;
                GFItems.updateStarterRod(item, sharedRodPower, sharedRodLuck);
            }
            chest.update();
        }
        if (dirty) {
            save();
        }
    }

    private void removeHouse() {
        if (houseInfo == null || altarBlock == null || altarBlock.getWorld() == null) return;
        BoundingBox box = houseInfo.safeZone();
        World w = altarBlock.getWorld();
        int minX = (int) Math.floor(box.getMinX());
        int maxX = (int) Math.ceil(box.getMaxX());
        int minY = (int) Math.floor(box.getMinY());
        int maxY = (int) Math.ceil(box.getMaxY());
        int minZ = (int) Math.floor(box.getMinZ());
        int maxZ = (int) Math.ceil(box.getMaxZ());
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    w.getBlockAt(x, y, z).setType(Material.AIR, false);
                }
            }
        }
        if (houseInfo.slotMachine() != null) {
            plugin.tables().delTable(houseInfo.slotMachine().getBlock());
        }
    }

    private void clearNightMonsters() {
        stopLakeMonster();
        if (altarBlock != null && altarBlock.getWorld() != null) {
            World w = altarBlock.getWorld();
            for (Entity e : w.getEntities()) {
                if (e instanceof Monster) {
                    e.remove();
                }
            }
        }
    }

    private void updateBossbar() {
        if (bossBar == null) return;
        int chips = plugin.bank().isAvailable() ? (int) Math.floor(plugin.bank().getBalance()) : 0;
        String title = (isNight ? "Ночь" : "День") + " " + day + " | Квота: " + quotaProgress + "/" + quotaRequired + " | Фишек: " + chips;
        bossBar.setTitle(title);
        double prog = quotaRequired <= 0 ? 0 : Math.min(1.0, Math.max(0.0, (double) quotaProgress / (double) quotaRequired));
        bossBar.setProgress(prog);
        bossBar.setColor(quotaMet ? org.bukkit.boss.BarColor.BLUE : (isNight ? org.bukkit.boss.BarColor.RED : org.bukkit.boss.BarColor.YELLOW));
    }

    private void forEachGamePlayer(java.util.function.Consumer<Player> fn) {
        for (UUID uuid : players) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) fn.accept(p);
        }
    }

    // === Trader ===

    private void spawnTrader() {
        if (altarBlock == null || altarBlock.getWorld() == null) return;
        if (traderUuid != null) {
            Entity e = Bukkit.getEntity(traderUuid);
            if (e != null && !e.isDead()) return;
        }

        if (bindExistingTrader()) {
            return;
        }

        World w = altarBlock.getWorld();
        Location loc = altarBlock.clone().add(2.5, 0, -1.5);
        loc.setY(w.getHighestBlockYAt(loc) + 1);

        Villager v = (Villager) w.spawnEntity(loc, EntityType.VILLAGER);
        v.customName(Component.text(TRADER_TITLE, NamedTextColor.GOLD));
        v.setCustomNameVisible(true);
        v.setAI(false);
        v.setInvulnerable(true);
        v.setPersistent(true);
        v.setSilent(true);
        v.setProfession(org.bukkit.entity.Villager.Profession.CLERIC);
        v.getPersistentDataContainer().set(Keys.GF_TRADER, PersistentDataType.BYTE, (byte) 1);

        traderUuid = v.getUniqueId();
    }

    private void despawnTrader() {
        if (traderUuid == null) return;
        Entity e = Bukkit.getEntity(traderUuid);
        if (e != null) e.remove();
        traderUuid = null;
    }

    private boolean isTrader(Entity e) {
        if (e == null) return false;
        if (traderUuid != null && traderUuid.equals(e.getUniqueId())) return true;
        if (!(e instanceof Villager villager)) return false;
        if (villager.getPersistentDataContainer().has(Keys.GF_TRADER, PersistentDataType.BYTE)) {
            traderUuid = villager.getUniqueId();
            return true;
        }
        return false;
    }

    private boolean bindExistingTrader() {
        if (altarBlock == null || altarBlock.getWorld() == null) return false;
        World w = altarBlock.getWorld();
        Location loc = altarBlock.clone().add(2.5, 0, -1.5);
        for (Entity entity : w.getNearbyEntities(loc, TRADER_SCAN_RADIUS, TRADER_SCAN_RADIUS, TRADER_SCAN_RADIUS)) {
            if (!(entity instanceof Villager villager)) continue;
            if (!villager.getPersistentDataContainer().has(Keys.GF_TRADER, PersistentDataType.BYTE)) continue;
            traderUuid = villager.getUniqueId();
            villager.setAI(false);
            villager.setInvulnerable(true);
            villager.setPersistent(true);
            villager.setSilent(true);
            villager.customName(Component.text(TRADER_TITLE, NamedTextColor.GOLD));
            villager.setCustomNameVisible(true);
            return true;
        }
        return false;
    }

    private Inventory createShopInventory() {
        Inventory inv = Bukkit.createInventory(null, 27, Component.text(SHOP_TITLE, NamedTextColor.GOLD));

        inv.setItem(10, addPriceLore(GFItems.createBait(1, 8), resolveShopPrice(GFItems.TYPE_BAIT, plugin.getConfig().getInt("shop.bait_tier1", 20))));
        inv.setItem(11, addPriceLore(GFItems.createBait(2, 8), resolveShopPrice(GFItems.TYPE_BAIT, plugin.getConfig().getInt("shop.bait_tier2", 75))));
        inv.setItem(12, addPriceLore(GFItems.createBait(3, 4), resolveShopPrice(GFItems.TYPE_BAIT, plugin.getConfig().getInt("shop.bait_tier3", 200))));

        inv.setItem(14, addPriceLore(GFItems.createRodUpgradePower(), resolveShopPrice(GFItems.TYPE_ROD_POWER, plugin.getConfig().getInt("shop.rod_power_upgrade", 250))));
        inv.setItem(15, addPriceLore(GFItems.createRodUpgradeLuck(), resolveShopPrice(GFItems.TYPE_ROD_LUCK, plugin.getConfig().getInt("shop.rod_luck_upgrade", 250))));

        inv.setItem(16, addPriceLore(GFItems.createQuotaReducer(plugin.getConfig().getInt("quota_reduce_amount", 10)), resolveShopPrice(GFItems.TYPE_QUOTA_REDUCER, plugin.getConfig().getInt("shop.quota_reduce_item", 300))));
        inv.setItem(19, addPriceLore(GFItems.createFishingWindowBoost(plugin.getConfig().getInt("upgrades.window_bonus_ms", 450)), resolveShopPrice(GFItems.TYPE_WINDOW_BOOST, plugin.getConfig().getInt("shop.window_boost", 220))));
        inv.setItem(20, addPriceLore(GFItems.createCatchBonus(plugin.getConfig().getInt("upgrades.catch_bonus_percent", 12)), resolveShopPrice(GFItems.TYPE_CATCH_BONUS, plugin.getConfig().getInt("shop.catch_bonus", 260))));
        inv.setItem(21, addPriceLore(GFItems.createPullCooldownBoost(plugin.getConfig().getInt("upgrades.pull_cooldown_reduction_ms", 30)), resolveShopPrice(GFItems.TYPE_PULL_COOLDOWN, plugin.getConfig().getInt("shop.pull_cooldown_boost", 200))));
        inv.setItem(22, addPriceLore(GFItems.createPullReductionBoost(plugin.getConfig().getInt("upgrades.pull_reduction", 1)), resolveShopPrice(GFItems.TYPE_PULL_REDUCTION, plugin.getConfig().getInt("shop.pull_reduction_boost", 240))));
        inv.setItem(23, addPriceLore(GFItems.createCampfireRecall(), resolveShopPrice(GFItems.TYPE_CAMPFIRE_RECALL, plugin.getConfig().getInt("shop.campfire_recall", 120))));
        inv.setItem(24, addPriceLore(GFItems.createAmuletSilence(), resolveShopPrice(GFItems.TYPE_AMULET_SILENCE, plugin.getConfig().getInt("shop.amulet_silence", 180))));

        return inv;
    }

    private ItemStack addPriceLore(ItemStack it, int price) {
        if (it == null) return null;
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return it;
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(Component.text("Цена: " + price + " фишек", NamedTextColor.GRAY));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "shop_price"), PersistentDataType.INTEGER, price);
        it.setItemMeta(meta);
        return it;
    }

    private int resolveShopPrice(String type, int basePrice) {
        if (!UPGRADE_TYPES.contains(type)) {
            return basePrice;
        }
        int count = upgradePurchaseCounts.getOrDefault(type, 0);
        double percent = plugin.getConfig().getDouble("shop.upgrade_price_increase_percent", 20.0);
        return (int) Math.ceil(basePrice * (1.0 + (count * percent / 100.0)));
    }

    // === Events ===

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (!isTrader(event.getRightClicked())) return;
        event.setCancelled(true);
        Player p = event.getPlayer();

        ItemStack hand = p.getInventory().getItemInMainHand();
        ItemStack offhand = p.getInventory().getItemInOffHand();
        ItemStack fishItem = GFItems.isCustomFish(hand) ? hand : (GFItems.isCustomFish(offhand) ? offhand : null);
        if (fishItem != null) {
            FishData fish = GFItems.readFish(fishItem);
            if (fish != null) {
                if (!plugin.bank().isAvailable()) {
                    p.sendMessage(Text.bad("Экономика недоступна (Vault/EssentialsX)."));
                    return;
                }
                int value = fish.value();
                plugin.bank().give(value);
                fishItem.setAmount(fishItem.getAmount() - 1);
                p.sendMessage(Text.ok("Вы продали рыбу за " + value + " фишек."));
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_YES, 1f, 1.2f);
                return;
            }
        }

        // fallback: open shop
        p.openInventory(createShopInventory());
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.0f);
    }

    @EventHandler
    public void onShopClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = PlainTextComponentSerializer.plainText().serialize(event.getView().title());
        if (!SHOP_TITLE.equals(title)) return;

        event.setCancelled(true);

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) return;

        Integer price = meta.getPersistentDataContainer().get(new org.bukkit.NamespacedKey(plugin, "shop_price"), PersistentDataType.INTEGER);
        if (price == null) return;

        if (!plugin.bank().isAvailable() || !plugin.bank().take(price)) {
            player.sendMessage(Text.bad("Недостаточно фишек."));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 0.8f);
            return;
        }

        ItemStack give = clicked.clone();
        // remove price lore marker
        ItemMeta gm = give.getItemMeta();
        if (gm != null) {
            gm.getPersistentDataContainer().remove(new org.bukkit.NamespacedKey(plugin, "shop_price"));
            give.setItemMeta(gm);
        }

        player.getInventory().addItem(give);
        player.sendMessage(Text.ok("Покупка успешна (-" + price + " фишек)."));
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 1f, 1.2f);

        String type = GFItems.getType(clicked);
        if (type != null && UPGRADE_TYPES.contains(type)) {
            upgradePurchaseCounts.put(type, upgradePurchaseCounts.getOrDefault(type, 0) + 1);
            save();
            player.openInventory(createShopInventory());
        }
    }

    @EventHandler
    public void onBedEnter(PlayerBedEnterEvent event) {
        Player player = event.getPlayer();
        if (!running || !isInGame(player)) return;
        if (event.getBedEnterResult() == PlayerBedEnterEvent.BedEnterResult.NOT_SAFE) {
            event.setUseBed(Event.Result.ALLOW);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player p = event.getPlayer();

        // special item use (right click)
        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            ItemStack hand = event.getItem();
            if (hand != null) {
                String type = GFItems.getType(hand);
                if (GFItems.TYPE_QUOTA_REDUCER.equals(type)) {
                    if (!running || !isInGame(p)) {
                        p.sendMessage(Text.bad("Это работает только в режиме GONE Fishing."));
                        return;
                    }
                    int reduce = plugin.getConfig().getInt("quota_reduce_amount", 10);
                    pendingQuotaReduction += reduce;
                    hand.setAmount(hand.getAmount() - 1);
                    p.sendMessage(Text.ok("Снижение квоты следующего дня: -" + reduce + " (в запасе: " + pendingQuotaReduction + ")."));
                    p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1.2f);
                    updateBossbar();
                    return;
                }
                if (GFItems.TYPE_WINDOW_BOOST.equals(type)) {
                    if (!running || !isInGame(p)) {
                        p.sendMessage(Text.bad("Это работает только в режиме GONE Fishing."));
                        return;
                    }
                    int bonus = plugin.getConfig().getInt("upgrades.window_bonus_ms", 450);
                    int max = plugin.getConfig().getInt("upgrades.max_window_bonus_ms", 2400);
                    sharedWindowBonusMs = Math.min(max, sharedWindowBonusMs + bonus);
                    hand.setAmount(hand.getAmount() - 1);
                    p.sendMessage(Text.ok("Окно вываживания увеличено до +" + sharedWindowBonusMs + " мс для команды."));
                    p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.2f);
                    return;
                }
                if (GFItems.TYPE_CATCH_BONUS.equals(type)) {
                    if (!running || !isInGame(p)) {
                        p.sendMessage(Text.bad("Это работает только в режиме GONE Fishing."));
                        return;
                    }
                    double bonus = plugin.getConfig().getDouble("upgrades.catch_bonus_percent", 12) / 100.0;
                    double max = plugin.getConfig().getDouble("upgrades.max_catch_multiplier", 2.5);
                    sharedValueMultiplier = Math.min(max, sharedValueMultiplier + bonus);
                    sharedPointsMultiplier = Math.min(max, sharedPointsMultiplier + bonus);
                    hand.setAmount(hand.getAmount() - 1);
                    p.sendMessage(Text.ok("Бонус улова: x" + String.format(java.util.Locale.US, "%.2f", sharedValueMultiplier)));
                    p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.1f);
                    return;
                }
                if (GFItems.TYPE_PULL_COOLDOWN.equals(type)) {
                    if (!running || !isInGame(p)) {
                        p.sendMessage(Text.bad("Это работает только в режиме GONE Fishing."));
                        return;
                    }
                    int bonus = plugin.getConfig().getInt("upgrades.pull_cooldown_reduction_ms", 30);
                    int max = plugin.getConfig().getInt("upgrades.max_pull_cooldown_reduction_ms", 140);
                    sharedPullCooldownReductionMs = Math.min(max, sharedPullCooldownReductionMs + bonus);
                    hand.setAmount(hand.getAmount() - 1);
                    p.sendMessage(Text.ok("Командные рывки быстрее: -" + sharedPullCooldownReductionMs + " мс."));
                    p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.2f);
                    return;
                }
                if (GFItems.TYPE_PULL_REDUCTION.equals(type)) {
                    if (!running || !isInGame(p)) {
                        p.sendMessage(Text.bad("Это работает только в режиме GONE Fishing."));
                        return;
                    }
                    int bonus = plugin.getConfig().getInt("upgrades.pull_reduction", 1);
                    int max = plugin.getConfig().getInt("upgrades.max_pull_reduction", 4);
                    sharedPullReduction = Math.min(max, sharedPullReduction + bonus);
                    hand.setAmount(hand.getAmount() - 1);
                    p.sendMessage(Text.ok("Командная сложность снижена: -" + sharedPullReduction + " рыв."));
                    p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.1f);
                    return;
                }
                if (GFItems.TYPE_CAMPFIRE_RECALL.equals(type)) {
                    if (houseInfo == null) {
                        p.sendMessage(Text.bad("Дом ещё не создан."));
                        return;
                    }
                    p.teleport(houseInfo.homeSpawn());
                    hand.setAmount(hand.getAmount() - 1);
                    p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
                    return;
                }
                if (GFItems.TYPE_AMULET_SILENCE.equals(type)) {
                    if (!isNight) {
                        p.sendMessage(Text.bad("Амулет активируется только ночью."));
                        return;
                    }
                    p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 20 * 20, 0, true, false, true));
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 20, 0, true, false, true));
                    hand.setAmount(hand.getAmount() - 1);
                    p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 0.9f);
                    return;
                }

                // shared rod upgrades
                if (GFItems.TYPE_ROD_POWER.equals(type) || GFItems.TYPE_ROD_LUCK.equals(type)) {
                    if (!running || !isInGame(p)) {
                        p.sendMessage(Text.bad("Это работает только в режиме GONE Fishing."));
                        return;
                    }
                    if (GFItems.TYPE_ROD_POWER.equals(type)) {
                        int max = plugin.getConfig().getInt("upgrades.max_team_power", 6);
                        if (sharedRodPower >= max) {
                            p.sendMessage(Text.bad("Сила команды уже на максимуме."));
                            return;
                        }
                        sharedRodPower++;
                        hand.setAmount(hand.getAmount() - 1);
                        p.sendMessage(Text.ok("Сила команды повышена до " + sharedRodPower + "."));
                        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.7f, 1.2f);
                        updateTeamRods();
                        return;
                    }
                    if (GFItems.TYPE_ROD_LUCK.equals(type)) {
                        int max = plugin.getConfig().getInt("upgrades.max_team_luck", 6);
                        if (sharedRodLuck >= max) {
                            p.sendMessage(Text.bad("Удача команды уже на максимуме."));
                            return;
                        }
                        sharedRodLuck++;
                        hand.setAmount(hand.getAmount() - 1);
                        p.sendMessage(Text.ok("Удача команды повышена до " + sharedRodLuck + "."));
                        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.7f, 1.2f);
                        updateTeamRods();
                        return;
                    }
                }
            }
        }

        // altar submit
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            Block b = event.getClickedBlock();
            if (altarBlock != null && b.getWorld() == altarBlock.getWorld()
                    && b.getX() == altarBlock.getBlockX() && b.getY() == altarBlock.getBlockY() && b.getZ() == altarBlock.getBlockZ()) {
                if (!running || !isInGame(p)) {
                    p.sendMessage(Text.bad("Сначала войдите в игру: /casino gf join"));
                    return;
                }
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (!GFItems.isCustomFish(hand)) {
                    p.sendMessage(Text.bad("Алтарь принимает только особую рыбу (из режима)."));
                    return;
                }
                if (quotaMet) {
                    p.sendMessage(Text.bad("Квота уже закрыта. Алтарь больше не принимает рыбу."));
                    return;
                }
                FishData fish = GFItems.readFish(hand);
                if (fish == null) return;

                hand.setAmount(hand.getAmount() - 1);
                quotaProgress += fish.points();

                p.sendMessage(Text.ok("Вы сдали рыбу: +" + fish.points() + " очков. Квота: " + quotaProgress + "/" + quotaRequired));
                p.playSound(p.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.7f, 1.0f);

                if (!quotaMet && quotaProgress >= quotaRequired) {
                    quotaMet = true;
                    clearNightMonsters();
                    forEachGamePlayer(pp -> {
                        pp.sendMessage(Text.ok("Квота закрыта! Алтарь доволен..."));
                        pp.playSound(pp.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.2f);
                    });
                }
                updateBossbar();
                return;
            }

            // campfire cook
            if (houseInfo != null) {
                Location c = houseInfo.campfire();
                if (b.getWorld() == c.getWorld() && b.getX() == c.getBlockX() && b.getY() == c.getBlockY() && b.getZ() == c.getBlockZ()) {
                    ItemStack hand = p.getInventory().getItemInMainHand();
                    if (!GFItems.isCustomFish(hand)) {
                        p.sendMessage(Text.bad("Можно жарить только особую рыбу."));
                        return;
                    }
                    if (cooking.containsKey(p.getUniqueId())) {
                        p.sendMessage(Text.bad("Вы уже что-то жарите."));
                        return;
                    }
                    FishData fish = GFItems.readFish(hand);
                    if (fish == null) return;
                    if (fish.cooked()) {
                        p.sendMessage(Text.bad("Эта рыба уже жареная."));
                        return;
                    }

                    // consume raw fish
                    hand.setAmount(hand.getAmount() - 1);

                    int seconds = plugin.getConfig().getInt("cooking.seconds", 5);
                    p.sendMessage(Text.info("Жарим рыбу... " + seconds + "с"));
                    p.playSound(p.getLocation(), Sound.BLOCK_CAMPFIRE_CRACKLE, 0.8f, 1.0f);

                    BukkitTask task = new BukkitRunnable() {
                        @Override
                        public void run() {
                            cooking.remove(p.getUniqueId());

                            double vm = plugin.getConfig().getDouble("cooking.value_multiplier", 1.5);
                            double pm = plugin.getConfig().getDouble("cooking.points_multiplier", 1.35);

                            FishData cooked = new FishData(
                                    fish.rarity(),
                                    fish.quality(),
                                    fish.weightKg(),
                                    (int) Math.round(fish.points() * pm),
                                    (int) Math.round(fish.value() * vm),
                                    true,
                                    fish.speciesName()
                            );
                            ItemStack cookedItem = GFItems.createFishItem(cooked);
                            p.getInventory().addItem(cookedItem);
                            p.sendMessage(Text.ok("Готово! Рыба пожарена."));
                            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.3f);
                        }
                    }.runTaskLater(plugin, 20L * seconds);

                    cooking.put(p.getUniqueId(), task);
                }
            }
        }

        // vanilla fishing handles rod clicks
    }

    private void giveToPlayer(Player p, ItemStack item) {
        Map<Integer, ItemStack> leftover = p.getInventory().addItem(item);
        if (!leftover.isEmpty()) {
            for (ItemStack it : leftover.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), it);
            }
        }
    }

    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!running || !isNight) return;
        if (!(event.getEntity() instanceof Monster m)) return;
        // buff monsters at night
        m.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 120, 0));
        if (random.nextDouble() < 0.25) {
            m.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 20 * 120, 0));

        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!running || bossBar == null) return;
        if (!players.contains(player.getUniqueId())) return;
        bossBar.addPlayer(player);
        updateBossbar();
        updateTeamRods();
        applyResourcePack(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancelFishingSession(event.getPlayer(), "Вы покинули игру.");
        if (bossBar == null) return;
        bossBar.removePlayer(event.getPlayer());
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            stopLakeMonster();
        }
    }

    @EventHandler
    public void onPlayerFish(PlayerFishEvent event) {
        Player player = event.getPlayer();
        if (!running || !isInGame(player)) return;
        ItemStack rod = player.getInventory().getItemInMainHand();
        if (rod == null || rod.getType() != Material.FISHING_ROD) return;
        if (plugin.getConfig().getBoolean("fishing_minigame.only_starter_rod", true)
                && !GFItems.isStarterRod(rod)) {
            return;
        }

        GFFishingSession active = fishingSessions.get(player.getUniqueId());
        if (active != null) {
            event.setCancelled(true);
            event.setExpToDrop(0);
            if (event.getCaught() != null) {
                event.getCaught().remove();
            }
            if (event.getState() == PlayerFishEvent.State.REEL_IN) {
                cancelFishingSession(player, "Вы прекратили вываживание.");
            }
            return;
        }

        if (event.getState() != PlayerFishEvent.State.BITE) return;
        startFishingSession(player, event.getHook());
        event.setCancelled(true);
        event.setExpToDrop(0);
        if (event.getCaught() != null) {
            event.getCaught().remove();
        }
    }

    @EventHandler
    public void onPlayerSwapHandItems(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        GFFishingSession session = fishingSessions.get(player.getUniqueId());
        if (session == null) return;
        if (!plugin.getConfig().getBoolean("fishing_minigame.dash.enabled", true)) return;
        event.setCancelled(true);

        long baseCooldown = plugin.getConfig().getLong("fishing_minigame.dash.cooldown_ms", 1200L);
        long cooldown = Math.max(150L, baseCooldown - sharedPullCooldownReductionMs);
        double bonus = plugin.getConfig().getDouble("fishing_minigame.dash.points_bonus", 6.0);
        boolean dashed = session.tryDash(System.currentTimeMillis(), cooldown, bonus);
        if (dashed) {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.7f, 1.5f);
            player.spawnParticle(Particle.CRIT, player.getLocation().add(0, 1.0, 0), 8, 0.25, 0.25, 0.25, 0.05);
        } else {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 0.7f);
        }
    }

    @EventHandler
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        if (!running) return;
        Player player = event.getPlayer();
        if (!isInGame(player)) return;

        boolean force = plugin.getConfig().getBoolean("resourcepack.force", false);
        if (event.getStatus() == PlayerResourcePackStatusEvent.Status.DECLINED
                || event.getStatus() == PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD) {
            if (force) {
                player.sendMessage(Text.bad("Для GONE Fishing нужен ресурспак. Вы исключены из режима."));
                leave(player);
            } else {
                player.sendMessage(Text.info("Без ресурспака рыба будет выглядеть как обычная треска."));
            }
        }
    }

    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        cancelFishingSession(event.getPlayer(), "Вы сменили локацию.");
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        cancelFishingSession(event.getPlayer(), "Вы сменили мир.");
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        cancelFishingSession(event.getEntity(), "Вы погибли.");
    }

    @EventHandler
    public void onItemBreak(PlayerItemBreakEvent event) {
        if (event.getBrokenItem().getType() != Material.FISHING_ROD) return;
        cancelFishingSession(event.getPlayer(), "Удочка сломалась.");
    }

    private void stopLakeMonster() {
        if (monsterTask != null) {
            monsterTask.cancel();
            monsterTask = null;
        }
        if (lakeMonsterUuid != null) {
            Entity e = Bukkit.getEntity(lakeMonsterUuid);
            if (e != null) e.remove();
            lakeMonsterUuid = null;
        }
    }

    private void ensureStarterChestContents() {
        if (houseInfo == null || houseInfo.starterChest() == null) return;
        Block chestBlock = houseInfo.starterChest().getBlock();
        if (!(chestBlock.getState() instanceof org.bukkit.block.Chest chest)) return;

        Inventory inv = chest.getInventory();
        int rodCount = 0;
        int baitCount = 0;

        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it == null) continue;

            if (GFItems.isStarterRod(it)) {
                if (rodCount >= 4) {
                    inv.setItem(i, null);
                } else {
                    rodCount++;
                }
                continue;
            }

            int baitTier = GFItems.getBaitTier(it);
            if (baitTier == 1) {
                int needed = 8 - baitCount;
                if (needed <= 0) {
                    inv.setItem(i, null);
                } else if (it.getAmount() > needed) {
                    it.setAmount(needed);
                    baitCount += needed;
                } else {
                    baitCount += it.getAmount();
                }
            }
        }

        for (int i = rodCount; i < 4; i++) {
            inv.addItem(GFItems.createStarterRod());
        }
        if (baitCount < 8) {
            inv.addItem(GFItems.createBait(1, 8 - baitCount));
        }

        chest.update();
    }

    public void applyResourcePack(Player player) {
        if (!plugin.getConfig().getBoolean("resourcepack.enabled", true)) return;
        String url = plugin.getConfig().getString("resourcepack.url", "");
        if (url == null || url.isBlank()) return;
        String hashHex = plugin.getConfig().getString("resourcepack.sha1", "");
        String promptText = plugin.getConfig().getString("resourcepack.prompt", "Для корректного отображения рыбы нужен ресурспак GONE Fishing.");
        boolean force = plugin.getConfig().getBoolean("resourcepack.force", false);

        if (hashHex != null && !hashHex.isBlank()) {
            byte[] hash = parseSha1(hashHex.trim());
            if (hash != null) {
                player.sendMessage(Text.info(promptText));
                player.setResourcePack(url, hash, force);
            } else {
                plugin.getLogger().warning("Invalid resourcepack sha1 in config.yml, sending without hash.");
                player.setResourcePack(url, promptText, force);
            }
        } else {
            player.setResourcePack(url, promptText, force);
        }
    }

    private void startFishingSession(Player player, FishHook hook) {
        if (!running || !isInGame(player)) return;
        if (fishingSessions.containsKey(player.getUniqueId())) return;
        if (hook == null || hook.isDead()) return;

        int baitTier = GFItems.getBaitTier(player.getInventory().getItemInOffHand());
        FishSelection selection = rollFishSelection(baitTier);
        if (selection == null) return;

        int maxHeight = plugin.getConfig().getInt("fishing_minigame.max_height", 100);
        int baseBarHeight = plugin.getConfig().getInt("fishing_minigame.base_bar_height", 20);
        int barPerPower = plugin.getConfig().getInt("fishing_minigame.bar_height_per_power", 2);
        int windowMsPerUnit = plugin.getConfig().getInt("fishing_minigame.window_ms_per_unit", 180);
        int barHeight = baseBarHeight + sharedRodPower * barPerPower + (sharedWindowBonusMs / Math.max(1, windowMsPerUnit));
        double lineStrength = Math.min(0.65, Math.max(0.0, sharedRodPower * 0.07));

        FishBehavior behavior = getBehavior(selection.rarity());
        double reductionFactor = Math.max(0.6, 1.0 - (0.07 * sharedPullReduction));
        double topSpeed = behavior.topSpeed() * reductionFactor;
        double upAcc = behavior.upAcceleration() * reductionFactor;
        double downAcc = behavior.downAcceleration() * reductionFactor;

        double bobberUpAccel = plugin.getConfig().getDouble("fishing_minigame.bobber.up_accel", -0.65);
        double bobberDownAccel = plugin.getConfig().getDouble("fishing_minigame.bobber.gravity", 0.55);
        double bobberDrag = plugin.getConfig().getDouble("fishing_minigame.bobber.drag", 0.92);
        double pointsToFinish = plugin.getConfig().getDouble("fishing_minigame.points_to_finish", 100.0);
        int timeLimitTicks = plugin.getConfig().getInt("fishing_minigame.time_limit_ticks", 240);

        GFFishingSession session = new GFFishingSession(
                player.getUniqueId(),
                hook.getUniqueId(),
                selection.rarity(),
                selection.speciesName(),
                selection.weightKg(),
                maxHeight,
                barHeight,
                pointsToFinish,
                lineStrength,
                behavior.idleTimeTicks(),
                topSpeed,
                upAcc,
                downAcc,
                behavior.avgDistance(),
                behavior.moveVariation(),
                bobberUpAccel,
                bobberDownAccel,
                bobberDrag,
                timeLimitTicks
        );
        fishingSessions.put(player.getUniqueId(), session);
        startFishingTickTask();

        if (plugin.getConfig().getBoolean("fishing_minigame.apply_rod_cooldown", true)) {
            player.setCooldown(Material.FISHING_ROD, timeLimitTicks);
        }

        player.sendMessage(Text.info("Клюёт! Удерживайте Shift, чтобы контролировать вываживание."));
        player.playSound(player.getLocation(), Sound.ENTITY_FISHING_BOBBER_SPLASH, 0.8f, 1.2f);
    }

    private void finishFishingSessionSuccess(Player player, GFFishingSession session) {
        double accuracy = session.totalTicks() == 0 ? 0.0 : (double) session.successTicks() / session.totalTicks();
        FishQuality quality = resolveQuality(accuracy);
        int basePoints = getBasePoints(session.rarity());
        int baseValue = getBaseValue(session.rarity());
        double weightMultiplier = getWeightMultiplier(session.weightKg());

        int points = (int) Math.round(basePoints * session.rarity().valueMult * quality.valueMult * sharedPointsMultiplier * weightMultiplier);
        int value = (int) Math.round(baseValue * session.rarity().valueMult * quality.valueMult * sharedValueMultiplier * weightMultiplier);

        FishData data = new FishData(session.rarity(), quality, session.weightKg(), points, value, false, session.speciesName());
        giveToPlayer(player, GFItems.createFishItem(data));

        player.sendMessage(Text.ok("Вы вытащили рыбу: " + session.speciesName() + " (" + session.rarity().ruName + ")."));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
    }

    private void finishFishingSessionFail(Player player, GFFishingSession session) {
        player.sendMessage(Text.bad("Рыба сорвалась!"));
        player.playSound(player.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 0.8f, 0.7f);
    }

    private void cancelFishingSession(Player player, String reason) {
        UUID uuid = player.getUniqueId();
        GFFishingSession session = fishingSessions.remove(uuid);
        if (session == null) return;
        if (reason != null && !reason.isBlank()) {
            player.sendMessage(Text.info(reason));
        }
        clearSessionUi(player);
    }

    private void clearFishingSessions() {
        if (fishingTickTask != null) {
            fishingTickTask.cancel();
            fishingTickTask = null;
        }
        for (UUID uuid : new ArrayList<>(fishingSessions.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                clearSessionUi(player);
            }
        }
        fishingSessions.clear();
    }

    private void clearSessionUi(Player player) {
        org.bukkit.boss.BossBar bar = fishingBossBars.remove(player.getUniqueId());
        if (bar != null) {
            bar.removeAll();
        }
        player.sendActionBar(Component.empty());
        player.setCooldown(Material.FISHING_ROD, 0);
    }

    private void startFishingTickTask() {
        if (fishingTickTask != null) return;
        fishingTickTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (fishingSessions.isEmpty()) {
                    cancel();
                    fishingTickTask = null;
                    return;
                }
                Iterator<Map.Entry<UUID, GFFishingSession>> iterator = fishingSessions.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<UUID, GFFishingSession> entry = iterator.next();
                    UUID uuid = entry.getKey();
                    GFFishingSession session = entry.getValue();
                    Player player = Bukkit.getPlayer(uuid);
                    if (player == null || !player.isOnline()) {
                        iterator.remove();
                        continue;
                    }
                    if (!running || !isInGame(player) || player.isDead()) {
                        clearSessionUi(player);
                        iterator.remove();
                        continue;
                    }
                    ItemStack rod = player.getInventory().getItemInMainHand();
                    if (rod == null || rod.getType() != Material.FISHING_ROD) {
                        cancelFishingSession(player, "Вы перестали держать удочку.");
                        continue;
                    }
                    if (plugin.getConfig().getBoolean("fishing_minigame.only_starter_rod", true)
                            && !GFItems.isStarterRod(rod)) {
                        cancelFishingSession(player, "Вы перестали использовать стартовую удочку.");
                        continue;
                    }
                    Entity hookEntity = session.hookId() == null ? null : Bukkit.getEntity(session.hookId());
                    if (!(hookEntity instanceof FishHook) || hookEntity.isDead()) {
                        cancelFishingSession(player, "Поплавок исчез.");
                        continue;
                    }

                    session.tick(player, player.isSneaking());
                    updateSessionUi(player, session);

                    if (session.isSuccess()) {
                        finishFishingSessionSuccess(player, session);
                        clearSessionUi(player);
                        iterator.remove();
                    } else if (session.isFailed()) {
                        finishFishingSessionFail(player, session);
                        clearSessionUi(player);
                        iterator.remove();
                    }
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void updateSessionUi(Player player, GFFishingSession session) {
        player.sendActionBar(buildActionBar(session));
        if (!plugin.getConfig().getBoolean("fishing_minigame.use_bossbar", false)) return;

        org.bukkit.boss.BossBar bar = fishingBossBars.computeIfAbsent(player.getUniqueId(), id -> {
            org.bukkit.boss.BossBar created = Bukkit.createBossBar("", barColorForRarity(session.rarity()), org.bukkit.boss.BarStyle.SEGMENTED_12);
            created.addPlayer(player);
            return created;
        });
        bar.setTitle("Вываживание: " + session.speciesName() + " (" + session.rarity().ruName + ")");
        bar.setProgress(session.progress01());
    }

    private Component buildActionBar(GFFishingSession session) {
        int width = plugin.getConfig().getInt("fishing_minigame.actionbar_width", ACTIONBAR_WIDTH);
        width = Math.max(14, Math.min(60, width));
        int fishIndex = toIndex(session.fishPos(), session.maxHeight(), width);
        int barStart = toIndex(session.bobberPos(), session.maxHeight(), width);
        int barEnd = toIndex(session.bobberPos() + session.barHeight(), session.maxHeight(), width);
        if (barEnd < barStart) {
            int tmp = barStart;
            barStart = barEnd;
            barEnd = tmp;
        }

        Component bar = BAR_OPEN;
        for (int i = 0; i < width; i++) {
            if (i == fishIndex) {
                bar = bar.append(Component.text(FISH_CHAR, session.rarity().color).decoration(TextDecoration.BOLD, true));
            } else if (i >= barStart && i <= barEnd) {
                bar = bar.append(Component.text(BAR_CHAR, NamedTextColor.GREEN));
            } else {
                bar = bar.append(Component.text(BACKGROUND_CHAR, NamedTextColor.DARK_GRAY));
            }
        }
        int percent = (int) Math.round(session.progress01() * 100);
        Component percentComp = Component.text(" " + percent + "%", session.rarity() == FishRarity.LEGENDARY ? NamedTextColor.GOLD : NamedTextColor.WHITE);
        return bar.append(BAR_CLOSE).append(percentComp);
    }

    private int toIndex(double pos, int maxHeight, int width) {
        double ratio = pos / Math.max(1.0, maxHeight);
        int idx = (int) Math.round(ratio * (width - 1));
        return Math.max(0, Math.min(width - 1, idx));
    }

    private FishSelection rollFishSelection(int baitTier) {
        double nightChance = plugin.getConfig().getDouble("fish_gen.night_pool_chance", 0.25);
        if (isNight && random.nextDouble() < nightChance) {
            List<GFItems.NightFishSpec> nightPool = GFItems.getNightFishPool();
            if (!nightPool.isEmpty()) {
                GFItems.NightFishSpec spec = nightPool.get(random.nextInt(nightPool.size()));
                double weight = randomRange(spec.minWeight(), spec.maxWeight());
                return new FishSelection(spec.rarity(), spec.name(), weight);
            }
        }

        FishRarity rarity = rollRarity(baitTier);
        List<String> pool = GFItems.getSpeciesPool(rarity);
        if (pool.isEmpty()) return null;
        String species = pool.get(random.nextInt(pool.size()));
        double[] range = getWeightRange(rarity);
        double weight = randomRange(range[0], range[1]);
        return new FishSelection(rarity, species, weight);
    }

    private FishRarity rollRarity(int baitTier) {
        String basePath = "fish_gen.rarity_weights." + (isNight ? "night" : "day") + ".";
        double common = plugin.getConfig().getDouble(basePath + "common", 65.0);
        double uncommon = plugin.getConfig().getDouble(basePath + "uncommon", 25.0);
        double rare = plugin.getConfig().getDouble(basePath + "rare", 8.0);
        double epic = plugin.getConfig().getDouble(basePath + "epic", 1.5);
        double legendary = plugin.getConfig().getDouble(basePath + "legendary", 0.5);

        FishRarity rolled = rollByWeights(common, uncommon, rare, epic, legendary);

        int shiftRolls = sharedRodLuck;
        if (baitTier == 2) {
            shiftRolls += plugin.getConfig().getInt("fish_gen.bait_shift_rolls.tier2", 1);
        } else if (baitTier >= 3) {
            shiftRolls += plugin.getConfig().getInt("fish_gen.bait_shift_rolls.tier3", 2);
        }
        double shiftChance = plugin.getConfig().getDouble("fish_gen.luck_shift_chance_per_level", 0.02);
        for (int i = 0; i < shiftRolls; i++) {
            if (random.nextDouble() < shiftChance) {
                rolled = nextRarity(rolled);
            }
        }
        return rolled;
    }

    private FishRarity rollByWeights(double common, double uncommon, double rare, double epic, double legendary) {
        double total = common + uncommon + rare + epic + legendary;
        double roll = random.nextDouble() * total;
        if ((roll -= common) < 0) return FishRarity.COMMON;
        if ((roll -= uncommon) < 0) return FishRarity.UNCOMMON;
        if ((roll -= rare) < 0) return FishRarity.RARE;
        if ((roll -= epic) < 0) return FishRarity.EPIC;
        return FishRarity.LEGENDARY;
    }

    private FishRarity nextRarity(FishRarity rarity) {
        return switch (rarity) {
            case COMMON -> FishRarity.UNCOMMON;
            case UNCOMMON -> FishRarity.RARE;
            case RARE -> FishRarity.EPIC;
            case EPIC -> FishRarity.LEGENDARY;
            case LEGENDARY -> FishRarity.LEGENDARY;
        };
    }

    private double[] getWeightRange(FishRarity rarity) {
        String key = rarityKey(rarity);
        double min = plugin.getConfig().getDouble("fish_gen.weight_ranges." + key + ".min", 0.2);
        double max = plugin.getConfig().getDouble("fish_gen.weight_ranges." + key + ".max", 2.0);
        if (max < min) max = min;
        return new double[]{min, max};
    }

    private int getBasePoints(FishRarity rarity) {
        return plugin.getConfig().getInt("fish_gen.base_points." + rarityKey(rarity), 4);
    }

    private int getBaseValue(FishRarity rarity) {
        return plugin.getConfig().getInt("fish_gen.base_value." + rarityKey(rarity), 20);
    }

    private double getWeightMultiplier(double weight) {
        double base = plugin.getConfig().getDouble("fish_gen.weight_value_multiplier.base", 0.85);
        double divisor = plugin.getConfig().getDouble("fish_gen.weight_value_multiplier.divisor", 20.0);
        return base + (weight / Math.max(1.0, divisor));
    }

    private String rarityKey(FishRarity rarity) {
        return switch (rarity) {
            case COMMON -> "common";
            case UNCOMMON -> "uncommon";
            case RARE -> "rare";
            case EPIC -> "epic";
            case LEGENDARY -> "legendary";
        };
    }

    private FishBehavior getBehavior(FishRarity rarity) {
        String key = "fishing_minigame.behavior." + rarityKey(rarity) + ".";
        int idle = plugin.getConfig().getInt(key + "idle", 10);
        double topSpeed = plugin.getConfig().getDouble(key + "top_speed", 2.2);
        double upAcc = plugin.getConfig().getDouble(key + "up_accel", 0.35);
        double downAcc = plugin.getConfig().getDouble(key + "down_accel", 0.4);
        int avgDist = plugin.getConfig().getInt(key + "avg_distance", 26);
        int variation = plugin.getConfig().getInt(key + "move_variation", 14);
        return new FishBehavior(idle, topSpeed, upAcc, downAcc, avgDist, variation);
    }

    private FishQuality resolveQuality(double accuracy) {
        if (accuracy >= 0.88) return FishQuality.IRIDIUM;
        if (accuracy >= 0.72) return FishQuality.GOLD;
        if (accuracy >= 0.55) return FishQuality.SILVER;
        return FishQuality.NORMAL;
    }

    private double randomRange(double min, double max) {
        if (max < min) return min;
        return min + (random.nextDouble() * (max - min));
    }

    private org.bukkit.boss.BarColor barColorForRarity(FishRarity rarity) {
        return switch (rarity) {
            case COMMON -> org.bukkit.boss.BarColor.GREEN;
            case UNCOMMON -> org.bukkit.boss.BarColor.BLUE;
            case RARE -> org.bukkit.boss.BarColor.PURPLE;
            case EPIC -> org.bukkit.boss.BarColor.RED;
            case LEGENDARY -> org.bukkit.boss.BarColor.YELLOW;
        };
    }

    private byte[] parseSha1(String hex) {
        if (hex == null) return null;
        String cleaned = hex.replaceAll("\\s+", "");
        if (cleaned.length() != 40) return null;
        byte[] out = new byte[20];
        for (int i = 0; i < out.length; i++) {
            int idx = i * 2;
            try {
                out[i] = (byte) Integer.parseInt(cleaned.substring(idx, idx + 2), 16);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }

    private void logFishModelInfo() {
        int common = GFItems.getSpeciesPool(FishRarity.COMMON).size();
        int uncommon = GFItems.getSpeciesPool(FishRarity.UNCOMMON).size();
        int rare = GFItems.getSpeciesPool(FishRarity.RARE).size();
        int epic = GFItems.getSpeciesPool(FishRarity.EPIC).size();
        int legendary = GFItems.getSpeciesPool(FishRarity.LEGENDARY).size();
        int night = GFItems.getNightFishPool().size();
        int total = common + uncommon + rare + epic + legendary + night;
        int maxRaw = 1000 + total;
        int maxCooked = 2000 + total;
        if (total == 49 && maxRaw == 1049 && maxCooked == 2049) {
            plugin.getLogger().info("GF fish models: " + total + " видов, max raw=" + maxRaw + ", max cooked=" + maxCooked + ".");
        } else {
            plugin.getLogger().warning("GF fish models mismatch: total=" + total + ", max raw=" + maxRaw + ", max cooked=" + maxCooked + ".");
        }
    }

    private record FishSelection(FishRarity rarity, String speciesName, double weightKg) {}

    private record FishBehavior(int idleTimeTicks, double topSpeed, double upAcceleration, double downAcceleration, int avgDistance, int moveVariation) {}

}
