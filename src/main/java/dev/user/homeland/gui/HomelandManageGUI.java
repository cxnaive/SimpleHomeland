package dev.user.homeland.gui;

import dev.user.homeland.SimpleHomelandPlugin;
import dev.user.homeland.config.ConfigManager;
import dev.user.homeland.config.GUIConfig;
import dev.user.homeland.config.PriceTier;
import dev.user.homeland.model.Homeland;
import dev.user.homeland.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 单个家园管理 GUI（支持玩家模式和 admin 模式）
 */
public class HomelandManageGUI extends AbstractGUI {

    private final String homelandName;
    private final GUIConfig.Manage cfg;
    private final UUID targetUuid;
    private final String targetName;
    private final boolean isAdmin;
    private boolean pendingExpand = false;
    private boolean pendingDelete = false;
    private String pendingLockDimension = null;

    // 玩家模式构造
    private HomelandManageGUI(SimpleHomelandPlugin plugin, Player player, String homelandName) {
        super(plugin, player, plugin.getConfigManager().getMessage("gui.manage-title", "name", homelandName),
                plugin.getConfigManager().getGUIConfig().getManage().getSize());
        this.homelandName = homelandName;
        this.cfg = plugin.getConfigManager().getGUIConfig().getManage();
        this.targetUuid = player.getUniqueId();
        this.targetName = null;
        this.isAdmin = false;
    }

    // admin 模式构造
    private HomelandManageGUI(SimpleHomelandPlugin plugin, Player admin, UUID targetUuid, String targetName, String homelandName) {
        super(plugin, admin, plugin.getConfigManager().getMessage("gui.manage-title", "name", homelandName),
                plugin.getConfigManager().getGUIConfig().getManage().getSize());
        this.homelandName = homelandName;
        this.cfg = plugin.getConfigManager().getGUIConfig().getManage();
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.isAdmin = true;
    }

    public static void open(SimpleHomelandPlugin plugin, Player player, String homelandName) {
        new HomelandManageGUI(plugin, player, homelandName).open();
    }

    public static void openAdmin(SimpleHomelandPlugin plugin, Player admin, UUID targetUuid, String targetName, String homelandName) {
        new HomelandManageGUI(plugin, admin, targetUuid, targetName, homelandName).open();
    }

    @Override
    public void initialize() {
        actions.clear();

        Optional<Homeland> optHomeland = plugin.getHomelandManager().getHomeland(targetUuid, homelandName);
        if (optHomeland.isEmpty()) {
            player.sendMessage(MessageUtil.toComponent(plugin.getConfigManager().getMessage("homeland-not-found", "name", homelandName)));
            abort();
            return;
        }

        Homeland homeland = optHomeland.get();
        ConfigManager config = plugin.getConfigManager();

        ItemStack border = createBorderItem();
        fillBorder(border);

        // 家园信息
        setItem(cfg.getInfoSlot(), createInfoItem(homeland));

        // 地狱
        boolean canUnlockNether = isAdmin || player.hasPermission("simplehomeland.homeland.unlock.nether");
        if (!homeland.hasNether()) {
            if (canUnlockNether) {
                if (isAdmin) {
                    setItem(cfg.getNetherSlot(), createUnlockAdminItem(config, "gui.unlock-nether-item-name"), (p, e) -> {
                        if (plugin.getConfigManager().isBranchMode()) {
                            MessageUtil.send(p, plugin.getConfigManager().getMessage("branch-mode-gui"));
                            return;
                        }
                        plugin.getHomelandManager().unlockNetherAdmin(p, targetUuid, homelandName,
                                () -> p.getScheduler().execute(plugin, () -> refresh(), () -> {}, 0L),
                                () -> p.getScheduler().execute(plugin, () -> refresh(), () -> {}, 0L));
                    });
                } else {
                    setItem(cfg.getNetherSlot(), createUnlockItem(config, "gui.unlock-nether-item-name",
                            config.getNetherUnlockMoney(), config.getNetherUnlockPoints()), (p, e) -> {
                        if (plugin.getConfigManager().isBranchMode()) {
                            MessageUtil.send(p, plugin.getConfigManager().getMessage("branch-mode-gui"));
                            return;
                        }
                        plugin.getHomelandManager().unlockNether(p, homelandName,
                                () -> p.getScheduler().execute(plugin, () -> refresh(), () -> {}, 0L),
                                () -> {});
                    });
                }
            } else {
                setItem(cfg.getNetherSlot(), createNoPermissionItem(config, "gui.unlock-nether-item-name",
                        cfg.getNetherUnlockMaterial()));
            }
        } else if ("nether".equals(pendingLockDimension)) {
            setItem(cfg.getNetherSlot(), createLockConfirmItem(cfg.getNetherConfirmMaterial(), "地狱"), (p, e) -> {
                pendingLockDimension = null;
                if (isAdmin) {
                    plugin.getHomelandManager().lockNetherAdmin(p, targetUuid, homelandName,
                            () -> p.getScheduler().execute(plugin, () -> refresh(), () -> {}, 0L),
                            () -> p.getScheduler().execute(plugin, () -> refresh(), () -> {}, 0L));
                } else {
                    plugin.getHomelandManager().lockNether(p, homelandName,
                            () -> p.getScheduler().execute(plugin, () -> refresh(), () -> {}, 0L),
                            () -> p.getScheduler().execute(plugin, () -> refresh(), () -> {}, 0L));
                }
            });
        } else {
            setItem(cfg.getNetherSlot(), createLockDimensionItem(cfg.getNetherLockMaterial(), "gui.lock-nether-item-name",
                    "gui.lock-nether-item-lore", "gui.lock-nether-item-click"), (p, e) -> {
                if (plugin.getConfigManager().isBranchMode()) {
                    MessageUtil.send(p, plugin.getConfigManager().getMessage("branch-mode-gui"));
                    return;
                }
                pendingLockDimension = "nether";
                refresh();
            });
        }

        // 传送到家园
        if (isAdmin) {
            setItem(cfg.getTeleportSlot(), createTeleportItem(), (p, e) -> {
                close();
                plugin.getHomelandManager().teleportToHomeland(p, targetUuid, homelandName);
            });
        } else {
            setItemIfPermitted(cfg.getTeleportSlot(), createTeleportItem(), "simplehomeland.homeland.home", (p, e) -> {
                close();
                plugin.getHomelandManager().teleportToHomeland(p, homelandName);
            });
        }

        // 游戏规则
        if (isAdmin) {
            setItem(cfg.getGameruleSlot(), createGameruleItem(), (p, e) -> {
                if (plugin.getConfigManager().isBranchMode()) {
                    MessageUtil.send(p, plugin.getConfigManager().getMessage("branch-mode-gui"));
                    return;
                }
                close();
                HomelandGameRuleGUI.openAdmin(plugin, p, targetUuid, targetName, homelandName);
            });
        } else {
            setItem(cfg.getGameruleSlot(), createGameruleItem(), (p, e) -> {
                if (plugin.getConfigManager().isBranchMode()) {
                    MessageUtil.send(p, plugin.getConfigManager().getMessage("branch-mode-gui"));
                    return;
                }
                close();
                HomelandGameRuleGUI.open(plugin, p, homelandName);
            });
        }

        // 返回主世界
        setItem(cfg.getReturnToMainSlot(), createReturnToMainItem(), (p, e) -> {
            close();
            plugin.getHomelandManager().teleportToMainWorld(p);
        });

        // 末地
        boolean canUnlockEnd = isAdmin || player.hasPermission("simplehomeland.homeland.unlock.end");
        if (!homeland.hasEnd()) {
            if (canUnlockEnd) {
                if (isAdmin) {
                    setItem(cfg.getEndSlot(), createUnlockAdminItem(config, "gui.unlock-end-item-name"), (p, e) -> {
                        if (plugin.getConfigManager().isBranchMode()) {
                            MessageUtil.send(p, plugin.getConfigManager().getMessage("branch-mode-gui"));
                            return;
                        }
                        plugin.getHomelandManager().unlockEndAdmin(p, targetUuid, homelandName,
                                () -> p.getScheduler().execute(plugin, () -> refresh(), () -> {}, 0L),
                                () -> p.getScheduler().execute(plugin, () -> refresh(), () -> {}, 0L));
                    });
                } else {
                    setItem(cfg.getEndSlot(), createUnlockItem(config, "gui.unlock-end-item-name",
                            config.getEndUnlockMoney(), config.getEndUnlockPoints()), (p, e) -> {
                        if (plugin.getConfigManager().isBranchMode()) {
                            MessageUtil.send(p, plugin.getConfigManager().getMessage("branch-mode-gui"));
                            return;
                        }
                        plugin.getHomelandManager().unlockEnd(p, homelandName,
                                () -> p.getScheduler().execute(plugin, () -> refresh(), () -> {}, 0L),
                                () -> {});
                    });
                }
            } else {
                setItem(cfg.getEndSlot(), createNoPermissionItem(config, "gui.unlock-end-item-name",
                        cfg.getEndUnlockMaterial()));
            }
        } else if ("end".equals(pendingLockDimension)) {
            setItem(cfg.getEndSlot(), createLockConfirmItem(cfg.getEndConfirmMaterial(), "末地"), (p, e) -> {
                pendingLockDimension = null;
                if (isAdmin) {
                    plugin.getHomelandManager().lockEndAdmin(p, targetUuid, homelandName,
                            () -> p.getScheduler().execute(plugin, () -> refresh(), () -> {}, 0L),
                            () -> p.getScheduler().execute(plugin, () -> refresh(), () -> {}, 0L));
                } else {
                    plugin.getHomelandManager().lockEnd(p, homelandName,
                            () -> p.getScheduler().execute(plugin, () -> refresh(), () -> {}, 0L),
                            () -> p.getScheduler().execute(plugin, () -> refresh(), () -> {}, 0L));
                }
            });
        } else {
            setItem(cfg.getEndSlot(), createLockDimensionItem(cfg.getEndLockMaterial(), "gui.lock-end-item-name",
                    "gui.lock-end-item-lore", "gui.lock-end-item-click"), (p, e) -> {
                if (plugin.getConfigManager().isBranchMode()) {
                    MessageUtil.send(p, plugin.getConfigManager().getMessage("branch-mode-gui"));
                    return;
                }
                pendingLockDimension = "end";
                refresh();
            });
        }

        // 公开/私有切换
        if (isAdmin) {
            setItem(cfg.getPublicSlot(), createPublicItem(homeland), (p, e) -> {
                plugin.getHomelandManager().togglePublicAdmin(p, targetUuid, homelandName,
                        () -> p.getScheduler().execute(plugin, () -> refresh(), () -> {}, 0L),
                        () -> {});
            });
        } else {
            setItem(cfg.getPublicSlot(), createPublicItem(homeland), (p, e) -> {
                plugin.getHomelandManager().togglePublic(p, homelandName,
                        () -> p.getScheduler().execute(plugin, () -> refresh(), () -> {}, 0L),
                        () -> {});
            });
        }

        // 邀请管理按钮
        if (isAdmin) {
            setItem(cfg.getInviteSlot(), createInviteManageItem(), (p, e) -> {
                close();
                HomelandInviteManageGUI.openAdmin(plugin, p, targetUuid, targetName, homelandName);
            });
        } else {
            setItem(cfg.getInviteSlot(), createInviteManageItem(), (p, e) -> {
                close();
                HomelandInviteManageGUI.open(plugin, p, homelandName);
            });
        }

        // 扩展边界
        int newRadius = homeland.getBorderRadius() + config.getBorderExpandStep();
        boolean canExpand = isAdmin || (newRadius <= config.getMaxBorderRadius() && player.hasPermission("simplehomeland.homeland.border"));
        if (canExpand && newRadius <= config.getMaxBorderRadius()) {
            if (pendingExpand) {
                setItem(cfg.getExpandSlot(), createExpandConfirmItem(homeland, config), (p, e) -> {
                    pendingExpand = false;
                    if (isAdmin) {
                        plugin.getHomelandManager().expandBorderAdmin(p, targetUuid, homelandName,
                                () -> p.getScheduler().execute(plugin, () -> refresh(), () -> {}, 0L),
                                () -> p.getScheduler().execute(plugin, () -> refresh(), () -> {}, 0L));
                    } else {
                        plugin.getHomelandManager().expandBorder(p, homelandName,
                                () -> p.getScheduler().execute(plugin, () -> refresh(), () -> {}, 0L),
                                () -> p.getScheduler().execute(plugin, () -> refresh(), () -> {}, 0L));
                    }
                });
            } else {
                setItem(cfg.getExpandSlot(), isAdmin ? createExpandAdminItem(homeland, config) : createExpandBorderItem(homeland, config), (p, e) -> {
                    if (plugin.getConfigManager().isBranchMode()) {
                        MessageUtil.send(p, plugin.getConfigManager().getMessage("branch-mode-gui"));
                        return;
                    }
                    pendingExpand = true;
                    refresh();
                });
            }
        }

        // 访客权限按钮
        if (isAdmin) {
            setItem(cfg.getVisitorFlagSlot(), createVisitorFlagItem(), (p, e) -> {
                close();
                HomelandVisitorFlagGUI.openAdmin(plugin, p, targetUuid, targetName, homelandName);
            });
        } else {
            setItem(cfg.getVisitorFlagSlot(), createVisitorFlagItem(), (p, e) -> {
                close();
                HomelandVisitorFlagGUI.open(plugin, p, homelandName);
            });
        }

        // 重置世界按钮
        setItem(cfg.getResetSlot(), createResetItem(), (p, e) -> {
            if (plugin.getConfigManager().isBranchMode()) {
                MessageUtil.send(p, plugin.getConfigManager().getMessage("branch-mode-gui"));
                return;
            }
            close();
            if (isAdmin) {
                HomelandResetGUI.openAdmin(plugin, p, targetUuid, targetName, homelandName);
            } else {
                HomelandResetGUI.open(plugin, p, homelandName);
            }
        });

        // 群系更改按钮（仅玩家模式）
        if (!isAdmin && player.hasPermission("simplehomeland.homeland.biome")) {
            setItem(cfg.getBiomeSlot(), createBiomeItem(), (p, e) -> {
                if (plugin.getConfigManager().isBranchMode()) {
                    MessageUtil.send(p, plugin.getConfigManager().getMessage("branch-mode-gui"));
                    return;
                }
                close();
                HomelandBiomeGUI.open(plugin, p, homelandName);
            });
        }

        // 删除按钮
        boolean canDelete = isAdmin || player.hasPermission("simplehomeland.homeland.delete");
        if (canDelete) {
            if (pendingDelete) {
                setItem(cfg.getDeleteSlot(), createDeleteConfirmItem(), (p, e) -> {
                    pendingDelete = false;
                    if (isAdmin) {
                        plugin.getHomelandManager().deleteHomelandAdmin(p, targetUuid, homelandName,
                                () -> p.getScheduler().execute(plugin, () -> {
                                    close();
                                    HomelandAdminGUI.HomelandAdminPlayerGUI.open(plugin, p, targetUuid, targetName);
                                }, () -> {}, 0L),
                                () -> p.getScheduler().execute(plugin, () -> refresh(), () -> {}, 0L));
                    } else {
                        plugin.getHomelandManager().deleteHomeland(p, homelandName,
                                () -> p.getScheduler().execute(plugin, () -> {
                                    close();
                                    HomelandGUI.open(plugin, p);
                                }, () -> {}, 0L),
                                () -> p.getScheduler().execute(plugin, () -> refresh(), () -> {}, 0L));
                    }
                });
            } else {
                setItem(cfg.getDeleteSlot(), createDeleteItem(), (p, e) -> {
                    if (plugin.getConfigManager().isBranchMode()) {
                        MessageUtil.send(p, plugin.getConfigManager().getMessage("branch-mode-gui"));
                        return;
                    }
                    pendingDelete = true;
                    refresh();
                });
            }
        }

        // 底部导航
        if (isAdmin) {
            setItem(cfg.getBackSlot(), createBackItem(
                    plugin.getConfigManager().getGUIConfig().getGlobal().getBackMaterial(),
                    "gui.admin-back-player-homelands"), (p, e) -> {
                close();
                HomelandAdminGUI.HomelandAdminPlayerGUI.open(plugin, p, targetUuid, targetName);
            });
        } else {
            setItem(cfg.getBackSlot(), createBackItem(
                    plugin.getConfigManager().getGUIConfig().getGlobal().getBackMaterial(),
                    "gui.back-item-name"), (p, e) -> {
                close();
                HomelandGUI.open(plugin, p);
            });
        }

        setItem(cfg.getCloseSlot(), createCloseItem(), (p, e) -> close());
    }

    // ===== Item 创建方法 =====

    private ItemStack createInfoItem(Homeland h) {
        ItemStack item = new ItemStack(cfg.getInfoMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        ConfigManager config = plugin.getConfigManager();
        meta.displayName(MessageUtil.guiName(config.getMessage("gui.homeland-item-name", "name", h.getName())));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.homeland-item-border", "border", String.valueOf(h.getBorderRadius()))));
        lore.add(MessageUtil.guiLore(config.getMessage(h.hasNether() ? "gui.homeland-item-nether-unlocked" : "gui.homeland-item-nether-locked")));
        lore.add(MessageUtil.guiLore(config.getMessage(h.hasEnd() ? "gui.homeland-item-end-unlocked" : "gui.homeland-item-end-locked")));
        if (!isAdmin) {
            lore.add(MessageUtil.guiLore(config.getMessage(h.isPublic() ? "gui.homeland-item-public" : "gui.homeland-item-private")));
        }

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createTeleportItem() {
        ItemStack item = new ItemStack(cfg.getTeleportMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(MessageUtil.guiName(plugin.getConfigManager().getMessage("gui.teleport-item-name")));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(plugin.getConfigManager().getMessage("gui.teleport-item-lore")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createExpandBorderItem(Homeland h, ConfigManager config) {
        ItemStack item = new ItemStack(cfg.getExpandMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        int newRadius = h.getBorderRadius() + config.getBorderExpandStep();

        meta.displayName(MessageUtil.guiName(config.getMessage("gui.expand-item-name")));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.expand-item-range",
                "current", String.valueOf(h.getBorderRadius()),
                "new", String.valueOf(newRadius))));
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));

        PriceTier tier = config.getExpansionCost(h.getBorderRadius());
        double expandMoney = tier.getMoney();
        int expandPoints = tier.getPoints();
        if (expandMoney > 0) {
            lore.add(MessageUtil.guiLore(config.getMessage("gui.expand-item-money", "money", String.format("%.0f", expandMoney))));
        }
        if (expandPoints > 0) {
            lore.add(MessageUtil.guiLore(config.getMessage("gui.expand-item-points", "points", String.valueOf(expandPoints))));
        }
        if (expandMoney == 0 && expandPoints == 0) {
            lore.add(MessageUtil.guiLore(config.getMessage("gui.expand-item-free")));
        }

        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.expand-item-click")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createExpandAdminItem(Homeland h, ConfigManager config) {
        ItemStack item = new ItemStack(cfg.getExpandMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        int newRadius = h.getBorderRadius() + config.getBorderExpandStep();

        meta.displayName(MessageUtil.guiName(config.getMessage("gui.expand-item-name")));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.expand-item-range",
                "current", String.valueOf(h.getBorderRadius()),
                "new", String.valueOf(newRadius))));
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.expand-item-click")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createExpandConfirmItem(Homeland h, ConfigManager config) {
        ItemStack item = new ItemStack(cfg.getExpandConfirmMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        int newRadius = h.getBorderRadius() + config.getBorderExpandStep();

        meta.displayName(MessageUtil.guiName(config.getMessage("gui.expand-confirm-item-name")));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.expand-item-range",
                "current", String.valueOf(h.getBorderRadius()),
                "new", String.valueOf(newRadius))));
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.expand-confirm-click")));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.expand-confirm-cancel")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createUnlockItem(ConfigManager config, String nameKey, double money, int points) {
        Material material = nameKey.contains("nether") ? cfg.getNetherUnlockMaterial() : cfg.getEndUnlockMaterial();
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(MessageUtil.guiName(config.getMessage(nameKey)));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));

        if (money > 0) {
            lore.add(MessageUtil.guiLore(config.getMessage("gui.expand-item-money", "money", String.format("%.0f", money))));
        }
        if (points > 0) {
            lore.add(MessageUtil.guiLore(config.getMessage("gui.expand-item-points", "points", String.valueOf(points))));
        }
        if (money == 0 && points == 0) {
            lore.add(MessageUtil.guiLore(config.getMessage("gui.expand-item-free")));
        }

        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.unlock-item-click")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createUnlockAdminItem(ConfigManager config, String nameKey) {
        Material material = nameKey.contains("nether") ? cfg.getNetherUnlockMaterial() : cfg.getEndUnlockMaterial();
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(MessageUtil.guiName(config.getMessage(nameKey)));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.admin-item-free")));
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.unlock-item-click")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createLockDimensionItem(Material lockMaterial, String nameKey, String loreKey, String clickKey) {
        ItemStack item = new ItemStack(lockMaterial);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        ConfigManager config = plugin.getConfigManager();
        meta.displayName(MessageUtil.guiName(config.getMessage(nameKey)));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage(loreKey)));
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage(clickKey)));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createLockConfirmItem(Material confirmMaterial, String dimension) {
        ItemStack item = new ItemStack(confirmMaterial);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        ConfigManager config = plugin.getConfigManager();
        meta.displayName(MessageUtil.guiName(config.getMessage("gui.lock-confirm-item-name", "dimension", dimension)));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.lock-confirm-click")));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.expand-confirm-cancel")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createPublicItem(Homeland h) {
        boolean isPublic = h.isPublic();
        ItemStack item = new ItemStack(isPublic ? cfg.getPublicMaterial() : cfg.getPrivateMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(MessageUtil.guiName(plugin.getConfigManager().getMessage(
                isPublic ? "gui.public-item-name" : "gui.private-item-name")));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(plugin.getConfigManager().getMessage(
                isPublic ? "gui.public-item-lore" : "gui.private-item-lore")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createInviteManageItem() {
        ItemStack item = new ItemStack(cfg.getInviteMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(MessageUtil.guiName(plugin.getConfigManager().getMessage("gui.invite-manage-item-name")));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(plugin.getConfigManager().getMessage("gui.invite-manage-item-lore")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createDeleteItem() {
        ItemStack item = new ItemStack(cfg.getDeleteMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        ConfigManager config = plugin.getConfigManager();
        meta.displayName(MessageUtil.guiName(config.getMessage("gui.delete-item-name")));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.delete-item-lore")));
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.delete-item-click")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createDeleteConfirmItem() {
        ItemStack item = new ItemStack(cfg.getDeleteConfirmMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        ConfigManager config = plugin.getConfigManager();
        meta.displayName(MessageUtil.guiName(config.getMessage("gui.delete-confirm-item-name")));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.delete-confirm-click")));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.expand-confirm-cancel")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createReturnToMainItem() {
        ItemStack item = new ItemStack(cfg.getReturnToMainMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(MessageUtil.guiName(plugin.getConfigManager().getMessage("gui.return-to-main-item-name")));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(plugin.getConfigManager().getMessage("gui.return-to-main-item-lore")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createGameruleItem() {
        ItemStack item = new ItemStack(cfg.getGameruleMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        ConfigManager config = plugin.getConfigManager();
        meta.displayName(MessageUtil.guiName(config.getMessage("gui.gamerule-item-name")));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.gamerule-item-lore")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createVisitorFlagItem() {
        ItemStack item = new ItemStack(cfg.getVisitorFlagMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        ConfigManager config = plugin.getConfigManager();
        meta.displayName(MessageUtil.guiName(config.getMessage("gui.visitor-flag-item-name")));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.visitor-flag-item-lore")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createResetItem() {
        ItemStack item = new ItemStack(cfg.getResetMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        ConfigManager config = plugin.getConfigManager();
        meta.displayName(MessageUtil.guiName(config.getMessage("gui.reset-item-name")));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.reset-item-lore")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createNoPermissionItem(ConfigManager config, String nameKey, Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(MessageUtil.guiName(config.getMessage(nameKey)));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.homeland-item-no-permission")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createBiomeItem() {
        ItemStack item = new ItemStack(cfg.getBiomeMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        ConfigManager config = plugin.getConfigManager();
        meta.displayName(MessageUtil.guiName(config.getMessage("gui.biome-item-name")));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty().decoration(TextDecoration.ITALIC, false));
        lore.add(MessageUtil.guiLore(config.getMessage("gui.biome-item-lore")));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

}
