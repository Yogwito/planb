package com.dino.infrastructure.network;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.*;
import java.util.*;

/**
 * Adaptador mínimo sobre {@link DatagramSocket} para la comunicación UDP.
 *
 * <p>Su responsabilidad es encapsular el envío y recepción de mapas
 * serializados como JSON, manteniendo una API pequeña para el resto del
 * proyecto. No implementa reglas de juego ni sincronización avanzada; solo
 * transporte.</p>
 */
public class UdpPeer {
    private DatagramSocket socket;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final int BUFFER_SIZE = 65535;
    private final byte[] receiveBuffer = new byte[BUFFER_SIZE];

    /**
     * Enlaza el socket UDP local.
     *
     * @param ip IP local a usar; si es {@code 0.0.0.0} o vacía, escucha en todas
     *           las interfaces
     * @param port puerto local
     * @throws IOException si el socket no puede abrirse
     */
    public void bind(String ip, int port) throws IOException {
        if (ip == null || ip.isBlank() || ip.equals("0.0.0.0")) {
            socket = new DatagramSocket(port);
        } else {
            socket = new DatagramSocket(port, InetAddress.getByName(ip));
        }
        socket.setSoTimeout(1); // 1ms timeout for near-non-blocking receive
    }

    /**
     * Envía un datagrama UDP al destino indicado.
     *
     * @param data mensaje ya construido como mapa
     * @param addr dirección IP destino
     * @param port puerto destino
     */
    public void send(Map<String, Object> data, InetAddress addr, int port) {
        try {
            byte[] bytes = mapper.writeValueAsBytes(data);
            socket.send(new DatagramPacket(bytes, bytes.length, addr, port));
        } catch (IOException e) {
            System.err.println("[UdpPeer] Send error: " + e.getMessage());
        }
    }

    /**
     * Envía el mismo mensaje a varios peers.
     *
     * @param data contenido a enviar
     * @param addrs lista de destinos remotos
     */
    public void broadcast(Map<String, Object> data, List<InetSocketAddress> addrs) {
        for (InetSocketAddress addr : addrs) {
            send(data, addr.getAddress(), addr.getPort());
        }
    }

    /**
     * Repite varias veces un envío importante para reducir la pérdida percibida.
     *
     * <p>Se usa en transiciones críticas como {@code START_GAME} y
     * {@code GAME_OVER}, donde perder un único datagrama sería muy visible.</p>
     *
     * @param data mensaje a reenviar
     * @param addrs peers destino
     * @param repeats cantidad de repeticiones
     * @param delayMs pausa entre repeticiones
     */
    public void broadcastBurst(Map<String, Object> data, List<InetSocketAddress> addrs, int repeats, int delayMs) {
        if (repeats <= 0) return;
        Thread.ofPlatform().daemon(true).start(() -> {
            for (int i = 0; i < repeats; i++) {
                broadcast(data, addrs);
                if (i + 1 < repeats) {
                    try {
                        Thread.sleep(Math.max(1, delayMs));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        });
    }

    /**
     * Intenta recibir un datagrama sin bloquear perceptiblemente el loop.
     *
     * @return mensaje y dirección de origen si hubo datos; vacío en timeout o error
     */
    @SuppressWarnings("unchecked")
    public Optional<Map.Entry<Map<String, Object>, InetSocketAddress>> receive() {
        if (socket == null || socket.isClosed()) return Optional.empty();
        try {
            DatagramPacket packet = new DatagramPacket(receiveBuffer, receiveBuffer.length);
            socket.receive(packet);
            byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
            Map<String, Object> msg = mapper.readValue(data, Map.class);
            InetSocketAddress sender = new InetSocketAddress(packet.getAddress(), packet.getPort());
            return Optional.of(Map.entry(msg, sender));
        } catch (SocketTimeoutException e) {
            return Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Cierra el socket si está abierto.
     */
    public void close() {
        if (socket != null && !socket.isClosed()) socket.close();
    }

    /**
     * Indica si el socket está listo para enviar y recibir.
     *
     * @return {@code true} si el socket fue enlazado y no está cerrado
     */
    public boolean isBound() {
        return socket != null && socket.isBound() && !socket.isClosed();
    }
}
