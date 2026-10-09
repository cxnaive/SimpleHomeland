package dev.user.homeland.manager;

import dev.user.homeland.config.ConfigManager;
import dev.user.homeland.config.ConfigManager.GameRuleConfig;
import dev.user.homeland.SimpleHomelandPlugin;
import net.thenextlvl.worlds.Level;
import net.thenextlvl.worlds.generator.Generator;
import net.thenextlvl.worlds.generator.GeneratorType;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.Bukkit;
import dev.user.homeland.model.Homeland;

public final class HomelandWorldSettings {

    private HomelandWorldSettings() {
    }

    /** Applies the load postconditions on the global region thread before entry is allowed. */
    public static void applyLoadedWorld(SimpleHomelandPlugin plugin, Homeland homeland, World world) {
        world.setAutoSave(true);
        world.getWorldBorder().setCenter(0, 0);
        world.getWorldBorder().setSize(homeland.getBorderRadius() * 2);
        if (world.key().value().equals(homeland.getWorldKey())) return;
        World overworld = Bukkit.getWorld(HomelandWorldKeys.bukkitKey(homeland.getWorldKey()));
        if (overworld != null && plugin.getWorldService().isReady(homeland.getWorldKey())) {
            copyGamerules(overworld, world, plugin.getConfigManager());
            copyDifficulty(overworld, world);
        } else {
            applyDefaultGamerules(world, plugin.getConfigManager());
            applyDefaultDifficulty(world, plugin.getConfigManager());
        }
    }

    public static void applyWorldTypeGenerator(SimpleHomelandPlugin plugin, Level.Builder builder,
                                               String worldType) {
        if ("void".equals(worldType)) {
            builder.generator(Generator.of(plugin, "void"))
                    .structures(false)
                    .bonusChest(false);
        } else if ("flat".equals(worldType)) {
            builder.generatorType(GeneratorType.FLAT)
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
