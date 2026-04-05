package com.dino.domain.rules;

import com.dino.config.GameConfig;
import com.dino.domain.entities.CollectibleItem;
import com.dino.domain.entities.PenaltyZone;
import com.dino.domain.entities.Player;
import javafx.util.Pair;

import java.util.List;
import java.util.Random;

public class GameRules {

    private static final Random random = new Random();

    public static boolean canConsumeFood(Player player, CollectibleItem item) {
        if (player == null || item == null || !item.isActive()) return false;
        return distance(player.getX(), player.getY(), item.getX(), item.getY())
            <= player.getRadius(GameConfig.PLAYER_RADIUS_SCALE) + GameConfig.FOOD_RADIUS;
    }

    public static boolean canConsumePlayer(Player predator, Player prey) {
        if (predator == null || prey == null || predator == prey) return false;
        if (!predator.isConnected() || !prey.isConnected()) return false;
        if (predator.getMass() < prey.getMass() * GameConfig.PLAYER_EAT_RATIO) return false;

        double predatorRadius = predator.getRadius(GameConfig.PLAYER_RADIUS_SCALE);
        double preyRadius = prey.getRadius(GameConfig.PLAYER_RADIUS_SCALE);
        double reach = predatorRadius - preyRadius * GameConfig.PLAYER_EAT_OVERLAP_MARGIN;
        return reach > 0 && distance(predator.getX(), predator.getY(), prey.getX(), prey.getY()) <= reach;
    }

    public static Pair<Player, Boolean> calculateWinner(List<Player> players) {
        if (players == null || players.isEmpty()) return new Pair<>(null, false);
        Player winner = null;
        double maxMass = Double.NEGATIVE_INFINITY;
        boolean tie = false;
        for (Player p : players) {
            if (p.getMass() > maxMass) {
                maxMass = p.getMass();
                winner = p;
                tie = false;
            } else if (Double.compare(p.getMass(), maxMass) == 0) {
                tie = true;
            }
        }
        return tie ? new Pair<>(null, true) : new Pair<>(winner, false);
    }

    public static PenaltyZone findTriggeredVirus(Player player, List<PenaltyZone> zones) {
        if (player == null || zones == null) return null;
        for (PenaltyZone zone : zones) {
            double playerRadius = player.getRadius(GameConfig.PLAYER_RADIUS_SCALE);
            double distance = distance(player.getX(), player.getY(), zone.getX(), zone.getY());
            if (player.getMass() >= zone.getTriggerMass() && distance <= playerRadius + zone.getRadius()) {
                return zone;
            }
        }
        return null;
    }

    public static double speedForMass(double mass) {
        double normalized = Math.max(1.0, mass / GameConfig.PLAYER_START_MASS);
        double speed = GameConfig.PLAYER_BASE_SPEED / Math.pow(normalized, 0.42);
        return Math.max(GameConfig.PLAYER_MIN_SPEED, speed);
    }

    public static double decayForMass(double mass, double dtSeconds) {
        if (mass <= GameConfig.PLAYER_DECAY_THRESHOLD) return 0;
        return mass * GameConfig.PLAYER_DECAY_RATE * dtSeconds;
    }

    public static double distance(double ax, double ay, double bx, double by) {
        double dx = ax - bx;
        double dy = ay - by;
        return Math.sqrt(dx * dx + dy * dy);
    }

    public static double randomArenaX() {
        return GameConfig.ARENA_X + random.nextDouble() * GameConfig.ARENA_W;
    }

    public static double randomArenaY() {
        return GameConfig.ARENA_Y + random.nextDouble() * GameConfig.ARENA_H;
    }
}
