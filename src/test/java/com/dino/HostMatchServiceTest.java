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

        host.setX(870); // within button (850..930) on step_b (y=674)
        host.setY(624); // bottom=676 → resolves onto step_b, then intersects button at y=658

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
    void levelAdvancesWhenAllConnectedPlayersReachExit() {
        EventBus eventBus = new EventBus();
        SessionService sessionService = new SessionService(eventBus);
        HostMatchService hostMatchService = new HostMatchService(sessionService, eventBus);

        Player host = new Player("host", "Host", "red");
        Player guest = new Player("guest", "Guest", "blue");
        sessionService.addPlayer(host);
        sessionService.addPlayer(guest);
        sessionService.setGameRunning(true);
        hostMatchService.initWorld();

        host.setX(1580);  // within exitZone (1560..1680) on exit_p (y=560)
        host.setY(510);   // bottom=562 → resolves onto exit_p, then inside exitZone y=450..560
        guest.setX(1620);
        guest.setY(510);

        hostMatchService.tick(0.016);

        assertTrue(sessionService.isGameRunning());
        assertTrue(sessionService.getCurrentLevelIndex() == 1);
        assertTrue(host.getScore() > 0);
        assertTrue(guest.getScore() > 0);
    }

    @Test
    void campaignEndsOnLastLevel() {
        EventBus eventBus = new EventBus();
        SessionService sessionService = new SessionService(eventBus);
        HostMatchService hostMatchService = new HostMatchService(sessionService, eventBus);

        Player host = new Player("host", "Host", "red");
        Player guest = new Player("guest", "Guest", "blue");
        sessionService.addPlayer(host);
        sessionService.addPlayer(guest);
        sessionService.setGameRunning(true);
        hostMatchService.initWorld();

        sessionService.setCurrentLevelIndex(2);
        sessionService.setExitZone(new com.dino.domain.entities.ExitZone(100, 700, 220, 120));
        host.setX(120);
        host.setY(720);
        guest.setX(180);
        guest.setY(720);

        hostMatchService.tick(0.016);

        assertFalse(sessionService.isGameRunning());
    }

    @Test
    void elasticThreadPullsPlayersBackBeforeHardBreak() {
        EventBus eventBus = new EventBus();
        SessionService sessionService = new SessionService(eventBus);
        HostMatchService hostMatchService = new HostMatchService(sessionService, eventBus);

        Player host = new Player("host", "Host", "red");
        Player guest = new Player("guest", "Guest", "blue");
        sessionService.addPlayer(host);
        sessionService.addPlayer(guest);
        sessionService.setGameRunning(true);
        hostMatchService.initWorld();

        host.setX(120);
        host.setY(740);
        guest.setX(360);
        guest.setY(740);

        double beforeDistance = Math.abs(guest.getCenterX() - host.getCenterX());
        hostMatchService.tick(0.016);
        double afterDistance = Math.abs(guest.getCenterX() - host.getCenterX());

        assertTrue(afterDistance < beforeDistance);
    }

    @Test
    void playersDoNotEndTickOverlapping() {
        EventBus eventBus = new EventBus();
        SessionService sessionService = new SessionService(eventBus);
        HostMatchService hostMatchService = new HostMatchService(sessionService, eventBus);

        Player host = new Player("host", "Host", "red");
        Player guest = new Player("guest", "Guest", "blue");
        sessionService.addPlayer(host);
        sessionService.addPlayer(guest);
        sessionService.setGameRunning(true);
        hostMatchService.initWorld();

        host.setX(200);
        host.setY(740);
        guest.setX(210);
        guest.setY(740);

        hostMatchService.tick(0.016);

        assertTrue(host.getX() + host.getWidth() <= guest.getX()
            || guest.getX() + guest.getWidth() <= host.getX()
            || host.getY() + host.getHeight() <= guest.getY()
            || guest.getY() + guest.getHeight() <= host.getY());
    }
}
