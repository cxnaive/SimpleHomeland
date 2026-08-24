package dev.user.homeland.manager;

import java.util.Map;
import java.util.HashSet;
import java.util.Set;
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

    private static final class Activity {
        private ActiveOperation exclusive;
        private final Set<ActiveOperation> loads = new HashSet<>();

        private OperationType activeType() {
            return exclusive != null ? exclusive.type()
                    : OperationType.LOAD_WORLD;
        }

        private boolean isEmpty() {
            return exclusive == null && loads.isEmpty();
        }
    }

    private final Map<Integer, Activity> active = new ConcurrentHashMap<>();

    public synchronized <T> CompletionStage<T> execute(int homelandId, OperationType type,
                                                        Supplier<? extends CompletionStage<T>> operation) {
        ActiveOperation token = new ActiveOperation(type, new Object());
        Activity activity = active.computeIfAbsent(homelandId, ignored -> new Activity());
        if (type == OperationType.LOAD_WORLD) {
            if (activity.exclusive != null) {
                return CompletableFuture.failedFuture(new BusyException(
                        activity.exclusive.type()));
            }
            activity.loads.add(token);
        } else {
            if (activity.exclusive != null || !activity.loads.isEmpty()) {
                return CompletableFuture.failedFuture(new BusyException(
                        activity.activeType()));
            }
            activity.exclusive = token;
        }

        CompletionStage<T> stage;
        try {
            stage = operation.get();
            if (stage == null) {
                stage = CompletableFuture.failedFuture(
                        new IllegalStateException("Homeland operation returned null stage: " + type));
            }
        } catch (Throwable error) {
            release(homelandId, token);
            return CompletableFuture.failedFuture(error);
        }

        return stage.whenComplete((result, error) -> release(homelandId, token));
    }

    private synchronized void release(int homelandId, ActiveOperation token) {
        Activity activity = active.get(homelandId);
        if (activity == null) return;
        if (activity.exclusive == token) {
            activity.exclusive = null;
        } else {
            activity.loads.remove(token);
        }
        if (activity.isEmpty()) active.remove(homelandId, activity);
    }

    public synchronized boolean isBusy(int homelandId) {
        Activity activity = active.get(homelandId);
        return activity != null && !activity.isEmpty();
    }

    public synchronized boolean isDestructiveOperationActive(int homelandId) {
        Activity activity = active.get(homelandId);
        return activity != null && activity.exclusive != null
                && activity.exclusive.type().isDestructive();
    }

    public synchronized void clear() {
        active.clear();
    }
}
