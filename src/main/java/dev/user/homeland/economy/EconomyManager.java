package dev.user.homeland.economy;

import dev.user.homeland.SimpleHomelandPlugin;
import me.yic.xconomy.api.XConomyAPI;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.UUID;

public class EconomyManager {

    private final SimpleHomelandPlugin plugin;
    private XConomyAPI xconomyAPI;
    private boolean enabled = false;

    public EconomyManager(SimpleHomelandPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        try {
            xconomyAPI = new XConomyAPI();
            enabled = true;
            plugin.getLogger().info("已连接到 XConomy 经济系统");
        } catch (Exception e) {
            plugin.getLogger().info("XConomy 未找到，经济功能不可用: " + e.getMessage());
            enabled = false;
        }
    }

    public void shutdown() {
        // 同步模式无需清理
    }

    public boolean isEnabled() { return enabled; }

    // ==================== 同步方法 ====================

    public double getBalance(Player player) {
        return getBalanceSync(player.getUniqueId());
    }

    public boolean withdraw(UUID uuid, String name, double amount) {
        return withdrawSync(uuid, name, amount);
    }

    public boolean deposit(UUID uuid, String name, double amount) {
        return depositSync(uuid, name, amount);
    }

    public boolean hasEnough(UUID uuid, double amount) {
        return hasEnoughSync(uuid, amount);
    }

    private double getBalanceSync(UUID uuid) {
        if (!enabled) return 0;
        try {
            var playerData = xconomyAPI.getPlayerData(uuid);
            if (playerData == null) {
                plugin.getLogger().warning("获取玩家数据失败: 玩家数据为 null, UUID: " + uuid);
                return 0;
            }
            BigDecimal bal = playerData.getBalance();
            return bal.doubleValue();
        } catch (Exception e) {
            plugin.getLogger().warning("获取余额失败: " + e.getMessage());
            return 0;
        }
    }

    private boolean withdrawSync(UUID uuid, String name, double amount) {
        if (!enabled) return false;
        if (amount <= 0) return true;
        try {
            int result = xconomyAPI.changePlayerBalance(uuid, name, BigDecimal.valueOf(amount), false);
            return result == 0;
        } catch (Exception e) {
            plugin.getLogger().warning("扣除金钱失败: " + e.getMessage());
            return false;
        }
    }

    private boolean depositSync(UUID uuid, String name, double amount) {
        if (!enabled) return false;
        if (amount <= 0) return true;
        try {
            int result = xconomyAPI.changePlayerBalance(uuid, name, BigDecimal.valueOf(amount), true);
            return result == 0;
        } catch (Exception e) {
            plugin.getLogger().warning("给予金钱失败: " + e.getMessage());
            return false;
        }
    }

    private boolean hasEnoughSync(UUID uuid, double amount) {
        if (!enabled || amount <= 0) return true;
        return getBalanceSync(uuid) >= amount;
    }

    public String format(double amount) {
        return String.format("%.2f", amount);
    }
}
