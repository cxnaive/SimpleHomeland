package dev.user.homeland.manager;

import dev.user.homeland.SimpleHomelandPlugin;
import dev.user.homeland.api.TeleportResult;
import dev.user.homeland.config.ConfigManager;
import dev.user.homeland.config.ConfigManager.GameRuleConfig;
import dev.user.homeland.model.Homeland;
import dev.user.homeland.model.VisitorFlag;
import dev.user.homeland.model.VisitorFlags;
import dev.user.homeland.util.MessageUtil;
import net.kyori.adventure.key.Key;
import net.thenextlvl.worlds.api.WorldsProvider;
import net.thenextlvl.worlds.api.level.Level;
import net.thenextlvl.worlds.api.link.LinkProvider;
import net.thenextlvl.worlds.api.view.LevelView;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class HomelandManager {

    private final SimpleHomelandPlugin plugin;
    private final ConcurrentHashMap<UUID, List<Homeland>> homelandCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Homeland> worldKeyIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<java.util.UUID, Homeland> worldUuidIndex = new ConcurrentHashMap<>();
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

    public HomelandManager(SimpleHomelandPlugin plugin) {
        this.plugin = plugin;
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
    }

    private void loadHomelands(UUID playerUuid) {
        plugin.getDatabaseQueue().submit("load-homelands-" + playerUuid, conn -> {
            List<Homeland> homelands = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT id, owner_uuid, name, world_key, border_radius, has_nether, has_end, is_public, visitor_flags FROM homeland WHERE owner_uuid = ?")) {
                stmt.setString(1, playerUuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        homelands.add(new Homeland(
                                rs.getInt("id"),
                                UUID.fromString(rs.getString("owner_uuid")),
                                rs.getString("name"),
                                rs.getString("world_key"),
                                rs.getInt("border_radius"),
                                rs.getBoolean("has_nether"),
                                rs.getBoolean("has_end"),
                                rs.getBoolean("is_public"),
                                VisitorFlags.fromJson(rs.getString("visitor_flags"))
                        ));
                    }
                }
            }
            return homelands;
        }, homelands -> {
            homelandCache.put(playerUuid, new CopyOnWriteArrayList<>(homelands));
            // 更新 worldKeyIndex
            for (Homeland h : homelands) {
                indexHomeland(h);
            }
        }, error -> {
            plugin.getLogger().warning("加载玩家家园数据失败: " + error.getMessage());
        });
    }

    public List<Homeland> getHomelands(UUID playerUuid) {
        return homelandCache.getOrDefault(playerUuid, Collections.emptyList());
    }

    public boolean isHomelandsCached(UUID playerUuid) {
        return homelandCache.containsKey(playerUuid);
    }

    /**
     * 异步获取玩家家园列表，优先从缓存读取，缓存未命中时通过 DatabaseQueue 从 DB 加载。
     */
    public void getOrLoadHomelandsAsync(UUID playerUuid, Consumer<List<Homeland>> callback) {
        getOrLoadHomelandsAsync(playerUuid, callback, () -> {});
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
                    "SELECT id, owner_uuid, name, world_key, border_radius, has_nether, has_end, is_public, visitor_flags FROM homeland WHERE owner_uuid = ?")) {
                stmt.setString(1, playerUuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        homelands.add(new Homeland(
                                rs.getInt("id"),
                                UUID.fromString(rs.getString("owner_uuid")),
                                rs.getString("name"),
                                rs.getString("world_key"),
                                rs.getInt("border_radius"),
                                rs.getBoolean("has_nether"),
                                rs.getBoolean("has_end"),
                                rs.getBoolean("is_public"),
                                VisitorFlags.fromJson(rs.getString("visitor_flags"))
                        ));
                    }
                }
            }
            return homelands;
        }, homelands -> {
            callback.accept(homelands);
        }, error -> {
            plugin.getLogger().warning("异步加载家园数据失败: " + error.getMessage());
            callback.accept(Collections.emptyList());
            onFailure.run();
        });
    }

    public Optional<Homeland> getHomeland(UUID playerUuid, String name) {
        return getHomelands(playerUuid).stream()
                .filter(h -> h.getName().equalsIgnoreCase(name))
                .findFirst();
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
        ConfigManager config = plugin.getConfigManager();

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

        // 检查重名
        if (getHomeland(ownerUuid, name).isPresent()) {
            messageTarget.sendMessage(MessageUtil.toComponent(config.getMessage("homeland-name-exists", "name", name)));
            lockedOnFailure.run();
            return;
        }

        // 检查经济（管理员跳过）
        final double moneyCost;
        final int pointsCost;
        if (!skipEconomy && messageTarget instanceof Player ownerPlayer) {
            moneyCost = config.getCreationMoney();
            pointsCost = config.getCreationPoints();
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

        // 创建世界
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            try {
                WorldsProvider provider = plugin.getWorldsProvider();

                var builder = provider.levelBuilder(java.nio.file.Path.of("homelands/" + worldKey))
                        .key(Key.key("simplehomeland", worldKey))
                        .name(name);

                switch (worldTypeKey) {
                    case "void" -> {
                        builder.chunkGenerator(new dev.user.homeland.generator.SkyblockChunkGenerator())
                               .structures(false)
                               .bonusChest(false);
                    }
                    case "flat" -> {
                        builder.generatorType(net.thenextlvl.worlds.api.generator.GeneratorType.FLAT)
                               .preset(net.thenextlvl.worlds.api.preset.Presets.CLASSIC_FLAT)
                               .structures(false)
                               .bonusChest(false);
                    }
                    default -> {
                        builder.structures(config.isWorldStructures())
                               .bonusChest(config.isWorldBonusChest());
                    }
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

                level.createAsync().thenAccept(overworld -> {
                    try {
                        // 空岛类型设置出生点到起始岛上
                        if ("void".equals(worldTypeKey)) {
                            overworld.setSpawnLocation(8, 63, 8);
                        }

                        // 设置 WorldBorder（需在全局区域线程执行）
                        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                            overworld.getWorldBorder().setCenter(0, 0);
                            overworld.getWorldBorder().setSize(borderRadius * 2);
                        });

                        // 设置自动加载
                        provider.levelView().setEnabled(overworld, true);
                        overworld.setAutoSave(true);
                        touchLastPlayerTime(worldKey);

                        // 应用默认游戏规则
                        applyDefaultGamerules(overworld);

                        // 写入数据库
                        java.util.UUID worldUuid = overworld.getUID();
                        plugin.getDatabaseQueue().submit("create-homeland", conn -> {
                            try (PreparedStatement stmt = conn.prepareStatement(
                                    "INSERT INTO homeland (owner_uuid, name, world_key, world_uuid, border_radius, has_nether, has_end, is_public, visitor_flags) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
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
                                stmt.executeUpdate();

                                try (ResultSet rs = stmt.getGeneratedKeys()) {
                                    if (rs.next()) {
                                        int id = rs.getInt(1);
                                        Homeland homeland = new Homeland(id, ownerUuid, name, worldKey, worldUuid, borderRadius, false, false, false, new VisitorFlags());
                                        homelandCache.computeIfAbsent(ownerUuid, k -> new CopyOnWriteArrayList<>()).add(homeland);
                                        indexHomeland(homeland);
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
                                plugin.getWorldsProvider().levelView().deleteAsync(overworld, true);
                            } catch (Exception ex) {
                                plugin.getLogger().warning("清理失败的世界失败: " + ex.getMessage());
                            }
                            refundEconomy(messageTarget instanceof Player p ? p : null, moneyCost, pointsCost);
                            lockedOnFailure.run();
                        });
                    } catch (Exception e) {
                        plugin.getLogger().severe("创建家园后续处理失败: " + e.getMessage());
                        refundEconomy(messageTarget instanceof Player p ? p : null, moneyCost, pointsCost);
                        sendAsyncMessage(messageTarget, config.getMessage("create-failed"));
                        lockedOnFailure.run();
                    }
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
        });
    }

    // ==================== 删除家园 ====================

    public void deleteHomeland(Player player, String name, Runnable onSuccess, Runnable onFailure) {
        UUID playerUuid = player.getUniqueId();
        Optional<Homeland> optHomeland = getHomeland(playerUuid, name);

        if (optHomeland.isEmpty()) {
            MessageUtil.send(player, plugin.getConfigManager().getMessage("homeland-not-found", "name", name));
            onFailure.run();
            return;
        }

        Homeland homeland = optHomeland.get();
        deleteHomelandCommon(player, homeland, "homeland-deleted", "delete-homeland", onSuccess, onFailure);
    }

    /**
     * 管理员删除家园（不需要玩家在线，仅需要UUID）
     */
    public void deleteHomelandAdmin(CommandSender sender, UUID ownerUuid, String homelandName, Runnable onSuccess, Runnable onFailure) {
        Optional<Homeland> optHomeland = getHomeland(ownerUuid, homelandName);

        if (optHomeland.isEmpty()) {
            sendAsyncMessage(sender, plugin.getConfigManager().getMessage("homeland-not-found", "name", homelandName));
            onFailure.run();
            return;
        }

        Homeland homeland = optHomeland.get();
        deleteHomelandCommon(sender, homeland, "homeland-deleted", "delete-homeland-admin", onSuccess, onFailure);
    }

    /**
     * 删除家园的共用逻辑：收集关联世界 → 传送玩家 → 删除DB记录 → 删除世界文件
     */
    private void deleteHomelandCommon(CommandSender messageTarget, Homeland homeland,
                                       String successMsgKey, String taskName,
                                       Runnable onSuccess, Runnable onFailure) {
        UUID ownerUuid = homeland.getOwnerUuid();

        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            try {
                WorldsProvider provider = plugin.getWorldsProvider();

                java.util.Set<World> worldsToDelete = new java.util.LinkedHashSet<>();
                java.util.Set<World> worldsToTeleport = new java.util.LinkedHashSet<>();

                World overworld = getHomelandWorld(homeland.getWorldKey());
                if (overworld != null) {
                    worldsToDelete.add(overworld);
                    worldsToTeleport.add(overworld);

                    LinkProvider linker = provider.linkProvider();
                    Optional<net.thenextlvl.worlds.api.link.LinkTree> treeOpt = linker.getLinkTree(overworld);
                    if (treeOpt.isPresent()) {
                        net.thenextlvl.worlds.api.link.LinkTree tree = treeOpt.get();
                        tree.getNether().ifPresent(n -> {
                            worldsToDelete.add(n);
                            worldsToTeleport.add(n);
                        });
                        tree.getEnd().ifPresent(e -> {
                            worldsToDelete.add(e);
                            worldsToTeleport.add(e);
                        });
                    }
                }

                // 通过 key 补充查找（防止 LinkTree 未覆盖的情况）
                if (homeland.hasNether()) {
                    World nether = getHomelandWorld(homeland.getWorldKey() + "_nether");
                    if (nether != null) {
                        worldsToDelete.add(nether);
                        worldsToTeleport.add(nether);
                    }
                }
                if (homeland.hasEnd()) {
                    World end = getHomelandWorld(homeland.getWorldKey() + "_the_end");
                    if (end != null) {
                        worldsToDelete.add(end);
                        worldsToTeleport.add(end);
                    }
                }

                // 传送所有玩家到主世界
                org.bukkit.World mainWorld = Bukkit.getWorlds().get(0);
                for (World w : worldsToTeleport) {
                    for (Player p : w.getPlayers()) {
                        p.getScheduler().execute(plugin, () -> p.teleportAsync(mainWorld.getSpawnLocation()), null, 1L);
                    }
                }

                // 先删除数据库记录
                plugin.getDatabaseQueue().submit(taskName, conn -> {
                    try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM homeland WHERE id = ?")) {
                        stmt.setInt(1, homeland.getId());
                        stmt.executeUpdate();
                    }
                    return null;
                }, result -> {
                    List<Homeland> list = homelandCache.get(ownerUuid);
                    if (list != null) {
                        list.removeIf(h -> h.getId() == homeland.getId());
                    }
                    unindexHomeland(homeland);

                    sendAsyncMessage(messageTarget, plugin.getConfigManager().getMessage(successMsgKey, "name", homeland.getName()));
                    onSuccess.run();

                    // DB删除成功后删除世界文件
                    if (!worldsToDelete.isEmpty()) {
                        java.util.Set<World> worldsCopy = new java.util.LinkedHashSet<>(worldsToDelete);
                        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> {
                            for (World w : worldsCopy) {
                                try {
                                    provider.levelView().deleteAsync(w, false).thenAccept(deletionResult -> {
                                        if (!deletionResult.isSuccess()) {
                                            plugin.getLogger().warning("删除世界失败: " + w.getName() + " - " + deletionResult);
                                        }
                                    }).exceptionally(throwable -> {
                                        plugin.getLogger().warning("删除世界异常: " + w.getName() + " - " + throwable.getMessage());
                                        return null;
                                    });
                                } catch (Exception e) {
                                    plugin.getLogger().warning("删除世界失败: " + w.getName() + " - " + e.getMessage());
                                }
                            }
                        }, 40L);
                    }
                }, error -> {
                    plugin.getLogger().warning("删除家园数据库记录失败: " + error.getMessage());
                    sendAsyncMessage(messageTarget, plugin.getConfigManager().getMessage("delete-failed-db"));
                    onFailure.run();
                });

            } catch (Exception e) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "删除家园失败: " + e.getMessage(), e);
                sendAsyncMessage(messageTarget, plugin.getConfigManager().getMessage("delete-failed-db"));
                onFailure.run();
            }
        });
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
            World world = getHomelandWorld(homeland.getWorldKey());

            if (world != null) {
                // 世界已加载，直接传送
                teleportToWorld(teleporter, homeland, world);
            } else {
                // 尝试通过 Worlds API 加载世界
                loadAndTeleport(teleporter, homeland);
            }
        });
    }

    /**
     * 将玩家传送回服务器主世界出生点。
     */
    public void teleportToMainWorld(Player player) {
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

    private void loadAndTeleport(Player teleporter, Homeland homeland) {
        sendAsyncMessage(teleporter, plugin.getConfigManager().getMessage("world-loading"));
        loadHomelandWorldAsync(homeland, world -> {
            if (world == null) {
                sendAsyncMessage(teleporter, plugin.getConfigManager().getMessage("world-not-loaded"));
                return;
            }
            teleportToWorld(teleporter, homeland, world);
        });
    }

    /**
     * 异步加载家园世界。加载成功后回调 World（主线程），失败回调 null。
     */
    private void loadHomelandWorldAsync(Homeland homeland, Consumer<World> callback) {
        loadHomelandWorldAsync(homeland.getWorldKey(), homeland, callback);
    }

    /**
     * 异步加载家园世界（支持子维度）。
     * @param targetWorldKey 实际要加载的世界 key（可以是主世界、_nether、_the_end）
     */
    private void loadHomelandWorldAsync(String targetWorldKey, Homeland homeland, Consumer<World> callback) {
        WorldsProvider provider = plugin.getWorldsProvider();
        if (provider == null) {
            callback.accept(null);
            return;
        }

        java.nio.file.Path worldPath = provider.levelView().getWorldContainer().resolve("homelands/" + targetWorldKey);
        var builderOpt = provider.levelView().read(worldPath);
        if (builderOpt.isEmpty()) {
            callback.accept(null);
            return;
        }

        Level level = builderOpt.get().build();
        if (!level.isWorldKnown()) {
            callback.accept(null);
            return;
        }

        level.createAsync().thenAccept(world -> {
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                int radius = homeland.getBorderRadius();
                world.getWorldBorder().setCenter(0, 0);
                world.getWorldBorder().setSize(radius * 2);
                provider.levelView().setEnabled(world, true);
                world.setAutoSave(true);
                touchLastPlayerTime(targetWorldKey);
                // 懒迁移：填充缺失的 world_uuid
                if (homeland.getWorldUuid() == null) {
                    updateWorldUuid(homeland, world.getUID());
                }
                callback.accept(world);
            });
        }).exceptionally(throwable -> {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "加载家园世界失败: " + throwable.getMessage(), throwable);
            callback.accept(null);
            return null;
        });
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
        // 使用 1 参数版本：DB 错误时回调空列表 → 找不到 homeland → WORLD_NOT_FOUND
        getOrLoadHomelandsAsync(ownerUuid, homelands -> {
            Optional<Homeland> optHomeland = homelands.stream()
                    .filter(h -> h.getName().equalsIgnoreCase(homelandName))
                    .findFirst();

            if (optHomeland.isEmpty()) {
                callback.accept(TeleportResult.WORLD_NOT_FOUND);
                return;
            }

            teleportToHomelandInternal(player, optHomeland.get(), optHomeland.get().getWorldKey(), bypassAccessCheck, location, callback);
        });
    }

    /**
     * API 入口：按世界 UUID 传送。
     * 先查 worldUuidIndex（支持未加载世界），再 fallback Bukkit.getWorld。
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
        Homeland homeland = worldUuidIndex.get(worldUUID);
        if (homeland != null) {
            teleportToHomelandInternal(player, homeland, homeland.getWorldKey(), bypassAccessCheck, location, callback);
            return;
        }

        // 非家园世界，尝试按 Bukkit UUID 查找
        World bukkitWorld = Bukkit.getWorld(worldUUID);
        if (bukkitWorld != null) {
            doTeleportWithResult(player, bukkitWorld, location, callback, TeleportResult.SUCCESS_OTHER_WORLD);
        } else {
            callback.accept(TeleportResult.WORLD_NOT_FOUND);
        }
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
        Homeland homeland = getHomelandByWorldKey(worldKey);
        if (homeland != null) {
            teleportToHomelandInternal(player, homeland, worldKey, bypassAccessCheck, location, callback);
            return;
        }

        // 非家园世界，尝试按 Bukkit 名称查找
        World bukkitWorld = Bukkit.getWorld(worldKey);
        if (bukkitWorld != null) {
            doTeleportWithResult(player, bukkitWorld, location, callback, TeleportResult.SUCCESS_OTHER_WORLD);
        } else {
            callback.accept(TeleportResult.WORLD_NOT_FOUND);
        }
    }

    /**
     * API 核心逻辑：权限检查 → 加载世界 → 传送 → 回调。
     * @param targetWorldKey 实际目标世界 key（可能是子维度如 xxx_nether）
     */
    private void teleportToHomelandInternal(Player player, Homeland homeland, String targetWorldKey,
                                             boolean bypassAccessCheck, @org.jetbrains.annotations.Nullable Location location,
                                             Consumer<TeleportResult> callback) {
        if (!bypassAccessCheck && !canEnterWorld(player, targetWorldKey)) {
            callback.accept(TeleportResult.ACCESS_DENIED);
            return;
        }

        // 标记绕过，防止 TeleportListener 二次拦截
        apiBypass.add(player.getUniqueId());

        World world = getHomelandWorld(targetWorldKey);
        if (world != null) {
            doTeleport(player, world, location, callback);
        } else {
            loadHomelandWorldAsync(targetWorldKey, homeland, loadedWorld -> {
                if (loadedWorld == null) {
                    apiBypass.remove(player.getUniqueId());
                    callback.accept(TeleportResult.WORLD_LOAD_FAILED);
                    return;
                }
                doTeleport(player, loadedWorld, location, callback);
            });
        }
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
        player.getScheduler().execute(plugin, () -> {
            player.teleportAsync(target).thenAccept(success -> {
                apiBypass.remove(player.getUniqueId());
                callback.accept(success ? successResult : TeleportResult.PLAYER_OFFLINE);
            });
        }, () -> {
            apiBypass.remove(player.getUniqueId());
            callback.accept(TeleportResult.PLAYER_OFFLINE);
        }, 1L);
    }

    /**
     * 检查玩家是否处于 API 绕过状态（用于 TeleportListener 放行）。
     */
    public boolean isApiBypass(UUID playerUuid) {
        return apiBypass.contains(playerUuid);
    }

    /**
     * 执行实际传送（家园世界），成功回调 SUCCESS。传送到指定位置，null 则用出生点。
     */
    private void doTeleport(Player player, World world, @org.jetbrains.annotations.Nullable Location location,
                            Consumer<TeleportResult> callback) {
        doTeleportWithResult(player, world, location, callback, TeleportResult.SUCCESS);
    }

    // ==================== 扩展边界 ====================

    public void expandBorder(Player player, String name, Runnable onSuccess, Runnable onFailure) {
        expandBorderInternal(player, player.getUniqueId(), name, false, player, onSuccess, onFailure);
    }

    public void expandBorderAdmin(CommandSender sender, UUID ownerUuid, String name, Runnable onSuccess, Runnable onFailure) {
        expandBorderInternal(sender, ownerUuid, name, true, null, onSuccess, onFailure);
    }

    private void expandBorderInternal(CommandSender messageTarget, UUID ownerUuid, String name, boolean skipEconomy, @org.jetbrains.annotations.Nullable Player economyPlayer, Runnable onSuccess, Runnable onFailure) {
        Optional<Homeland> optHomeland = getHomeland(ownerUuid, name);

        if (optHomeland.isEmpty()) {
            MessageUtil.send(messageTarget, plugin.getConfigManager().getMessage("homeland-not-found", "name", name));
            onFailure.run();
            return;
        }

        Homeland homeland = optHomeland.get();
        ConfigManager config = plugin.getConfigManager();

        final int oldRadius = homeland.getBorderRadius();
        int newRadius = oldRadius + config.getBorderExpandStep();
        if (newRadius > config.getMaxBorderRadius()) {
            MessageUtil.send(messageTarget, config.getMessage("border-max-reached"));
            onFailure.run();
            return;
        }

        // 先检查世界是否加载
        World overworld = getHomelandWorld(homeland.getWorldKey());
        if (overworld == null) {
            MessageUtil.send(messageTarget, config.getMessage("world-not-loaded"));
            onFailure.run();
            return;
        }

        // 检查经济（管理员跳过）
        final double moneyCost;
        final int pointsCost;
        if (!skipEconomy) {
            moneyCost = config.getExpansionMoney();
            pointsCost = config.getExpansionPoints();
            if (economyPlayer == null || !checkAndWithdrawEconomy(economyPlayer, moneyCost, pointsCost)) {
                onFailure.run();
                return;
            }
        } else {
            moneyCost = 0;
            pointsCost = 0;
        }

        // 更新 WorldBorder（在全局区域调度上执行）
        final int finalNewRadius = newRadius;
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            overworld.getWorldBorder().setSize(finalNewRadius * 2);

            // 同步更新地狱/末地边界
            if (homeland.hasNether()) {
                World nether = getHomelandWorld(homeland.getWorldKey() + "_nether");
                if (nether != null) {
                    nether.getWorldBorder().setSize(finalNewRadius * 2);
                }
            }
            if (homeland.hasEnd()) {
                World end = getHomelandWorld(homeland.getWorldKey() + "_the_end");
                if (end != null) {
                    end.getWorldBorder().setSize(finalNewRadius * 2);
                }
            }
        });

        // 更新数据库
        homeland.setBorderRadius(newRadius);
        plugin.getDatabaseQueue().submit("expand-border", conn -> {
            try (PreparedStatement stmt = conn.prepareStatement("UPDATE homeland SET border_radius = ? WHERE id = ?")) {
                stmt.setInt(1, newRadius);
                stmt.setInt(2, homeland.getId());
                stmt.executeUpdate();
            }
            return null;
        }, result -> {
            sendAsyncMessage(messageTarget, config.getMessage("border-expanded", "name", homeland.getName(), "border", String.valueOf(newRadius)));
            onSuccess.run();
        }, error -> {
            plugin.getLogger().warning("更新边界数据库记录失败: " + error.getMessage());
            // 回滚内存模型
            homeland.setBorderRadius(oldRadius);
            // 回滚 WorldBorder
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                overworld.getWorldBorder().setSize(oldRadius * 2);
                if (homeland.hasNether()) {
                    World nether = getHomelandWorld(homeland.getWorldKey() + "_nether");
                    if (nether != null) {
                        nether.getWorldBorder().setSize(oldRadius * 2);
                    }
                }
                if (homeland.hasEnd()) {
                    World end = getHomelandWorld(homeland.getWorldKey() + "_the_end");
                    if (end != null) {
                        end.getWorldBorder().setSize(oldRadius * 2);
                    }
                }
            });
            // 退费
            if (economyPlayer != null) {
                refundEconomy(economyPlayer, moneyCost, pointsCost);
            }
            sendAsyncMessage(messageTarget, config.getMessage("border-expand-failed"));
            onFailure.run();
        });
    }

    // ==================== 解锁地狱 ====================

    public void unlockNether(Player player, String name, Runnable onSuccess, Runnable onFailure) {
        unlockNetherInternal(player, player.getUniqueId(), name, false, player, onSuccess, onFailure);
    }

    public void unlockNetherAdmin(CommandSender sender, UUID ownerUuid, String name, Runnable onSuccess, Runnable onFailure) {
        unlockNetherInternal(sender, ownerUuid, name, true, null, onSuccess, onFailure);
    }

    private void unlockNetherInternal(CommandSender messageTarget, UUID ownerUuid, String name, boolean skipEconomy, @org.jetbrains.annotations.Nullable Player economyPlayer, Runnable onSuccess, Runnable onFailure) {
        Optional<Homeland> optHomeland = getHomeland(ownerUuid, name);

        if (optHomeland.isEmpty()) {
            MessageUtil.send(messageTarget, plugin.getConfigManager().getMessage("homeland-not-found", "name", name));
            onFailure.run();
            return;
        }

        Homeland homeland = optHomeland.get();
        if (homeland.hasNether()) {
            MessageUtil.send(messageTarget, plugin.getConfigManager().getMessage("already-unlocked"));
            onFailure.run();
            return;
        }

        ConfigManager config = plugin.getConfigManager();
        final double moneyCost;
        final int pointsCost;
        if (!skipEconomy) {
            moneyCost = config.getNetherUnlockMoney();
            pointsCost = config.getNetherUnlockPoints();
            if (economyPlayer == null || !checkAndWithdrawEconomy(economyPlayer, moneyCost, pointsCost)) {
                onFailure.run();
                return;
            }
        } else {
            moneyCost = 0;
            pointsCost = 0;
        }

        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            try {
                WorldsProvider provider = plugin.getWorldsProvider();
                World overworld = getHomelandWorld(homeland.getWorldKey());
                if (overworld == null) {
                    sendAsyncMessage(messageTarget, config.getMessage("world-not-loaded"));
                    if (economyPlayer != null) refundEconomy(economyPlayer, moneyCost, pointsCost);
                    onFailure.run();
                    return;
                }

                String netherKey = homeland.getWorldKey() + "_nether";
                Level netherLevel = provider.levelBuilder(java.nio.file.Path.of("homelands/" + netherKey))
                        .key(Key.key("simplehomeland", netherKey))
                        .name(homeland.getName() + " - 地狱")
                        .levelStem(net.thenextlvl.worlds.api.generator.LevelStem.NETHER)
                        .build();

                netherLevel.createAsync().thenAccept(nether -> {
                    try {
                        // 关联传送门
                        provider.linkProvider().link(overworld, nether);

                        // 设置自动加载和边界
                        provider.levelView().setEnabled(nether, true);
                        nether.setAutoSave(true);
                        int netherBorder = homeland.getBorderRadius() * 2;
                        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                            nether.getWorldBorder().setCenter(0, 0);
                            nether.getWorldBorder().setSize(netherBorder);
                        });

                        // 从主世界复制游戏规则
                        copyGamerules(overworld, nether);

                        // 更新数据库
                        homeland.setHasNether(true);
                        plugin.getDatabaseQueue().submit("unlock-nether", conn -> {
                            try (PreparedStatement stmt = conn.prepareStatement("UPDATE homeland SET has_nether = TRUE WHERE id = ?")) {
                                stmt.setInt(1, homeland.getId());
                                stmt.executeUpdate();
                            }
                            return null;
                        }, result -> {
                            // 更新 worldKeyIndex
                            worldKeyIndex.put(homeland.getWorldKey() + "_nether", homeland);
                            sendAsyncMessage(messageTarget, config.getMessage("nether-unlocked", "name", homeland.getName()));
                            onSuccess.run();
                        }, error -> {
                            plugin.getLogger().warning("更新地狱解锁数据库记录失败: " + error.getMessage());
                            homeland.setHasNether(false);
                            // 清理已创建的地狱世界
                            try {
                                provider.levelView().deleteAsync(nether, true);
                            } catch (Exception ex) {
                                plugin.getLogger().warning("清理失败的地狱世界失败: " + ex.getMessage());
                            }
                            if (economyPlayer != null) refundEconomy(economyPlayer, moneyCost, pointsCost);
                            sendAsyncMessage(messageTarget, config.getMessage("unlock-nether-failed"));
                            onFailure.run();
                        });
                    } catch (Exception e) {
                        plugin.getLogger().log(java.util.logging.Level.SEVERE, "解锁地狱后续处理失败: " + e.getMessage(), e);
                        // 清理已创建的地狱世界
                        try {
                            provider.levelView().deleteAsync(nether, true);
                        } catch (Exception ex) {
                            plugin.getLogger().warning("清理失败的地狱世界失败: " + ex.getMessage());
                        }
                        if (economyPlayer != null) refundEconomy(economyPlayer, moneyCost, pointsCost);
                        sendAsyncMessage(messageTarget, config.getMessage("unlock-nether-failed"));
                        onFailure.run();
                    }
                }).exceptionally(throwable -> {
                    plugin.getLogger().log(java.util.logging.Level.SEVERE, "创建地狱世界失败: " + throwable.getMessage(), throwable);
                    if (economyPlayer != null) refundEconomy(economyPlayer, moneyCost, pointsCost);
                    sendAsyncMessage(messageTarget, config.getMessage("unlock-nether-failed"));
                    onFailure.run();
                    return null;
                });

            } catch (Exception e) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "解锁地狱失败: " + e.getMessage(), e);
                if (economyPlayer != null) refundEconomy(economyPlayer, moneyCost, pointsCost);
                sendAsyncMessage(messageTarget, config.getMessage("unlock-nether-failed"));
                onFailure.run();
            }
        });
    }

    // ==================== 解锁末地 ====================

    public void unlockEnd(Player player, String name, Runnable onSuccess, Runnable onFailure) {
        unlockEndInternal(player, player.getUniqueId(), name, false, player, onSuccess, onFailure);
    }

    public void unlockEndAdmin(CommandSender sender, UUID ownerUuid, String name, Runnable onSuccess, Runnable onFailure) {
        unlockEndInternal(sender, ownerUuid, name, true, null, onSuccess, onFailure);
    }

    private void unlockEndInternal(CommandSender messageTarget, UUID ownerUuid, String name, boolean skipEconomy, @org.jetbrains.annotations.Nullable Player economyPlayer, Runnable onSuccess, Runnable onFailure) {
        Optional<Homeland> optHomeland = getHomeland(ownerUuid, name);

        if (optHomeland.isEmpty()) {
            MessageUtil.send(messageTarget, plugin.getConfigManager().getMessage("homeland-not-found", "name", name));
            onFailure.run();
            return;
        }

        Homeland homeland = optHomeland.get();
        if (homeland.hasEnd()) {
            MessageUtil.send(messageTarget, plugin.getConfigManager().getMessage("already-unlocked"));
            onFailure.run();
            return;
        }

        ConfigManager config = plugin.getConfigManager();
        final double moneyCost;
        final int pointsCost;
        if (!skipEconomy) {
            moneyCost = config.getEndUnlockMoney();
            pointsCost = config.getEndUnlockPoints();
            if (economyPlayer == null || !checkAndWithdrawEconomy(economyPlayer, moneyCost, pointsCost)) {
                onFailure.run();
                return;
            }
        } else {
            moneyCost = 0;
            pointsCost = 0;
        }

        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            try {
                WorldsProvider provider = plugin.getWorldsProvider();
                World overworld = getHomelandWorld(homeland.getWorldKey());
                if (overworld == null) {
                    sendAsyncMessage(messageTarget, config.getMessage("world-not-loaded"));
                    if (economyPlayer != null) refundEconomy(economyPlayer, moneyCost, pointsCost);
                    onFailure.run();
                    return;
                }

                String endKey = homeland.getWorldKey() + "_the_end";
                Level endLevel = provider.levelBuilder(java.nio.file.Path.of("homelands/" + endKey))
                        .key(Key.key("simplehomeland", endKey))
                        .name(homeland.getName() + " - 末地")
                        .levelStem(net.thenextlvl.worlds.api.generator.LevelStem.END)
                        .build();

                endLevel.createAsync().thenAccept(end -> {
                    try {
                        // 关联传送门
                        provider.linkProvider().link(overworld, end);

                        // 设置自动加载和边界
                        provider.levelView().setEnabled(end, true);
                        end.setAutoSave(true);
                        int endBorder = homeland.getBorderRadius() * 2;
                        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                            end.getWorldBorder().setCenter(0, 0);
                            end.getWorldBorder().setSize(endBorder);
                        });

                        // 从主世界复制游戏规则
                        copyGamerules(overworld, end);

                        // 更新数据库
                        homeland.setHasEnd(true);
                        plugin.getDatabaseQueue().submit("unlock-end", conn -> {
                            try (PreparedStatement stmt = conn.prepareStatement("UPDATE homeland SET has_end = TRUE WHERE id = ?")) {
                                stmt.setInt(1, homeland.getId());
                                stmt.executeUpdate();
                            }
                            return null;
                        }, result -> {
                            // 更新 worldKeyIndex
                            worldKeyIndex.put(homeland.getWorldKey() + "_the_end", homeland);
                            sendAsyncMessage(messageTarget, config.getMessage("end-unlocked", "name", homeland.getName()));
                            onSuccess.run();
                        }, error -> {
                            plugin.getLogger().warning("更新末地解锁数据库记录失败: " + error.getMessage());
                            homeland.setHasEnd(false);
                            // 清理已创建的末地世界
                            try {
                                provider.levelView().deleteAsync(end, true);
                            } catch (Exception ex) {
                                plugin.getLogger().warning("清理失败的末地世界失败: " + ex.getMessage());
                            }
                            if (economyPlayer != null) refundEconomy(economyPlayer, moneyCost, pointsCost);
                            sendAsyncMessage(messageTarget, config.getMessage("unlock-end-failed"));
                            onFailure.run();
                        });
                    } catch (Exception e) {
                        plugin.getLogger().log(java.util.logging.Level.SEVERE, "解锁末地后续处理失败: " + e.getMessage(), e);
                        // 清理已创建的末地世界
                        try {
                            provider.levelView().deleteAsync(end, true);
                        } catch (Exception ex) {
                            plugin.getLogger().warning("清理失败的末地世界失败: " + ex.getMessage());
                        }
                        if (economyPlayer != null) refundEconomy(economyPlayer, moneyCost, pointsCost);
                        sendAsyncMessage(messageTarget, config.getMessage("unlock-end-failed"));
                        onFailure.run();
                    }
                }).exceptionally(throwable -> {
                    plugin.getLogger().log(java.util.logging.Level.SEVERE, "创建末地世界失败: " + throwable.getMessage(), throwable);
                    if (economyPlayer != null) refundEconomy(economyPlayer, moneyCost, pointsCost);
                    sendAsyncMessage(messageTarget, config.getMessage("unlock-end-failed"));
                    onFailure.run();
                    return null;
                });

            } catch (Exception e) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "解锁末地失败: " + e.getMessage(), e);
                if (economyPlayer != null) refundEconomy(economyPlayer, moneyCost, pointsCost);
                sendAsyncMessage(messageTarget, config.getMessage("unlock-end-failed"));
                onFailure.run();
            }
        });
    }

    // ==================== 删除维度 ====================

    public void lockNether(Player player, String name, Runnable onSuccess, Runnable onFailure) {
        lockDimensionInternal(player, player.getUniqueId(), name, "nether", onSuccess, onFailure);
    }

    public void lockNetherAdmin(CommandSender sender, UUID ownerUuid, String name, Runnable onSuccess, Runnable onFailure) {
        lockDimensionInternal(sender, ownerUuid, name, "nether", onSuccess, onFailure);
    }

    public void lockEnd(Player player, String name, Runnable onSuccess, Runnable onFailure) {
        lockDimensionInternal(player, player.getUniqueId(), name, "end", onSuccess, onFailure);
    }

    public void lockEndAdmin(CommandSender sender, UUID ownerUuid, String name, Runnable onSuccess, Runnable onFailure) {
        lockDimensionInternal(sender, ownerUuid, name, "end", onSuccess, onFailure);
    }

    private void lockDimensionInternal(CommandSender messageTarget, UUID ownerUuid, String name,
                                       String dimension, Runnable onSuccess, Runnable onFailure) {
        Optional<Homeland> optHomeland = getHomeland(ownerUuid, name);
        if (optHomeland.isEmpty()) {
            MessageUtil.send(messageTarget, plugin.getConfigManager().getMessage("homeland-not-found", "name", name));
            onFailure.run();
            return;
        }

        Homeland homeland = optHomeland.get();
        boolean isNether = "nether".equals(dimension);

        if (isNether && !homeland.hasNether()) {
            MessageUtil.send(messageTarget, plugin.getConfigManager().getMessage("dimension-not-unlocked"));
            onFailure.run();
            return;
        }
        if (!isNether && !homeland.hasEnd()) {
            MessageUtil.send(messageTarget, plugin.getConfigManager().getMessage("dimension-not-unlocked"));
            onFailure.run();
            return;
        }

        ConfigManager config = plugin.getConfigManager();
        String suffix = isNether ? "_nether" : "_the_end";
        String dimWorldKey = homeland.getWorldKey() + suffix;

        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            try {
                WorldsProvider provider = plugin.getWorldsProvider();
                if (provider == null) {
                    sendAsyncMessage(messageTarget, config.getMessage("unlock-nether-failed"));
                    onFailure.run();
                    return;
                }

                World overworld = getHomelandWorld(homeland.getWorldKey());
                World dimWorld = getHomelandWorld(dimWorldKey);

                if (dimWorld != null) {
                    // 传送世界中所有玩家到主世界
                    org.bukkit.World mainWorld = Bukkit.getWorlds().get(0);
                    for (var entity : dimWorld.getEntities()) {
                        if (entity instanceof org.bukkit.entity.Player p) {
                            p.teleportAsync(mainWorld.getSpawnLocation());
                        }
                    }

                    // 解绑传送门
                    if (overworld != null) {
                        provider.linkProvider().unlink(overworld, dimWorld);
                    }

                    // 删除维度世界
                    provider.levelView().deleteAsync(dimWorld, false);
                }

                // 更新数据库
                String column = isNether ? "has_nether" : "has_end";
                plugin.getDatabaseQueue().submit("lock-dimension", conn -> {
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "UPDATE homeland SET " + column + " = FALSE WHERE id = ?")) {
                        stmt.setInt(1, homeland.getId());
                        stmt.executeUpdate();
                    }
                    return null;
                }, result -> {
                    // 更新内存状态
                    if (isNether) {
                        homeland.setHasNether(false);
                    } else {
                        homeland.setHasEnd(false);
                    }
                    worldKeyIndex.remove(dimWorldKey);

                    String msgKey = isNether ? "nether-locked" : "end-locked";
                    sendAsyncMessage(messageTarget, config.getMessage(msgKey, "name", homeland.getName()));
                    onSuccess.run();
                }, error -> {
                    plugin.getLogger().warning("删除维度数据库更新失败: " + error.getMessage());
                    String failKey = isNether ? "lock-nether-failed" : "lock-end-failed";
                    sendAsyncMessage(messageTarget, config.getMessage(failKey));
                    onFailure.run();
                });
            } catch (Exception e) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "删除维度失败: " + e.getMessage(), e);
                String failKey = isNether ? "lock-nether-failed" : "lock-end-failed";
                sendAsyncMessage(messageTarget, config.getMessage(failKey));
                onFailure.run();
            }
        });
    }

    // ==================== 经济检查与扣费 ====================

    private boolean checkAndWithdrawEconomy(Player player, double money, int points) {
        ConfigManager config = plugin.getConfigManager();

        // 如果需要金币但经济插件未安装，拒绝操作
        if (money > 0 && !plugin.getEconomyManager().isEnabled()) {
            MessageUtil.send(player, config.getMessage("economy-plugin-missing"));
            return false;
        }

        // 如果需要点券但点券插件未安装，拒绝操作
        if (points > 0 && !plugin.getPlayerPointsManager().isEnabled()) {
            MessageUtil.send(player, config.getMessage("points-plugin-missing"));
            return false;
        }

        // 直接尝试扣除金币（避免 TOCTOU 竞态）
        if (money > 0 && plugin.getEconomyManager().isEnabled()) {
            if (!plugin.getEconomyManager().withdraw(player.getUniqueId(), player.getName(), money)) {
                MessageUtil.send(player, config.getMessage("not-enough-money", "money", String.format("%.2f", money)));
                return false;
            }
        }

        // 直接尝试扣除点券
        if (points > 0 && plugin.getPlayerPointsManager().isEnabled()) {
            if (!plugin.getPlayerPointsManager().withdraw(player.getUniqueId(), points)) {
                // 回滚金币（如果金币已扣除）
                if (money > 0 && plugin.getEconomyManager().isEnabled()) {
                    plugin.getEconomyManager().deposit(player.getUniqueId(), player.getName(), money);
                }
                MessageUtil.send(player, config.getMessage("not-enough-points", "points", String.valueOf(points)));
                return false;
            }
        }

        return true;
    }

    private void refundEconomy(Player economyPlayer, double money, int points) {
        if (economyPlayer == null) return;
        if (money > 0 && plugin.getEconomyManager().isEnabled()) {
            boolean success = plugin.getEconomyManager().deposit(economyPlayer.getUniqueId(), economyPlayer.getName(), money);
            if (!success) {
                plugin.getLogger().warning("退还金币失败! 玩家: " + economyPlayer.getName() + ", 金额: " + money);
            }
        }
        if (points > 0 && plugin.getPlayerPointsManager().isEnabled()) {
            boolean success = plugin.getPlayerPointsManager().deposit(economyPlayer.getUniqueId(), points);
            if (!success) {
                plugin.getLogger().warning("退还点券失败! 玩家: " + economyPlayer.getName() + ", 数量: " + points);
            }
        }
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
        plugin.getDatabaseQueue().submit("load-world-key-index", conn -> {
            java.util.Map<String, Homeland> index = new java.util.HashMap<>();
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT id, owner_uuid, name, world_key, world_uuid, border_radius, has_nether, has_end, is_public, visitor_flags FROM homeland");
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String worldUuidStr = rs.getString("world_uuid");
                    UUID worldUuid = (worldUuidStr != null && !worldUuidStr.isEmpty()) ? UUID.fromString(worldUuidStr) : null;
                    Homeland h = new Homeland(
                            rs.getInt("id"),
                            UUID.fromString(rs.getString("owner_uuid")),
                            rs.getString("name"),
                            rs.getString("world_key"),
                            worldUuid,
                            rs.getInt("border_radius"),
                            rs.getBoolean("has_nether"),
                            rs.getBoolean("has_end"),
                            rs.getBoolean("is_public"),
                            VisitorFlags.fromJson(rs.getString("visitor_flags"))
                    );
                    index.put(h.getWorldKey(), h);
                    if (h.hasNether()) index.put(h.getWorldKey() + "_nether", h);
                    if (h.hasEnd()) index.put(h.getWorldKey() + "_the_end", h);
                }
            }
            return index;
        }, index -> {
            worldKeyIndex.putAll(index);
            plugin.getLogger().info("已加载 " + index.size() + " 个家园世界索引");
            // 恢复已加载世界的 WorldBorder
            restoreWorldBorders();
            // 预加载所有家园世界（如果配置启用）
            if (plugin.getConfigManager().isPreloadWorlds()) {
                preloadAllWorlds();
            }
        }, error -> {
            plugin.getLogger().warning("加载家园世界索引失败: " + error.getMessage());
        });
    }

    /**
     * 恢复所有已加载家园世界的 WorldBorder 设置。
     * 用于服务器启动时，因为 WorldLoadEvent 可能已在插件加载前触发。
     */
    public void restoreWorldBorders() {
        for (var entry : worldKeyIndex.entrySet()) {
            String key = entry.getKey();
            Homeland h = entry.getValue();
            World world = getHomelandWorld(key);
            if (world != null) {
                int radius = h.getBorderRadius();
                Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                    world.getWorldBorder().setCenter(0, 0);
                    world.getWorldBorder().setSize(radius * 2);
                });
                // 懒迁移：已加载但缺少 world_uuid 的家园，填充并写入数据库
                if (h.getWorldUuid() == null) {
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
        WorldsProvider provider = plugin.getWorldsProvider();
        if (provider == null) {
            plugin.getLogger().warning("预加载失败: Worlds 插件未提供 API");
            return;
        }

        // 收集需要加载的世界（去重，只取主世界 key）
        java.util.Set<String> mainKeys = new java.util.LinkedHashSet<>();
        for (var entry : worldKeyIndex.entrySet()) {
            Homeland h = entry.getValue();
            if (entry.getKey().equals(h.getWorldKey())) {
                mainKeys.add(h.getWorldKey());
            }
        }

        if (mainKeys.isEmpty()) return;

        plugin.getLogger().info("开始预加载 " + mainKeys.size() + " 个家园世界...");

        java.util.concurrent.atomic.AtomicInteger loaded = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger skipped = new java.util.concurrent.atomic.AtomicInteger(0);
        int total = mainKeys.size();

        for (String worldKey : mainKeys) {
            // 跳过已加载的世界
            if (getHomelandWorld(worldKey) != null) {
                skipped.incrementAndGet();
                continue;
            }

            java.nio.file.Path worldPath = provider.levelView().getWorldContainer().resolve("homelands/" + worldKey);
            var builderOpt = provider.levelView().read(worldPath);
            if (builderOpt.isEmpty()) {
                skipped.incrementAndGet();
                continue;
            }

            Level level = builderOpt.get().build();
            level.createAsync().thenAccept(world -> {
                world.setAutoSave(true);
                provider.levelView().setEnabled(world, true);
                touchLastPlayerTime(worldKey);

                Homeland h = worldKeyIndex.get(worldKey);
                if (h != null) {
                    int radius = h.getBorderRadius();
                    Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                        world.getWorldBorder().setCenter(0, 0);
                        world.getWorldBorder().setSize(radius * 2);
                    });
                }

                int done = loaded.incrementAndGet();
                if (done + skipped.get() >= total) {
                    plugin.getLogger().info("家园世界预加载完成: 加载 " + done + " 个, 跳过 " + skipped.get() + " 个");
                }
            }).exceptionally(throwable -> {
                plugin.getLogger().warning("预加载家园世界失败: " + worldKey + " - " + throwable.getMessage());
                skipped.incrementAndGet();
                return null;
            });
        }
    }

    /**
     * 通过 NamespacedKey 查找已加载的世界。
     * 世界创建时使用 Key.key("simplehomeland", worldKey)，因此必须通过 NamespacedKey 查找，
     * 而不能用 Bukkit.getWorld(worldKey) 按名称查找（名称是家园名称而非 worldKey）。
     */
    public World getHomelandWorld(String worldKey) {
        return Bukkit.getWorld(new NamespacedKey("simplehomeland", worldKey));
    }

    private void indexHomeland(Homeland h) {
        worldKeyIndex.put(h.getWorldKey(), h);
        if (h.hasNether()) worldKeyIndex.put(h.getWorldKey() + "_nether", h);
        if (h.hasEnd()) worldKeyIndex.put(h.getWorldKey() + "_the_end", h);
        if (h.getWorldUuid() != null) worldUuidIndex.put(h.getWorldUuid(), h);
    }

    private void unindexHomeland(Homeland h) {
        worldKeyIndex.remove(h.getWorldKey());
        worldKeyIndex.remove(h.getWorldKey() + "_nether");
        worldKeyIndex.remove(h.getWorldKey() + "_the_end");
        if (h.getWorldUuid() != null) worldUuidIndex.remove(h.getWorldUuid());
    }

    public Homeland getHomelandByWorldKey(String worldKey) {
        return worldKeyIndex.get(worldKey);
    }

    public Homeland getHomelandByWorldUUID(UUID worldUuid) {
        return worldUuidIndex.get(worldUuid);
    }

    /**
     * 懒迁移：将已加载世界的 UUID 写入数据库和内存索引。
     */
    private void updateWorldUuid(Homeland homeland, UUID worldUuid) {
        homeland.setWorldUuid(worldUuid);
        worldUuidIndex.put(worldUuid, homeland);
        plugin.getDatabaseQueue().submit("update-world-uuid", conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE homeland SET world_uuid = ? WHERE id = ?")) {
                stmt.setString(1, worldUuid.toString());
                stmt.setInt(2, homeland.getId());
                stmt.executeUpdate();
            }
            return null;
        }, result -> {}, error -> {
            plugin.getLogger().warning("更新 world_uuid 失败: " + error.getMessage());
        });
    }

    // ==================== 游戏规则 ====================

    @SuppressWarnings("deprecation")
    public void applyDefaultGamerules(World world) {
        for (GameRuleConfig grc : plugin.getConfigManager().getEnabledGameruleConfigs()) {
            applyGamerule(world, grc, grc.getDefaultValue());
        }
    }

    @SuppressWarnings("unchecked")
    private void applyGamerule(World world, GameRuleConfig grc, String value) {
        if (grc.isBooleanType()) {
            world.setGameRule((GameRule<Boolean>) grc.getGameRule(),
                    Boolean.parseBoolean(value));
        } else {
            world.setGameRule((GameRule<Integer>) grc.getGameRule(),
                    Integer.parseInt(value));
        }
    }

    @SuppressWarnings("unchecked")
    public void copyGamerules(World source, World target) {
        for (GameRuleConfig grc : plugin.getConfigManager().getEnabledGameruleConfigs()) {
            String value = getGameruleValue(source, grc);
            applyGamerule(target, grc, value);
        }
    }

    @SuppressWarnings("unchecked")
    private String getGameruleValue(World world, GameRuleConfig grc) {
        if (grc.isBooleanType()) {
            return String.valueOf(world.getGameRuleValue((GameRule<Boolean>) grc.getGameRule()));
        } else {
            return String.valueOf(world.getGameRuleValue((GameRule<Integer>) grc.getGameRule()));
        }
    }

    @SuppressWarnings("deprecation")
    public void setGamerule(Player player, String homelandName, GameRuleConfig grc, String value,
                            Runnable onSuccess, Runnable onFailure) {
        Optional<Homeland> optHomeland = getHomeland(player.getUniqueId(), homelandName);
        if (optHomeland.isEmpty()) {
            MessageUtil.send(player, plugin.getConfigManager().getMessage("homeland-not-found", "name", homelandName));
            onFailure.run();
            return;
        }

        Homeland homeland = optHomeland.get();
        String worldKey = homeland.getWorldKey();

        World overworld = getHomelandWorld(worldKey);
        if (overworld == null) {
            MessageUtil.send(player, plugin.getConfigManager().getMessage("gamerule-world-not-loaded"));
            onFailure.run();
            return;
        }

        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            applyGamerule(overworld, grc, value);

            // 同步到已加载的子维度
            if (homeland.hasNether()) {
                World nether = getHomelandWorld(worldKey + "_nether");
                if (nether != null) {
                    applyGamerule(nether, grc, value);
                }
            }
            if (homeland.hasEnd()) {
                World end = getHomelandWorld(worldKey + "_the_end");
                if (end != null) {
                    applyGamerule(end, grc, value);
                }
            }

            onSuccess.run();
        });
    }

    public void setGameruleAdmin(Player player, UUID ownerUuid, String homelandName, GameRuleConfig grc, String value,
                                 Runnable onSuccess, Runnable onFailure) {
        Optional<Homeland> optHomeland = getHomeland(ownerUuid, homelandName);
        if (optHomeland.isEmpty()) {
            MessageUtil.send(player, plugin.getConfigManager().getMessage("homeland-not-found", "name", homelandName));
            onFailure.run();
            return;
        }

        Homeland homeland = optHomeland.get();
        String worldKey = homeland.getWorldKey();

        World overworld = getHomelandWorld(worldKey);
        if (overworld == null) {
            MessageUtil.send(player, plugin.getConfigManager().getMessage("gamerule-world-not-loaded"));
            onFailure.run();
            return;
        }

        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            applyGamerule(overworld, grc, value);

            if (homeland.hasNether()) {
                World nether = getHomelandWorld(worldKey + "_nether");
                if (nether != null) {
                    applyGamerule(nether, grc, value);
                }
            }
            if (homeland.hasEnd()) {
                World end = getHomelandWorld(worldKey + "_the_end");
                if (end != null) {
                    applyGamerule(end, grc, value);
                }
            }

            onSuccess.run();
        });
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
        org.bukkit.World world = player.getWorld();
        String worldKey = world.getKey().getKey();
        Homeland homeland = worldKeyIndex.get(worldKey);
        if (homeland == null) return; // 非家园世界

        if (!canEnterWorld(player, worldKey)) {
            org.bukkit.World mainWorld = plugin.getServer().getWorlds().get(0);
            player.getScheduler().execute(plugin, () -> {
                player.teleportAsync(mainWorld.getSpawnLocation());
                MessageUtil.send(player, plugin.getConfigManager().getMessage("homeland-eject"));
            }, () -> {}, 1L);
        }
    }

    /**
     * 检查玩家是否可以进入指定世界
     */
    public boolean canEnterWorld(Player player, String worldKey) {
        Homeland homeland = worldKeyIndex.get(worldKey);
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

        Homeland homeland = worldKeyIndex.get(worldKey);
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
        lastPlayerPresentTime.put(worldKey, System.currentTimeMillis());
    }

    private void checkAndUnloadIdleWorlds() {
        int autoUnloadSeconds = plugin.getConfigManager().getAutoUnloadSeconds();
        if (autoUnloadSeconds <= 0) return;
        if (plugin.getConfigManager().isPreloadWorlds()) return;

        long threshold = autoUnloadSeconds * 1000L;
        long now = System.currentTimeMillis();

        for (Map.Entry<String, Homeland> entry : worldKeyIndex.entrySet()) {
            String worldKey = entry.getKey();
            Homeland homeland = entry.getValue();

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
        WorldsProvider provider = plugin.getWorldsProvider();
        if (provider == null) return;

        lastPlayerPresentTime.remove(worldKey);

        for (World world : worlds) {
            // 确保没有玩家残留
            if (!world.getPlayers().isEmpty()) return;

            plugin.getLogger().info("正在卸载空闲家园世界: " + world.getName());
            provider.levelView().unloadAsync(world, true).thenAccept(success -> {
                if (success) {
                    plugin.getLogger().info("已自动卸载空闲家园世界: " + world.getName());
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
        homelandCache.clear();
        worldKeyIndex.clear();
        playerInvites.clear();
        lastPlayerPresentTime.clear();
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