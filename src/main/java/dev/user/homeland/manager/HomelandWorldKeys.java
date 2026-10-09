package dev.user.homeland.manager;

import dev.user.homeland.model.HomelandDimension;
import net.kyori.adventure.key.Key;
import org.bukkit.NamespacedKey;

/** World identities are independent of the editable homeland display name. */
public final class HomelandWorldKeys {
    public static final String NAMESPACE = "homelands";

    private HomelandWorldKeys() {}

    public static String normalize(String value) {
        if (value.startsWith(NAMESPACE + ":")) return value.substring(NAMESPACE.length() + 1);
        if (value.startsWith("simplehomeland:")) return value.substring("simplehomeland:".length());
        if (value.startsWith(NAMESPACE + "_homeland_")) return value.substring(NAMESPACE.length() + 1);
        return value;
    }

    public static Key key(String value) {
        return Key.key(NAMESPACE, normalize(value));
    }

    public static NamespacedKey bukkitKey(String value) {
        return new NamespacedKey(NAMESPACE, normalize(value));
    }

    public static HomelandDimension dimension(String value) {
        String key = normalize(value);
        if (key.endsWith("_nether")) return HomelandDimension.NETHER;
        if (key.endsWith("_the_end")) return HomelandDimension.END;
        return HomelandDimension.OVERWORLD;
    }

    public static String base(String value) {
        String key = normalize(value);
        return key.substring(0, key.length() - dimension(key).worldKeySuffix().length());
    }

    public static HomelandDimension nameDimension(String name) {
        if (name.endsWith(" - 地狱")) return HomelandDimension.NETHER;
        if (name.endsWith(" - 末地")) return HomelandDimension.END;
        return HomelandDimension.OVERWORLD;
    }

    public static String baseName(String name) {
        HomelandDimension dimension = nameDimension(name);
        return dimension == HomelandDimension.OVERWORLD ? name
                : name.substring(0, name.length() - (" - " + dimension.displayName()).length());
    }
}
