package dev.user.homeland.manager;

import dev.user.homeland.SimpleHomelandPlugin;
import dev.user.homeland.config.ConfigManager;
import dev.user.homeland.config.ConfigManager.GameRuleConfig;
import dev.user.homeland.config.PriceTier;
import dev.user.homeland.model.CustomSettings;
import dev.user.homeland.model.Homeland;
import dev.user.homeland.model.HomelandDimension;
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

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

final class HomelandWorldOperations {

    static final class ExpectedFailure extends RuntimeException {
        private ExpectedFailure() {
        }

        private ExpectedFailure(Throwable cause) {
            super(cause);
        }
    }

    private static final class DeleteDatabaseFailure extends RuntimeException {
        private DeleteDatabaseFailure(Throwable cause) {
            super(cause);
        }
    }

    private final SimpleHomelandPlugin plugin;
    private final HomelandStore store;
    private final HomelandEconomy economy;
    private final Consumer<Homeland> removeHomeland;
    private final Consumer<String> touchWorld;
    private final Set<String> activeCleanups = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingCleanups = ConcurrentHashMap.newKeySet();
    private volatile boolean cleanupIndexLoaded;

    HomelandWorldOperations(SimpleHomelandPlugin plugin, HomelandStore store,
                            HomelandEconomy economy, Consumer<Homeland> removeHomeland,
                            Consumer<String> touchWorld) {
        this.plugin = plugin;
        this.store = store;
        this.economy = economy;
        this.removeHomeland = removeHomeland;
        this.touchWorld = touchWorld;
    }

    CompletionStage<Void> deleteHomeland(CommandSender target, Homeland homeland,
                                         String databaseTaskName) {
        CompletionStage<Void> workflow = onGlobalRegionThread(() -> buildDeletionPlan(homeland))
                .thenCompose(plan -> evacuatePlayers(plan.loadedWorlds()).thenApply(ignored -> plan))
                .thenCompose(plan -> plugin.getDatabaseQueue()
                        .submitFuture(databaseTaskName, conn -> {
                            persistCleanupAndDeleteHomeland(conn, homeland);
                            return plan;
                        })
                        .exceptionallyCompose(error -> CompletableFuture.failedFuture(
                                new DeleteDatabaseFailure(error))))
                .thenCompose(plan -> {
                    pendingCleanups.add(homeland.getWorldKey());
                    removeHomeland.accept(homeland);
                    store.remove(homeland);
                    return executeCleanup(
                            new WorldCleanupTask(homeland.getWorldKey()), plan);
                })
                .thenRun(() -> sendMessage(target, plugin.getConfigManager().getMessage(
                        "homeland-deleted", "name", homeland.getName())));

        return workflow.handle((ignored, error) -> {
            if (error == null) return null;
            Throwable cause = unwrap(error);
            String messageKey = cause instanceof DeleteDatabaseFailure
                    ? "delete-failed-db" : "delete-failed-world";
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "删除家园失败: " + cause.getMessage(), cause);
            sendMessage(target, plugin.getConfigManager().getMessage(messageKey));
            throw new CompletionException(new ExpectedFailure(cause));
        });
    }

    static void persistCleanupAndDeleteHomeland(
            java.sql.Connection connection, Homeland homeland) throws SQLException {
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement cleanup = connection.prepareStatement(
                    "INSERT INTO homeland_world_cleanup " +
                            "(world_key) VALUES (?)")) {
                cleanup.setString(1, homeland.getWorldKey());
                cleanup.executeUpdate();
            }
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM homeland WHERE id = ?")) {
                delete.setInt(1, homeland.getId());
                if (delete.executeUpdate() != 1) {
                    throw new SQLException("Homeland row not found: " + homeland.getId());
                }
            }
            connection.commit();
        } catch (SQLException error) {
            connection.rollback();
            throw error;
        }
    }

    void recoverPendingCleanups() {
        plugin.getDatabaseQueue().submitFuture("load-world-cleanups", connection -> {
            List<WorldCleanupTask> tasks = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT world_key FROM homeland_world_cleanup");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    tasks.add(new WorldCleanupTask(result.getString("world_key")));
                }
            }
            return tasks;
        }).thenAccept(tasks -> {
                    tasks.forEach(task -> pendingCleanups.add(task.worldKey()));
                    cleanupIndexLoaded = true;
                    tasks.forEach(this::retryCleanup);
                })
                .exceptionally(error -> {
                    plugin.getLogger().log(java.util.logging.Level.SEVERE,
                            "加载待清理家园世界失败", unwrap(error));
                    return null;
                });
    }

    private void retryCleanup(WorldCleanupTask task) {
        if (!activeCleanups.add(task.worldKey())) return;
        onGlobalRegionThread(() -> buildDeletionPlan(task))
                .thenCompose(this::deleteHomelandWorlds)
                .thenCompose(ignored -> removeCleanupRecord(task.worldKey()))
                .whenComplete((ignored, error) -> {
                    activeCleanups.remove(task.worldKey());
                    if (error == null) {
                        plugin.getLogger().info("已完成家园世界补偿清理: " + task.worldKey());
                    } else {
                        plugin.getLogger().log(java.util.logging.Level.SEVERE,
                                "家园世界补偿清理失败，将在下次启动继续: " + task.worldKey(),
                                unwrap(error));
                    }
                });
    }

    private CompletionStage<Void> executeCleanup(
            WorldCleanupTask task, HomelandDeletionPlan plan) {
        if (!activeCleanups.add(task.worldKey())) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "World cleanup already active: " + task.worldKey()));
        }
        return deleteHomelandWorlds(plan)
                .thenCompose(ignored -> removeCleanupRecord(task.worldKey()))
                .whenComplete((ignored, error) -> activeCleanups.remove(task.worldKey()));
    }

    private CompletionStage<Void> removeCleanupRecord(String worldKey) {
        return retryDatabase(() -> plugin.getDatabaseQueue().submitFuture(
                "finish-world-cleanup", connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "DELETE FROM homeland_world_cleanup WHERE world_key = ?")) {
                        statement.setString(1, worldKey);
                        statement.executeUpdate();
                    }
                    return null;
                }), 3).thenRun(() -> pendingCleanups.remove(worldKey));
    }

    boolean isCleanupPending(String worldKey) {
        if (pendingCleanups.contains(worldKey)) return true;
        if (worldKey.endsWith(HomelandDimension.NETHER.worldKeySuffix())) {
            return pendingCleanups.contains(worldKey.substring(
                    0, worldKey.length() - HomelandDimension.NETHER.worldKeySuffix().length()));
        }
        if (worldKey.endsWith(HomelandDimension.END.worldKeySuffix())) {
            return pendingCleanups.contains(worldKey.substring(
                    0, worldKey.length() - HomelandDimension.END.worldKeySuffix().length()));
        }
        return false;
    }

    boolean isCleanupIndexLoaded() {
        return cleanupIndexLoaded;
    }

    CompletionStage<Void> unlockDimension(CommandSender target, Homeland homeland,
                                          HomelandDimension dimension, Player economyPlayer) {
        ConfigManager config = plugin.getConfigManager();
        if (dimension.isUnlocked(homeland)) {
            return reject(target, config.getMessage("already-unlocked"));
        }

        ChargedCost cost = economyPlayer == null ? ChargedCost.none() : charge(
                economyPlayer,
                dimension == HomelandDimension.NETHER
                        ? config.getNetherUnlockMoney() : config.getEndUnlockMoney(),
                dimension == HomelandDimension.NETHER
                        ? config.getNetherUnlockPoints() : config.getEndUnlockPoints());
        if (cost == null) return CompletableFuture.failedFuture(new ExpectedFailure());

        CompletionStage<Void> workflow = createUnlockedDimension(homeland, dimension, target)
                .thenCompose(context -> updateDimensionState(
                                homeland, dimension, true,
                                "unlock-" + dimension.name().toLowerCase(java.util.Locale.ROOT))
                        .exceptionallyCompose(error -> failAfterCleanup(
                                cleanupCreatedDimension(context), error,
                                "清理解锁失败的维度世界时发生异常"))
                        .thenRun(() -> {
                            dimension.setUnlocked(homeland, true);
                            store.refreshIndexes(homeland);
                        }))
                .thenRun(() -> sendMessage(target, config.getMessage(
                        dimension == HomelandDimension.NETHER ? "nether-unlocked" : "end-unlocked",
                        "name", homeland.getName())));

        return reportFailure(workflow, target, cost,
                config.getMessage(dimension == HomelandDimension.NETHER
                        ? "unlock-nether-failed" : "unlock-end-failed"),
                "解锁" + dimension.displayName() + "失败");
    }

    CompletionStage<Void> deleteDimension(CommandSender target, Homeland homeland,
                                          HomelandDimension dimension) {
        ConfigManager config = plugin.getConfigManager();
        if (!dimension.isUnlocked(homeland)) {
            return reject(target, config.getMessage("dimension-not-unlocked"));
        }

        CompletionStage<Void> workflow = loadDimensionForDeletion(homeland, dimension)
                .thenCompose(context -> evacuatePlayers(context.world() == null
                                ? List.of() : List.of(context.world()))
                        .thenApply(ignored -> context))
                .thenCompose(context -> updateDimensionState(
                                homeland, dimension, false, "delete-dimension")
                        .exceptionallyCompose(error -> failAfterCleanup(
                                restoreDimensionWorld(context), error,
                                "恢复维度世界状态时发生异常"))
                        .thenCompose(ignored -> deleteDimensionWorld(context)
                                .exceptionallyCompose(error -> compensateDimensionDeletion(
                                        homeland, dimension, context, error))))
                .thenRun(() -> {
                    dimension.setUnlocked(homeland, false);
                    store.refreshIndexes(homeland);
                    sendMessage(target, config.getMessage(
                            dimension == HomelandDimension.NETHER ? "nether-locked" : "end-locked",
                            "name", homeland.getName()));
                });

        return reportFailure(workflow, target, ChargedCost.none(),
                config.getMessage(dimension == HomelandDimension.NETHER
                        ? "lock-nether-failed" : "lock-end-failed"),
                "删除" + dimension.displayName() + "失败");
    }

    CompletionStage<Void> resetOverworld(CommandSender target, Homeland homeland,
                                         String worldTypeKey, Player economyPlayer) {
        ConfigManager config = plugin.getConfigManager();
        ChargedCost cost = economyPlayer == null ? ChargedCost.none() : charge(
                economyPlayer, config.getResetOverworldMoney(), config.getResetOverworldPoints());
        if (cost == null) return CompletableFuture.failedFuture(new ExpectedFailure());

        CompletionStage<java.util.UUID> prepare = loadOverworldForRecovery(homeland)
                .thenCompose(currentWorld -> {
                    java.util.UUID previousWorldUuid = currentWorld == null
                            ? null : currentWorld.getUID();
                    return plugin.getDatabaseQueue().submitFuture(
                            "prepare-reset-overworld", conn -> {
                                prepareOverworldResetRecord(conn, homeland.getId(),
                                        worldTypeKey, previousWorldUuid);
                                return previousWorldUuid;
                            });
                });

        return reportFailure(prepare, target, cost,
                config.getMessage("reset-failed"), "准备主世界重置失败")
                .thenCompose(previousWorldUuid -> {
                    homeland.setWorldType(worldTypeKey);
                    homeland.setOverworldResetPending(true);
                    homeland.setOverworldResetPreviousUuid(previousWorldUuid);
                    store.updateWorldUuid(homeland, null);
                    // prepare 已持久化后，重置已经进入提交状态。后续失败只保留
                    // pending 供恢复任务继续执行，不自动退款，避免恢复成功时免费重置。
                    return reportFailure(performOverworldReset(
                                    target, homeland, worldTypeKey), target, ChargedCost.none(),
                            config.getMessage("reset-pending-recovery"), "重置主世界失败");
                });
    }

    CompletionStage<Void> resumeOverworldReset(Homeland homeland) {
        // prepare clears world_uuid; only a generated, saved replacement writes it.
        // Loading damaged old metadata can invent a UUID without clearing its chunks.
        java.util.UUID generated = homeland.getWorldUuid();
        CompletionStage<Void> recovery = generated == null
                ? performOverworldReset(null, homeland, homeland.getWorldType())
                : loadOverworldForRecovery(homeland)
                .thenCompose(world -> {
                    if (world == null || !generated.equals(world.getUID())) {
                        return performOverworldReset(null, homeland, homeland.getWorldType());
                    }
                    return finishExistingOverworldReset(homeland, world);
                });
        return reportFailure(recovery, null, ChargedCost.none(),
                null, "恢复主世界重置失败");
    }

    private CompletionStage<World> loadOverworldForRecovery(Homeland homeland) {
        World loaded = getWorld(homeland.getWorldKey());
        if (loaded != null) return CompletableFuture.completedFuture(loaded);
        return plugin.getWorldService().load(homeland.getWorldKey(), homeland);
    }

    private CompletionStage<Void> finishExistingOverworldReset(
            Homeland homeland, World world) {
        return onGlobalRegionThread(() -> new OverworldResetContext(
                        requireProvider(), world,
                        homeland.hasNether()
                                ? getWorld(HomelandDimension.NETHER.worldKey(homeland)) : null,
                        homeland.hasEnd()
                                ? getWorld(HomelandDimension.END.worldKey(homeland)) : null))
                .thenCompose(context -> findResetSpawnY(world, homeland.getWorldType())
                        .thenApply(spawnY -> new ConfiguredOverworld(
                                new OverworldCreation(context, world), spawnY)))
                .thenCompose(configured -> configureOverworld(
                                configured, homeland, homeland.getWorldType())
                        .thenApply(ignored -> configured.creation().world()))
                .thenCompose(replacement -> persistGeneratedOverworld(homeland, replacement)
                        .thenApply(ignored -> replacement))
                .thenCompose(worldResult -> finishOverworldReset(
                        null, homeland, homeland.getWorldType(), worldResult));
    }

    private CompletionStage<Void> performOverworldReset(
            CommandSender target, Homeland homeland, String worldTypeKey) {
        CompletionStage<Void> workflow = onGlobalRegionThread(() -> {
                    WorldsAccess provider = requireProvider();
                    return new OverworldResetContext(
                            provider,
                            getWorld(homeland.getWorldKey()),
                            homeland.hasNether()
                                    ? getWorld(HomelandDimension.NETHER.worldKey(homeland)) : null,
                            homeland.hasEnd()
                                    ? getWorld(HomelandDimension.END.worldKey(homeland)) : null);
                })
                .thenCompose(context -> evacuatePlayers(context.overworld() == null
                                ? List.of() : List.of(context.overworld()))
                        .thenApply(ignored -> context))
                .thenCompose(context -> unloadOverworld(context).thenApply(ignored -> context))
                .thenCompose(context -> deleteRegionFilesAsync(
                                plugin.getWorldService().directory(homeland.getWorldKey()))
                        .thenApply(ignored -> context))
                .thenCompose(context -> createOverworld(context, homeland, worldTypeKey)
                        .thenApply(world -> new OverworldCreation(context, world)))
                .thenCompose(creation -> findResetSpawnY(creation.world(), worldTypeKey)
                        .thenApply(spawnY -> new ConfiguredOverworld(creation, spawnY)))
                .thenCompose(configured -> configureOverworld(configured, homeland, worldTypeKey)
                        .thenApply(ignored -> configured.creation().world()))
                .thenCompose(world -> persistGeneratedOverworld(homeland, world)
                        .thenApply(ignored -> world))
                .thenCompose(world -> finishOverworldReset(
                        target, homeland, worldTypeKey, world));
        return workflow;
    }

    private CompletionStage<Void> persistGeneratedOverworld(Homeland homeland, World world) {
        return requireProvider().save(world, true).thenCompose(saved -> {
            if (!saved) return CompletableFuture.failedFuture(
                    new IOException("Could not save replacement overworld: " + world.key()));
            return retryDatabase(() -> plugin.getDatabaseQueue().submitFuture(
                    "record-generated-overworld", connection -> {
                        recordGeneratedOverworldResetRecord(connection, homeland.getId(), world.getUID());
                        return null;
                    }), 3).thenRun(() -> store.updateWorldUuid(homeland, world.getUID()));
        });
    }

    private CompletionStage<Void> finishOverworldReset(
            CommandSender target, Homeland homeland, String worldTypeKey, World world) {
        return retryDatabase(() -> plugin.getDatabaseQueue().submitFuture(
                            "finish-reset-overworld", conn -> {
                                finishOverworldResetRecord(conn, homeland.getId(),
                                        worldTypeKey, world.getUID());
                                return null;
                            }), 3)
                .thenRun(() -> {
                    store.updateWorldUuid(homeland, world.getUID());
                    homeland.setWorldType(worldTypeKey);
                    homeland.setOverworldResetPending(false);
                    homeland.setOverworldResetPreviousUuid(null);
                    if (target != null) {
                        sendMessage(target, plugin.getConfigManager().getMessage(
                                "reset-overworld-success", "name", homeland.getName()));
                    }
                });
    }

    static void prepareOverworldResetRecord(java.sql.Connection connection, int homelandId,
                                            String worldType,
                                            java.util.UUID previousWorldUuid)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE homeland SET world_type = ?, world_uuid = NULL, " +
                        "overworld_reset_pending = TRUE, " +
                        "overworld_reset_previous_uuid = ? WHERE id = ?")) {
            statement.setString(1, worldType);
            statement.setString(2, previousWorldUuid == null
                    ? null : previousWorldUuid.toString());
            statement.setInt(3, homelandId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Homeland row not found: " + homelandId);
            }
        }
    }

    static void recordGeneratedOverworldResetRecord(java.sql.Connection connection, int homelandId,
                                                    java.util.UUID worldUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE homeland SET world_uuid = ? WHERE id = ? AND overworld_reset_pending = TRUE")) {
            statement.setString(1, worldUuid.toString());
            statement.setInt(2, homelandId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Pending overworld reset not found: " + homelandId);
            }
        }
    }

    static void finishOverworldResetRecord(java.sql.Connection connection, int homelandId,
                                           String worldType, java.util.UUID worldUuid)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE homeland SET world_uuid = ?, world_type = ?, " +
                        "overworld_reset_pending = FALSE, " +
                        "overworld_reset_previous_uuid = NULL WHERE id = ?")) {
            statement.setString(1, worldUuid.toString());
            statement.setString(2, worldType);
            statement.setInt(3, homelandId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Homeland row not found: " + homelandId);
            }
        }
    }

    CompletionStage<Void> resetDimension(CommandSender target, Homeland homeland,
                                         HomelandDimension dimension, Player economyPlayer) {
        ConfigManager config = plugin.getConfigManager();
        if (dimension == HomelandDimension.OVERWORLD || !dimension.isUnlocked(homeland)) {
            return reject(target, config.getMessage("dimension-not-unlocked"));
        }

        ChargedCost cost = economyPlayer == null ? ChargedCost.none() : charge(
                economyPlayer,
                dimension == HomelandDimension.NETHER
                        ? config.getResetNetherMoney() : config.getResetEndMoney(),
                dimension == HomelandDimension.NETHER
                        ? config.getResetNetherPoints() : config.getResetEndPoints());
        if (cost == null) return CompletableFuture.failedFuture(new ExpectedFailure());

        CompletionStage<Void> workflow = loadDimensionForReset(homeland, dimension)
                .thenCompose(context -> regenerateDimension(context, homeland, dimension)
                        .exceptionallyCompose(error -> failAfterCleanup(
                                restoreDimensionWorld(context), error,
                                "恢复重置失败的维度链接时发生异常")))
                .thenCompose(context -> onGlobalRegionThread(() ->
                        initializeDimensionWorld(context, homeland)))
                .thenRun(() -> sendMessage(target, config.getMessage(
                        dimension == HomelandDimension.NETHER
                                ? "reset-nether-success" : "reset-end-success",
                        "name", homeland.getName())));

        return reportFailure(workflow, target, cost,
                config.getMessage("reset-failed"),
                "重置" + dimension.displayName() + "失败");
    }

    CompletionStage<Void> expandBorder(CommandSender target, Homeland homeland,
                                       Player economyPlayer) {
        ConfigManager config = plugin.getConfigManager();
        int oldRadius = homeland.getBorderRadius();
        int newRadius = oldRadius + config.getBorderExpandStep();
        if (newRadius > config.getMaxBorderRadius()) {
            return reject(target, config.getMessage("border-max-reached"));
        }

        World overworld = getWorld(homeland.getWorldKey());
        if (overworld == null) {
            return reject(target, config.getMessage("world-not-loaded"));
        }

        PriceTier tier = economyPlayer == null
                ? new PriceTier(0, 0) : config.getExpansionCost(oldRadius);
        ChargedCost cost = economyPlayer == null ? ChargedCost.none()
                : charge(economyPlayer, tier.getMoney(), tier.getPoints());
        if (cost == null) return CompletableFuture.failedFuture(new ExpectedFailure());

        CompletionStage<Void> workflow = setWorldBorders(homeland, newRadius)
                .thenRun(() -> homeland.setBorderRadius(newRadius))
                .thenCompose(ignored -> plugin.getDatabaseQueue().submitFuture(
                        "expand-border", connection -> {
                            try (PreparedStatement statement = connection.prepareStatement(
                                    "UPDATE homeland SET border_radius = ? WHERE id = ?")) {
                                statement.setInt(1, newRadius);
                                statement.setInt(2, homeland.getId());
                                if (statement.executeUpdate() != 1) {
                                    throw new SQLException(
                                            "Homeland row not found: " + homeland.getId());
                                }
                            }
                            return null;
                        }).exceptionallyCompose(error -> setWorldBorders(homeland, oldRadius)
                                .handle((ignoredRollback, rollbackError) -> {
                                    homeland.setBorderRadius(oldRadius);
                                    if (rollbackError != null) {
                                        error.addSuppressed(unwrap(rollbackError));
                                    }
                                    return null;
                                })
                                .thenCompose(ignoredRollback ->
                                        CompletableFuture.failedFuture(error))))
                .thenRun(() -> sendMessage(target, config.getMessage(
                        "border-expanded", "name", homeland.getName(),
                        "border", String.valueOf(newRadius))));

        return reportFailure(workflow, target, cost,
                config.getMessage("border-expand-failed"), "扩展家园边界失败");
    }

    CompletionStage<Void> setGamerule(CommandSender target, Homeland homeland,
                                      GameRuleConfig gameRule, String value) {
        World overworld = getWorld(homeland.getWorldKey());
        if (overworld == null) {
            return reject(target, plugin.getConfigManager().getMessage(
                    "gui.gamerule-world-not-loaded"));
        }
        CompletionStage<Void> workflow = onGlobalRegionThread(() -> {
            forEachLoadedWorld(homeland, world ->
                    HomelandWorldSettings.applyGamerule(world, gameRule, value));
        });
        return reportFailure(workflow, target, ChargedCost.none(),
                plugin.getConfigManager().getMessage("gui.gamerule-set-failed"),
                "设置家园游戏规则失败");
    }

    CompletionStage<Void> setDifficulty(CommandSender target, Homeland homeland,
                                        Difficulty difficulty) {
        World overworld = getWorld(homeland.getWorldKey());
        if (overworld == null) {
            return reject(target, plugin.getConfigManager().getMessage(
                    "gui.difficulty-world-not-loaded"));
        }
        return onGlobalRegionThread(() ->
                forEachLoadedWorld(homeland, world -> world.setDifficulty(difficulty)));
    }

    CompletionStage<Void> changeBiome(Player player, Homeland homeland, Biome biome,
                                      double money, int points) {
        World world = player.getWorld();
        String worldKey = world.getKey().getKey();
        if (!worldKey.equals(homeland.getWorldKey())
                || !homeland.getOwnerUuid().equals(player.getUniqueId())) {
            return reject(player, plugin.getConfigManager().getMessage(
                    "gui.biome-not-in-homeland"));
        }

        ChargedCost cost = charge(player, money, points);
        if (cost == null) return CompletableFuture.failedFuture(new ExpectedFailure());
        Location location = player.getLocation();
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        CompletionStage<Void> workflow = onRegionThread(world, chunkX, chunkZ, () -> {
            for (int x = chunkX << 4; x < (chunkX << 4) + 16; x += 4) {
                for (int z = chunkZ << 4; z < (chunkZ << 4) + 16; z += 4) {
                    for (int y = world.getMinHeight(); y < world.getMaxHeight(); y += 4) {
                        world.setBiome(x, y, z, biome);
                    }
                }
            }
            world.refreshChunk(chunkX, chunkZ);
            return null;
        });
        return reportFailure(workflow, player, cost,
                plugin.getConfigManager().getMessage("gui.biome-failed"),
                "更改家园群系失败");
    }

    CompletionStage<Void> updateCustomSetting(CommandSender target, Homeland homeland,
                                              String key, boolean value) {
        if (getWorld(homeland.getWorldKey()) == null) {
            return reject(target, plugin.getConfigManager().getMessage(
                    "gui.gamerule-world-not-loaded"));
        }

        CustomSettings settings = homeland.getCustomSettings();
        CustomSettings.Update update = settings.update(key, value);
        return plugin.getDatabaseQueue().<Void>submitFuture(
                        "update-custom-setting-" + homeland.getId(), connection -> {
                            String persistedJson;
                            try (PreparedStatement select = connection.prepareStatement(
                                    "SELECT custom_settings FROM homeland WHERE id = ?")) {
                                select.setInt(1, homeland.getId());
                                try (ResultSet result = select.executeQuery()) {
                                    if (!result.next()) {
                                        throw new SQLException(
                                                "Homeland row not found: " + homeland.getId());
                                    }
                                    persistedJson = result.getString("custom_settings");
                                }
                            }
                            try (PreparedStatement statement = connection.prepareStatement(
                                    "UPDATE homeland SET custom_settings = ? WHERE id = ?")) {
                                statement.setString(1, CustomSettings.updateJson(
                                        persistedJson, key, value));
                                statement.setInt(2, homeland.getId());
                                if (statement.executeUpdate() != 1) {
                                    throw new SQLException(
                                            "Homeland row not found: " + homeland.getId());
                                }
                            }
                            return null;
                        })
                .exceptionallyCompose(error -> {
                    settings.rollback(update);
                    return CompletableFuture.<Void>failedFuture(error);
                });
    }

    private CompletionStage<Void> setWorldBorders(Homeland homeland, int radius) {
        return onGlobalRegionThread(() -> forEachLoadedWorld(homeland, world -> {
            world.getWorldBorder().setCenter(0, 0);
            world.getWorldBorder().setSize(radius * 2);
        }));
    }

    private void forEachLoadedWorld(Homeland homeland, Consumer<World> action) {
        World overworld = getWorld(homeland.getWorldKey());
        if (overworld != null) action.accept(overworld);
        if (homeland.hasNether()) {
            World nether = getWorld(HomelandDimension.NETHER.worldKey(homeland));
            if (nether != null) action.accept(nether);
        }
        if (homeland.hasEnd()) {
            World end = getWorld(HomelandDimension.END.worldKey(homeland));
            if (end != null) action.accept(end);
        }
    }

    private HomelandDeletionPlan buildDeletionPlan(Homeland homeland) {
        return buildDeletionPlan(new WorldCleanupTask(homeland.getWorldKey()));
    }

    private HomelandDeletionPlan buildDeletionPlan(WorldCleanupTask task) {
        List<String> keys = List.of(
                task.worldKey(),
                task.worldKey() + HomelandDimension.NETHER.worldKeySuffix(),
                task.worldKey() + HomelandDimension.END.worldKeySuffix());

        Set<World> loaded = new LinkedHashSet<>();
        Set<Path> unloaded = new LinkedHashSet<>();
        for (String key : keys) {
            World world = getWorld(key);
            if (world != null) {
                loaded.add(world);
            } else {
                Path path = plugin.getWorldService().directory(key);
                // 不在 global region 线程执行磁盘 stat；删除阶段在异步线程复查路径。
                unloaded.add(path);
            }
        }
        return new HomelandDeletionPlan(List.copyOf(loaded), List.copyOf(unloaded));
    }

    private CompletionStage<Void> deleteHomelandWorlds(HomelandDeletionPlan plan) {
        List<CompletableFuture<Void>> cleanup = new ArrayList<>();
        for (World world : plan.loadedWorlds()) {
            cleanup.add(plugin.getWorldService().delete(world.key().value(), world));
        }
        for (Path path : plan.unloadedPaths()) {
            cleanup.add(plugin.getWorldService().delete(path.getFileName().toString(), null));
        }
        return CompletableFuture.allOf(cleanup.toArray(CompletableFuture[]::new));
    }

    private CompletionStage<DimensionWorldContext> createUnlockedDimension(
            Homeland homeland, HomelandDimension dimension, CommandSender target) {
        CompletionStage<DimensionWorldContext> creation = onGlobalRegionThreadAsync(() -> {
            WorldsAccess provider = requireProvider();
            World overworld = getWorld(homeland.getWorldKey());
            if (overworld == null) {
                return reject(target, plugin.getConfigManager().getMessage("world-not-loaded"));
            }
            return plugin.getWorldService().create(dimensionBuilder(provider, homeland, dimension).build())
                    .thenApply(world -> new DimensionWorldContext(provider, overworld, world,
                            plugin.getWorldService().directory(dimension.worldKey(homeland))));
        });
        return creation.thenCompose(context -> onGlobalRegionThread(() -> {
                    initializeDimensionWorld(context, homeland);
                    return context;
                }).exceptionallyCompose(error -> failAfterCleanup(
                        cleanupCreatedDimension(context), error,
                        "清理初始化失败的维度世界时发生异常")));
    }

    private CompletionStage<DimensionWorldContext> loadDimensionForDeletion(
            Homeland homeland, HomelandDimension dimension) {
        return onGlobalRegionThread(() -> {
            WorldsAccess provider = requireProvider();
            World overworld = getWorld(homeland.getWorldKey());
            String worldKey = dimension.worldKey(homeland);
            Path path = plugin.getWorldService().directory(worldKey);
            World world = getWorld(worldKey);
            if (world != null) {
                return new DimensionWorldContext(provider, overworld, world, path,
                        homeland.getWorldUuid(dimension));
            }
            // 未加载世界没有玩家需要疏散，直接在删除阶段异步清理目录，
            // 避免为了删除损坏的 level.dat 再次加载世界。
            return new DimensionWorldContext(provider, overworld, null, path,
                    homeland.getWorldUuid(dimension));
        });
    }

    private CompletionStage<DimensionWorldContext> loadDimensionForReset(
            Homeland homeland, HomelandDimension dimension) {
        return loadDimension(homeland, dimension);
    }

    private CompletionStage<DimensionWorldContext> loadDimension(
            Homeland homeland, HomelandDimension dimension) {
        return onGlobalRegionThreadAsync(() -> {
            WorldsAccess provider = requireProvider();
            World overworld = getWorld(homeland.getWorldKey());
            Path path = plugin.getWorldService().directory(dimension.worldKey(homeland));
            World world = getWorld(dimension.worldKey(homeland));
            if (world != null) {
                return CompletableFuture.completedFuture(
                        new DimensionWorldContext(provider, overworld, world, path));
            }
            return plugin.getWorldService().load(dimension.worldKey(homeland), homeland)
                    .thenApply(loaded -> new DimensionWorldContext(
                            provider, overworld, loaded, path));
        });
    }

    private Level.Builder dimensionBuilder(WorldsAccess provider, Homeland homeland,
                                           HomelandDimension dimension) {
        return plugin.getWorldService().builder(dimension.worldKey(homeland), homeland.getWorldType());
    }

    private void initializeDimensionWorld(DimensionWorldContext context, Homeland homeland) {
        context.world().setAutoSave(true);
        context.world().getWorldBorder().setCenter(0, 0);
        context.world().getWorldBorder().setSize(homeland.getBorderRadius() * 2);
        touchWorld.accept(context.world().getKey().getKey());
        if (context.overworld() != null) {
            HomelandWorldSettings.copyGamerules(
                    context.overworld(), context.world(), plugin.getConfigManager());
            HomelandWorldSettings.copyDifficulty(context.overworld(), context.world());
        }
    }

    private CompletionStage<Void> cleanupCreatedDimension(DimensionWorldContext context) {
        return plugin.getWorldService().delete(context.world().key().value(), context.world());
    }

    private CompletionStage<Void> updateDimensionState(
            Homeland homeland, HomelandDimension dimension, boolean unlocked, String taskName) {
        World world = getWorld(dimension.worldKey(homeland));
        java.util.UUID uuid = !unlocked ? null : world == null
                ? homeland.getWorldUuid(dimension) : world.getUID();
        return updateDimensionState(homeland, dimension, unlocked, taskName, uuid);
    }

    private CompletionStage<Void> updateDimensionState(
            Homeland homeland, HomelandDimension dimension, boolean unlocked, String taskName,
            java.util.UUID uuid) {
        return plugin.getDatabaseQueue().submitFuture(taskName, conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE homeland SET " + dimension.databaseColumn() + " = ?, "
                            + dimension.uuidColumn() + " = ? WHERE id = ?")) {
                stmt.setBoolean(1, unlocked);
                stmt.setString(2, uuid == null ? null : uuid.toString());
                stmt.setInt(3, homeland.getId());
                if (stmt.executeUpdate() != 1) {
                    throw new SQLException("Homeland row not found: " + homeland.getId());
                }
            }
            return null;
        }).thenRun(() -> homeland.setWorldUuid(dimension, uuid));
    }

    private CompletionStage<Void> deleteDimensionWorld(DimensionWorldContext context) {
        return plugin.getWorldService().delete(context.path().getFileName().toString(), context.world());
    }

    private CompletionStage<Void> restoreDimensionWorld(DimensionWorldContext context) {
        // Worlds 4 custom-world operations complete immediately and never schedule a restart action.
        if (context.world() == null || getWorld(context.world().key().value()) == context.world()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.failedFuture(new IllegalStateException(
                "Dimension was already unloaded: " + context.world().key()));
    }

    private CompletionStage<Void> compensateDimensionDeletion(
            Homeland homeland, HomelandDimension dimension, DimensionWorldContext context,
            Throwable originalError) {
        return restoreDimensionWorld(context)
                .handle((ignored, worldError) -> worldError)
                .thenCompose(worldError -> {
                    if (worldError != null) {
                        dimension.setUnlocked(homeland, false);
                        Throwable cause = unwrap(worldError);
                        originalError.addSuppressed(cause);
                        plugin.getLogger().log(java.util.logging.Level.SEVERE,
                                "恢复维度世界失败，维度将保持锁定", cause);
                        return disableDimensionWorld(context)
                                .handle((ignoredDisabled, disableError) -> {
                                    if (disableError != null) {
                                        originalError.addSuppressed(unwrap(disableError));
                                    }
                                    store.refreshIndexes(homeland);
                                    return (Void) null;
                                })
                                .thenCompose(ignoredDisabled ->
                                        CompletableFuture.failedFuture(originalError));
                    }
                    return updateDimensionState(
                                    homeland, dimension, true, "restore-dimension-state", context.uuid())
                            .handle((ignored, databaseError) -> databaseError)
                            .thenCompose(databaseError -> {
                                if (databaseError == null) {
                                    dimension.setUnlocked(homeland, true);
                                    store.refreshIndexes(homeland);
                                    return CompletableFuture.failedFuture(originalError);
                                } else {
                                    dimension.setUnlocked(homeland, false);
                                    Throwable cause = unwrap(databaseError);
                                    originalError.addSuppressed(cause);
                                    plugin.getLogger().log(java.util.logging.Level.SEVERE,
                                            "回补维度数据库状态失败，维度将保持锁定", cause);
                                    return disableDimensionWorld(context)
                                            .handle((ignoredDisabled, disableError) -> {
                                                if (disableError != null) {
                                                    originalError.addSuppressed(unwrap(disableError));
                                                }
                                                store.refreshIndexes(homeland);
                                                return (Void) null;
                                            })
                                            .thenCompose(ignoredDisabled ->
                                                    CompletableFuture.failedFuture(originalError));
                                }
                            });
                });
    }

    private CompletionStage<Void> disableDimensionWorld(DimensionWorldContext context) {
        if (context.world() == null) return CompletableFuture.completedFuture(null);
        return context.provider().unload(context.world(), false).thenCompose(success ->
                success ? CompletableFuture.completedFuture(null) : CompletableFuture.failedFuture(
                        new IllegalStateException("Could not unload locked dimension")));
    }

    private CompletionStage<Void> unloadOverworld(OverworldResetContext context) {
        if (context.overworld() == null) return CompletableFuture.completedFuture(null);
        return context.provider().unload(context.overworld(), false).thenCompose(success ->
                success ? CompletableFuture.completedFuture(null) : CompletableFuture.failedFuture(
                        new IllegalStateException("Failed to unload overworld")));
    }

    private CompletionStage<Void> deleteRegionFilesAsync(Path worldPath) {
        return CompletableFuture.runAsync(() -> {
            try {
                deleteRegionFiles(worldPath);
            } catch (IOException error) {
                throw new CompletionException(error);
            }
        });
    }

    private CompletionStage<World> createOverworld(
            OverworldResetContext context, Homeland homeland, String worldTypeKey) {
        return onGlobalRegionThreadAsync(() -> {
            Level.Builder builder = plugin.getWorldService()
                    .builder(homeland.getWorldKey(), worldTypeKey)
                    .ignoreLevelData(true)
                    .seed(new java.util.Random().nextLong());

            if (!"void".equals(worldTypeKey) && !"flat".equals(worldTypeKey)) {
                ConfigManager config = plugin.getConfigManager();
                builder.structures(config.isWorldStructures())
                        .bonusChest(config.isWorldBonusChest());
            } else {
                HomelandWorldSettings.applyWorldTypeGenerator(plugin, builder, worldTypeKey);
            }
            return plugin.getWorldService().create(builder.build());
        });
    }

    private CompletionStage<Integer> findResetSpawnY(World world, String worldTypeKey) {
        return onRegionThread(world, 0, 0, () -> {
            ConfigManager config = plugin.getConfigManager();
            return switch (worldTypeKey) {
                case "void" -> config.getSpawnYVoid();
                case "flat" -> config.getSpawnYFlat();
                default -> world.getHighestBlockYAt(0, 0) + 1;
            };
        });
    }

    private CompletionStage<Void> configureOverworld(
            ConfiguredOverworld configured, Homeland homeland, String worldTypeKey) {
        return onGlobalRegionThread(() -> {
            World world = configured.creation().world();
            if ("void".equals(worldTypeKey)) {
                world.setSpawnLocation(8, configured.spawnY(), 8);
            } else {
                world.setSpawnLocation(0, configured.spawnY(), 0);
            }
            world.getWorldBorder().setCenter(0, 0);
            world.getWorldBorder().setSize(homeland.getBorderRadius() * 2);
            world.setAutoSave(true);
            touchWorld.accept(homeland.getWorldKey());
            HomelandWorldSettings.applyDefaultGamerules(world, plugin.getConfigManager());
            HomelandWorldSettings.applyDefaultDifficulty(world, plugin.getConfigManager());

        });
    }

    private CompletionStage<DimensionWorldContext> regenerateDimension(
            DimensionWorldContext context, Homeland homeland, HomelandDimension dimension) {
        return context.provider().regenerate(context.world(),
                        builder -> builder.seed(new java.util.Random().nextLong()))
                .thenApply(world -> new DimensionWorldContext(
                        context.provider(), context.overworld(), world, context.path()));
    }

    private ChargedCost charge(Player player, double money, int points) {
        return economy.withdraw(player, money, points)
                ? new ChargedCost(player, money, points) : null;
    }

    private <T> CompletionStage<T> retryDatabase(
            Supplier<? extends CompletionStage<T>> action, int attemptsRemaining) {
        CompletionStage<T> attempt;
        try {
            attempt = action.get();
        } catch (Throwable error) {
            attempt = CompletableFuture.failedFuture(error);
        }
        if (attemptsRemaining <= 1) return attempt;
        return attempt.exceptionallyCompose(error -> {
            plugin.getLogger().warning("数据库提交失败，将重试（剩余 "
                    + (attemptsRemaining - 1) + " 次）: " + unwrap(error).getMessage());
            return retryDatabase(action, attemptsRemaining - 1);
        });
    }

    private <T> CompletionStage<T> reportFailure(
            CompletionStage<T> stage, CommandSender target, ChargedCost cost,
            String failureMessage, String logContext) {
        return stage.handle((result, error) -> {
            if (error == null) return result;
            economy.refund(cost.player(), cost.money(), cost.points());
            Throwable cause = unwrap(error);
            if (cause instanceof ExpectedFailure) throw new CompletionException(cause);

            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    logContext + ": " + cause.getMessage(), cause);
            if (target != null && failureMessage != null) {
                sendMessage(target, failureMessage);
            }
            throw new CompletionException(new ExpectedFailure(cause));
        });
    }

    private <T> CompletionStage<T> reject(CommandSender target, String message) {
        sendMessage(target, message);
        return CompletableFuture.failedFuture(new ExpectedFailure());
    }

    private <T> CompletionStage<T> failAfterCleanup(
            CompletionStage<?> cleanup, Throwable originalError, String cleanupContext) {
        return cleanup.handle((ignored, cleanupError) -> {
                    if (cleanupError != null) {
                        Throwable cause = unwrap(cleanupError);
                        originalError.addSuppressed(cause);
                        plugin.getLogger().warning(cleanupContext + ": " + cause.getMessage());
                    }
                    return null;
                })
                .thenCompose(ignored -> CompletableFuture.failedFuture(originalError));
    }

    private CompletionStage<Void> evacuatePlayers(Collection<World> worlds) {
        if (worlds.isEmpty()) return CompletableFuture.completedFuture(null);
        World mainWorld = Bukkit.getWorlds().get(0);
        Set<Player> players = new LinkedHashSet<>();
        for (World world : worlds) players.addAll(world.getPlayers());
        if (players.isEmpty()) return CompletableFuture.completedFuture(null);

        List<CompletableFuture<Void>> teleports = new ArrayList<>(players.size());
        for (Player player : players) {
            CompletableFuture<Void> teleport = new CompletableFuture<>();
            teleports.add(teleport);
            boolean scheduled = player.getScheduler().execute(plugin, () -> {
                try {
                    player.teleportAsync(mainWorld.getSpawnLocation()).whenComplete((success, error) -> {
                        if (error != null) {
                            teleport.completeExceptionally(error);
                        } else if (!success) {
                            teleport.completeExceptionally(new IllegalStateException(
                                    "Failed to evacuate player " + player.getName()));
                        } else {
                            teleport.complete(null);
                        }
                    });
                } catch (Throwable error) {
                    teleport.completeExceptionally(error);
                }
            }, () -> teleport.completeExceptionally(new IllegalStateException(
                    "Player retired before evacuation: " + player.getName())), 1L);
            if (!scheduled) {
                teleport.completeExceptionally(new IllegalStateException(
                        "Could not schedule evacuation for " + player.getName()));
            }
        }
        return CompletableFuture.allOf(teleports.toArray(CompletableFuture[]::new));
    }

    private <T> CompletionStage<T> onGlobalRegionThread(Supplier<T> action) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                try {
                    future.complete(action.get());
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                }
            });
        } catch (Throwable error) {
            future.completeExceptionally(error);
        }
        return future;
    }

    private CompletionStage<Void> onGlobalRegionThread(Runnable action) {
        return onGlobalRegionThread(() -> {
            action.run();
            return null;
        });
    }

    private <T> CompletionStage<T> onGlobalRegionThreadAsync(
            Supplier<? extends CompletionStage<T>> action) {
        return onGlobalRegionThread(action).thenCompose(Function.identity());
    }

    private <T> CompletionStage<T> onRegionThread(
            World world, int chunkX, int chunkZ, Supplier<T> action) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, () -> {
                try {
                    future.complete(action.get());
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                }
            });
        } catch (Throwable error) {
            future.completeExceptionally(error);
        }
        return future;
    }

    static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private WorldsAccess requireProvider() {
        WorldsAccess provider = plugin.getWorldsAccess();
        if (provider == null) throw new IllegalStateException("Worlds provider is unavailable");
        return provider;
    }

    private World getWorld(String worldKey) {
        return Bukkit.getWorld(new NamespacedKey("homelands", worldKey));
    }

    private void sendMessage(CommandSender target, String message) {
        MessageUtil.sendAsync(target, message, plugin);
    }

    private void deleteRecursively(Path path) throws IOException {
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void deleteRegionFiles(Path levelDir) throws IOException {
        String[] subdirs = {
                "DIM-1", "DIM1", "advancements", "data", "entities",
                "playerdata", "poi", "region", "stats"
        };
        IOException failure = null;
        for (String subdir : subdirs) {
            Path path = levelDir.resolve(subdir);
            if (!Files.exists(path)) continue;
            try {
                deleteRecursively(path);
            } catch (IOException error) {
                if (failure == null) {
                    failure = new IOException("Failed to clear world data in " + levelDir);
                }
                failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }

    private record ChargedCost(Player player, double money, int points) {
        private static ChargedCost none() {
            return new ChargedCost(null, 0, 0);
        }
    }

    private record HomelandDeletionPlan(List<World> loadedWorlds, List<Path> unloadedPaths) {
    }

    private record WorldCleanupTask(String worldKey) {
    }

    private record DimensionWorldContext(WorldsAccess provider, World overworld,
                                         World world, Path path, java.util.UUID uuid) {
        private DimensionWorldContext(WorldsAccess provider, World overworld, World world, Path path) {
            this(provider, overworld, world, path, world == null ? null : world.getUID());
        }
    }

    private record OverworldResetContext(
            WorldsAccess provider, World overworld, World nether, World end) {
    }

    private record OverworldCreation(OverworldResetContext context, World world) {
    }

    private record ConfiguredOverworld(OverworldCreation creation, int spawnY) {
    }
}
