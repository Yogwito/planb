package com.dino.domain.entities;

import com.dino.config.GameConfig;

/**
 * Entidad principal del dominio para representar a un jugador en lobby y partida.
 *
 * <p>Es un objeto mutable y serializable por snapshot. Acumula estado de
 * movimiento, puntaje, conexión y progreso dentro de la campaña.</p>
 */
public class Player {
    private String id;
    private String name;
    private String color;
    private double x;
    private double y;
    private double vx;
    private double vy;
    private double coyoteTimer;
    private boolean grounded;
    private boolean alive;
    private boolean atExit;
    private double targetX;
    private int score;
    private int deaths;
    private int finishOrder;
    private boolean connected;
    private boolean ready;

    /** Constructor vacío requerido para reconstrucción por snapshot. */
    public Player() {}

    /**
     * Crea un jugador listo para registrarse en la sesión.
     *
     * @param id identificador único
     * @param name nombre visible
     * @param color color asignado dentro del juego
     */
    public Player(String id, String name, String color) {
        this.id = id;
        this.name = name;
        this.color = color;
        this.alive = true;
        this.connected = true;
        this.ready = false;
        this.targetX = 0;
    }

    /** @return ancho jugable del personaje según la configuración global */
    public double getWidth() {
        return GameConfig.PLAYER_WIDTH;
    }

    /** @return alto jugable del personaje según la configuración global */
    public double getHeight() {
        return GameConfig.PLAYER_HEIGHT;
    }

    /** @return coordenada X del centro del jugador */
    public double getCenterX() {
        return x + getWidth() / 2.0;
    }

    /** @return coordenada Y del centro del jugador */
    public double getCenterY() {
        return y + getHeight() / 2.0;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }

    /**
     * Suma o resta puntaje sin permitir valores negativos.
     *
     * @param delta cambio de puntaje a aplicar
     */
    public void addScore(int delta) {
        this.score = Math.max(0, this.score + delta);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public double getX() { return x; }
    public void setX(double x) { this.x = x; }
    public double getY() { return y; }
    public void setY(double y) { this.y = y; }
    public double getVx() { return vx; }
    public void setVx(double vx) { this.vx = vx; }
    public double getVy() { return vy; }
    public void setVy(double vy) { this.vy = vy; }
    public double getCoyoteTimer() { return coyoteTimer; }
    public void setCoyoteTimer(double coyoteTimer) { this.coyoteTimer = coyoteTimer; }
    public boolean isGrounded() { return grounded; }
    public void setGrounded(boolean grounded) { this.grounded = grounded; }
    public boolean isAlive() { return alive; }
    public void setAlive(boolean alive) { this.alive = alive; }
    public boolean isAtExit() { return atExit; }
    public void setAtExit(boolean atExit) { this.atExit = atExit; }
    public double getTargetX() { return targetX; }
    public void setTargetX(double targetX) { this.targetX = targetX; }
    public int getDeaths() { return deaths; }
    public void setDeaths(int deaths) { this.deaths = deaths; }
    public int getFinishOrder() { return finishOrder; }
    public void setFinishOrder(int finishOrder) { this.finishOrder = finishOrder; }
    public boolean isConnected() { return connected; }
    public void setConnected(boolean connected) { this.connected = connected; }
    public boolean isReady() { return ready; }
    public void setReady(boolean ready) { this.ready = ready; }
}
