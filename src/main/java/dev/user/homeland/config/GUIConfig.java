package dev.user.homeland.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/**
 * GUI 布局配置，从 gui.yml 加载。
 * 每个嵌套类对应一个 GUI 画面的 slot/material 配置。
 */
public class GUIConfig {

    private final Global global;
    private final HomelandList homelandList;
    private final Manage manage;
    private final Create create;
    private final GameRule gamerule;
    private final Admin admin;
    private final Visitor visitor;
    private final VisitorFlag visitorFlag;
    private final InviteManage inviteManage;
    private final Reset reset;

    public GUIConfig(FileConfiguration config) {
        this.global = new Global(safeSection(config, "global"));
        this.homelandList = new HomelandList(safeSection(config, "homeland-list"));
        this.manage = new Manage(safeSection(config, "manage"));
        this.create = new Create(safeSection(config, "create"));
        this.gamerule = new GameRule(safeSection(config, "gamerule"));
        this.admin = new Admin(safeSection(config, "admin"));
        this.visitor = new Visitor(safeSection(config, "visitor"));
        this.visitorFlag = new VisitorFlag(safeSection(config, "visitor-flag"));
        this.inviteManage = new InviteManage(safeSection(config, "invite-manage"));
        this.reset = new Reset(safeSection(config, "reset"));
    }

    public Global getGlobal() { return global; }
    public HomelandList getHomelandList() { return homelandList; }
    public Manage getManage() { return manage; }
    public Create getCreate() { return create; }
    public GameRule getGamerule() { return gamerule; }
    public Admin getAdmin() { return admin; }
    public Visitor getVisitor() { return visitor; }
    public VisitorFlag getVisitorFlag() { return visitorFlag; }
    public InviteManage getInviteManage() { return inviteManage; }
    public Reset getReset() { return reset; }

    // ==================== 辅助方法 ====================

    private static Material parseMaterial(ConfigurationSection sec, String key, String defaultVal) {
        try {
            return Material.valueOf(sec.getString(key, defaultVal).toUpperCase());
        } catch (IllegalArgumentException e) {
            return Material.valueOf(defaultVal);
        }
    }

    private static int[] parseIntArray(ConfigurationSection sec, String key, int[] defaultVal) {
        List<Integer> list = sec.getIntegerList(key);
        if (list.isEmpty()) return defaultVal;
        return list.stream().mapToInt(i -> i).toArray();
    }

    private static ConfigurationSection safeSection(ConfigurationSection parent, String key) {
        ConfigurationSection sec = parent.getConfigurationSection(key);
        return sec != null ? sec : parent.createSection(key);
    }

    // ==================== 全局设置 ====================

    public static class Global {
        private final Material borderMaterial;
        private final Material closeMaterial;
        private final Material backMaterial;
        private final Material pageButtonMaterial;
        private final Material pageInfoMaterial;

        public Global(ConfigurationSection sec) {
            this.borderMaterial = parseMaterial(sec, "border-material", "BLACK_STAINED_GLASS_PANE");
            this.closeMaterial = parseMaterial(sec, "close-material", "BARRIER");
            this.backMaterial = parseMaterial(sec, "back-material", "ARROW");
            this.pageButtonMaterial = parseMaterial(sec, "page-button-material", "ARROW");
            this.pageInfoMaterial = parseMaterial(sec, "page-info-material", "PAPER");
        }

        public Material getBorderMaterial() { return borderMaterial; }
        public Material getCloseMaterial() { return closeMaterial; }
        public Material getBackMaterial() { return backMaterial; }
        public Material getPageButtonMaterial() { return pageButtonMaterial; }
        public Material getPageInfoMaterial() { return pageInfoMaterial; }
    }

    // ==================== 家园列表 GUI ====================

    public static class HomelandList {
        private final int size;
        private final int itemsPerPage;
        private final int[] pageSlots;
        private final int pagePrevSlot;
        private final int pageNextSlot;
        private final Material homelandMaterial;
        private final int emptySlot;
        private final Material emptyMaterial;
        private final int createSlot;
        private final Material createMaterial;
        private final int returnToMainSlot;
        private final Material returnToMainMaterial;
        private final int closeSlot;
        private final int onlinePlayersSlot;
        private final Material onlinePlayersMaterial;
        private final Material invitedMaterial;

        public HomelandList(ConfigurationSection sec) {
            this.size = sec.getInt("size", 54);
            this.itemsPerPage = sec.getInt("items-per-page", 28);
            this.pageSlots = parseIntArray(sec, "page-slots", new int[]{10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43});
            this.pagePrevSlot = sec.getInt("page-prev-slot", 46);
            this.pageNextSlot = sec.getInt("page-next-slot", 53);
            this.homelandMaterial = parseMaterial(sec, "homeland-material", "GRASS_BLOCK");
            this.emptySlot = sec.getInt("empty-slot", 22);
            this.emptyMaterial = parseMaterial(sec, "empty-material", "BARRIER");
            this.createSlot = sec.getInt("create-slot", 48);
            this.createMaterial = parseMaterial(sec, "create-material", "NETHER_STAR");
            this.returnToMainSlot = sec.getInt("return-to-main-slot", 45);
            this.returnToMainMaterial = parseMaterial(sec, "return-to-main-material", "COMPASS");
            this.closeSlot = sec.getInt("close-slot", 49);
            this.onlinePlayersSlot = sec.getInt("online-players-slot", 50);
            this.onlinePlayersMaterial = parseMaterial(sec, "online-players-material", "PLAYER_HEAD");
            this.invitedMaterial = parseMaterial(sec, "invited-material", "OAK_DOOR");
        }

        public int getSize() { return size; }
        public int getItemsPerPage() { return itemsPerPage; }
        public int[] getPageSlots() { return pageSlots; }
        public int getPagePrevSlot() { return pagePrevSlot; }
        public int getPageNextSlot() { return pageNextSlot; }
        public Material getHomelandMaterial() { return homelandMaterial; }
        public int getEmptySlot() { return emptySlot; }
        public Material getEmptyMaterial() { return emptyMaterial; }
        public int getCreateSlot() { return createSlot; }
        public Material getCreateMaterial() { return createMaterial; }
        public int getReturnToMainSlot() { return returnToMainSlot; }
        public Material getReturnToMainMaterial() { return returnToMainMaterial; }
        public int getCloseSlot() { return closeSlot; }
        public int getOnlinePlayersSlot() { return onlinePlayersSlot; }
        public Material getOnlinePlayersMaterial() { return onlinePlayersMaterial; }
        public Material getInvitedMaterial() { return invitedMaterial; }
    }

    // ==================== 家园管理 GUI ====================

    public static class Manage {
        private final int size;
        private final int infoSlot;
        private final Material infoMaterial;
        private final int teleportSlot;
        private final Material teleportMaterial;
        private final int gameruleSlot;
        private final Material gameruleMaterial;
        private final int returnToMainSlot;
        private final Material returnToMainMaterial;
        private final int netherSlot;
        private final Material netherUnlockMaterial;
        private final Material netherLockMaterial;
        private final Material netherConfirmMaterial;
        private final int endSlot;
        private final Material endUnlockMaterial;
        private final Material endLockMaterial;
        private final Material endConfirmMaterial;
        private final int publicSlot;
        private final Material publicMaterial;
        private final Material privateMaterial;
        private final int inviteSlot;
        private final Material inviteMaterial;
        private final int expandSlot;
        private final Material expandMaterial;
        private final Material expandConfirmMaterial;
        private final int uninviteSlot;
        private final Material uninviteMaterial;
        private final int deleteSlot;
        private final Material deleteMaterial;
        private final Material deleteConfirmMaterial;
        private final int backSlot;
        private final int closeSlot;
        private final int visitorFlagSlot;
        private final Material visitorFlagMaterial;
        private final int resetSlot;
        private final Material resetMaterial;

        public Manage(ConfigurationSection sec) {
            this.size = sec.getInt("size", 54);
            this.infoSlot = sec.getInt("info-slot", 13);
            this.infoMaterial = parseMaterial(sec, "info-material", "GRASS_BLOCK");
            this.teleportSlot = sec.getInt("teleport-slot", 20);
            this.teleportMaterial = parseMaterial(sec, "teleport-material", "ENDER_PEARL");
            this.gameruleSlot = sec.getInt("gamerule-slot", 21);
            this.gameruleMaterial = parseMaterial(sec, "gamerule-material", "BOOK");
            this.returnToMainSlot = sec.getInt("return-to-main-slot", 22);
            this.returnToMainMaterial = parseMaterial(sec, "return-to-main-material", "COMPASS");
            this.netherSlot = sec.getInt("nether-slot", 19);
            this.netherUnlockMaterial = parseMaterial(sec, "nether-unlock-material", "NETHERRACK");
            this.netherLockMaterial = parseMaterial(sec, "nether-lock-material", "RED_STAINED_GLASS_PANE");
            this.netherConfirmMaterial = parseMaterial(sec, "nether-confirm-material", "ORANGE_STAINED_GLASS_PANE");
            this.endSlot = sec.getInt("end-slot", 23);
            this.endUnlockMaterial = parseMaterial(sec, "end-unlock-material", "END_STONE");
            this.endLockMaterial = parseMaterial(sec, "end-lock-material", "RED_STAINED_GLASS_PANE");
            this.endConfirmMaterial = parseMaterial(sec, "end-confirm-material", "ORANGE_STAINED_GLASS_PANE");
            this.publicSlot = sec.getInt("public-slot", 28);
            this.publicMaterial = parseMaterial(sec, "public-material", "LIME_STAINED_GLASS_PANE");
            this.privateMaterial = parseMaterial(sec, "private-material", "GRAY_STAINED_GLASS_PANE");
            this.inviteSlot = sec.getInt("invite-slot", 29);
            this.inviteMaterial = parseMaterial(sec, "invite-material", "WRITABLE_BOOK");
            this.expandSlot = sec.getInt("expand-slot", 30);
            this.expandMaterial = parseMaterial(sec, "expand-material", "EXPERIENCE_BOTTLE");
            this.expandConfirmMaterial = parseMaterial(sec, "expand-confirm-material", "LIME_STAINED_GLASS_PANE");
            this.uninviteSlot = sec.getInt("uninvite-slot", 31);
            this.uninviteMaterial = parseMaterial(sec, "uninvite-material", "BOOK");
            this.deleteSlot = sec.getInt("delete-slot", 32);
            this.deleteMaterial = parseMaterial(sec, "delete-material", "RED_STAINED_GLASS_PANE");
            this.deleteConfirmMaterial = parseMaterial(sec, "delete-confirm-material", "RED_STAINED_GLASS_PANE");
            this.backSlot = sec.getInt("back-slot", 45);
            this.closeSlot = sec.getInt("close-slot", 53);
            this.visitorFlagSlot = sec.getInt("visitor-flag-slot", 24);
            this.visitorFlagMaterial = parseMaterial(sec, "visitor-flag-material", "OAK_SIGN");
            this.resetSlot = sec.getInt("reset-slot", 34);
            this.resetMaterial = parseMaterial(sec, "reset-material", "SPONGE");
        }

        public int getSize() { return size; }
        public int getInfoSlot() { return infoSlot; }
        public Material getInfoMaterial() { return infoMaterial; }
        public int getTeleportSlot() { return teleportSlot; }
        public Material getTeleportMaterial() { return teleportMaterial; }
        public int getGameruleSlot() { return gameruleSlot; }
        public Material getGameruleMaterial() { return gameruleMaterial; }
        public int getReturnToMainSlot() { return returnToMainSlot; }
        public Material getReturnToMainMaterial() { return returnToMainMaterial; }
        public int getNetherSlot() { return netherSlot; }
        public Material getNetherUnlockMaterial() { return netherUnlockMaterial; }
        public Material getNetherLockMaterial() { return netherLockMaterial; }
        public Material getNetherConfirmMaterial() { return netherConfirmMaterial; }
        public int getEndSlot() { return endSlot; }
        public Material getEndUnlockMaterial() { return endUnlockMaterial; }
        public Material getEndLockMaterial() { return endLockMaterial; }
        public Material getEndConfirmMaterial() { return endConfirmMaterial; }
        public int getPublicSlot() { return publicSlot; }
        public Material getPublicMaterial() { return publicMaterial; }
        public Material getPrivateMaterial() { return privateMaterial; }
        public int getInviteSlot() { return inviteSlot; }
        public Material getInviteMaterial() { return inviteMaterial; }
        public int getExpandSlot() { return expandSlot; }
        public Material getExpandMaterial() { return expandMaterial; }
        public Material getExpandConfirmMaterial() { return expandConfirmMaterial; }
        public int getUninviteSlot() { return uninviteSlot; }
        public Material getUninviteMaterial() { return uninviteMaterial; }
        public int getDeleteSlot() { return deleteSlot; }
        public Material getDeleteMaterial() { return deleteMaterial; }
        public Material getDeleteConfirmMaterial() { return deleteConfirmMaterial; }
        public int getBackSlot() { return backSlot; }
        public int getCloseSlot() { return closeSlot; }
        public int getVisitorFlagSlot() { return visitorFlagSlot; }
        public Material getVisitorFlagMaterial() { return visitorFlagMaterial; }
        public int getResetSlot() { return resetSlot; }
        public Material getResetMaterial() { return resetMaterial; }
    }

    // ==================== 创建家园地形选择 GUI ====================

    public static class Create {
        private final int size;
        private final int defaultSlot;
        private final Material defaultMaterial;
        private final int voidSlot;
        private final Material voidMaterial;
        private final int flatSlot;
        private final Material flatMaterial;
        private final int closeSlot;

        public Create(ConfigurationSection sec) {
            this.size = sec.getInt("size", 27);
            this.defaultSlot = sec.getInt("default-slot", 11);
            this.defaultMaterial = parseMaterial(sec, "default-material", "GRASS_BLOCK");
            this.voidSlot = sec.getInt("void-slot", 13);
            this.voidMaterial = parseMaterial(sec, "void-material", "LIGHT_BLUE_STAINED_GLASS");
            this.flatSlot = sec.getInt("flat-slot", 15);
            this.flatMaterial = parseMaterial(sec, "flat-material", "SAND");
            this.closeSlot = sec.getInt("close-slot", 22);
        }

        public int getSize() { return size; }
        public int getDefaultSlot() { return defaultSlot; }
        public Material getDefaultMaterial() { return defaultMaterial; }
        public int getVoidSlot() { return voidSlot; }
        public Material getVoidMaterial() { return voidMaterial; }
        public int getFlatSlot() { return flatSlot; }
        public Material getFlatMaterial() { return flatMaterial; }
        public int getCloseSlot() { return closeSlot; }
    }

    // ==================== 游戏规则 GUI ====================

    public static class GameRule {
        private final int size;
        private final int itemsPerPage;
        private final int[] contentSlots;
        private final int emptySlot;
        private final int backSlot;
        private final int prevSlot;
        private final int pageInfoSlot;
        private final int nextSlot;
        private final int closeSlot;

        public GameRule(ConfigurationSection sec) {
            this.size = sec.getInt("size", 54);
            this.itemsPerPage = sec.getInt("items-per-page", 28);
            this.contentSlots = parseIntArray(sec, "content-slots",
                    new int[]{10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43});
            this.emptySlot = sec.getInt("empty-slot", 22);
            this.backSlot = sec.getInt("back-slot", 45);
            this.prevSlot = sec.getInt("prev-slot", 47);
            this.pageInfoSlot = sec.getInt("page-info-slot", 49);
            this.nextSlot = sec.getInt("next-slot", 51);
            this.closeSlot = sec.getInt("close-slot", 53);
        }

        public int getSize() { return size; }
        public int getItemsPerPage() { return itemsPerPage; }
        public int[] getContentSlots() { return contentSlots; }
        public int getEmptySlot() { return emptySlot; }
        public int getBackSlot() { return backSlot; }
        public int getPrevSlot() { return prevSlot; }
        public int getPageInfoSlot() { return pageInfoSlot; }
        public int getNextSlot() { return nextSlot; }
        public int getCloseSlot() { return closeSlot; }
    }

    // ==================== 管理员 GUI ====================

    public static class Admin {
        private final int size;
        private final int itemsPerPage;
        private final int[] pageSlots;
        private final int pagePrevSlot;
        private final int pageNextSlot;
        private final int infoSlot;
        private final Material infoMaterial;
        private final Material playerMaterial;
        private final int emptySlot;
        private final Material emptyMaterial;
        private final int closeSlot;
        private final int lobbyFlagSlot;
        private final Material lobbyFlagMaterial;
        private final PlayerHomelands playerHomelands;

        public Admin(ConfigurationSection sec) {
            this.size = sec.getInt("size", 54);
            this.itemsPerPage = sec.getInt("items-per-page", 28);
            this.pageSlots = parseIntArray(sec, "page-slots", new int[]{10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43});
            this.pagePrevSlot = sec.getInt("page-prev-slot", 45);
            this.pageNextSlot = sec.getInt("page-next-slot", 53);
            this.infoSlot = sec.getInt("info-slot", 4);
            this.infoMaterial = parseMaterial(sec, "info-material", "COMPASS");
            this.playerMaterial = parseMaterial(sec, "player-material", "PLAYER_HEAD");
            this.emptySlot = sec.getInt("empty-slot", 22);
            this.emptyMaterial = parseMaterial(sec, "empty-material", "BARRIER");
            this.closeSlot = sec.getInt("close-slot", 49);
            this.lobbyFlagSlot = sec.getInt("lobby-flag-slot", 48);
            this.lobbyFlagMaterial = parseMaterial(sec, "lobby-flag-material", "OAK_SIGN");
            this.playerHomelands = new PlayerHomelands(safeSection(sec, "player-homelands"));
        }

        public int getSize() { return size; }
        public int getItemsPerPage() { return itemsPerPage; }
        public int[] getPageSlots() { return pageSlots; }
        public int getPagePrevSlot() { return pagePrevSlot; }
        public int getPageNextSlot() { return pageNextSlot; }
        public int getInfoSlot() { return infoSlot; }
        public Material getInfoMaterial() { return infoMaterial; }
        public Material getPlayerMaterial() { return playerMaterial; }
        public int getEmptySlot() { return emptySlot; }
        public Material getEmptyMaterial() { return emptyMaterial; }
        public int getCloseSlot() { return closeSlot; }
        public int getLobbyFlagSlot() { return lobbyFlagSlot; }
        public Material getLobbyFlagMaterial() { return lobbyFlagMaterial; }
        public PlayerHomelands getPlayerHomelands() { return playerHomelands; }

        // 管理员 - 玩家家园列表
        public static class PlayerHomelands {
            private final int size;
            private final int itemsPerPage;
            private final int[] pageSlots;
            private final int pagePrevSlot;
            private final int pageNextSlot;
            private final Material homelandMaterial;
            private final int emptySlot;
            private final Material emptyMaterial;
            private final int createSlot;
            private final Material createMaterial;
            private final int backSlot;
            private final int closeSlot;

            public PlayerHomelands(ConfigurationSection sec) {
                this.size = sec.getInt("size", 54);
                this.itemsPerPage = sec.getInt("items-per-page", 28);
                this.pageSlots = parseIntArray(sec, "page-slots", new int[]{10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43});
                this.pagePrevSlot = sec.getInt("page-prev-slot", 46);
                this.pageNextSlot = sec.getInt("page-next-slot", 53);
                this.homelandMaterial = parseMaterial(sec, "homeland-material", "GRASS_BLOCK");
                this.emptySlot = sec.getInt("empty-slot", 22);
                this.emptyMaterial = parseMaterial(sec, "empty-material", "BARRIER");
                this.createSlot = sec.getInt("create-slot", 48);
                this.createMaterial = parseMaterial(sec, "create-material", "NETHER_STAR");
                this.backSlot = sec.getInt("back-slot", 45);
                this.closeSlot = sec.getInt("close-slot", 49);
            }

            public int getSize() { return size; }
            public int getItemsPerPage() { return itemsPerPage; }
            public int[] getPageSlots() { return pageSlots; }
            public int getPagePrevSlot() { return pagePrevSlot; }
            public int getPageNextSlot() { return pageNextSlot; }
            public Material getHomelandMaterial() { return homelandMaterial; }
            public int getEmptySlot() { return emptySlot; }
            public Material getEmptyMaterial() { return emptyMaterial; }
            public int getCreateSlot() { return createSlot; }
            public Material getCreateMaterial() { return createMaterial; }
            public int getBackSlot() { return backSlot; }
            public int getCloseSlot() { return closeSlot; }
        }
    }

    // ==================== 访问在线玩家 GUI ====================

    public static class Visitor {
        private final int size;
        private final int itemsPerPage;
        private final int[] pageSlots;
        private final int pagePrevSlot;
        private final int pageNextSlot;
        private final int infoSlot;
        private final Material infoMaterial;
        private final Material playerMaterial;
        private final int emptySlot;
        private final Material emptyMaterial;
        private final int backSlot;
        private final int closeSlot;
        private final PlayerHomelands playerHomelands;

        public Visitor(ConfigurationSection sec) {
            this.size = sec.getInt("size", 54);
            this.itemsPerPage = sec.getInt("items-per-page", 28);
            this.pageSlots = parseIntArray(sec, "page-slots", new int[]{10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43});
            this.pagePrevSlot = sec.getInt("page-prev-slot", 46);
            this.pageNextSlot = sec.getInt("page-next-slot", 53);
            this.infoSlot = sec.getInt("info-slot", 4);
            this.infoMaterial = parseMaterial(sec, "info-material", "COMPASS");
            this.playerMaterial = parseMaterial(sec, "player-material", "PLAYER_HEAD");
            this.emptySlot = sec.getInt("empty-slot", 22);
            this.emptyMaterial = parseMaterial(sec, "empty-material", "BARRIER");
            this.backSlot = sec.getInt("back-slot", 45);
            this.closeSlot = sec.getInt("close-slot", 49);
            this.playerHomelands = new PlayerHomelands(safeSection(sec, "player-homelands"));
        }

        public int getSize() { return size; }
        public int getItemsPerPage() { return itemsPerPage; }
        public int[] getPageSlots() { return pageSlots; }
        public int getPagePrevSlot() { return pagePrevSlot; }
        public int getPageNextSlot() { return pageNextSlot; }
        public int getInfoSlot() { return infoSlot; }
        public Material getInfoMaterial() { return infoMaterial; }
        public Material getPlayerMaterial() { return playerMaterial; }
        public int getEmptySlot() { return emptySlot; }
        public Material getEmptyMaterial() { return emptyMaterial; }
        public int getBackSlot() { return backSlot; }
        public int getCloseSlot() { return closeSlot; }
        public PlayerHomelands getPlayerHomelands() { return playerHomelands; }

        // 访问 - 玩家家园列表
        public static class PlayerHomelands {
            private final int size;
            private final int itemsPerPage;
            private final int[] pageSlots;
            private final int pagePrevSlot;
            private final int pageNextSlot;
            private final Material homelandAccessibleMaterial;
            private final Material homelandDeniedMaterial;
            private final int emptySlot;
            private final Material emptyMaterial;
            private final int backSlot;
            private final int closeSlot;

            public PlayerHomelands(ConfigurationSection sec) {
                this.size = sec.getInt("size", 54);
                this.itemsPerPage = sec.getInt("items-per-page", 28);
                this.pageSlots = parseIntArray(sec, "page-slots", new int[]{10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43});
                this.pagePrevSlot = sec.getInt("page-prev-slot", 46);
                this.pageNextSlot = sec.getInt("page-next-slot", 53);
                this.homelandAccessibleMaterial = parseMaterial(sec, "homeland-accessible-material", "GRASS_BLOCK");
                this.homelandDeniedMaterial = parseMaterial(sec, "homeland-denied-material", "REDSTONE_BLOCK");
                this.emptySlot = sec.getInt("empty-slot", 22);
                this.emptyMaterial = parseMaterial(sec, "empty-material", "BARRIER");
                this.backSlot = sec.getInt("back-slot", 45);
                this.closeSlot = sec.getInt("close-slot", 49);
            }

            public int getSize() { return size; }
            public int getItemsPerPage() { return itemsPerPage; }
            public int[] getPageSlots() { return pageSlots; }
            public int getPagePrevSlot() { return pagePrevSlot; }
            public int getPageNextSlot() { return pageNextSlot; }
            public Material getHomelandAccessibleMaterial() { return homelandAccessibleMaterial; }
            public Material getHomelandDeniedMaterial() { return homelandDeniedMaterial; }
            public int getEmptySlot() { return emptySlot; }
            public Material getEmptyMaterial() { return emptyMaterial; }
            public int getBackSlot() { return backSlot; }
            public int getCloseSlot() { return closeSlot; }
        }
    }

    // ==================== 访客权限设置 GUI ====================

    public static class VisitorFlag {
        private final int size;
        private final int itemsPerPage;
        private final int[] contentSlots;
        private final int emptySlot;
        private final int prevSlot;
        private final int pageInfoSlot;
        private final int nextSlot;
        private final int backSlot;
        private final int closeSlot;

        public VisitorFlag(ConfigurationSection sec) {
            this.size = sec.getInt("size", 54);
            this.itemsPerPage = sec.getInt("items-per-page", 28);
            this.contentSlots = parseIntArray(sec, "content-slots",
                    new int[]{10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43});
            this.emptySlot = sec.getInt("empty-slot", 22);
            this.prevSlot = sec.getInt("prev-slot", 47);
            this.pageInfoSlot = sec.getInt("page-info-slot", 49);
            this.nextSlot = sec.getInt("next-slot", 51);
            this.backSlot = sec.getInt("back-slot", 45);
            this.closeSlot = sec.getInt("close-slot", 53);
        }

        public int getSize() { return size; }
        public int getItemsPerPage() { return itemsPerPage; }
        public int[] getContentSlots() { return contentSlots; }
        public int getEmptySlot() { return emptySlot; }
        public int getPrevSlot() { return prevSlot; }
        public int getPageInfoSlot() { return pageInfoSlot; }
        public int getNextSlot() { return nextSlot; }
        public int getBackSlot() { return backSlot; }
        public int getCloseSlot() { return closeSlot; }
    }

    // ==================== 邀请管理 GUI ====================

    public static class InviteManage {
        private final int size;
        private final int itemsPerPage;
        private final int[] contentSlots;
        private final int modeSlot;
        private final Material modeOnlineMaterial;
        private final Material modeInvitedMaterial;
        private final int emptySlot;
        private final Material emptyMaterial;
        private final int prevSlot;
        private final int pageInfoSlot;
        private final int nextSlot;
        private final int backSlot;
        private final int closeSlot;

        public InviteManage(ConfigurationSection sec) {
            this.size = sec.getInt("size", 54);
            this.itemsPerPage = sec.getInt("items-per-page", 28);
            this.contentSlots = parseIntArray(sec, "content-slots",
                    new int[]{10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43});
            this.modeSlot = sec.getInt("mode-slot", 4);
            this.modeOnlineMaterial = parseMaterial(sec, "mode-online-material", "LIME_STAINED_GLASS_PANE");
            this.modeInvitedMaterial = parseMaterial(sec, "mode-invited-material", "ORANGE_STAINED_GLASS_PANE");
            this.emptySlot = sec.getInt("empty-slot", 22);
            this.emptyMaterial = parseMaterial(sec, "empty-material", "BARRIER");
            this.prevSlot = sec.getInt("prev-slot", 47);
            this.pageInfoSlot = sec.getInt("page-info-slot", 49);
            this.nextSlot = sec.getInt("next-slot", 51);
            this.backSlot = sec.getInt("back-slot", 45);
            this.closeSlot = sec.getInt("close-slot", 53);
        }

        public int getSize() { return size; }
        public int getItemsPerPage() { return itemsPerPage; }
        public int[] getContentSlots() { return contentSlots; }
        public int getModeSlot() { return modeSlot; }
        public Material getModeOnlineMaterial() { return modeOnlineMaterial; }
        public Material getModeInvitedMaterial() { return modeInvitedMaterial; }
        public int getEmptySlot() { return emptySlot; }
        public Material getEmptyMaterial() { return emptyMaterial; }
        public int getPrevSlot() { return prevSlot; }
        public int getPageInfoSlot() { return pageInfoSlot; }
        public int getNextSlot() { return nextSlot; }
        public int getBackSlot() { return backSlot; }
        public int getCloseSlot() { return closeSlot; }
    }

    // ==================== 重置世界 GUI ====================

    public static class Reset {
        private final int size;
        private final int overworldSlot;
        private final Material overworldMaterial;
        private final int netherSlot;
        private final Material netherMaterial;
        private final int endSlot;
        private final Material endMaterial;
        private final Material lockedMaterial;
        private final Material confirmMaterial;
        private final int closeSlot;

        public Reset(ConfigurationSection sec) {
            this.size = sec.getInt("size", 27);
            this.overworldSlot = sec.getInt("overworld-slot", 11);
            this.overworldMaterial = parseMaterial(sec, "overworld-material", "GRASS_BLOCK");
            this.netherSlot = sec.getInt("nether-slot", 13);
            this.netherMaterial = parseMaterial(sec, "nether-material", "NETHERRACK");
            this.endSlot = sec.getInt("end-slot", 15);
            this.endMaterial = parseMaterial(sec, "end-material", "END_STONE");
            this.lockedMaterial = parseMaterial(sec, "locked-material", "BARRIER");
            this.confirmMaterial = parseMaterial(sec, "confirm-material", "YELLOW_STAINED_GLASS_PANE");
            this.closeSlot = sec.getInt("close-slot", 22);
        }

        public int getSize() { return size; }
        public int getOverworldSlot() { return overworldSlot; }
        public Material getOverworldMaterial() { return overworldMaterial; }
        public int getNetherSlot() { return netherSlot; }
        public Material getNetherMaterial() { return netherMaterial; }
        public int getEndSlot() { return endSlot; }
        public Material getEndMaterial() { return endMaterial; }
        public Material getLockedMaterial() { return lockedMaterial; }
        public Material getConfirmMaterial() { return confirmMaterial; }
        public int getCloseSlot() { return closeSlot; }
    }
}
