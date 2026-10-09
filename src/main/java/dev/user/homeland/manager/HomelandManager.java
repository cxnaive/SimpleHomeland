package dev.user.homeland.manager;

import dev.user.homeland.SimpleHomelandPlugin;
import dev.user.homeland.api.HomelandInfo;
import dev.user.homeland.api.LoadWorldResult;
import dev.user.homeland.api.TeleportResult;
import dev.user.homeland.config.ConfigManager;
import dev.user.homeland.config.ConfigManager.GameRuleConfig;
import dev.user.homeland.config.PriceTier;
import dev.user.homeland.model.CustomSettings;
import dev.user.homeland.model.Homeland;
import dev.user.homeland.model.HomelandDimension;
import dev.user.homeland.model.VisitorFlag;
import dev.user.homeland.model.VisitorFlags;
import dev.user.homeland.util.MessageUtil;
import net.thenextlvl.worlds.WorldsAccess;
import net.thenextlvl.worlds.Level;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

public class HomelandManager {

    private final SimpleHomelandPlugin plugin;
    private final ConcurrentHashMap<UUID, List<Homeland>> homelandCache = new ConcurrentHashMap<>();
    private final HomelandStore homelandStore = new HomelandStore();
    private final HomelandOperationCoordinator operationCoordinator = new HomelandOperationCoordinator();
    private final ConcurrentHashMap<String, CompletableFuture<World>> activeWorldLoads =
            new ConcurrentHashMap<>();
    private final HomelandEconomy homelandEconomy;
    private final HomelandWorldOperations worldOperations;
    private final ConcurrentHashMap<UUID, Set<Integer>> playerInvites = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean> creatingInProgress = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean> namingInProgress = new ConcurrentHashMap<>();
    // 邀请/取消邀请等待输入状态: UUID -> (homelandName, isUninvite)
    private final ConcurrentHashMap<UUID, Object[]> inviteInputInProgress = new ConcurrentHashMap<>();
    // 管理员命名状态: UUID -> (targetUuid, targetName)
    private final ConcurrentHashMap<UUID, Object[]> adminNamingInProgress = new ConcurrentHashMap<>();
    // 记录每个家园世界最后有玩家在线的时间（worldKey -> epoch millis）
    private final ConcurrentHashMap<String, Long> lastPlayerPresentTime = new ConcurrentHashMap<>();
    // API 传送时的权限绕过标记，TeleportListener 会检查此集合
    private final Set<UUID> apiBypass = ConcurrentHashMap.newKeySet();
    // 是否为分支模式
    private final boolean branchMode;
    private volatile boolean worldIndexLoaded;
    private final AtomicBoolean worldIndexLoadInProgress = new AtomicBoolean();
    private final boolean startupMigrationEnabled;
    private final AtomicBoolean startupMigrationActive = new AtomicBoolean();
    private HomelandStartupMigration startupMigration;

    public HomelandManager(SimpleHomelandPlugin plugin) {
        this.plugin = plugin;
        this.branchMode = plugin.getConfigManager().isBranchMode();
        this.startupMigrationEnabled = !branchMode && plugin.getConfigManager().isMigrateWorldsOnStartup();
        this.startupMigrationActive.set(startupMigrationEnabled);
        this.homelandEconomy = new HomelandEconomy(plugin);
        this.worldOperations = new HomelandWorldOperations(
                plugin, homelandStore, homelandEconomy, this::removeHomelandFromCache,
                this::touchLastPlayerTime);
        if (!branchMode) worldOperations.recoverPendingCleanups();
        loadWorldKeyIndex();
    }

    // ==================== 缓存 ====================

    public void onPlayerJoin(UUID playerUuid) {
        homelandCache.putIfAbsent(playerUuid, new CopyOnWriteArrayList<>());
        playerInvites.putIfAbsent(playerUuid, ConcurrentHashMap.newKeySet());
        loadHomelands(playerUuid);
        loadInvites(playerUuid);
    }

    public void onPlayerQuit(UUID playerUuid) {
        homelandCache.remove(playerUuid);
        playerInvites.remove(playerUuid);
        namingInProgress.remove(playerUuid);
        inviteInputInProgress.remove(playerUuid);
        adminNamingInProgress.remove(playerUuid);
        apiBypass.remove(playerUuid);
    }

    private void loadHomelands(UUID playerUuid) {
        plugin.getDatabaseQueue().submit("load-homelands-" + playerUuid, conn -> {
            List<Homeland> homelands = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT id, owner_uuid, name, world_key, world_uuid, nether_uuid, end_uuid, border_radius, has_nether, has_end, is_public, visitor_flags, COALESCE(world_type, 'default') as world_type, COALESCE(custom_settings, '{}') as custom_settings, COALESCE(overworld_reset_pending, FALSE) as overworld_reset_pending, overworld_reset_previous_uuid FROM homeland WHERE owner_uuid = ?")) {
                stmt.setString(1, playerUuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        homelands.add(readHomeland(rs));
                    }
                }
            }
            return homelands;
        }, homelands -> {
            homelandCache.put(playerUuid, new CopyOnWriteArrayList<>(canonicalize(homelands)));
        }, error -> {
            plugin.getLogger().warning("加载玩家家园数据失败: " + error.getMessage());
        });
    }

    public List<Homeland> getHomelands(UUID playerUuid) {
        List<Homeland> cached = homelandCache.get(playerUuid);
        return cached != null ? cached : homelandStore.findByOwner(playerUuid);
    }

    /**
     * 获取玩家被邀请的家园列表。
     * 从邀请索引和规范家园仓库中查找对应的 Homeland 对象。
     */
    public List<Homeland> getInvitedHomelands(UUID playerUuid) {
        Set<Integer> invitedIds = playerInvites.get(playerUuid);
        if (invitedIds == null || invitedIds.isEmpty()) return Collections.emptyList();
        List<Homeland> result = new ArrayList<>();
        for (Homeland h : homelandStore.all()) {
            if (invitedIds.contains(h.getId())) {
                result.add(h);
            }
        }
        return result;
    }

    public boolean isHomelandsCached(UUID playerUuid) {
        return homelandCache.containsKey(playerUuid);
    }

    /**
     * 异步获取玩家家园列表，优先从缓存读取，缓存未命中时通过 DatabaseQueue 从 DB 加载。
     */
    public void getOrLoadHomelandsAsync(UUID playerUuid, Consumer<List<Homeland>> callback) {
        getOrLoadHomelandsAsync(playerUuid, callback,
                () -> callback.accept(Collections.emptyList()));
    }

    /**
     * 异步获取玩家家园列表，优先从缓存读取，缓存未命中时通过 DatabaseQueue 从 DB 加载。
     * @param onFailure 加载失败时的回调
     */
    public void getOrLoadHomelandsAsync(UUID playerUuid, Consumer<List<Homeland>> callback, Runnable onFailure) {
        List<Homeland> cached = homelandCache.get(playerUuid);
        if (cached != null) {
            callback.accept(cached);
            return;
        }
        plugin.getDatabaseQueue().submit("load-homelands-async-" + playerUuid, conn -> {
            List<Homeland> homelands = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT id, owner_uuid, name, world_key, world_uuid, nether_uuid, end_uuid, border_radius, has_nether, has_end, is_public, visitor_flags, COALESCE(world_type, 'default') as world_type, COALESCE(custom_settings, '{}') as custom_settings, COALESCE(overworld_reset_pending, FALSE) as overworld_reset_pending, overworld_reset_previous_uuid FROM homeland WHERE owner_uuid = ?")) {
                stmt.setString(1, playerUuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        homelands.add(readHomeland(rs));
                    }
                }
            }
            return homelands;
        }, homelands -> {
            callback.accept(canonicalize(homelands));
        }, error -> {
            plugin.getLogger().warning("异步加载家园数据失败: " + error.getMessage());
            onFailure.run();
        });
    }

    public Optional<Homeland> getHomeland(UUID playerUuid, String name) {
        return homelandStore.find(playerUuid, name);
    }

    /**
     * 从规范家园仓库查找家园（不受玩家缓存限制，启动时即加载全部）。
     */
    public Optional<Homeland> findHomeland(UUID ownerUuid, String name) {
        return homelandStore.find(ownerUuid, name);
    }

    private List<Homeland> canonicalize(List<Homeland> snapshots) {
        return snapshots.stream().map(homelandStore::upsert).toList();
    }

    private void executeHomelandOperation(CommandSender messageTarget, UUID ownerUuid, String name,
                                          HomelandOperationCoordinator.OperationType type,
                                          Function<Homeland, CompletionStage<Void>> operation,
                                          Runnable onSuccess, Runnable onFailure) {
        if (branchMode) {
            sendAsyncMessage(messageTarget, plugin.getConfigManager().getMessage("branch-mode-not-allowed"));
            onFailure.run();
            return;
        }

        if (startupMigrationActive.get()) {
            sendAsyncMessage(messageTarget, plugin.getConfigManager().getMessage("world-migration-in-progress"));
            onFailure.run();
            return;
        }

        Optional<Homeland> homeland = homelandStore.find(ownerUuid, name);
        if (homeland.isEmpty()) {
            sendAsyncMessage(messageTarget, plugin.getConfigManager().getMessage(
                    "homeland-not-found", "name", name));
            onFailure.run();
            return;
        }

        if (homeland.get().isOverworldResetPending()
                && type != HomelandOperationCoordinator.OperationType.DELETE_HOMELAND) {
            sendAsyncMessage(messageTarget,
                    plugin.getConfigManager().getMessage("homeland-operation-pending"));
            onFailure.run();
            return;
        }

        operationCoordinator.execute(homeland.get().getId(), type, () -> operation.apply(homeland.get()))
                .whenComplete((ignored, error) -> {
                    if (error == null) {
                        onSuccess.run();
                        return;
                    }
                    Throwable cause = HomelandWorldOperations.unwrap(error);
                    if (cause instanceof HomelandOperationCoordinator.BusyException) {
                        sendAsyncMessage(messageTarget,
                                plugin.getConfigManager().getMessage("homeland-operation-busy"));
                    } else if (!(cause instanceof HomelandWorldOperations.ExpectedFailure)) {
                        plugin.getLogger().log(java.util.logging.Level.SEVERE,
                                "家园世界操作异常: " + type + " - " + cause.getMessage(), cause);
                    }
                    onFailure.run();
                });
    }

    private void removeHomelandFromCache(Homeland homeland) {
        List<Homeland> cached = homelandCache.get(homeland.getOwnerUuid());
        if (cached != null) cached.removeIf(entry -> entry.getId() == homeland.getId());
    }

    private Homeland readHomeland(ResultSet rs) throws SQLException {
        String worldUuidValue = rs.getString("world_uuid");
        UUID worldUuid = worldUuidValue == null || worldUuidValue.isBlank()
                ? null : UUID.fromString(worldUuidValue);
        Homeland homeland = new Homeland(
                rs.getInt("id"),
                UUID.fromString(rs.getString("owner_uuid")),
                rs.getString("name"),
                rs.getString("world_key"),
                worldUuid,
                rs.getString("world_type"),
                rs.getInt("border_radius"),
                rs.getBoolean("has_nether"),
                rs.getBoolean("has_end"),
                rs.getBoolean("is_public"),
                VisitorFlags.fromJson(rs.getString("visitor_flags")),
                CustomSettings.fromJson(rs.getString("custom_settings")),
                rs.getBoolean("overworld_reset_pending"),
                parseNullableUuid(rs.getString("overworld_reset_previous_uuid"))
        );
        homeland.setWorldUuid(HomelandDimension.NETHER, parseNullableUuid(rs.getString("nether_uuid")));
        homeland.setWorldUuid(HomelandDimension.END, parseNullableUuid(rs.getString("end_uuid")));
        return homeland;
    }

    private UUID parseNullableUuid(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    public int getHomelandCount(UUID playerUuid) {
        return getHomelands(playerUuid).size();
    }

    // ==================== 命名状态 ====================

    public void startNaming(UUID playerUuid) {
        namingInProgress.put(playerUuid, Boolean.TRUE);
    }

    public boolean isNaming(UUID playerUuid) {
        return namingInProgress.containsKey(playerUuid);
    }

    public void finishNaming(UUID playerUuid) {
        namingInProgress.remove(playerUuid);
    }

    // ==================== 管理员命名状态 ====================

    public void startAdminNaming(UUID adminUuid, UUID targetUuid, String targetName) {
        adminNamingInProgress.put(adminUuid, new Object[]{targetUuid, targetName});
    }

    public boolean isAdminNaming(UUID playerUuid) {
        return adminNamingInProgress.containsKey(playerUuid);
    }

    public Object[] getAdminNamingState(UUID playerUuid) {
        return adminNamingInProgress.get(playerUuid);
    }

    public void finishAdminNaming(UUID playerUuid) {
        adminNamingInProgress.remove(playerUuid);
    }

    // ==================== 邀请输入状态 ====================

    public void startInviteInput(UUID playerUuid, String homelandName, boolean isUninvite) {
        inviteInputInProgress.put(playerUuid, new Object[]{homelandName, isUninvite});
    }

    public boolean isInviteInput(UUID playerUuid) {
        return inviteInputInProgress.containsKey(playerUuid);
    }

    public Object[] getInviteInputState(UUID playerUuid) {
        return inviteInputInProgress.get(playerUuid);
    }

    public void finishInviteInput(UUID playerUuid) {
        inviteInputInProgress.remove(playerUuid);
    }

    // ==================== 创建家园 ====================

    public void createHomeland(Player player, String name, String worldTypeKey, Runnable onSuccess, Runnable onFailure) {
        createHomelandInternal(player, player.getUniqueId(), name, worldTypeKey, false, onSuccess, onFailure);
    }

    public void createHomelandAdmin(CommandSender admin, UUID ownerUuid, String name, String worldTypeKey, Runnable onSuccess, Runnable onFailure) {
        // 管理员创建不需要在线玩家，也不扣费、不传送
        createHomelandInternal(admin, ownerUuid, name, worldTypeKey, true, onSuccess, onFailure);
    }

    private void createHomelandInternal(CommandSender messageTarget, UUID ownerUuid,
                                         String name, String worldTypeKey, boolean skipEconomy, Runnable onSuccess, Runnable onFailure) {
        if (branchMode) {
            sendAsyncMessage(messageTarget, plugin.getConfigManager().getMessage("branch-mode-not-allowed"));
            onFailure.run();
            return;
        }
        ConfigManager config = plugin.getConfigManager();

        if (startupMigrationActive.get()) {
            sendAsyncMessage(messageTarget, config.getMessage("world-migration-in-progress"));
            onFailure.run();
            return;
        }

        // 检查名称长度
        if (name.length() < 2 || name.length() > 32) {
            messageTarget.sendMessage(MessageUtil.toComponent(config.getMessage("homeland-name-invalid")));
            onFailure.run();
            return;
        }

        // 检查名称是否只含合法字符
        if (!name.matches("[a-zA-Z0-9_\\u4e00-\\u9fa5]+")) {
            messageTarget.sendMessage(MessageUtil.toComponent(config.getMessage("homeland-name-invalid")));
            onFailure.run();
            return;
        }

        // 获取创建锁，防止并发创建绕过数量上限
        if (creatingInProgress.putIfAbsent(ownerUuid, Boolean.TRUE) != null) {
            messageTarget.sendMessage(MessageUtil.toComponent(config.getMessage("create-failed")));
            onFailure.run();
            return;
        }

        // 包装回调以确保释放锁
        final Runnable lockedOnSuccess = () -> { creatingInProgress.remove(ownerUuid); onSuccess.run(); };
        final Runnable lockedOnFailure = () -> { creatingInProgress.remove(ownerUuid); onFailure.run(); };

        // 检查数量上限
        if (getHomelandCount(ownerUuid) >= config.getMaxHomelands()) {
            messageTarget.sendMessage(MessageUtil.toComponent(config.getMessage("homeland-limit-reached", "max", String.valueOf(config.getMaxHomelands()))));
            lockedOnFailure.run();
            return;
        }

        // 检查全局重名（家园名称全局唯一）
        if (findHomelandByName(name) != null) {
            messageTarget.sendMessage(MessageUtil.toComponent(config.getMessage("homeland-name-exists", "name", name)));
            lockedOnFailure.run();
            return;
        }

        // 检查经济（管理员跳过）
        final double moneyCost;
        final int pointsCost;
        if (!skipEconomy && messageTarget instanceof Player ownerPlayer) {
            PriceTier tier = config.getCreationCost(getHomelandCount(ownerUuid));
            moneyCost = tier.getMoney();
            pointsCost = tier.getPoints();
            if (!checkAndWithdrawEconomy(ownerPlayer, moneyCost, pointsCost)) {
                lockedOnFailure.run();
                return;
            }
        } else {
            moneyCost = 0;
            pointsCost = 0;
        }

        // 生成世界key - 使用时间戳避免删除后重建的key冲突
        String worldKey = "homeland_" + ownerUuid.toString().substring(0, 8) + "_" + System.currentTimeMillis();
        int borderRadius = config.getDefaultBorderRadius();

        // 创建世界 — 不在 globalRegionScheduler 中包裹，让 createAsync 内部的 supplyGlobal 自行调度
        // 如果在 global 线程中调用 supplyGlobal，它会同步执行并阻塞 global 线程
        try {
            WorldsAccess provider = plugin.getWorldsAccess();

            var builder = plugin.getWorldService().builder(worldKey, worldTypeKey);

            if (!"void".equals(worldTypeKey) && !"flat".equals(worldTypeKey)) {
                builder.structures(config.isWorldStructures())
                        .bonusChest(config.isWorldBonusChest());
            } else {
                HomelandWorldSettings.applyWorldTypeGenerator(plugin, builder, worldTypeKey);
            }

            Level level = builder.build();

            String seed = config.getWorldSeed();
            if (seed != null && !seed.isEmpty()) {
                try {
                    level = level.toBuilder().seed(Long.parseLong(seed)).build();
                } catch (NumberFormatException e) {
                    plugin.getLogger().warning("世界种子配置无效（非数字），将使用随机种子: " + seed);
                }
            }

                plugin.getWorldService().create(level).thenAccept(overworld -> {
                    // 先在 region thread 上获取 spawn Y（需要 chunk 访问）
                    Bukkit.getRegionScheduler().execute(plugin, overworld, 0, 0, () -> {
                        ConfigManager cfg = plugin.getConfigManager();
                        final int spawnY;
                        if ("void".equals(worldTypeKey)) {
                            spawnY = cfg.getSpawnYVoid();
                        } else if ("flat".equals(worldTypeKey)) {
                            spawnY = cfg.getSpawnYFlat();
                        } else {
                            spawnY = overworld.getHighestBlockYAt(0, 0) + 1;
                        }
                        // 跳回 global region thread 执行 server settings 操作
                        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                            try {
                                // 设置出生点：void 固定在起始岛上，其他类型在 (0,0) 附近
                                if ("void".equals(worldTypeKey)) {
                                    overworld.setSpawnLocation(8, spawnY, 8);
                                } else {
                                    overworld.setSpawnLocation(0, spawnY, 0);
                                }

                                // 设置 WorldBorder
                                overworld.getWorldBorder().setCenter(0, 0);
                                overworld.getWorldBorder().setSize(borderRadius * 2);

                                // 设置自动加载
                                overworld.setAutoSave(true);
                                touchLastPlayerTime(worldKey);

                                // 应用默认游戏规则
                                HomelandWorldSettings.applyDefaultGamerules(
                                        overworld, plugin.getConfigManager());
                                // 应用默认世界难度
                                HomelandWorldSettings.applyDefaultDifficulty(
                                        overworld, plugin.getConfigManager());

                                // 写入数据库
                                java.util.UUID worldUuid = overworld.getUID();
                                plugin.getDatabaseQueue().submit("create-homeland", conn -> {
                                    try (PreparedStatement duplicate = conn.prepareStatement(
                                            "SELECT id FROM homeland WHERE LOWER(name) = LOWER(?)")) {
                                        duplicate.setString(1, name);
                                        try (ResultSet existing = duplicate.executeQuery()) {
                                            if (existing.next()) {
                                                throw new SQLException(
                                                        "Homeland name already exists: " + name,
                                                        "23505");
                                            }
                                        }
                                    }
                                    try (PreparedStatement stmt = conn.prepareStatement(
                                            "INSERT INTO homeland (owner_uuid, name, world_key, world_uuid, border_radius, has_nether, has_end, is_public, visitor_flags, world_type, custom_settings) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                                            PreparedStatement.RETURN_GENERATED_KEYS)) {
                                        stmt.setString(1, ownerUuid.toString());
                                        stmt.setString(2, name);
                                        stmt.setString(3, worldKey);
                                        stmt.setString(4, worldUuid.toString());
                                        stmt.setInt(5, borderRadius);
                                        stmt.setBoolean(6, false);
                                        stmt.setBoolean(7, false);
                                        stmt.setBoolean(8, false);
                                        stmt.setString(9, new VisitorFlags().toJson());
                                        stmt.setString(10, worldTypeKey);
                                        stmt.setString(11, new CustomSettings().toJson());
                                        stmt.executeUpdate();

                                        try (ResultSet rs = stmt.getGeneratedKeys()) {
                                            if (rs.next()) {
                                                int id = rs.getInt(1);
                                                Homeland homeland = homelandStore.upsert(new Homeland(id, ownerUuid, name, worldKey, worldUuid, worldTypeKey, borderRadius, false, false, false, new VisitorFlags(), new CustomSettings()));
                                                List<Homeland> cached = homelandCache.get(ownerUuid);
                                                if (cached != null) cached.add(homeland);
                                            }
                                        }
                                    }
                                    return null;
                                }, result -> {
                                    sendAsyncMessage(messageTarget, config.getMessage("homeland-created", "name", name));
                                    // 传送到家园（仅玩家自己创建时传送）
                                    if (!skipEconomy && messageTarget instanceof Player ownerPlayer && ownerPlayer.isOnline()) {
                                        ownerPlayer.getScheduler().execute(plugin, () -> {
                                            Location spawn = overworld.getSpawnLocation();
                                            ownerPlayer.teleportAsync(spawn);
                                        }, () -> {}, 1L);
                                    }
                                    lockedOnSuccess.run();
                                }, error -> {
                                    if (isUniqueConstraintViolation(error)) {
                                        sendAsyncMessage(messageTarget, config.getMessage("homeland-name-exists", "name", name));
                                    } else {
                                        plugin.getLogger().warning("创建家园数据库记录失败: " + error.getMessage());
                                        sendAsyncMessage(messageTarget, config.getMessage("create-failed-db"));
                                    }
                                    // 尝试清理已创建的世界
                                    try {
                                        plugin.getWorldService().delete(worldKey, overworld);
                                    } catch (Exception ex) {
                                        plugin.getLogger().warning("清理失败的世界失败: " + ex.getMessage());
                                    }
                                    refundEconomy(messageTarget instanceof Player p ? p : null, moneyCost, pointsCost);
                                    lockedOnFailure.run();
                                });
                            } catch (Exception e) {
                                plugin.getLogger().severe("创建家园后续处理失败: " + e.getMessage());
                                // 尝试清理已创建的世界（避免遗留孤儿世界）
                                try {
                                    plugin.getWorldService().delete(worldKey, overworld);
                                } catch (Exception ex) {
                                    plugin.getLogger().warning("清理失败的世界失败: " + ex.getMessage());
                                }
                                refundEconomy(messageTarget instanceof Player p ? p : null, moneyCost, pointsCost);
                                sendAsyncMessage(messageTarget, config.getMessage("create-failed"));
                                lockedOnFailure.run();
                            }
                        });
                    });
                }).exceptionally(throwable -> {
                    plugin.getLogger().log(java.util.logging.Level.SEVERE, "创建家园世界失败: " + throwable.getMessage(), throwable);
                    refundEconomy(messageTarget instanceof Player p2 ? p2 : null, moneyCost, pointsCost);
                    sendAsyncMessage(messageTarget, config.getMessage("create-failed"));
                    lockedOnFailure.run();
                    return null;
                });

        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "创建家园世界构建失败: " + e.getMessage(), e);
            refundEconomy(messageTarget instanceof Player p3 ? p3 : null, moneyCost, pointsCost);
            sendAsyncMessage(messageTarget, config.getMessage("create-failed"));
            lockedOnFailure.run();
        }
    }

    // ==================== 删除家园 ====================

    public void deleteHomeland(Player player, String name, Runnable onSuccess, Runnable onFailure) {
        executeHomelandOperation(player, player.getUniqueId(), name,
                HomelandOperationCoordinator.OperationType.DELETE_HOMELAND,
                homeland -> worldOperations.deleteHomeland(player, homeland, "delete-homeland"),
                onSuccess, onFailure);
    }

    /**
     * 管理员删除家园（不需要玩家在线，仅需要UUID）
     */
    public void deleteHomelandAdmin(CommandSender sender, UUID ownerUuid, String homelandName, Runnable onSuccess, Runnable onFailure) {
        executeHomelandOperation(sender, ownerUuid, homelandName,
                HomelandOperationCoordinator.OperationType.DELETE_HOMELAND,
                homeland -> worldOperations.deleteHomeland(
                        sender, homeland, "delete-homeland-admin"),
                onSuccess, onFailure);
    }

    // ==================== 传送到家园 ====================

    public void teleportToHomeland(Player player, String name) {
        teleportToHomeland(player, player.getUniqueId(), name);
    }

    public void teleportToHomeland(Player teleporter, UUID ownerUuid, String name) {
        getOrLoadHomelandsAsync(ownerUuid, homelands -> {
            Optional<Homeland> optHomeland = homelands.stream()
                    .filter(h -> h.getName().equalsIgnoreCase(name))
                    .findFirst();

            if (optHomeland.isEmpty()) {
                sendAsyncMessage(teleporter, plugin.getConfigManager().getMessage("homeland-not-found", "name", name));
                return;
            }

            Homeland homeland = optHomeland.get();

            if (branchMode) {
                teleportToHomelandInternal(teleporter, homeland, homeland.getWorldKey(),
                        false, null, result -> {
                            if (result == TeleportResult.ACCESS_DENIED) {
                                sendAsyncMessage(teleporter, plugin.getConfigManager().getMessage("world-access-denied"));
                            }
                        });
            } else {
                World world = getHomelandWorld(homeland.getWorldKey());
                if (world != null) {
                    teleportToWorld(teleporter, homeland, world);
                } else {
                    loadAndTeleport(teleporter, homeland);
                }
            }
        });
    }

    /**
     * 将玩家传送回服务器主世界出生点。
     */
    public void teleportToMainWorld(Player player) {
        if (branchMode) {
            sendAsyncMessage(player, plugin.getConfigManager().getMessage("back-to-main-world"));
            plugin.getCrossServerManager().transferToMainServer(player);
            return;
        }
        org.bukkit.World mainWorld = plugin.getServer().getWorlds().get(0);
        player.getScheduler().execute(plugin, () -> {
            player.teleportAsync(mainWorld.getSpawnLocation());
            sendAsyncMessage(player, plugin.getConfigManager().getMessage("back-to-main-world"));
        }, null, 1L);
    }



    private void teleportToWorld(Player teleporter, Homeland homeland, World world) {
        // 先检查访问权限，避免发出"正在传送"消息后又被 TeleportListener 拦截
        if (!canEnterWorld(teleporter, homeland.getWorldKey())) {
            sendAsyncMessage(teleporter, plugin.getConfigManager().getMessage("world-access-denied"));
            return;
        }

        sendAsyncMessage(teleporter, plugin.getConfigManager().getMessage("homeland-teleport", "name", homeland.getName()));
        teleporter.getScheduler().execute(plugin, () -> teleporter.teleportAsync(world.getSpawnLocation()), null, 1L);
    }

    public void loadAndTeleport(Player teleporter, Homeland homeland) {
        if (startupMigrationActive.get()) {
            sendAsyncMessage(teleporter, plugin.getConfigManager().getMessage("world-migration-in-progress"));
            return;
        }
        sendAsyncMessage(teleporter, plugin.getConfigManager().getMessage("world-loading"));
        loadHomelandWorldAsync(homeland, world -> {
            if (world == null) {
                sendAsyncMessage(teleporter, plugin.getConfigManager().getMessage("world-not-loaded"));
                return;
            }
            teleportToWorld(teleporter, homeland, world);
        }, error -> {
            if (error instanceof HomelandOperationCoordinator.BusyException) {
                sendAsyncMessage(teleporter, plugin.getConfigManager().getMessage(
                        "homeland-operation-busy"));
            } else {
                sendAsyncMessage(teleporter, plugin.getConfigManager().getMessage(
                        "world-not-loaded"));
            }
        });
    }

    /**
     * 加载家园世界并传送，传送完成后移除 apiBypass 标记（跨服 API 用）。
     */
    public void loadAndTeleportWithBypass(Player teleporter, Homeland homeland, boolean bypassAccess) {
        if (startupMigrationActive.get()) {
            sendAsyncMessage(teleporter, plugin.getConfigManager().getMessage("world-migration-in-progress"));
            return;
        }
        if (!bypassAccess) {
            loadAndTeleport(teleporter, homeland);
            return;
        }
        sendAsyncMessage(teleporter, plugin.getConfigManager().getMessage("world-loading"));
        loadHomelandWorldAsync(homeland, world -> {
            if (world == null) {
                apiBypass.remove(teleporter.getUniqueId());
                sendAsyncMessage(teleporter, plugin.getConfigManager().getMessage("world-not-loaded"));
                return;
            }
            teleporter.getScheduler().execute(plugin, () -> {
                teleporter.teleportAsync(world.getSpawnLocation()).thenAccept(success -> {
                    apiBypass.remove(teleporter.getUniqueId());
                });
            }, () -> apiBypass.remove(teleporter.getUniqueId()), 1L);
        }, error -> {
            if (error instanceof HomelandOperationCoordinator.BusyException) {
                sendAsyncMessage(teleporter, plugin.getConfigManager().getMessage(
                        "homeland-operation-busy"));
            } else {
                sendAsyncMessage(teleporter, plugin.getConfigManager().getMessage(
                        "world-not-loaded"));
            }
            apiBypass.remove(teleporter.getUniqueId());
        });
    }

    /**
     * 异步加载家园世界。加载成功后回调 World（主线程），失败回调 null。
     */
    public void loadHomelandWorldAsync(Homeland homeland, Consumer<World> callback) {
        loadHomelandWorldAsync(homeland.getWorldKey(), homeland, callback);
    }

    private void loadHomelandWorldAsync(Homeland homeland, Consumer<World> callback,
                                        Consumer<Throwable> onFailure) {
        loadHomelandWorldAsync(homeland.getWorldKey(), homeland, callback, onFailure);
    }

    /**
     * 异步加载家园世界（支持子维度）。
     * @param targetWorldKey 实际要加载的世界 key（可以是主世界、_nether、_the_end）
     */
    private void loadHomelandWorldAsync(String targetWorldKey, Homeland homeland,
                                        Consumer<World> callback) {
        loadHomelandWorldAsync(targetWorldKey, homeland, callback,
                ignored -> callback.accept(null));
    }

    private void loadHomelandWorldAsync(String targetWorldKey, Homeland homeland,
                                        Consumer<World> callback,
                                        Consumer<Throwable> onFailure) {
        loadHomelandWorldStage(targetWorldKey, homeland).whenComplete((world, error) -> {
            if (error != null) {
                Throwable cause = HomelandWorldOperations.unwrap(error);
                if (!(cause instanceof HomelandOperationCoordinator.BusyException)) {
                    plugin.getLogger().log(java.util.logging.Level.SEVERE,
                            "加载家园世界失败: " + targetWorldKey, cause);
                }
                onFailure.accept(cause);
                return;
            }
            callback.accept(world);
        });
    }

    private CompletionStage<World> loadHomelandWorldStage(
            String targetWorldKey, Homeland homeland) {
        CompletableFuture<World> newLoad = new CompletableFuture<>();
        CompletableFuture<World> activeLoad = activeWorldLoads.putIfAbsent(
                targetWorldKey, newLoad);
        if (activeLoad != null) return activeLoad;

        operationCoordinator.execute(homeland.getId(),
                        HomelandOperationCoordinator.OperationType.LOAD_WORLD,
                        () -> loadHomelandWorld(targetWorldKey, homeland))
                .whenComplete((world, error) -> {
                    activeWorldLoads.remove(targetWorldKey, newLoad);
                    if (error == null) {
                        newLoad.complete(world);
                        if (world != null) {
                            Bukkit.getGlobalRegionScheduler().execute(plugin, this::publishLoadedWorlds);
                        }
                    } else {
                        newLoad.completeExceptionally(error);
                    }
                });
        return newLoad;
    }

    private CompletionStage<World> loadHomelandWorld(
            String targetWorldKey, Homeland homeland) {
        if (!isWorldAccessStateReady() || isWorldUnavailable(homeland)) {
            return CompletableFuture.completedFuture(null);
        }
        World existing = getHomelandWorld(targetWorldKey);
        if (existing != null) {
            if (!plugin.getWorldService().isReady(targetWorldKey)) {
                return CompletableFuture.failedFuture(new IOException(
                        "World migration is unfinished: " + targetWorldKey));
            }
            touchLastPlayerTime(targetWorldKey);
            return CompletableFuture.completedFuture(existing);
        }

        WorldsAccess provider = plugin.getWorldsAccess();
        if (provider == null) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<World> result = new CompletableFuture<>();
        plugin.getWorldService().load(targetWorldKey, homeland).thenAccept(world -> {
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                try {
                    HomelandWorldSettings.applyLoadedWorld(plugin, homeland, world);
                    touchLastPlayerTime(targetWorldKey);
                    // 懒迁移：填充缺失的 world_uuid
                    if (!homeland.isOverworldResetPending()) {
                        updateWorldUuid(homeland, targetWorldKey, world.getUID());
                    }
                    result.complete(world);
                } catch (Throwable error) {
                    result.completeExceptionally(error);
                }
            });
        }).exceptionally(throwable -> {
            result.completeExceptionally(throwable);
            return null;
        });
        return result;
    }

    /**
     * 按完整 worldKey 加载家园世界（主世界或子维度）。
     * 主世界应用对应生成器；子维度（nether/end）保留 level.dat 中的维度生成器。
     */
    public void loadHomelandWorldByKey(String worldKey, Consumer<World> callback) {
        loadHomelandWorldByKey(worldKey, callback, ignored -> callback.accept(null));
    }

    public void loadHomelandWorldByKey(String worldKey, Consumer<World> callback,
                                       Consumer<Throwable> onFailure) {
        String normalized = HomelandWorldKeys.normalize(worldKey);
        if (!normalized.equals(worldKey)) {
            loadHomelandWorldByKey(normalized, callback, onFailure);
            return;
        }

        Homeland homeland = getHomelandByWorldKey(worldKey);
        if (homeland == null) {
            onFailure.accept(new IllegalArgumentException("Unknown homeland world: " + worldKey));
            return;
        }
        if (worldKey.equals(homeland.getWorldKey())) {
            loadHomelandWorldAsync(homeland, callback, onFailure);
        } else {
            loadHomelandWorldAsync(worldKey, homeland, callback, onFailure);
        }
    }

    // ==================== API 传送方法 ====================

    /**
     * API 入口：按 owner UUID + 家园名称传送。
     */
    public void teleportToHomelandAPI(Player player, UUID ownerUuid, String homelandName,
                                       boolean bypassAccessCheck, Consumer<TeleportResult> callback) {
        teleportToHomelandAPI(player, ownerUuid, homelandName, bypassAccessCheck, null, callback);
    }

    /**
     * API 入口：按 owner UUID + 家园名称传送，可指定目标位置。
     */
    public void teleportToHomelandAPI(Player player, UUID ownerUuid, String homelandName,
                                       boolean bypassAccessCheck, @org.jetbrains.annotations.Nullable Location location,
                                       Consumer<TeleportResult> callback) {
        plugin.getLogger().info("[API] teleportToHomeland: player=" + player.getName()
                + ", owner=" + ownerUuid + ", name=" + homelandName
                + ", bypass=" + bypassAccessCheck + ", loc=" + (location != null));
        // 使用兼容入口：DB 错误时回调空列表并返回 WORLD_NOT_FOUND。
        getOrLoadHomelandsAsync(ownerUuid, homelands -> {
            Optional<Homeland> optHomeland = homelands.stream()
                    .filter(h -> h.getName().equalsIgnoreCase(homelandName))
                    .findFirst();

            if (optHomeland.isEmpty()) {
                plugin.getLogger().info("[API] teleportToHomeland: homeland not found for owner=" + ownerUuid
                        + ", name=" + homelandName + " (have " + homelands.size() + " homelands)");
                callback.accept(TeleportResult.WORLD_NOT_FOUND);
                return;
            }

            teleportToHomelandInternal(player, optHomeland.get(), optHomeland.get().getWorldKey(), bypassAccessCheck, location, callback);
        });
    }

    /**
     * API 入口：按世界 UUID 传送。
     * 先查世界 UUID 索引（支持未加载世界），再 fallback Bukkit.getWorld。
     */
    public void teleportToHomelandByWorldUUIDAPI(Player player, UUID worldUUID,
                                                   boolean bypassAccessCheck, Consumer<TeleportResult> callback) {
        teleportToHomelandByWorldUUIDAPI(player, worldUUID, bypassAccessCheck, null, callback);
    }

    /**
     * API 入口：按世界 UUID 传送，可指定目标位置。
     */
    public void teleportToHomelandByWorldUUIDAPI(Player player, UUID worldUUID,
                                                   boolean bypassAccessCheck, @org.jetbrains.annotations.Nullable Location location,
                                                   Consumer<TeleportResult> callback) {
        plugin.getLogger().info("[API] teleportByWorldUUID: player=" + player.getName()
                + ", worldUUID=" + worldUUID + ", bypass=" + bypassAccessCheck
                + ", loc=" + (location != null));
        String targetKey = homelandStore.getWorldKeyByUuid(worldUUID);
        Homeland homeland = targetKey == null ? null : homelandStore.getByWorldKey(targetKey);
        if (homeland != null) {
            plugin.getLogger().info("[API] teleportByWorldUUID: index hit, worldKey=" + homeland.getWorldKey());
            teleportToHomelandInternal(player, homeland, targetKey, bypassAccessCheck, location, callback);
            return;
        }

        // 先尝试按 Bukkit UUID 查找（主模式和分支模式都先查本地）
        plugin.getLogger().info("[API] teleportByWorldUUID: index miss, falling back to Bukkit.getWorld");
        World bukkitWorld = Bukkit.getWorld(worldUUID);
        if (bukkitWorld != null) {
            plugin.getLogger().info("[API] teleportByWorldUUID: Bukkit found world=" + bukkitWorld.getName());
            if (routeLoadedHomelandWorld(player, bukkitWorld, bypassAccessCheck,
                    location, callback)) return;
            if (isUnregisteredHomelandWorld(bukkitWorld)) {
                callback.accept(TeleportResult.WORLD_NOT_FOUND);
                return;
            }
            if (bypassAccessCheck) {
                doTeleportWithResult(player, bukkitWorld, location, callback, TeleportResult.SUCCESS);
                return;
            }
            // 非 bypass：仍需查 DB 做权限检查，但使用本地世界直接传送
            if (branchMode) {
                queryHomelandByWorldUUIDAsync(worldUUID, info -> {
                    if (info == null) {
                        doTeleportWithResult(player, bukkitWorld, location, callback, TeleportResult.SUCCESS_OTHER_WORLD);
                        return;
                    }
                    if (!canEnterWorld(player, info.worldKey())) {
                        callback.accept(TeleportResult.ACCESS_DENIED);
                        return;
                    }
                    doTeleportWithResult(player, bukkitWorld, location, callback, TeleportResult.SUCCESS);
                });
                return;
            }
            doTeleportWithResult(player, bukkitWorld, location, callback, TeleportResult.SUCCESS_OTHER_WORLD);
            return;
        }

        // 分支模式：查数据库 + 跨服传送
        if (branchMode) {
            plugin.getLogger().info("[API] teleportByWorldUUID: branch mode, querying DB");
            queryHomelandByWorldUUIDAsync(worldUUID, info -> {
                if (info == null) {
                    callback.accept(TeleportResult.WORLD_NOT_FOUND);
                    return;
                }
                Homeland h = new Homeland(0, info.ownerUuid(), info.name(), info.worldKey(),
                        info.borderRadius(), info.hasNether(), info.hasEnd(), info.isPublic(), null);
                teleportToHomelandInternal(player, h, info.worldKey(), bypassAccessCheck, location, callback);
            });
            return;
        }

        plugin.getLogger().info("[API] teleportByWorldUUID: world not found anywhere");
        callback.accept(TeleportResult.WORLD_NOT_FOUND);
    }

    /**
     * API 入口：按家园名称传送（家园名称全局唯一）。
     * 若名称匹配到家园，走家园流程（权限检查、异步加载）。
     * 若未匹配，回调 WORLD_NOT_FOUND。
     */
    public void teleportToHomelandByWorldNameAPI(Player player, String worldName,
                                                  boolean bypassAccessCheck, Consumer<TeleportResult> callback) {
        teleportToHomelandByWorldNameAPI(player, worldName, bypassAccessCheck, null, callback);
    }

    /**
     * API 入口：按家园名称传送（家园名称全局唯一），可指定目标位置。
     */
    public void teleportToHomelandByWorldNameAPI(Player player, String worldName,
                                                  boolean bypassAccessCheck, @org.jetbrains.annotations.Nullable Location location,
                                                  Consumer<TeleportResult> callback) {
        plugin.getLogger().info("[API] teleportByWorldName: player=" + player.getName()
                + ", worldName=" + worldName + ", bypass=" + bypassAccessCheck
                + ", loc=" + (location != null));

        queryHomelandByWorldNameAsync(worldName, info -> {
            if (info != null) {
                teleportToHomelandByWorldKeyAPI(player, info.worldKey(), bypassAccessCheck, location, callback);
                return;
            }
            World bukkitWorld = Bukkit.getWorld(worldName);
            if (bukkitWorld == null || isUnregisteredHomelandWorld(bukkitWorld)) {
                callback.accept(TeleportResult.WORLD_NOT_FOUND);
                return;
            }
            doTeleportWithResult(player, bukkitWorld, location, callback,
                    bypassAccessCheck ? TeleportResult.SUCCESS : TeleportResult.SUCCESS_OTHER_WORLD);
        });
    }

    /**
     * API 入口：按 worldKey 传送。
     * 若 worldKey 对应家园世界，走家园流程（权限检查、异步加载）；
     * 若不是家园世界但 Bukkit 中已注册，直接传送到该世界，回调 SUCCESS_OTHER_WORLD。
     */
    public void teleportToHomelandByWorldKeyAPI(Player player, String worldKey,
                                                 boolean bypassAccessCheck, Consumer<TeleportResult> callback) {
        teleportToHomelandByWorldKeyAPI(player, worldKey, bypassAccessCheck, null, callback);
    }

    /**
     * API 入口：按 worldKey 传送，可指定目标位置。
     */
    public void teleportToHomelandByWorldKeyAPI(Player player, String worldKey,
                                                 boolean bypassAccessCheck, @org.jetbrains.annotations.Nullable Location location,
                                                 Consumer<TeleportResult> callback) {
        String normalized = HomelandWorldKeys.normalize(worldKey);
        if (!normalized.equals(worldKey)) {
            teleportToHomelandByWorldKeyAPI(player, normalized, bypassAccessCheck, location, callback);
            return;
        }

        plugin.getLogger().info("[API] teleportByWorldKey: player=" + player.getName()
                + ", worldKey=" + worldKey + ", bypass=" + bypassAccessCheck
                + ", loc=" + (location != null));
        Homeland homeland = getHomelandByWorldKey(worldKey);
        if (homeland != null) {
            plugin.getLogger().info("[API] teleportByWorldKey: index hit, owner=" + homeland.getOwnerUuid());
            teleportToHomelandInternal(player, homeland, worldKey, bypassAccessCheck, location, callback);
            return;
        }

        // 先尝试按 Bukkit key 查找（主模式和分支模式都先查本地）
        plugin.getLogger().info("[API] teleportByWorldKey: index miss, falling back to Bukkit.getWorld");
        World bukkitWorld = Bukkit.getWorld(worldKey);
        if (bukkitWorld != null) {
            plugin.getLogger().info("[API] teleportByWorldKey: Bukkit found world=" + bukkitWorld.getName());
            if (routeLoadedHomelandWorld(player, bukkitWorld, bypassAccessCheck,
                    location, callback)) return;
            if (isUnregisteredHomelandWorld(bukkitWorld)) {
                callback.accept(TeleportResult.WORLD_NOT_FOUND);
                return;
            }
            if (bypassAccessCheck) {
                doTeleportWithResult(player, bukkitWorld, location, callback, TeleportResult.SUCCESS);
                return;
            }
            // 非 bypass：仍需查 DB 做权限检查，但使用本地世界直接传送
            if (branchMode) {
                queryHomelandByWorldKeyAsync(worldKey, info -> {
                    if (info == null) {
                        doTeleportWithResult(player, bukkitWorld, location, callback, TeleportResult.SUCCESS_OTHER_WORLD);
                        return;
                    }
                    if (!canEnterWorld(player, info.worldKey())) {
                        callback.accept(TeleportResult.ACCESS_DENIED);
                        return;
                    }
                    doTeleportWithResult(player, bukkitWorld, location, callback, TeleportResult.SUCCESS);
                });
                return;
            }
            doTeleportWithResult(player, bukkitWorld, location, callback, TeleportResult.SUCCESS_OTHER_WORLD);
            return;
        }

        // 分支模式：查数据库 + 跨服传送
        if (branchMode) {
            plugin.getLogger().info("[API] teleportByWorldKey: branch mode, querying DB");
            queryHomelandByWorldKeyAsync(worldKey, info -> {
                if (info == null) {
                    callback.accept(TeleportResult.WORLD_NOT_FOUND);
                    return;
                }
                Homeland h = new Homeland(0, info.ownerUuid(), info.name(), info.worldKey(),
                        info.borderRadius(), info.hasNether(), info.hasEnd(), info.isPublic(), null);
                teleportToHomelandInternal(player, h, worldKey, bypassAccessCheck, location, callback);
            });
            return;
        }

        plugin.getLogger().info("[API] teleportByWorldKey: world not found anywhere");
        callback.accept(TeleportResult.WORLD_NOT_FOUND);
    }

    private boolean routeLoadedHomelandWorld(
            Player player, World world, boolean bypassAccessCheck,
            @org.jetbrains.annotations.Nullable Location location,
            Consumer<TeleportResult> callback) {
        if (!HomelandWorldKeys.NAMESPACE.equals(world.key().namespace())) return false;
        String actualWorldKey = world.getKey().getKey();
        Homeland homeland = homelandStore.getByWorldKey(actualWorldKey);
        if (homeland == null) return false;
        teleportToHomelandInternal(player, homeland, actualWorldKey,
                bypassAccessCheck, location, callback);
        return true;
    }

    private boolean isUnregisteredHomelandWorld(World world) {
        return "homelands".equals(world.getKey().getNamespace());
    }

    /**
     * API 核心逻辑：权限检查 → 加载世界 → 传送 → 回调。
     * @param targetWorldKey 实际目标世界 key（可能是子维度如 xxx_nether）
     */
    private void teleportToHomelandInternal(Player player, Homeland homeland, String targetWorldKey,
                                              boolean bypassAccessCheck, @org.jetbrains.annotations.Nullable Location location,
                                              Consumer<TeleportResult> callback) {
        if (startupMigrationActive.get()) {
            sendAsyncMessage(player, plugin.getConfigManager().getMessage("world-migration-in-progress"));
            callback.accept(TeleportResult.WORLD_LOAD_FAILED);
            return;
        }
        if (isWorldUnavailable(homeland)) {
            callback.accept(TeleportResult.WORLD_LOAD_FAILED);
            return;
        }
        if (branchMode) {
            plugin.getLogger().info("[API] branch teleport: player=" + player.getName()
                    + ", worldKey=" + targetWorldKey + ", bypass=" + bypassAccessCheck);
            if (!bypassAccessCheck && !hasWorldAccess(player, targetWorldKey)) {
                callback.accept(TeleportResult.ACCESS_DENIED);
                return;
            }
            plugin.getCrossServerManager().requestTeleport(
                    player, homeland.getOwnerUuid(), homeland.getName(), targetWorldKey, bypassAccessCheck, location);
            callback.accept(TeleportResult.SUCCESS);
            return;
        }
        plugin.getLogger().info("[API] internal: player=" + player.getName()
                + ", worldKey=" + targetWorldKey + ", bypass=" + bypassAccessCheck);
        if (!bypassAccessCheck && !hasWorldAccess(player, targetWorldKey)) {
            plugin.getLogger().info("[API] internal: ACCESS_DENIED for player=" + player.getName());
            callback.accept(TeleportResult.ACCESS_DENIED);
            return;
        }

        // 标记绕过，防止 TeleportListener 二次拦截
        apiBypass.add(player.getUniqueId());

        // Join the load even if Bukkit already exposes its world. Migration and
        // settings must complete before any caller gets permission to teleport.
        loadHomelandWorldAsync(targetWorldKey, homeland, loadedWorld -> {
            if (loadedWorld == null) {
                plugin.getLogger().info("[API] internal: async load FAILED for worldKey=" + targetWorldKey);
                apiBypass.remove(player.getUniqueId());
                callback.accept(TeleportResult.WORLD_LOAD_FAILED);
                return;
            }
            plugin.getLogger().info("[API] internal: async load success, teleporting");
            doTeleport(player, loadedWorld, location, callback);
        }, error -> {
            apiBypass.remove(player.getUniqueId());
            if (error instanceof HomelandOperationCoordinator.BusyException) {
                plugin.getLogger().info("[API] internal: load busy for worldKey=" + targetWorldKey);
            }
            callback.accept(TeleportResult.WORLD_LOAD_FAILED);
        });
    }

    /**
     * 执行实际传送，延迟 1 tick 后异步传送，成功时回调指定 result，失败回调 PLAYER_OFFLINE。
     * @param location 传送目标位置，null 时使用世界出生点
     */
    private void doTeleportWithResult(Player player, World world, @org.jetbrains.annotations.Nullable Location location,
                                      Consumer<TeleportResult> callback, TeleportResult successResult) {
        Location target = location != null
                ? new Location(world, location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch())
                : world.getSpawnLocation();
        plugin.getLogger().info("[API] doTeleport: player=" + player.getName()
                + ", world=" + world.getName() + ", target=" + target.toVector()
                + ", expectedResult=" + successResult);
        boolean scheduled = player.getScheduler().execute(plugin, () -> {
            player.teleportAsync(target).whenComplete((success, error) -> {
                apiBypass.remove(player.getUniqueId());
                TeleportResult result = error == null && Boolean.TRUE.equals(success)
                        ? successResult : player.isOnline() ? TeleportResult.WORLD_LOAD_FAILED : TeleportResult.PLAYER_OFFLINE;
                plugin.getLogger().info("[API] doTeleport: result=" + result + " for player=" + player.getName());
                callback.accept(result);
            });
        }, () -> {
            apiBypass.remove(player.getUniqueId());
            plugin.getLogger().info("[API] doTeleport: PLAYER_OFFLINE (retired) for player=" + player.getName());
            callback.accept(TeleportResult.PLAYER_OFFLINE);
        }, 1L);
        if (!scheduled) {
            apiBypass.remove(player.getUniqueId());
            callback.accept(TeleportResult.PLAYER_OFFLINE);
        }
    }

    /**
     * 检查玩家是否处于 API 绕过状态（用于 TeleportListener 放行）。
     */
    public boolean isApiBypass(UUID playerUuid) {
        return apiBypass.contains(playerUuid);
    }

    public void addApiBypass(UUID playerUuid) {
        apiBypass.add(playerUuid);
    }

    public void removeApiBypass(UUID playerUuid) {
        apiBypass.remove(playerUuid);
    }

    // ==================== 跨服查询 API ====================

    /**
     * 异步查询家园是否存在（通过 owner UUID + 名称）。
     * 分支模式下通过共享数据库查询，主服务器模式下通过缓存。
     */
    public void queryHomelandAsync(UUID ownerUuid, String name, java.util.function.Consumer<HomelandInfo> callback) {
        getOrLoadHomelandsAsync(ownerUuid, homelands -> {
            Optional<Homeland> opt = homelands.stream()
                    .filter(h -> h.getName().equalsIgnoreCase(name))
                    .findFirst();
            callback.accept(opt.map(this::toHomelandInfo).orElse(null));
        }, () -> callback.accept(null));
    }

    /**
     * 异步查询家园是否存在（通过 worldKey）。
     */
    public void queryHomelandByWorldKeyAsync(String worldKey, java.util.function.Consumer<HomelandInfo> callback) {
        String normalized = HomelandWorldKeys.normalize(worldKey);
        if (!normalized.equals(worldKey)) {
            queryHomelandByWorldKeyAsync(normalized, callback);
            return;
        }

        Homeland cached = homelandStore.getByWorldKey(worldKey);
        if (cached != null) {
            callback.accept(toHomelandInfo(cached, worldKey));
            return;
        }
        plugin.getDatabaseQueue().submit("query-homeland-key", conn ->
                HomelandWorldIdentity.queryKey(conn, worldKey), callback, error -> {
            plugin.getLogger().warning("查询家园世界失败: " + error.getMessage());
            callback.accept(null);
        });
    }

    /**
     * 异步查询家园是否存在（通过家园名称，全局唯一）。
     */
    public void queryHomelandByWorldNameAsync(String worldName, java.util.function.Consumer<HomelandInfo> callback) {
        Homeland cached = findHomelandByName(worldName);
        if (cached != null) {
            callback.accept(toHomelandInfo(cached));
            return;
        }
        plugin.getDatabaseQueue().submit("query-homeland-name", conn ->
                HomelandWorldIdentity.queryName(conn, worldName), callback, error -> {
            plugin.getLogger().warning("查询家园世界失败: " + error.getMessage());
            callback.accept(null);
        });
    }

    /**
     * 异步查询家园是否存在（通过世界 UUID）。
     */
    public void queryHomelandByWorldUUIDAsync(UUID worldUUID, java.util.function.Consumer<HomelandInfo> callback) {
        String targetKey = homelandStore.getWorldKeyByUuid(worldUUID);
        Homeland cached = targetKey == null ? null : homelandStore.getByWorldKey(targetKey);
        if (cached != null) {
            callback.accept(toHomelandInfo(cached, targetKey));
            return;
        }
        plugin.getDatabaseQueue().submit("query-homeland-uuid", conn ->
                HomelandWorldIdentity.queryUuid(conn, worldUUID), callback, error -> {
            plugin.getLogger().warning("查询家园世界失败: " + error.getMessage());
            callback.accept(null);
        });
    }

    private HomelandInfo toHomelandInfo(Homeland h, String worldKey) {
        return new HomelandInfo(h.getOwnerUuid(), h.getName(), worldKey,
                h.getWorldUuid(HomelandWorldKeys.dimension(worldKey)),
                h.getBorderRadius(), h.hasNether(), h.hasEnd(), h.isPublic());
    }

    private HomelandInfo toHomelandInfo(Homeland h) {
        return new HomelandInfo(h.getOwnerUuid(), h.getName(), h.getWorldKey(), h.getWorldUuid(),
                h.getBorderRadius(), h.hasNether(), h.hasEnd(), h.isPublic());
    }

    /**
     * 加载家园世界并传送到指定坐标（跨服 API 用）。
     * bypass 时由调用方负责 addApiBypass，本方法负责 removeApiBypass。
     */
    public void loadHomelandWorldAndTeleport(Player player, Homeland homeland, Location targetLocation,
                                             boolean bypassAccess) {
        if (!bypassAccess && !canEnterWorld(player, homeland.getWorldKey())) {
            sendAsyncMessage(player, plugin.getConfigManager().getMessage("world-access-denied"));
            return;
        }
        sendAsyncMessage(player, plugin.getConfigManager().getMessage("world-loading"));
        loadHomelandWorldAsync(homeland, world -> {
            if (world == null) {
                if (bypassAccess) apiBypass.remove(player.getUniqueId());
                sendAsyncMessage(player, plugin.getConfigManager().getMessage("world-not-loaded"));
                return;
            }
            Location target = new Location(world, targetLocation.getX(), targetLocation.getY(), targetLocation.getZ(),
                    targetLocation.getYaw(), targetLocation.getPitch());
            player.getScheduler().execute(plugin, () -> {
                player.teleportAsync(target).thenAccept(success -> {
                    if (bypassAccess) apiBypass.remove(player.getUniqueId());
                });
            }, () -> { if (bypassAccess) apiBypass.remove(player.getUniqueId()); }, 1L);
        });
    }

    /**
     * 执行实际传送（家园世界），成功回调 SUCCESS。传送到指定位置，null 则用出生点。
     */
    private void doTeleport(Player player, World world, @org.jetbrains.annotations.Nullable Location location,
                            Consumer<TeleportResult> callback) {
        doTeleportWithResult(player, world, location, callback, TeleportResult.SUCCESS);
    }

    // ==================== API 加载世界方法 ====================

    /**
     * API 入口：按 owner UUID + 家园名称加载世界。
     */
    public void loadHomelandWorldAPI(UUID ownerUuid, String homelandName, Consumer<LoadWorldResult> callback) {
        if (branchMode) {
            plugin.getCrossServerManager().requestLoadWorld(null, ownerUuid, homelandName, null);
            callback.accept(LoadWorldResult.REQUEST_SENT);
            return;
        }
        getOrLoadHomelandsAsync(ownerUuid, homelands -> {
            Optional<Homeland> opt = homelands.stream()
                    .filter(h -> h.getName().equalsIgnoreCase(homelandName))
                    .findFirst();
            if (opt.isEmpty()) {
                callback.accept(LoadWorldResult.WORLD_NOT_FOUND);
                return;
            }
            doLoadHomelandWorld(opt.get(), callback);
        });
    }

    /**
     * API 入口：按 world key 加载世界。
     */
    public void loadHomelandWorldByWorldKeyAPI(String worldKey, Consumer<LoadWorldResult> callback) {
        String normalized = HomelandWorldKeys.normalize(worldKey);
        if (!normalized.equals(worldKey)) {
            loadHomelandWorldByWorldKeyAPI(normalized, callback);
            return;
        }

        if (branchMode) {
            plugin.getCrossServerManager().requestLoadWorld(null, null, null, worldKey);
            callback.accept(LoadWorldResult.REQUEST_SENT);
            return;
        }
        Homeland homeland = getHomelandByWorldKey(worldKey);
        if (homeland == null) {
            callback.accept(LoadWorldResult.WORLD_NOT_FOUND);
            return;
        }
        loadHomelandWorldByKey(HomelandWorldKeys.normalize(worldKey), world ->
                callback.accept(world != null ? LoadWorldResult.SUCCESS : LoadWorldResult.WORLD_LOAD_FAILED));
    }

    /**
     * API 入口：按 world UUID 加载世界。
     */
    public void loadHomelandWorldByWorldUUIDAPI(UUID worldUUID, Consumer<LoadWorldResult> callback) {
        if (branchMode) {
            plugin.getCrossServerManager().requestLoadWorld(worldUUID, null, null, null);
            callback.accept(LoadWorldResult.REQUEST_SENT);
            return;
        }
        Homeland homeland = getHomelandByWorldUUID(worldUUID);
        if (homeland == null) {
            callback.accept(LoadWorldResult.WORLD_NOT_FOUND);
            return;
        }
        loadHomelandWorldByKey(homelandStore.getWorldKeyByUuid(worldUUID), world ->
                callback.accept(world != null ? LoadWorldResult.SUCCESS : LoadWorldResult.WORLD_LOAD_FAILED));
    }

    private void doLoadHomelandWorld(Homeland homeland, Consumer<LoadWorldResult> callback) {
        loadHomelandWorldAsync(homeland, world -> {
            callback.accept(world != null ? LoadWorldResult.SUCCESS : LoadWorldResult.WORLD_LOAD_FAILED);
        });
    }

    /**
     * 供跨服请求使用的加载入口，仅加载世界，无回调。
     */
    public void loadHomelandWorldForRequest(Homeland homeland) {
        loadHomelandWorldAsync(homeland, world -> {
            if (world != null) {
                plugin.getLogger().info("跨服加载世界成功: " + homeland.getName() + " (" + homeland.getWorldKey() + ")");
            } else {
                plugin.getLogger().warning("跨服加载世界失败: " + homeland.getName() + " (" + homeland.getWorldKey() + ")");
            }
        });
    }

    // ==================== API 查询世界加载状态 ====================

    /**
     * 检查家园世界是否已加载（同步）。
     * 主服务器: 本地即时检查，可在任意线程调用。
     * 分支服务器: 实时查询 Redis / MySQL，MySQL 模式下会阻塞调用线程等待数据库响应。
     * 如需非阻塞调用，请使用 {@link #isHomelandWorldLoadedAsync}。
     */
    public boolean isHomelandWorldLoaded(String worldKey) {
        String normalized = HomelandWorldKeys.normalize(worldKey);
        if (!normalized.equals(worldKey)) {
            return isHomelandWorldLoaded(normalized);
        }

        if (!branchMode) {
            return getHomelandWorld(worldKey) != null && !isWorldOperationPending(worldKey);
        }
        return plugin.getCrossServerManager().isWorldLoadedOnMain(worldKey);
    }

    /**
     * 异步检查家园世界是否已加载（通过 world key）。
     * 主服务器：查本地缓存；分支服务器：异步查询 Redis / MySQL 主服务器状态。
     */
    public void isHomelandWorldLoadedAsync(String worldKey, java.util.function.Consumer<Boolean> callback) {
        String normalized = HomelandWorldKeys.normalize(worldKey);
        if (!normalized.equals(worldKey)) {
            isHomelandWorldLoadedAsync(normalized, callback);
            return;
        }

        if (!branchMode) {
            callback.accept(isHomelandWorldLoaded(worldKey));
            return;
        }
        plugin.getDatabaseQueue().submit("query-world-loaded-" + worldKey, conn -> {
            return plugin.getCrossServerManager().isWorldLoadedOnMain(worldKey);
        }, result -> callback.accept(Boolean.TRUE.equals(result)), error -> callback.accept(false));
    }

    /**
     * 通知跨服系统更新已加载世界列表。
     */
    private void publishLoadedWorlds() {
        if (plugin.getCrossServerManager() != null) {
            plugin.getCrossServerManager().publishLoadedWorlds();
        }
    }

    /**
     * 通过世界 UUID 检查家园世界是否已加载（仅主服务器模式）。
     * 分支模式下本地无缓存，请使用异步版本。
     */
    public boolean isHomelandWorldLoadedByUUID(UUID worldUUID) {
        if (branchMode) return false;
        Homeland h = getHomelandByWorldUUID(worldUUID);
        if (h == null) return false;
        return isHomelandWorldLoaded(homelandStore.getWorldKeyByUuid(worldUUID));
    }

    /**
     * 异步检查家园世界是否已加载（通过世界 UUID）。
     * 主服务器：查本地缓存；分支服务器：先查 DB 获取 worldKey，再查主服务器已加载列表。
     */
    public void isHomelandWorldLoadedByUUIDAsync(UUID worldUUID, java.util.function.Consumer<Boolean> callback) {
        if (!branchMode) {
            Homeland h = getHomelandByWorldUUID(worldUUID);
            callback.accept(h != null && isHomelandWorldLoaded(homelandStore.getWorldKeyByUuid(worldUUID)));
            return;
        }
        queryHomelandByWorldUUIDAsync(worldUUID, info -> {
            if (info == null) callback.accept(false);
            else isHomelandWorldLoadedAsync(info.worldKey(), callback);
        });
    }

    // ==================== 扩展边界 ====================

    public void expandBorder(Player player, String name,
                             Runnable onSuccess, Runnable onFailure) {
        executeHomelandOperation(player, player.getUniqueId(), name,
                HomelandOperationCoordinator.OperationType.MODIFY_WORLD,
                homeland -> worldOperations.expandBorder(player, homeland, player),
                onSuccess, onFailure);
    }

    public void expandBorderAdmin(CommandSender sender, UUID ownerUuid, String name,
                                  Runnable onSuccess, Runnable onFailure) {
        executeHomelandOperation(sender, ownerUuid, name,
                HomelandOperationCoordinator.OperationType.MODIFY_WORLD,
                homeland -> worldOperations.expandBorder(sender, homeland, null),
                onSuccess, onFailure);
    }

    // ==================== 维度操作 ====================

    public void unlockNether(Player player, String name, Runnable onSuccess, Runnable onFailure) {
        executeHomelandOperation(player, player.getUniqueId(), name,
                HomelandOperationCoordinator.OperationType.UNLOCK_DIMENSION,
                homeland -> worldOperations.unlockDimension(
                        player, homeland, HomelandDimension.NETHER, player),
                onSuccess, onFailure);
    }

    public void unlockNetherAdmin(CommandSender sender, UUID ownerUuid, String name,
                                  Runnable onSuccess, Runnable onFailure) {
        executeHomelandOperation(sender, ownerUuid, name,
                HomelandOperationCoordinator.OperationType.UNLOCK_DIMENSION,
                homeland -> worldOperations.unlockDimension(
                        sender, homeland, HomelandDimension.NETHER, null),
                onSuccess, onFailure);
    }

    public void unlockEnd(Player player, String name, Runnable onSuccess, Runnable onFailure) {
        executeHomelandOperation(player, player.getUniqueId(), name,
                HomelandOperationCoordinator.OperationType.UNLOCK_DIMENSION,
                homeland -> worldOperations.unlockDimension(
                        player, homeland, HomelandDimension.END, player),
                onSuccess, onFailure);
    }

    public void unlockEndAdmin(CommandSender sender, UUID ownerUuid, String name,
                               Runnable onSuccess, Runnable onFailure) {
        executeHomelandOperation(sender, ownerUuid, name,
                HomelandOperationCoordinator.OperationType.UNLOCK_DIMENSION,
                homeland -> worldOperations.unlockDimension(
                        sender, homeland, HomelandDimension.END, null),
                onSuccess, onFailure);
    }

    public void lockNether(Player player, String name, Runnable onSuccess, Runnable onFailure) {
        executeHomelandOperation(player, player.getUniqueId(), name,
                HomelandOperationCoordinator.OperationType.DELETE_DIMENSION,
                homeland -> worldOperations.deleteDimension(
                        player, homeland, HomelandDimension.NETHER),
                onSuccess, onFailure);
    }

    public void lockNetherAdmin(CommandSender sender, UUID ownerUuid, String name, Runnable onSuccess, Runnable onFailure) {
        executeHomelandOperation(sender, ownerUuid, name,
                HomelandOperationCoordinator.OperationType.DELETE_DIMENSION,
                homeland -> worldOperations.deleteDimension(
                        sender, homeland, HomelandDimension.NETHER),
                onSuccess, onFailure);
    }

    public void lockEnd(Player player, String name, Runnable onSuccess, Runnable onFailure) {
        executeHomelandOperation(player, player.getUniqueId(), name,
                HomelandOperationCoordinator.OperationType.DELETE_DIMENSION,
                homeland -> worldOperations.deleteDimension(
                        player, homeland, HomelandDimension.END),
                onSuccess, onFailure);
    }

    public void lockEndAdmin(CommandSender sender, UUID ownerUuid, String name, Runnable onSuccess, Runnable onFailure) {
        executeHomelandOperation(sender, ownerUuid, name,
                HomelandOperationCoordinator.OperationType.DELETE_DIMENSION,
                homeland -> worldOperations.deleteDimension(
                        sender, homeland, HomelandDimension.END),
                onSuccess, onFailure);
    }

    // ==================== 重置世界 ====================

    public void resetOverworld(Player player, String name, String worldTypeKey,
                               Runnable onSuccess, Runnable onFailure) {
        executeHomelandOperation(player, player.getUniqueId(), name,
                HomelandOperationCoordinator.OperationType.RESET_OVERWORLD,
                homeland -> worldOperations.resetOverworld(
                        player, homeland, worldTypeKey, player),
                onSuccess, onFailure);
    }

    public void resetOverworldAdmin(CommandSender admin, UUID ownerUuid, String name,
                                    String worldTypeKey, Runnable onSuccess, Runnable onFailure) {
        executeHomelandOperation(admin, ownerUuid, name,
                HomelandOperationCoordinator.OperationType.RESET_OVERWORLD,
                homeland -> worldOperations.resetOverworld(
                        admin, homeland, worldTypeKey, null),
                onSuccess, onFailure);
    }

    public void resetDimension(Player player, String name, String dimension,
                               Runnable onSuccess, Runnable onFailure) {
        executeHomelandOperation(player, player.getUniqueId(), name,
                HomelandOperationCoordinator.OperationType.RESET_DIMENSION,
                homeland -> worldOperations.resetDimension(
                        player, homeland, HomelandDimension.parse(dimension), player),
                onSuccess, onFailure);
    }

    public void resetDimensionAdmin(CommandSender admin, UUID ownerUuid, String name,
                                    String dimension, Runnable onSuccess, Runnable onFailure) {
        executeHomelandOperation(admin, ownerUuid, name,
                HomelandOperationCoordinator.OperationType.RESET_DIMENSION,
                homeland -> worldOperations.resetDimension(
                        admin, homeland, HomelandDimension.parse(dimension), null),
                onSuccess, onFailure);
    }

    // ==================== 经济检查与扣费 ====================

    public boolean checkAndWithdrawEconomy(Player player, double money, int points) {
        return homelandEconomy.withdraw(player, money, points);
    }

    public void refundEconomy(Player player, double money, int points) {
        homelandEconomy.refund(player, money, points);
    }

    /**
     * 从异步线程安全地发送消息给玩家
     */
    private void sendAsyncMessage(Player player, String message) {
        MessageUtil.sendAsync(player, message, plugin);
    }

    /**
     * 从异步线程安全地发送消息给 CommandSender（玩家或控制台）
     */
    private void sendAsyncMessage(CommandSender sender, String message) {
        MessageUtil.sendAsync(sender, message, plugin);
    }

    // ==================== 世界索引 ====================

    /**
     * 启动时加载所有家园的世界 key 索引，用于 TeleportListener 快速判断
     */
    private void loadWorldKeyIndex() {
        if (!worldIndexLoadInProgress.compareAndSet(false, true)) return;
        plugin.getDatabaseQueue().submit("load-world-key-index", conn -> {
            if (!branchMode) {
                try {
                    plugin.getWorldService().loadMigrationState();
                } catch (IOException error) {
                    throw new SQLException("Could not read world migration state", error);
                }
            }
            if (startupMigrationEnabled) plugin.getLogger().info("启动迁移准备: 正在读取家园索引及旧世界 UUID...");
            List<Homeland> homelands = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT id, owner_uuid, name, world_key, world_uuid, nether_uuid, end_uuid, border_radius, has_nether, has_end, is_public, visitor_flags, COALESCE(world_type, 'default') as world_type, COALESCE(custom_settings, '{}') as custom_settings, COALESCE(overworld_reset_pending, FALSE) as overworld_reset_pending, overworld_reset_previous_uuid FROM homeland");
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Homeland homeland = readHomeland(rs);
                    if (!branchMode) backfillLegacyUuids(conn, homeland);
                    homelands.add(homeland);
                    if (startupMigrationEnabled && homelands.size() % 500 == 0) {
                        plugin.getLogger().info("启动迁移准备: 已读取 " + homelands.size() + " 个家园索引");
                    }
                }
            }
            return homelands;
        }, homelands -> {
            try {
                canonicalize(homelands);
                worldIndexLoaded = true;
                plugin.getLogger().info("已加载 " + homelands.size() + " 个家园索引");
                // 延后一 tick，确保插件字段、监听器和全局状态都已完成初始化。
                Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> {
                    if (startupMigrationEnabled) {
                        startupMigration = new HomelandStartupMigration(plugin, this,
                                plugin.getWorldService());
                        startupMigration.start();
                    } else {
                        finishStartupMigration();
                    }
                }, 1L);
            } finally {
                worldIndexLoadInProgress.set(false);
            }
        }, error -> {
            worldIndexLoadInProgress.set(false);
            plugin.getLogger().warning("加载家园世界索引失败: " + error.getMessage());
        });
    }

    private void backfillLegacyUuids(java.sql.Connection conn, Homeland homeland) throws SQLException {
        for (HomelandDimension dimension : HomelandDimension.values()) {
            if (!dimension.isUnlocked(homeland) || homeland.getWorldUuid(dimension) != null
                    || homeland.isOverworldResetPending()) continue;
            try {
                UUID uuid = plugin.getWorldService().legacyUuid(dimension.worldKey(homeland));
                if (uuid == null) continue;
                try (PreparedStatement statement = conn.prepareStatement(
                        "UPDATE homeland SET " + dimension.uuidColumn() + " = ? WHERE id = ?")) {
                    statement.setString(1, uuid.toString());
                    statement.setInt(2, homeland.getId());
                    statement.executeUpdate();
                }
                homeland.setWorldUuid(dimension, uuid);
            } catch (IOException error) {
                plugin.getLogger().warning("读取旧世界 UUID 失败: " + dimension.worldKey(homeland)
                        + " - " + error.getMessage());
            }
        }
    }

    private void recoverPendingWorldOperations() {
        if (branchMode) return;
        for (Homeland homeland : homelandStore.all()) {
            if (!homeland.isOverworldResetPending()) continue;
            operationCoordinator.execute(homeland.getId(),
                            HomelandOperationCoordinator.OperationType.RESET_OVERWORLD,
                            () -> worldOperations.resumeOverworldReset(homeland))
                    .whenComplete((ignored, error) -> {
                        if (error == null) {
                            plugin.getLogger().info("已恢复未完成的主世界重置: "
                                    + homeland.getName());
                        } else if (!(HomelandWorldOperations.unwrap(error)
                                instanceof HomelandOperationCoordinator.BusyException)) {
                            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                                    "恢复未完成的主世界重置失败: " + homeland.getName(),
                                    HomelandWorldOperations.unwrap(error));
                        }
                    });
        }
    }

    /**
     * 恢复所有已加载家园世界的 WorldBorder 设置。
     * 用于服务器启动时，因为 WorldLoadEvent 可能已在插件加载前触发。
     */
    public void restoreWorldBorders() {
        for (var entry : homelandStore.worldEntries().entrySet()) {
            String key = entry.getKey();
            Homeland h = entry.getValue();
            World world = getHomelandWorld(key);
            if (world != null && plugin.getWorldService().isReady(key)) {
                int radius = h.getBorderRadius();
                Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                    world.getWorldBorder().setCenter(0, 0);
                    world.getWorldBorder().setSize(radius * 2);
                });
                // 懒迁移：已加载但缺少 world_uuid 的家园，填充并写入数据库
                if (key.equals(h.getWorldKey())
                        && !h.isOverworldResetPending()
                        && !world.getUID().equals(h.getWorldUuid())) {
                    updateWorldUuid(h, world.getUID());
                }
            }
        }
    }

    /**
     * 预加载所有家园世界。
     * 仅加载主世界 key（跳过 _nether/_the_end），子维度由 WorldLoadListener 自动关联。
     */
    private void preloadAllWorlds() {
        if (plugin.getWorldsAccess() == null) {
            plugin.getLogger().warning("预加载失败: Worlds 插件未提供 API");
            return;
        }

        List<Homeland> homelands = List.copyOf(homelandStore.all());
        if (homelands.isEmpty()) return;

        plugin.getLogger().info("开始预加载 " + homelands.size() + " 个家园世界...");

        java.util.concurrent.atomic.AtomicInteger loaded = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger skipped = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger finished = new java.util.concurrent.atomic.AtomicInteger(0);
        int total = homelands.size();

        Consumer<Boolean> recordResult = wasLoaded -> {
            if (wasLoaded) loaded.incrementAndGet();
            else skipped.incrementAndGet();
            if (finished.incrementAndGet() == total) {
                plugin.getLogger().info("家园世界预加载完成: 加载 " + loaded.get()
                        + " 个, 跳过 " + skipped.get() + " 个");
            }
        };

        for (Homeland homeland : homelands) {
            if (homeland.isOverworldResetPending()
                    || getHomelandWorld(homeland.getWorldKey()) != null) {
                recordResult.accept(false);
                continue;
            }
            loadHomelandWorldAsync(homeland,
                    world -> recordResult.accept(world != null));
        }
    }

    List<Homeland> startupHomelands() {
        return List.copyOf(homelandStore.all());
    }

    void finishStartupMigration() {
        startupMigrationActive.set(false);
        recoverPendingWorldOperations();
        restoreWorldBorders();
        if (plugin.getConfigManager().isPreloadWorlds()) preloadAllWorlds();
    }

    /**
     * 通过 NamespacedKey 查找已加载的世界。
     * 世界使用 homelands 命名空间，显示名称与 Bukkit 世界名称相互独立。
     */
    public World getHomelandWorld(String worldKey) {
        return Bukkit.getWorld(HomelandWorldKeys.bukkitKey(worldKey));
    }

    public Homeland getHomelandByWorldKey(String worldKey) {
        return homelandStore.getByWorldKey(HomelandWorldKeys.normalize(worldKey));
    }

    public Homeland getHomelandByWorldUUID(UUID worldUuid) {
        return homelandStore.getByWorldUuid(worldUuid);
    }

    /**
     * 按家园名称全局查找（名称全局唯一）。
     */
    public Homeland findHomelandByName(String name) {
        return homelandStore.findByName(name);
    }

    /**
     * 懒迁移：将已加载世界的 UUID 写入数据库和内存索引。
     */
    private void updateWorldUuid(Homeland homeland, UUID worldUuid) {
        updateWorldUuid(homeland, homeland.getWorldKey(), worldUuid);
    }

    CompletableFuture<Void> updateWorldUuid(Homeland homeland, String worldKey, UUID worldUuid) {
        HomelandDimension dimension = HomelandWorldKeys.dimension(worldKey);
        if (worldUuid.equals(homeland.getWorldUuid(dimension))) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> result = plugin.getDatabaseQueue().submitFuture("update-world-uuid", conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE homeland SET " + dimension.uuidColumn() + " = ? WHERE id = ?")) {
                stmt.setString(1, worldUuid.toString());
                stmt.setInt(2, homeland.getId());
                if (stmt.executeUpdate() != 1) throw new SQLException("Homeland no longer exists: " + homeland.getId());
            }
            return null;
        }).thenRun(() -> {
            homeland.setWorldUuid(dimension, worldUuid);
            homelandStore.refreshIndexes(homeland);
        });
        return result.whenComplete((ignored, error) -> {
            if (error != null) plugin.getLogger().warning("更新世界 UUID 失败: " + worldKey + " - " + error.getMessage());
        });
    }

    // ==================== 孤儿世界清理 ====================

    /**
     * 孤儿世界信息：磁盘上存在但数据库未注册的家园世界。
     */
    public static class OrphanWorld {
        private final String key;
        private final String name;

        public OrphanWorld(String key, String name) {
            this.key = key;
            this.name = name;
        }

        public String getKey() { return key; }
        public String getName() { return name; }
    }

    private static final java.util.regex.Pattern HOMELAND_KEY_PATTERN =
            java.util.regex.Pattern.compile("^homeland_[0-9a-f]{8}_\\d+(_nether|_the_end)?$");

    /**
     * 扫描 homelands 目录中所有 key 符合家园命名规范但未在数据库注册的世界。
     * 此方法执行文件 I/O，应在异步线程调用。
     */
    public List<OrphanWorld> scanOrphanWorlds() {
        List<OrphanWorld> orphans = new ArrayList<>();
        WorldsAccess provider = plugin.getWorldsAccess();
        if (provider == null) return orphans;

        Path homelandsDir = provider.getDimensionsRoot().resolve("homelands");
        if (!Files.isDirectory(homelandsDir)) return orphans;

        try (var stream = Files.list(homelandsDir)) {
            List<Path> dirs = stream.filter(Files::isDirectory).toList();
            for (Path path : dirs) {
                String folderName = path.getFileName().toString();
                if (!HOMELAND_KEY_PATTERN.matcher(folderName).matches()) continue;
                if (!Files.isDirectory(path.resolve("region"))
                        && !Files.isDirectory(path.resolve("data"))) continue;
                // 已在数据库注册 → 跳过
                if (getHomelandByWorldKey(folderName) != null) continue;

                orphans.add(new OrphanWorld(folderName, folderName));
            }
        } catch (IOException e) {
            plugin.getLogger().warning("扫描孤儿世界失败: " + e.getMessage());
        }
        return orphans;
    }

    /**
     * 批量删除孤儿世界。逐个顺序处理（避免并发加载冲突），全部完成后回调。
     * onComplete 回调在 global region thread 上执行：accept(成功数, 失败数)。
     */
    public void deleteOrphanWorlds(List<OrphanWorld> orphans, java.util.function.BiConsumer<Integer, Integer> onComplete) {
        WorldsAccess provider = plugin.getWorldsAccess();
        if (provider == null || orphans.isEmpty()) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> onComplete.accept(0, 0));
            return;
        }

        // 必须在 global region thread 上执行世界加载/删除操作
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            final int[] counters = {0, 0}; // [success, fail]
            final int total = orphans.size();
            final int[] index = {0};

            Runnable processNext = new Runnable() {
                @Override
                public void run() {
                    if (index[0] >= total) {
                        onComplete.accept(counters[0], counters[1]);
                        return;
                    }
                    OrphanWorld orphan = orphans.get(index[0]);
                    index[0]++;
                    deleteOneOrphan(provider, orphan, success -> {
                        if (success) counters[0]++;
                        else counters[1]++;
                        // 继续处理下一个（仍在 global region thread）
                        Bukkit.getGlobalRegionScheduler().execute(plugin, this);
                    });
                }
            };
            processNext.run();
        });
    }

    private void deleteOneOrphan(WorldsAccess provider, OrphanWorld orphan, Consumer<Boolean> onDone) {
        if (getHomelandByWorldKey(orphan.getKey()) != null) {
            onDone.accept(false);
            return;
        }
        plugin.getWorldService().delete(orphan.getKey(), getHomelandWorld(orphan.getKey()))
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        plugin.getLogger().warning("删除孤儿世界失败: " + orphan.getKey() + " - " + error.getMessage());
                    }
                    onDone.accept(error == null);
                });
    }

    // ==================== 游戏规则 ====================
    public void setGamerule(Player player, String homelandName,
                            GameRuleConfig gameRule, String value,
                            Runnable onSuccess, Runnable onFailure) {
        executeHomelandOperation(player, player.getUniqueId(), homelandName,
                HomelandOperationCoordinator.OperationType.MODIFY_WORLD,
                homeland -> worldOperations.setGamerule(
                        player, homeland, gameRule, value),
                onSuccess, onFailure);
    }

    public void setGameruleAdmin(Player player, UUID ownerUuid, String homelandName,
                                 GameRuleConfig gameRule, String value,
                                 Runnable onSuccess, Runnable onFailure) {
        executeHomelandOperation(player, ownerUuid, homelandName,
                HomelandOperationCoordinator.OperationType.MODIFY_WORLD,
                homeland -> worldOperations.setGamerule(
                        player, homeland, gameRule, value),
                onSuccess, onFailure);
    }

    public void setDifficulty(Player player, String homelandName, Difficulty difficulty,
                              Runnable onSuccess, Runnable onFailure) {
        executeHomelandOperation(player, player.getUniqueId(), homelandName,
                HomelandOperationCoordinator.OperationType.MODIFY_WORLD,
                homeland -> worldOperations.setDifficulty(player, homeland, difficulty),
                onSuccess, onFailure);
    }

    public void setDifficultyAdmin(Player player, UUID ownerUuid, String homelandName,
                                   Difficulty difficulty,
                                   Runnable onSuccess, Runnable onFailure) {
        executeHomelandOperation(player, ownerUuid, homelandName,
                HomelandOperationCoordinator.OperationType.MODIFY_WORLD,
                homeland -> worldOperations.setDifficulty(player, homeland, difficulty),
                onSuccess, onFailure);
    }

    public void changeBiome(Player player, String homelandName, Biome biome,
                            double money, int points,
                            Runnable onSuccess, Runnable onFailure) {
        executeHomelandOperation(player, player.getUniqueId(), homelandName,
                HomelandOperationCoordinator.OperationType.MODIFY_WORLD,
                homeland -> worldOperations.changeBiome(
                        player, homeland, biome, money, points),
                onSuccess, onFailure);
    }

    // ==================== 邀请系统 ====================

    private void loadInvites(UUID playerUuid) {
        plugin.getDatabaseQueue().submit("load-invites-" + playerUuid, conn -> {
            Set<Integer> invitedIds = new HashSet<>();
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT homeland_id FROM homeland_invite WHERE player_uuid = ?")) {
                stmt.setString(1, playerUuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        invitedIds.add(rs.getInt("homeland_id"));
                    }
                }
            }
            return invitedIds;
        }, ids -> {
            Set<Integer> concurrentIds = ConcurrentHashMap.newKeySet();
            concurrentIds.addAll(ids);
            playerInvites.put(playerUuid, concurrentIds);

            // 邀请数据加载完成后，检查玩家当前是否在无权进入的家园世界中
            Player player = plugin.getServer().getPlayer(playerUuid);
            if (player != null && player.isOnline()) {
                checkAndEjectAfterLoad(player);
            }
        }, error -> {
            plugin.getLogger().warning("加载邀请数据失败: " + error.getMessage());
        });
    }

    /**
     * 邀请数据加载完成后，检查玩家当前所在世界。
     * 如果在无权进入的家园世界中，传送回主世界。
     */
    private void checkAndEjectAfterLoad(Player player) {
        // 优先读取 PDC 中记录的"离开位置"（从 homeland 下线后的重连恢复候选）
        QuitLocation quit = readQuitLocation(player);

        String targetWorldKey;
        if (quit != null) {
            targetWorldKey = quit.worldKey();
        } else {
            targetWorldKey = player.getWorld().getKey().getKey();
        }

        Homeland homeland = homelandStore.getByWorldKey(targetWorldKey);
        if (homeland == null) {
            // 目标非家园：清除可能的 PDC 记录后放行
            clearQuitLocation(player);
            return;
        }

        if (!canEnterWorld(player, targetWorldKey)) {
            // 无权进入 → 踢出大厅
            org.bukkit.World mainWorld = plugin.getServer().getWorlds().get(0);
            player.getScheduler().execute(plugin, () -> {
                player.teleportAsync(mainWorld.getSpawnLocation());
                MessageUtil.send(player, plugin.getConfigManager().getMessage("homeland-eject"));
            }, () -> {}, 1L);
            clearQuitLocation(player);
            return;
        }

        // 有权进入
        if (quit != null) {
            // 重连恢复候选 → 加载世界并传送回原位置
            restoreToWorld(player, targetWorldKey, quit.x(), quit.y(), quit.z(), quit.yaw(), quit.pitch());
            clearQuitLocation(player);
        }
        // 非 PDC 候选（玩家已在已加载 homeland 中）：维持现状
    }

    /**
     * 原地恢复：确保目标家园世界已加载，并将玩家传送到记录坐标。
     * 若目标是子维度，必须先确保主世界已加载——否则子维度无法从主世界复制
     * 游戏规则/难度，也无法建立传送门 link（WorldLoadListener 依赖主世界已加载）。
     */
    private void restoreToWorld(Player player, String worldKey, double x, double y, double z, float yaw, float pitch) {
        Homeland homeland = getHomelandByWorldKey(worldKey);
        if (homeland == null) return;

        boolean isOverworld = worldKey.equals(homeland.getWorldKey());
        if (isOverworld) {
            loadAndTeleport(player, worldKey, x, y, z, yaw, pitch);
            return;
        }

        // 子维度：先确保主世界已加载，再加载子维度
        World overworld = getHomelandWorld(homeland.getWorldKey());
        if (overworld != null) {
            loadAndTeleport(player, worldKey, x, y, z, yaw, pitch);
        } else {
            MessageUtil.send(player, plugin.getConfigManager().getMessage("homeland-rejoin-loading"));
            loadHomelandWorldAsync(homeland, ow -> {
                if (ow == null) return; // 主世界加载失败，留大厅
                loadAndTeleport(player, worldKey, x, y, z, yaw, pitch);
            });
        }
    }

    /**
     * 加载指定家园世界（若未加载）并将玩家传送到记录坐标。
     */
    private void loadAndTeleport(Player player, String worldKey, double x, double y, double z, float yaw, float pitch) {
        World loaded = getHomelandWorld(worldKey);
        if (loaded != null) {
            teleportToLocation(player, loaded, x, y, z, yaw, pitch);
            return;
        }
        MessageUtil.send(player, plugin.getConfigManager().getMessage("homeland-rejoin-loading"));
        loadHomelandWorldByKey(worldKey, world -> {
            if (world == null) return; // 加载失败，留大厅
            teleportToLocation(player, world, x, y, z, yaw, pitch);
        }, error -> {
            if (error instanceof HomelandOperationCoordinator.BusyException) {
                sendAsyncMessage(player, plugin.getConfigManager().getMessage(
                        "homeland-operation-busy"));
            }
        });
    }

    private void teleportToLocation(Player player, World world, double x, double y, double z, float yaw, float pitch) {
        player.getScheduler().execute(plugin, () -> {
            if (player.isOnline()) {
                player.teleportAsync(new Location(world, x, y, z, yaw, pitch));
            }
        }, () -> {}, 1L);
    }

    // ==================== 重连防坠虚空 ====================

    private static final String QUIT_LOC_KEY = "homeland_quit_location";

    /**
     * 玩家下线时记录其所在位置（若在 homeland 世界）。
     * 在 PlayerQuitEvent 中调用，Paper 在其后保存玩家数据，故 PDC 会持久化。
     */
    public void recordQuitLocation(Player player) {
        if (plugin.getConfigManager().isBranchMode()) return;

        String worldKey = player.getWorld().getKey().getKey();
        if (getHomelandByWorldKey(worldKey) == null) {
            // 不在 homeland：清除旧记录
            clearQuitLocation(player);
            return;
        }

        Location loc = player.getLocation();
        String encoded = worldKey + ";" + loc.getX() + ";" + loc.getY() + ";" + loc.getZ()
                + ";" + loc.getYaw() + ";" + loc.getPitch();
        player.getPersistentDataContainer().set(
                new NamespacedKey(plugin, QUIT_LOC_KEY), PersistentDataType.STRING, encoded);
    }

    /**
     * 阶段 1（join 时同步调用）：若玩家的目标 homeland 世界未加载，立即停泊大厅以防坠虚空。
     * 不做权限检查、不传送回去（交给 checkAndEjectAfterLoad 阶段 2）。
     */
    public void rescueFromVoidOnJoin(Player player) {
        if (plugin.getConfigManager().isBranchMode()) return;

        QuitLocation quit = readQuitLocation(player);
        boolean needRescue = false;

        // 情形 A：有 PDC 记录（上次从 homeland 下线），目标世界当前未加载（卸载或已删除）→ 需停泊大厅
        if (quit != null && getHomelandWorld(quit.worldKey()) == null) {
            needRescue = true;
        } else {
            // 情形 B（兜底，无 PDC 的旧玩家）：当前世界是 homeland 但实际未加载
            String currentKey = player.getWorld().getKey().getKey();
            if (getHomelandByWorldKey(currentKey) != null && getHomelandWorld(currentKey) == null) {
                needRescue = true;
            }
        }

        if (needRescue) {
            // 关键：必须延迟到下一 tick 执行传送。在 PlayerJoinEvent 期间（placeNewPlayer 尚未完成）
            // 同步调用 teleportAsync 会触发 "Player is already removed from player chunk loader"。
            // 延迟 1 tick 等 placeNewPlayer 完成后再传送（与现有 checkAndEject 的 1L 延迟同理）。
            // 1 tick 的下坠约 0.05 格，不会造成伤害，坠虚空致死需数秒。
            player.getScheduler().execute(plugin, () -> rescueToMainSpawn(player), () -> {}, 1L);
        }
    }

    private void rescueToMainSpawn(Player player) {
        org.bukkit.World mainWorld = plugin.getServer().getWorlds().get(0);
        if (mainWorld == null) return;
        player.teleportAsync(mainWorld.getSpawnLocation());
    }

    private QuitLocation readQuitLocation(Player player) {
        String encoded = player.getPersistentDataContainer()
                .get(new NamespacedKey(plugin, QUIT_LOC_KEY), PersistentDataType.STRING);
        if (encoded == null) return null;
        try {
            String[] p = encoded.split(";");
            if (p.length != 6) return null;
            return new QuitLocation(p[0], Double.parseDouble(p[1]), Double.parseDouble(p[2]),
                    Double.parseDouble(p[3]), Float.parseFloat(p[4]), Float.parseFloat(p[5]));
        } catch (Exception e) {
            return null;
        }
    }

    private void clearQuitLocation(Player player) {
        player.getPersistentDataContainer().remove(new NamespacedKey(plugin, QUIT_LOC_KEY));
    }

    private record QuitLocation(String worldKey, double x, double y, double z, float yaw, float pitch) {}

    /**
     * 检查玩家是否可以进入指定世界
     */
    public boolean canEnterWorld(Player player, String worldKey) {
        if (isWorldOperationPending(worldKey)) return false;
        return hasWorldAccess(player, worldKey);
    }

    private boolean hasWorldAccess(Player player, String worldKey) {
        Homeland homeland = homelandStore.getByWorldKey(worldKey);
        if (homeland == null) return true; // 非家园世界，不拦截

        // 管理员绕过
        if (player.hasPermission("simplehomeland.admin")) return true;
        // 主人
        if (homeland.getOwnerUuid().equals(player.getUniqueId())) return true;
        // 公开
        if (homeland.isPublic()) return true;
        // 被邀请（邀请数据尚未加载完成时暂时放行，loadInvites 完成后会校验并踢出）
        Set<Integer> invites = playerInvites.get(player.getUniqueId());
        if (invites == null) return true;
        if (invites.contains(homeland.getId())) return true;

        return false;
    }

    private boolean isWorldUnavailable(Homeland homeland) {
        return startupMigrationActive.get() || homeland.isOverworldResetPending()
                || operationCoordinator.isDestructiveOperationActive(homeland.getId());
    }

    public boolean isWorldCleanupPending(String worldKey) {
        return worldOperations.isCleanupPending(worldKey);
    }

    public boolean isWorldOperationPending(String worldKey) {
        worldKey = HomelandWorldKeys.normalize(worldKey);
        if (activeWorldLoads.containsKey(worldKey)
                || (!branchMode && !plugin.getWorldService().isReady(worldKey))) return true;
        if (worldOperations.isCleanupPending(worldKey)) return true;
        Homeland homeland = homelandStore.getByWorldKey(worldKey);
        return homeland != null && isWorldUnavailable(homeland);
    }

    public boolean isWorldAccessStateReady() {
        return worldIndexLoaded && !startupMigrationActive.get()
                && (branchMode || worldOperations.isCleanupIndexLoaded());
    }

    public boolean isStartupMigrationActive() {
        return startupMigrationActive.get();
    }

    /**
     * 检查访客在指定家园世界中是否有某个权限标志。
     * 主人、被邀请者、管理员自动放行。
     */
    public boolean checkVisitorFlag(Player player, String worldKey, VisitorFlag flag) {
        // 大厅世界检查
        org.bukkit.World mainWorld = plugin.getServer().getWorlds().get(0);
        if (mainWorld != null && mainWorld.getKey().getKey().equals(worldKey)) {
            if (player.hasPermission("simplehomeland.admin")) return true;
            return plugin.getConfigManager().getLobbyVisitorFlags().get(flag);
        }

        Homeland homeland = homelandStore.getByWorldKey(worldKey);
        if (homeland == null) return true;                                       // 非家园世界
        if (homeland.getOwnerUuid().equals(player.getUniqueId())) return true;   // 主人
        if (player.hasPermission("simplehomeland.admin")) return true;           // 管理员
        Set<Integer> invites = playerInvites.get(player.getUniqueId());
        if (invites != null && invites.contains(homeland.getId())) return true;  // 被邀请者
        return homeland.getVisitorFlags().get(flag);                              // 访客 → 查 flag
    }

    /**
     * 更新访客权限标志
     */
    public void updateVisitorFlag(Player owner, String homelandName, VisitorFlag flag, boolean value,
                                  Runnable onSuccess, Runnable onFailure) {
        updateVisitorFlagInternal(owner, owner.getUniqueId(), homelandName, flag, value, onSuccess, onFailure);
    }

    public void updateVisitorFlagAdmin(Player sender, UUID ownerUuid, String homelandName, VisitorFlag flag, boolean value,
                                       Runnable onSuccess, Runnable onFailure) {
        updateVisitorFlagInternal(sender, ownerUuid, homelandName, flag, value, onSuccess, onFailure);
    }

    private void updateVisitorFlagInternal(CommandSender messageTarget, UUID ownerUuid, String homelandName,
                                           VisitorFlag flag, boolean value, Runnable onSuccess, Runnable onFailure) {
        Optional<Homeland> optHomeland = getHomeland(ownerUuid, homelandName);
        if (optHomeland.isEmpty()) {
            MessageUtil.send(messageTarget, plugin.getConfigManager().getMessage("homeland-not-found", "name", homelandName));
            onFailure.run();
            return;
        }

        Homeland homeland = optHomeland.get();
        homeland.getVisitorFlags().set(flag, value);
        String json = homeland.getVisitorFlags().toJson();

        plugin.getDatabaseQueue().submit("update-visitor-flag-" + homeland.getId(), conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE homeland SET visitor_flags = ? WHERE id = ?")) {
                stmt.setString(1, json);
                stmt.setInt(2, homeland.getId());
                stmt.executeUpdate();
            }
            return null;
        }, result -> {
            String valueStr = value ? "允许" : "禁止";
            MessageUtil.send(messageTarget, plugin.getConfigManager().getMessage(
                    "visitor-flag-updated", "flag", flag.getDisplayName(), "value", valueStr));
            onSuccess.run();
        }, error -> {
            plugin.getLogger().warning("更新访客权限失败: " + error.getMessage());
            homeland.getVisitorFlags().set(flag, !value);
            MessageUtil.send(messageTarget, plugin.getConfigManager().getMessage("visitor-flag-update-failed"));
            onFailure.run();
        });
    }

    /**
     * 更新自定义设置
     */
    public void updateCustomSetting(Player owner, String homelandName,
                                    String key, boolean value,
                                    Runnable onSuccess, Runnable onFailure) {
        executeHomelandOperation(owner, owner.getUniqueId(), homelandName,
                HomelandOperationCoordinator.OperationType.MODIFY_WORLD,
                homeland -> worldOperations.updateCustomSetting(
                        owner, homeland, key, value),
                onSuccess, onFailure);
    }

    public void updateCustomSettingAdmin(CommandSender sender, UUID ownerUuid,
                                         String homelandName, String key, boolean value,
                                         Runnable onSuccess, Runnable onFailure) {
        executeHomelandOperation(sender, ownerUuid, homelandName,
                HomelandOperationCoordinator.OperationType.MODIFY_WORLD,
                homeland -> worldOperations.updateCustomSetting(
                        sender, homeland, key, value),
                onSuccess, onFailure);
    }

    /**
     * 切换家园公开/私有状态
     */
    public void togglePublic(Player player, String homelandName, Runnable onSuccess, Runnable onFailure) {
        togglePublicInternal(player, player.getUniqueId(), homelandName, onSuccess, onFailure);
    }

    public void togglePublicAdmin(CommandSender sender, UUID ownerUuid, String homelandName, Runnable onSuccess, Runnable onFailure) {
        togglePublicInternal(sender, ownerUuid, homelandName, onSuccess, onFailure);
    }

    private void togglePublicInternal(CommandSender messageTarget, UUID ownerUuid, String homelandName, Runnable onSuccess, Runnable onFailure) {
        Optional<Homeland> optHomeland = getHomeland(ownerUuid, homelandName);
        if (optHomeland.isEmpty()) {
            MessageUtil.send(messageTarget, plugin.getConfigManager().getMessage("homeland-not-found", "name", homelandName));
            onFailure.run();
            return;
        }

        Homeland homeland = optHomeland.get();
        boolean newPublic = !homeland.isPublic();

        plugin.getDatabaseQueue().submit("toggle-public", conn -> {
            try (PreparedStatement stmt = conn.prepareStatement("UPDATE homeland SET is_public = ? WHERE id = ?")) {
                stmt.setBoolean(1, newPublic);
                stmt.setInt(2, homeland.getId());
                stmt.executeUpdate();
            }
            return null;
        }, result -> {
            homeland.setPublic(newPublic);
            if (newPublic) {
                MessageUtil.send(messageTarget, plugin.getConfigManager().getMessage("homeland-set-public", "name", homelandName));
            } else {
                MessageUtil.send(messageTarget, plugin.getConfigManager().getMessage("homeland-set-private", "name", homelandName));
            }
            onSuccess.run();
        }, error -> {
            plugin.getLogger().warning("切换公开状态失败: " + error.getMessage());
            MessageUtil.send(messageTarget, plugin.getConfigManager().getMessage("homeland-toggle-failed"));
            onFailure.run();
        });
    }

    /**
     * 邀请玩家到家园
     */
    public void invitePlayer(Player inviter, String homelandName, UUID targetUuid, String targetName,
                             Runnable onSuccess, Runnable onFailure) {
        invitePlayerInternal(inviter, inviter.getUniqueId(), homelandName, targetUuid, targetName, onSuccess, onFailure);
    }

    public void invitePlayerAdmin(CommandSender sender, UUID ownerUuid, String homelandName, UUID targetUuid, String targetName,
                                  Runnable onSuccess, Runnable onFailure) {
        invitePlayerInternal(sender, ownerUuid, homelandName, targetUuid, targetName, onSuccess, onFailure);
    }

    private void invitePlayerInternal(CommandSender messageTarget, UUID ownerUuid, String homelandName,
                                      UUID targetUuid, String targetName, Runnable onSuccess, Runnable onFailure) {
        Optional<Homeland> optHomeland = getHomeland(ownerUuid, homelandName);
        if (optHomeland.isEmpty()) {
            MessageUtil.send(messageTarget, plugin.getConfigManager().getMessage("homeland-not-found", "name", homelandName));
            onFailure.run();
            return;
        }

        Homeland homeland = optHomeland.get();
        if (homeland.getOwnerUuid().equals(targetUuid)) {
            MessageUtil.send(messageTarget, plugin.getConfigManager().getMessage("invite-is-owner"));
            onFailure.run();
            return;
        }

        // 检查是否已邀请
        Set<Integer> existingInvites = playerInvites.get(targetUuid);
        if (existingInvites != null && existingInvites.contains(homeland.getId())) {
            MessageUtil.send(messageTarget, plugin.getConfigManager().getMessage("invite-already-exists", "player", targetName, "name", homelandName));
            onFailure.run();
            return;
        }

        plugin.getDatabaseQueue().submit("invite-player", conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO homeland_invite (homeland_id, player_uuid) VALUES (?, ?)")) {
                stmt.setInt(1, homeland.getId());
                stmt.setString(2, targetUuid.toString());
                stmt.executeUpdate();
            }
            return null;
        }, result -> {
            // 更新缓存
            playerInvites.computeIfAbsent(targetUuid, k -> new HashSet<>()).add(homeland.getId());
            MessageUtil.send(messageTarget, plugin.getConfigManager().getMessage("invite-sent", "player", targetName, "name", homelandName));

            // 通知被邀请者（如果在线）
            Player target = plugin.getServer().getPlayer(targetUuid);
            if (target != null && target.isOnline()) {
                String inviterName = messageTarget instanceof Player p ? p.getName() : "Admin";
                sendAsyncMessage(target, plugin.getConfigManager().getMessage("invite-received", "name", homelandName, "owner", inviterName));
            }
            onSuccess.run();
        }, error -> {
            plugin.getLogger().warning("邀请玩家失败: " + error.getMessage());
            MessageUtil.send(messageTarget, plugin.getConfigManager().getMessage("invite-failed"));
            onFailure.run();
        });
    }

    /**
     * 取消邀请
     */
    public void uninvitePlayer(Player inviter, String homelandName, UUID targetUuid, String targetName,
                               Runnable onSuccess, Runnable onFailure) {
        uninvitePlayerInternal(inviter, inviter.getUniqueId(), homelandName, targetUuid, targetName, onSuccess, onFailure);
    }

    public void uninvitePlayerAdmin(CommandSender sender, UUID ownerUuid, String homelandName, UUID targetUuid, String targetName,
                                    Runnable onSuccess, Runnable onFailure) {
        uninvitePlayerInternal(sender, ownerUuid, homelandName, targetUuid, targetName, onSuccess, onFailure);
    }

    private void uninvitePlayerInternal(CommandSender messageTarget, UUID ownerUuid, String homelandName,
                                        UUID targetUuid, String targetName, Runnable onSuccess, Runnable onFailure) {
        Optional<Homeland> optHomeland = getHomeland(ownerUuid, homelandName);
        if (optHomeland.isEmpty()) {
            MessageUtil.send(messageTarget, plugin.getConfigManager().getMessage("homeland-not-found", "name", homelandName));
            onFailure.run();
            return;
        }

        Homeland homeland = optHomeland.get();

        plugin.getDatabaseQueue().submit("uninvite-player", conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "DELETE FROM homeland_invite WHERE homeland_id = ? AND player_uuid = ?")) {
                stmt.setInt(1, homeland.getId());
                stmt.setString(2, targetUuid.toString());
                int rows = stmt.executeUpdate();
                return rows > 0;
            }
        }, removed -> {
            if (removed) {
                Set<Integer> invites = playerInvites.get(targetUuid);
                if (invites != null) invites.remove(homeland.getId());
                MessageUtil.send(messageTarget, plugin.getConfigManager().getMessage("invite-revoked", "player", targetName, "name", homelandName));
                onSuccess.run();
            } else {
                MessageUtil.send(messageTarget, plugin.getConfigManager().getMessage("invite-not-found", "player", targetName, "name", homelandName));
                onFailure.run();
            }
        }, error -> {
            plugin.getLogger().warning("取消邀请失败: " + error.getMessage());
            MessageUtil.send(messageTarget, plugin.getConfigManager().getMessage("invite-failed"));
            onFailure.run();
        });
    }

    /**
     * 异步获取家园的邀请列表（玩家名称列表）
     * 仅在DB线程中查询UUID，名称解析在主线程回调中完成以避免阻塞
     */
    public void getInvitedNames(int homelandId, Consumer<List<String>> callback) {
        plugin.getDatabaseQueue().submit("get-invited-names-" + homelandId, conn -> {
            List<UUID> uuids = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT player_uuid FROM homeland_invite WHERE homeland_id = ?")) {
                stmt.setInt(1, homelandId);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        uuids.add(UUID.fromString(rs.getString("player_uuid")));
                    }
                }
            }
            return uuids;
        }, uuids -> {
            // 在主线程/回调线程中解析名称，避免在DB线程中调用阻塞的getOfflinePlayer
            List<String> names = new ArrayList<>();
            for (UUID uuid : uuids) {
                @SuppressWarnings("deprecation")
                String name = plugin.getServer().getOfflinePlayer(uuid).getName();
                names.add(name != null ? name : uuid.toString().substring(0, 8));
            }
            callback.accept(names);
        }, error -> {
            plugin.getLogger().warning("获取邀请列表失败: " + error.getMessage());
            callback.accept(Collections.emptyList());
        });
    }

    /**
     * 异步获取家园的邀请列表（UUID + 名称）
     */
    public void getInvitedPlayers(int homelandId, Consumer<Map<UUID, String>> callback) {
        plugin.getDatabaseQueue().submit("get-invited-players-" + homelandId, conn -> {
            Map<UUID, String> result = new LinkedHashMap<>();
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT player_uuid FROM homeland_invite WHERE homeland_id = ?")) {
                stmt.setInt(1, homelandId);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        UUID uuid = UUID.fromString(rs.getString("player_uuid"));
                        result.put(uuid, null);
                    }
                }
            }
            return result;
        }, uuidMap -> {
            Map<UUID, String> resolved = new LinkedHashMap<>();
            for (UUID uuid : uuidMap.keySet()) {
                @SuppressWarnings("deprecation")
                String name = plugin.getServer().getOfflinePlayer(uuid).getName();
                resolved.put(uuid, name != null ? name : uuid.toString().substring(0, 8));
            }
            callback.accept(resolved);
        }, error -> {
            plugin.getLogger().warning("获取邀请列表失败: " + error.getMessage());
            callback.accept(Collections.emptyMap());
        });
    }

    // ==================== 自动卸载 ====================

    public void startAutoUnloadTask() {
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            if (!worldIndexLoaded) loadWorldKeyIndex();
            if (startupMigrationActive.get()) return;
            recoverPendingWorldOperations();
            worldOperations.recoverPendingCleanups();
        }, 1200L, 1200L);

        if (plugin.getConfigManager().isPreloadWorlds()) {
            plugin.getLogger().info("家园世界预加载已启用，自动卸载已禁用");
            return;
        }

        int autoUnloadSeconds = plugin.getConfigManager().getAutoUnloadSeconds();
        if (autoUnloadSeconds <= 0) {
            plugin.getLogger().info("家园世界自动卸载已禁用");
            return;
        }

        plugin.getLogger().info("家园世界自动卸载已启用，空闲 " + autoUnloadSeconds + " 秒后卸载");

        // 每 60 秒检查一次（1200 ticks = 60s）
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            checkAndUnloadIdleWorlds();
        }, 1200L, 1200L);
    }

    public void touchLastPlayerTime(String worldKey) {
        // 所有维度共用家园主世界的空闲计时，与卸载检查的读取 key 一致。
        lastPlayerPresentTime.put(HomelandWorldKeys.base(worldKey), System.currentTimeMillis());
    }

    private void checkAndUnloadIdleWorlds() {
        if (startupMigrationActive.get()) return;
        int autoUnloadSeconds = plugin.getConfigManager().getAutoUnloadSeconds();
        if (autoUnloadSeconds <= 0) return;
        if (plugin.getConfigManager().isPreloadWorlds()) return;

        long threshold = autoUnloadSeconds * 1000L;
        long now = System.currentTimeMillis();

        for (Map.Entry<String, Homeland> entry : homelandStore.worldEntries().entrySet()) {
            String worldKey = entry.getKey();
            Homeland homeland = entry.getValue();

            if (homeland.isOverworldResetPending()
                    || operationCoordinator.isBusy(homeland.getId())) continue;

            // 只处理主世界 key（跳过 _nether / _the_end 后缀的条目）
            if (!worldKey.equals(homeland.getWorldKey())) continue;

            // 收集该家园所有已加载的维度
            List<World> loadedWorlds = new ArrayList<>();
            World overworld = getHomelandWorld(worldKey);
            if (overworld != null) loadedWorlds.add(overworld);
            if (homeland.hasNether()) {
                World nether = getHomelandWorld(worldKey + "_nether");
                if (nether != null) loadedWorlds.add(nether);
            }
            if (homeland.hasEnd()) {
                World end = getHomelandWorld(worldKey + "_the_end");
                if (end != null) loadedWorlds.add(end);
            }

            if (loadedWorlds.isEmpty()) {
                lastPlayerPresentTime.remove(worldKey);
                continue;
            }

            // 检查是否有玩家在任意维度中
            boolean hasPlayers = false;
            for (World w : loadedWorlds) {
                if (!w.getPlayers().isEmpty()) {
                    hasPlayers = true;
                    break;
                }
            }

            if (hasPlayers) {
                lastPlayerPresentTime.put(worldKey, now);
            } else {
                Long lastPresent = lastPlayerPresentTime.get(worldKey);
                if (lastPresent == null) {
                    // 首次检测到空，记录时间
                    lastPlayerPresentTime.put(worldKey, now);
                } else if (now - lastPresent >= threshold) {
                    // 超过阈值，卸载
                    unloadHomelandWorlds(worldKey, homeland, loadedWorlds);
                }
            }
        }
    }

    private void unloadHomelandWorlds(String worldKey, Homeland homeland, List<World> worlds) {
        WorldsAccess provider = plugin.getWorldsAccess();
        if (provider == null) return;

        lastPlayerPresentTime.remove(worldKey);

        for (World world : worlds) {
            // 确保没有玩家残留
            if (!world.getPlayers().isEmpty()) return;

            plugin.getLogger().info("正在卸载空闲家园世界: " + world.getName());
            provider.unload(world, true).thenAccept(success -> {
                if (success) {
                    plugin.getLogger().info("已自动卸载空闲家园世界: " + world.getName());
                    Bukkit.getGlobalRegionScheduler().execute(plugin, () -> publishLoadedWorlds());
                } else {
                    plugin.getLogger().warning("卸载家园世界失败: " + world.getName());
                }
            }).exceptionally(throwable -> {
                plugin.getLogger().warning("卸载家园世界异常: " + world.getName() + " - " + throwable.getMessage());
                return null;
            });
        }
    }

    // ==================== 关闭 ====================

    public void shutdown() {
        if (startupMigration != null) startupMigration.shutdown();
        homelandCache.clear();
        homelandStore.clear();
        activeWorldLoads.clear();
        playerInvites.clear();
        lastPlayerPresentTime.clear();
        operationCoordinator.clear();
    }

    // ==================== 工具方法 ====================

    /**
     * 判断异常是否为数据库唯一约束违反（并发创建同名家园时可能发生）。
     * 兼容 H2 和 MySQL 的错误码/状态。
     */
    private boolean isUniqueConstraintViolation(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof java.sql.SQLException sqlEx) {
                String state = sqlEx.getSQLState();
                // H2: 23505 (UNIQUE_CONSTRAINT_VIOLATION)
                // MySQL: 23000 (通用约束违反), 23001 不存在... 但23000覆盖了unique
                if ("23505".equals(state) || "23000".equals(state)) {
                    return true;
                }
                int errorCode = sqlEx.getErrorCode();
                // H2 error code for unique constraint: 22001 或 23505
                // MySQL error code for duplicate key: 1062
                if (errorCode == 23505 || errorCode == 1062) {
                    return true;
                }
            }
            cause = cause.getCause();
        }
        // 兜底：检查消息中是否包含关键信息
        String msg = error.getMessage();
        if (msg != null) {
            String lower = msg.toLowerCase();
            if (lower.contains("unique") || lower.contains("duplicate") || lower.contains("constraint")) {
                return true;
            }
        }
        return false;
    }

}
