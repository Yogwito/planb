package com.dino.domain.rules;

import com.dino.domain.entities.CollectibleItem;
import com.dino.domain.entities.PenaltyZone;
import com.dino.domain.entities.Player;
import javafx.util.Pair;

import java.util.List;
import java.util.Random;

public class GameRules {

    private static final Random random = new Random();

    public static String resolveItemCollection(CollectibleItem item, List<String> playerIds) {
        if (item == null || !item.isActive() || playerIds == null || playerIds.isEmpty()) return null;
        return playerIds.get(playerIds.size() == 1 ? 0 : random.nextInt(playerIds.size()));
    }

    public static Pair<Player, Boolean> calculateWinner(List<Player> players) {
        if (players == null || players.isEmpty()) return new Pair<>(null, false);
        Player winner = null;
        int maxScore = Integer.MIN_VALUE;
        boolean tie = false;
        for (Player p : players) {
            if (p.getScore() > maxScore) {
                maxScore = p.getScore();
                winner = p;
                tie = false;
            } else if (p.getScore() == maxScore) {
                tie = true;
            }
        }
        return tie ? new Pair<>(null, true) : new Pair<>(winner, false);
    }

    public static PenaltyZone checkPenaltyZone(Player player, List<PenaltyZone> zones) {
        if (player == null || zones == null) return null;
        for (PenaltyZone zone : zones) {
            double dx = player.getX() - zone.getX();
            double dy = player.getY() - zone.getY();
            if (Math.sqrt(dx * dx + dy * dy) <= zone.getRadius()) return zone;
        }
        return null;
    }
}
