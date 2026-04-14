package dev.user.homeland;

import dev.user.homeland.command.HomelandCommand;
import dev.user.homeland.config.ConfigManager;
import dev.user.homeland.database.DatabaseManager;
import dev.user.homeland.database.DatabaseQueue;
import dev.user.homeland.economy.EconomyManager;
import dev.user.homeland.economy.PlayerPointsManager;
import dev.user.homeland.gui.GUIManager;
import dev.user.homeland.gui.GUIListener;
import dev.user.homeland.listener.PlayerListener;
import dev.user.homeland.listener.HomelandProtectionListener;
import dev.user.homeland.listener.CommandBlockListener;
import dev.user.homeland.listener.TeleportListener;
import dev.user.homeland.listener.WorldLoadListener;
import dev.user.homeland.manager.HomelandManager;
import dev.user.homeland.placeholder.HomelandExpansion;
import net.thenextlvl.worlds.api.WorldsProvider;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class SimpleHomelandPlugin extends JavaPlugin {

    private static SimpleHomelandPlugin instance;

    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private DatabaseQueue databaseQueue;
    private EconomyManager economyManager;
    private PlayerPointsManager playerPointsManager;
    private HomelandManager homelandManager;
    private WorldsProvider worldsProvider;
    private HomelandExpansion homelandExpansion;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        this.configManager = new ConfigManager(this);
        configManager.load();

        this.databaseManager = new DatabaseManager(this);
        if (!databaseManager.init()) {
            getLogger().severe("数据库初始化失败，插件将禁用！");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (configManager.getDatabaseType().equalsIgnoreCase("h2")) {
            getLogger().warning("============================================");
            getLogger().warning("当前使用 H2 数据库（本地文件模式）");
            getLogger().warning("H2 不支持多服务器同时访问！");
            getLogger().warning("如需跨服部署，请改用 MySQL 数据库");
            getLogger().warning("============================================");
        } else {
            getLogger().info("使用 MySQL 数据库，支持跨服部署");
        }

        this.databaseQueue = new DatabaseQueue(this);

        this.economyManager = new EconomyManager(this);
        economyManager.init();

        this.playerPointsManager = new PlayerPointsManager(this);
        playerPointsManager.init();

        // 获取 Worlds API
        this.worldsProvider = getServer().getServicesManager().load(WorldsProvider.class);
        if (worldsProvider == null) {
            getLogger().severe("未找到 Worlds 插件，插件无法运行！");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 家园管理器
        this.homelandManager = new HomelandManager(this);
        homelandManager.startAutoUnloadTask();
        for (Player player : getServer().getOnlinePlayers()) {
            homelandManager.onPlayerJoin(player.getUniqueId());
        }

        // 事件监听器
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new GUIListener(), this);
        getServer().getPluginManager().registerEvents(new TeleportListener(this), this);
        getServer().getPluginManager().registerEvents(new WorldLoadListener(this), this);
        getServer().getPluginManager().registerEvents(new HomelandProtectionListener(this), this);
        getServer().getPluginManager().registerEvents(new CommandBlockListener(this), this);

        // 注册命令
        HomelandCommand cmdExecutor = new HomelandCommand(this);
        getCommand("homeland").setExecutor(cmdExecutor);
        getCommand("homeland").setTabCompleter(cmdExecutor);

        // PlaceholderAPI 扩展（硬依赖）
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            this.homelandExpansion = new HomelandExpansion(this);
            if (homelandExpansion.register()) {
                getLogger().info("PlaceholderAPI 扩展已注册！");
            } else {
                getLogger().severe("PlaceholderAPI 扩展注册失败！");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        } else {
            getLogger().severe("未找到 PlaceholderAPI，插件无法运行！");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getLogger().info("SimpleHomeland 插件已启用！");
    }

    @Override
    public void onDisable() {
        if (homelandExpansion != null) {
            homelandExpansion.unregister();
        }

        if (homelandManager != null) {
            homelandManager.shutdown();
        }

        if (economyManager != null) {
            economyManager.shutdown();
        }

        if (databaseQueue != null) {
            databaseQueue.shutdown();
        }

        if (databaseManager != null) {
            databaseManager.close();
        }

        GUIManager.closeAll();

        getLogger().info("SimpleHomeland 插件已禁用！");
        instance = null;
    }

    public void reload() {
        configManager.load();
        getLogger().info("配置已重载！");
    }

    // ==================== Getters ====================

    public static SimpleHomelandPlugin getInstance() { return instance; }
    public ConfigManager getConfigManager() { return configManager; }
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public DatabaseQueue getDatabaseQueue() { return databaseQueue; }
    public EconomyManager getEconomyManager() { return economyManager; }
    public PlayerPointsManager getPlayerPointsManager() { return playerPointsManager; }
    public HomelandManager getHomelandManager() { return homelandManager; }
    public WorldsProvider getWorldsProvider() { return worldsProvider; }
}