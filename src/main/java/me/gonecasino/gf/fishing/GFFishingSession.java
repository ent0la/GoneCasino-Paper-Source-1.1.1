package me.gonecasino.gf.fishing;

import me.gonecasino.gf.FishRarity;
import org.bukkit.entity.Player;

import java.util.UUID;

public final class GFFishingSession {
    private final UUID playerId;
    private final UUID hookId;
    private final FishRarity rarity;
    private final String speciesName;
    private final double weightKg;
    private final int maxHeight;
    private final int barHeight;
    private final double pointsToFinish;
    private final double lineStrength;

    private final int idleTimeTicks;
    private final double topSpeed;
    private final double upAcceleration;
    private final double downAcceleration;
    private final int avgDistance;
    private final int moveVariation;

    private final double bobberUpAccel;
    private final double bobberDownAccel;
    private final double bobberDrag;

    private double fishPos;
    private double fishVelocity;
    private int fishTarget;
    private boolean fishIsIdle;
    private int fishIdleTicks;

    private double bobberPos;
    private double bobberVelocity;

    private double points;
    private int totalTicks;
    private int successTicks;
    private int timeLeftTicks;
    private long lastDashMs;

    public GFFishingSession(UUID playerId,
                            UUID hookId,
                            FishRarity rarity,
                            String speciesName,
                            double weightKg,
                            int maxHeight,
                            int barHeight,
                            double pointsToFinish,
                            double lineStrength,
                            int idleTimeTicks,
                            double topSpeed,
                            double upAcceleration,
                            double downAcceleration,
                            int avgDistance,
                            int moveVariation,
                            double bobberUpAccel,
                            double bobberDownAccel,
                            double bobberDrag,
                            int timeLeftTicks) {
        this.playerId = playerId;
        this.hookId = hookId;
        this.rarity = rarity;
        this.speciesName = speciesName;
        this.weightKg = weightKg;
        this.maxHeight = Math.max(10, maxHeight);
        this.barHeight = Math.max(4, Math.min(barHeight, this.maxHeight - 2));
        this.pointsToFinish = Math.max(10, pointsToFinish);
        this.lineStrength = Math.max(0.0, Math.min(0.65, lineStrength));
        this.idleTimeTicks = Math.max(0, idleTimeTicks);
        this.topSpeed = Math.max(0.1, topSpeed);
        this.upAcceleration = Math.max(0.01, upAcceleration);
        this.downAcceleration = Math.max(0.01, downAcceleration);
        this.avgDistance = Math.max(4, avgDistance);
        this.moveVariation = Math.max(0, moveVariation);
        this.bobberUpAccel = bobberUpAccel;
        this.bobberDownAccel = bobberDownAccel;
        this.bobberDrag = bobberDrag;
        this.timeLeftTicks = Math.max(20, timeLeftTicks);

        this.fishPos = this.maxHeight * 0.5;
        this.bobberPos = this.maxHeight * 0.3;
        this.fishTarget = -1;
    }

    public void tick(Player player, boolean inputUp) {
        totalTicks++;
        timeLeftTicks--;

        if (inputUp) {
            bobberVelocity += bobberUpAccel;
        } else {
            bobberVelocity += bobberDownAccel;
        }
        bobberVelocity *= bobberDrag;
        bobberPos += bobberVelocity;
        double maxBobber = maxHeight - barHeight;
        if (bobberPos < 0) {
            bobberPos = 0;
            bobberVelocity = 0;
        } else if (bobberPos > maxBobber) {
            bobberPos = maxBobber;
            bobberVelocity = 0;
        }

        if (fishIsIdle) {
            fishIdleTicks--;
            if (fishIdleTicks <= 0) {
                fishIsIdle = false;
                fishTarget = -1;
            }
        }

        if (!fishIsIdle && (fishTarget == -1 || Math.abs(fishPos - fishTarget) < 1.2)) {
            int distance = avgDistance + (int) Math.round((player.getRandom().nextDouble() * 2 - 1) * moveVariation);
            int dir = player.getRandom().nextBoolean() ? 1 : -1;
            int target = (int) Math.round(fishPos + dir * distance);
            target = Math.max(0, Math.min(maxHeight, target));
            fishTarget = target;
            if (player.getRandom().nextDouble() < 0.22) {
                fishIsIdle = true;
                fishIdleTicks = idleTimeTicks + player.getRandom().nextInt(5);
                fishVelocity *= 0.5;
            }
        }

        if (!fishIsIdle) {
            if (fishPos < fishTarget) {
                fishVelocity += upAcceleration;
            } else if (fishPos > fishTarget) {
                fishVelocity -= downAcceleration;
            }
            fishVelocity = Math.max(-topSpeed, Math.min(topSpeed, fishVelocity));
            fishPos += fishVelocity;
            if (fishPos < 0) {
                fishPos = 0;
                fishVelocity = 0;
            } else if (fishPos > maxHeight) {
                fishPos = maxHeight;
                fishVelocity = 0;
            }
        }

        boolean onFish = fishPos >= bobberPos && fishPos <= bobberPos + barHeight;
        if (onFish) {
            points += 1.0;
            successTicks++;
        } else {
            points -= (1.0 - lineStrength);
        }
        if (points < 0) points = 0;
        if (points > pointsToFinish) points = pointsToFinish;
    }

    public boolean tryDash(long nowMs, long cooldownMs, double bonusPoints) {
        if (nowMs - lastDashMs < cooldownMs) {
            return false;
        }
        lastDashMs = nowMs;
        points = Math.min(pointsToFinish, points + bonusPoints);
        bobberPos = clamp(fishPos - barHeight / 2.0, 0, maxHeight - barHeight);
        bobberVelocity = 0.0;
        return true;
    }

    public boolean isSuccess() {
        return points >= pointsToFinish;
    }

    public boolean isFailed() {
        return points <= 0 || timeLeftTicks <= 0;
    }

    public boolean isFinished() {
        return isSuccess() || isFailed();
    }

    public double progress01() {
        return Math.max(0.0, Math.min(1.0, points / pointsToFinish));
    }

    public UUID playerId() {
        return playerId;
    }

    public UUID hookId() {
        return hookId;
    }

    public FishRarity rarity() {
        return rarity;
    }

    public String speciesName() {
        return speciesName;
    }

    public double weightKg() {
        return weightKg;
    }

    public int maxHeight() {
        return maxHeight;
    }

    public int barHeight() {
        return barHeight;
    }

    public double fishPos() {
        return fishPos;
    }

    public double bobberPos() {
        return bobberPos;
    }

    public int totalTicks() {
        return totalTicks;
    }

    public int successTicks() {
        return successTicks;
    }

    public int timeLeftTicks() {
        return timeLeftTicks;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
