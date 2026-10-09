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
