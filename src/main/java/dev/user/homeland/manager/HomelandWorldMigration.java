package dev.user.homeland.manager;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Prepares nested Worlds 3 folders for Paper's official legacyName migration. */
final class HomelandWorldMigration {
    private final Path container;
    private final Path dimensions;
    private final Path journal;

    HomelandWorldMigration(Path container, Path dimensions, Path journal) {
        this.container = container.toAbsolutePath().normalize();
        this.dimensions = dimensions.toAbsolutePath().normalize();
        this.journal = journal.toAbsolutePath().normalize();
    }

    Path target(String key) {
        validate(key);
        return dimensions.resolve("homelands").resolve(key);
    }

    Path source(String key) {
        validate(key);
        return container.resolve("homelands").resolve(key);
    }

    boolean needsMigration(String key) {
        validate(key);
        Path pending = journal.resolve(key + ".pending");
        // Missing/invalid data must be attempted and reported as a failure, not silently skipped.
        return Files.exists(pending) || !Files.isDirectory(target(key));
    }

    UUID legacyUuid(String key) throws IOException {
        // A completed migration/reset/deletion must never revive a UUID from its backup.
        if (Files.exists(journal.resolve(key + ".complete"))) return null;
        Path uid = source(key).resolve("uid.dat");
        if (!Files.isRegularFile(uid)) return null;
        try (DataInputStream input = new DataInputStream(Files.newInputStream(uid))) {
            return new UUID(input.readLong(), input.readLong());
        }
    }

    Preparation prepare(String key) throws IOException {
        return prepare(key, () -> false);
    }

    Preparation prepare(String key, BooleanSupplier cancelled) throws IOException {
        checkCancelled(cancelled);
        validate(key);
        Path pending = journal.resolve(key + ".pending");
        Path complete = journal.resolve(key + ".complete");
        Path staging = journal.resolve("staging").resolve(key);
        Path target = target(key);
        if (Files.exists(pending)) {
            // Only paths owned by this durable journal can be moved out of the way.
            quarantine(key, staging);
            quarantine(key, target);
        } else if (Files.exists(target)) {
            return new Preparation(null, null);
        } else if (Files.exists(complete)) {
            throw new IOException("Migrated homeland directory is missing: " + target);
        }
        Path source = source(key);
        if (!Files.isRegularFile(source.resolve("level.dat"))
                && !Files.isRegularFile(source.resolve("level.dat_old"))) {
            throw new IOException("Homeland world data is missing: " + source);
        }
        if (Files.exists(staging)) throw new IOException("Migration staging path is occupied: " + staging);
        UUID uuid = legacyUuid(key);
        Files.createDirectories(journal);
        // Written before copying so an interrupted copy is also recoverable.
        Files.writeString(pending, uuid == null ? "" : uuid.toString());
        copy(source, staging, cancelled);
        return new Preparation(staging, uuid);
    }

    void complete(String key) throws IOException {
        validate(key);
        Files.createDirectories(journal);
        Path pending = journal.resolve(key + ".pending");
        Path complete = journal.resolve(key + ".complete");
        if (Files.exists(pending)) {
            Files.move(pending, complete, StandardCopyOption.REPLACE_EXISTING);
        } else if (!Files.exists(complete)) {
            Files.writeString(complete, "");
        }
    }

    private void quarantine(String key, Path path) throws IOException {
        if (!Files.exists(path)) return;
        Path quarantine = journal.resolve("interrupted");
        Files.createDirectories(quarantine);
        Files.move(path, quarantine.resolve(key + "-" + UUID.randomUUID()));
    }

    private static void copy(Path source, Path target, BooleanSupplier cancelled) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) throws IOException {
                checkCancelled(cancelled);
                if (Files.isSymbolicLink(path)) throw new IOException("Symbolic link in legacy world: " + path);
                Files.createDirectories(target.resolve(source.relativize(path)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                checkCancelled(cancelled);
                if (attrs.isSymbolicLink()) throw new IOException("Symbolic link in legacy world: " + path);
                Files.copy(path, target.resolve(source.relativize(path)));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean()) throw new java.io.InterruptedIOException("Homeland migration stopped");
    }

    private static void validate(String key) {
        if (!key.matches("[a-z0-9_-]+")) throw new IllegalArgumentException("Invalid homeland world key: " + key);
    }

    record Preparation(Path staging, UUID uuid) {
        boolean legacy() { return staging != null; }
    }
}
