package dev.user.homeland.api;

/**
 * 加载家园世界 API 的结果枚举。
 */
public enum LoadWorldResult {
    /** 加载成功（含已加载的情况） */
    SUCCESS,
    /** 家园不存在 */
    WORLD_NOT_FOUND,
    /** 世界加载失败 */
    WORLD_LOAD_FAILED,
    /** 跨服请求已发送（分支模式） */
    REQUEST_SENT
}
