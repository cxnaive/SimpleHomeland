package dev.user.homeland.manager;

import dev.user.homeland.model.HomelandDimension;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HomelandWorldIdentityTest {
    @Test
    void sharedDatabaseQueriesRespectDimensionAndUnlockFlags() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:dimension-identity")) {
            UUID overworld = UUID.randomUUID();
            UUID nether = UUID.randomUUID();
            UUID end = UUID.randomUUID();
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE homeland (owner_uuid VARCHAR(36), name VARCHAR(32), "
                        + "world_key VARCHAR(64), world_uuid VARCHAR(36), nether_uuid VARCHAR(36), end_uuid VARCHAR(36), "
                        + "border_radius INT, has_nether BOOLEAN, has_end BOOLEAN, is_public BOOLEAN)");
                statement.execute("INSERT INTO homeland VALUES ('" + UUID.randomUUID() + "', 'Home', "
                        + "'homeland_1234abcd_1', '" + overworld + "', '" + nether + "', '" + end + "', "
                        + "128, TRUE, TRUE, FALSE)");
            }
            for (HomelandDimension dimension : HomelandDimension.values()) {
                UUID uuid = switch (dimension) {
                    case OVERWORLD -> overworld;
                    case NETHER -> nether;
                    case END -> end;
                };
                String key = "homeland_1234abcd_1" + dimension.worldKeySuffix();
                assertEquals(key, HomelandWorldIdentity.queryUuid(connection, uuid).worldKey());
                assertEquals(uuid, HomelandWorldIdentity.queryKey(connection, "homelands:" + key).worldUuid());
                String name = "HOME" + (dimension == HomelandDimension.OVERWORLD
                        ? "" : " - " + dimension.displayName());
                assertEquals(key, HomelandWorldIdentity.queryName(connection, name).worldKey());
            }
            // These are valid display names, even when they collide with a legacy
            // dimension alias or a modern Bukkit world name.
            for (String exactName : new String[]{"Home - 地狱", "Home - 末地", "homelands_homeland_1234abcd_1"}) {
                UUID exactUuid = UUID.randomUUID();
                try (var statement = connection.prepareStatement(
                        "INSERT INTO homeland (owner_uuid, name, world_key, world_uuid, border_radius, "
                                + "has_nether, has_end, is_public) VALUES (?, ?, ?, ?, 128, FALSE, FALSE, FALSE)")) {
                    statement.setString(1, UUID.randomUUID().toString());
                    statement.setString(2, exactName);
                    statement.setString(3, "homeland_exact_name_1");
                    statement.setString(4, exactUuid.toString());
                    statement.executeUpdate();
                }
                var result = HomelandWorldIdentity.queryName(connection, exactName.toUpperCase(java.util.Locale.ROOT));
                assertEquals("homeland_exact_name_1", result.worldKey());
                assertEquals(exactUuid, result.worldUuid());
                try (var statement = connection.createStatement()) {
                    statement.execute("DELETE FROM homeland WHERE world_key = 'homeland_exact_name_1'");
                }
            }
            assertEquals("homeland_1234abcd_1_nether",
                    HomelandWorldIdentity.queryName(connection, "Home - 地狱").worldKey());
            assertEquals("homeland_1234abcd_1",
                    HomelandWorldIdentity.queryName(connection, "homelands_homeland_1234abcd_1").worldKey());
            try (var statement = connection.createStatement()) {
                statement.execute("UPDATE homeland SET has_nether = FALSE, world_uuid = NULL");
            }
            assertNull(HomelandWorldIdentity.queryUuid(connection, overworld));
            assertNull(HomelandWorldIdentity.queryUuid(connection, nether));
            assertNull(HomelandWorldIdentity.queryKey(connection, "homeland_1234abcd_1_nether"));
            assertNull(HomelandWorldIdentity.queryName(connection, "Home - 地狱"));
            assertEquals(end, HomelandWorldIdentity.queryUuid(connection, end).worldUuid());
        }
    }

    @Test
    void supportsPublishedKeyAndNameFormsWithoutChangingStoredKey() {
        String key = "homeland_1234abcd_1_nether";
        assertEquals(key, HomelandWorldKeys.normalize("homelands:" + key));
        assertEquals(key, HomelandWorldKeys.normalize("simplehomeland:" + key));
        assertEquals(key, HomelandWorldKeys.normalize("homelands_" + key));
        assertEquals("homeland_1234abcd_1", HomelandWorldKeys.base(key));
        assertEquals(HomelandDimension.NETHER, HomelandWorldKeys.dimension(key));
        assertEquals("world", HomelandWorldKeys.normalize("world"));
    }
}
