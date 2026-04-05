package com.dino.domain.rules;

import com.dino.config.GameConfig;
import com.dino.domain.entities.ButtonSwitch;
import com.dino.domain.entities.Door;
import com.dino.domain.entities.ExitZone;
import com.dino.domain.entities.PlatformTile;
import com.dino.domain.entities.Player;
import com.dino.domain.entities.PushBlock;

import java.util.Collection;
import java.util.List;

/**
 * Reglas puras del dominio.
 *
 * <p>Concentra validaciones y cálculos sin efectos secundarios: colisiones,
 * activación de zonas, restricción del hilo y condiciones de salida. Esto evita
 * duplicar reglas entre la simulación del host y la presentación.</p>
 */
public final class GameRules {
    /** Clase utilitaria: no debe instanciarse. */
    private GameRules() {}

    /**
     * Evalúa intersección AABB entre dos rectángulos.
     */
    public static boolean intersects(double ax, double ay, double aw, double ah,
                                     double bx, double by, double bw, double bh) {
        return ax < bx + bw && ax + aw > bx && ay < by + bh && ay + ah > by;
    }

    /**
     * Determina si el jugador está colisionando con una plataforma.
     */
    public static boolean intersects(Player player, PlatformTile platform) {
        return intersects(player.getX(), player.getY(), player.getWidth(), player.getHeight(),
            platform.getX(), platform.getY(), platform.getWidth(), platform.getHeight());
    }

    /**
     * Determina si el jugador colisiona con una puerta cerrada.
     */
    public static boolean intersects(Player player, Door door) {
        if (door == null || door.isOpen()) return false;
        return intersects(player.getX(), player.getY(), player.getWidth(), player.getHeight(),
            door.getX(), door.getY(), door.getWidth(), door.getHeight());
    }

    /**
     * Determina si dos jugadores se superponen.
     */
    public static boolean intersects(Player a, Player b) {
        if (a == null || b == null || a == b) return false;
        return intersects(a.getX(), a.getY(), a.getWidth(), a.getHeight(),
            b.getX(), b.getY(), b.getWidth(), b.getHeight());
    }

    /**
     * Determina si un jugador empuja o colisiona con un bloque móvil.
     */
    public static boolean intersects(Player player, PushBlock block) {
        if (player == null || block == null) return false;
        return intersects(player.getX(), player.getY(), player.getWidth(), player.getHeight(),
            block.getX(), block.getY(), block.getWidth(), block.getHeight());
    }

    /**
     * Determina si un bloque móvil está colisionando con una plataforma.
     */
    public static boolean intersects(PushBlock block, PlatformTile platform) {
        if (block == null || platform == null) return false;
        return intersects(block.getX(), block.getY(), block.getWidth(), block.getHeight(),
            platform.getX(), platform.getY(), platform.getWidth(), platform.getHeight());
    }

    /**
     * Determina si un bloque móvil colisiona con una puerta cerrada.
     */
    public static boolean intersects(PushBlock block, Door door) {
        if (block == null || door == null || door.isOpen()) return false;
        return intersects(block.getX(), block.getY(), block.getWidth(), block.getHeight(),
            door.getX(), door.getY(), door.getWidth(), door.getHeight());
    }

    /**
     * Verifica si un jugador está presionando el botón del nivel.
     */
    public static boolean isPressingButton(Player player, ButtonSwitch button) {
        if (player == null || button == null || !player.isAlive()) return false;
        return intersects(player.getX(), player.getY(), player.getWidth(), player.getHeight(),
            button.getX(), button.getY(), button.getWidth(), button.getHeight());
    }

    /**
     * Verifica si un jugador se encuentra dentro de la salida.
     */
    public static boolean isInsideExit(Player player, ExitZone exitZone) {
        if (player == null || exitZone == null || !player.isAlive()) return false;
        return intersects(player.getX(), player.getY(), player.getWidth(), player.getHeight(),
            exitZone.getX(), exitZone.getY(), exitZone.getWidth(), exitZone.getHeight());
    }

    /**
     * Confirma si todos los jugadores conectados ya están dentro de la meta.
     */
    public static boolean allConnectedPlayersAtExit(Collection<Player> players) {
        boolean hasConnectedPlayers = false;
        for (Player player : players) {
            if (!player.isConnected()) continue;
            hasConnectedPlayers = true;
            if (!player.isAtExit()) return false;
        }
        return hasConnectedPlayers;
    }

    /**
     * Indica si un desplazamiento viola la distancia máxima permitida por el hilo.
     */
    public static boolean violatesThreadDistance(Player movingPlayer, Collection<Player> players) {
        for (Player other : players) {
            if (other == movingPlayer || !other.isConnected() || !other.isAlive()) continue;
            if (distance(movingPlayer, other) > GameConfig.THREAD_HARD_LIMIT) {
                return true;
            }
        }
        return false;
    }

    /**
     * Calcula la distancia entre los centros de dos jugadores.
     */
    public static double distance(Player a, Player b) {
        double dx = a.getCenterX() - b.getCenterX();
        double dy = a.getCenterY() - b.getCenterY();
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * Busca una plataforma que esté sosteniendo actualmente al jugador.
     *
     * @param player jugador a evaluar
     * @param platforms plataformas del nivel
     * @return plataforma de soporte o {@code null} si no existe una coincidencia
     */
    public static PlatformTile findSupportingPlatform(Player player, List<PlatformTile> platforms) {
        for (PlatformTile platform : platforms) {
            boolean withinX = player.getX() + player.getWidth() > platform.getX()
                && player.getX() < platform.getX() + platform.getWidth();
            boolean onTop = Math.abs((player.getY() + player.getHeight()) - platform.getY()) < 2.5;
            if (withinX && onTop) return platform;
        }
        return null;
    }
}
