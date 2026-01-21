package me.gonecasino.gf;

import me.gonecasino.GoneCasinoPlugin;
import me.gonecasino.util.LocUtil;
import me.gonecasino.util.Keys;
import me.gonecasino.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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

    // fishing minigame
    private static final DecimalFormat DF = new DecimalFormat("0.00");
    private final Map<UUID, FishingChallenge> challenges = new HashMap<>();
    private BukkitTask fishingTask;
    private long tickCounter = 0L;

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
    private static final List<LootEntry> TREASURE_TABLE = List.of(
            new LootEntry(Material.PRISMARINE_SHARD, 1, 3, 5.0),
            new LootEntry(Material.GOLD_NUGGET, 2, 6, 5.5),
            new LootEntry(Material.EMERALD, 1, 2, 2.2),
            new LootEntry(Material.NAUTILUS_SHELL, 1, 1, 1.2),
            new LootEntry(Material.HEART_OF_THE_SEA, 1, 1, 0.4)
    );
    private static final List<LootEntry> TRASH_TABLE = List.of(
            new LootEntry(Material.STICK, 1, 3, 4.5),
            new LootEntry(Material.ROTTEN_FLESH, 1, 2, 3.5),
            new LootEntry(Material.STRING, 1, 3, 3.0),
            new LootEntry(Material.BONE, 1, 2, 2.4),
            new LootEntry(Material.KELP, 1, 3, 2.2),
            new LootEntry(Material.LEATHER_BOOTS, 1, 1, 0.6)
    );

    private Component fishingHud(FishingChallenge ch, boolean inZone) {
        int length = plugin.getConfig().getInt("fishing.minigame.bar_length", 30);
        length = Math.max(12, Math.min(40, length));
        int fishIndex = (int) Math.round(ch.fishPos * (length - 1));
        int playerIndex = (int) Math.round(ch.playerPos * (length - 1));
        int zoneRadius = (int) Math.round((ch.zoneSize * length) / 2.0);
        Component bar = Component.empty();
        for (int i = 0; i < length; i++) {
            boolean inFishZone = Math.abs(i - fishIndex) <= zoneRadius;
            if (i == playerIndex) {
                NamedTextColor color = inFishZone ? NamedTextColor.GOLD : NamedTextColor.RED;
                bar = bar.append(Component.text("▲", color));
            } else {
                NamedTextColor color = inFishZone ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY;
                bar = bar.append(Component.text("█", color));
            }
        }
        int percent = (int) Math.round(ch.progress * 100.0);
        NamedTextColor stateColor = inZone ? NamedTextColor.GREEN : NamedTextColor.RED;
        return Component.text("🎣 ", NamedTextColor.AQUA)
                .append(bar)
                .append(Component.text(" " + percent + "%", stateColor));
    }

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
            }
        }
        requester.sendMessage(Text.ok("Подключено игроков: " + joined));
    }

    public void leave(Player p) {
        players.remove(p.getUniqueId());
        challenges.remove(p.getUniqueId());
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
            if (p != null) bossBar.addPlayer(p);
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
        challenges.clear();

        despawnTrader();
        clearNightMonsters();

        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }

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
        challenges.clear();

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

        fishingTask = new BukkitRunnable() {
            @Override
            public void run() {
                tickCounter++;
                tickFishingChallenges();
            }
        }.runTaskTimer(plugin, 1L, 1L);
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
        r.addPotionEffect(new PotionEffect(PotionEffectType.INCREASE_DAMAGE, 20 * 120, 0));
        r.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, 20 * 120, 1));
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
                        p.spawnParticle(Particle.SMOKE_LARGE, p.getLocation().add(0, 0.6, 0), 16, 0.4, 0.5, 0.4, 0.01);
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

        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            ItemStack it = event.getItem();
            if (it != null && it.getType() == Material.FISHING_ROD) {
                FishingChallenge ch = challenges.get(p.getUniqueId());
                if (ch != null && !ch.completed && !ch.failed) {
                    long holdTicks = plugin.getConfig().getLong("fishing.input_hold_ticks", 6L);
                    ch.inputUntilTick = tickCounter + Math.max(1L, holdTicks);
                }
            }
        }
    }

    @EventHandler
    public void onFish(PlayerFishEvent event) {
        Player p = event.getPlayer();
        if (event.getState() == PlayerFishEvent.State.BITE) {
            event.setCancelled(true);
            FishingChallenge existing = challenges.get(p.getUniqueId());
            if (existing != null && !existing.completed && !existing.failed) return;
            startFishingChallenge(p);
            return;
        }

        FishingChallenge ch = challenges.get(p.getUniqueId());
        if (ch != null && (event.getState() == PlayerFishEvent.State.CAUGHT_FISH
                || event.getState() == PlayerFishEvent.State.REEL_IN)) {
            event.setCancelled(true);
            if (event.getCaught() != null) event.getCaught().remove();
        }

        if (event.getState() == PlayerFishEvent.State.FAILED_ATTEMPT
                || event.getState() == PlayerFishEvent.State.IN_GROUND
                || event.getState() == PlayerFishEvent.State.CAUGHT_ENTITY) {
            if (ch != null) {
                ch.failed = true;
                challenges.remove(p.getUniqueId());
                p.sendMessage(Text.bad("Рыба сорвалась..."));
                p.playSound(p.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 0.6f, 0.6f);
            }
        }
    }

    private void startFishingChallenge(Player p) {
        ItemStack rod = p.getInventory().getItemInMainHand();
        if (rod.getType() != Material.FISHING_ROD) rod = p.getInventory().getItemInOffHand();

        int rodPower = GFItems.getRodPower(rod) + sharedRodPower;
        int rodLuck = GFItems.getRodLuck(rod) + sharedRodLuck;

        ItemStack bait = p.getInventory().getItemInOffHand();
        int baitTier = GFItems.getBaitTier(bait);

        if (baitTier > 0) {
            bait.setAmount(bait.getAmount() - 1);
        }

        FishData fish = rollFish(baitTier, rodLuck, isNight);
        FishProfile profile = buildFishProfile(fish, p.getLocation(), baitTier, rodPower, rodLuck, p.getLevel());

        double zoneBase = plugin.getConfig().getDouble("fishing.minigame.zone_base", 0.32);
        double zoneMin = plugin.getConfig().getDouble("fishing.minigame.zone_min", 0.12);
        double zoneMax = plugin.getConfig().getDouble("fishing.minigame.zone_max", 0.46);
        double zone = zoneBase - profile.difficulty * 0.08 + baitTier * 0.02 + rodPower * 0.01 + sharedPullReduction * 0.02;
        zone = Math.max(zoneMin, Math.min(zoneMax, zone));

        double progressGain = plugin.getConfig().getDouble("fishing.minigame.progress_gain", 0.018);
        double progressLoss = plugin.getConfig().getDouble("fishing.minigame.progress_loss", 0.024);
        progressGain += sharedPullReduction * 0.002;
        progressGain += sharedPullCooldownReductionMs / 20000.0;
        progressLoss = Math.max(0.008, progressLoss - sharedPullCooldownReductionMs / 30000.0);

        int timeLimit = plugin.getConfig().getInt("fishing.minigame.time_limit_ticks", 420);
        timeLimit += (int) Math.round(sharedWindowBonusMs / 1000.0 * 20.0);
        int failZeroTicks = plugin.getConfig().getInt("fishing.minigame.fail_zero_ticks", 40);
        int hudInterval = plugin.getConfig().getInt("fishing.minigame.hud_interval_ticks", 4);

        int minTarget = plugin.getConfig().getInt("fishing.minigame.target_change_min_ticks", 8);
        int maxTarget = plugin.getConfig().getInt("fishing.minigame.target_change_max_ticks", 24);
        long nextTargetTick = tickCounter + minTarget + random.nextInt(Math.max(1, maxTarget - minTarget + 1));
        double fishPos = 0.15 + random.nextDouble() * 0.7;

        FishingChallenge ch = new FishingChallenge(
                fish,
                profile,
                baitTier,
                rodPower,
                rodLuck,
                zone,
                progressGain,
                progressLoss,
                timeLimit,
                failZeroTicks,
                tickCounter,
                hudInterval,
                fishPos,
                fishPos,
                nextTargetTick
        );
        challenges.put(p.getUniqueId(), ch);

        p.showTitle(Title.title(
                Component.text("🎣 Поединок с рыбой!", NamedTextColor.AQUA),
                Component.text("Удерживай ▲ в зоне рыбы", NamedTextColor.YELLOW),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(900), Duration.ofMillis(200))
        ));
        p.playSound(p.getLocation(), Sound.ENTITY_FISHING_BOBBER_SPLASH, 0.8f, 1.1f);
    }

    private void tickFishingChallenges() {
        if (challenges.isEmpty()) return;
        Iterator<Map.Entry<UUID, FishingChallenge>> iterator = challenges.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, FishingChallenge> entry = iterator.next();
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p == null || !p.isOnline()) {
                iterator.remove();
                continue;
            }

            FishingChallenge ch = entry.getValue();
            if (ch.completed || ch.failed) {
                iterator.remove();
                continue;
            }

            long elapsed = tickCounter - ch.startTick;
            if (elapsed > ch.timeLimitTicks) {
                ch.failed = true;
                iterator.remove();
                p.sendMessage(Text.bad("Рыба сорвалась..."));
                p.playSound(p.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 0.6f, 0.6f);
                continue;
            }

            boolean inputHeld = tickCounter <= ch.inputUntilTick;
            updatePlayerBar(ch, inputHeld);
            updateFishMotion(ch);

            boolean inZone = Math.abs(ch.playerPos - ch.fishPos) <= ch.zoneSize / 2.0;
            if (inZone) {
                ch.progress = Math.min(1.0, ch.progress + ch.progressGain);
            } else {
                ch.progress = Math.max(0.0, ch.progress - ch.progressLoss);
                ch.missed = true;
            }

            if (ch.progress <= 0.0) {
                ch.zeroTicks++;
            } else {
                ch.zeroTicks = 0;
            }

            if (ch.zeroTicks > ch.failZeroTicks) {
                ch.failed = true;
                iterator.remove();
                p.sendMessage(Text.bad("Рыба сорвалась..."));
                p.playSound(p.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 0.6f, 0.6f);
                continue;
            }

            if (tickCounter % ch.hudIntervalTicks == 0) {
                p.sendActionBar(fishingHud(ch, inZone));
            }

            if (ch.progress >= 1.0) {
                ch.completed = true;
                iterator.remove();
                finishFishingChallenge(p, ch);
            }
        }
    }

    private void updatePlayerBar(FishingChallenge ch, boolean inputHeld) {
        double accelUp = plugin.getConfig().getDouble("fishing.minigame.player_accel_up", 0.035);
        double accelDown = plugin.getConfig().getDouble("fishing.minigame.player_accel_down", 0.03);
        double drag = plugin.getConfig().getDouble("fishing.minigame.player_drag", 0.88);
        double maxSpeed = plugin.getConfig().getDouble("fishing.minigame.player_max_speed", 0.065);

        double accel = inputHeld ? accelUp : -accelDown;
        ch.playerVel += accel;
        ch.playerVel *= drag;
        ch.playerVel = Math.max(-maxSpeed, Math.min(maxSpeed, ch.playerVel));
        ch.playerPos += ch.playerVel;

        if (ch.playerPos < 0.0) {
            ch.playerPos = 0.0;
            ch.playerVel = 0.0;
        } else if (ch.playerPos > 1.0) {
            ch.playerPos = 1.0;
            ch.playerVel = 0.0;
        }
    }

    private void updateFishMotion(FishingChallenge ch) {
        if (tickCounter >= ch.nextTargetTick) {
            ch.fishTarget = 0.1 + random.nextDouble() * 0.8;
            int minTicks = plugin.getConfig().getInt("fishing.minigame.target_change_min_ticks", 8);
            int maxTicks = plugin.getConfig().getInt("fishing.minigame.target_change_max_ticks", 24);
            ch.nextTargetTick = tickCounter + minTicks + random.nextInt(Math.max(1, maxTicks - minTicks + 1));

            double burstChance = plugin.getConfig().getDouble("fishing.minigame.fish_burst_chance", 0.05);
            double burstForce = plugin.getConfig().getDouble("fishing.minigame.fish_burst_force", 0.08);
            if (random.nextDouble() < burstChance * ch.profile.aggression) {
                double dir = random.nextBoolean() ? 1.0 : -1.0;
                ch.fishVel += dir * burstForce * ch.profile.aggression;
            }
        }

        double baseSpeed = plugin.getConfig().getDouble("fishing.minigame.fish_base_speed", 0.012);
        double variance = plugin.getConfig().getDouble("fishing.minigame.fish_speed_variance", 0.012);
        double aggressionFactor = 0.6 + ch.profile.aggression * 0.4;
        double maxSpeed = baseSpeed + variance * aggressionFactor;
        double inertia = plugin.getConfig().getDouble("fishing.minigame.fish_inertia", 0.9);
        double jerk = plugin.getConfig().getDouble("fishing.minigame.fish_jerk", 0.01);
        double steerStrength = plugin.getConfig().getDouble("fishing.minigame.fish_steer_strength", 0.045);

        double steer = (ch.fishTarget - ch.fishPos) * steerStrength * (0.8 + ch.profile.aggression * 0.2);
        ch.fishVel += steer + (random.nextDouble() * 2 - 1) * jerk * ch.profile.jitter;
        ch.fishVel *= inertia;
        ch.fishVel = Math.max(-maxSpeed, Math.min(maxSpeed, ch.fishVel));
        ch.fishPos += ch.fishVel;

        if (ch.fishPos < 0.0) {
            ch.fishPos = 0.0;
            ch.fishVel = Math.abs(ch.fishVel) * 0.6;
        } else if (ch.fishPos > 1.0) {
            ch.fishPos = 1.0;
            ch.fishVel = -Math.abs(ch.fishVel) * 0.6;
        }
    }

    private void finishFishingChallenge(Player p, FishingChallenge ch) {
        boolean perfect = !ch.missed;
        FishingLoot loot = rollFishingLoot(p, ch, perfect);

        if (loot.mainItem != null) {
            giveToPlayer(p, loot.mainItem);
        }
        for (ItemStack extra : loot.bonusItems) {
            giveToPlayer(p, extra);
        }

        if (loot.isTrash) {
            p.sendMessage(Text.info("Вы вытащили мусор: " + loot.mainItem.getType().name().toLowerCase(Locale.ROOT)));
            p.playSound(p.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 0.7f, 0.7f);
        } else {
            FishData fish = loot.fish;
            p.sendMessage(Component.text("🎉 Улов: ", NamedTextColor.YELLOW)
                    .append(Component.text(fish.speciesName(), fish.rarity().color))
                    .append(Component.text(" • " + fish.quality().ruName + " • Вес: " + DF.format(fish.weightKg()) + "кг • Очки: " + fish.points(), NamedTextColor.GRAY))
            );
            forEachGamePlayer(pp -> {
                if (pp.getUniqueId().equals(p.getUniqueId())) return;
                pp.sendMessage(Component.text("🌊 " + p.getName() + " поймал: ", NamedTextColor.AQUA)
                        .append(Component.text(fish.speciesName(), fish.rarity().color))
                        .append(Component.text(" • " + fish.quality().ruName + " • Вес: " + DF.format(fish.weightKg()) + "кг • Очки: " + fish.points(), NamedTextColor.GRAY))
                );
            });
            p.playSound(p.getLocation(), Sound.ENTITY_FISH_SWIM, 0.8f, 1.2f);
        }

        if (loot.treasureItem != null) {
            p.sendMessage(Text.ok("Сокровища: +" + loot.treasureItem.getAmount() + " " + loot.treasureItem.getType().name().toLowerCase(Locale.ROOT)));
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.9f, 1.4f);
        }

        if (perfect && !loot.isTrash) {
            p.sendMessage(Text.ok("Идеальный улов! Бонус к награде."));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.6f);
        }

        p.spawnParticle(Particle.SPLASH, p.getLocation().add(0, 0.4, 0), 12, 0.35, 0.2, 0.35, 0.0);
    }

    private void giveToPlayer(Player p, ItemStack item) {
        Map<Integer, ItemStack> leftover = p.getInventory().addItem(item);
        if (!leftover.isEmpty()) {
            for (ItemStack it : leftover.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), it);
            }
        }
    }

    private FishingLoot rollFishingLoot(Player p, FishingChallenge ch, boolean perfect) {
        double treasureChance = plugin.getConfig().getDouble("fishing.loot.treasure_chance", 0.08);
        double trashChance = plugin.getConfig().getDouble("fishing.loot.trash_chance", 0.07);
        double perfectTreasure = plugin.getConfig().getDouble("fishing.loot.perfect_treasure_bonus", 0.08);

        treasureChance += ch.rodLuck * 0.012 + ch.baitTier * 0.02 + (perfect ? perfectTreasure : 0.0);
        treasureChance += ch.fish.rarity().ordinal() * 0.015;
        treasureChance = Math.min(0.55, Math.max(0.0, treasureChance));

        trashChance -= ch.rodLuck * 0.01 + ch.baitTier * 0.015;
        trashChance += ch.profile.difficulty * 0.04;
        trashChance = Math.min(0.45, Math.max(0.02, trashChance));

        ItemStack treasure = null;
        ItemStack mainItem;
        FishData fish = null;
        boolean isTrash = false;

        if (random.nextDouble() < trashChance) {
            mainItem = rollLootTable(TRASH_TABLE);
            isTrash = true;
        } else {
            FishQuality quality = rollQuality(p, ch, perfect);
            fish = applyQualityAndBonuses(ch.fish, quality, perfect);
            mainItem = GFItems.createFishItem(fish);
        }

        if (!isTrash && random.nextDouble() < treasureChance) {
            treasure = rollLootTable(TREASURE_TABLE);
        }

        List<ItemStack> bonus = new ArrayList<>();
        if (treasure != null) {
            bonus.add(treasure.clone());
        }

        return new FishingLoot(mainItem, fish, treasure, bonus, isTrash);
    }

    private FishData applyQualityAndBonuses(FishData fish, FishQuality quality, boolean perfect) {
        double perfectBonus = plugin.getConfig().getDouble("fishing.perfect_bonus_percent", 15) / 100.0;
        double qualityMult = quality.valueMult;

        int points = (int) Math.round(fish.points() * sharedPointsMultiplier * qualityMult);
        int value = (int) Math.round(fish.value() * sharedValueMultiplier * qualityMult);
        if (perfect) {
            points = (int) Math.round(points * (1.0 + perfectBonus));
            value = (int) Math.round(value * (1.0 + perfectBonus));
        }

        return new FishData(fish.rarity(), quality, fish.weightKg(), points, value, fish.cooked(), fish.speciesName());
    }

    private FishQuality rollQuality(Player p, FishingChallenge ch, boolean perfect) {
        double baseSilver = plugin.getConfig().getDouble("fishing.loot.quality_base_silver", 0.25);
        double baseGold = plugin.getConfig().getDouble("fishing.loot.quality_base_gold", 0.12);
        double baseIridium = plugin.getConfig().getDouble("fishing.loot.quality_base_iridium", 0.04);
        double baitBonus = plugin.getConfig().getDouble("fishing.loot.quality_bait_bonus", 0.05);
        double luckBonus = plugin.getConfig().getDouble("fishing.loot.quality_luck_bonus", 0.03);
        double skillBonus = plugin.getConfig().getDouble("fishing.loot.quality_skill_bonus", 0.004);
        double perfectBonus = plugin.getConfig().getDouble("fishing.loot.quality_perfect_bonus", 0.15);
        double difficultyPenalty = plugin.getConfig().getDouble("fishing.loot.quality_difficulty_penalty", 0.05);

        double skill = Math.min(1.0, p.getLevel() * skillBonus);
        double qualityScore = ch.baitTier * baitBonus + ch.rodLuck * luckBonus + skill;
        if (perfect) {
            qualityScore += perfectBonus;
        }
        qualityScore -= ch.profile.difficulty * difficultyPenalty;
        qualityScore = Math.max(0.0, Math.min(0.65, qualityScore));

        double iridiumChance = baseIridium + qualityScore * 0.2;
        double goldChance = baseGold + qualityScore * 0.35;
        double silverChance = baseSilver + qualityScore * 0.45;

        double roll = random.nextDouble();
        if (roll < iridiumChance) return FishQuality.IRIDIUM;
        if (roll < iridiumChance + goldChance) return FishQuality.GOLD;
        if (roll < iridiumChance + goldChance + silverChance) return FishQuality.SILVER;
        return FishQuality.NORMAL;
    }

    private FishProfile buildFishProfile(FishData fish, Location loc, int baitTier, int rodPower, int rodLuck, int skillLevel) {
        double difficulty = switch (fish.rarity()) {
            case COMMON -> 0.7;
            case UNCOMMON -> 0.95;
            case RARE -> 1.2;
            case EPIC -> 1.5;
            case LEGENDARY -> 1.9;
        };

        World world = loc.getWorld();
        boolean raining = world != null && world.hasStorm();
        boolean thunder = world != null && world.isThundering();
        boolean nightTime = world != null && world.getTime() >= 13000;
        Biome biome = world != null ? world.getBiome(loc) : Biome.PLAINS;

        if (raining) difficulty += 0.1;
        if (thunder) difficulty += 0.15;
        if (nightTime) difficulty += 0.1;

        if (biome == Biome.RIVER || biome == Biome.OCEAN || biome == Biome.DEEP_OCEAN) {
            difficulty -= 0.08;
        } else if (biome == Biome.SWAMP || biome == Biome.MANGROVE_SWAMP) {
            difficulty += 0.12;
        }

        difficulty -= baitTier * 0.06;
        difficulty -= rodPower * 0.05;
        difficulty -= Math.min(0.2, rodLuck * 0.02);
        difficulty -= Math.min(0.25, skillLevel * 0.01);
        difficulty = Math.max(0.55, Math.min(2.3, difficulty));

        double aggression = Math.max(0.6, Math.min(2.0, difficulty));
        double jitter = 0.8 + (random.nextDouble() * 0.4);
        return new FishProfile(difficulty, aggression, jitter);
    }

    private ItemStack rollLootTable(List<LootEntry> table) {
        double total = 0.0;
        for (LootEntry entry : table) {
            total += entry.weight();
        }
        double pick = random.nextDouble() * total;
        double runningWeight = 0.0;
        for (LootEntry entry : table) {
            runningWeight += entry.weight();
            if (pick <= runningWeight) {
                int amount = entry.minAmount();
                if (entry.maxAmount() > entry.minAmount()) {
                    amount += random.nextInt(entry.maxAmount() - entry.minAmount() + 1);
                }
                return new ItemStack(entry.material(), amount);
            }
        }
        LootEntry fallback = table.get(0);
        return new ItemStack(fallback.material(), fallback.minAmount());
    }

    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!running || !isNight) return;
        if (!(event.getEntity() instanceof Monster m)) return;
        // buff monsters at night
        m.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 120, 0));
        if (random.nextDouble() < 0.25) {
            m.addPotionEffect(new PotionEffect(PotionEffectType.INCREASE_DAMAGE, 20 * 120, 0));
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
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (bossBar == null) return;
        bossBar.removePlayer(event.getPlayer());
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            stopLakeMonster();
        }
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

    private FishData rollFish(int baitTier, int rodLuck, boolean night) {
        int requiredTier = plugin.getConfig().getInt("night_fish.required_bait_tier", 3);
        double nightChance = plugin.getConfig().getDouble("night_fish.chance", 0.18);
        if (night && baitTier >= requiredTier && random.nextDouble() < nightChance) {
            return rollNightFish(rodLuck);
        }

        FishRarity rarity = rollRarity(baitTier, rodLuck, night);

        double minW = switch (rarity) {
            case COMMON -> 0.4;
            case UNCOMMON -> 1.0;
            case RARE -> 2.5;
            case EPIC -> 5.0;
            case LEGENDARY -> 10.0;
        };
        double maxW = switch (rarity) {
            case COMMON -> 2.2;
            case UNCOMMON -> 4.5;
            case RARE -> 7.5;
            case EPIC -> 12.0;
            case LEGENDARY -> 20.0;
        };

        double weight = minW + random.nextDouble() * (maxW - minW);
        // night slightly heavier
        if (night) weight *= 1.08;

        // points/value
        int points = (int) Math.round((weight * 5 + (rarity.ordinal() * 8)));
        int value = (int) Math.round(points * rarity.valueMult);

        String species = rollSpecies(rarity);

        return new FishData(rarity, FishQuality.NORMAL, weight, points, value, false, species);
    }

    private FishRarity rollRarity(int baitTier, int rodLuck, boolean night) {
        int tier = Math.max(0, Math.min(3, baitTier));
        double[][] weights = {
                {70, 20, 7, 2.5, 0.5},
                {60, 25, 10, 4, 1},
                {45, 30, 15, 7, 3},
                {30, 30, 20, 12, 8}
        };
        double[] base = weights[tier].clone();

        double luckBoost = rodLuck * 1.6 + (night ? 3.5 : 0);
        base[2] += luckBoost * 0.6;
        base[3] += luckBoost * 0.35;
        base[4] += luckBoost * 0.2;
        base[0] = Math.max(12, base[0] - luckBoost * 0.8);
        base[1] = Math.max(8, base[1] - luckBoost * 0.3);

        double total = 0;
        for (double v : base) total += v;
        double roll = random.nextDouble() * total;
        double acc = 0;
        for (int i = 0; i < base.length; i++) {
            acc += base[i];
            if (roll <= acc) {
                return FishRarity.values()[i];
            }
        }
        return FishRarity.COMMON;
    }

    private String rollSpecies(FishRarity rarity) {
        List<String> pool = GFItems.getSpeciesPool(rarity);
        return pool.get(random.nextInt(pool.size()));
    }

    private FishData rollNightFish(int rodLuck) {
        double bonus = plugin.getConfig().getDouble("night_fish.points_bonus", 0.25);
        List<GFItems.NightFishSpec> pool = GFItems.getNightFishPool();
        int idx = Math.min(pool.size() - 1, Math.max(0, random.nextInt(pool.size()) + Math.min(2, rodLuck / 2)));
        GFItems.NightFishSpec pick = pool.get(idx);

        double weight = pick.minWeight() + random.nextDouble() * (pick.maxWeight() - pick.minWeight());
        int basePoints = (int) Math.round((weight * 5 + (pick.rarity().ordinal() * 8)) * (1.0 + bonus));
        int points = (int) Math.round(basePoints);
        int value = (int) Math.round(points * pick.rarity().valueMult);
        return new FishData(pick.rarity(), FishQuality.NORMAL, weight, points, value, false, pick.name());
    }

    private record FishingLoot(ItemStack mainItem, FishData fish, ItemStack treasureItem, List<ItemStack> bonusItems, boolean isTrash) {}

    private record LootEntry(Material material, int minAmount, int maxAmount, double weight) {}

    private record FishProfile(double difficulty, double aggression, double jitter) {}

    private static final class FishingChallenge {
        final FishData fish;
        final FishProfile profile;
        final int baitTier;
        final int rodPower;
        final int rodLuck;
        final double zoneSize;
        final double progressGain;
        final double progressLoss;
        final int timeLimitTicks;
        final int failZeroTicks;
        final int hudIntervalTicks;
        final long startTick;
        long inputUntilTick;
        long nextTargetTick;
        double progress;
        double playerPos;
        double playerVel;
        double fishPos;
        double fishVel;
        double fishTarget;
        int zeroTicks;
        boolean missed;
        boolean completed;
        boolean failed;

        FishingChallenge(
                FishData fish,
                FishProfile profile,
                int baitTier,
                int rodPower,
                int rodLuck,
                double zoneSize,
                double progressGain,
                double progressLoss,
                int timeLimitTicks,
                int failZeroTicks,
                long startTick,
                int hudIntervalTicks,
                double fishPos,
                double fishTarget,
                long nextTargetTick
        ) {
            this.fish = fish;
            this.profile = profile;
            this.baitTier = baitTier;
            this.rodPower = rodPower;
            this.rodLuck = rodLuck;
            this.zoneSize = zoneSize;
            this.progressGain = progressGain;
            this.progressLoss = progressLoss;
            this.timeLimitTicks = timeLimitTicks;
            this.failZeroTicks = failZeroTicks;
            this.startTick = startTick;
            this.hudIntervalTicks = Math.max(1, hudIntervalTicks);
            this.fishPos = fishPos;
            this.fishTarget = fishTarget;
            this.nextTargetTick = nextTargetTick;
            this.playerPos = 0.5;
            this.playerVel = 0.0;
            this.fishVel = 0.0;
            this.progress = 0.0;
            this.zeroTicks = 0;
            this.missed = false;
            this.completed = false;
            this.failed = false;
        }
    }
}
