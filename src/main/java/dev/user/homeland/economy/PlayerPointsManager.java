package dev.user.homeland.economy;

import dev.user.homeland.SimpleHomelandPlugin;
import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.PlayerPointsAPI;

import java.util.UUID;

public class PlayerPointsManager {

    private final SimpleHomelandPlugin plugin;
    private PlayerPointsAPI playerPointsAPI;
    private boolean enabled = false;

    public PlayerPointsManager(SimpleHomelandPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        try {
            if (plugin.getServer().getPluginManager().getPlugin("PlayerPoints") == null) {
                plugin.getLogger().info("PlayerPoints 插件未找到，点券功能不可用");
                return;
            }

            PlayerPoints playerPoints = PlayerPoints.getInstance();
            if (playerPoints == null) {
                plugin.getLogger().info("PlayerPoints 实例未找到，点券功能不可用");
                return;
            }

            this.playerPointsAPI = playerPoints.getAPI();
            if (this.playerPointsAPI == null) {
                plugin.getLogger().info("PlayerPoints API 未找到，点券功能不可用");
                return;
            }

            this.enabled = true;
            plugin.getLogger().info("已连接到 PlayerPoints 点券系统");
        } catch (Exception e) {
            plugin.getLogger().info("PlayerPoints 初始化失败: " + e.getMessage());
            this.enabled = false;
        }
    }

    public boolean isEnabled() { return enabled; }

    public int getPoints(UUID playerUuid) {
        if (!enabled || playerPointsAPI == null) return 0;
        try {
            return playerPointsAPI.look(playerUuid);
        } catch (Exception e) {
            plugin.getLogger().warning("获取点券余额失败: " + e.getMessage());
            return 0;
        }
    }

    public boolean withdraw(UUID playerUuid, int amount) {
        if (!enabled || playerPointsAPI == null) return false;
        if (amount <= 0) return true;
        try {
            return playerPointsAPI.take(playerUuid, amount);
        } catch (Exception e) {
            plugin.getLogger().warning("扣除点券失败: " + e.getMessage());
            return false;
        }
    }

    public boolean deposit(UUID playerUuid, int amount) {
        if (!enabled || playerPointsAPI == null) return false;
        if (amount <= 0) return true;
        try {
            return playerPointsAPI.give(playerUuid, amount);
        } catch (Exception e) {
            plugin.getLogger().warning("给予点券失败: " + e.getMessage());
            return false;
        }
    }

    public boolean hasEnough(UUID playerUuid, int amount) {
        if (!enabled || amount <= 0) return true;
        return getPoints(playerUuid) >= amount;
    }

    public String format(int amount) {
        return String.format("%,d", amount);
    }
}