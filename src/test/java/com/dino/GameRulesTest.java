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
    void testPlayerConsumesFoodWhenTouchingIt() {
        Player player = new Player("p1", "Alice", "red");
        player.setX(100);
        player.setY(100);
        player.setMass(40);

        CollectibleItem item = new CollectibleItem("food1", 108, 100, 4);

        assertTrue(GameRules.canConsumeFood(player, item));
    }

    @Test
    void testLargePlayerCanConsumeSmallerPlayer() {
        Player predator = new Player("p1", "Big", "red");
        predator.setMass(120);
        predator.setX(100);
        predator.setY(100);

        Player prey = new Player("p2", "Small", "blue");
        prey.setMass(40);
        prey.setX(108);
        prey.setY(100);

        assertTrue(GameRules.canConsumePlayer(predator, prey));
    }

    @Test
    void testWinnerSinglePlayer() {
        Player p = new Player("p1", "Alice", "red");
        p.setMass(50);
        Pair<Player, Boolean> result = GameRules.calculateWinner(List.of(p));
        assertNotNull(result.getKey());
        assertEquals("Alice", result.getKey().getName());
        assertFalse(result.getValue());
    }

    @Test
    void testWinnerTie() {
        Player p1 = new Player("p1", "Alice", "red");  p1.setMass(50);
        Player p2 = new Player("p2", "Bob",   "blue"); p2.setMass(50);
        Pair<Player, Boolean> result = GameRules.calculateWinner(List.of(p1, p2));
        assertNull(result.getKey());
        assertTrue(result.getValue());
    }

    @Test
    void testVirusDetectsOnlyBigPlayers() {
        Player p = new Player("p1", "Alice", "red");
        p.setMass(120);
        p.setX(100);
        p.setY(100);

        PenaltyZone inside  = new PenaltyZone("z1", 110, 110, 28, 90, 0.35);
        PenaltyZone outside = new PenaltyZone("z2", 300, 300, 28, 90, 0.35);

        PenaltyZone detected = GameRules.findTriggeredVirus(p, List.of(inside, outside));
        assertNotNull(detected);
        assertEquals("z1", detected.getId());
    }
}
