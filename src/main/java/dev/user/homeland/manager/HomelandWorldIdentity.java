package dev.user.homeland.manager;

import dev.user.homeland.api.HomelandInfo;
import dev.user.homeland.model.HomelandDimension;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/** Resolves unloaded dimensions from the same shared DB used by branch servers. */
final class HomelandWorldIdentity {
    private static final String COLUMNS = "owner_uuid, name, world_key, world_uuid, nether_uuid, end_uuid, "
            + "border_radius, has_nether, has_end, is_public";

    private HomelandWorldIdentity() {}

    static HomelandInfo queryUuid(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + COLUMNS
                + " FROM homeland WHERE world_uuid = ? OR (has_nether = TRUE AND nether_uuid = ?)"
                + " OR (has_end = TRUE AND end_uuid = ?)")) {
            for (int index = 1; index <= 3; index++) statement.setString(index, uuid.toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                HomelandDimension dimension = uuid.toString().equals(rows.getString("world_uuid"))
                        ? HomelandDimension.OVERWORLD : uuid.toString().equals(rows.getString("nether_uuid"))
                        ? HomelandDimension.NETHER : HomelandDimension.END;
                return info(rows, dimension);
            }
        }
    }

    static HomelandInfo queryKey(Connection connection, String value) throws SQLException {
        String key = HomelandWorldKeys.normalize(value);
        HomelandDimension dimension = HomelandWorldKeys.dimension(key);
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + COLUMNS
                + " FROM homeland WHERE world_key = ?")) {
            statement.setString(1, HomelandWorldKeys.base(key));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                if (dimension != HomelandDimension.OVERWORLD
                        && !rows.getBoolean(dimension.databaseColumn())) return null;
                return info(rows, dimension);
            }
        }
    }

    static HomelandInfo queryName(Connection connection, String name) throws SQLException {
        HomelandDimension dimension = HomelandWorldKeys.nameDimension(name);
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + COLUMNS
                + " FROM homeland WHERE LOWER(name) = LOWER(?)")) {
            statement.setString(1, HomelandWorldKeys.baseName(name));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                if (dimension != HomelandDimension.OVERWORLD
                        && !rows.getBoolean(dimension.databaseColumn())) return null;
                return info(rows, dimension);
            }
        }
    }

    private static HomelandInfo info(ResultSet rows, HomelandDimension dimension) throws SQLException {
        String uuid = rows.getString(dimension.uuidColumn());
        return new HomelandInfo(UUID.fromString(rows.getString("owner_uuid")), rows.getString("name"),
                rows.getString("world_key") + dimension.worldKeySuffix(),
                uuid == null ? null : UUID.fromString(uuid), rows.getInt("border_radius"),
                rows.getBoolean("has_nether"), rows.getBoolean("has_end"), rows.getBoolean("is_public"));
    }
}
