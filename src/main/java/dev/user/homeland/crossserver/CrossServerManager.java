package dev.user.homeland.crossserver;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.user.homeland.SimpleHomelandPlugin;
import dev.user.homeland.config.ConfigManager;
import dev.user.homeland.model.Homeland;
import dev.user.homeland.util.MessageUtil;
import org.jetbrains.annotations.Nullable;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CrossServerManager {

    @FunctionalInterface
    public interface MessageHandler {
        void handle(String type, String payload);
    }

    private final SimpleHomelandPlugin plugin;
    private final Gson gson = new Gson();

    private final Set<PendingTeleport> pendingTeleports = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> cachedOnlinePlayers = new ConcurrentHashMap<>();

    private ScheduledTask heartbeatTask;
    private ScheduledTask messagePollTask;
    private ScheduledTask onlineSyncTask;

    private RedisManager redisManager;

    public record PendingTeleport(UUID playerUuid, UUID ownerUuid, String homelandName,
                                  boolean bypassAccess, @Nullable Location targetLocation,
                                  long createdAt) {}

    public CrossServerManager(SimpleHomelandPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        ConfigManager config = plugin.getConfigManager();

        if (config.isRedisChannel()) {
            redisManager = new RedisManager(plugin);
            if (!redisManager.init()) {
                plugin.getLogger().severe("Redis 连接失败，插件将禁用！");
                plugin.getServer().getPluginManager().disablePlugin(plugin);
                return;
            }
        }

        if (config.isBranchMode()) {
            initBranchMode();
        } else {
            initMainMode();
        }
    }

    // ==================== Main Mode ====================

    private void initMainMode() {
        ConfigManager config = plugin.getConfigManager();

        if (redisManager != null) {
            redisManager.startHeartbeat();
            redisManager.startMessageListener(this::handleMessage);
            plugin.getLogger().info("跨服: 主服务器模式已启动 (Redis, 心跳=" + config.getHeartbeatInterval() + "s)");
        } else {
            long heartbeatTicks = config.getHeartbeatInterval() * 20L;
            long pollTicks = config.getPollInterval() * 20L;

            heartbeatTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> writeHeartbeat(),
                    heartbeatTicks, heartbeatTicks);

            messagePollTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> pollMessages(),
                    pollTicks, pollTicks);

            plugin.getLogger().info("跨服: 主服务器模式已启动 (MySQL, 心跳=" + config.getHeartbeatInterval()
                    + "s, 轮询=" + config.getPollInterval() + "s)");
        }
    }

    private void writeHeartbeat() {
        JsonArray playersArray = new JsonArray();
        for (Player p : Bukkit.getOnlinePlayers()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("uuid", p.getUniqueId().toString());
            obj.addProperty("name", p.getName());
            playersArray.add(obj);
        }

        String onlineJson = playersArray.toString();
        ConfigManager config = plugin.getConfigManager();

        plugin.getDatabaseQueue().submit("heartbeat", conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO homeland_server_status (server_id, server_mode, online_players, updated_at) " +
                            "VALUES (?, 'main', ?, CURRENT_TIMESTAMP) " +
                            "ON DUPLICATE KEY UPDATE server_mode='main', online_players=?, updated_at=CURRENT_TIMESTAMP")) {
                ps.setString(1, config.getServerId());
                ps.setString(2, onlineJson);
                ps.setString(3, onlineJson);
                ps.executeUpdate();
            } catch (Exception e) {
                plugin.getLogger().warning("写入心跳失败: " + e.getMessage());
            }
            return null;
        });
    }

    private void pollMessages() {
        ConfigManager config = plugin.getConfigManager();
        plugin.getDatabaseQueue().submit("poll-messages", conn -> {
            try {
                long staleMs = config.getStaleRequestSeconds() * 1000L;
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM homeland_messages WHERE target_server_id = ? AND processed = FALSE AND created_at < ?")) {
                    ps.setString(1, config.getServerId());
                    ps.setTimestamp(2, new Timestamp(System.currentTimeMillis() - staleMs));
                    ps.executeUpdate();
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT id, message_type, payload FROM homeland_messages " +
                                "WHERE target_server_id = ? AND processed = FALSE ORDER BY created_at")) {
                    ps.setString(1, config.getServerId());
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) {
                        int msgId = rs.getInt("id");
                        String type = rs.getString("message_type");
                        String payload = rs.getString("payload");
                        handleMessage(type, payload);
                        try (PreparedStatement ups = conn.prepareStatement(
                                "UPDATE homeland_messages SET processed = TRUE WHERE id = ?")) {
                            ups.setInt(1, msgId);
                            ups.executeUpdate();
                        }
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("轮询跨服消息失败: " + e.getMessage());
            }
            return null;
        });
    }

    private void handleMessage(String type, String payload) {
        if ("TELEPORT_REQUEST".equals(type)) {
            try {
                JsonObject json = JsonParser.parseString(payload).getAsJsonObject();
                UUID playerUuid = UUID.fromString(json.get("playerUuid").getAsString());
                UUID ownerUuid = UUID.fromString(json.get("ownerUuid").getAsString());
                String homelandName = json.get("homelandName").getAsString();
                boolean bypassAccess = json.has("bypassAccess") && json.get("bypassAccess").getAsBoolean();

                Location targetLocation = null;
                if (json.has("targetLocation") && !json.get("targetLocation").isJsonNull()) {
                    JsonObject loc = json.getAsJsonObject("targetLocation");
                    targetLocation = new Location(null,
                            loc.get("x").getAsDouble(), loc.get("y").getAsDouble(), loc.get("z").getAsDouble(),
                            loc.has("yaw") ? (float) loc.get("yaw").getAsDouble() : 0f,
                            loc.has("pitch") ? (float) loc.get("pitch").getAsDouble() : 0f);
                }

                PendingTeleport pt = new PendingTeleport(playerUuid, ownerUuid, homelandName,
                        bypassAccess, targetLocation, System.currentTimeMillis());
                plugin.getLogger().info("收到跨服传送请求: " + playerUuid + " -> " + homelandName);

                // 玩家可能已经在线（轮询延迟导致消息晚于玩家到达）
                Player onlinePlayer = Bukkit.getPlayer(playerUuid);
                if (onlinePlayer != null) {
                    executeTeleport(onlinePlayer, pt);
                } else {
                    pendingTeleports.add(pt);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("解析跨服传送请求失败: " + e.getMessage());
            }
        }
    }

    public void onPlayerJoinCheckTeleport(Player player) {
        long now = System.currentTimeMillis();
        long staleMs = plugin.getConfigManager().getStaleRequestSeconds() * 1000L;

        // 先清理过期请求
        pendingTeleports.removeIf(pt -> now - pt.createdAt() > staleMs);

        Iterator<PendingTeleport> it = pendingTeleports.iterator();
        while (it.hasNext()) {
            PendingTeleport pt = it.next();
            if (pt.playerUuid().equals(player.getUniqueId())) {
                it.remove();
                executeTeleport(player, pt);
                return;
            }
        }
    }

    private void executeTeleport(Player player, PendingTeleport pt) {
        long age = System.currentTimeMillis() - pt.createdAt();
        if (age > plugin.getConfigManager().getStaleRequestSeconds() * 1000L) {
            plugin.getLogger().warning("跨服传送请求已过期: " + player.getName());
            return;
        }
        player.getScheduler().execute(plugin, () -> {
            Homeland h = plugin.getHomelandManager().findHomeland(pt.ownerUuid(), pt.homelandName()).orElse(null);
            if (h == null) {
                MessageUtil.send(player, plugin.getConfigManager().getMessage("homeland-not-found", "name", pt.homelandName()));
                return;
            }
            if (pt.bypassAccess()) {
                plugin.getHomelandManager().addApiBypass(player.getUniqueId());
            }
            if (pt.targetLocation() != null) {
                plugin.getHomelandManager().loadHomelandWorldAndTeleport(player, h, pt.targetLocation(), pt.bypassAccess());
            } else {
                plugin.getHomelandManager().loadAndTeleportWithBypass(player, h, pt.bypassAccess());
            }
        }, () -> {}, 20L);
    }

    // ==================== Branch Mode ====================

    private void initBranchMode() {
        try {
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, "BungeeCord");
        } catch (Exception e) {
            plugin.getLogger().warning("注册 BungeeCord 通道失败: " + e.getMessage());
        }

        if (redisManager != null) {
            redisManager.startOnlineSync();
        } else {
            long syncTicks = 200L;
            onlineSyncTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> refreshOnlinePlayers(),
                    syncTicks, syncTicks);
            refreshOnlinePlayers();
        }

        String channel = redisManager != null ? "Redis" : "MySQL";
        plugin.getLogger().info("跨服: 分支服务器模式已启动 (" + channel + ", 目标主服务器: " + plugin.getConfigManager().getMainServer() + ")");
    }

    public void requestTeleport(Player player, UUID ownerUuid, String homelandName,
                                boolean bypassAccess, @Nullable Location targetLocation) {
        ConfigManager config = plugin.getConfigManager();
        MessageUtil.send(player, config.getMessage("branch-teleporting"));

        // 构建附带坐标和 bypass 的 payload
        Map<String, Object> payloadMap = new LinkedHashMap<>();
        payloadMap.put("playerUuid", player.getUniqueId().toString());
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

        if (redisManager != null) {
            redisManager.sendTeleportRequest(player.getUniqueId(), ownerUuid, homelandName,
                    bypassAccess, targetLocation);
            player.getScheduler().execute(plugin, () -> transferToMainServer(player), () -> {}, 1L);
        } else {
            String payload = gson.toJson(payloadMap);

            plugin.getDatabaseQueue().submit("teleport-request", conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO homeland_messages (source_server_id, target_server_id, message_type, payload) " +
                                "VALUES (?, ?, 'TELEPORT_REQUEST', ?)")) {
                    ps.setString(1, config.getServerId());
                    ps.setString(2, config.getMainServer());
                    ps.setString(3, payload);
                    ps.executeUpdate();

                    player.getScheduler().execute(plugin, () -> transferToMainServer(player), () -> {}, 1L);
                } catch (Exception e) {
                    plugin.getLogger().warning("发送跨服传送请求失败: " + e.getMessage());
                    player.getScheduler().execute(plugin, () ->
                            MessageUtil.send(player, config.getMessage("branch-transfer-failed")), () -> {}, 1L);
                }
                return null;
            });
        }
    }

    public void transferToMainServer(Player player) {
        try {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("Connect");
            out.writeUTF(plugin.getConfigManager().getMainServer());
            player.sendPluginMessage(plugin, "BungeeCord", out.toByteArray());
        } catch (Exception e) {
            plugin.getLogger().warning("Velocity 传送失败: " + e.getMessage());
            MessageUtil.send(player, plugin.getConfigManager().getMessage("branch-transfer-failed"));
        }
    }

    private void refreshOnlinePlayers() {
        ConfigManager config = plugin.getConfigManager();
        plugin.getDatabaseQueue().submit("sync-online", conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT online_players FROM homeland_server_status WHERE server_id = ?")) {
                ps.setString(1, config.getMainServer());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    String json = rs.getString("online_players");
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
                }
            } catch (Exception e) {
                plugin.getLogger().fine("同步在线玩家失败: " + e.getMessage());
            }
            return null;
        });
    }

    public Map<UUID, String> getCachedOnlinePlayers() {
        if (redisManager != null) {
            return redisManager.getCachedOnlinePlayers();
        }
        return Collections.unmodifiableMap(cachedOnlinePlayers);
    }

    // ==================== Common ====================

    public void shutdown() {
        if (redisManager != null) {
            redisManager.shutdown();
        }

        if (heartbeatTask != null) heartbeatTask.cancel();
        if (messagePollTask != null) messagePollTask.cancel();
        if (onlineSyncTask != null) onlineSyncTask.cancel();
        pendingTeleports.clear();
        cachedOnlinePlayers.clear();

        if (redisManager == null && !plugin.getConfigManager().isBranchMode()) {
            plugin.getDatabaseQueue().submit("cleanup-heartbeat", conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM homeland_server_status WHERE server_id = ?")) {
                    ps.setString(1, plugin.getConfigManager().getServerId());
                    ps.executeUpdate();
                } catch (Exception ignored) {}
                return null;
            });
        }
    }
}
