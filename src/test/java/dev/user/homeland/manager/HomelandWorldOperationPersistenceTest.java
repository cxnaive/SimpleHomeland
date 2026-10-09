package dev.user.homeland.manager;

import dev.user.homeland.model.Homeland;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HomelandWorldOperationPersistenceTest {

    @Test
    void deleteCommitsCleanupMarkerAndHomelandRemovalTogether() throws Exception {
        try (Connection connection = database("delete")) {
            UUID owner = UUID.randomUUID();
            insertHomeland(connection, 1, owner, "home", "homeland_delete_1",
                    UUID.randomUUID(), "default");
            Homeland homeland = new Homeland(1, owner, "home", "homeland_delete_1",
                    128, true, true, false, null);

            HomelandWorldOperations.persistCleanupAndDeleteHomeland(connection, homeland);

            assertEquals(0, count(connection, "homeland"));
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT world_key FROM homeland_world_cleanup")) {
                assertTrue(result.next());
                assertEquals("homeland_delete_1", result.getString("world_key"));
            }
        }
    }

    @Test
    void resetPrepareAndFinishPreserveRecoveryMetadata() throws Exception {
        try (Connection connection = database("reset")) {
            UUID owner = UUID.randomUUID();
            UUID previousUuid = UUID.randomUUID();
            UUID replacementUuid = UUID.randomUUID();
            insertHomeland(connection, 2, owner, "home", "homeland_reset_1",
                    previousUuid, "default");

            HomelandWorldOperations.prepareOverworldResetRecord(
                    connection, 2, "flat", previousUuid);
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT * FROM homeland WHERE id = 2")) {
                assertTrue(result.next());
                assertNull(result.getString("world_uuid"));
                assertEquals("flat", result.getString("world_type"));
                assertTrue(result.getBoolean("overworld_reset_pending"));
                assertEquals(previousUuid.toString(),
                        result.getString("overworld_reset_previous_uuid"));
            }

            HomelandWorldOperations.recordGeneratedOverworldResetRecord(connection, 2, replacementUuid);
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT * FROM homeland WHERE id = 2")) {
                assertTrue(result.next());
                assertEquals(replacementUuid.toString(), result.getString("world_uuid"));
                assertTrue(result.getBoolean("overworld_reset_pending"));
                assertEquals(previousUuid.toString(), result.getString("overworld_reset_previous_uuid"));
            }

            HomelandWorldOperations.finishOverworldResetRecord(
                    connection, 2, "flat", replacementUuid);
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT * FROM homeland WHERE id = 2")) {
                assertTrue(result.next());
                assertEquals(replacementUuid.toString(), result.getString("world_uuid"));
                assertEquals("flat", result.getString("world_type"));
                assertFalse(result.getBoolean("overworld_reset_pending"));
                assertNull(result.getString("overworld_reset_previous_uuid"));
            }
        }
    }

    @Test
    void generatedResetMarkerCannotOverwriteACompletedWorld() throws Exception {
        try (Connection connection = database("generated-without-prepare")) {
            UUID original = UUID.randomUUID();
            insertHomeland(connection, 4, UUID.randomUUID(), "home", "homeland_generated_1", original, "default");
            assertThrows(java.sql.SQLException.class, () ->
                    HomelandWorldOperations.recordGeneratedOverworldResetRecord(connection, 4, UUID.randomUUID()));
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT world_uuid FROM homeland WHERE id = 4")) {
                assertTrue(result.next());
                assertEquals(original.toString(), result.getString("world_uuid"));
            }
        }
    }

    @Test
    void cleanupMarkerFailureRollsBackHomelandDeletion() throws Exception {
        try (Connection connection = database("delete-rollback")) {
            UUID owner = UUID.randomUUID();
            insertHomeland(connection, 3, owner, "home", "homeland_delete_rollback",
                    UUID.randomUUID(), "default");
            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO homeland_world_cleanup (world_key) " +
                        "VALUES ('homeland_delete_rollback')");
            }
            Homeland homeland = new Homeland(3, owner, "home",
                    "homeland_delete_rollback", 128, false, false, false, null);

            assertThrows(java.sql.SQLException.class, () ->
                    HomelandWorldOperations.persistCleanupAndDeleteHomeland(
                            connection, homeland));

            assertEquals(1, count(connection, "homeland"));
            assertEquals(1, count(connection, "homeland_world_cleanup"));
        }
    }

    private Connection database(String name) throws Exception {
        Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE homeland (" +
                    "id INT PRIMARY KEY, owner_uuid VARCHAR(36), name VARCHAR(32), " +
                    "world_key VARCHAR(64), world_uuid VARCHAR(36), world_type VARCHAR(16), " +
                    "overworld_reset_pending BOOLEAN DEFAULT FALSE, " +
                    "overworld_reset_previous_uuid VARCHAR(36))");
            statement.execute("CREATE TABLE homeland_world_cleanup (" +
                    "world_key VARCHAR(64) PRIMARY KEY)");
        }
        return connection;
    }

    private void insertHomeland(Connection connection, int id, UUID owner, String name,
                                String worldKey, UUID worldUuid, String worldType)
            throws Exception {
        try (var statement = connection.prepareStatement(
                "INSERT INTO homeland " +
                        "(id, owner_uuid, name, world_key, world_uuid, world_type) " +
                        "VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setInt(1, id);
            statement.setString(2, owner.toString());
            statement.setString(3, name);
            statement.setString(4, worldKey);
            statement.setString(5, worldUuid.toString());
            statement.setString(6, worldType);
            statement.executeUpdate();
        }
    }

    private int count(Connection connection, String table) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getInt(1);
        }
    }
}
