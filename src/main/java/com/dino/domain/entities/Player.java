package com.dino.domain.entities;

public class Player {
    private String id;
    private String name;
    private String color;
    private double x;
    private double y;
    private double mass;
    private boolean connected;
    private boolean ready;

    public Player() {}

    public Player(String id, String name, String color) {
        this.id = id;
        this.name = name;
        this.color = color;
        this.mass = 28.0;
        this.connected = true;
        this.ready = false;
    }

    public void addMass(double delta) {
        setMass(this.mass + delta);
    }

    public void setMass(double mass) {
        this.mass = Math.max(0, mass);
    }

    public double getMass() {
        return mass;
    }

    public double getRadius(double radiusScale) {
        return Math.max(10.0, Math.sqrt(Math.max(1.0, mass)) * radiusScale);
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
    public int getScore() { return (int) Math.round(this.mass); }
    public void setScore(int score) { setMass(score); }
    public boolean isConnected() { return connected; }
    public void setConnected(boolean connected) { this.connected = connected; }
    public boolean isReady() { return ready; }
    public void setReady(boolean ready) { this.ready = ready; }
}
