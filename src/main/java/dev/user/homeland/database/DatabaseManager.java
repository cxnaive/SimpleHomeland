package dev.user.homeland.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.user.homeland.SimpleHomelandPlugin;
import dev.user.homeland.config.ConfigManager;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
                    : "name VARCHAR_IGNORECASE(32) NOT NULL";

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

            // 迁移：添加 name 全局唯一约束（家园名称全局唯一）
            try {
                stmt.execute("ALTER TABLE homeland ADD CONSTRAINT homeland_name_unique UNIQUE (name)");
                plugin.getLogger().info("已添加 name 全局唯一约束");
            } catch (SQLException alterError) {
                boolean uniqueIndexExists;
                try {
                    uniqueIndexExists = hasSingleColumnUniqueIndex(conn, "homeland", "name");
                } catch (SQLException metadataError) {
                    alterError.addSuppressed(metadataError);
                    plugin.getLogger().log(java.util.logging.Level.SEVERE,
                            "无法确认 name 全局唯一约束状态，停止数据库初始化。", alterError);
                    throw alterError;
                }
                if (uniqueIndexExists) {
                    plugin.getLogger().fine("name 全局唯一约束已存在，跳过迁移");
                } else {
                    plugin.getLogger().log(java.util.logging.Level.WARNING,
                            "无法添加 name 全局唯一约束；请检查现有重复名称或数据库权限。", alterError);
                    throw alterError;
                }
            }

            // MySQL/MariaDB 的 utf8mb4_unicode_ci 已经按大小写不敏感处理名称。
            // H2 旧表可能仍是 VARCHAR，迁移为 VARCHAR_IGNORECASE 以对齐应用层规则。
            if (!isMySQL) {
                try {
                    if (!isH2NameCaseInsensitive(conn)) {
                        stmt.execute("ALTER TABLE homeland ALTER COLUMN name "
                                + "VARCHAR_IGNORECASE(32) NOT NULL");
                    }
                } catch (SQLException error) {
                    plugin.getLogger().log(java.util.logging.Level.SEVERE,
                            "无法迁移 H2 name 大小写不敏感列，停止数据库初始化。", error);
                    throw error;
                }
            }

            addColumnIfMissing(conn, stmt, "homeland", "world_uuid", "VARCHAR(36)");
            addColumnIfMissing(conn, stmt, "homeland", "nether_uuid", "VARCHAR(36)");
            addColumnIfMissing(conn, stmt, "homeland", "end_uuid", "VARCHAR(36)");
            addColumnIfMissing(conn, stmt, "homeland", "world_type",
                    "VARCHAR(16) DEFAULT 'default'");
            addColumnIfMissing(conn, stmt, "homeland", "overworld_reset_pending",
                    "BOOLEAN DEFAULT FALSE");
            addColumnIfMissing(conn, stmt, "homeland", "overworld_reset_previous_uuid",
                    "VARCHAR(36)");

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

            String cleanupTable = "CREATE TABLE IF NOT EXISTS homeland_world_cleanup (" +
                    "    world_key VARCHAR(64) PRIMARY KEY," +
                    "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")";
            stmt.execute(cleanupTable);

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

            addColumnIfMissing(conn, stmt, "homeland", "is_public",
                    "BOOLEAN DEFAULT FALSE");
            addColumnIfMissing(conn, stmt, "homeland", "visitor_flags", "TEXT");
            addColumnIfMissing(conn, stmt, "homeland", "custom_settings", "TEXT");

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
                addColumnIfMissing(conn, stmt, "homeland_server_status",
                        "loaded_worlds", "TEXT");
            }
        }
    }

    private void addColumnIfMissing(Connection connection, Statement statement,
                                    String table, String column, String definition)
            throws SQLException {
        if (columnExists(connection, table, column)) return;
        try {
            statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        } catch (SQLException error) {
            // 两台主服可能同时迁移同一列。成功执行 ALTER 的另一台会让本次
            // 抛出 duplicate-column；复查元数据后仅在列确实存在时吞掉该竞态。
            try {
                if (columnExists(connection, table, column)) return;
            } catch (SQLException metadataError) {
                error.addSuppressed(metadataError);
            }
            throw error;
        }
        plugin.getLogger().info("已添加 " + table + "." + column + " 列");
    }

    private boolean columnExists(Connection connection, String table, String column)
            throws SQLException {
        try (ResultSet columns = connection.getMetaData().getColumns(
                connection.getCatalog(), null, null, null)) {
            while (columns.next()) {
                if (table.equalsIgnoreCase(columns.getString("TABLE_NAME"))
                        && column.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean hasSingleColumnUniqueIndex(Connection connection, String table,
                                               String column) throws SQLException {
        var metadata = connection.getMetaData();
        String metadataTable = metadata.storesUpperCaseIdentifiers()
                ? table.toUpperCase(java.util.Locale.ROOT)
                : metadata.storesLowerCaseIdentifiers() ? table.toLowerCase(java.util.Locale.ROOT) : table;
        Map<String, Set<String>> indexColumns = new HashMap<>();
        try (ResultSet indexes = metadata.getIndexInfo(
                connection.getCatalog(), null, metadataTable, true, false)) {
            while (indexes.next()) {
                if (indexes.getBoolean("NON_UNIQUE")) continue;
                String indexName = indexes.getString("INDEX_NAME");
                String indexColumn = indexes.getString("COLUMN_NAME");
                if (indexName == null || indexColumn == null) continue;
                indexColumns.computeIfAbsent(indexName, ignored -> new HashSet<>())
                        .add(indexColumn);
            }
        }
        return indexColumns.values().stream().anyMatch(columns -> columns.size() == 1
                && columns.stream().anyMatch(column::equalsIgnoreCase));
    }

    private boolean isH2NameCaseInsensitive(Connection connection) throws SQLException {
        try (ResultSet columns = connection.getMetaData().getColumns(
                connection.getCatalog(), null, null, null)) {
            while (columns.next()) {
                if ("homeland".equalsIgnoreCase(columns.getString("TABLE_NAME"))
                        && "name".equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
                    return "VARCHAR_IGNORECASE".equalsIgnoreCase(columns.getString("TYPE_NAME"));
                }
            }
        }
        return false;
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
