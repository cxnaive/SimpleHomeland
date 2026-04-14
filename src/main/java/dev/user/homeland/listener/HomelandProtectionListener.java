package dev.user.homeland.listener;

import dev.user.homeland.SimpleHomelandPlugin;
import dev.user.homeland.model.VisitorFlag;
import dev.user.homeland.util.MessageUtil;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.inventory.InventoryHolder;

import java.util.EnumSet;
import java.util.Set;

/**
 * 访客权限保护监听器。
 * 检查访客（非主人、非被邀请者）在家园世界中的行为权限。
 */
public class HomelandProtectionListener implements Listener {

    private final SimpleHomelandPlugin plugin;

    // ===== 方块类型集合 =====

    /** 交互方块：按钮、拉杆、门、栅栏门、活板门、压力板、钟、音符盒、中继器、比较器 */
    private static final Set<Material> INTERACT_BLOCKS = EnumSet.of(
            // 按钮
            Material.STONE_BUTTON, Material.OAK_BUTTON, Material.SPRUCE_BUTTON, Material.BIRCH_BUTTON,
            Material.JUNGLE_BUTTON, Material.ACACIA_BUTTON, Material.DARK_OAK_BUTTON, Material.MANGROVE_BUTTON,
            Material.CHERRY_BUTTON, Material.BAMBOO_BUTTON, Material.CRIMSON_BUTTON, Material.WARPED_BUTTON,
            Material.POLISHED_BLACKSTONE_BUTTON,
            // 拉杆
            Material.LEVER,
            // 门
            Material.OAK_DOOR, Material.SPRUCE_DOOR, Material.BIRCH_DOOR, Material.JUNGLE_DOOR,
            Material.ACACIA_DOOR, Material.DARK_OAK_DOOR, Material.MANGROVE_DOOR, Material.CHERRY_DOOR,
            Material.BAMBOO_DOOR, Material.CRIMSON_DOOR, Material.WARPED_DOOR, Material.IRON_DOOR,
            // 栅栏门
            Material.OAK_FENCE_GATE, Material.SPRUCE_FENCE_GATE, Material.BIRCH_FENCE_GATE,
            Material.JUNGLE_FENCE_GATE, Material.ACACIA_FENCE_GATE, Material.DARK_OAK_FENCE_GATE,
            Material.MANGROVE_FENCE_GATE, Material.CHERRY_FENCE_GATE, Material.BAMBOO_FENCE_GATE,
            Material.CRIMSON_FENCE_GATE, Material.WARPED_FENCE_GATE,
            // 活板门
            Material.OAK_TRAPDOOR, Material.SPRUCE_TRAPDOOR, Material.BIRCH_TRAPDOOR,
            Material.JUNGLE_TRAPDOOR, Material.ACACIA_TRAPDOOR, Material.DARK_OAK_TRAPDOOR,
            Material.MANGROVE_TRAPDOOR, Material.CHERRY_TRAPDOOR, Material.BAMBOO_TRAPDOOR,
            Material.CRIMSON_TRAPDOOR, Material.WARPED_TRAPDOOR, Material.IRON_TRAPDOOR,
            // 压力板
            Material.STONE_PRESSURE_PLATE, Material.OAK_PRESSURE_PLATE, Material.SPRUCE_PRESSURE_PLATE,
            Material.BIRCH_PRESSURE_PLATE, Material.JUNGLE_PRESSURE_PLATE, Material.ACACIA_PRESSURE_PLATE,
            Material.DARK_OAK_PRESSURE_PLATE, Material.MANGROVE_PRESSURE_PLATE, Material.CHERRY_PRESSURE_PLATE,
            Material.BAMBOO_PRESSURE_PLATE, Material.CRIMSON_PRESSURE_PLATE, Material.WARPED_PRESSURE_PLATE,
            Material.LIGHT_WEIGHTED_PRESSURE_PLATE, Material.HEAVY_WEIGHTED_PRESSURE_PLATE,
            Material.POLISHED_BLACKSTONE_PRESSURE_PLATE,
            // 其他交互方块
            Material.BELL, Material.NOTE_BLOCK, Material.REPEATER, Material.COMPARATOR,
            Material.DAYLIGHT_DETECTOR, Material.CALIBRATED_SCULK_SENSOR
    );

    /** 容器方块 */
    private static final Set<Material> CONTAINER_BLOCKS = EnumSet.of(
            Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL,
            Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER,
            Material.HOPPER, Material.DROPPER, Material.DISPENSER,
            Material.SHULKER_BOX, Material.WHITE_SHULKER_BOX, Material.ORANGE_SHULKER_BOX,
            Material.MAGENTA_SHULKER_BOX, Material.LIGHT_BLUE_SHULKER_BOX, Material.YELLOW_SHULKER_BOX,
            Material.LIME_SHULKER_BOX, Material.PINK_SHULKER_BOX, Material.GRAY_SHULKER_BOX,
            Material.LIGHT_GRAY_SHULKER_BOX, Material.CYAN_SHULKER_BOX, Material.PURPLE_SHULKER_BOX,
            Material.BLUE_SHULKER_BOX, Material.BROWN_SHULKER_BOX, Material.GREEN_SHULKER_BOX,
            Material.RED_SHULKER_BOX, Material.BLACK_SHULKER_BOX,
            Material.ENDER_CHEST,
            Material.CRAFTER, Material.CHISELED_BOOKSHELF,
            Material.JUKEBOX, Material.LECTERN, Material.LOOM, Material.CARTOGRAPHY_TABLE,
            Material.GRINDSTONE, Material.STONECUTTER, Material.SMITHING_TABLE
    );

    /** 铁砧类方块 */
    private static final Set<Material> ANVIL_BLOCKS = EnumSet.of(
            Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL
    );

    /** 附魔/酿造方块 */
    private static final Set<Material> ENCHANT_BLOCKS = EnumSet.of(
            Material.ENCHANTING_TABLE, Material.BREWING_STAND
    );

    /** 床类方块 */
    private static final Set<Material> BED_BLOCKS = EnumSet.of(
            Material.WHITE_BED, Material.ORANGE_BED, Material.MAGENTA_BED, Material.LIGHT_BLUE_BED,
            Material.YELLOW_BED, Material.LIME_BED, Material.PINK_BED, Material.GRAY_BED,
            Material.LIGHT_GRAY_BED, Material.CYAN_BED, Material.PURPLE_BED, Material.BLUE_BED,
            Material.BROWN_BED, Material.GREEN_BED, Material.RED_BED, Material.BLACK_BED
    );

    /** 农业相关：骨粉可作用的方块 */
    private static final Set<Material> FARM_BLOCKS = EnumSet.of(
            Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS,
            Material.PUMPKIN_STEM, Material.MELON_STEM, Material.TORCHFLOWER_CROP,
            Material.COCOA, Material.SWEET_BERRY_BUSH, Material.GLOW_BERRIES,
            Material.NETHER_WART
    );

    public HomelandProtectionListener(SimpleHomelandPlugin plugin) {
        this.plugin = plugin;
    }

    // ==================== 方块事件 ====================

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        String worldKey = event.getBlock().getWorld().getKey().getKey();
        if (!plugin.getHomelandManager().checkVisitorFlag(player, worldKey, VisitorFlag.PLACE)) {
            event.setCancelled(true);
            sendDenied(player);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        String worldKey = event.getBlock().getWorld().getKey().getKey();
        if (!plugin.getHomelandManager().checkVisitorFlag(player, worldKey, VisitorFlag.BREAK)) {
            event.setCancelled(true);
            sendDenied(player);
        }
    }

    // ==================== 交互事件 ====================

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() == org.bukkit.event.block.Action.PHYSICAL) {
            Block block = event.getClickedBlock();
            if (block == null) return;
            Material mat = block.getType();
            // 压力板触发 → INTERACT
            if (INTERACT_BLOCKS.contains(mat)) {
                checkInteract(event, event.getPlayer());
            }
            // 践踏农田 → FARMING
            else if (mat == Material.FARMLAND) {
                String worldKey = block.getWorld().getKey().getKey();
                if (!plugin.getHomelandManager().checkVisitorFlag(event.getPlayer(), worldKey, VisitorFlag.FARMING)) {
                    event.setCancelled(true);
                }
            }
            return;
        }

        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null) return;

        Player player = event.getPlayer();
        String worldKey = block.getWorld().getKey().getKey();
        Material mat = block.getType();

        // 交互方块
        if (INTERACT_BLOCKS.contains(mat)) {
            if (!plugin.getHomelandManager().checkVisitorFlag(player, worldKey, VisitorFlag.INTERACT)) {
                event.setCancelled(true);
                sendDenied(player);
            }
            return;
        }

        // 容器
        if (CONTAINER_BLOCKS.contains(mat)) {
            if (!plugin.getHomelandManager().checkVisitorFlag(player, worldKey, VisitorFlag.CONTAINER)) {
                event.setCancelled(true);
                sendDenied(player);
            }
            return;
        }

        // 铁砧
        if (ANVIL_BLOCKS.contains(mat)) {
            if (!plugin.getHomelandManager().checkVisitorFlag(player, worldKey, VisitorFlag.ANVIL)) {
                event.setCancelled(true);
                sendDenied(player);
            }
            return;
        }

        // 附魔/酿造
        if (ENCHANT_BLOCKS.contains(mat)) {
            if (!plugin.getHomelandManager().checkVisitorFlag(player, worldKey, VisitorFlag.ENCHANT)) {
                event.setCancelled(true);
                sendDenied(player);
            }
            return;
        }

        // 床
        if (BED_BLOCKS.contains(mat)) {
            if (!plugin.getHomelandManager().checkVisitorFlag(player, worldKey, VisitorFlag.BED)) {
                event.setCancelled(true);
                sendDenied(player);
            }
            return;
        }

        // 农业（骨粉/收获）
        if (FARM_BLOCKS.contains(mat) || mat == Material.FARMLAND || mat == Material.SOUL_SAND) {
            if (!plugin.getHomelandManager().checkVisitorFlag(player, worldKey, VisitorFlag.FARMING)) {
                event.setCancelled(true);
                sendDenied(player);
            }
            return;
        }

        // 手持骨粉对其他方块使用
        if (event.getItem() != null && event.getItem().getType() == Material.BONE_MEAL) {
            if (!plugin.getHomelandManager().checkVisitorFlag(player, worldKey, VisitorFlag.FARMING)) {
                event.setCancelled(true);
                sendDenied(player);
            }
            return;
        }
    }

    private void checkInteract(PlayerInteractEvent event, Player player) {
        Block block = event.getClickedBlock();
        if (block == null) return;
        String worldKey = block.getWorld().getKey().getKey();
        if (!plugin.getHomelandManager().checkVisitorFlag(player, worldKey, VisitorFlag.INTERACT)) {
            event.setCancelled(true);
            sendDenied(player);
        }
    }

    // ==================== 物品事件 ====================

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        String worldKey = player.getWorld().getKey().getKey();
        if (!plugin.getHomelandManager().checkVisitorFlag(player, worldKey, VisitorFlag.PICKUP)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        String worldKey = player.getWorld().getKey().getKey();
        if (!plugin.getHomelandManager().checkVisitorFlag(player, worldKey, VisitorFlag.DROP)) {
            event.setCancelled(true);
            sendDenied(player);
        }
    }

    // ==================== 战斗事件 ====================

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Player player = resolvePlayer(event.getDamager());
        if (player == null) return;

        Entity target = event.getEntity();
        String worldKey = target.getWorld().getKey().getKey();

        if (target instanceof Player) {
            // PVP
            if (!plugin.getHomelandManager().checkVisitorFlag(player, worldKey, VisitorFlag.PVP)) {
                event.setCancelled(true);
                sendDenied(player);
            }
        } else if (target instanceof Animals || target instanceof WaterMob || target instanceof Villager
                || target instanceof WanderingTrader || target instanceof Golem) {
            // 攻击动物/村民/傀儡
            if (!plugin.getHomelandManager().checkVisitorFlag(player, worldKey, VisitorFlag.KILL_ANIMAL)) {
                event.setCancelled(true);
                sendDenied(player);
            }
        } else if (target instanceof Monster) {
            // 攻击怪物
            if (!plugin.getHomelandManager().checkVisitorFlag(player, worldKey, VisitorFlag.KILL_MONSTER)) {
                event.setCancelled(true);
                sendDenied(player);
            }
        }
    }

    private Player resolvePlayer(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) return p;
        return null;
    }

    // ==================== 动物互动事件 ====================

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        Entity entity = event.getRightClicked();
        String worldKey = entity.getWorld().getKey().getKey();

        // 村民交易
        if (entity instanceof Villager || entity instanceof WanderingTrader) {
            if (!plugin.getHomelandManager().checkVisitorFlag(player, worldKey, VisitorFlag.TRADE)) {
                event.setCancelled(true);
                sendDenied(player);
            }
            return;
        }

        // 动物互动（喂食、剪羊毛、挤奶等）
        if (entity instanceof Animals || entity instanceof WaterMob) {
            if (!plugin.getHomelandManager().checkVisitorFlag(player, worldKey, VisitorFlag.FARMING)) {
                event.setCancelled(true);
                sendDenied(player);
            }
        }
    }

    // ==================== 载具事件 ====================

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (!(event.getEntered() instanceof Player player)) return;
        String worldKey = player.getWorld().getKey().getKey();
        if (!plugin.getHomelandManager().checkVisitorFlag(player, worldKey, VisitorFlag.VEHICLE)) {
            event.setCancelled(true);
            sendDenied(player);
        }
    }

    // ==================== 投掷/射击事件 ====================

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player player)) return;
        String worldKey = player.getWorld().getKey().getKey();

        org.bukkit.entity.Projectile proj = event.getEntity();
        if (proj instanceof Egg || proj instanceof Snowball || proj instanceof EnderPearl
                || proj instanceof org.bukkit.entity.ThrownPotion
                || proj instanceof org.bukkit.entity.Trident) {
            if (!plugin.getHomelandManager().checkVisitorFlag(player, worldKey, VisitorFlag.PROJECTILE)) {
                event.setCancelled(true);
                sendDenied(player);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerFish(PlayerFishEvent event) {
        Player player = event.getPlayer();
        String worldKey = player.getWorld().getKey().getKey();
        if (!plugin.getHomelandManager().checkVisitorFlag(player, worldKey, VisitorFlag.PROJECTILE)) {
            event.setCancelled(true);
            sendDenied(player);
        }
    }

    // ==================== 兜底：容器 GUI 打开 ====================

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        InventoryHolder holder = event.getInventory().getHolder();
        // 跳过玩家自身背包
        if (holder instanceof Player) return;

        String worldKey = player.getWorld().getKey().getKey();
        InventoryType type = event.getInventory().getType();

        // 村民交易界面
        if (type == InventoryType.MERCHANT) {
            if (!plugin.getHomelandManager().checkVisitorFlag(player, worldKey, VisitorFlag.TRADE)) {
                event.setCancelled(true);
                sendDenied(player);
            }
        }
    }

    // ==================== 辅助 ====================

    private void sendDenied(Player player) {
        MessageUtil.send(player, plugin.getConfigManager().getMessage("visitor-flag-denied"));
    }
}
