package com.dino.application.services;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Implementación sencilla del patrón Observer para eventos internos.
 *
 * <p>Permite que capas distintas del sistema se comuniquen sin acoplarse de
 * forma directa. Por ejemplo, el host publica eventos de gameplay, la UI los
 * refleja y el audio reproduce sonidos sin que unas clases conozcan a las
 * otras.</p>
 */
public class EventBus {
    private final Map<String, List<Consumer<Map<String, Object>>>> subscribers = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Registra un observador para un tipo de evento.
     *
     * @param event nombre lógico del evento
     * @param callback acción a ejecutar cuando el evento sea publicado
     */
    public void subscribe(String event, Consumer<Map<String, Object>> callback) {
        lock.lock();
        try {
            subscribers.computeIfAbsent(event, k -> new ArrayList<>()).add(callback);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Elimina un observador previamente registrado.
     *
     * @param event nombre lógico del evento
     * @param callback observador a remover
     */
    public void unsubscribe(String event, Consumer<Map<String, Object>> callback) {
        lock.lock();
        try {
            List<Consumer<Map<String, Object>>> list = subscribers.get(event);
            if (list != null) list.remove(callback);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Publica un evento a todos los observadores actuales.
     *
     * <p>El método toma una copia de los suscriptores antes de iterar para no
     * fallar si algún observador modifica suscripciones durante la notificación.</p>
     *
     * @param event nombre lógico del evento
     * @param payload datos asociados al evento
     */
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
