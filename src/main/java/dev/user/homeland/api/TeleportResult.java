package dev.user.homeland.api;

/**
 * 传送 API 的结果枚举。
 */
public enum TeleportResult {
    /** 传送成功（家园世界） */
    SUCCESS,
    /** 传送成功（非家园世界，Bukkit 注册世界） */
    SUCCESS_OTHER_WORLD,
    /** 权限不足（未 bypass 且 canEnterWorld 返回 false） */
    ACCESS_DENIED,
    /** 家园/世界不存在 */
    WORLD_NOT_FOUND,
    /** 世界加载失败 */
    WORLD_LOAD_FAILED,
    /** 玩家已下线 */
    PLAYER_OFFLINE
}
