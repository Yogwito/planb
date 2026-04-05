package com.dino.domain.entities;

/**
 * Plataforma estática rectangular del nivel.
 *
 * <p>Se usa para suelo, escalones y superficies de apoyo. Es inmutable a nivel
 * conceptual, aunque se serializa como DTO simple para snapshots.</p>
 */
public class PlatformTile {
    private String id;
    private double x;
    private double y;
    private double width;
    private double height;

    public PlatformTile() {}

    public PlatformTile(String id, double x, double y, double width, double height) {
        this.id = id;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public double getX() { return x; }
    public void setX(double x) { this.x = x; }
    public double getY() { return y; }
    public void setY(double y) { this.y = y; }
    public double getWidth() { return width; }
    public void setWidth(double width) { this.width = width; }
    public double getHeight() { return height; }
    public void setHeight(double height) { this.height = height; }
}
