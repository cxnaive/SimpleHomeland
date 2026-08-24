package dev.user.homeland.manager;

import dev.user.homeland.SimpleHomelandPlugin;
import dev.user.homeland.config.ConfigManager;
import dev.user.homeland.util.MessageUtil;
import org.bukkit.entity.Player;

final class HomelandEconomy {

    private final SimpleHomelandPlugin plugin;

    HomelandEconomy(SimpleHomelandPlugin plugin) {
        this.plugin = plugin;
    }

    boolean withdraw(Player player, double money, int points) {
        ConfigManager config = plugin.getConfigManager();
        if (money > 0 && !plugin.getEconomyManager().isEnabled()) {
            MessageUtil.send(player, config.getMessage("economy-plugin-missing"));
            return false;
        }
        if (points > 0 && !plugin.getPlayerPointsManager().isEnabled()) {
            MessageUtil.send(player, config.getMessage("points-plugin-missing"));
            return false;
        }

        if (money > 0 && plugin.getEconomyManager().isEnabled()
                && !plugin.getEconomyManager().withdraw(
                player.getUniqueId(), player.getName(), money)) {
            MessageUtil.send(player, config.getMessage(
                    "not-enough-money", "money", String.format("%.2f", money)));
            return false;
        }

        if (points > 0 && plugin.getPlayerPointsManager().isEnabled()
                && !plugin.getPlayerPointsManager().withdraw(player.getUniqueId(), points)) {
            if (money > 0 && plugin.getEconomyManager().isEnabled()) {
                plugin.getEconomyManager().deposit(
                        player.getUniqueId(), player.getName(), money);
            }
            MessageUtil.send(player, config.getMessage(
                    "not-enough-points", "points", String.valueOf(points)));
            return false;
        }
        return true;
    }

    void refund(Player player, double money, int points) {
        if (player == null) return;
        if (money > 0 && plugin.getEconomyManager().isEnabled()
                && !plugin.getEconomyManager().deposit(
                player.getUniqueId(), player.getName(), money)) {
            plugin.getLogger().warning("退还金币失败! 玩家: " + player.getName()
                    + ", 金额: " + money);
        }
        if (points > 0 && plugin.getPlayerPointsManager().isEnabled()
                && !plugin.getPlayerPointsManager().deposit(player.getUniqueId(), points)) {
            plugin.getLogger().warning("退还点券失败! 玩家: " + player.getName()
                    + ", 数量: " + points);
        }
    }
}
