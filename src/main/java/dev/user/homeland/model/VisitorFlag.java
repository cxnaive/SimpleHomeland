package dev.user.homeland.model;

import org.bukkit.Material;

public enum VisitorFlag {

    // 方块
    PLACE("place", "放置方块", false, Material.GRASS_BLOCK, Category.BLOCK),
    BREAK("break", "破坏方块", false, Material.DIAMOND_PICKAXE, Category.BLOCK),
    // 交互
    INTERACT("interact", "交互方块", false, Material.LEVER, Category.INTERACT),
    CONTAINER("container", "打开容器", false, Material.CHEST, Category.INTERACT),
    ANVIL("anvil", "使用铁砧", false, Material.ANVIL, Category.INTERACT),
    ENCHANT("enchant", "附魔/酿造", false, Material.ENCHANTING_TABLE, Category.INTERACT),
    // 物品
    PICKUP("pickup", "拾取物品", true, Material.HOPPER, Category.ITEM),
    DROP("drop", "丢弃物品", true, Material.DROPPER, Category.ITEM),
    // 战斗
    KILL_ANIMAL("kill_animal", "攻击动物", false, Material.COOKED_BEEF, Category.COMBAT),
    KILL_MONSTER("kill_monster", "攻击怪物", true, Material.BLAZE_POWDER, Category.COMBAT),
    PVP("pvp", "玩家对战", false, Material.IRON_SWORD, Category.COMBAT),
    // 其他
    FARMING("farming", "农业/动物", false, Material.WHEAT, Category.OTHER),
    VEHICLE("vehicle", "使用载具", false, Material.MINECART, Category.OTHER),
    BED("bed", "使用床", false, Material.RED_BED, Category.OTHER),
    TRADE("trade", "村民交易", false, Material.EMERALD, Category.OTHER),
    PROJECTILE("projectile", "投掷/射击", false, Material.BOW, Category.OTHER);

    private final String key;
    private final String displayName;
    private final boolean defaultValue;
    private final Material material;
    private final Category category;

    VisitorFlag(String key, String displayName, boolean defaultValue, Material material, Category category) {
        this.key = key;
        this.displayName = displayName;
        this.defaultValue = defaultValue;
        this.material = material;
        this.category = category;
    }

    public String getKey() { return key; }
    public String getDisplayName() { return displayName; }
    public boolean getDefaultValue() { return defaultValue; }
    public Material getMaterial() { return material; }
    public Category getCategory() { return category; }

    public static VisitorFlag fromKey(String key) {
        for (VisitorFlag flag : values()) {
            if (flag.key.equals(key)) return flag;
        }
        return null;
    }

    public enum Category {
        BLOCK, INTERACT, ITEM, COMBAT, OTHER
    }
}
