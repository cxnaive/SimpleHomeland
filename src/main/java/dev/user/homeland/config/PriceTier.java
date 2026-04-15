package dev.user.homeland.config;

public class PriceTier {

    private final double money;
    private final int points;

    public PriceTier(double money, int points) {
        this.money = money;
        this.points = points;
    }

    public double getMoney() { return money; }
    public int getPoints() { return points; }
}
