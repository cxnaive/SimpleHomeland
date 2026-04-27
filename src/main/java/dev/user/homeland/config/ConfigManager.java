package dev.user.homeland.config;

import dev.user.homeland.SimpleHomelandPlugin;
import dev.user.homeland.model.VisitorFlags;
import org.bukkit.ChatColor;
import org.bukkit.GameRule;
import org.bukkit.GameRules;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ConfigManager {

    private final SimpleHomelandPlugin plugin;
    private FileConfiguration config;
    private FileConfiguration messagesConfig;
    private FileConfiguration rulesConfig;

    // 数据库配置
    private String databaseType;
    private String h2Filename;
    private String mysqlHost;
    private int mysqlPort;
    private String mysqlDatabase;
    private String mysqlUsername;
    private String mysqlPassword;
    private int mysqlPoolSize;

    // 家园配置
    private int maxHomelands;
    private int defaultBorderRadius;
    private int maxBorderRadius;
    private int borderExpandStep;
    private double netherUnlockMoney;
    private int netherUnlockPoints;
    private double endUnlockMoney;
    private int endUnlockPoints;
    private double resetOverworldMoney;
    private int resetOverworldPoints;
    private double resetNetherMoney;
    private int resetNetherPoints;
    private double resetEndMoney;
    private int resetEndPoints;
    private List<PriceTier> creationTiers;
    private List<ExpansionPriceTier> expansionTiers;
    private String worldSeed;
    private boolean worldStructures;
    private boolean worldBonusChest;
    private int spawnYDefault;
    private int spawnYVoid;
    private int spawnYFlat;
    private String defaultWorldType;
    private String defaultDifficulty;
    private int autoUnloadSeconds;
    private boolean preloadWorlds;

    // 命令拦截配置
    private boolean commandBlockEnabled;
    private List<String> blockedCommands;
    private boolean commandBlockOwner;

    // 游戏规则配置
    private List<GameRuleConfig> gameruleConfigs = new ArrayList<>();
    private List<GameRuleConfig> enabledGameruleConfigs = new ArrayList<>();

    // GUI 布局配置
    private GUIConfig guiConfig;

    // 群系更改配置
    private double biomeChangeMoney;
    private int biomeChangePoints;
    private List<BiomeEntry> biomeEntries = new ArrayList<>();

    // 大厅访客权限
    private VisitorFlags lobbyVisitorFlags;

    // 跨服模式配置
    private ServerMode serverMode;
    private String serverId;
    private String mainServer;
    private int pollInterval;
    private int heartbeatInterval;
    private int staleRequestSeconds;

    // PlayerWarp 数据库配置
    private boolean playerwarpDbEnabled;
    private String playerwarpDbHost;
    private int playerwarpDbPort;
    private String playerwarpDbDatabase;
    private String playerwarpDbUsername;
    private String playerwarpDbPassword;
    private int playerwarpDbPoolSize;

    // 消息渠道配置
    private String messageChannel;
    private String redisHost;
    private int redisPort;
    private String redisPassword;
    private int redisDatabase;
    private int redisPoolSize;

    public ConfigManager(SimpleHomelandPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        this.config = plugin.getConfig();

        // 数据库配置
        this.databaseType = config.getString("database.type", "h2");

        // 跨服模式配置
        String modeStr = config.getString("mode", "main");
        this.serverMode = modeStr.equalsIgnoreCase("branch") ? ServerMode.BRANCH : ServerMode.MAIN;
        this.serverId = config.getString("server-id", "survival");
        this.mainServer = config.getString("main-server", "survival");
        this.pollInterval = config.getInt("poll-interval-seconds", 3);
        this.heartbeatInterval = config.getInt("heartbeat-interval-seconds", 30);
        this.staleRequestSeconds = config.getInt("stale-request-seconds", 60);

        // 消息渠道配置
        this.messageChannel = config.getString("message-channel", "mysql").toLowerCase();
        if (!this.messageChannel.equals("mysql") && !this.messageChannel.equals("redis")) {
            plugin.getLogger().warning("无效的 message-channel 值: " + this.messageChannel + ", 使用默认值 mysql");
            this.messageChannel = "mysql";
        }
        this.redisHost = config.getString("redis.host", "localhost");
        this.redisPort = config.getInt("redis.port", 6379);
        this.redisPassword = config.getString("redis.password", "");
        this.redisDatabase = config.getInt("redis.database", 0);
        this.redisPoolSize = config.getInt("redis.pool-size", 4);

        this.h2Filename = config.getString("database.h2.file", "homeland");
        this.mysqlHost = config.getString("database.mysql.host", "localhost");
        this.mysqlPort = config.getInt("database.mysql.port", 3306);
        this.mysqlDatabase = config.getString("database.mysql.database", "homeland");
        this.mysqlUsername = config.getString("database.mysql.user", "root");
        this.mysqlPassword = config.getString("database.mysql.password", "");
        this.mysqlPoolSize = config.getInt("database.mysql.pool-size", 5);

        // PlayerWarp 数据库配置
        this.playerwarpDbEnabled = config.getBoolean("playerwarp-database.enabled", false);
        this.playerwarpDbHost = config.getString("playerwarp-database.host", "localhost");
        this.playerwarpDbPort = config.getInt("playerwarp-database.port", 3306);
        this.playerwarpDbDatabase = config.getString("playerwarp-database.database", "playerwarp");
        this.playerwarpDbUsername = config.getString("playerwarp-database.user", "root");
        this.playerwarpDbPassword = config.getString("playerwarp-database.password", "");
        this.playerwarpDbPoolSize = config.getInt("playerwarp-database.pool-size", 2);

        // 家园配置
        this.maxHomelands = config.getInt("homeland.max-homelands", 1);
        this.defaultBorderRadius = config.getInt("homeland.default-border-radius", 500);
        this.maxBorderRadius = config.getInt("homeland.max-border-radius", 5000);
        this.borderExpandStep = config.getInt("homeland.border-expand-step", 100);
        this.netherUnlockMoney = config.getDouble("homeland.nether-unlock.money", 500.0);
        this.netherUnlockPoints = config.getInt("homeland.nether-unlock.points", 0);
        this.endUnlockMoney = config.getDouble("homeland.end-unlock.money", 1000.0);
        this.endUnlockPoints = config.getInt("homeland.end-unlock.points", 0);
        this.resetOverworldMoney = config.getDouble("homeland.reset.overworld.money", 500.0);
        this.resetOverworldPoints = config.getInt("homeland.reset.overworld.points", 0);
        this.resetNetherMoney = config.getDouble("homeland.reset.nether.money", 300.0);
        this.resetNetherPoints = config.getInt("homeland.reset.nether.points", 0);
        this.resetEndMoney = config.getDouble("homeland.reset.end.money", 300.0);
        this.resetEndPoints = config.getInt("homeland.reset.end.points", 0);
        // 创建费用阶梯
        List<?> creationTierList = config.getMapList("homeland.creation.tiers");
        if (creationTierList.isEmpty()) {
            this.creationTiers = List.of(new PriceTier(
                    config.getDouble("homeland.creation.money", 1000.0),
                    config.getInt("homeland.creation.points", 0)));
        } else {
            this.creationTiers = parseCreationTiers(creationTierList);
        }

        // 扩展费用阶梯
        List<?> expansionTierList = config.getMapList("homeland.expansion.tiers");
        if (expansionTierList.isEmpty()) {
            this.expansionTiers = List.of(new ExpansionPriceTier(0,
                    config.getDouble("homeland.expansion.money", 100.0),
                    config.getInt("homeland.expansion.points", 0)));
        } else {
            this.expansionTiers = parseExpansionTiers(expansionTierList);
        }
        this.worldSeed = config.getString("homeland.world.seed", "");
        this.worldStructures = config.getBoolean("homeland.world.structures", true);
        this.worldBonusChest = config.getBoolean("homeland.world.bonus-chest", false);
        this.spawnYDefault = config.getInt("homeland.world.spawn-y.default", -60);
        this.spawnYVoid = config.getInt("homeland.world.spawn-y.void", 63);
        this.spawnYFlat = config.getInt("homeland.world.spawn-y.flat", -60);
        this.defaultWorldType = config.getString("homeland.world-type", "default");
        this.defaultDifficulty = config.getString("homeland.default-difficulty", "hard");
        if (!List.of("peaceful", "easy", "normal", "hard").contains(this.defaultDifficulty.toLowerCase())) {
            plugin.getLogger().warning("无效的 default-difficulty: " + this.defaultDifficulty + ", 使用 normal");
            this.defaultDifficulty = "normal";
        }
        this.autoUnloadSeconds = config.getInt("homeland.auto-unload-seconds", 600);
        this.preloadWorlds = config.getBoolean("homeland.preload-worlds", false);

        // 命令拦截配置
        this.commandBlockEnabled = config.getBoolean("command-block.enabled", true);
        this.blockedCommands = config.getStringList("command-block.blocked-commands")
                .stream().map(String::toLowerCase).toList();
        this.commandBlockOwner = config.getBoolean("command-block.block-owner", false);

        // 游戏规则配置
        loadRulesConfig();
        gameruleConfigs.clear();
        enabledGameruleConfigs.clear();
        ConfigurationSection grSection = rulesConfig.getConfigurationSection("gamerules");
        if (grSection != null) {
            for (String key : grSection.getKeys(false)) {
                ConfigurationSection sec = grSection.getConfigurationSection(key);
                if (sec == null) continue;
                boolean enabled = sec.getBoolean("enabled", false);
                if (sec.contains("default-value") || enabled) {
                    try {
                        GameRuleConfig grc = new GameRuleConfig(key, sec);
                        gameruleConfigs.add(grc);
                        if (enabled) {
                            enabledGameruleConfigs.add(grc);
                        }
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("未知的 GameRule: " + key + "，跳过");
                    }
                }
            }
        }

        loadMessagesConfig();
        loadGUIConfig();
        loadBiomesConfig();

        // 大厅访客权限
        this.lobbyVisitorFlags = VisitorFlags.fromJson(config.getString("lobby-visitor-flags", ""));
    }

    private void loadRulesConfig() {
        File rulesFile = new File(plugin.getDataFolder(), "rules.yml");
        if (!rulesFile.exists()) {
            plugin.saveResource("rules.yml", false);
        }
        this.rulesConfig = YamlConfiguration.loadConfiguration(rulesFile);

        try (InputStreamReader defaultReader = new InputStreamReader(
                plugin.getResource("rules.yml"), StandardCharsets.UTF_8)) {
            YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(defaultReader);
            this.rulesConfig.setDefaults(defaultConfig);
        } catch (Exception e) {
            plugin.getLogger().warning("加载默认规则配置失败: " + e.getMessage());
        }
    }

    private void loadMessagesConfig() {
        File messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        this.messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);

        try (InputStreamReader defaultReader = new InputStreamReader(
                plugin.getResource("messages.yml"), StandardCharsets.UTF_8)) {
            YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(defaultReader);
            this.messagesConfig.setDefaults(defaultConfig);
        } catch (Exception e) {
            plugin.getLogger().warning("加载默认消息配置失败: " + e.getMessage());
        }
    }

    private void loadGUIConfig() {
        File guiFile = new File(plugin.getDataFolder(), "gui.yml");
        if (!guiFile.exists()) {
            plugin.saveResource("gui.yml", false);
        }
        FileConfiguration guiYaml = YamlConfiguration.loadConfiguration(guiFile);

        try (InputStreamReader defaultReader = new InputStreamReader(
                plugin.getResource("gui.yml"), StandardCharsets.UTF_8)) {
            YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(defaultReader);
            guiYaml.setDefaults(defaultConfig);
        } catch (Exception e) {
            plugin.getLogger().warning("加载默认GUI配置失败: " + e.getMessage());
        }
        this.guiConfig = new GUIConfig(guiYaml);
    }

    public String getMessage(String key, String... replacements) {
        String message = messagesConfig.getString(key, "");
        if (message.isEmpty()) {
            return "§c消息未找到: " + key;
        }
        for (int i = 0; i < replacements.length - 1; i += 2) {
            message = message.replace("{" + replacements[i] + "}", replacements[i + 1]);
        }
        // 拼接前缀（跳过 prefix 本身和 usage 帮助信息）
        if (!key.equals("prefix") && !key.startsWith("usage.") && !key.startsWith("gui.") && !key.startsWith("papi")) {
            String prefix = messagesConfig.getString("prefix", "");
            if (!prefix.isEmpty()) {
                message = prefix + message;
            }
        }
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    // ==================== Getters ====================

    public String getDatabaseType() { return databaseType; }
    public String getH2Filename() { return h2Filename; }
    public String getMysqlHost() { return mysqlHost; }
    public int getMysqlPort() { return mysqlPort; }
    public String getMysqlDatabase() { return mysqlDatabase; }
    public String getMysqlUsername() { return mysqlUsername; }
    public String getMysqlPassword() { return mysqlPassword; }
    public int getMysqlPoolSize() { return mysqlPoolSize; }

    public boolean isPlayerwarpDbEnabled() { return playerwarpDbEnabled; }
    public String getPlayerwarpDbHost() { return playerwarpDbHost; }
    public int getPlayerwarpDbPort() { return playerwarpDbPort; }
    public String getPlayerwarpDbDatabase() { return playerwarpDbDatabase; }
    public String getPlayerwarpDbUsername() { return playerwarpDbUsername; }
    public String getPlayerwarpDbPassword() { return playerwarpDbPassword; }
    public int getPlayerwarpDbPoolSize() { return playerwarpDbPoolSize; }

    public int getMaxHomelands() { return maxHomelands; }
    public int getDefaultBorderRadius() { return defaultBorderRadius; }
    public int getMaxBorderRadius() { return maxBorderRadius; }
    public int getBorderExpandStep() { return borderExpandStep; }
    public double getNetherUnlockMoney() { return netherUnlockMoney; }
    public int getNetherUnlockPoints() { return netherUnlockPoints; }
    public double getEndUnlockMoney() { return endUnlockMoney; }
    public int getEndUnlockPoints() { return endUnlockPoints; }
    public double getResetOverworldMoney() { return resetOverworldMoney; }
    public int getResetOverworldPoints() { return resetOverworldPoints; }
    public double getResetNetherMoney() { return resetNetherMoney; }
    public int getResetNetherPoints() { return resetNetherPoints; }
    public double getResetEndMoney() { return resetEndMoney; }
    public int getResetEndPoints() { return resetEndPoints; }

    public PriceTier getCreationCost(int currentCount) {
        if (creationTiers.isEmpty()) return new PriceTier(0, 0);
        int index = Math.min(currentCount, creationTiers.size() - 1);
        return creationTiers.get(index);
    }

    public PriceTier getExpansionCost(int currentRadius) {
        if (expansionTiers.isEmpty()) return new PriceTier(0, 0);
        // 按 minRadius 降序找第一个匹配的档位
        for (int i = expansionTiers.size() - 1; i >= 0; i--) {
            if (currentRadius >= expansionTiers.get(i).getMinRadius()) {
                ExpansionPriceTier tier = expansionTiers.get(i);
                return new PriceTier(tier.getMoney(), tier.getPoints());
            }
        }
        ExpansionPriceTier first = expansionTiers.get(0);
        return new PriceTier(first.getMoney(), first.getPoints());
    }
    public String getWorldSeed() { return worldSeed; }
    public boolean isWorldStructures() { return worldStructures; }
    public boolean isWorldBonusChest() { return worldBonusChest; }
    public int getSpawnYDefault() { return spawnYDefault; }
    public int getSpawnYVoid() { return spawnYVoid; }
    public int getSpawnYFlat() { return spawnYFlat; }
    public String getDefaultWorldType() { return defaultWorldType; }
    public String getDefaultDifficulty() { return defaultDifficulty; }
    public int getAutoUnloadSeconds() { return autoUnloadSeconds; }
    public boolean isPreloadWorlds() { return preloadWorlds; }

    public boolean isCommandBlockEnabled() { return commandBlockEnabled; }
    public List<String> getBlockedCommands() { return blockedCommands; }
    public boolean isCommandBlockOwner() { return commandBlockOwner; }

    public List<GameRuleConfig> getEnabledGameruleConfigs() {
        return Collections.unmodifiableList(enabledGameruleConfigs);
    }

    public List<GameRuleConfig> getAllGameruleConfigs() {
        return Collections.unmodifiableList(gameruleConfigs);
    }

    public GUIConfig getGUIConfig() { return guiConfig; }

    public double getBiomeChangeMoney() { return biomeChangeMoney; }
    public int getBiomeChangePoints() { return biomeChangePoints; }
    public List<BiomeEntry> getBiomeEntries() { return Collections.unmodifiableList(biomeEntries); }

    public VisitorFlags getLobbyVisitorFlags() { return lobbyVisitorFlags; }
    public void saveLobbyVisitorFlags(VisitorFlags flags) {
        this.lobbyVisitorFlags = flags;
        config.set("lobby-visitor-flags", flags.toJson());
        plugin.saveConfig();
    }

    public ServerMode getServerMode() { return serverMode; }
    public boolean isBranchMode() { return serverMode == ServerMode.BRANCH; }
    public String getServerId() { return serverId; }
    public String getMainServer() { return mainServer; }
    public int getPollInterval() { return pollInterval; }
    public int getHeartbeatInterval() { return heartbeatInterval; }
    public int getStaleRequestSeconds() { return staleRequestSeconds; }

    public String getMessageChannel() { return messageChannel; }
    public boolean isRedisChannel() { return "redis".equals(messageChannel); }
    public String getRedisHost() { return redisHost; }
    public int getRedisPort() { return redisPort; }
    public String getRedisPassword() { return redisPassword; }
    public int getRedisDatabase() { return redisDatabase; }
    public int getRedisPoolSize() { return redisPoolSize; }

    // ==================== 价格阶梯解析 ====================

    private void loadBiomesConfig() {
        File biomesFile = new File(plugin.getDataFolder(), "biomes.yml");
        if (!biomesFile.exists()) {
            plugin.saveResource("biomes.yml", false);
        }
        FileConfiguration biomesYaml = YamlConfiguration.loadConfiguration(biomesFile);

        try (InputStreamReader defaultReader = new InputStreamReader(
                plugin.getResource("biomes.yml"), StandardCharsets.UTF_8)) {
            YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(defaultReader);
            biomesYaml.setDefaults(defaultConfig);
        } catch (Exception e) {
            plugin.getLogger().warning("加载默认群系配置失败: " + e.getMessage());
        }

        ConfigurationSection costSection = biomesYaml.getConfigurationSection("biome-change.cost");
        this.biomeChangeMoney = costSection != null ? costSection.getDouble("money", 100.0) : 100.0;
        this.biomeChangePoints = costSection != null ? costSection.getInt("points", 0) : 0;

        this.biomeEntries.clear();
        ConfigurationSection biomesSection = biomesYaml.getConfigurationSection("biomes");
        if (biomesSection != null) {
            for (String key : biomesSection.getKeys(false)) {
                ConfigurationSection sec = biomesSection.getConfigurationSection(key);
                if (sec == null) continue;
                try {
                    @SuppressWarnings("deprecation")
                    Biome biome = Biome.valueOf(key.toUpperCase());
                    String name = sec.getString("name", key);
                    Material material;
                    try {
                        material = Material.valueOf(sec.getString("material", "GRASS_BLOCK").toUpperCase());
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("群系 " + key + " 的材质无效: " + e.getMessage());
                        material = Material.GRASS_BLOCK;
                    }
                    List<String> lore = sec.getStringList("lore");
                    double costOverrideMoney = sec.contains("cost-override.money") ? sec.getDouble("cost-override.money") : -1;
                    int costOverridePoints = sec.contains("cost-override.points") ? sec.getInt("cost-override.points") : -1;
                    biomeEntries.add(new BiomeEntry(biome, name, material, lore, costOverrideMoney, costOverridePoints));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("跳过无效的群系: " + key);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<PriceTier> parseCreationTiers(List<?> tierList) {
        List<PriceTier> tiers = new ArrayList<>();
        for (Object obj : tierList) {
            if (obj instanceof Map<?, ?> map) {
                Map<String, Object> m = (Map<String, Object>) map;
                double money = m.containsKey("money") ? ((Number) m.get("money")).doubleValue() : 0;
                int points = m.containsKey("points") ? ((Number) m.get("points")).intValue() : 0;
                tiers.add(new PriceTier(money, points));
            }
        }
        if (tiers.isEmpty()) tiers.add(new PriceTier(1000.0, 0));
        return tiers;
    }

    @SuppressWarnings("unchecked")
    private List<ExpansionPriceTier> parseExpansionTiers(List<?> tierList) {
        List<ExpansionPriceTier> tiers = new ArrayList<>();
        for (Object obj : tierList) {
            if (obj instanceof Map<?, ?> map) {
                Map<String, Object> m = (Map<String, Object>) map;
                int minRadius = m.containsKey("min-radius") ? ((Number) m.get("min-radius")).intValue() : 0;
                double money = m.containsKey("money") ? ((Number) m.get("money")).doubleValue() : 0;
                int points = m.containsKey("points") ? ((Number) m.get("points")).intValue() : 0;
                tiers.add(new ExpansionPriceTier(minRadius, money, points));
            }
        }
        if (tiers.isEmpty()) tiers.add(new ExpansionPriceTier(0, 100.0, 0));
        tiers.sort((a, b) -> Integer.compare(a.getMinRadius(), b.getMinRadius()));
        return tiers;
    }

    // ==================== GameRuleConfig 内部类 ====================

    @SuppressWarnings("deprecation")
    public static class GameRuleConfig {
        private static final Map<String, GameRule<?>> GAME_RULE_MAP = Map.ofEntries(
                // 生存相关
                Map.entry("KEEP_INVENTORY", GameRules.KEEP_INVENTORY),
                Map.entry("NATURAL_HEALTH_REGENERATION", GameRules.NATURAL_HEALTH_REGENERATION),
                Map.entry("IMMEDIATE_RESPAWN", GameRules.IMMEDIATE_RESPAWN),
                Map.entry("SHOW_DEATH_MESSAGES", GameRules.SHOW_DEATH_MESSAGES),
                // 伤害相关
                Map.entry("DROWNING_DAMAGE", GameRules.DROWNING_DAMAGE),
                Map.entry("FALL_DAMAGE", GameRules.FALL_DAMAGE),
                Map.entry("FIRE_DAMAGE", GameRules.FIRE_DAMAGE),
                Map.entry("FREEZE_DAMAGE", GameRules.FREEZE_DAMAGE),
                // 生物相关
                Map.entry("SPAWN_MOBS", GameRules.SPAWN_MOBS),
                Map.entry("SPAWN_MONSTERS", GameRules.SPAWN_MONSTERS),
                Map.entry("SPAWN_PHANTOMS", GameRules.SPAWN_PHANTOMS),
                Map.entry("SPAWN_PATROLS", GameRules.SPAWN_PATROLS),
                Map.entry("SPAWN_WARDENS", GameRules.SPAWN_WARDENS),
                Map.entry("SPAWN_WANDERING_TRADERS", GameRules.SPAWN_WANDERING_TRADERS),
                Map.entry("SPAWNER_BLOCKS_WORK", GameRules.SPAWNER_BLOCKS_WORK),
                Map.entry("MOB_GRIEFING", GameRules.MOB_GRIEFING),
                Map.entry("MOB_DROPS", GameRules.MOB_DROPS),
                Map.entry("FORGIVE_DEAD_PLAYERS", GameRules.FORGIVE_DEAD_PLAYERS),
                Map.entry("UNIVERSAL_ANGER", GameRules.UNIVERSAL_ANGER),
                Map.entry("RAIDS", GameRules.RAIDS),
                // 方块/物品掉落
                Map.entry("BLOCK_DROPS", GameRules.BLOCK_DROPS),
                Map.entry("ENTITY_DROPS", GameRules.ENTITY_DROPS),
                Map.entry("TNT_EXPLODES", GameRules.TNT_EXPLODES),
                Map.entry("TNT_EXPLOSION_DROP_DECAY", GameRules.TNT_EXPLOSION_DROP_DECAY),
                Map.entry("BLOCK_EXPLOSION_DROP_DECAY", GameRules.BLOCK_EXPLOSION_DROP_DECAY),
                Map.entry("MOB_EXPLOSION_DROP_DECAY", GameRules.MOB_EXPLOSION_DROP_DECAY),
                Map.entry("PROJECTILES_CAN_BREAK_BLOCKS", GameRules.PROJECTILES_CAN_BREAK_BLOCKS),
                // 世界/环境
                Map.entry("ADVANCE_TIME", GameRules.ADVANCE_TIME),
                Map.entry("ADVANCE_WEATHER", GameRules.ADVANCE_WEATHER),
                Map.entry("FIRE_SPREAD_RADIUS_AROUND_PLAYER", GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER),
                Map.entry("SPREAD_VINES", GameRules.SPREAD_VINES),
                Map.entry("WATER_SOURCE_CONVERSION", GameRules.WATER_SOURCE_CONVERSION),
                Map.entry("LAVA_SOURCE_CONVERSION", GameRules.LAVA_SOURCE_CONVERSION),
                Map.entry("RANDOM_TICK_SPEED", GameRules.RANDOM_TICK_SPEED),
                // 玩家互动
                Map.entry("PVP", GameRules.PVP),
                Map.entry("ALLOW_ENTERING_NETHER_USING_PORTALS", GameRules.ALLOW_ENTERING_NETHER_USING_PORTALS),
                Map.entry("ENDER_PEARLS_VANISH_ON_DEATH", GameRules.ENDER_PEARLS_VANISH_ON_DEATH),
                Map.entry("PLAYERS_SLEEPING_PERCENTAGE", GameRules.PLAYERS_SLEEPING_PERCENTAGE),
                Map.entry("RESPAWN_RADIUS", GameRules.RESPAWN_RADIUS),
                Map.entry("MAX_ENTITY_CRAMMING", GameRules.MAX_ENTITY_CRAMMING),
                Map.entry("GLOBAL_SOUND_EVENTS", GameRules.GLOBAL_SOUND_EVENTS),
                Map.entry("SHOW_ADVANCEMENT_MESSAGES", GameRules.SHOW_ADVANCEMENT_MESSAGES)
        );

        private final String key;
        private final Material material;
        private final String name;
        private final List<String> lore;
        private final String defaultValue;
        private final GameRule<?> gameRule;
        private final boolean isBooleanType;

        public GameRuleConfig(String key, ConfigurationSection sec) {
            this.key = key;
            this.material = Material.valueOf(sec.getString("material", "PAPER"));
            this.name = sec.getString("name", key);
            this.lore = sec.getStringList("lore");
            this.defaultValue = sec.getString("default-value", "false");
            this.gameRule = GAME_RULE_MAP.get(key);
            if (this.gameRule == null) {
                throw new IllegalArgumentException("未知的 GameRule: " + key);
            }
            this.isBooleanType = this.gameRule.getType() == Boolean.class;
        }

        public String getKey() { return key; }
        public Material getMaterial() { return material; }
        public String getName() { return name; }
        public List<String> getLore() { return lore; }
        public String getDefaultValue() { return defaultValue; }
        public GameRule<?> getGameRule() { return gameRule; }
        public boolean isBooleanType() { return isBooleanType; }
    }

    // ==================== BiomeEntry 内部类 ====================

    public static class BiomeEntry {
        private final Biome biome;
        private final String name;
        private final Material material;
        private final List<String> lore;
        private final double costOverrideMoney;
        private final int costOverridePoints;

        public BiomeEntry(Biome biome, String name, Material material, List<String> lore,
                          double costOverrideMoney, int costOverridePoints) {
            this.biome = biome;
            this.name = name;
            this.material = material;
            this.lore = lore;
            this.costOverrideMoney = costOverrideMoney;
            this.costOverridePoints = costOverridePoints;
        }

        public Biome getBiome() { return biome; }
        public String getName() { return name; }
        public Material getMaterial() { return material; }
        public List<String> getLore() { return lore; }
        public double getCostOverrideMoney() { return costOverrideMoney; }
        public int getCostOverridePoints() { return costOverridePoints; }
        public boolean hasCostOverride() { return costOverrideMoney >= 0 || costOverridePoints >= 0; }
    }
}
