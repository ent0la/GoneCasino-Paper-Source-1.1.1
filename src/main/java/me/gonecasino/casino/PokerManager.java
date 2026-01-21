package me.gonecasino.casino;

import me.gonecasino.GoneCasinoPlugin;
import me.gonecasino.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Simple Texas Hold'em implementation (no betting; ante only).
 */
public final class PokerManager {
    private final GoneCasinoPlugin plugin;

    // tables: serialized location -> set of seated players
    private final Map<String, Set<UUID>> seated = new HashMap<>();

    public PokerManager(GoneCasinoPlugin plugin) {
        this.plugin = plugin;
    }

    public void toggleSeat(Player player, Location tableBlock) {
        String key = tableKey(tableBlock);
        seated.putIfAbsent(key, new LinkedHashSet<>());
        Set<UUID> set = seated.get(key);

        if (set.contains(player.getUniqueId())) {
            set.remove(player.getUniqueId());
            player.sendMessage(Text.info("Вы встали из-за покерного стола."));
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 0.8f);
            return;
        }

        if (set.size() >= 4) {
            player.sendMessage(Text.bad("За столом уже 4 игрока."));
            return;
        }

        set.add(player.getUniqueId());
        player.sendMessage(Text.ok("Вы сели за покерный стол. Игроков: " + set.size() + "/4"));
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.2f);
    }

    public boolean startNearestTable(Player starter, Collection<Location> pokerTables) {
        Location nearest = null;
        double best = Double.MAX_VALUE;
        for (Location loc : pokerTables) {
            if (loc == null || loc.getWorld() == null || starter.getWorld() != loc.getWorld()) continue;
            double d = loc.distanceSquared(starter.getLocation());
            if (d < best) {
                best = d;
                nearest = loc;
            }
        }
        if (nearest == null || best > 25) { // within 5 blocks
            starter.sendMessage(Text.bad("Рядом нет покерного стола (5 блоков)."));
            return false;
        }
        return startHandAt(nearest);
    }

    public boolean startHandAt(Location tableBlock) {
        String key = tableKey(tableBlock);
        Set<UUID> set = seated.getOrDefault(key, Set.of());
        List<Player> players = new ArrayList<>();
        for (UUID uuid : set) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline() && p.getWorld() == tableBlock.getWorld() && p.getLocation().distanceSquared(tableBlock) <= 64) {
                players.add(p);
            }
        }
        if (players.size() < 2) {
            if (!players.isEmpty()) players.get(0).sendMessage(Text.bad("Нужно минимум 2 игрока за столом."));
            return false;
        }

        int ante = 50;
        int pot = 0;
        for (Player p : players) {
            if (!plugin.data().takeChips(p.getUniqueId(), ante)) {
                p.sendMessage(Text.bad("Недостаточно фишек для анте (" + ante + ")."));
                return false;
            }
            pot += ante;
        }

        Deck deck = new Deck();
        deck.shuffle();

        Map<Player, List<Card>> hole = new HashMap<>();
        for (Player p : players) {
            hole.put(p, List.of(deck.draw(), deck.draw()));
        }

        List<Card> board = List.of(deck.draw(), deck.draw(), deck.draw(), deck.draw(), deck.draw());

        // DM hole cards
        for (Player p : players) {
            List<Card> hc = hole.get(p);
            p.sendMessage(Text.info("Ваши карты: " + hc.get(0) + " " + hc.get(1)));
        }

        // Broadcast board
        String boardStr = board.get(0) + " " + board.get(1) + " " + board.get(2) + " | " + board.get(3) + " | " + board.get(4);
        for (Player p : players) {
            p.sendMessage(Text.info("Стол: " + boardStr));
        }

        // Evaluate
        Map<Player, HandValue> values = new HashMap<>();
        HandValue best = null;
        for (Player p : players) {
            List<Card> seven = new ArrayList<>(board);
            seven.addAll(hole.get(p));
            HandValue hv = HandEvaluator.evaluate(seven);
            values.put(p, hv);
            if (best == null || hv.compareTo(best) > 0) best = hv;
        }

        List<Player> winners = new ArrayList<>();
        for (var e : values.entrySet()) {
            if (e.getValue().compareTo(best) == 0) winners.add(e.getKey());
        }

        int share = pot / winners.size();
        int remainder = pot % winners.size();
        for (int i = 0; i < winners.size(); i++) {
            int win = share + (i == 0 ? remainder : 0);
            plugin.data().addChips(winners.get(i).getUniqueId(), win);
        }

        String winNames = winners.stream().map(Player::getName).reduce((a,b)->a+", "+b).orElse("?");
        for (Player p : players) {
            p.sendMessage(Component.text("Победитель: " + winNames + " (" + best + ") Банк: " + pot + ", каждому: " + share));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.1f);
        }

        return true;
    }

    private static String tableKey(Location blockLoc) {
        return blockLoc.getWorld().getName() + ";" + blockLoc.getBlockX() + ";" + blockLoc.getBlockY() + ";" + blockLoc.getBlockZ();
    }

    // === Poker evaluation classes ===

    private static final class Deck {
        private final List<Card> cards = new ArrayList<>();
        private int index = 0;

        Deck() {
            for (int suit = 0; suit < 4; suit++) {
                for (int rank = 2; rank <= 14; rank++) {
                    cards.add(new Card(rank, suit));
                }
            }
        }

        void shuffle() {
            Collections.shuffle(cards);
        }

        Card draw() {
            return cards.get(index++);
        }
    }

    private record Card(int rank, int suit) {
        @Override
        public String toString() {
            String r = switch (rank) {
                case 14 -> "A";
                case 13 -> "K";
                case 12 -> "Q";
                case 11 -> "J";
                case 10 -> "T";
                default -> Integer.toString(rank);
            };
            String s = switch (suit) {
                case 0 -> "♠";
                case 1 -> "♥";
                case 2 -> "♦";
                default -> "♣";
            };
            return r + s;
        }
    }

    /**
     * category: 0..8 (high card..straight flush), tiebreak: list of ranks high to low
     */
    private record HandValue(int category, List<Integer> tiebreak) implements Comparable<HandValue> {
        @Override
        public int compareTo(HandValue o) {
            if (category != o.category) return Integer.compare(category, o.category);
            int n = Math.min(tiebreak.size(), o.tiebreak.size());
            for (int i = 0; i < n; i++) {
                int a = tiebreak.get(i);
                int b = o.tiebreak.get(i);
                if (a != b) return Integer.compare(a, b);
            }
            return 0;
        }

        @Override
        public String toString() {
            return switch (category) {
                case 8 -> "Straight Flush";
                case 7 -> "Four of a Kind";
                case 6 -> "Full House";
                case 5 -> "Flush";
                case 4 -> "Straight";
                case 3 -> "Three of a Kind";
                case 2 -> "Two Pair";
                case 1 -> "One Pair";
                default -> "High Card";
            };
        }
    }

    private static final class HandEvaluator {
        static HandValue evaluate(List<Card> cards7) {
            if (cards7 == null || cards7.size() != 7) throw new IllegalArgumentException("Need 7 cards");

            // rank counts
            int[] rankCount = new int[15];
            List<Integer>[] suitRanks = new List[4];
            for (int i = 0; i < 4; i++) suitRanks[i] = new ArrayList<>();

            for (Card c : cards7) {
                rankCount[c.rank]++;
                suitRanks[c.suit].add(c.rank);
            }

            // sort suit ranks desc
            for (int i = 0; i < 4; i++) {
                suitRanks[i].sort(Comparator.reverseOrder());
            }

            // straight helper (returns highest rank of straight, 0 if none)
            java.util.function.Function<boolean[], Integer> straightHigh = present -> {
                // Ace-low: treat A as 1 too
                boolean[] p = Arrays.copyOf(present, present.length);
                if (p[14]) p[1] = true;
                int run = 0;
                for (int r = 14; r >= 1; r--) {
                    if (p[r]) {
                        run++;
                        if (run >= 5) {
                            int high = r + 4;
                            // For wheel straight, high should be 5
                            if (high == 14 && p[5] && p[4] && p[3] && p[2] && p[1]) return 5;
                            return Math.min(high, 14);
                        }
                    } else {
                        run = 0;
                    }
                }
                return 0;
            };

            // straight flush
            for (int suit = 0; suit < 4; suit++) {
                if (suitRanks[suit].size() >= 5) {
                    boolean[] present = new boolean[15];
                    for (int r : suitRanks[suit]) present[r] = true;
                    int sh = straightHigh.apply(present);
                    if (sh > 0) {
                        return new HandValue(8, List.of(sh));
                    }
                }
            }

            // four of a kind
            int quad = 0;
            for (int r = 14; r >= 2; r--) {
                if (rankCount[r] == 4) {
                    quad = r;
                    break;
                }
            }
            if (quad > 0) {
                int kicker = highestKicker(rankCount, Set.of(quad));
                return new HandValue(7, List.of(quad, kicker));
            }

            // triples and pairs
            List<Integer> trips = new ArrayList<>();
            List<Integer> pairs = new ArrayList<>();
            for (int r = 14; r >= 2; r--) {
                if (rankCount[r] == 3) trips.add(r);
                else if (rankCount[r] == 2) pairs.add(r);
            }

            // full house
            if (!trips.isEmpty() && (!pairs.isEmpty() || trips.size() >= 2)) {
                int topTrip = trips.get(0);
                int topPair = !pairs.isEmpty() ? pairs.get(0) : trips.get(1);
                return new HandValue(6, List.of(topTrip, topPair));
            }

            // flush
            for (int suit = 0; suit < 4; suit++) {
                if (suitRanks[suit].size() >= 5) {
                    List<Integer> top5 = suitRanks[suit].subList(0, 5);
                    return new HandValue(5, new ArrayList<>(top5));
                }
            }

            // straight
            {
                boolean[] present = new boolean[15];
                for (int r = 2; r <= 14; r++) present[r] = rankCount[r] > 0;
                int sh = straightHigh.apply(present);
                if (sh > 0) {
                    return new HandValue(4, List.of(sh));
                }
            }

            // three of a kind
            if (!trips.isEmpty()) {
                int t = trips.get(0);
                List<Integer> kickers = topKickers(rankCount, Set.of(t), 2);
                List<Integer> tb = new ArrayList<>();
                tb.add(t);
                tb.addAll(kickers);
                return new HandValue(3, tb);
            }

            // two pair
            if (pairs.size() >= 2) {
                int p1 = pairs.get(0);
                int p2 = pairs.get(1);
                int kicker = highestKicker(rankCount, Set.of(p1, p2));
                return new HandValue(2, List.of(p1, p2, kicker));
            }

            // one pair
            if (pairs.size() == 1) {
                int p = pairs.get(0);
                List<Integer> kickers = topKickers(rankCount, Set.of(p), 3);
                List<Integer> tb = new ArrayList<>();
                tb.add(p);
                tb.addAll(kickers);
                return new HandValue(1, tb);
            }

            // high card
            List<Integer> top5 = topKickers(rankCount, Set.of(), 5);
            return new HandValue(0, top5);
        }

        private static int highestKicker(int[] rankCount, Set<Integer> excluded) {
            for (int r = 14; r >= 2; r--) {
                if (rankCount[r] > 0 && !excluded.contains(r)) return r;
            }
            return 2;
        }

        private static List<Integer> topKickers(int[] rankCount, Set<Integer> excluded, int n) {
            List<Integer> res = new ArrayList<>();
            for (int r = 14; r >= 2 && res.size() < n; r--) {
                if (rankCount[r] > 0 && !excluded.contains(r)) {
                    for (int k = 0; k < rankCount[r] && res.size() < n; k++) {
                        res.add(r);
                    }
                }
            }
            return res;
        }
    }
}
