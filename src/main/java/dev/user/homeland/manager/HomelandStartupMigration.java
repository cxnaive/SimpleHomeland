package dev.user.homeland.manager;

import dev.user.homeland.SimpleHomelandPlugin;
import dev.user.homeland.model.Homeland;
import dev.user.homeland.model.HomelandDimension;
import dev.user.homeland.manager.HomelandWorldService.UnloadFailure;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

/** Optional startup importer. Only one world is loaded at a time, then saved and unloaded. */
final class HomelandStartupMigration {
    private final SimpleHomelandPlugin plugin;
    private final HomelandManager manager;
    private final HomelandWorldService service;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final long startedAt = System.nanoTime();
    private volatile ScheduledTask heartbeat;
    private volatile String current = "扫描旧家园目录";
    private volatile int total;
    private volatile int completed;
    private volatile int failed;

    HomelandStartupMigration(SimpleHomelandPlugin plugin, HomelandManager manager,
                              HomelandWorldService service) {
        this.plugin = plugin;
        this.manager = manager;
        this.service = service;
    }

    void start() {
        plugin.getLogger().info("已启用启动全量迁移，开始检查家园目录；旧目录将保留");
        heartbeat = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> logProgress(), 200L, 200L);
        CompletableFuture.supplyAsync(() -> plan(manager.startupHomelands(),
                        job -> service.needsStartupMigration(job.key(),
                                job.homeland().getWorldUuid(job.dimension()) == null), cancelled::get))
                .thenCompose(jobs -> {
                    total = jobs.size();
                    plugin.getLogger().info("启动迁移检查完成: " + total + " 个维度需要处理");
                    return runSerial(jobs, this::migrate, cancelled::get, this::recordResult);
                }).whenComplete((ignored, error) -> {
                    if (cancelled.get()) return;
                    Bukkit.getGlobalRegionScheduler().execute(plugin, () -> finish(error));
                });
    }

    static List<Job> plan(Collection<Homeland> homelands, Predicate<Job> needsMigration,
                          BooleanSupplier cancelled) {
        List<Job> jobs = new ArrayList<>();
        for (Homeland homeland : homelands.stream().sorted(Comparator.comparingInt(Homeland::getId)).toList()) {
            if (cancelled.getAsBoolean()) break;
            // The reset journal owns these worlds; recovery runs after this migration phase.
            if (homeland.isOverworldResetPending()) continue;
            for (HomelandDimension dimension : HomelandDimension.values()) {
                if (!dimension.isUnlocked(homeland)) continue;
                Job job = new Job(homeland, dimension.worldKey(homeland), dimension);
                if (needsMigration.test(job)) jobs.add(job);
            }
        }
        return List.copyOf(jobs);
    }

    static CompletableFuture<Void> runSerial(List<Job> jobs,
                                              Function<Job, CompletableFuture<Void>> migrate,
                                              BooleanSupplier cancelled,
                                              BiConsumer<Job, Throwable> record) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (Job job : jobs) {
            // Async continuation prevents deep recursion for large queues and keeps IO off tick threads.
            chain = chain.thenComposeAsync(ignored -> {
                if (cancelled.getAsBoolean()) return CompletableFuture.completedFuture(null);
                CompletableFuture<Void> operation;
                try {
                    operation = migrate.apply(job);
                } catch (Throwable error) {
                    operation = CompletableFuture.failedFuture(error);
                }
                return operation.handle((result, error) -> {
                    Throwable cause = error == null ? null : HomelandWorldOperations.unwrap(error);
                    record.accept(job, cause);
                    // Continuing after failed unload could leave thousands of worlds resident.
                    if (cause instanceof UnloadFailure) throw new CompletionException(cause);
                    return null;
                });
            });
        }
        return chain;
    }

    private CompletableFuture<Void> migrate(Job job) {
        current = job.key() + "（复制 / 转换）";
        plugin.getLogger().info("开始转换 [" + (completed + failed + 1) + "/" + total + "]: " + job.key());
        if (manager.getHomelandWorld(job.key()) != null) {
            return CompletableFuture.failedFuture(new UnloadFailure("待迁移世界已被其他插件加载: " + job.key()));
        }
        return service.load(job.key(), job.homeland(), cancelled::get).thenCompose(world -> {
            current = job.key() + "（写入 UUID / 保存并卸载）";
            CompletableFuture<Void> persist = cancelled.get()
                    ? CompletableFuture.failedFuture(new IOException("启动迁移已停止"))
                    : manager.updateWorldUuid(job.homeland(), job.key(), world.getUID());
            // Even if the DB write fails, unload before attempting the next world.
            return persist.handle((ignored, error) -> error).thenCompose(persistError ->
                    service.unloadAfterMigration(world).handle((ignored, unloadError) -> {
                        if (unloadError != null) {
                            throw new CompletionException(new UnloadFailure(
                                    "无法卸载已转换世界: " + job.key(), unloadError));
                        }
                        if (persistError != null) throw new CompletionException(persistError);
                        return null;
                    }));
        });
    }

    private void recordResult(Job job, Throwable error) {
        if (cancelled.get()) return;
        if (error == null) {
            completed++;
            plugin.getLogger().info("转换完成 [" + (completed + failed) + "/" + total + "]: "
                    + job.key() + "，已保存并卸载");
        } else {
            failed++;
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "家园转换失败: " + job.key() + " - " + error.getMessage(), error);
        }
    }

    private void logProgress() {
        int processed = completed + failed;
        String progress = total == 0 ? "扫描中" : processed + "/" + total
                + " (" + (100L * processed / total) + "%)";
        plugin.getLogger().info("启动迁移进度: " + progress + "，成功 " + completed + "，失败 "
                + failed + "，当前 " + current + "，耗时 " + elapsedSeconds() + " 秒");
    }

    private void finish(Throwable error) {
        if (heartbeat != null) heartbeat.cancel();
        if (cancelled.get()) return;
        if (error != null) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "启动迁移提前停止，剩余 " + (total - completed - failed) + " 个维度未处理",
                    HomelandWorldOperations.unwrap(error));
        }
        plugin.getLogger().info("启动迁移结束: 成功 " + completed + "，失败 " + failed
                + "，总计 " + total + "，耗时 " + elapsedSeconds() + " 秒；旧目录保留");
        manager.finishStartupMigration();
    }

    private long elapsedSeconds() { return (System.nanoTime() - startedAt) / 1_000_000_000L; }

    void shutdown() {
        cancelled.set(true);
        if (heartbeat != null) heartbeat.cancel();
    }

    record Job(Homeland homeland, String key, HomelandDimension dimension) {}

}
