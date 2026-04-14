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
     * 将玩家传送到指定家园的指定位置。
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
}
