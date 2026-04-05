package com.dino.domain.entities;

public class PushBlock {
    private String id;
    private double x;
    private double y;
    private double width;
    private double height;
    private double vx;
    private double vy;
    private double homeX;
    private double homeY;

    public PushBlock() {}

    public PushBlock(String id, double x, double y, double width, double height) {
        this.id = id;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.homeX = x;
        this.homeY = y;
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
    public double getVx() { return vx; }
    public void setVx(double vx) { this.vx = vx; }
    public double getVy() { return vy; }
    public void setVy(double vy) { this.vy = vy; }
    public double getHomeX() { return homeX; }
    public void setHomeX(double homeX) { this.homeX = homeX; }
    public double getHomeY() { return homeY; }
    public void setHomeY(double homeY) { this.homeY = homeY; }
}
