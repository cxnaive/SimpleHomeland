package dev.user.homeland.manager;

import dev.user.homeland.model.Homeland;
import dev.user.homeland.model.HomelandDimension;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HomelandStartupMigrationTest {
    private Homeland home(int id, boolean nether, boolean end) {
        return new Homeland(id, UUID.randomUUID(), "Home" + id, "homeland_test_" + id,
                500, nether, end, false, null);
    }

    private List<HomelandStartupMigration.Job> jobs(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count).mapToObj(id -> {
            Homeland home = home(id, false, false);
            return new HomelandStartupMigration.Job(home, home.getWorldKey(), HomelandDimension.OVERWORLD);
        }).toList();
    }

    @Test
    void plansAllUnlockedDimensionsAndSkipsCompletedAndPendingResets() {
        Homeland all = home(1, true, true);
        Homeland overworldOnly = home(2, false, false);
        Homeland resetting = home(3, true, true);
        resetting.setOverworldResetPending(true);
        List<String> keys = HomelandStartupMigration.plan(List.of(resetting, overworldOnly, all),
                job -> job.dimension() != HomelandDimension.END, () -> false)
                .stream().map(HomelandStartupMigration.Job::key).toList();
        assertEquals(List.of(all.getWorldKey(), all.getWorldKey() + "_nether",
                overworldOnly.getWorldKey()), keys);
        assertTrue(HomelandStartupMigration.plan(List.of(all), job -> true, () -> true).isEmpty());
    }

    @Test
    void waitsForEachWorldToFinishAndContinuesAfterIndividualFailure() throws Exception {
        var jobs = jobs(3);
        CompletableFuture<Void> firstFinished = new CompletableFuture<>();
        CompletableFuture<Void> firstStarted = new CompletableFuture<>();
        List<String> started = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> result = HomelandStartupMigration.runSerial(jobs, job -> {
            started.add(job.key());
            if (job.equals(jobs.getFirst())) {
                firstStarted.complete(null);
                return firstFinished;
            }
            if (job.equals(jobs.get(1))) return CompletableFuture.failedFuture(new IOException("copy failed"));
            return CompletableFuture.completedFuture(null);
        }, () -> false, (job, error) -> { if (error != null) errors.add(error); });
        firstStarted.get(5, TimeUnit.SECONDS);
        assertEquals(List.of(jobs.getFirst().key()), started);
        assertFalse(result.isDone());
        firstFinished.complete(null);
        result.get(5, TimeUnit.SECONDS);
        assertEquals(jobs.stream().map(HomelandStartupMigration.Job::key).toList(), started);
        assertEquals(1, errors.size());
        assertEquals("copy failed", errors.getFirst().getMessage());
    }

    @Test
    void stopsBeforeNextWorldWhenCancelledOrUnloadFails() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger started = new AtomicInteger();
        HomelandStartupMigration.runSerial(jobs(3), job -> {
            started.incrementAndGet();
            cancelled.set(true);
            return CompletableFuture.completedFuture(null);
        }, cancelled::get, (job, error) -> {}).get(5, TimeUnit.SECONDS);
        assertEquals(1, started.get());

        started.set(0);
        CompletableFuture<Void> failure = HomelandStartupMigration.runSerial(jobs(3), job -> {
            started.incrementAndGet();
            return CompletableFuture.failedFuture(new HomelandWorldService.UnloadFailure("unload failed"));
        }, () -> false, (job, error) -> {});
        assertThrows(java.util.concurrent.ExecutionException.class, () -> failure.get(5, TimeUnit.SECONDS));
        assertEquals(1, started.get());
    }

    @Test
    void handlesLargeQueueWithoutRecursiveCompletionOrParallelLoads() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        HomelandStartupMigration.runSerial(jobs(5000), job -> {
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            return CompletableFuture.runAsync(active::decrementAndGet);
        }, () -> false, (job, error) -> {
            assertNull(error);
            completed.incrementAndGet();
        }).get(20, TimeUnit.SECONDS);
        assertEquals(5000, completed.get());
        assertEquals(1, peak.get());
    }
}
