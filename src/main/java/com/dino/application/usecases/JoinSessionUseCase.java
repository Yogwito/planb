package com.dino.application.usecases;

import com.dino.application.services.EventBus;
import com.dino.application.services.SessionService;
import com.dino.domain.entities.Player;
import com.dino.domain.events.EventNames;
import com.dino.infrastructure.network.UdpPeer;
import com.dino.infrastructure.serialization.MessageSerializer;

import java.net.InetAddress;
import java.util.Map;
import java.util.UUID;

public class JoinSessionUseCase {
    private final SessionService sessionService;
    private final UdpPeer udpPeer;
    private final MessageSerializer serializer;
    private final EventBus eventBus;

    public JoinSessionUseCase(SessionService sessionService, UdpPeer udpPeer,
                               MessageSerializer serializer, EventBus eventBus) {
        this.sessionService = sessionService;
        this.udpPeer = udpPeer;
        this.serializer = serializer;
        this.eventBus = eventBus;
    }

    public void execute(String playerName, String localIp, int localPort,
                        String hostIp, int hostPort) throws Exception {
        String playerId = UUID.randomUUID().toString();
        sessionService.setLocalPlayerId(playerId);
        sessionService.setLocalIp(localIp);
        sessionService.setLocalPort(localPort);
        sessionService.setHostIp(hostIp);
        sessionService.setHostPort(hostPort);
        sessionService.setHost(false);
        sessionService.setPlayerName(playerName);

        Player self = new Player(playerId, playerName, "blue");
        sessionService.addPlayer(self);

        udpPeer.bind(localIp, localPort);
        Map<String, Object> joinMsg = serializer.build(MessageSerializer.JOIN, "playerId", playerId, "name", playerName);
        udpPeer.send(joinMsg, InetAddress.getByName(hostIp), hostPort);

        eventBus.publish(EventNames.PLAYER_JOINED, Map.of("playerId", playerId, "name", playerName));
    }
}
