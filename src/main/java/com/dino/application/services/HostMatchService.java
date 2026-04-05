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
    private final SessionService sessionService;
    private final EventBus eventBus;
    private final Random random = new Random();
    private final Map<String, double[]> playerTargets = new HashMap<>();
    private double gameTime = 0;
    private boolean gameOver = false;

    public HostMatchService(SessionService sessionService, EventBus eventBus) {
        this.sessionService = sessionService;
        this.eventBus = eventBus;
    }

    public void initWorld() {
        List<CollectibleItem> items = sessionService.getItems();
        items.clear();
        for (int i = 0; i < GameConfig.ITEM_COUNT; i++) {
            double x = GameConfig.ARENA_X + random.nextDouble() * GameConfig.ARENA_W;
            double y = GameConfig.ARENA_Y + random.nextDouble() * GameConfig.ARENA_H;
            items.add(new CollectibleItem("item_" + i, x, y, GameConfig.ITEM_POINTS));
        }

        List<PenaltyZone> zones = sessionService.getPenaltyZones();
        zones.clear();
        for (int i = 0; i < 4; i++) {
            double x = GameConfig.ARENA_X + random.nextDouble() * GameConfig.ARENA_W;
            double y = GameConfig.ARENA_Y + random.nextDouble() * GameConfig.ARENA_H;
            zones.add(new PenaltyZone("zone_" + i, x, y,
                60 + random.nextDouble() * 40, GameConfig.PENALTY_POINTS, GameConfig.PENALTY_MULTIPLIER));
        }

        sessionService.setGameTimer(GameConfig.GAME_DURATION_SECONDS);
        sessionService.setGameRunning(true);
        gameTime = 0;
        gameOver = false;

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

        // Move players
        for (Player p : players.values()) {
            if (!p.isConnected()) continue;
            double[] target = playerTargets.getOrDefault(p.getId(), new double[]{p.getX(), p.getY()});
            double speed = p.effectiveSpeed(GameConfig.PLAYER_SPEED, gameTime);
            double dx = target[0] - p.getX();
            double dy = target[1] - p.getY();
            double dist = Math.sqrt(dx * dx + dy * dy);
            if (dist > 1.0) {
                double move = Math.min(speed * dt, dist);
                p.setX(Math.max(GameConfig.ARENA_X, Math.min(GameConfig.ARENA_X + GameConfig.ARENA_W, p.getX() + dx / dist * move)));
                p.setY(Math.max(GameConfig.ARENA_Y, Math.min(GameConfig.ARENA_Y + GameConfig.ARENA_H, p.getY() + dy / dist * move)));
            }
        }

        // Item collection
        for (CollectibleItem item : items) {
            if (!item.isActive()) continue;
            List<String> collectors = new ArrayList<>();
            for (Player p : players.values()) {
                if (!p.isConnected()) continue;
                double dx = p.getX() - item.getX();
                double dy = p.getY() - item.getY();
                if (Math.sqrt(dx * dx + dy * dy) < 30) collectors.add(p.getId());
            }
            if (!collectors.isEmpty()) {
                String winner = GameRules.resolveItemCollection(item, collectors);
                if (winner != null) {
                    Player wp = players.get(winner);
                    if (wp != null) {
                        wp.setScore(wp.getScore() + item.getPoints());
                        item.setActive(false);
                        eventBus.publish(EventNames.ITEM_COLLECTED, Map.of("playerId", winner, "itemId", item.getId(), "points", item.getPoints()));
                        eventBus.publish(EventNames.SCORE_UPDATED, Map.of("playerId", winner, "score", wp.getScore()));
                    }
                }
            }
        }

        // Penalty zones
        for (Player p : players.values()) {
            if (!p.isConnected() || p.isPenalized(gameTime)) continue;
            PenaltyZone zone = GameRules.checkPenaltyZone(p, zones);
            if (zone != null) {
                p.applyPenalty(GameConfig.PENALTY_DURATION, zone.getPoints(), gameTime);
                eventBus.publish(EventNames.PENALTY_APPLIED, Map.of("playerId", p.getId(), "zoneId", zone.getId(), "points", zone.getPoints()));
                eventBus.publish(EventNames.SCORE_UPDATED, Map.of("playerId", p.getId(), "score", p.getScore()));
            }
        }

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
}
