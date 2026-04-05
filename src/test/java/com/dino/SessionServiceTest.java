package com.dino;

import com.dino.application.services.EventBus;
import com.dino.application.services.SessionService;
import com.dino.domain.entities.Player;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SessionServiceTest {

    @Test
    void getRemotePeerAddressesExcludesLocalPlayer() {
        SessionService sessionService = new SessionService(new EventBus());

        Player host = new Player("host", "Host", "red");
        Player guest = new Player("guest", "Guest", "blue");
        sessionService.addPlayer(host);
        sessionService.addPlayer(guest);
        sessionService.setLocalPlayerId("host");
        sessionService.registerPeerAddress("host", new InetSocketAddress("127.0.0.1", 5000));
        sessionService.registerPeerAddress("guest", new InetSocketAddress("127.0.0.1", 5001));

        List<InetSocketAddress> remotes = sessionService.getRemotePeerAddresses();

        assertEquals(1, remotes.size());
        assertEquals(5001, remotes.get(0).getPort());
    }

    @Test
    void snapshotRoundTripPreservesReadyState() {
        SessionService hostSession = new SessionService(new EventBus());
        Player host = new Player("host", "Host", "red");
        host.setReady(true);
        host.setMass(73);
        hostSession.addPlayer(host);

        Map<String, Object> snapshot = hostSession.getSnapshotData();

        SessionService clientSession = new SessionService(new EventBus());
        clientSession.updateFromSnapshot(snapshot);

        List<Player> players = clientSession.getPlayersSnapshot();
        assertEquals(1, players.size());
        assertTrue(players.get(0).isReady());
        assertEquals(73, players.get(0).getScore());
    }

    @Test
    void snapshotsAreDefensiveCopies() {
        SessionService sessionService = new SessionService(new EventBus());
        Player host = new Player("host", "Host", "red");
        sessionService.addPlayer(host);

        List<Player> snapshot = sessionService.getPlayersSnapshot();
        snapshot.get(0).setName("Changed");

        assertEquals("Host", sessionService.getPlayersSnapshot().get(0).getName());
    }

    @Test
    void updateFromSnapshotRemovesPlayersMissingFromAuthoritativeState() {
        SessionService sessionService = new SessionService(new EventBus());
        sessionService.addPlayer(new Player("host", "Host", "red"));
        sessionService.addPlayer(new Player("guest", "Guest", "blue"));

        sessionService.updateFromSnapshot(Map.of(
            "players", List.of(Map.of(
                "id", "host",
                "name", "Host",
                "color", "red",
                "x", 0,
                "y", 0,
                "mass", 28,
                "score", 28,
                "connected", true,
                "ready", true
            ))
        ));

        List<Player> players = sessionService.getPlayersSnapshot();
        assertEquals(1, players.size());
        assertEquals("host", players.get(0).getId());
    }
}
