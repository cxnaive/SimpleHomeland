package dev.user.homeland.crossserver;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.user.homeland.SimpleHomelandPlugin;
import dev.user.homeland.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

public class RedisManager {

    private static final String CHANNEL_PREFIX = "shl:msg:";
    private static final String PLAYERS_PREFIX = "shl:players:";
    private static final String LOADED_WORLDS_PREFIX = "shl:worlds:";

    private final SimpleHomelandPlugin plugin;
    private final Gson gson = new Gson();

    private JedisPool jedisPool;
    private JedisPubSub pubSub;
    private volatile Thread subscribeThread;
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask heartbeatTask;
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask onlineSyncTask;

    private final ConcurrentHashMap<UUID, String> cachedOnlinePlayers = new ConcurrentHashMap<>();

    public RedisManager(SimpleHomelandPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean init() {
        ConfigManager config = plugin.getConfigManager();
        try {
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(config.getRedisPoolSize());

            String password = config.getRedisPassword();
            int database = config.getRedisDatabase();
            if (password == null || password.isEmpty()) {
                jedisPool = new JedisPool(poolConfig, config.getRedisHost(), config.getRedisPort(), 0, null, database);
            } else {
                jedisPool = new JedisPool(poolConfig, config.getRedisHost(), config.getRedisPort(), 0, password, database);
            }

            try (Jedis jedis = jedisPool.getResource()) {
                jedis.ping();
            }

            plugin.getLogger().info("Redis 连接成功: " + config.getRedisHost() + ":" + config.getRedisPort());
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("Redis 连接失败: " + e.getMessage());
            return false;
        }
    }

    // ==================== Main Mode ====================

    public void startHeartbeat() {
        ConfigManager config = plugin.getConfigManager();
        long heartbeatTicks = config.getHeartbeatInterval() * 20L;

        heartbeatTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> writeHeartbeat(),
                heartbeatTicks, heartbeatTicks);
    }

    private void writeHeartbeat() {
        JsonArray playersArray = new JsonArray();
        for (var p : Bukkit.getOnlinePlayers()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("uuid", p.getUniqueId().toString());
            obj.addProperty("name", p.getName());
            playersArray.add(obj);
        }

        String onlineJson = playersArray.toString();

        // 收集已加载的家园世界 key
        JsonArray worldsArray = new JsonArray();
        for (var world : Bukkit.getWorlds()) {
            var nk = world.getKey();
            if ("simplehomeland".equals(nk.getNamespace())) {
                worldsArray.add(nk.getKey());
            }
        }
        String loadedWorldsJson = worldsArray.toString();

        ConfigManager config = plugin.getConfigManager();
        String playersKey = PLAYERS_PREFIX + config.getServerId();
        String worldsKey = LOADED_WORLDS_PREFIX + config.getServerId();

        try (Jedis jedis = jedisPool.getResource()) {
            jedis.setex(playersKey, config.getStaleRequestSeconds(), onlineJson);
            jedis.setex(worldsKey, config.getStaleRequestSeconds(), loadedWorldsJson);
        } catch (Exception e) {
            plugin.getLogger().warning("Redis 写入心跳失败: " + e.getMessage());
        }
    }

    public void startMessageListener(CrossServerManager.MessageHandler handler) {
        ConfigManager config = plugin.getConfigManager();
        String channel = CHANNEL_PREFIX + config.getServerId();

        pubSub = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                try {
                    JsonObject json = JsonParser.parseString(message).getAsJsonObject();
                    String type = json.get("type").getAsString();
                    String payload = json.get("payload").getAsString();
                    handler.handle(type, payload);
                } catch (Exception e) {
                    plugin.getLogger().warning("Redis 消息解析失败: " + e.getMessage());
                }
            }
        };

        subscribeThread = new Thread(() -> {
            while (running()) {
                try (Jedis jedis = jedisPool.getResource()) {
                    plugin.getLogger().info("Redis 订阅已连接: " + channel);
                    jedis.subscribe(pubSub, channel);
                    // subscribe 正常返回（unsubscribe 调用），退出循环
                    break;
                } catch (Exception e) {
                    if (running()) {
                        plugin.getLogger().warning("Redis 订阅断开: " + e.getMessage() + ", 3秒后重连...");
                        try {
                            Thread.sleep(3000);
                        } catch (InterruptedException ie) {
                            break;
                        }
                    }
                }
            }
        }, "SimpleHomeland-Redis-Subscriber");
        subscribeThread.setDaemon(true);
        subscribeThread.start();

        plugin.getLogger().info("Redis 订阅已启动: " + channel);
    }

    private boolean running() {
        return subscribeThread != null && subscribeThread.isAlive();
    }

    public void stopHeartbeat() {
        if (heartbeatTask != null) heartbeatTask.cancel();
    }

    public void stopMessageListener() {
        if (pubSub != null) pubSub.unsubscribe();
        if (subscribeThread != null) {
            subscribeThread.interrupt();
            subscribeThread = null;
        }
    }

    // ==================== Branch Mode ====================

    public void startOnlineSync() {
        long syncTicks = 200L;
        onlineSyncTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> refreshOnlinePlayers(),
                syncTicks, syncTicks);
        refreshOnlinePlayers();
    }

    private void refreshOnlinePlayers() {
        ConfigManager config = plugin.getConfigManager();
        String playersKey = PLAYERS_PREFIX + config.getMainServer();

        try (Jedis jedis = jedisPool.getResource()) {
            String json = jedis.get(playersKey);
            if (json != null && !json.isEmpty()) {
                Map<UUID, String> newMap = new LinkedHashMap<>();
                JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
                for (int i = 0; i < arr.size(); i++) {
                    JsonObject obj = arr.get(i).getAsJsonObject();
                    UUID uuid = UUID.fromString(obj.get("uuid").getAsString());
                    String name = obj.get("name").getAsString();
                    newMap.put(uuid, name);
                }
                cachedOnlinePlayers.clear();
                cachedOnlinePlayers.putAll(newMap);
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Redis 同步在线玩家失败: " + e.getMessage());
        }
    }

    public void sendTeleportRequest(UUID playerUuid, UUID ownerUuid, String homelandName,
                                    boolean bypassAccess, @Nullable Location targetLocation) {
        ConfigManager config = plugin.getConfigManager();

        Map<String, Object> payloadMap = new LinkedHashMap<>();
        payloadMap.put("playerUuid", playerUuid.toString());
        payloadMap.put("ownerUuid", ownerUuid.toString());
        payloadMap.put("homelandName", homelandName);
        payloadMap.put("bypassAccess", bypassAccess);
        if (targetLocation != null) {
            Map<String, Object> locMap = new LinkedHashMap<>();
            locMap.put("x", targetLocation.getX());
            locMap.put("y", targetLocation.getY());
            locMap.put("z", targetLocation.getZ());
            locMap.put("yaw", targetLocation.getYaw());
            locMap.put("pitch", targetLocation.getPitch());
            payloadMap.put("targetLocation", locMap);
        }

        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("type", "TELEPORT_REQUEST");
        wrapper.addProperty("payload", gson.toJson(payloadMap));

        String channel = CHANNEL_PREFIX + config.getMainServer();
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.publish(channel, wrapper.toString());
        } catch (Exception e) {
            plugin.getLogger().warning("Redis 发送传送请求失败: " + e.getMessage());
        }
    }

    public void sendLoadWorldRequest(Map<String, Object> payloadMap) {
        ConfigManager config = plugin.getConfigManager();

        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("type", "LOAD_WORLD_REQUEST");
        wrapper.addProperty("payload", gson.toJson(payloadMap));

        String channel = CHANNEL_PREFIX + config.getMainServer();
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.publish(channel, wrapper.toString());
        } catch (Exception e) {
            plugin.getLogger().warning("Redis 发送加载世界请求失败: " + e.getMessage());
        }
    }

    public Map<UUID, String> getCachedOnlinePlayers() {
        return Collections.unmodifiableMap(cachedOnlinePlayers);
    }

    /**
     * 主服务器调用：立即发布已加载世界列表到 Redis。
     */
    public void publishLoadedWorlds(String loadedWorldsJson) {
        ConfigManager config = plugin.getConfigManager();
        String key = LOADED_WORLDS_PREFIX + config.getServerId();
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.setex(key, config.getStaleRequestSeconds(), loadedWorldsJson);
        } catch (Exception e) {
            plugin.getLogger().warning("Redis 发布已加载世界失败: " + e.getMessage());
        }
    }

    /**
     * 分支服务器调用：直接从 Redis 查询某个世界是否已加载（实时）。
     */
    public boolean isWorldLoadedOnMain(String worldKey) {
        ConfigManager config = plugin.getConfigManager();
        String key = LOADED_WORLDS_PREFIX + config.getMainServer();
        try (Jedis jedis = jedisPool.getResource()) {
            String json = jedis.get(key);
            if (json == null || json.isEmpty()) return false;
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                if (worldKey.equals(arr.get(i).getAsString())) return true;
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Redis 查询世界状态失败: " + e.getMessage());
        }
        return false;
    }

    public void stopOnlineSync() {
        if (onlineSyncTask != null) onlineSyncTask.cancel();
    }

    // ==================== Common ====================

    public void shutdown() {
        stopHeartbeat();
        stopMessageListener();
        stopOnlineSync();

        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
        }
        cachedOnlinePlayers.clear();
    }
}
