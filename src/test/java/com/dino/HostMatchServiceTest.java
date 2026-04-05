package com.dino;

import com.dino.application.services.EventBus;
import com.dino.application.services.HostMatchService;
import com.dino.application.services.SessionService;
import com.dino.config.GameConfig;
import com.dino.domain.entities.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostMatchServiceTest {

    @Test
    void hostResolvesPlayerConsumptionAuthoritatively() {
        EventBus eventBus = new EventBus();
        SessionService sessionService = new SessionService(eventBus);
        HostMatchService hostMatchService = new HostMatchService(sessionService, eventBus);

        Player predator = new Player("predator", "Big", "red");
        predator.setConnected(true);
        predator.setMass(120);
        predator.setX(200);
        predator.setY(200);

        Player prey = new Player("prey", "Small", "blue");
        prey.setConnected(true);
        prey.setMass(40);
        prey.setX(205);
        prey.setY(200);

        sessionService.addPlayer(predator);
        sessionService.addPlayer(prey);
        sessionService.setGameRunning(true);
        sessionService.setGameTimer(60);

        hostMatchService.tick(0.016);

        assertTrue(predator.getMass() > 120);
        assertEquals(GameConfig.PLAYER_RESPAWN_MASS, prey.getMass());
        assertTrue(prey.getX() >= GameConfig.ARENA_X);
        assertTrue(prey.getX() <= GameConfig.ARENA_X + GameConfig.ARENA_W);
        assertTrue(prey.getY() >= GameConfig.ARENA_Y);
        assertTrue(prey.getY() <= GameConfig.ARENA_Y + GameConfig.ARENA_H);
    }
}
