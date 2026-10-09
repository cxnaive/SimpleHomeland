package dev.user.homeland.manager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HomelandWorldMigrationTest {
    @TempDir Path root;
    private static final String KEY = "homeland_1234abcd_123456";

    private HomelandWorldMigration migration() {
        return new HomelandWorldMigration(root, root.resolve("world/dimensions"), root.resolve("journal"));
    }

    private UUID legacy() throws Exception {
        UUID uuid = UUID.randomUUID();
        Path source = migration().source(KEY);
        Files.createDirectories(source.resolve("DIM-1/region"));
        Files.createDirectories(source.resolve("entities"));
        Files.writeString(source.resolve("level.dat"), "legacy-level-data");
        Files.writeString(source.resolve("DIM-1/region/r.0.0.mca"), "blocks-and-container");
        Files.writeString(source.resolve("entities/r.0.0.mca"), "entities");
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(source.resolve("uid.dat")))) {
            output.writeLong(uuid.getMostSignificantBits());
            output.writeLong(uuid.getLeastSignificantBits());
        }
        return uuid;
    }

    @Test
    void stagesCompleteCopyWhileKeepingOriginalUntouched() throws Exception {
        UUID uuid = legacy();
        var preparation = migration().prepare(KEY);
        assertTrue(preparation.legacy());
        assertEquals(java.util.Set.of(KEY), migration().pendingKeys());
        assertEquals(uuid, preparation.uuid());
        assertEquals("blocks-and-container", Files.readString(preparation.staging().resolve("DIM-1/region/r.0.0.mca")));
        assertEquals("entities", Files.readString(preparation.staging().resolve("entities/r.0.0.mca")));
        assertTrue(Files.exists(migration().source(KEY).resolve("uid.dat")));
    }

    @Test
    void retriesInterruptedCopyAndMigrationWithoutOverwritingBackup() throws Exception {
        UUID uuid = legacy();
        var preparation = migration().prepare(KEY);
        Files.writeString(preparation.staging().resolve("level.dat"), "incomplete");
        Files.createDirectories(migration().target(KEY));
        Files.writeString(migration().target(KEY).resolve("partial"), "partial");
        var retried = migration().prepare(KEY);
        assertEquals(uuid, retried.uuid());
        assertFalse(Files.exists(migration().target(KEY)));
        assertEquals("legacy-level-data", Files.readString(retried.staging().resolve("level.dat")));
        try (var files = Files.list(root.resolve("journal/interrupted"))) {
            assertEquals(2, files.count());
        }
    }

    @Test
    void completedMigrationIsIdempotentAndNeverReimportsDeletedWorld() throws Exception {
        legacy();
        migration().prepare(KEY);
        Files.createDirectories(migration().target(KEY));
        migration().complete(KEY);
        assertFalse(migration().prepare(KEY).legacy());
        assertTrue(migration().pendingKeys().isEmpty());
        assertNull(migration().legacyUuid(KEY));
        HomelandWorldService.deleteTree(migration().target(KEY));
        assertThrows(java.io.IOException.class, () -> migration().prepare(KEY));
    }

    @Test
    void rejectsMissingWorldAndOccupiedStagingPath() throws Exception {
        assertThrows(java.io.IOException.class, () -> migration().prepare(KEY));
        legacy();
        Path staging = root.resolve("journal/staging").resolve(KEY);
        Files.createDirectories(staging);
        Files.writeString(staging.resolve("foreign-data"), "keep");
        assertThrows(java.io.IOException.class, () -> migration().prepare(KEY));
        assertEquals("keep", Files.readString(staging.resolve("foreign-data")));
        assertFalse(Files.exists(root.resolve("journal/" + KEY + ".pending")));
    }

    @Test
    void refusesPathTraversal() {
        assertThrows(IllegalArgumentException.class, () -> migration().target("../../world"));
    }

    @Test
    void scanDoesNotLoadOrCopyAndFindsInterruptedWork() throws Exception {
        assertTrue(migration().needsMigration(KEY));
        assertThrows(java.io.IOException.class, () -> migration().prepare(KEY));
        assertFalse(Files.exists(migration().target(KEY)), "Missing data must never generate an empty world");
        legacy();
        assertTrue(migration().needsMigration(KEY));
        assertFalse(Files.exists(root.resolve("journal")));
        migration().prepare(KEY);
        Files.createDirectories(migration().target(KEY));
        assertTrue(migration().needsMigration(KEY), "Pending migration owns the partial target");
        migration().complete(KEY);
        assertFalse(migration().needsMigration(KEY));
    }

    @Test
    void interruptedCopyKeepsBackupAndCanBeRetried() throws Exception {
        UUID uuid = legacy();
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(java.io.InterruptedIOException.class,
                () -> migration().prepare(KEY, () -> checks.incrementAndGet() > 3));
        assertEquals(uuid, migration().legacyUuid(KEY));
        assertEquals("legacy-level-data", Files.readString(migration().source(KEY).resolve("level.dat")));
        assertTrue(Files.exists(root.resolve("journal/" + KEY + ".pending")));
        assertEquals(uuid, migration().prepare(KEY).uuid());
    }
}
