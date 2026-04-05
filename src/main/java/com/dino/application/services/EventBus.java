package com.dino.application.services;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

public class EventBus {
    private final Map<String, List<Consumer<Map<String, Object>>>> subscribers = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    public void subscribe(String event, Consumer<Map<String, Object>> callback) {
        lock.lock();
        try {
            subscribers.computeIfAbsent(event, k -> new ArrayList<>()).add(callback);
        } finally {
            lock.unlock();
        }
    }

    public void unsubscribe(String event, Consumer<Map<String, Object>> callback) {
        lock.lock();
        try {
            List<Consumer<Map<String, Object>>> list = subscribers.get(event);
            if (list != null) list.remove(callback);
        } finally {
            lock.unlock();
        }
    }

    public void publish(String event, Map<String, Object> payload) {
        List<Consumer<Map<String, Object>>> snapshot;
        lock.lock();
        try {
            List<Consumer<Map<String, Object>>> list = subscribers.get(event);
            snapshot = list != null ? new ArrayList<>(list) : Collections.emptyList();
        } finally {
            lock.unlock();
        }
        for (Consumer<Map<String, Object>> cb : snapshot) {
            try { cb.accept(payload); }
            catch (Exception e) { System.err.println("[EventBus] Error in " + event + ": " + e.getMessage()); }
        }
    }
}
