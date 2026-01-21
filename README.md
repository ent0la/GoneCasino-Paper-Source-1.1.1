# GoneCasino (Paper 1.20.6)

This is a Paper/Spigot plugin project (Java 21) for Minecraft **1.20.6**.

Features implemented (core):
- "GONE Fishing" day/night quota loop
- Altar with orange/blue flame particles
- Trader spawning near altar during the day and leaving at night
- Custom fish (weight, points, rarity) with chat messages
- Bait system (higher tier bait -> better fish)
- Fishing mini-challenge: heavier fish is harder to reel in
- Safe house spawned near altar (4 beds, campfire)
- Campfire 5 second cooking mechanic (cooked fish sell for more + give more altar points)
- Basic casino scaffolding (tables, slots) and simple poker command

## Build
You need:
- JDK 21
- Gradle (any recent version)

Then:
```bash
gradle build
```
The jar will appear in `build/libs/`.

## Install
Copy the jar into your server's `plugins/` directory and restart.
