package dev.user.homeland.manager;

import dev.user.homeland.config.ConfigManager;
import dev.user.homeland.config.ConfigManager.GameRuleConfig;
import dev.user.homeland.generator.SkyblockChunkGenerator;
import net.thenextlvl.worlds.api.generator.GeneratorType;
import net.thenextlvl.worlds.api.level.Level;
import net.thenextlvl.worlds.api.preset.Presets;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.World;

public final class HomelandWorldSettings {

    private HomelandWorldSettings() {
    }

    public static void applyWorldTypeGenerator(Level.Builder builder, String worldType) {
        if ("void".equals(worldType)) {
            builder.chunkGenerator(new SkyblockChunkGenerator())
                    .structures(false)
                    .bonusChest(false);
        } else if ("flat".equals(worldType)) {
            builder.generatorType(GeneratorType.FLAT)
                    .preset(Presets.CLASSIC_FLAT)
                    .structures(false)
                    .bonusChest(false);
        }
    }

    public static void applyDefaultGamerules(World world, ConfigManager config) {
        for (GameRuleConfig gameRule : config.getAllGameruleConfigs()) {
            if (!gameRule.isCustom()) {
                applyGamerule(world, gameRule, gameRule.getDefaultValue());
            }
        }
    }

    public static void copyGamerules(World source, World target, ConfigManager config) {
        for (GameRuleConfig gameRule : config.getAllGameruleConfigs()) {
            if (!gameRule.isCustom()) {
                applyGamerule(target, gameRule, getGameruleValue(source, gameRule));
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static void applyGamerule(World world, GameRuleConfig gameRule, String value) {
        if (gameRule.isBooleanType()) {
            world.setGameRule((GameRule<Boolean>) gameRule.getGameRule(),
                    Boolean.parseBoolean(value));
        } else {
            world.setGameRule((GameRule<Integer>) gameRule.getGameRule(),
                    Integer.parseInt(value));
        }
    }

    @SuppressWarnings("unchecked")
    private static String getGameruleValue(World world, GameRuleConfig gameRule) {
        if (gameRule.isBooleanType()) {
            return String.valueOf(world.getGameRuleValue(
                    (GameRule<Boolean>) gameRule.getGameRule()));
        }
        return String.valueOf(world.getGameRuleValue(
                (GameRule<Integer>) gameRule.getGameRule()));
    }

    public static void applyDefaultDifficulty(World world, ConfigManager config) {
        world.setDifficulty(Difficulty.valueOf(
                config.getDefaultDifficulty().toUpperCase(java.util.Locale.ROOT)));
    }

    public static void copyDifficulty(World source, World target) {
        target.setDifficulty(source.getDifficulty());
    }
}
