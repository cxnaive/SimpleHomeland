package dev.user.homeland.api;

import dev.user.homeland.SimpleHomelandPlugin;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * SimpleHomeland 对外 API。
 * <p>
 * 提供传送玩家到家园世界等能力，自动处理权限检查、异步世界加载和传送。
 * <p>
 * 使用示例：
 * <pre>{@code
 * // 传送到出生点
 * SimpleHomelandAPI.teleportToHomeland(player, ownerUuid, "我的家园", false, result -> {
 *     if (result == TeleportResult.SUCCESS) { ... }
 * });
 *
 * // 传送到指定坐标
 * SimpleHomelandAPI.teleportToHomeland(player, ownerUuid, "我的家园", false, location, result -> {
 *     if (result == TeleportResult.SUCCESS) { ... }
 * });
 * }</pre>
 */
public final class SimpleHomelandAPI {

    private SimpleHomelandAPI() {}

    /**
     * 将玩家传送到指定家园（出生点）。
     *
     * @param player           要传送的玩家
     * @param ownerUuid        家园所有者的 UUID
     * @param homelandName     家园名称
     * @param bypassAccessCheck 是否跳过访问权限检查
     * @param callback         结果回调（主线程）
     */
    public static void teleportToHomeland(Player player, UUID ownerUuid, String homelandName,
                                          boolean bypassAccessCheck, Consumer<TeleportResult> callback) {
        teleportToHomeland(player, ownerUuid, homelandName, bypassAccessCheck, null, callback);
    }

    /**
     * 将玩家传送到指定世界的指定位置。
     *
     * @param player           要传送的玩家
     * @param ownerUuid        家园所有者的 UUID
     * @param homelandName     家园名称
     * @param bypassAccessCheck 是否跳过访问权限检查
     * @param location         目标位置，null 则传送到出生点
     * @param callback         结果回调（主线程）
     */
    public static void teleportToHomeland(Player player, UUID ownerUuid, String homelandName,
                                          boolean bypassAccessCheck, @Nullable Location location,
                                          Consumer<TeleportResult> callback) {
        SimpleHomelandPlugin plugin = SimpleHomelandPlugin.getInstance();
        if (plugin == null) {
            callback.accept(TeleportResult.WORLD_NOT_FOUND);
            return;
        }
        plugin.getHomelandManager().teleportToHomelandAPI(player, ownerUuid, homelandName, bypassAccessCheck, location, callback);
    }

    /**
     * 将玩家传送到指定世界（出生点），通过世界 UUID 查找。
     * <p>
     * 支持未加载的家园世界（会自动异步加载）。
     *
     * @param player           要传送的玩家
     * @param worldUUID        世界 UUID（World.getUID()）
     * @param bypassAccessCheck 是否跳过访问权限检查
     * @param callback         结果回调（主线程）
     */
    public static void teleportToHomelandByWorldUUID(Player player, UUID worldUUID,
                                                      boolean bypassAccessCheck, Consumer<TeleportResult> callback) {
        teleportToHomelandByWorldUUID(player, worldUUID, bypassAccessCheck, null, callback);
    }

    /**
     * 将玩家传送到指定世界的指定位置，通过世界 UUID 查找。
     * <p>
     * 支持未加载的家园世界（会自动异步加载）。
     *
     * @param player           要传送的玩家
     * @param worldUUID        世界 UUID（World.getUID()）
     * @param bypassAccessCheck 是否跳过访问权限检查
     * @param location         目标位置，null 则传送到出生点
     * @param callback         结果回调（主线程）
     */
    public static void teleportToHomelandByWorldUUID(Player player, UUID worldUUID,
                                                      boolean bypassAccessCheck, @Nullable Location location,
                                                      Consumer<TeleportResult> callback) {
        SimpleHomelandPlugin plugin = SimpleHomelandPlugin.getInstance();
        if (plugin == null) {
            callback.accept(TeleportResult.WORLD_NOT_FOUND);
            return;
        }
        plugin.getHomelandManager().teleportToHomelandByWorldUUIDAPI(player, worldUUID, bypassAccessCheck, location, callback);
    }

    /**
     * 将玩家传送到指定世界（出生点）。
     *
     * @param player           要传送的玩家
     * @param worldKey         世界 key（家园 worldKey 或 Bukkit 世界名）
     * @param bypassAccessCheck 是否跳过访问权限检查
     * @param callback         结果回调（主线程）
     */
    public static void teleportToHomelandByWorldKey(Player player, String worldKey,
                                                     boolean bypassAccessCheck, Consumer<TeleportResult> callback) {
        teleportToHomelandByWorldKey(player, worldKey, bypassAccessCheck, null, callback);
    }

    /**
     * 将玩家传送到指定世界的指定位置。
     *
     * @param player           要传送的玩家
     * @param worldKey         世界 key（家园 worldKey 或 Bukkit 世界名）
     * @param bypassAccessCheck 是否跳过访问权限检查
     * @param location         目标位置，null 则传送到出生点
     * @param callback         结果回调（主线程）
     */
    public static void teleportToHomelandByWorldKey(Player player, String worldKey,
                                                     boolean bypassAccessCheck, @Nullable Location location,
                                                     Consumer<TeleportResult> callback) {
        SimpleHomelandPlugin plugin = SimpleHomelandPlugin.getInstance();
        if (plugin == null) {
            callback.accept(TeleportResult.WORLD_NOT_FOUND);
            return;
        }
        plugin.getHomelandManager().teleportToHomelandByWorldKeyAPI(player, worldKey, bypassAccessCheck, location, callback);
    }

    // ==================== 查询 API ====================

    /**
     * 异步查询家园是否存在（通过 owner UUID + 名称）。
     * <p>
     * 分支模式下通过共享数据库查询，主服务器模式下通过缓存。
     *
     * @param ownerUuid    家园所有者的 UUID
     * @param homelandName 家园名称
     * @param callback     结果回调，null 表示家园不存在
     */
    public static void queryHomelandAsync(UUID ownerUuid, String homelandName,
                                           Consumer<@Nullable HomelandInfo> callback) {
        SimpleHomelandPlugin plugin = SimpleHomelandPlugin.getInstance();
        if (plugin == null) {
            callback.accept(null);
            return;
        }
        plugin.getHomelandManager().queryHomelandAsync(ownerUuid, homelandName, callback);
    }

    /**
     * 异步查询家园是否存在（通过 worldKey）。
     *
     * @param worldKey 世界 key
     * @param callback 结果回调，null 表示家园不存在
     */
    public static void queryHomelandByWorldKeyAsync(String worldKey,
                                                     Consumer<@Nullable HomelandInfo> callback) {
        SimpleHomelandPlugin plugin = SimpleHomelandPlugin.getInstance();
        if (plugin == null) {
            callback.accept(null);
            return;
        }
        plugin.getHomelandManager().queryHomelandByWorldKeyAsync(worldKey, callback);
    }

    /**
     * 异步查询家园是否存在（通过世界 UUID）。
     *
     * @param worldUUID 世界 UUID（World.getUID()）
     * @param callback  结果回调，null 表示家园不存在
     */
    public static void queryHomelandByWorldUUIDAsync(UUID worldUUID,
                                                      Consumer<@Nullable HomelandInfo> callback) {
        SimpleHomelandPlugin plugin = SimpleHomelandPlugin.getInstance();
        if (plugin == null) {
            callback.accept(null);
            return;
        }
        plugin.getHomelandManager().queryHomelandByWorldUUIDAsync(worldUUID, callback);
    }

    // ==================== 加载世界 API ====================

    /**
     * 让主服务器加载指定的家园世界（通过 owner UUID + 名称）。
     * <p>
     * 分支模式下会发送跨服请求到主服务器，回调 {@link LoadWorldResult#REQUEST_SENT}。
     * 主服务器模式下直接加载，回调实际结果。
     *
     * @param ownerUuid    家园所有者的 UUID
     * @param homelandName 家园名称
     * @param callback     结果回调
     */
    public static void loadHomelandWorld(UUID ownerUuid, String homelandName,
                                          Consumer<LoadWorldResult> callback) {
        SimpleHomelandPlugin plugin = SimpleHomelandPlugin.getInstance();
        if (plugin == null) {
            callback.accept(LoadWorldResult.WORLD_NOT_FOUND);
            return;
        }
        plugin.getHomelandManager().loadHomelandWorldAPI(ownerUuid, homelandName, callback);
    }

    /**
     * 让主服务器加载指定的家园世界（通过 world key）。
     *
     * @param worldKey 世界 key
     * @param callback 结果回调
     */
    public static void loadHomelandWorldByWorldKey(String worldKey,
                                                    Consumer<LoadWorldResult> callback) {
        SimpleHomelandPlugin plugin = SimpleHomelandPlugin.getInstance();
        if (plugin == null) {
            callback.accept(LoadWorldResult.WORLD_NOT_FOUND);
            return;
        }
        plugin.getHomelandManager().loadHomelandWorldByWorldKeyAPI(worldKey, callback);
    }

    /**
     * 让主服务器加载指定的家园世界（通过世界 UUID）。
     *
     * @param worldUUID 世界 UUID（World.getUID()）
     * @param callback  结果回调
     */
    public static void loadHomelandWorldByWorldUUID(UUID worldUUID,
                                                      Consumer<LoadWorldResult> callback) {
        SimpleHomelandPlugin plugin = SimpleHomelandPlugin.getInstance();
        if (plugin == null) {
            callback.accept(LoadWorldResult.WORLD_NOT_FOUND);
            return;
        }
        plugin.getHomelandManager().loadHomelandWorldByWorldUUIDAPI(worldUUID, callback);
    }

    // ==================== 查询世界加载状态 ====================

    /**
     * 检查家园世界是否已加载（通过 world key，同步）。
     * <p>
     * 主服务器：本地即时检查，可在任意线程调用。
     * 分支服务器：实时查询 Redis / MySQL 主服务器状态，
     * MySQL 模式下会阻塞调用线程等待数据库响应，请勿在游戏线程调用。
     * 如需非阻塞调用，请使用 {@link #isHomelandWorldLoadedAsync}。
     *
     * @param worldKey 世界 key
     * @return 世界是否已加载
     */
    public static boolean isHomelandWorldLoaded(String worldKey) {
        SimpleHomelandPlugin plugin = SimpleHomelandPlugin.getInstance();
        if (plugin == null) return false;
        return plugin.getHomelandManager().isHomelandWorldLoaded(worldKey);
    }

    /**
     * 异步检查家园世界是否已加载（通过 world key）。
     * <p>
     * 主服务器：查本地缓存，立即回调。分支服务器：异步查询 Redis / MySQL 主服务器状态。
     *
     * @param worldKey 世界 key
     * @param callback 结果回调
     */
    public static void isHomelandWorldLoadedAsync(String worldKey, Consumer<Boolean> callback) {
        SimpleHomelandPlugin plugin = SimpleHomelandPlugin.getInstance();
        if (plugin == null) {
            callback.accept(false);
            return;
        }
        plugin.getHomelandManager().isHomelandWorldLoadedAsync(worldKey, callback);
    }

    /**
     * 检查家园世界是否已加载（通过世界 UUID）。
     * <p>
     * 仅主服务器模式有效。分支服务器本地无缓存，请使用 {@link #isHomelandWorldLoadedByUUIDAsync}。
     *
     * @param worldUUID 世界 UUID（World.getUID()）
     * @return 世界是否已加载
     */
    public static boolean isHomelandWorldLoadedByUUID(UUID worldUUID) {
        SimpleHomelandPlugin plugin = SimpleHomelandPlugin.getInstance();
        if (plugin == null) return false;
        return plugin.getHomelandManager().isHomelandWorldLoadedByUUID(worldUUID);
    }

    /**
     * 异步检查家园世界是否已加载（通过世界 UUID）。
     * <p>
     * 主服务器：查本地缓存。分支服务器：先查 DB 获取 worldKey，再实时查询主服务器状态。
     *
     * @param worldUUID 世界 UUID（World.getUID()）
     * @param callback  结果回调
     */
    public static void isHomelandWorldLoadedByUUIDAsync(UUID worldUUID, Consumer<Boolean> callback) {
        SimpleHomelandPlugin plugin = SimpleHomelandPlugin.getInstance();
        if (plugin == null) {
            callback.accept(false);
            return;
        }
        plugin.getHomelandManager().isHomelandWorldLoadedByUUIDAsync(worldUUID, callback);
    }
}
