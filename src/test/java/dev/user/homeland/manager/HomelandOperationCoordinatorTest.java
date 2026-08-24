package dev.user.homeland.manager;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HomelandOperationCoordinatorTest {

    private final HomelandOperationCoordinator coordinator = new HomelandOperationCoordinator();

    @Test
    void rejectsConcurrentOperationAndReleasesAfterSuccess() {
        CompletableFuture<Void> pending = new CompletableFuture<>();
        var first = coordinator.execute(7,
                HomelandOperationCoordinator.OperationType.RESET_OVERWORLD,
                () -> pending);

        assertTrue(coordinator.isBusy(7));
        CompletionException error = assertThrows(CompletionException.class, () ->
                coordinator.execute(7,
                                HomelandOperationCoordinator.OperationType.DELETE_HOMELAND,
                                () -> CompletableFuture.completedFuture(null))
                        .toCompletableFuture().join());
        var busy = assertInstanceOf(HomelandOperationCoordinator.BusyException.class,
                error.getCause());
        assertEquals(HomelandOperationCoordinator.OperationType.RESET_OVERWORLD,
                busy.getActiveOperation());

        pending.complete(null);
        first.toCompletableFuture().join();
        assertFalse(coordinator.isBusy(7));
    }

    @Test
    void releasesAfterExceptionalCompletion() {
        var operation = coordinator.execute(9,
                HomelandOperationCoordinator.OperationType.DELETE_DIMENSION,
                () -> CompletableFuture.failedFuture(new IllegalStateException("failed")));

        assertThrows(CompletionException.class, () -> operation.toCompletableFuture().join());
        assertFalse(coordinator.isBusy(9));
    }

    @Test
    void releasesWhenOperationThrowsSynchronously() {
        var operation = coordinator.execute(11,
                HomelandOperationCoordinator.OperationType.UNLOCK_DIMENSION,
                () -> {
                    throw new IllegalArgumentException("failed before stage creation");
                });

        assertThrows(CompletionException.class, () -> operation.toCompletableFuture().join());
        assertFalse(coordinator.isBusy(11));
    }

    @Test
    void releasesWhenOperationReturnsNull() {
        var operation = coordinator.execute(13,
                HomelandOperationCoordinator.OperationType.RESET_DIMENSION,
                () -> null);

        assertThrows(CompletionException.class, () -> operation.toCompletableFuture().join());
        assertFalse(coordinator.isBusy(13));
    }

    @Test
    void distinguishesDestructiveOperationsFromShortWorldUpdates() {
        CompletableFuture<Void> pending = new CompletableFuture<>();
        coordinator.execute(15, HomelandOperationCoordinator.OperationType.MODIFY_WORLD,
                () -> pending);
        assertFalse(coordinator.isDestructiveOperationActive(15));
        pending.complete(null);

        CompletableFuture<Void> load = new CompletableFuture<>();
        coordinator.execute(15, HomelandOperationCoordinator.OperationType.LOAD_WORLD,
                () -> load);
        CompletableFuture<Void> secondLoad = new CompletableFuture<>();
        coordinator.execute(15, HomelandOperationCoordinator.OperationType.LOAD_WORLD,
                () -> secondLoad);
        assertFalse(coordinator.isDestructiveOperationActive(15));
        CompletionException loadBusy = assertThrows(CompletionException.class, () ->
                coordinator.execute(15,
                                HomelandOperationCoordinator.OperationType.DELETE_HOMELAND,
                                () -> CompletableFuture.completedFuture(null))
                        .toCompletableFuture().join());
        assertEquals(HomelandOperationCoordinator.OperationType.LOAD_WORLD,
                assertInstanceOf(HomelandOperationCoordinator.BusyException.class,
                        loadBusy.getCause()).getActiveOperation());
        load.complete(null);
        secondLoad.complete(null);

        CompletableFuture<Void> reset = new CompletableFuture<>();
        coordinator.execute(15, HomelandOperationCoordinator.OperationType.RESET_OVERWORLD,
                () -> reset);
        assertTrue(coordinator.isDestructiveOperationActive(15));
        reset.complete(null);
        assertFalse(coordinator.isDestructiveOperationActive(15));
    }

    @Test
    void differentHomelandsCanRunOperationsInParallel() {
        CompletableFuture<Void> first = new CompletableFuture<>();
        CompletableFuture<Void> second = new CompletableFuture<>();

        coordinator.execute(21, HomelandOperationCoordinator.OperationType.LOAD_WORLD,
                () -> first);
        coordinator.execute(22, HomelandOperationCoordinator.OperationType.LOAD_WORLD,
                () -> second);

        assertTrue(coordinator.isBusy(21));
        assertTrue(coordinator.isBusy(22));
        first.complete(null);
        second.complete(null);
        assertFalse(coordinator.isBusy(21));
        assertFalse(coordinator.isBusy(22));
    }
}
