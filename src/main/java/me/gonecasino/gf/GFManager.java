package me.gonecasino.gf;

import me.gonecasino.GoneCasinoPlugin;
import me.gonecasino.util.LocUtil;
import me.gonecasino.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
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

    private org.bukkit.boss.BossBar bossBar;

    // trader
    private UUID traderUuid;
    private static final String TRADER_TITLE = "Странный торговец";
    private static final String SHOP_TITLE = "Торговец у Алтаря";

    // fishing minigame
    private static final DecimalFormat DF = new DecimalFormat("0.00");
    private final Map<UUID, FishingChallenge> challenges = new HashMap<>();

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

        if (altarBlock != null && altarBlock.getWorld() != null) {
            int ox = plugin.getConfig().getInt("house.offset_x", 8);
            int oz = plugin.getConfig().getInt("house.offset_z", 0);
            this.houseInfo = HouseBuilder.computeInfo(altarBlock.getWorld(), altarBlock, ox, oz);
        }

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

        save();

        // (re)spawn trader if currently day and running
        if (running && !isNight) {
            spawnTrader();
        }
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
        if (players.size() >= 4) {
            p.sendMessage(Text.bad("Лимит игроков: 4"));
            return;
        }
        players.add(p.getUniqueId());
        p.sendMessage(Text.ok("Вы вошли в GONE Fishing. Игроков: " + players.size() + "/4"));
        if (bossBar != null) bossBar.addPlayer(p);
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

    public void status(Player p) {
        p.sendMessage(Text.info("GONE Fishing: " + (running ? "ON" : "OFF")));
        p.sendMessage(Text.info("Игроков: " + players.size() + "/4"));
        if (running) {
            p.sendMessage(Text.info("День: " + day + (isNight ? " (ночь)" : " (день)")));
            p.sendMessage(Text.info("Квота: " + quotaProgress + "/" + quotaRequired + (quotaMet ? " (закрыто)" : "")));
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

    private void clearNightMonsters() {
        if (monsterTask != null) {
            monsterTask.cancel();
            monsterTask = null;
        }
        if (lakeMonsterUuid != null) {
            Entity e = Bukkit.getEntity(lakeMonsterUuid);
            if (e != null) e.remove();
            lakeMonsterUuid = null;
        }
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

        traderUuid = v.getUniqueId();
    }

    private void despawnTrader() {
        if (traderUuid == null) return;
        Entity e = Bukkit.getEntity(traderUuid);
        if (e != null) e.remove();
        traderUuid = null;
    }

    private boolean isTrader(Entity e) {
        return traderUuid != null && e != null && traderUuid.equals(e.getUniqueId());
    }

    private Inventory createShopInventory() {
        Inventory inv = Bukkit.createInventory(null, 27, Component.text(SHOP_TITLE, NamedTextColor.GOLD));

        inv.setItem(10, addPriceLore(GFItems.createBait(1, 8), plugin.getConfig().getInt("shop.bait_tier1", 20)));
        inv.setItem(11, addPriceLore(GFItems.createBait(2, 8), plugin.getConfig().getInt("shop.bait_tier2", 75)));
        inv.setItem(12, addPriceLore(GFItems.createBait(3, 4), plugin.getConfig().getInt("shop.bait_tier3", 200)));

        inv.setItem(14, addPriceLore(GFItems.createRodUpgradePower(), plugin.getConfig().getInt("shop.rod_power_upgrade", 250)));
        inv.setItem(15, addPriceLore(GFItems.createRodUpgradeLuck(), plugin.getConfig().getInt("shop.rod_luck_upgrade", 250)));

        inv.setItem(16, addPriceLore(GFItems.createQuotaReducer(plugin.getConfig().getInt("quota_reduce_amount", 10)), plugin.getConfig().getInt("shop.quota_reduce_item", 300)));
        inv.setItem(19, addPriceLore(GFItems.createFishingWindowBoost(plugin.getConfig().getInt("upgrades.window_bonus_ms", 450)), plugin.getConfig().getInt("shop.window_boost", 220)));
        inv.setItem(20, addPriceLore(GFItems.createCatchBonus(plugin.getConfig().getInt("upgrades.catch_bonus_percent", 12)), plugin.getConfig().getInt("shop.catch_bonus", 260)));
        inv.setItem(22, addPriceLore(GFItems.createCampfireRecall(), plugin.getConfig().getInt("shop.campfire_recall", 120)));
        inv.setItem(23, addPriceLore(GFItems.createAmuletSilence(), plugin.getConfig().getInt("shop.amulet_silence", 180)));

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

    // === Events ===

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (!isTrader(event.getRightClicked())) return;
        event.setCancelled(true);
        Player p = event.getPlayer();

        ItemStack hand = p.getInventory().getItemInMainHand();
        if (GFItems.isCustomFish(hand)) {
            FishData fish = GFItems.readFish(hand);
            if (fish != null) {
                if (!plugin.bank().isAvailable()) {
                    p.sendMessage(Text.bad("Экономика недоступна (Vault)."));
                    return;
                }
                int value = fish.value();
                plugin.bank().give(value);
                hand.setAmount(hand.getAmount() - 1);
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

        // left-click tug progress for fishing challenge
        if (event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK) {
            ItemStack it = event.getItem();
            if (it != null && it.getType() == Material.FISHING_ROD) {
                FishingChallenge ch = challenges.get(p.getUniqueId());
                if (ch != null && !ch.completed && System.currentTimeMillis() <= ch.expireAt) {
                    long now = System.currentTimeMillis();
                    int cooldown = plugin.getConfig().getInt("fishing.pull_cooldown_ms", 220);
                    if (now - ch.lastPullAt < cooldown) {
                        return;
                    }
                    ch.lastPullAt = now;
                    ch.pullsDone++;
                    if (ch.pullsDone >= ch.requiredPulls) {
                        ch.completed = true;
                        p.sendActionBar(Component.text("Готово! ПКМ чтобы вытащить", NamedTextColor.GREEN));
                        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.3f);
                    } else {
                        p.sendActionBar(Component.text("Тяни рыбу: " + ch.pullsDone + "/" + ch.requiredPulls, NamedTextColor.YELLOW));
                        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.0f);
                    }
                }
            }
        }
    }

    @EventHandler
    public void onFish(PlayerFishEvent event) {
        Player p = event.getPlayer();
        if (!running || !isInGame(p)) return;

        if (event.getState() == PlayerFishEvent.State.BITE) {
            // start challenge
            ItemStack rod = p.getInventory().getItemInMainHand();
            if (rod.getType() != Material.FISHING_ROD) rod = p.getInventory().getItemInOffHand();

            int rodPower = GFItems.getRodPower(rod) + sharedRodPower;
            int rodLuck = GFItems.getRodLuck(rod) + sharedRodLuck;

            ItemStack bait = p.getInventory().getItemInOffHand();
            int baitTier = GFItems.getBaitTier(bait);

            // consume bait immediately (locks tier for this bite)
            if (baitTier > 0) {
                bait.setAmount(bait.getAmount() - 1);
            }

            FishData fish = rollFish(baitTier, rodLuck, isNight);

            int minPulls = plugin.getConfig().getInt("fishing.min_pulls", 2);
            int maxPulls = plugin.getConfig().getInt("fishing.max_pulls", 12);
            double w2p = plugin.getConfig().getDouble("fishing.weight_to_pulls", 0.55);

            int req = (int) Math.round(minPulls + fish.weightKg() * w2p);
            req = Math.max(minPulls, Math.min(maxPulls, req));
            req = Math.max(1, req - rodPower); // power reduces needed tugs

            int baseWindow = plugin.getConfig().getInt("fishing.base_window_ms", 3500);
            double w2w = plugin.getConfig().getDouble("fishing.weight_to_window_ms", 85);
            long window = (long) (baseWindow - fish.weightKg() * w2w + rodPower * 250L + sharedWindowBonusMs);
            window = Math.max(1600L, Math.min(6500L, window));

            FishingChallenge ch = new FishingChallenge(fish, req, 0, System.currentTimeMillis() + window, false, 0L);
            challenges.put(p.getUniqueId(), ch);

            p.sendActionBar(Component.text("Клюёт! ЛКМ тянуть: 0/" + req, NamedTextColor.YELLOW));
            return;
        }

        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH) {
            FishingChallenge ch = challenges.remove(p.getUniqueId());
            if (ch == null) return;

            if (System.currentTimeMillis() > ch.expireAt || !ch.completed) {
                // fish escaped
                event.setCancelled(true);
                if (event.getCaught() != null) event.getCaught().remove();
                p.sendMessage(Text.bad("Рыба сорвалась..."));
                p.playSound(p.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 0.6f, 0.6f);
                return;
            }

            // replace caught item with custom fish
            ItemStack custom = GFItems.createFishItem(ch.fish);
            if (event.getCaught() instanceof Item itemEntity) {
                itemEntity.setItemStack(custom);
            } else {
                // fallback: drop to player
                p.getWorld().dropItemNaturally(p.getLocation(), custom);
            }

            p.sendMessage(Component.text("Вы поймали: ", NamedTextColor.YELLOW)
                    .append(Component.text(ch.fish.speciesName(), ch.fish.rarity().color))
                    .append(Component.text(" | Вес: " + DF.format(ch.fish.weightKg()) + "кг | Очки: " + ch.fish.points(), NamedTextColor.GRAY))
            );
            p.playSound(p.getLocation(), Sound.ENTITY_FISH_SWIM, 0.8f, 1.2f);
        }

        // clean up on fail
        if (event.getState() == PlayerFishEvent.State.FAILED_ATTEMPT
                || event.getState() == PlayerFishEvent.State.IN_GROUND
                || event.getState() == PlayerFishEvent.State.CAUGHT_ENTITY) {
            challenges.remove(p.getUniqueId());
        }
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

    private FishData rollFish(int baitTier, int rodLuck, boolean night) {
        int requiredTier = plugin.getConfig().getInt("night_fish.required_bait_tier", 3);
        double nightChance = plugin.getConfig().getDouble("night_fish.chance", 0.18);
        if (night && baitTier >= requiredTier && random.nextDouble() < nightChance) {
            return rollNightFish(rodLuck);
        }

        // rarity roll
        int luck = rodLuck * 2 + baitTier * 4 + (night ? 5 : 0);
        int roll = random.nextInt(100) + 1 + luck;

        FishRarity rarity;
        if (roll >= 130) rarity = FishRarity.LEGENDARY;
        else if (roll >= 112) rarity = FishRarity.EPIC;
        else if (roll >= 95) rarity = FishRarity.RARE;
        else if (roll >= 75) rarity = FishRarity.UNCOMMON;
        else rarity = FishRarity.COMMON;

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
        int points = (int) Math.round((weight * 5 + (rarity.ordinal() * 8)) * sharedPointsMultiplier);
        int value = (int) Math.round(points * rarity.valueMult * sharedValueMultiplier);

        String species = switch (rarity) {
            case COMMON -> "Треска";
            case UNCOMMON -> "Лосось";
            case RARE -> "Глубинная рыба";
            case EPIC -> "Тёмный карп";
            case LEGENDARY -> "Рыба-кошмар";
        };

        return new FishData(rarity, weight, points, value, false, species);
    }

    private FishData rollNightFish(int rodLuck) {
        double bonus = plugin.getConfig().getDouble("night_fish.points_bonus", 0.25);
        List<NightFish> pool = List.of(
                new NightFish("Лунная щука", FishRarity.RARE, 3.0, 6.5),
                new NightFish("Тень-угорь", FishRarity.EPIC, 6.5, 12.0),
                new NightFish("Бездна-катран", FishRarity.LEGENDARY, 11.0, 19.0)
        );
        int idx = Math.min(pool.size() - 1, Math.max(0, random.nextInt(pool.size()) + Math.min(2, rodLuck / 2)));
        NightFish pick = pool.get(idx);

        double weight = pick.minWeight + random.nextDouble() * (pick.maxWeight - pick.minWeight);
        int basePoints = (int) Math.round((weight * 5 + (pick.rarity.ordinal() * 8)) * (1.0 + bonus));
        int points = (int) Math.round(basePoints * sharedPointsMultiplier);
        int value = (int) Math.round(points * pick.rarity.valueMult * sharedValueMultiplier);
        return new FishData(pick.rarity, weight, points, value, false, pick.name);
    }

    private record NightFish(String name, FishRarity rarity, double minWeight, double maxWeight) {}

    private static final class FishingChallenge {
        final FishData fish;
        final int requiredPulls;
        int pullsDone;
        final long expireAt;
        boolean completed;
        long lastPullAt;

        FishingChallenge(FishData fish, int requiredPulls, int pullsDone, long expireAt, boolean completed, long lastPullAt) {
            this.fish = fish;
            this.requiredPulls = requiredPulls;
            this.pullsDone = pullsDone;
            this.expireAt = expireAt;
            this.completed = completed;
            this.lastPullAt = lastPullAt;
        }
    }
}
