package dev.user.homeland.integration;

import dev.user.homeland.SimpleHomelandPlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.PortalType;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.CompletableFuture;

/** Real Paper player tests: valid bed, repeat visit, credits disabled, and invalid bed fallback. */
final class PaperEndReturnProbe implements Listener {
    private final JavaPlugin probe;
    private final SimpleHomelandPlugin plugin;
    private final CompletableFuture<World> worldsReady = new CompletableFuture<>();
    private World overworld;
    private World end;

    PaperEndReturnProbe(JavaPlugin probe, SimpleHomelandPlugin plugin) {
        this.probe = probe;
        this.plugin = plugin;
    }

    void start() {
        Bukkit.getPluginManager().registerEvents(this, probe);
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(probe, task -> {
            if (!plugin.getHomelandManager().isWorldAccessStateReady()) return;
            task.cancel();
            load("homeland_8f12bcde_1791567461566").thenCompose(world -> {
                overworld = world;
                return load("homeland_8f12bcde_1791567461566_the_end");
            }).whenComplete((world, error) -> {
                if (error != null) { fail(error); return; }
                end = world;
                worldsReady.complete(overworld);
                probe.getLogger().info("PROBE_PAPER_END_CLIENT_READY");
            });
        }, 20L, 20L);
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        if (!event.getPlayer().getName().equals("MigrationOwner")) return;
        Player player = event.getPlayer();
        player.addAttachment(probe, "simplehomeland.admin", true);
        worldsReady.thenRun(() -> player.getScheduler().runDelayed(probe, task -> {
            try {
                player.setGameMode(GameMode.CREATIVE);
                placeBed();
                runCase(player, 0);
            } catch (Throwable error) { fail(error); }
        }, null, 40L));
    }

    private void placeBed() {
        for (int x = 20; x <= 30; x++) {
            for (int z = 4; z <= 12; z++) {
                overworld.getBlockAt(x, 80, z).setType(Material.STONE);
                for (int y = 81; y <= 84; y++) overworld.getBlockAt(x, y, z).setType(Material.AIR);
            }
        }
        Bed foot = (Bed) Material.RED_BED.createBlockData();
        foot.setFacing(BlockFace.EAST);
        foot.setPart(Bed.Part.FOOT);
        overworld.getBlockAt(24, 81, 8).setBlockData(foot);
        Bed head = (Bed) foot.clone();
        head.setPart(Bed.Part.HEAD);
        overworld.getBlockAt(25, 81, 8).setBlockData(head);
    }

    private void runCase(Player player, int index) {
        if (index == 4) {
            probe.getLogger().info("PROBE_PAPER_END_RETURN_PASS cases=4 expectedWinScreens=1");
            Bukkit.getGlobalRegionScheduler().runDelayed(probe, task -> Bukkit.shutdown(), 40L);
            return;
        }
        try {
            setCreditsDisabled(index == 2);
            player.setHasSeenWinScreen(index == 1 || index == 3);
            if (index == 2) player.setRespawnLocation(null);
            else player.setRespawnLocation(new Location(overworld, 25, 81, 8), false);
            if (index == 3) {
                overworld.getBlockAt(24, 81, 8).setType(Material.AIR);
                overworld.getBlockAt(25, 81, 8).setType(Material.AIR);
            }
            Location bed = player.getRespawnLocation();
            require(index < 2 ? bed != null : bed == null, "Respawn fixture is invalid for case " + index);
            player.teleportAsync(new Location(end, 16.5, 85, 8.5)).thenAccept(success -> {
                try {
                    require(success, "Could not enter end fixture");
                    player.setPortalCooldown(0);
                    Bukkit.getPluginManager().callEvent(new EntityPortalEnterEvent(player, player.getLocation(), PortalType.ENDER));
                    player.getScheduler().runDelayed(probe, task -> {
                        try {
                            require(player.getWorld().equals(overworld), "End returned to wrong world");
                            Location expected = index < 2 ? bed : overworld.getSpawnLocation();
                            require(Math.abs(player.getLocation().getX() - expected.getX()) < 2
                                            && Math.abs(player.getLocation().getZ() - expected.getZ()) < 2,
                                    "End return ignored respawn selection for case " + index);
                            require(player.hasSeenWinScreen(), "Credits state not recorded for case " + index);
                            probe.getLogger().info("PROBE_PAPER_END_CASE_PASS " + index);
                            runCase(player, index + 1);
                        } catch (Throwable error) { fail(error); }
                    }, null, 20L);
                } catch (Throwable error) { fail(error); }
            }).exceptionally(error -> { fail(error); return null; });
        } catch (Throwable error) { fail(error); }
    }

    private void setCreditsDisabled(boolean value) throws ReflectiveOperationException {
        Object level = end.getClass().getMethod("getHandle").invoke(end);
        Object config = level.getClass().getMethod("paperConfig").invoke(level);
        Object misc = config.getClass().getField("misc").get(config);
        misc.getClass().getField("disableEndCredits").setBoolean(misc, value);
    }
    private CompletableFuture<World> load(String key) {
        CompletableFuture<World> result = new CompletableFuture<>();
        plugin.getHomelandManager().loadHomelandWorldByKey(key, result::complete, result::completeExceptionally);
        return result;
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private void fail(Throwable error) {
        probe.getLogger().log(java.util.logging.Level.SEVERE, "PROBE_PAPER_END_RETURN_FAILED", error);
        Bukkit.getGlobalRegionScheduler().execute(probe, Bukkit::shutdown);
    }
}
