package dev.user.homeland.manager;

import dev.user.homeland.SimpleHomelandPlugin;
import dev.user.homeland.config.ConfigManager;
import dev.user.homeland.config.ConfigManager.GameRuleConfig;
import dev.user.homeland.config.PriceTier;
import dev.user.homeland.model.CustomSettings;
import dev.user.homeland.model.Homeland;
import dev.user.homeland.model.HomelandDimension;
import dev.user.homeland.util.MessageUtil;
import net.kyori.adventure.key.Key;
import net.thenextlvl.worlds.api.WorldsProvider;
import net.thenextlvl.worlds.api.level.Level;
import net.thenextlvl.worlds.api.link.LinkProvider;
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
                        .thenCompose(ignored -> context.world() == null
                                ? CompletableFuture.completedFuture(context)
                                : onGlobalRegionThread(() -> {
                                    context.provider().levelView().setEnabled(context.world(), false);
                                    return context;
                                })))
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
                    return reportFailure(performOverworldReset(
                                    target, homeland, worldTypeKey), target, cost,
                            config.getMessage("reset-pending-recovery"), "重置主世界失败");
                });
    }

    CompletionStage<Void> resumeOverworldReset(Homeland homeland) {
        CompletionStage<Void> recovery = loadOverworldForRecovery(homeland)
                .thenCompose(world -> {
                    java.util.UUID previous = homeland.getOverworldResetPreviousUuid();
                    if (world == null || previous == null || previous.equals(world.getUID())) {
                        return performOverworldReset(null, homeland, homeland.getWorldType());
                    }
                    return finishExistingOverworldReset(homeland, world);
                });
        return reportFailure(recovery, null, ChargedCost.none(),
                null, "恢复主世界重置失败");
    }

    private CompletionStage<World> loadOverworldForRecovery(Homeland homeland) {
        return onGlobalRegionThreadAsync(() -> {
            World loaded = getWorld(homeland.getWorldKey());
            if (loaded != null) return CompletableFuture.completedFuture(loaded);

            WorldsProvider provider = requireProvider();
            Path path = provider.levelView().getWorldContainer()
                    .resolve("homelands/" + homeland.getWorldKey());
            var builder = provider.levelView().read(path);
            if (builder.isEmpty()) return CompletableFuture.completedFuture(null);
            return builder.get().build().createAsync();
        });
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
                .thenCompose(worldResult -> finishOverworldReset(
                        null, homeland, homeland.getWorldType(), worldResult));
    }

    private CompletionStage<Void> performOverworldReset(
            CommandSender target, Homeland homeland, String worldTypeKey) {
        CompletionStage<Void> workflow = onGlobalRegionThread(() -> {
                    WorldsProvider provider = requireProvider();
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
                                context.provider().levelView().getWorldContainer()
                                        .resolve("homelands/" + homeland.getWorldKey()))
                        .thenApply(ignored -> context))
                .thenCompose(context -> createOverworld(context, homeland, worldTypeKey)
                        .thenApply(world -> new OverworldCreation(context, world)))
                .thenCompose(creation -> findResetSpawnY(creation.world(), worldTypeKey)
                        .thenApply(spawnY -> new ConfiguredOverworld(creation, spawnY)))
                .thenCompose(configured -> configureOverworld(configured, homeland, worldTypeKey)
                        .thenApply(ignored -> configured.creation().world()))
                .thenCompose(world -> finishOverworldReset(
                        target, homeland, worldTypeKey, world));
        return workflow;
    }

    private CompletionStage<Void> finishOverworldReset(
            CommandSender target, Homeland homeland, String worldTypeKey, World world) {
        store.updateWorldUuid(homeland, world.getUID());
        homeland.setWorldType(worldTypeKey);
        return retryDatabase(() -> plugin.getDatabaseQueue().submitFuture(
                            "finish-reset-overworld", conn -> {
                                finishOverworldResetRecord(conn, homeland.getId(),
                                        worldTypeKey, world.getUID());
                                return null;
                            }), 3)
                .thenRun(() -> {
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
        WorldsProvider provider = requireProvider();
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
                Path path = provider.levelView().getWorldContainer().resolve("homelands/" + key);
                // 不在 global region 线程执行磁盘 stat；删除阶段在异步线程复查路径。
                unloaded.add(path);
            }
        }
        return new HomelandDeletionPlan(provider, getWorld(task.worldKey()),
                List.copyOf(loaded), List.copyOf(unloaded));
    }

    private CompletionStage<Void> deleteHomelandWorlds(HomelandDeletionPlan plan) {
        return onGlobalRegionThreadAsync(() -> {
            LinkProvider linker = plan.provider().linkProvider();
            if (plan.overworld() != null) {
                for (World world : plan.loadedWorlds()) {
                    if (world != plan.overworld()) linker.unlink(plan.overworld(), world);
                }
            }

            List<CompletableFuture<Void>> cleanup = new ArrayList<>();
            for (World world : plan.loadedWorlds()) {
                plan.provider().levelView().setEnabled(world, false);
                cleanup.add(plan.provider().levelView().deleteAsync(world, false)
                        .thenCompose(result -> result
                                == net.thenextlvl.worlds.api.view.LevelView.DeletionResult.SUCCESS
                                ? CompletableFuture.completedFuture(null)
                                : CompletableFuture.failedFuture(new IllegalStateException(
                                        "World deletion returned " + result + ": "
                                                + world.getName()))));
            }
            for (Path path : plan.unloadedPaths()) {
                cleanup.add(CompletableFuture.runAsync(() -> {
                    try {
                        if (Files.exists(path)) deleteRecursively(path);
                    } catch (IOException error) {
                        throw new CompletionException(error);
                    }
                }));
            }
            return CompletableFuture.allOf(cleanup.toArray(CompletableFuture[]::new));
        });
    }

    private CompletionStage<DimensionWorldContext> createUnlockedDimension(
            Homeland homeland, HomelandDimension dimension, CommandSender target) {
        CompletionStage<DimensionWorldContext> creation = onGlobalRegionThreadAsync(() -> {
            WorldsProvider provider = requireProvider();
            World overworld = getWorld(homeland.getWorldKey());
            if (overworld == null) {
                return reject(target, plugin.getConfigManager().getMessage("world-not-loaded"));
            }
            return dimensionBuilder(provider, homeland, dimension).build().createAsync()
                    .thenApply(world -> new DimensionWorldContext(provider, overworld, world,
                            provider.levelView().getWorldContainer().resolve(
                                    "homelands/" + dimension.worldKey(homeland))));
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
            WorldsProvider provider = requireProvider();
            World overworld = getWorld(homeland.getWorldKey());
            String worldKey = dimension.worldKey(homeland);
            Path path = provider.levelView().getWorldContainer()
                    .resolve("homelands/" + worldKey);
            World world = getWorld(worldKey);
            if (world != null) {
                return new DimensionWorldContext(provider, overworld, world, path);
            }
            // 未加载世界没有玩家需要疏散，直接在删除阶段异步清理目录，
            // 避免为了删除损坏的 level.dat 再次加载世界。
            return new DimensionWorldContext(provider, overworld, null, path);
        });
    }

    private CompletionStage<DimensionWorldContext> loadDimensionForReset(
            Homeland homeland, HomelandDimension dimension) {
        return loadDimension(homeland, dimension);
    }

    private CompletionStage<DimensionWorldContext> loadDimension(
            Homeland homeland, HomelandDimension dimension) {
        return onGlobalRegionThreadAsync(() -> {
            WorldsProvider provider = requireProvider();
            World overworld = getWorld(homeland.getWorldKey());
            Path path = provider.levelView().getWorldContainer().resolve(
                    "homelands/" + dimension.worldKey(homeland));
            World world = getWorld(dimension.worldKey(homeland));
            if (world != null) {
                return CompletableFuture.completedFuture(
                        new DimensionWorldContext(provider, overworld, world, path));
            }
            return dimensionBuilder(provider, homeland, dimension).build().createAsync()
                    .thenApply(loaded -> new DimensionWorldContext(
                            provider, overworld, loaded, path));
        });
    }

    private Level.Builder dimensionBuilder(WorldsProvider provider, Homeland homeland,
                                           HomelandDimension dimension) {
        String worldKey = dimension.worldKey(homeland);
        return provider.levelBuilder(Path.of("homelands/" + worldKey))
                .key(Key.key("simplehomeland", worldKey))
                .name(homeland.getName() + " - " + dimension.displayName())
                .levelStem(dimension == HomelandDimension.NETHER
                        ? net.thenextlvl.worlds.api.generator.LevelStem.NETHER
                        : net.thenextlvl.worlds.api.generator.LevelStem.END);
    }

    private void initializeDimensionWorld(DimensionWorldContext context, Homeland homeland) {
        if (context.overworld() != null) {
            context.provider().linkProvider().link(context.overworld(), context.world());
        }
        context.provider().levelView().setEnabled(context.world(), true);
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
        return onGlobalRegionThreadAsync(() -> {
            if (context.overworld() != null) {
                context.provider().linkProvider().unlink(context.overworld(), context.world());
            }
            context.provider().levelView().setEnabled(context.world(), false);
            return context.provider().levelView().deleteAsync(context.world(), false)
                    .thenCompose(result -> {
                        if (result
                                == net.thenextlvl.worlds.api.view.LevelView.DeletionResult.SUCCESS) {
                            return CompletableFuture.completedFuture(null);
                        }
                        return onGlobalRegionThread(() -> {
                            var levelView = context.provider().levelView();
                            cancelScheduledOperation(
                                    levelView.isDeletionScheduled(context.world()),
                                    () -> levelView.cancelScheduledDeletion(context.world()),
                                    () -> levelView.isDeletionScheduled(context.world()),
                                    "unlock cleanup deletion");
                            throw new IllegalStateException(
                                    "Unlock cleanup returned " + result + ": "
                                            + context.world().getName());
                        });
                    });
        });
    }

    private CompletionStage<Void> updateDimensionState(
            Homeland homeland, HomelandDimension dimension, boolean unlocked, String taskName) {
        return plugin.getDatabaseQueue().submitFuture(taskName, conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE homeland SET " + dimension.databaseColumn() + " = ? WHERE id = ?")) {
                stmt.setBoolean(1, unlocked);
                stmt.setInt(2, homeland.getId());
                if (stmt.executeUpdate() != 1) {
                    throw new SQLException("Homeland row not found: " + homeland.getId());
                }
            }
            return null;
        });
    }

    private CompletionStage<Void> deleteDimensionWorld(DimensionWorldContext context) {
        if (context.world() == null) {
            if (context.path() == null) return CompletableFuture.completedFuture(null);
            return CompletableFuture.runAsync(() -> {
                try {
                    if (Files.exists(context.path())) deleteRecursively(context.path());
                } catch (IOException error) {
                    throw new CompletionException(error);
                }
            });
        }
        return onGlobalRegionThreadAsync(() -> {
            if (context.overworld() != null) {
                context.provider().linkProvider().unlink(context.overworld(), context.world());
            }
            return context.provider().levelView().deleteAsync(context.world(), false)
                    .thenCompose(result -> result
                            == net.thenextlvl.worlds.api.view.LevelView.DeletionResult.SUCCESS
                            ? CompletableFuture.completedFuture(null)
                            : CompletableFuture.failedFuture(new IllegalStateException(
                                    "World deletion returned " + result)));
        });
    }

    private CompletionStage<Void> restoreDimensionWorld(DimensionWorldContext context) {
        if (context.world() == null) return CompletableFuture.completedFuture(null);
        return onGlobalRegionThread(() -> {
            var levelView = context.provider().levelView();
            cancelScheduledOperation(levelView.isDeletionScheduled(context.world()),
                    () -> levelView.cancelScheduledDeletion(context.world()),
                    () -> levelView.isDeletionScheduled(context.world()), "deletion");
            cancelScheduledOperation(levelView.isRegenerationScheduled(context.world()),
                    () -> levelView.cancelScheduledRegeneration(context.world()),
                    () -> levelView.isRegenerationScheduled(context.world()), "regeneration");
            context.provider().levelView().setEnabled(context.world(), true);
            if (context.overworld() != null) {
                context.provider().linkProvider().link(context.overworld(), context.world());
            }
        });
    }

    private void cancelScheduledOperation(boolean scheduled, Supplier<Boolean> cancel,
                                          Supplier<Boolean> stillScheduled, String operation) {
        if (!scheduled) return;
        boolean cancelled = cancel.get();
        if (!cancelled || stillScheduled.get()) {
            throw new IllegalStateException(
                    "Could not cancel scheduled world " + operation);
        }
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
                                    homeland, dimension, true, "restore-dimension-state")
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
        return onGlobalRegionThread(() -> {
            if (context.overworld() != null) {
                context.provider().linkProvider().unlink(context.overworld(), context.world());
            }
            context.provider().levelView().setEnabled(context.world(), false);
        });
    }

    private CompletionStage<Void> unloadOverworld(OverworldResetContext context) {
        if (context.overworld() == null) return CompletableFuture.completedFuture(null);
        CompletionStage<Boolean> unload = onGlobalRegionThreadAsync(() -> {
            LinkProvider linker = context.provider().linkProvider();
            if (context.nether() != null) linker.unlink(context.overworld(), context.nether());
            if (context.end() != null) linker.unlink(context.overworld(), context.end());
            return context.provider().levelView().unloadAsync(context.overworld(), false);
        });
        return unload.handle((success, error) -> {
                    if (error != null) return error;
                    return Boolean.TRUE.equals(success) ? null
                            : new IllegalStateException("Failed to unload overworld");
                })
                .thenCompose(error -> error == null
                        ? CompletableFuture.completedFuture(null)
                        : failAfterCleanup(restoreOverworldLinks(context), error,
                                "恢复主世界维度链接时发生异常"));
    }

    private CompletionStage<Void> restoreOverworldLinks(OverworldResetContext context) {
        return onGlobalRegionThread(() -> {
            LinkProvider linker = context.provider().linkProvider();
            if (context.nether() != null) linker.link(context.overworld(), context.nether());
            if (context.end() != null) linker.link(context.overworld(), context.end());
        });
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
            Level.Builder builder = context.provider()
                    .levelBuilder(Path.of("homelands/" + homeland.getWorldKey()))
                    .key(Key.key("simplehomeland", homeland.getWorldKey()))
                    .name(homeland.getName())
                    .ignoreLevelData(true)
                    .seed(new java.util.Random().nextLong());

            if (!"void".equals(worldTypeKey) && !"flat".equals(worldTypeKey)) {
                ConfigManager config = plugin.getConfigManager();
                builder.structures(config.isWorldStructures())
                        .bonusChest(config.isWorldBonusChest());
            } else {
                HomelandWorldSettings.applyWorldTypeGenerator(builder, worldTypeKey);
            }
            return builder.build().createAsync();
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
            configured.creation().context().provider().levelView().setEnabled(world, true);
            world.setAutoSave(true);
            touchWorld.accept(homeland.getWorldKey());
            HomelandWorldSettings.applyDefaultGamerules(world, plugin.getConfigManager());
            HomelandWorldSettings.applyDefaultDifficulty(world, plugin.getConfigManager());

            LinkProvider linker = configured.creation().context().provider().linkProvider();
            if (configured.creation().context().nether() != null) {
                linker.link(world, configured.creation().context().nether());
            }
            if (configured.creation().context().end() != null) {
                linker.link(world, configured.creation().context().end());
            }
        });
    }

    private CompletionStage<DimensionWorldContext> regenerateDimension(
            DimensionWorldContext context, Homeland homeland, HomelandDimension dimension) {
        return onGlobalRegionThreadAsync(() -> {
            if (context.overworld() != null) {
                context.provider().linkProvider().unlink(context.overworld(), context.world());
            }
            return context.provider().levelView().regenerateAsync(
                    context.world(), false,
                    builder -> builder.seed(new java.util.Random().nextLong()));
        }).thenCompose(result -> {
            if (result == net.thenextlvl.worlds.api.view.LevelView.DeletionResult.SUCCESS) {
                return CompletableFuture.completedFuture(null);
            }
            return onGlobalRegionThread(() ->
                            context.provider().levelView()
                                    .cancelScheduledRegeneration(context.world()))
                    .thenCompose(ignored -> CompletableFuture.failedFuture(
                            new IllegalStateException(
                                    "Dimension regeneration returned " + result)));
        })
                .thenCompose(ignored -> onGlobalRegionThreadAsync(() -> {
                    World regenerated = getWorld(dimension.worldKey(homeland));
                    if (regenerated != null) {
                        return CompletableFuture.completedFuture(new DimensionWorldContext(
                                context.provider(), context.overworld(), regenerated,
                                context.path()));
                    }
                    return dimensionBuilder(context.provider(), homeland, dimension)
                            .build().createAsync()
                            .thenApply(world -> new DimensionWorldContext(
                                    context.provider(), context.overworld(), world,
                                    context.path()));
                }));
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

    private WorldsProvider requireProvider() {
        WorldsProvider provider = plugin.getWorldsProvider();
        if (provider == null) throw new IllegalStateException("Worlds provider is unavailable");
        return provider;
    }

    private World getWorld(String worldKey) {
        return Bukkit.getWorld(new NamespacedKey("simplehomeland", worldKey));
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

    private record HomelandDeletionPlan(WorldsProvider provider, World overworld,
                                         List<World> loadedWorlds, List<Path> unloadedPaths) {
    }

    private record WorldCleanupTask(String worldKey) {
    }

    private record DimensionWorldContext(WorldsProvider provider, World overworld,
                                         World world, Path path) {
    }

    private record OverworldResetContext(
            WorldsProvider provider, World overworld, World nether, World end) {
    }

    private record OverworldCreation(OverworldResetContext context, World world) {
    }

    private record ConfiguredOverworld(OverworldCreation creation, int spawnY) {
    }
}
