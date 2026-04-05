package com.dino;

import com.dino.domain.entities.ButtonSwitch;
import com.dino.domain.entities.ExitZone;
import com.dino.domain.entities.Player;
import com.dino.domain.rules.GameRules;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pruebas unitarias para reglas puras del dominio.
 */
class GameRulesTest {

    @Test
    /** Verifica la detección básica de solapamiento con el botón. */
    void playerPressesButtonWhenOverlapping() {
        Player player = new Player("p1", "A", "red");
        player.setX(100);
        player.setY(100);

        ButtonSwitch button = new ButtonSwitch("button", 90, 130, 80, 16);

        assertTrue(GameRules.isPressingButton(player, button));
    }

    @Test
    /** La salida solo se valida si todos los conectados llegaron. */
    void allConnectedPlayersMustReachExit() {
        Player a = new Player("a", "A", "red");
        a.setConnected(true);
        a.setAtExit(true);

        Player b = new Player("b", "B", "blue");
        b.setConnected(true);
        b.setAtExit(false);

        assertFalse(GameRules.allConnectedPlayersAtExit(List.of(a, b)));

        b.setAtExit(true);
        assertTrue(GameRules.allConnectedPlayersAtExit(List.of(a, b)));
    }

    @Test
    /** El hilo debe bloquear separaciones excesivas. */
    void threadDistanceBlocksExcessiveSeparation() {
        Player a = new Player("a", "A", "red");
        a.setX(0);
        a.setY(0);

        Player b = new Player("b", "B", "blue");
        b.setX(500);
        b.setY(0);

        assertTrue(GameRules.violatesThreadDistance(a, List.of(a, b)));
    }

    @Test
    /** La zona de salida debe detectar a un jugador dentro del rectángulo. */
    void exitZoneDetectsPlayerInside() {
        Player player = new Player("p1", "A", "red");
        player.setX(200);
        player.setY(300);

        ExitZone exitZone = new ExitZone(180, 280, 120, 110);
        assertTrue(GameRules.isInsideExit(player, exitZone));
    }
}
