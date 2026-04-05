package com.dino.domain.entities;

public class PenaltyZone {
    private String id;
    private double x;
    private double y;
    private double radius;
    private int points;
    private double slowMultiplier;

    public PenaltyZone() {}

    public PenaltyZone(String id, double x, double y, double radius, int points, double slowMultiplier) {
        this.id = id;
        this.x = x;
        this.y = y;
        this.radius = radius;
        this.points = points;
        this.slowMultiplier = slowMultiplier;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public double getX() { return x; }
    public void setX(double x) { this.x = x; }
    public double getY() { return y; }
    public void setY(double y) { this.y = y; }
    public double getRadius() { return radius; }
    public void setRadius(double radius) { this.radius = radius; }
    public int getPoints() { return points; }
    public void setPoints(int points) { this.points = points; }
    public double getSlowMultiplier() { return slowMultiplier; }
    public void setSlowMultiplier(double slowMultiplier) { this.slowMultiplier = slowMultiplier; }
}
