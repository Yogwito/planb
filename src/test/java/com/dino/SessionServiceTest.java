package com.dino;

import com.dino.application.services.EventBus;
import com.dino.application.services.SessionService;
import com.dino.domain.entities.ButtonSwitch;
import com.dino.domain.entities.Door;
import com.dino.domain.entities.ExitZone;
import com.dino.domain.entities.PlatformTile;
import com.dino.domain.entities.Player;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionServiceTest {

    @Test
    void getRemotePeerAddressesExcludesLocalPlayer() {
        SessionService sessionService = new SessionService(new EventBus());

        sessionService.addPlayer(new Player("host", "Host", "red"));
        sessionService.addPlayer(new Player("guest", "Guest", "blue"));
        sessionService.setLocalPlayerId("host");
        sessionService.registerPeerAddress("host", new InetSocketAddress("127.0.0.1", 5000));
        sessionService.registerPeerAddress("guest", new InetSocketAddress("127.0.0.1", 5001));

        List<InetSocketAddress> remotes = sessionService.getRemotePeerAddresses();

        assertEquals(1, remotes.size());
        assertEquals(5001, remotes.get(0).getPort());
    }

    @Test
    void snapshotRoundTripPreservesRoomState() {
        SessionService hostSession = new SessionService(new EventBus());
        Player host = new Player("host", "Host", "red");
        host.setReady(true);
        host.setAtExit(true);
        host.setScore(125);
        host.setDeaths(1);
        host.setFinishOrder(1);
        hostSession.addPlayer(host);
        hostSession.getPlatforms().add(new PlatformTile("ground", 0, 800, 400, 50));
        hostSession.getSpawnPoints().add(new double[]{90, 700});
        ButtonSwitch button = new ButtonSwitch("button", 100, 700, 80, 16);
        button.setPressed(true);
        hostSession.setButtonSwitch(button);
        Door door = new Door("door", 200, 620, 56, 140);
        door.setOpen(true);
        hostSession.setDoor(door);
        hostSession.setExitZone(new ExitZone(300, 600, 120, 100));
        hostSession.setRoomResetCount(2);
        hostSession.setRoomResetReason("test");

        Map<String, Object> snapshot = hostSession.getSnapshotData();

        SessionService clientSession = new SessionService(new EventBus());
        clientSession.updateFromSnapshot(snapshot);

        assertEquals(1, clientSession.getPlayersSnapshot().size());
        assertTrue(clientSession.getPlayersSnapshot().get(0).isAtExit());
        assertEquals(125, clientSession.getPlayersSnapshot().get(0).getScore());
        assertEquals(1, clientSession.getPlayersSnapshot().get(0).getFinishOrder());
        assertEquals(1, clientSession.getPlatformsSnapshot().size());
        assertTrue(clientSession.getButtonSwitchSnapshot().isPressed());
        assertTrue(clientSession.getDoorSnapshot().isOpen());
        assertEquals("test", clientSession.getRoomResetReason());
    }

    @Test
    void updateFromSnapshotRemovesMissingPlayers() {
        SessionService sessionService = new SessionService(new EventBus());
        sessionService.addPlayer(new Player("host", "Host", "red"));
        sessionService.addPlayer(new Player("guest", "Guest", "blue"));

        sessionService.updateFromSnapshot(Map.of(
            "players", List.of(Map.ofEntries(
                Map.entry("id", "host"),
                Map.entry("name", "Host"),
                Map.entry("color", "red"),
                Map.entry("x", 0),
                Map.entry("y", 0),
                Map.entry("vx", 0),
                Map.entry("vy", 0),
                Map.entry("grounded", false),
                Map.entry("alive", true),
                Map.entry("atExit", false),
                Map.entry("connected", true),
                Map.entry("ready", true)
            ))
        ));

        assertEquals(1, sessionService.getPlayersSnapshot().size());
        assertEquals("host", sessionService.getPlayersSnapshot().get(0).getId());
    }
}
