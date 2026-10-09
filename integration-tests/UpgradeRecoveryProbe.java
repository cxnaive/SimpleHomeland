package dev.user.homeland.integration;

import dev.user.homeland.SimpleHomelandPlugin;
import dev.user.homeland.manager.HomelandManager;
import dev.user.homeland.manager.HomelandStore;
import dev.user.homeland.model.Homeland;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Fault injection against a disposable copy of the nine-world migration fixture. */
final class UpgradeRecoveryProbe implements Listener {
    private final JavaPlugin probe;
    private final SimpleHomelandPlugin plugin;
    private final HomelandManager manager;
    private final String faultKey = "homeland_8f12bcde_1791567572923_the_end";
    private final CompletableFuture<Void> concurrentLoad = new CompletableFuture<>();
    private boolean inject;
    private boolean refuseUnload;

    UpgradeRecoveryProbe(JavaPlugin probe, SimpleHomelandPlugin plugin) {
        this.probe = probe;
        this.plugin = plugin;
        this.manager = plugin.getHomelandManager();
    }

    void start() {
        Bukkit.getPluginManager().registerEvents(this, probe);
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(probe, task -> {
            if (!manager.isWorldAccessStateReady()) return;
            task.cancel();
            CompletableFuture<Void> checks = Boolean.getBoolean("homeland.probe.upgrade-recovery-restart")
                    ? restartPendingMigration() : partialReset().thenCompose(ignored -> migrationFailure());
            checks
                    .whenComplete((ignored, error) -> {
                        refuseUnload = false;
                        if (error != null) probe.getLogger().log(java.util.logging.Level.SEVERE,
                                "PROBE_UPGRADE_RECOVERY_FAILED", error);
                        else probe.getLogger().info("PROBE_UPGRADE_RECOVERY_PASS");
                        Bukkit.getGlobalRegionScheduler().execute(probe, Bukkit::shutdown);
                    });
        }, 20L, 20L);
    }

    private CompletableFuture<Void> restartPendingMigration() {
        require(!plugin.getWorldService().isReady(faultKey), "Pending journal not restored on startup");
        Homeland home = manager.getHomelandByWorldKey(faultKey);
        // Bypass the homeland loader to emulate a third-party plugin exposing a
        // pending world in Bukkit. Its disk journal must still block entry.
        return plugin.getWorldService().builder(faultKey, home.getWorldType()).build().create()
                .thenCompose(world -> {
                    require(manager.isWorldOperationPending(faultKey), "Third-party load bypassed pending journal");
                    require(!manager.isHomelandWorldLoaded(faultKey), "Pending world advertised as ready");
                    return mustFailLoad().thenCompose(ignored -> plugin.getWorldsAccess().unload(world, false));
                }).thenCompose(unloaded -> {
                    require(unloaded, "Could not unload third-party fixture");
                    return load(faultKey);
                }).thenAccept(world -> {
                    require(plugin.getWorldService().isReady(faultKey), "Retry failed to clear pending state");
                    require(!Files.exists(journal().resolve(faultKey + ".pending")), "Retry retained pending journal");
                    probe.getLogger().info("PROBE_PENDING_MIGRATION_RESTART_PASS");
                });
    }

    private CompletableFuture<Void> partialReset() {
        Homeland home = manager.getHomelandByWorldKey("homeland_8f12bcde_1791567461566");
        return load(home.getWorldKey()).thenCompose(world -> onRegion(world, () -> {
            require(world.getBlockAt(8, 80, 8).getType() == Material.DIAMOND_BLOCK, "Original marker missing");
        }).thenCompose(ignored -> plugin.getWorldsAccess().unload(world, true))
                .thenCompose(unloaded -> {
                    require(unloaded, "Could not unload reset fixture");
                    return prepareReset(home, world.getUID(), null);
                }).thenRunAsync(() -> {
                    try {
                        Path level = plugin.getWorldService().directory(home.getWorldKey());
                        Files.move(level.resolve("data"), level.resolve("probe-data-backup"));
                        require(Files.isDirectory(level.resolve("region")), "Old regions missing");
                    } catch (Exception error) { throw new RuntimeException(error); }
                }).thenCompose(ignored -> resume(home)).thenCompose(ignored -> {
                    World replacement = manager.getHomelandWorld(home.getWorldKey());
                    return onRegion(replacement, () -> {
                        require(!home.isOverworldResetPending(), "Reset is still pending");
                        require(!world.getUID().equals(replacement.getUID()), "Reset UUID unchanged");
                        require(replacement.getBlockAt(8, 80, 8).getType() != Material.DIAMOND_BLOCK,
                                "Reset accepted old terrain as completed");
                        replacement.getBlockAt(8, 80, 8).setType(Material.GOLD_BLOCK);
                        probe.getLogger().info("PROBE_PARTIAL_RESET_PASS");
                    }).thenCompose(done -> plugin.getWorldsAccess().save(replacement, true))
                            .thenCompose(saved -> {
                                require(saved, "Could not save generated replacement");
                                return prepareReset(home, world.getUID(), replacement.getUID());
                            }).thenCompose(done -> plugin.getWorldsAccess().unload(replacement, true))
                            .thenCompose(unloaded -> {
                                require(unloaded, "Could not unload generated replacement");
                                return resume(home);
                            }).thenCompose(done -> onRegion(manager.getHomelandWorld(home.getWorldKey()), () -> {
                                World recovered = manager.getHomelandWorld(home.getWorldKey());
                                require(replacement.getUID().equals(recovered.getUID()), "Generated world reset twice");
                                require(recovered.getBlockAt(8, 80, 8).getType() == Material.GOLD_BLOCK,
                                        "Generated replacement lost on recovery");
                                require(!home.isOverworldResetPending(), "Finish recovery still pending");
                                probe.getLogger().info("PROBE_GENERATED_RESET_RECOVERY_PASS");
                            }));
                }));
    }

    private CompletableFuture<Void> prepareReset(Homeland home, UUID previous, UUID generated) {
        return plugin.getDatabaseQueue().submitFuture("probe-reset-stage", connection -> {
            try (var statement = connection.prepareStatement("UPDATE homeland SET world_uuid = ?, world_type = 'flat', "
                    + "overworld_reset_pending = TRUE, overworld_reset_previous_uuid = ? WHERE id = ?")) {
                statement.setString(1, generated == null ? null : generated.toString());
                statement.setString(2, previous.toString());
                statement.setInt(3, home.getId());
                require(statement.executeUpdate() == 1, "Fixture row missing");
            }
            home.setWorldType("flat");
            home.setOverworldResetPending(true);
            home.setOverworldResetPreviousUuid(previous);
            try {
                var field = HomelandManager.class.getDeclaredField("homelandStore");
                field.setAccessible(true);
                ((HomelandStore) field.get(manager)).updateWorldUuid(home, generated);
            } catch (ReflectiveOperationException error) { throw new java.sql.SQLException(error); }
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<Void> resume(Homeland home) {
        try {
            var field = HomelandManager.class.getDeclaredField("worldOperations");
            field.setAccessible(true);
            Object operations = field.get(manager);
            var method = operations.getClass().getDeclaredMethod("resumeOverworldReset", Homeland.class);
            method.setAccessible(true);
            return ((CompletionStage<Void>) method.invoke(operations, home)).toCompletableFuture();
        } catch (Exception error) { return CompletableFuture.failedFuture(error); }
    }

    private CompletableFuture<Void> migrationFailure() {
        return CompletableFuture.runAsync(() -> {
            try {
                Path level = plugin.getWorldService().directory(faultKey);
                Path journal = journal();
                Files.move(level, level.resolveSibling(faultKey + "_probe_backup"));
                Files.move(journal.resolve(faultKey + ".complete"), journal.resolve(faultKey + ".probe_backup"));
                Path staging = journal.resolve("staging").resolve(faultKey);
                if (Files.exists(staging)) Files.move(staging, staging.resolveSibling(faultKey + "_probe_backup"));
                inject = true;
                refuseUnload = true;
            } catch (Exception error) { throw new RuntimeException(error); }
        }).thenCompose(ignored -> mustFailLoad()).thenCompose(ignored -> concurrentLoad)
                .thenCompose(ignored -> {
                    require(manager.getHomelandWorld(faultKey) != null, "Unload refusal did not retain world");
                    require(manager.isWorldOperationPending(faultKey), "Failed world exposed for entry");
                    require(!manager.isHomelandWorldLoaded(faultKey), "Failed world reported ready");
                    return mustFailLoad();
                }).thenCompose(ignored -> {
                    probe.getLogger().info("PROBE_FAILED_MIGRATION_BLOCKED_PASS");
                    refuseUnload = false;
                    return plugin.getWorldsAccess().unload(manager.getHomelandWorld(faultKey), false);
                }).thenRunAsync(() -> {
                    try {
                        Files.move(journal().resolve(faultKey + ".complete"), journal().resolve(faultKey + ".blocked_backup"));
                    } catch (Exception error) { throw new RuntimeException(error); }
                }).thenCompose(ignored -> load(faultKey)).thenCompose(world -> onRegion(world, () -> {
                    require(!manager.isWorldOperationPending(faultKey), "Successful migration still blocked");
                    require(manager.isHomelandWorldLoaded(faultKey), "Successful migration not ready");
                    require(world.getBlockAt(8, 80, 8).getType() == Material.DIAMOND_BLOCK, "Legacy marker lost");
                    require(!Files.exists(journal().resolve(faultKey + ".pending")), "Migration journal still pending");
                    probe.getLogger().info("PROBE_FAILED_MIGRATION_RETRY_PASS");
                }));
    }

    @EventHandler public void onLoad(WorldLoadEvent event) {
        if (!inject || !event.getWorld().key().value().equals(faultKey)) return;
        try {
            require(manager.isWorldOperationPending(faultKey), "WorldLoad exposes unfinished migration");
            require(!manager.isHomelandWorldLoaded(faultKey), "WorldLoad reports unfinished migration ready");
            manager.loadHomelandWorldByKey(faultKey, world -> concurrentLoad.completeExceptionally(
                    new AssertionError("Concurrent load bypassed migration")), error -> concurrentLoad.complete(null));
            Path blocked = journal().resolve(faultKey + ".complete");
            Files.createDirectory(blocked);
            Files.writeString(blocked.resolve("fault"), "Injected journal write failure");
            inject = false;
        } catch (Exception error) { throw new RuntimeException(error); }
    }

    @EventHandler public void onUnload(WorldUnloadEvent event) {
        if (refuseUnload && event.getWorld().key().value().equals(faultKey)) event.setCancelled(true);
    }

    private Path journal() { return plugin.getDataFolder().toPath().resolve("world-migration"); }
    private CompletableFuture<World> load(String key) {
        CompletableFuture<World> result = new CompletableFuture<>();
        manager.loadHomelandWorldByKey(key, result::complete, result::completeExceptionally);
        return result;
    }
    private CompletableFuture<Void> mustFailLoad() {
        return load(faultKey).handle((world, error) -> {
            require(error != null && world == null, "Unfinished migration returned SUCCESS");
            return null;
        });
    }
    private CompletableFuture<Void> onRegion(World world, Runnable check) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Bukkit.getRegionScheduler().execute(probe, world, 0, 0, () -> {
            try { check.run(); result.complete(null); }
            catch (Throwable error) { result.completeExceptionally(error); }
        });
        return result;
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
