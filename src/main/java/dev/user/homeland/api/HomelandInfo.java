package dev.user.homeland.api;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

/**
 * 家园查询结果，包含家园基本信息。
 * <p>
 * 通过 {@link SimpleHomelandAPI#queryHomelandAsync} 等方法获取。
 */
public record HomelandInfo(
        UUID ownerUuid,
        String name,
        String worldKey,
        @Nullable UUID worldUuid,
        int borderRadius,
        boolean hasNether,
        boolean hasEnd,
        boolean isPublic
) {}
