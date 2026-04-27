package dev.user.homeland.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.user.homeland.SimpleHomelandPlugin;
import dev.user.homeland.config.ConfigManager;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * PlayerWarp 插件的独立数据库连接池。
 * 用于查询 warp_player 表解析地标对应的家园世界。
 */
public class PlayerWarpDb {

    private final SimpleHomelandPlugin plugin;
    private HikariDataSource dataSource;

    public PlayerWarpDb(SimpleHomelandPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean init() {
        ConfigManager cfg = plugin.getConfigManager();
        HikariConfig config = new HikariConfig();
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setJdbcUrl(String.format("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                cfg.getPlayerwarpDbHost(), cfg.getPlayerwarpDbPort(), cfg.getPlayerwarpDbDatabase()));
        config.setUsername(cfg.getPlayerwarpDbUsername());
        config.setPassword(cfg.getPlayerwarpDbPassword());
        config.setMaximumPoolSize(cfg.getPlayerwarpDbPoolSize());
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);

        try {
            dataSource = new HikariDataSource(config);
            plugin.getLogger().info("PlayerWarp 数据库连接成功！");
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("PlayerWarp 数据库连接失败: " + e.getMessage());
            return false;
        }
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
