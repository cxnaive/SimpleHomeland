package dev.user.homeland.integration;

import dev.user.homeland.SimpleHomelandPlugin;
import dev.user.homeland.manager.HomelandManager;
import dev.user.homeland.model.Homeland;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/** Actual HuskHomes commands, first-use import, occupied retention, idle unload and re-entry. */
final class HuskHomesLazyCycleProbe implements Listener {
    private static final UUID OWNER = UUID.fromString("8f12bcde-0000-0000-0000-000000000001");
    private final JavaPlugin probe;
    private final SimpleHomelandPlugin plugin;
    private final HomelandManager manager;
    private final boolean restarted = Boolean.getBoolean("homeland.probe.closed-cycle-restart");
    private final boolean stopAfterImport = Boolean.getBoolean("homeland.probe.stop-after-import");
    private final AtomicBoolean failed = new AtomicBoolean();
    private final AtomicBoolean joined = new AtomicBoolean();
    private final AtomicInteger loads = new AtomicInteger();
    private final AtomicInteger unloads = new AtomicInteger();
    private final List<Target> targets = new ArrayList<>();
    private volatile Player player;
    private volatile String phase = "等待测试客户端";
    private Map<String, FileTime> completionTimes;
    private int commands;

    HuskHomesLazyCycleProbe(JavaPlugin probe, SimpleHomelandPlugin plugin) {
        this.probe = probe;
        this.plugin = plugin;
        this.manager = plugin.getHomelandManager();
    }

    void start() {
        Bukkit.getPluginManager().registerEvents(this, probe);
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(probe, task -> {
            if (failed.get()) { task.cancel(); return; }
            probe.getLogger().info("PROBE_CYCLE_PROGRESS " + phase + " commands=" + commands
                    + " loads=" + loads.get() + " unloads=" + unloads.get());
        }, 400L, 400L);
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(probe, task -> {
            if (!manager.isWorldAccessStateReady()) return;
            task.cancel();
            CompletableFuture.runAsync(this::prepareTargets).whenComplete((ignored, error) -> {
                if (error != null) fail(error);
                else probe.getLogger().info("PROBE_CYCLE_CLIENT_READY worlds=" + targets.size());
            });
        }, 100L, 20L);
    }

    private void prepareTargets() {
        try {
            require(!plugin.getConfigManager().isPreloadWorlds(), "Preload must be disabled");
            require(!plugin.getConfigManager().isMigrateWorldsOnStartup(), "Startup migration must be disabled");
            require(plugin.getConfigManager().getAutoUnloadSeconds() == 20, "Set idle timeout to 20 seconds for this test");
            List<String> names = List.of("MigrationDefault", "MigrationFlat", "MigrationVoid");
            for (String name : names) {
                Homeland home = manager.findHomeland(OWNER, name).orElseThrow();
                // Start with a child alone: its clock must initialize the owning homeland's timer.
                for (String suffix : List.of("_nether", "_the_end", "")) {
                    String key = home.getWorldKey() + suffix;
                    UUID uuid = UUID.fromString(Files.readString(Path.of("probe-" + key + ".uuid")));
                    String oldName = home.getName() + (suffix.equals("_nether") ? " - 地狱"
                            : suffix.equals("_the_end") ? " - 末地" : "");
                    Target target = new Target(home.getWorldKey(), key, uuid, oldName, "lazycycle" + targets.size());
                    require(Bukkit.getWorld(uuid) == null, "World was already loaded: " + key);
                    require(Files.isDirectory(target.directory()) == restarted, "Unexpected migration state: " + key);
                    targets.add(target);
                }
            }
            if (restarted) completionTimes = readCompletionTimes();
        } catch (Exception error) {
            throw new java.util.concurrent.CompletionException(error);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!event.getPlayer().getName().equals("MigrationOwner") || !joined.compareAndSet(false, true)) return;
        player = event.getPlayer();
        player.addAttachment(probe, "simplehomeland.admin", true);
        player.setOp(true); // Isolated fixture only; enables real /home and /warp commands.
        player.setGameMode(GameMode.CREATIVE);
        player.setFlying(true);
        player.getScheduler().runDelayed(probe, task -> {
            if (targets.size() != 9) { fail(new AssertionError("Probe was not ready before client login")); return; }
            phase = "写入旧世界名和原 UUID 的 HuskHomes home/warp";
            seedSavedPositions().whenComplete((ignored, error) -> {
                if (error != null) fail(error);
                else visit(0, restarted);
            });
        }, () -> fail(new AssertionError("Client retired")), 60L);
    }

    private CompletableFuture<Void> seedSavedPositions() {
        if (restarted) return CompletableFuture.completedFuture(null);
        try {
            var husk = Bukkit.getPluginManager().getPlugin("HuskHomes");
            require(husk != null && husk.isEnabled(), "HuskHomes is unavailable");
            ClassLoader loader = husk.getClass().getClassLoader();
            Class<?> apiType = loader.loadClass("net.william278.huskhomes.api.HuskHomesAPI");
            Object api = apiType.getMethod("getInstance").invoke(null);
            Object user = apiType.getMethod("adaptUser", Player.class).invoke(api, player);
            Class<?> userType = loader.loadClass("net.william278.huskhomes.user.User");
            Class<?> worldType = loader.loadClass("net.william278.huskhomes.position.World");
            Class<?> positionType = loader.loadClass("net.william278.huskhomes.position.Position");
            String server = (String) apiType.getMethod("getServer").invoke(api);
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (Target target : targets) {
                Object oldWorld = worldType.getMethod("from", String.class, UUID.class)
                        .invoke(null, target.oldName(), target.uuid());
                Object position = positionType.getMethod("at", double.class, double.class, double.class,
                                float.class, float.class, worldType, String.class)
                        .invoke(null, 8.5, 82.0, 8.5, 40f, 20f, oldWorld, server);
                chain = chain.thenCompose(ignored -> invokeFuture(api, "createHome",
                                new Class<?>[]{userType, String.class, positionType,
                                        boolean.class, boolean.class, boolean.class, boolean.class},
                                user, target.alias(), position, true, false, true, true))
                        .thenCompose(ignored -> invokeFuture(api, "createWarp",
                                new Class<?>[]{String.class, positionType}, target.alias(), position))
                        .thenApply(ignored -> null);
            }
            return chain;
        } catch (Exception error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private CompletableFuture<?> invokeFuture(Object instance, String method, Class<?>[] types, Object... args) {
        try {
            return (CompletableFuture<?>) instance.getClass().getMethod(method, types).invoke(instance, args);
        } catch (Exception error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private void visit(int index, boolean reloaded) {
        if (failed.get()) return;
        if (index == targets.size()) {
            if (stopAfterImport && !restarted) { stopWithNewlyMigratedWorlds(); return; }
            if (restarted) { finish(); return; }
            leave(() -> awaitIdleUnloads(reloaded ? this::finish : this::afterFirstUnload));
            return;
        }
        Target target = targets.get(index);
        String command = ((index + (reloaded ? 1 : 0)) % 2 == 0 ? "home " : "warp ") + target.alias();
        phase = (reloaded ? "卸载后再次传送" : "首次命令触发旧世界转换") + ": " + command;
        long commandAt = System.currentTimeMillis();
        player.getScheduler().execute(probe, () -> {
            try {
                require(player.performCommand("huskhomes:" + command), "Command was not registered: " + command);
                commands++;
                awaitArrival(target, commandAt, () -> verifyContent(target, reloaded, () -> {
                    probe.getLogger().info("PROBE_CYCLE_COMMAND_PASS /" + command + " world=" + target.key()
                            + " uuid=" + target.uuid() + " reloaded=" + reloaded);
                    if (!reloaded && !stopAfterImport && index == 0) {
                        phase = "玩家停留在仅加载的地狱，跨过自动卸载检查";
                        player.getScheduler().runDelayed(probe, task -> {
                            try {
                                require(player.getWorld().getUID().equals(target.uuid())
                                                && Bukkit.getWorld(target.uuid()) != null && unloads.get() == 0,
                                        "Occupied child world was unloaded");
                                probe.getLogger().info("PROBE_CYCLE_OCCUPIED_PASS");
                                visit(index + 1, false);
                            } catch (Throwable error) { fail(error); }
                        }, () -> fail(new AssertionError("Client retired")), 1300L);
                    } else visit(index + 1, reloaded);
                }));
            } catch (Throwable error) { fail(error); }
        }, () -> fail(new AssertionError("Client retired")), 1L);
    }

    private void awaitArrival(Target target, long startedAt, Runnable next) {
        player.getScheduler().runAtFixedRate(probe, task -> {
            try {
                if (failed.get()) { task.cancel(); return; }
                if (!player.getWorld().getUID().equals(target.uuid())) {
                    require(System.currentTimeMillis() - startedAt < 30_000, "Command timed out: " + target.alias());
                    return;
                }
                task.cancel();
                Location actual = player.getLocation();
                require(Math.abs(actual.getX() - 8.5) < .01 && Math.abs(actual.getY() - 82) < .01
                                && Math.abs(actual.getZ() - 8.5) < .01
                                && Math.abs(actual.getYaw() - 40) < .01 && Math.abs(actual.getPitch() - 20) < .01,
                        "Saved coordinates/rotation changed: " + actual);
                var field = HomelandManager.class.getDeclaredField("lastPlayerPresentTime");
                field.setAccessible(true);
                @SuppressWarnings("unchecked") Map<String, Long> times = (Map<String, Long>) field.get(manager);
                require(times.getOrDefault(target.base(), 0L) >= startedAt,
                        "Child load did not refresh the homeland idle timer: " + target.key());
                require(!times.containsKey(target.base() + "_nether") && !times.containsKey(target.base() + "_the_end"),
                        "Child world wrote a separate idle timer");
                next.run();
            } catch (Throwable error) { task.cancel(); fail(error); }
        }, () -> fail(new AssertionError("Client retired")), 1L, 5L);
    }

    private void verifyContent(Target target, boolean reloaded, Runnable next) {
        World world = Bukkit.getWorld(target.uuid());
        Bukkit.getRegionScheduler().execute(probe, world, 0, 0, () -> {
            try {
                require(world.key().asString().equals("homelands:" + target.key()), "Wrong world key");
                require(world.getWorldPath().toAbsolutePath().normalize()
                        .equals(target.directory().toAbsolutePath().normalize()), "Wrong active world path");
                require(world.getBlockAt(8, 80, 8).getType() == Material.DIAMOND_BLOCK, "Original block lost");
                Chest chest = (Chest) world.getBlockAt(9, 80, 8).getState();
                ItemStack original = chest.getBlockInventory().getItem(0);
                require(original != null && original.getType() == Material.EMERALD && original.getAmount() == 17,
                        "Original container items lost");
                require(world.getNearbyEntities(new Location(world, 10.5, 81, 8.5), 3, 3, 3).stream()
                        .anyMatch(entity -> "migration-entity".equals(entity.getCustomName())), "Original entity lost");
                if (reloaded) {
                    require(world.getBlockAt(11, 80, 8).getType() == Material.GOLD_BLOCK, "Unload did not save changed block");
                    ItemStack saved = chest.getBlockInventory().getItem(1);
                    require(saved != null && saved.getType() == Material.DIAMOND && saved.getAmount() == 3,
                            "Unload did not save changed inventory");
                } else {
                    world.getBlockAt(11, 80, 8).setType(Material.GOLD_BLOCK);
                    chest.getBlockInventory().setItem(1, new ItemStack(Material.DIAMOND, 3));
                }
                next.run();
            } catch (Throwable error) { fail(error); }
        });
    }

    private void leave(Runnable next) {
        player.getScheduler().execute(probe, () -> player.teleportAsync(Bukkit.getWorlds().getFirst().getSpawnLocation())
                .whenComplete((success, error) -> {
                    if (error != null || !Boolean.TRUE.equals(success)) fail(new AssertionError("Could not leave homeland", error));
                    else next.run();
                }), () -> fail(new AssertionError("Client retired")), 1L);
    }

    private void awaitIdleUnloads(Runnable next) {
        phase = "等待生产定时任务保存并自动卸载所有家园维度";
        long startedAt = System.currentTimeMillis();
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(probe, task -> {
            try {
                if (failed.get()) { task.cancel(); return; }
                require(System.currentTimeMillis() - startedAt < 150_000, "Automatic idle unload timed out");
                if (targets.stream().anyMatch(target -> Bukkit.getWorld(target.uuid()) != null)) return;
                task.cancel();
                probe.getLogger().info("PROBE_CYCLE_AUTO_UNLOAD_PASS worlds=9 unloadEvents=" + unloads.get());
                next.run();
            } catch (Throwable error) { task.cancel(); fail(error); }
        }, 20L, 20L);
    }

    private void afterFirstUnload() {
        CompletableFuture.supplyAsync(() -> {
            try { return readCompletionTimes(); }
            catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
        }).whenComplete((times, error) -> {
            if (error != null) fail(error);
            else { completionTimes = times; visit(0, true); }
        });
    }

    private Map<String, FileTime> readCompletionTimes() throws Exception {
        Map<String, FileTime> result = new LinkedHashMap<>();
        for (Target target : targets) {
            require(Files.exists(target.directory()), "Active directory was deleted on unload");
            require(Files.exists(Path.of("homelands", target.key(), "level.dat")), "Legacy backup was deleted");
            Path journal = Path.of("plugins/SimpleHomeland/world-migration");
            require(!Files.exists(journal.resolve(target.key() + ".pending")), "Migration remained pending");
            result.put(target.key(), Files.getLastModifiedTime(journal.resolve(target.key() + ".complete")));
        }
        return result;
    }

    private void finish() {
        phase = "校验未重复转换及磁盘保存结果";
        CompletableFuture.runAsync(() -> {
            try {
                require(completionTimes.equals(readCompletionTimes()), "Re-entry imported the legacy backup again");
                require(commands == (restarted ? 9 : 18), "Wrong command count: " + commands);
                require(loads.get() == (restarted ? 9 : 18), "Unexpected extra loaded worlds: " + loads.get());
                if (!restarted) require(unloads.get() == 18, "Worlds were not all automatically unloaded twice");
                probe.getLogger().info((restarted ? "PROBE_CLOSED_CYCLE_RESTART_PASS" : "PROBE_CLOSED_CYCLE_PASS")
                        + " commands=" + commands + " worlds=9 loads=" + loads.get() + " unloads=" + unloads.get());
                Bukkit.shutdown();
            } catch (Throwable error) { fail(error); }
        });
    }

    private void stopWithNewlyMigratedWorlds() {
        phase = "刚转换并修改后直接停服，所有家园世界保持加载";
        CompletableFuture.runAsync(() -> {
            try {
                require(commands == 9 && loads.get() == 9 && unloads.get() == 0,
                        "Shutdown test must keep all newly migrated worlds loaded");
                require(targets.stream().allMatch(target -> Bukkit.getWorld(target.uuid()) != null),
                        "Newly migrated world was already unloaded before shutdown");
                readCompletionTimes();
                probe.getLogger().info("PROBE_STOP_AFTER_IMPORT_READY worlds=9 loads=9 unloads=0");
                // Normal server shutdown must save the changed blocks/inventories; no manual save/unload here.
                Bukkit.shutdown();
            } catch (Throwable error) { fail(error); }
        });
    }

    @EventHandler
    public void onLoad(WorldLoadEvent event) {
        if (event.getWorld().key().namespace().equals("homelands")) loads.incrementAndGet();
    }

    @EventHandler
    public void onUnload(WorldUnloadEvent event) {
        if (event.getWorld().key().namespace().equals("homelands")) unloads.incrementAndGet();
    }

    private void fail(Throwable error) {
        if (!failed.compareAndSet(false, true)) return;
        probe.getLogger().log(Level.SEVERE, "PROBE_CLOSED_CYCLE_FAILED phase=" + phase, error);
        Bukkit.shutdown();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Target(String base, String key, UUID uuid, String oldName, String alias) {
        Path directory() { return Path.of("world/dimensions/homelands", key); }
    }
}
