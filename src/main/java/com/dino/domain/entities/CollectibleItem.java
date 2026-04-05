package com.dino.domain.entities;

/**
 * Moneda o ítem coleccionable del nivel.
 *
 * <p>Entrega puntaje individual cuando un jugador la recoge y luego queda
 * inactiva hasta el siguiente reinicio de sala.</p>
 */
public class CollectibleItem {
    private String id;
    private double x;
    private double y;
    private int points;
    private boolean active;

    public CollectibleItem() {}

    public CollectibleItem(String id, double x, double y, int points) {
        this.id = id;
        this.x = x;
        this.y = y;
        this.points = points;
        this.active = true;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public double getX() { return x; }
    public void setX(double x) { this.x = x; }
    public double getY() { return y; }
    public void setY(double y) { this.y = y; }
    public int getPoints() { return points; }
    public void setPoints(int points) { this.points = points; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
