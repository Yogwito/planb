package com.dino.application.services;

import com.dino.config.GameConfig;
import com.dino.domain.entities.CollectibleItem;
import com.dino.domain.entities.PenaltyZone;
import com.dino.domain.entities.Player;
import com.dino.domain.events.EventNames;
import com.dino.domain.rules.GameRules;
import javafx.util.Pair;

import java.util.*;

public class HostMatchService {
    private static final int MAX_SPAWN_ATTEMPTS = 20;

    private final SessionService sessionService;
    private final EventBus eventBus;
    private final Map<String, double[]> playerTargets = new HashMap<>();
    private double gameTime = 0;
    private boolean gameOver = false;
    private double foodSpawnBudget = 0;

    public HostMatchService(SessionService sessionService, EventBus eventBus) {
        this.sessionService = sessionService;
        this.eventBus = eventBus;
    }

    public void initWorld() {
        List<CollectibleItem> items = sessionService.getItems();
        items.clear();
        List<PenaltyZone> zones = sessionService.getPenaltyZones();
        zones.clear();
        for (int i = 0; i < GameConfig.VIRUS_COUNT; i++) {
            zones.add(spawnVirus("virus_" + i, sessionService.getPlayers().values(), zones));
        }

        for (int i = 0; i < GameConfig.FOOD_INITIAL_COUNT; i++) {
            items.add(spawnFood("food_" + i, sessionService.getPlayers().values(), zones));
        }

        sessionService.setGameTimer(GameConfig.GAME_DURATION_SECONDS);
        sessionService.setGameRunning(true);
        gameTime = 0;
        gameOver = false;
        foodSpawnBudget = 0;

        List<double[]> starts = List.of(
            new double[]{GameConfig.ARENA_X + 100, GameConfig.ARENA_Y + 100},
            new double[]{GameConfig.ARENA_X + GameConfig.ARENA_W - 100, GameConfig.ARENA_Y + 100},
            new double[]{GameConfig.ARENA_X + 100, GameConfig.ARENA_Y + GameConfig.ARENA_H - 100},
            new double[]{GameConfig.ARENA_X + GameConfig.ARENA_W - 100, GameConfig.ARENA_Y + GameConfig.ARENA_H - 100}
        );
        int i = 0;
        for (Player p : sessionService.getPlayers().values()) {
            if (i < starts.size()) {
                p.setX(starts.get(i)[0]);
                p.setY(starts.get(i)[1]);
                p.setMass(GameConfig.PLAYER_START_MASS);
                playerTargets.put(p.getId(), new double[]{p.getX(), p.getY()});
                i++;
            }
        }
    }

    public void handleInput(String playerId, double targetX, double targetY) {
        playerTargets.put(playerId, new double[]{targetX, targetY});
    }

    public void tick(double dt) {
        if (gameOver || !sessionService.isGameRunning()) return;
        gameTime += dt;
        double timer = sessionService.getGameTimer() - dt;
        sessionService.setGameTimer(Math.max(0, timer));

        Map<String, Player> players = sessionService.getPlayers();
        List<CollectibleItem> items = sessionService.getItems();
        List<PenaltyZone> zones = sessionService.getPenaltyZones();

        applyMassDecay(players.values(), dt);
        replenishFood(items, players.values(), zones, dt);
        for (Player p : players.values()) {
            if (!p.isConnected()) continue;
            double[] target = playerTargets.getOrDefault(p.getId(), new double[]{p.getX(), p.getY()});
            double speed = GameRules.speedForMass(p.getMass());
            double dx = target[0] - p.getX();
            double dy = target[1] - p.getY();
            double dist = Math.sqrt(dx * dx + dy * dy);
            if (dist > 1.0) {
                double move = Math.min(speed * dt, dist);
                p.setX(Math.max(GameConfig.ARENA_X, Math.min(GameConfig.ARENA_X + GameConfig.ARENA_W, p.getX() + dx / dist * move)));
                p.setY(Math.max(GameConfig.ARENA_Y, Math.min(GameConfig.ARENA_Y + GameConfig.ARENA_H, p.getY() + dy / dist * move)));
            }
        }

        handleFoodCollection(players, items);
        handlePlayerConsumption(players);
        handleViruses(players, zones, items);

        // Game over
        if (timer <= 0 && !gameOver) {
            gameOver = true;
            sessionService.setGameRunning(false);
            Pair<Player, Boolean> result = GameRules.calculateWinner(new ArrayList<>(players.values()));
            Map<String, Object> payload = new HashMap<>();
            payload.put("winner", result.getKey() != null ? result.getKey().getId() : null);
            payload.put("isTie", result.getValue());
            eventBus.publish(EventNames.GAME_OVER, payload);
        }
    }

    private void applyMassDecay(Collection<Player> players, double dt) {
        for (Player player : players) {
            if (!player.isConnected()) continue;
            double decay = GameRules.decayForMass(player.getMass(), dt);
            if (decay <= 0) continue;
            player.setMass(Math.max(GameConfig.PLAYER_MIN_MASS, player.getMass() - decay));
        }
    }

    private void handleFoodCollection(Map<String, Player> players, List<CollectibleItem> items) {
        Iterator<CollectibleItem> iterator = items.iterator();
        while (iterator.hasNext()) {
            CollectibleItem item = iterator.next();
            if (!item.isActive()) continue;
            for (Player p : players.values()) {
                if (!p.isConnected()) continue;
                if (GameRules.canConsumeFood(p, item)) {
                    p.addMass(item.getPoints());
                    eventBus.publish(EventNames.ITEM_COLLECTED, Map.of(
                        "playerId", p.getId(),
                        "itemId", item.getId(),
                        "points", item.getPoints()
                    ));
                    publishMassUpdate(p);
                    iterator.remove();
                    break;
                }
            }
        }
    }

    private void handlePlayerConsumption(Map<String, Player> players) {
        List<Player> ordered = new ArrayList<>(players.values());
        ordered.sort((a, b) -> Double.compare(b.getMass(), a.getMass()));

        Set<String> eatenThisTick = new HashSet<>();
        for (Player predator : ordered) {
            if (!predator.isConnected()) continue;
            if (eatenThisTick.contains(predator.getId())) continue;
            for (Player prey : ordered) {
                if (predator == prey || !prey.isConnected()) continue;
                if (eatenThisTick.contains(prey.getId())) continue;
                if (GameRules.canConsumePlayer(predator, prey)) {
                    double gainedMass = Math.max(1.0, prey.getMass() * GameConfig.PLAYER_MASS_TRANSFER_RATIO);
                    predator.addMass(gainedMass);
                    publishMassUpdate(predator);
                    eventBus.publish(EventNames.PLAYER_CONSUMED, Map.of(
                        "playerId", predator.getId(),
                        "targetId", prey.getId(),
                        "gainedMass", gainedMass
                    ));
                    respawnPlayer(prey);
                    publishMassUpdate(prey);
                    eatenThisTick.add(prey.getId());
                }
            }
        }
    }

    private void handleViruses(Map<String, Player> players, List<PenaltyZone> zones, List<CollectibleItem> items) {
        for (Player player : players.values()) {
            if (!player.isConnected()) continue;
            PenaltyZone virus = GameRules.findTriggeredVirus(player, zones);
            if (virus == null) continue;

            double lostMass = Math.max(0, player.getMass() * virus.getBurstRatio());
            player.setMass(Math.max(GameConfig.PLAYER_START_MASS, player.getMass() - lostMass));
            publishMassUpdate(player);
            eventBus.publish(EventNames.VIRUS_TRIGGERED, Map.of(
                "playerId", player.getId(),
                "zoneId", virus.getId(),
                "lostMass", lostMass
            ));
            feedWorldFromBurst(items, lostMass);
            repositionVirus(virus, players.values(), zones);
        }
    }

    private void replenishFood(List<CollectibleItem> items, Collection<Player> players, List<PenaltyZone> zones, double dt) {
        foodSpawnBudget += GameConfig.FOOD_SPAWN_PER_SECOND * dt;
        while (items.size() < GameConfig.FOOD_MAX_COUNT && foodSpawnBudget >= 1.0) {
            items.add(spawnFood("food_" + UUID.randomUUID(), players, zones));
            foodSpawnBudget -= 1.0;
        }
    }

    private CollectibleItem spawnFood(String id, Collection<Player> players, List<PenaltyZone> zones) {
        Pair<Double, Double> position = findValidSpawn(
            players,
            zones,
            GameConfig.FOOD_MIN_PLAYER_DISTANCE,
            GameConfig.FOOD_MIN_VIRUS_DISTANCE
        );
        return new CollectibleItem(id, position.getKey(), position.getValue(), GameConfig.FOOD_MASS);
    }

    private PenaltyZone spawnVirus(String id, Collection<Player> players, List<PenaltyZone> zones) {
        Pair<Double, Double> position = findValidSpawn(
            players,
            zones,
            GameConfig.VIRUS_MIN_PLAYER_DISTANCE,
            GameConfig.VIRUS_MIN_VIRUS_DISTANCE
        );
        return new PenaltyZone(
            id,
            position.getKey(),
            position.getValue(),
            GameConfig.VIRUS_RADIUS,
            GameConfig.VIRUS_TRIGGER_MASS,
            GameConfig.VIRUS_BURST_RATIO
        );
    }

    private void respawnPlayer(Player player) {
        Pair<Double, Double> position = findValidSpawn(
            sessionService.getPlayers().values(),
            sessionService.getPenaltyZones(),
            GameConfig.VIRUS_MIN_PLAYER_DISTANCE,
            GameConfig.VIRUS_MIN_VIRUS_DISTANCE
        );
        player.setMass(GameConfig.PLAYER_RESPAWN_MASS);
        player.setX(position.getKey());
        player.setY(position.getValue());
        playerTargets.put(player.getId(), new double[]{player.getX(), player.getY()});
    }

    private void repositionVirus(PenaltyZone virus, Collection<Player> players, List<PenaltyZone> zones) {
        List<PenaltyZone> others = new ArrayList<>(zones);
        others.remove(virus);
        Pair<Double, Double> position = findValidSpawn(
            players,
            others,
            GameConfig.VIRUS_MIN_PLAYER_DISTANCE,
            GameConfig.VIRUS_MIN_VIRUS_DISTANCE
        );
        virus.setX(position.getKey());
        virus.setY(position.getValue());
    }

    private void feedWorldFromBurst(List<CollectibleItem> items, double lostMass) {
        int extraFood = Math.max(2, Math.min(GameConfig.VIRUS_FEED_DROP_COUNT, (int) Math.round(lostMass / GameConfig.FOOD_MASS)));
        for (int i = 0; i < extraFood; i++) {
            if (items.size() >= GameConfig.FOOD_MAX_COUNT) return;
            items.add(spawnFood("burst_" + UUID.randomUUID(), sessionService.getPlayers().values(), sessionService.getPenaltyZones()));
        }
    }

    private Pair<Double, Double> findValidSpawn(
        Collection<Player> players,
        List<PenaltyZone> zones,
        double minPlayerDistance,
        double minVirusDistance
    ) {
        Pair<Double, Double> fallback = new Pair<>(GameRules.randomArenaX(), GameRules.randomArenaY());
        for (int i = 0; i < MAX_SPAWN_ATTEMPTS; i++) {
            double x = GameRules.randomArenaX();
            double y = GameRules.randomArenaY();
            if (!isFarEnoughFromPlayers(x, y, players, minPlayerDistance)) continue;
            if (!isFarEnoughFromViruses(x, y, zones, minVirusDistance)) continue;
            return new Pair<>(x, y);
        }
        return fallback;
    }

    private boolean isFarEnoughFromPlayers(double x, double y, Collection<Player> players, double minDistance) {
        for (Player player : players) {
            if (!player.isConnected()) continue;
            if (GameRules.distance(x, y, player.getX(), player.getY()) < minDistance) return false;
        }
        return true;
    }

    private boolean isFarEnoughFromViruses(double x, double y, List<PenaltyZone> zones, double minDistance) {
        for (PenaltyZone zone : zones) {
            if (GameRules.distance(x, y, zone.getX(), zone.getY()) < minDistance) return false;
        }
        return true;
    }

    private void publishMassUpdate(Player player) {
        eventBus.publish(EventNames.SCORE_UPDATED, Map.of(
            "playerId", player.getId(),
            "score", player.getScore(),
            "mass", player.getMass()
        ));
    }
}
