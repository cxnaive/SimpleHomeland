package dev.user.homeland.database;

import dev.user.homeland.SimpleHomelandPlugin;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class DatabaseQueue {

    private final SimpleHomelandPlugin plugin;
    private final BlockingQueue<DatabaseTask<?>> taskQueue;
    private final ExecutorService executor;
    private volatile boolean running = true;

    public DatabaseQueue(SimpleHomelandPlugin plugin) {
        this.plugin = plugin;
        this.taskQueue = new LinkedBlockingQueue<>(1000);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "SimpleHomeland-DB-Queue");
            t.setDaemon(true);
            return t;
        });

        startProcessing();
    }

    private void startProcessing() {
        executor.submit(() -> {
            while (running || !taskQueue.isEmpty()) {
                try {
                    DatabaseTask<?> task = taskQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (task != null) {
                        try {
                            processTask(task);
                        } catch (Exception e) {
                            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                                    "Unexpected error processing task: " + task.getName(), e);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    private <T> void processTask(DatabaseTask<T> task) {
        long startTime = System.currentTimeMillis();

        try (Connection connection = plugin.getDatabaseManager().getConnection()) {
            T result = task.getOperation().execute(connection);
            long duration = System.currentTimeMillis() - startTime;

            if (duration > 1000) {
                plugin.getLogger().warning("Slow query [" + task.getName() + "] took: " + duration + "ms");
            }

            if (task.getCallback() != null) {
                try {
                    task.getCallback().accept(result);
                } catch (Exception e) {
                    plugin.getLogger().warning("Database callback failed: " + e.getMessage());
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Database operation failed [" + task.getName() + "]: " + e.getMessage());

            if (task.getErrorCallback() != null) {
                task.getErrorCallback().accept(e);
            }
        }
    }

    public <T> void submit(String name, DatabaseOperation<T> operation, Consumer<T> callback, Consumer<SQLException> errorCallback) {
        if (!running) {
            plugin.getLogger().warning("Database queue is closed, cannot submit task: " + name);
            return;
        }

        DatabaseTask<T> task = new DatabaseTask<>(name, operation, callback, errorCallback);
        try {
            boolean offered = taskQueue.offer(task, 5, TimeUnit.SECONDS);
            if (!offered) {
                plugin.getLogger().severe("Database queue is full, cannot submit task: " + name);
                if (errorCallback != null) {
                    errorCallback.accept(new SQLException("Database queue is full, task rejected: " + name));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            plugin.getLogger().warning("Submit database task interrupted: " + name);
            if (errorCallback != null) {
                errorCallback.accept(new SQLException("Task submission interrupted: " + name, e));
            }
        }
    }

    public void submit(String name, DatabaseOperation<Void> operation) {
        submit(name, operation, null, null);
    }

    public void shutdown() {
        running = false;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    public interface DatabaseOperation<T> {
        T execute(Connection connection) throws SQLException;
    }

    private static class DatabaseTask<T> {
        private final String name;
        private final DatabaseOperation<T> operation;
        private final Consumer<T> callback;
        private final Consumer<SQLException> errorCallback;

        public DatabaseTask(String name, DatabaseOperation<T> operation, Consumer<T> callback, Consumer<SQLException> errorCallback) {
            this.name = name;
            this.operation = operation;
            this.callback = callback;
            this.errorCallback = errorCallback;
        }

        public String getName() { return name; }
        public DatabaseOperation<T> getOperation() { return operation; }
        public Consumer<T> getCallback() { return callback; }
        public Consumer<SQLException> getErrorCallback() { return errorCallback; }
    }
}