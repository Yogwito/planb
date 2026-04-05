package com.dino.infrastructure.network;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.*;
import java.util.*;

public class UdpPeer {
    private DatagramSocket socket;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final int BUFFER_SIZE = 65535;
    private final byte[] receiveBuffer = new byte[BUFFER_SIZE];

    public void bind(String ip, int port) throws IOException {
        if (ip == null || ip.isBlank() || ip.equals("0.0.0.0")) {
            socket = new DatagramSocket(port);
        } else {
            socket = new DatagramSocket(port, InetAddress.getByName(ip));
        }
        socket.setSoTimeout(1); // 1ms timeout for near-non-blocking receive
    }

    public void send(Map<String, Object> data, InetAddress addr, int port) {
        try {
            byte[] bytes = mapper.writeValueAsBytes(data);
            socket.send(new DatagramPacket(bytes, bytes.length, addr, port));
        } catch (IOException e) {
            System.err.println("[UdpPeer] Send error: " + e.getMessage());
        }
    }

    public void broadcast(Map<String, Object> data, List<InetSocketAddress> addrs) {
        for (InetSocketAddress addr : addrs) {
            send(data, addr.getAddress(), addr.getPort());
        }
    }

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

    public void close() {
        if (socket != null && !socket.isClosed()) socket.close();
    }

    public boolean isBound() {
        return socket != null && socket.isBound() && !socket.isClosed();
    }
}
