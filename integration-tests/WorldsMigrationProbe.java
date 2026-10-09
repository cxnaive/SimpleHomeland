package dev.user.homeland.integration;

import dev.user.homeland.SimpleHomelandPlugin;
import dev.user.homeland.api.SimpleHomelandAPI;
import dev.user.homeland.api.TeleportResult;
import dev.user.homeland.manager.HomelandManager;
import dev.user.homeland.model.Homeland;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.block.Chest;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** Isolated real-server probe; never install on a production server. */
public final class WorldsMigrationProbe extends JavaPlugin implements Listener {
    private static final UUID OWNER = UUID.fromString("8f12bcde-0000-0000-0000-000000000001");
    private SimpleHomelandPlugin homeland;
    private HomelandManager manager;
    private final List<String> names = List.of("MigrationDefault", "MigrationFlat", "MigrationVoid");
    private int verified;
    private final AtomicInteger migratingLoaded = new AtomicInteger();
    private final AtomicInteger migratingPeak = new AtomicInteger();

    @Override
    public void onEnable() {
        homeland = (SimpleHomelandPlugin) Bukkit.getPluginManager().getPlugin("SimpleHomeland");
        manager = homeland.getHomelandManager();
        Bukkit.getPluginManager().registerEvents(this, this);
        if (Boolean.getBoolean("homeland.probe.upgrade-recovery")
                || Boolean.getBoolean("homeland.probe.upgrade-recovery-restart")) {
            new UpgradeRecoveryProbe(this, homeland).start();
            return;
        }
        if (Boolean.getBoolean("homeland.probe.paper-end-return")) {
            new PaperEndReturnProbe(this, homeland).start();
            return;
        }
        if (Boolean.getBoolean("homeland.probe.closed-cycle")) {
            new HuskHomesLazyCycleProbe(this, homeland).start();
            return;
        }
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> {
            if (!manager.isWorldAccessStateReady()) return;
            task.cancel();
            if (Boolean.getBoolean("homeland.probe.create")) create(0);
            else if (Boolean.getBoolean("homeland.probe.lifecycle")) lifecycle();
            else if (Boolean.getBoolean("homeland.probe.portal")) portal();
            else if (Boolean.getBoolean("homeland.probe.recovery")) recovery();
            else {
                if (Boolean.getBoolean("homeland.probe.startup")) checkStartupMigration();
                if (Boolean.getBoolean("homeland.probe.lazy")) checkLazyMigration();
                verify(0);
            }
        }, 100L, 20L);
    }

    @EventHandler
    public void onMigrationLoad(WorldLoadEvent event) {
        if (!Boolean.getBoolean("homeland.probe.startup") || !manager.isStartupMigrationActive()
                || !event.getWorld().key().namespace().equals("homelands")) return;
        migratingPeak.accumulateAndGet(migratingLoaded.incrementAndGet(), Math::max);
    }

    @EventHandler
    public void onMigrationUnload(WorldUnloadEvent event) {
        if (!Boolean.getBoolean("homeland.probe.startup") || !manager.isStartupMigrationActive()
                || !event.getWorld().key().namespace().equals("homelands")) return;
        migratingLoaded.decrementAndGet();
    }

    private void checkStartupMigration() {
        try {
            for (String name : names) {
                Homeland home = manager.findHomeland(OWNER, name).orElseThrow();
                for (String suffix : List.of("", "_nether", "_the_end")) {
                    String key = home.getWorldKey() + suffix;
                    require(manager.getHomelandWorld(key) == null, "Startup migration retained loaded world: " + key);
                    require(Files.exists(Path.of("world/dimensions/homelands", key)), "Startup target missing: " + key);
                    require(Files.exists(Path.of("plugins/SimpleHomeland/world-migration", key + ".complete")),
                            "Startup migration marker missing: " + key);
                    require(Files.exists(Path.of("homelands", key, "level.dat")), "Legacy backup missing: " + key);
                }
            }
            boolean restarted = Boolean.getBoolean("homeland.probe.startup-restart");
            require(migratingPeak.get() == (restarted ? 0 : 1), "Unexpected migration peak: " + migratingPeak.get());
            require(migratingLoaded.get() == 0, "Startup worlds were not all unloaded");
            getLogger().info("PROBE_STARTUP_MIGRATION_PASS worlds=9 peak=" + migratingPeak.get());
        } catch (Throwable error) {
            failed(error);
            throw new IllegalStateException(error);
        }
    }

    private void checkLazyMigration() {
        try {
            require(!manager.isStartupMigrationActive(), "Default enabled startup migration");
            for (String name : names) {
                Homeland home = manager.findHomeland(OWNER, name).orElseThrow();
                for (String suffix : List.of("", "_nether", "_the_end")) {
                    require(!Files.exists(Path.of("world/dimensions/homelands", home.getWorldKey() + suffix)),
                            "Default migrated before first access");
                }
            }
            getLogger().info("PROBE_DEFAULT_LAZY_PASS");
        } catch (Throwable error) {
            failed(error);
            throw new IllegalStateException(error);
        }
    }

    private void create(int index) {
        if (index == names.size()) {
            getLogger().info("PROBE_OLD_READY");
            Bukkit.shutdown();
            return;
        }
        if (manager.findHomeland(OWNER, names.get(index)).isPresent()) {
            create(index + 1);
            return;
        }
        String type = List.of("default", "flat", "void").get(index);
        manager.createHomelandAdmin(Bukkit.getConsoleSender(), OWNER, names.get(index), type, () -> {
            manager.unlockNetherAdmin(Bukkit.getConsoleSender(), OWNER, names.get(index), () ->
                    manager.unlockEndAdmin(Bukkit.getConsoleSender(), OWNER, names.get(index), () -> {
                        Homeland home = manager.findHomeland(OWNER, names.get(index)).orElseThrow();
                        CompletableFuture<?>[] futures = List.of("", "_nether", "_the_end").stream()
                                .map(suffix -> populate(manager.getHomelandWorld(home.getWorldKey() + suffix)))
                                .toArray(CompletableFuture[]::new);
                        CompletableFuture.allOf(futures).thenRun(() -> create(index + 1));
                    }, this::failed), this::failed);
        }, this::failed);
    }

    private CompletableFuture<Void> populate(World world) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Bukkit.getRegionScheduler().execute(this, world, 0, 0, () -> {
            try {
                world.getBlockAt(8, 80, 8).setType(Material.DIAMOND_BLOCK);
                world.getBlockAt(9, 80, 8).setType(Material.CHEST);
                Chest chest = (Chest) world.getBlockAt(9, 80, 8).getState();
                chest.getBlockInventory().setItem(0, new ItemStack(Material.EMERALD, 17));
                ArmorStand stand = world.spawn(new Location(world, 10.5, 81, 8.5), ArmorStand.class);
                stand.setGravity(false);
                stand.setPersistent(true);
                stand.setCustomName("migration-entity");
                Path identity = Path.of("probe-" + world.getKey().getKey() + ".uuid");
                Files.writeString(identity, world.getUID().toString());
                getLogger().info("PROBE_OLD_WORLD " + world.getKey() + " UUID=" + world.getUID());
                done.complete(null);
            } catch (Throwable error) {
                done.completeExceptionally(error);
                failed(error);
            }
        });
        return done;
    }

    private void verify(int index) {
        if (index == names.size()) {
            getLogger().info("PROBE_MIGRATION_PASS worlds=" + verified);
            if (Boolean.getBoolean("homeland.probe.shutdown")) {
                Bukkit.shutdown();
                return;
            }
            Homeland home = manager.findHomeland(OWNER, names.get(0)).orElseThrow();
            homeland.getWorldsAccess().unload(manager.getHomelandWorld(home.getWorldKey() + "_nether"), true)
                    .thenAccept(success -> {
                        require(success, "Could not unload dimension for API test");
                        getLogger().info("PROBE_API_UNLOADED_READY");
                    });
            return;
        }
        Homeland home = manager.findHomeland(OWNER, names.get(index)).orElseThrow();
        verifyDimension(home, 0).thenRun(() -> verify(index + 1)).exceptionally(error -> {
            failed(error);
            return null;
        });
    }

    private CompletableFuture<Void> verifyDimension(Homeland home, int dimension) {
        if (dimension == 3) return CompletableFuture.completedFuture(null);
        String key = home.getWorldKey() + List.of("", "_nether", "_the_end").get(dimension);
        CompletableFuture<Void> future = new CompletableFuture<>();
        try {
            UUID expected = UUID.fromString(Files.readString(Path.of("probe-" + key + ".uuid")));
            SimpleHomelandAPI.queryHomelandByWorldUUIDAsync(expected, info -> {
                try {
                    require(info != null && info.worldKey().equals(key), "Unloaded UUID lookup failed: " + key);
                    SimpleHomelandAPI.loadHomelandWorldByWorldUUID(expected, result -> {
                        World world = Bukkit.getWorld(expected);
                        if (world == null) {
                            future.completeExceptionally(new AssertionError("UUID not preserved: " + key + " " + result));
                            return;
                        }
                        Bukkit.getRegionScheduler().execute(this, world, 0, 0, () -> {
                            try {
                                require(world.key().asString().equals("homelands:" + key), "Key changed incorrectly");
                                require(world.getWorldPath().toString().contains("dimensions/homelands/" + key), "Wrong world path");
                                require(world.getBlockAt(8, 80, 8).getType() == Material.DIAMOND_BLOCK, "Block lost: " + key);
                                Chest chest = (Chest) world.getBlockAt(9, 80, 8).getState();
                                ItemStack items = chest.getBlockInventory().getItem(0);
                                require(items != null && items.getType() == Material.EMERALD && items.getAmount() == 17,
                                        "Container contents lost: " + key);
                                require(world.getNearbyEntities(new Location(world, 10.5, 81, 8.5), 3, 3, 3).stream()
                                        .anyMatch(e -> "migration-entity".equals(e.getCustomName())), "Entity lost: " + key);
                                verified++;
                                getLogger().info("PROBE_NEW_WORLD_PASS " + world.key() + " UUID=" + expected);
                                future.complete(null);
                            } catch (Throwable error) {
                                future.completeExceptionally(error);
                            }
                        });
                    });
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                }
            });
        } catch (Throwable error) {
            future.completeExceptionally(error);
        }
        return future.thenCompose(ignored -> verifyDimension(home, dimension + 1));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (Boolean.getBoolean("homeland.probe.upgrade-recovery")
                || Boolean.getBoolean("homeland.probe.upgrade-recovery-restart")
                || Boolean.getBoolean("homeland.probe.paper-end-return")) return;
        if (Boolean.getBoolean("homeland.probe.closed-cycle")) return;
        if (Boolean.getBoolean("homeland.probe.create")) return;
        if (event.getPlayer().getName().equals("MigrationOwner")) {
            event.getPlayer().addAttachment(this, "simplehomeland.admin", true);
        }
        Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> {
            Homeland home = manager.findHomeland(OWNER, names.get(0)).orElseThrow();
            String key = home.getWorldKey() + "_nether";
            try {
                UUID uuid = UUID.fromString(Files.readString(Path.of("probe-" + key + ".uuid")));
                // Identical reflective signature and null-world Location to the sibling HuskHomes hook.
                var method = SimpleHomelandAPI.class.getMethod("teleportToHomelandByWorldUUID",
                        org.bukkit.entity.Player.class, UUID.class, boolean.class, Location.class, java.util.function.Consumer.class);
                java.util.function.Consumer<TeleportResult> callback = result -> {
                    try {
                        require(Bukkit.isOwnedByCurrentRegion(event.getPlayer()), "Callback ran off player region");
                        if (!event.getPlayer().getName().equals("MigrationOwner")) {
                            require(result == TeleportResult.ACCESS_DENIED, "Visitor access check bypassed: " + result);
                            getLogger().info("PROBE_API_ACCESS_DENIED_PASS");
                            testContainers(event.getPlayer());
                        } else {
                            require(result == TeleportResult.SUCCESS, "API teleport failed: " + result);
                            require(event.getPlayer().getWorld().getUID().equals(uuid), "Wrong API dimension");
                            Location actual = event.getPlayer().getLocation();
                            require(Math.abs(actual.getX() - 8.5) < 0.01 && Math.abs(actual.getZ() - 8.5) < 0.01,
                                    "Coordinates lost");
                            getLogger().info("PROBE_HUSKHOMES_SIGNATURE_PASS");
                            testHuskHomes(event.getPlayer(), uuid);
                        }
                    } catch (Throwable error) { failed(error); }
                };
                method.invoke(null, event.getPlayer(), uuid, false,
                        new Location(null, 8.5, 82, 8.5, 40, 20), callback);
            } catch (Throwable error) { failed(error); }
        }, 100L);
    }

    private void testHuskHomes(org.bukkit.entity.Player player, UUID uuid) throws Exception {
        var husk = Bukkit.getPluginManager().getPlugin("HuskHomes");
        require(husk != null && husk.isEnabled(), "HuskHomes is not enabled");
        // Move out and unload the dimension, then pass an old saved name + UUID through BukkitUser.
        player.teleportAsync(Bukkit.getWorlds().get(0).getSpawnLocation()).thenRun(() -> {
            homeland.getWorldsAccess().unload(Bukkit.getWorld(uuid), true).thenAccept(success -> {
                player.getScheduler().execute(this, () -> {
                    try {
                        require(success && Bukkit.getWorld(uuid) == null, "HuskHomes target was not unloaded");
                        ClassLoader loader = husk.getClass().getClassLoader();
                        Class<?> savedWorld = loader.loadClass("net.william278.huskhomes.position.World");
                        Class<?> savedLocation = loader.loadClass("net.william278.huskhomes.position.Location");
                        Object world = savedWorld.getMethod("from", String.class, UUID.class)
                                .invoke(null, "MigrationDefault - 地狱", uuid);
                        Object location = savedLocation.getMethod("at", double.class, double.class, double.class,
                                        float.class, float.class, savedWorld)
                                .invoke(null, 8.5, 82.0, 8.5, 40f, 20f, world);
                        Object user = husk.getClass().getMethod("getOnlineUser", org.bukkit.entity.Player.class).invoke(husk, player);
                        user.getClass().getMethod("teleportLocally", savedLocation, boolean.class).invoke(user, location, true);
                        player.getScheduler().runDelayed(this, task -> {
                            try {
                                require(player.getWorld().getUID().equals(uuid), "HuskHomes went to wrong world");
                                require(Math.abs(player.getLocation().getX() - 8.5) < .01, "HuskHomes coordinates lost");
                                getLogger().info("PROBE_REAL_HUSKHOMES_UNLOADED_PASS");
                            } catch (Throwable error) { failed(error); }
                        }, () -> failed(), 60L);
                    } catch (Throwable error) { failed(error); }
                }, () -> failed(), 1L);
            });
        });
    }

    private void lifecycle() {
        UUID owner = UUID.fromString("8f12bcde-0000-0000-0000-000000000002");
        String name = "LifecycleProbe";
        manager.createHomelandAdmin(Bukkit.getConsoleSender(), owner, name, "void", () -> {
            Homeland home = manager.findHomeland(owner, name).orElseThrow();
            UUID initialRoot = home.getWorldUuid();
            require(manager.getHomelandWorld(home.getWorldKey()).getGenerator() != null, "Void generator missing");
            manager.unlockNetherAdmin(Bukkit.getConsoleSender(), owner, name, () ->
                    manager.unlockEndAdmin(Bukkit.getConsoleSender(), owner, name, () -> {
                        UUID endUuid = manager.getHomelandWorld(home.getWorldKey() + "_the_end").getUID();
                        manager.resetOverworldAdmin(Bukkit.getConsoleSender(), owner, name, "flat", () -> {
                            require(!initialRoot.equals(home.getWorldUuid()), "Reset did not renew root UUID");
                            require(manager.getHomelandByWorldUUID(initialRoot) == null, "Old root UUID still indexed");
                            manager.resetDimensionAdmin(Bukkit.getConsoleSender(), owner, name, "nether", () ->
                                    manager.lockEndAdmin(Bukkit.getConsoleSender(), owner, name, () -> {
                                        require(manager.getHomelandByWorldUUID(endUuid) == null, "Deleted End UUID still indexed");
                                        require(manager.getHomelandWorld(home.getWorldKey() + "_the_end") == null, "Deleted End still loaded");
                                        manager.unlockEndAdmin(Bukkit.getConsoleSender(), owner, name, () ->
                                                manager.deleteHomelandAdmin(Bukkit.getConsoleSender(), owner, name, () -> {
                                                    require(manager.findHomeland(owner, name).isEmpty(), "Deleted home still indexed");
                                                    for (String suffix : List.of("", "_nether", "_the_end")) {
                                                        require(!Files.exists(homeland.getWorldService().directory(home.getWorldKey() + suffix)),
                                                                "Deleted world directory remains");
                                                    }
                                                    getLogger().info("PROBE_LIFECYCLE_PASS");
                                                }, this::failed), this::failed);
                                    }, this::failed), this::failed);
                        }, this::failed);
                    }, this::failed), this::failed);
        }, this::failed);
    }

    private void testContainers(org.bukkit.entity.Player player) {
        Homeland home = manager.findHomeland(OWNER, names.get(1)).orElseThrow();
        home.setPublic(true);
        World world = manager.getHomelandWorld(home.getWorldKey());
        SimpleHomelandAPI.teleportToHomelandByWorldKey(player, home.getWorldKey(), false,
                new Location(world, 8.5, 82, 8.5), result -> {
                    try {
                        require(result == TeleportResult.SUCCESS, "Container visitor could not enter public home");
                        for (Material material : List.of(Material.COPPER_CHEST, Material.WAXED_OXIDIZED_COPPER_CHEST,
                                Material.OAK_SHELF, Material.PALE_OAK_SHELF)) {
                            var block = world.getBlockAt(4, 80, 8);
                            block.setType(material);
                            var event = new org.bukkit.event.player.PlayerInteractEvent(player,
                                    org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK, new ItemStack(Material.AIR),
                                    block, org.bukkit.block.BlockFace.UP);
                            Bukkit.getPluginManager().callEvent(event);
                            require(event.isCancelled(), "Unprotected 26.2 container: " + material);
                            if (block.getState() instanceof org.bukkit.inventory.InventoryHolder holder) {
                                require(player.openInventory(holder.getInventory()) == null,
                                        "Inventory open bypassed protection: " + material);
                            }
                        }
                        getLogger().info("PROBE_CONTAINER_26_2_PASS");
                    } catch (Throwable error) { failed(error); }
                });
    }

    private void portal() {
        Homeland first = manager.findHomeland(OWNER, names.get(0)).orElseThrow();
        Homeland other = manager.findHomeland(OWNER, names.get(1)).orElseThrow();
        // Load another home's dimensions first to catch Worlds' namespace-first routing.
        manager.loadHomelandWorldByKey(other.getWorldKey() + "_nether", ignored ->
                manager.loadHomelandWorldByKey(other.getWorldKey() + "_the_end", ignoredEnd ->
                        manager.loadHomelandWorldByKey(first.getWorldKey(), source ->
                                manager.loadHomelandWorldByKey(first.getWorldKey() + "_nether", target ->
                                        manager.loadHomelandWorldByKey(first.getWorldKey() + "_the_end", end -> {
                                            portalEntity(source, target, org.bukkit.PortalType.NETHER)
                                                    .thenCompose(nothing -> portalEntity(source, end, org.bukkit.PortalType.ENDER))
                                                    .thenRun(() -> getLogger().info("PROBE_PORTAL_ISOLATION_PASS"))
                                                    .exceptionally(error -> { failed(error); return null; });
                                        })))));
    }

    private CompletableFuture<Void> portalEntity(World source, World target, org.bukkit.PortalType type) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Bukkit.getRegionScheduler().execute(this, source, 0, 0, () -> {
            int x = type == org.bukkit.PortalType.NETHER ? 13 : 4;
            Material portal = type == org.bukkit.PortalType.NETHER ? Material.NETHER_PORTAL : Material.END_PORTAL;
            if (type == org.bukkit.PortalType.NETHER) {
                for (int px = 12; px <= 15; px++) for (int y = 80; y <= 84; y++) {
                    source.getBlockAt(px, y, 8).setType(px == 12 || px == 15 || y == 80 || y == 84
                            ? Material.OBSIDIAN : portal);
                }
            } else {
                source.getBlockAt(x, 80, 8).setType(Material.OBSIDIAN);
                source.getBlockAt(x, 81, 8).setType(portal);
            }
            var entity = source.spawn(new Location(source, x + .5, 81, 8.5), org.bukkit.entity.Pig.class);
            entity.setAI(false);
            entity.setGravity(false);
            entity.setInvulnerable(true);
            UUID uuid = entity.getUniqueId();
            Location position = entity.getLocation();
            Bukkit.getPluginManager().callEvent(new org.bukkit.event.entity.EntityPortalEnterEvent(entity, position, type));
            Bukkit.getRegionScheduler().runDelayed(this, target, 0, 0, task -> {
                var arrived = Bukkit.getEntity(uuid);
                try {
                    require(arrived != null && arrived.getWorld().equals(target), "Portal routed to wrong home: " + type);
                    getLogger().info("PROBE_PORTAL_PASS " + type + " -> " + target.key());
                    result.complete(null);
                } catch (Throwable error) { result.completeExceptionally(error); }
            }, 100L);
        });
        return result;
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<Void> resume(Homeland home, UUID previous, UUID generated) {
        return homeland.getDatabaseQueue().submitFuture("probe-pending-reset", connection -> {
            try (var statement = connection.prepareStatement("UPDATE homeland SET world_uuid = ?, "
                    + "world_type = 'flat', overworld_reset_pending = TRUE, overworld_reset_previous_uuid = ? WHERE id = ?")) {
                statement.setString(1, generated == null ? null : generated.toString());
                statement.setString(2, previous.toString());
                statement.setInt(3, home.getId());
                statement.executeUpdate();
            }
            return null;
        }).thenCompose(ignored -> {
            try {
                home.setWorldType("flat");
                home.setOverworldResetPending(true);
                home.setOverworldResetPreviousUuid(previous);
                var storeField = HomelandManager.class.getDeclaredField("homelandStore");
                storeField.setAccessible(true);
                ((dev.user.homeland.manager.HomelandStore) storeField.get(manager)).updateWorldUuid(home, generated);
                var field = HomelandManager.class.getDeclaredField("worldOperations");
                field.setAccessible(true);
                Object operations = field.get(manager);
                var method = operations.getClass().getDeclaredMethod("resumeOverworldReset", Homeland.class);
                method.setAccessible(true);
                return ((java.util.concurrent.CompletionStage<Void>) method.invoke(operations, home)).toCompletableFuture();
            } catch (Exception error) {
                return CompletableFuture.failedFuture(error);
            }
        });
    }

    private void recovery() {
        UUID owner = UUID.fromString("8f12bcde-0000-0000-0000-000000000003");
        String name = "RecoveryProbe";
        manager.createHomelandAdmin(Bukkit.getConsoleSender(), owner, name, "void", () -> {
            Homeland home = manager.findHomeland(owner, name).orElseThrow();
            UUID previous = home.getWorldUuid();
            resume(home, previous, null).thenCompose(ignored -> {
                require(!home.isOverworldResetPending(), "Reset recovery stayed pending");
                require(!previous.equals(home.getWorldUuid()), "Pending reset did not regenerate world");
                UUID completed = home.getWorldUuid();
                return resume(home, previous, completed).thenRun(() -> {
                    require(completed.equals(home.getWorldUuid()), "Finish recovery regenerated world twice");
                    require(!home.isOverworldResetPending(), "Finish recovery stayed pending");
                });
            }).thenRun(() -> manager.deleteHomelandAdmin(Bukkit.getConsoleSender(), owner, name,
                    () -> getLogger().info("PROBE_RECOVERY_PASS"), this::failed))
                    .exceptionally(error -> { failed(error); return null; });
        }, this::failed);
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private void failed() { failed(new AssertionError("Operation failed")); }

    private void failed(Throwable error) {
        getLogger().log(java.util.logging.Level.SEVERE, "PROBE_FAILED", error);
    }
}
