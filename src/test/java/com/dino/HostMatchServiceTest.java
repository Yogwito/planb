package com.dino;

import com.dino.application.services.EventBus;
import com.dino.application.services.HostMatchService;
import com.dino.application.services.SessionService;
import com.dino.domain.entities.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostMatchServiceTest {

    @Test
    void hostOpensDoorWhenButtonIsPressed() {
        EventBus eventBus = new EventBus();
        SessionService sessionService = new SessionService(eventBus);
        HostMatchService hostMatchService = new HostMatchService(sessionService, eventBus);

        Player host = new Player("host", "Host", "red");
        sessionService.addPlayer(host);
        sessionService.setGameRunning(true);
        hostMatchService.initWorld();

        host.setX(940);
        host.setY(640);

        hostMatchService.tick(0.016);

        assertTrue(sessionService.getButtonSwitch().isPressed());
        assertTrue(sessionService.getDoor().isOpen());
        assertTrue(host.getScore() >= 25);
    }

    @Test
    void hostResetsRoomWhenPlayerFalls() {
        EventBus eventBus = new EventBus();
        SessionService sessionService = new SessionService(eventBus);
        HostMatchService hostMatchService = new HostMatchService(sessionService, eventBus);

        Player host = new Player("host", "Host", "red");
        sessionService.addPlayer(host);
        sessionService.setGameRunning(true);
        hostMatchService.initWorld();

        double initialSpawnX = host.getX();
        host.setY(1200);

        hostMatchService.tick(0.016);

        assertTrue(sessionService.getRoomResetCount() > 0);
        assertTrue(host.getY() < 900);
        assertTrue(host.getX() == initialSpawnX);
        assertFalse(sessionService.getDoor().isOpen());
        assertTrue(host.getDeaths() > 0);
    }

    @Test
    void levelCompletesWhenAllConnectedPlayersReachExit() {
        EventBus eventBus = new EventBus();
        SessionService sessionService = new SessionService(eventBus);
        HostMatchService hostMatchService = new HostMatchService(sessionService, eventBus);

        Player host = new Player("host", "Host", "red");
        Player guest = new Player("guest", "Guest", "blue");
        sessionService.addPlayer(host);
        sessionService.addPlayer(guest);
        sessionService.setGameRunning(true);
        hostMatchService.initWorld();

        host.setX(1570);
        host.setY(380);
        guest.setX(1600);
        guest.setY(380);

        hostMatchService.tick(0.016);

        assertTrue(host.isAtExit());
        assertTrue(guest.isAtExit());
        assertFalse(sessionService.isGameRunning());
        assertTrue(host.getScore() > 0);
        assertTrue(guest.getScore() > 0);
    }
}
