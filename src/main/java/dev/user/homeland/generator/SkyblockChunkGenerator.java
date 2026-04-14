package dev.user.homeland.generator;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.ChunkGenerator.ChunkData;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.Random;

/**
 * 空岛世界生成器。
 * 所有区块为空气，仅在 chunk(0,0) 的出生点放置一个起始岛。
 */
public class SkyblockChunkGenerator extends ChunkGenerator {

    // 起始岛参数
    private static final int ISLAND_Y_BOTTOM = 60;  // 最底层泥土
    private static final int ISLAND_Y_TOP = 62;      // 草方块层
    private static final int ISLAND_MIN = 6;          // 局部 X/Z 最小值
    private static final int ISLAND_MAX = 10;         // 局部 X/Z 最大值 (5x5)
    private static final int TREE_X = 8;              // 树干 X
    private static final int TREE_Z = 8;              // 树干 Z
    private static final int TREE_Y_BASE = 63;        // 树干底部
    private static final int TREE_Y_TOP = 67;         // 树干顶部

    @Override
    public boolean canSpawn(@NotNull World world, int x, int z) {
        return true;
    }

    // 显式禁用所有原版地形生成阶段
    // 不覆写的话，Worlds 插件通过 NMS 创建世界时可能会应用默认噪声地形

    @Override
    public boolean shouldGenerateNoise(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ) {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ) {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ) {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ) {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ) {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ) {
        return false;
    }

    @Override
    public @NotNull ChunkData generateChunkData(@NotNull World world, @NotNull Random random,
                                                 int chunkX, int chunkZ, @NotNull BiomeGrid biome) {
        ChunkData data = createChunkData(world);

        if (chunkX != 0 || chunkZ != 0) {
            return data; // 非出生点区块，全部为空气
        }

        // 放置 5x5 起始岛平台
        for (int x = ISLAND_MIN; x <= ISLAND_MAX; x++) {
            for (int z = ISLAND_MIN; z <= ISLAND_MAX; z++) {
                for (int y = ISLAND_Y_BOTTOM; y < ISLAND_Y_TOP; y++) {
                    data.setBlock(x, y, z, Material.DIRT);
                }
                data.setBlock(x, ISLAND_Y_TOP, z, Material.GRASS_BLOCK);
            }
        }

        // 放置橡木树干
        for (int y = TREE_Y_BASE; y <= TREE_Y_TOP; y++) {
            data.setBlock(TREE_X, y, TREE_Z, Material.OAK_LOG);
        }

        // 树叶层 - 仿照原版橡树树冠
        // 树干: Y=63~67 (5格)
        // Y=65: 3x3满树叶（紧贴树干下层，跳过树干位置）
        setLeafSquare(data, TREE_X, TREE_Y_BASE + 2, TREE_Z, 1, true);

        // Y=66: 3x3满树叶（紧贴树干上层）
        setLeafSquare(data, TREE_X, TREE_Y_BASE + 3, TREE_Z, 1, true);

        // Y=67 (树干顶部): 5x5减四角
        setLeafSquare(data, TREE_X, TREE_Y_TOP, TREE_Z, 2, false);

        // Y=68: 5x5减四角
        setLeafSquare(data, TREE_X, TREE_Y_TOP + 1, TREE_Z, 2, false);

        // Y=69: 3x3减四角
        setLeafSquare(data, TREE_X, TREE_Y_TOP + 2, TREE_Z, 1, false);

        // Y=70: 十字形顶端
        setLeafPlus(data, TREE_X, TREE_Y_TOP + 3, TREE_Z);

        return data;
    }

    /**
     * 放置方形树叶层。
     * @param radius 半径（1=3x3, 2=5x5）
     * @param skipCenter 是否跳过中心（树干所在位置）
     */
    private void setLeafSquare(ChunkData data, int cx, int y, int cz, int radius, boolean skipCenter) {
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                // 跳过四角
                if (Math.abs(x - cx) == radius && Math.abs(z - cz) == radius) continue;
                // 跳过中心树干
                if (skipCenter && x == cx && z == cz) continue;
                data.setBlock(x, y, z, Material.OAK_LEAVES);
            }
        }
    }

    /**
     * 放置十字形树叶
     */
    private void setLeafPlus(ChunkData data, int cx, int y, int cz) {
        data.setBlock(cx, y, cz, Material.OAK_LEAVES);
        data.setBlock(cx - 1, y, cz, Material.OAK_LEAVES);
        data.setBlock(cx + 1, y, cz, Material.OAK_LEAVES);
        data.setBlock(cx, y, cz - 1, Material.OAK_LEAVES);
        data.setBlock(cx, y, cz + 1, Material.OAK_LEAVES);
    }
}
