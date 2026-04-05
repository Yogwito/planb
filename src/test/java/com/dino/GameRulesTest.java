package com.dino;

import com.dino.domain.entities.CollectibleItem;
import com.dino.domain.entities.PenaltyZone;
import com.dino.domain.entities.Player;
import com.dino.domain.rules.GameRules;
import javafx.util.Pair;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GameRulesTest {

    @Test
    void testSinglePlayerCollectsItem() {
        CollectibleItem item = new CollectibleItem("item1", 100, 100, 10);
        String winner = GameRules.resolveItemCollection(item, List.of("player1"));
        assertEquals("player1", winner);
    }

    @Test
    void testMultiplePlayersSameItem() {
        CollectibleItem item = new CollectibleItem("item1", 100, 100, 10);
        List<String> collectors = List.of("player1", "player2", "player3");
        String winner = GameRules.resolveItemCollection(item, collectors);
        assertNotNull(winner);
        assertTrue(collectors.contains(winner));
    }

    @Test
    void testWinnerSinglePlayer() {
        Player p = new Player("p1", "Alice", "red");
        p.setScore(50);
        Pair<Player, Boolean> result = GameRules.calculateWinner(List.of(p));
        assertNotNull(result.getKey());
        assertEquals("Alice", result.getKey().getName());
        assertFalse(result.getValue());
    }

    @Test
    void testWinnerTie() {
        Player p1 = new Player("p1", "Alice", "red");  p1.setScore(50);
        Player p2 = new Player("p2", "Bob",   "blue"); p2.setScore(50);
        Pair<Player, Boolean> result = GameRules.calculateWinner(List.of(p1, p2));
        assertNull(result.getKey());
        assertTrue(result.getValue());
    }

    @Test
    void testPenaltyZoneDetection() {
        Player p = new Player("p1", "Alice", "red");
        p.setX(100); p.setY(100);

        PenaltyZone inside  = new PenaltyZone("z1", 110, 110, 50, -5, 0.4);
        PenaltyZone outside = new PenaltyZone("z2", 300, 300, 30, -5, 0.4);

        PenaltyZone detected = GameRules.checkPenaltyZone(p, List.of(inside, outside));
        assertNotNull(detected);
        assertEquals("z1", detected.getId());
    }
}
