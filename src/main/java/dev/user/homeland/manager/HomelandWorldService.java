package dev.user.homeland.manager;

import dev.user.homeland.SimpleHomelandPlugin;
import dev.user.homeland.model.Homeland;
import dev.user.homeland.model.HomelandDimension;
import net.thenextlvl.worlds.Dimension;
import net.thenextlvl.worlds.Level;
import net.thenextlvl.worlds.WorldsAccess;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;

/** Worlds 4 lifecycle and the one-time, UUID-preserving Worlds 3 import. */
public final class HomelandWorldService {
    private final SimpleHomelandPlugin plugin;
    private final WorldsAccess worlds;
    private final HomelandWorldMigration migration;

    public HomelandWorldService(SimpleHomelandPlugin plugin) {
        this.plugin = plugin;
        this.worlds = plugin.getWorldsAccess();
        this.migration = new HomelandWorldMigration(Bukkit.getWorldContainer().toPath(),
                worlds.getDimensionsRoot(), plugin.getDataFolder().toPath().resolve("world-migration"));
    }

    public Path directory(String key) {
        return migration.target(HomelandWorldKeys.normalize(key));
    }

    public UUID legacyUuid(String key) throws IOException {
        return migration.legacyUuid(key);
    }

    /** Checks legacy import/retry, or UUID persistence left unfinished after a previous import. */
    boolean needsStartupMigration(String key, boolean missingUuid) {
        return migration.needsMigration(HomelandWorldKeys.normalize(key))
                || (missingUuid && Files.exists(directory(key)));
    }

    public Level.Builder builder(String key, String worldType) {
        var builder = Level.builder(HomelandWorldKeys.key(key)).dimension(dimension(key));
        if (HomelandWorldKeys.dimension(key) == HomelandDimension.OVERWORLD) {
            HomelandWorldSettings.applyWorldTypeGenerator(plugin, builder, worldType);
        }
        return builder;
    }

    public CompletableFuture<World> load(String key, Homeland homeland) {
        return load(key, homeland, () -> !plugin.isEnabled());
    }

    CompletableFuture<World> load(String key, Homeland homeland, BooleanSupplier cancelled) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return migration.prepare(key, cancelled);
            } catch (IOException error) {
                throw new CompletionException(error);
            }
        }).thenCompose(preparation -> {
            if (cancelled.getAsBoolean()) return CompletableFuture.failedFuture(
                    new java.io.InterruptedIOException("Homeland migration stopped"));
            var builder = builder(key, homeland.getWorldType());
            if (preparation.legacy()) builder.legacyName(preparation.staging().toString());
            return builder.build().create().thenCompose(world -> {
                UUID expected = preparation.uuid() != null ? preparation.uuid()
                        : homeland.getWorldUuid(HomelandWorldKeys.dimension(key));
                if (!homeland.isOverworldResetPending() && expected != null && !expected.equals(world.getUID())) {
                    return worlds.unload(world, false).thenCompose(ignored ->
                            CompletableFuture.failedFuture(new IOException(
                                    "Migration changed world UUID: " + key + " (expected "
                                            + expected + ", found " + world.getUID() + ")")));
                }
                CompletableFuture<World> finish = CompletableFuture.supplyAsync(() -> {
                    try {
                        migration.complete(key);
                        register(world);
                        return world;
                    } catch (IOException error) {
                        throw new CompletionException(error);
                    }
                });
                return finish.exceptionallyCompose(error -> worlds.unload(world, false)
                        .thenCompose(ignored -> CompletableFuture.failedFuture(error)));
            });
        });
    }

    public CompletableFuture<World> create(Level level) {
        return level.create().thenCompose(world -> CompletableFuture.supplyAsync(() -> {
            try {
                migration.complete(world.key().value());
                register(world);
                return world;
            } catch (IOException error) {
                throw new CompletionException(error);
            }
        }).exceptionallyCompose(error -> worlds.unload(world, false)
                .thenCompose(ignored -> CompletableFuture.failedFuture(error))));
    }

    /** Saves and unloads a world after startup migration while retaining its converted files. */
    CompletableFuture<Void> unloadAfterMigration(World world) {
        return worlds.unload(world, true).thenCompose(success -> {
            if (success) return CompletableFuture.completedFuture(null);
            return CompletableFuture.failedFuture(
                    new IOException("Could not unload migrated world: " + world.getKey()));
        });
    }

    private void register(World world) {
        // SimpleHomeland controls lazy loading and reapplies its generator from the DB.
        // Persisting a generator here would require our POSTWORLD plugin during Worlds STARTUP.
        synchronized (worlds.getWorldRegistry()) {
            var registry = worlds.getWorldRegistry();
            registry.registerIfAbsent(world.key(), dimension(world.key().value()), false, null);
            if (registry.isEnabled(world.key())) registry.setEnabled(world.key(), false);
        }
    }

    public CompletableFuture<Void> delete(String key, World loaded) {
        CompletableFuture<Boolean> unload = loaded == null
                ? CompletableFuture.completedFuture(true) : worlds.unload(loaded, false);
        return unload.thenAcceptAsync(success -> {
            if (!success) throw new CompletionException(new IOException("Could not unload world: " + key));
            try {
                // Retire the legacy backup before removing live data, including never-imported worlds.
                migration.complete(key);
                deleteTree(directory(key));
                synchronized (worlds.getWorldRegistry()) {
                    worlds.getWorldRegistry().unregister(HomelandWorldKeys.key(key));
                }
                worlds.getScheduler().cancel(HomelandWorldKeys.key(key));
            } catch (IOException error) {
                throw new CompletionException(error);
            }
        });
    }

    static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static Dimension dimension(String key) {
        return switch (HomelandWorldKeys.dimension(key)) {
            case OVERWORLD -> Dimension.OVERWORLD;
            case NETHER -> Dimension.THE_NETHER;
            case END -> Dimension.THE_END;
        };
    }
}
