package com.dino.domain.rules;

import com.dino.config.GameConfig;
import com.dino.domain.entities.ButtonSwitch;
import com.dino.domain.entities.Door;
import com.dino.domain.entities.ExitZone;
import com.dino.domain.entities.PlatformTile;
import com.dino.domain.entities.Player;

import java.util.Collection;
import java.util.List;

public final class GameRules {
    private GameRules() {}

    public static boolean intersects(double ax, double ay, double aw, double ah,
                                     double bx, double by, double bw, double bh) {
        return ax < bx + bw && ax + aw > bx && ay < by + bh && ay + ah > by;
    }

    public static boolean intersects(Player player, PlatformTile platform) {
        return intersects(player.getX(), player.getY(), player.getWidth(), player.getHeight(),
            platform.getX(), platform.getY(), platform.getWidth(), platform.getHeight());
    }

    public static boolean intersects(Player player, Door door) {
        if (door == null || door.isOpen()) return false;
        return intersects(player.getX(), player.getY(), player.getWidth(), player.getHeight(),
            door.getX(), door.getY(), door.getWidth(), door.getHeight());
    }

    public static boolean isPressingButton(Player player, ButtonSwitch button) {
        if (player == null || button == null || !player.isAlive()) return false;
        return intersects(player.getX(), player.getY(), player.getWidth(), player.getHeight(),
            button.getX(), button.getY(), button.getWidth(), button.getHeight());
    }

    public static boolean isInsideExit(Player player, ExitZone exitZone) {
        if (player == null || exitZone == null || !player.isAlive()) return false;
        return intersects(player.getX(), player.getY(), player.getWidth(), player.getHeight(),
            exitZone.getX(), exitZone.getY(), exitZone.getWidth(), exitZone.getHeight());
    }

    public static boolean allConnectedPlayersAtExit(Collection<Player> players) {
        boolean hasConnectedPlayers = false;
        for (Player player : players) {
            if (!player.isConnected()) continue;
            hasConnectedPlayers = true;
            if (!player.isAtExit()) return false;
        }
        return hasConnectedPlayers;
    }

    public static boolean violatesThreadDistance(Player movingPlayer, Collection<Player> players) {
        for (Player other : players) {
            if (other == movingPlayer || !other.isConnected() || !other.isAlive()) continue;
            double dx = movingPlayer.getCenterX() - other.getCenterX();
            double dy = movingPlayer.getCenterY() - other.getCenterY();
            double distanceSquared = dx * dx + dy * dy;
            if (distanceSquared > GameConfig.THREAD_MAX_DISTANCE * GameConfig.THREAD_MAX_DISTANCE) {
                return true;
            }
        }
        return false;
    }

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
