package dev.user.homeland.database;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DatabaseNameConstraintTest {

    @Test
    void recognizesExistingH2UniqueConstraintWhenTableIdentifierIsUppercase() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:unique-metadata")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE homeland (name VARCHAR_IGNORECASE(32), owner_uuid VARCHAR(36), "
                        + "CONSTRAINT homeland_name_unique UNIQUE(name))");
                assertTrue(DatabaseManager.hasSingleColumnUniqueIndex(connection, "homeland", "name"));
                assertFalse(DatabaseManager.hasSingleColumnUniqueIndex(connection, "homeland", "owner_uuid"));
            }
        }
    }

    @Test
    void h2NameConstraintIsCaseInsensitiveAndIdempotent() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:name-constraint;DB_CLOSE_DELAY=-1")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE homeland (name VARCHAR(32) NOT NULL, UNIQUE(name))");
                String migration = "ALTER TABLE homeland ALTER COLUMN name "
                        + "VARCHAR_IGNORECASE(32) NOT NULL";
                statement.execute(migration);
                assertDoesNotThrow(() -> statement.execute(migration));
                statement.execute("INSERT INTO homeland(name) VALUES ('Foo')");

                assertThrows(SQLException.class,
                        () -> statement.execute("INSERT INTO homeland(name) VALUES ('foo')"));

                statement.execute("CREATE TABLE homeland_new ("
                        + "name VARCHAR_IGNORECASE(32) NOT NULL, UNIQUE(name))");
                statement.execute("INSERT INTO homeland_new(name) VALUES ('Bar')");
                assertThrows(SQLException.class,
                        () -> statement.execute("INSERT INTO homeland_new(name) VALUES ('bar')"));
            }
        }
    }
}
