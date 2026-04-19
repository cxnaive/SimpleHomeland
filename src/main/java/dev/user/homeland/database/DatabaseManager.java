package dev.user.homeland.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.user.homeland.SimpleHomelandPlugin;
import dev.user.homeland.config.ConfigManager;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseManager {

    private final SimpleHomelandPlugin plugin;
    private HikariDataSource dataSource;

    public DatabaseManager(SimpleHomelandPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean init() {
        close();

        try {
            ConfigManager config = plugin.getConfigManager();
            String type = config.getDatabaseType();

            if (type.equalsIgnoreCase("mysql")) {
                initMySQL();
            } else {
                initH2();
            }

            createTables();

            plugin.getLogger().info("数据库连接成功！类型: " + type);
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "数据库初始化失败: " + e.getMessage(), e);
            return false;
        }
    }

    private void initMySQL() {
        HikariConfig config = new HikariConfig();
        ConfigManager cfg = plugin.getConfigManager();

        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setJdbcUrl(String.format("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                cfg.getMysqlHost(), cfg.getMysqlPort(), cfg.getMysqlDatabase()));
        config.setUsername(cfg.getMysqlUsername());
        config.setPassword(cfg.getMysqlPassword());
        config.setMaximumPoolSize(cfg.getMysqlPoolSize());
        config.setMinimumIdle(2);
        config.setConnectionTimeout(10000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);

        dataSource = new HikariDataSource(config);
    }

    private void initH2() {
        HikariConfig config = new HikariConfig();
        String filename = plugin.getConfigManager().getH2Filename();
        File dataFolder = new File(plugin.getDataFolder(), "data");
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        config.setDriverClassName("org.h2.Driver");
        config.setJdbcUrl("jdbc:h2:" + new File(dataFolder, filename).getAbsolutePath() +
                ";AUTO_RECONNECT=TRUE;DB_CLOSE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE");
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setConnectionTestQuery("SELECT 1");

        dataSource = new HikariDataSource(config);
    }

    private void createTables() throws SQLException {
        boolean isMySQL = isMySQL();
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {

            String idColumn = isMySQL ? "id INT AUTO_INCREMENT PRIMARY KEY" : "id INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY";

            String nameColumn = isMySQL
                    ? "name VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL"
                    : "name VARCHAR(32) NOT NULL";

            String homelandTable = "CREATE TABLE IF NOT EXISTS homeland (" +
                    idColumn + "," +
                    "    owner_uuid VARCHAR(36) NOT NULL," +
                    "    " + nameColumn + "," +
                    "    world_key VARCHAR(64) NOT NULL," +
                    "    world_uuid VARCHAR(36)," +
                    "    border_radius INT NOT NULL DEFAULT 500," +
                    "    has_nether BOOLEAN DEFAULT FALSE," +
                    "    has_end BOOLEAN DEFAULT FALSE," +
                    "    is_public BOOLEAN DEFAULT FALSE," +
                    "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "    UNIQUE (owner_uuid, world_key)," +
                    "    UNIQUE (owner_uuid, name)" +
                    ")";
            stmt.execute(homelandTable);

            // 迁移：为已有表添加 world_uuid 列
            try {
                stmt.execute("ALTER TABLE homeland ADD COLUMN world_uuid VARCHAR(36)");
                plugin.getLogger().info("已添加 world_uuid 列");
            } catch (SQLException e) {
                // 列已存在，忽略
            }

            // 迁移：为已有表添加 world_type 列
            try {
                stmt.execute("ALTER TABLE homeland ADD COLUMN world_type VARCHAR(16) DEFAULT 'default'");
                plugin.getLogger().info("已添加 world_type 列");
            } catch (SQLException e) {
                // 列已存在，忽略
            }

            // 索引
            String[][] indexes = {
                    {"idx_homeland_owner", "homeland", "owner_uuid"},
                    {"idx_homeland_world_key", "homeland", "world_key"}
            };

            for (String[] index : indexes) {
                try {
                    stmt.execute("CREATE INDEX IF NOT EXISTS " + index[0] + " ON " + index[1] + " (" + index[2] + ")");
                } catch (SQLException e) {
                    String state = e.getSQLState();
                    if (state != null && (state.equals("42P07") || state.equals("X0Y32"))
                            || e.getErrorCode() == 1061) {
                        plugin.getLogger().fine("索引 " + index[0] + " 已存在，跳过创建");
                    } else {
                        String msg = e.getMessage().toLowerCase();
                        if (msg.contains("duplicate") || msg.contains("already exists")) {
                            plugin.getLogger().fine("索引 " + index[0] + " 已存在，跳过创建");
                        } else {
                            throw e;
                        }
                    }
                }
            }

            plugin.getLogger().info("数据库表创建/检查完成");

            // 邀请表
            String inviteTable = "CREATE TABLE IF NOT EXISTS homeland_invite (" +
                    idColumn + "," +
                    "    homeland_id INT NOT NULL," +
                    "    player_uuid VARCHAR(36) NOT NULL," +
                    "    invited_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "    UNIQUE (homeland_id, player_uuid)," +
                    "    FOREIGN KEY (homeland_id) REFERENCES homeland(id) ON DELETE CASCADE" +
                    ")";
            stmt.execute(inviteTable);

            String[][] inviteIndexes = {
                    {"idx_invite_homeland", "homeland_invite", "homeland_id"},
                    {"idx_invite_player", "homeland_invite", "player_uuid"}
            };

            for (String[] index : inviteIndexes) {
                try {
                    stmt.execute("CREATE INDEX IF NOT EXISTS " + index[0] + " ON " + index[1] + " (" + index[2] + ")");
                } catch (SQLException e) {
                    String state = e.getSQLState();
                    if (state != null && (state.equals("42P07") || state.equals("X0Y32"))
                            || e.getErrorCode() == 1061) {
                        plugin.getLogger().fine("索引 " + index[0] + " 已存在，跳过创建");
                    } else {
                        String msg = e.getMessage().toLowerCase();
                        if (msg.contains("duplicate") || msg.contains("already exists")) {
                            plugin.getLogger().fine("索引 " + index[0] + " 已存在，跳过创建");
                        } else {
                            plugin.getLogger().warning("创建索引 " + index[0] + " 失败: " + e.getMessage());
                        }
                    }
                }
            }

            // 兼容旧库：添加 is_public 列
            try {
                stmt.execute("ALTER TABLE homeland ADD COLUMN is_public BOOLEAN DEFAULT FALSE");
            } catch (SQLException ignored) {
                // 列已存在，忽略
            }

            // 兼容旧库：添加 visitor_flags 列
            try {
                stmt.execute("ALTER TABLE homeland ADD COLUMN visitor_flags TEXT");
            } catch (SQLException ignored) {
                // 列已存在，忽略
            }

            // 跨服消息表（仅 MySQL）
            if (isMySQL) {
                String messageTable = "CREATE TABLE IF NOT EXISTS homeland_messages (" +
                        idColumn + "," +
                        "    source_server_id VARCHAR(64) NOT NULL," +
                        "    target_server_id VARCHAR(64) NOT NULL," +
                        "    message_type VARCHAR(32) NOT NULL," +
                        "    payload TEXT," +
                        "    created_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3)," +
                        "    processed BOOLEAN DEFAULT FALSE" +
                        ")";
                stmt.execute(messageTable);
                try {
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_msg_poll ON homeland_messages (target_server_id, processed, created_at)");
                } catch (SQLException ignored) {}

                String statusTable = "CREATE TABLE IF NOT EXISTS homeland_server_status (" +
                        "    server_id VARCHAR(64) PRIMARY KEY," +
                        "    server_mode VARCHAR(16) NOT NULL," +
                        "    online_players TEXT," +
                        "    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP" +
                        ")";
                stmt.execute(statusTable);
                try {
                    stmt.execute("ALTER TABLE homeland_server_status ADD COLUMN loaded_worlds TEXT");
                } catch (SQLException ignored) {}
            }
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

    public boolean isMySQL() {
        String dbType = plugin.getConfigManager().getDatabaseType().toLowerCase();
        return dbType.equals("mysql") || dbType.equals("mariadb");
    }
}
