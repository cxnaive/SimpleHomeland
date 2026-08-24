package dev.user.homeland.manager;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class HomelandOperationCoordinator {

    public enum OperationType {
        DELETE_HOMELAND(true),
        UNLOCK_DIMENSION(true),
        DELETE_DIMENSION(true),
        RESET_OVERWORLD(true),
        RESET_DIMENSION(true),
        MODIFY_WORLD(false),
        LOAD_WORLD(false);

        private final boolean destructive;

        OperationType(boolean destructive) {
            this.destructive = destructive;
        }

        boolean isDestructive() {
            return destructive;
        }
    }

    public static final class BusyException extends RuntimeException {
        private final OperationType activeOperation;

        private BusyException(OperationType activeOperation) {
            super("Homeland operation already in progress: " + activeOperation);
            this.activeOperation = activeOperation;
        }

        public OperationType getActiveOperation() {
            return activeOperation;
        }
    }

    private record ActiveOperation(OperationType type, Object token) {}

    private final Map<Integer, ActiveOperation> active = new ConcurrentHashMap<>();

    public <T> CompletionStage<T> execute(int homelandId, OperationType type,
                                          Supplier<? extends CompletionStage<T>> operation) {
        ActiveOperation token = new ActiveOperation(type, new Object());
        ActiveOperation current = active.putIfAbsent(homelandId, token);
        if (current != null) {
            return CompletableFuture.failedFuture(new BusyException(current.type()));
        }

        CompletionStage<T> stage;
        try {
            stage = operation.get();
            if (stage == null) {
                stage = CompletableFuture.failedFuture(
                        new IllegalStateException("Homeland operation returned null stage: " + type));
            }
        } catch (Throwable error) {
            active.remove(homelandId, token);
            return CompletableFuture.failedFuture(error);
        }

        return stage.whenComplete((result, error) -> active.remove(homelandId, token));
    }

    public boolean isBusy(int homelandId) {
        return active.containsKey(homelandId);
    }

    public boolean isDestructiveOperationActive(int homelandId) {
        ActiveOperation operation = active.get(homelandId);
        return operation != null && operation.type().isDestructive();
    }

    public void clear() {
        active.clear();
    }
}
