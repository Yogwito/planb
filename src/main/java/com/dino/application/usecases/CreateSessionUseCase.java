package com.dino.application.usecases;

import com.dino.application.services.EventBus;
import com.dino.application.services.SessionService;
import com.dino.domain.entities.Player;
import com.dino.domain.events.EventNames;
import com.dino.infrastructure.network.UdpPeer;

import java.util.Map;
import java.util.UUID;

public class CreateSessionUseCase {
    private final SessionService sessionService;
    private final UdpPeer udpPeer;
    private final EventBus eventBus;

    public CreateSessionUseCase(SessionService sessionService, UdpPeer udpPeer, EventBus eventBus) {
        this.sessionService = sessionService;
        this.udpPeer = udpPeer;
        this.eventBus = eventBus;
    }

    public void execute(String playerName, String localIp, int localPort, int expectedPlayers) throws Exception {
        String playerId = UUID.randomUUID().toString();
        sessionService.setLocalPlayerId(playerId);
        sessionService.setLocalIp(localIp);
        sessionService.setLocalPort(localPort);
        sessionService.setHostIp(localIp);
        sessionService.setHostPort(localPort);
        sessionService.setHost(true);
        sessionService.setPlayerName(playerName);
        sessionService.setExpectedPlayers(expectedPlayers);

        Player host = new Player(playerId, playerName, "red");
        host.setX(200); host.setY(200);
        sessionService.addPlayer(host);

        udpPeer.bind(localIp, localPort);
        eventBus.publish(EventNames.PLAYER_JOINED, Map.of("playerId", playerId, "name", playerName));
    }
}
