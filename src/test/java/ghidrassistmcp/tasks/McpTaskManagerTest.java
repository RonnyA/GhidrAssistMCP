package ghidrassistmcp.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.spec.McpSchema;

class McpTaskManagerTest {
    private final McpTaskManager manager = new McpTaskManager(1);
    private final CountDownLatch releaseWorker = new CountDownLatch(1);

    @AfterEach
    void shutdown() {
        releaseWorker.countDown();
        manager.shutdown();
    }

    @Test
    void queuedCancellationReleasesLeaseWithoutExecutingTool() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        manager.submitTask("blocking", Map.of(), task -> {
            running.countDown();
            awaitUninterruptibly(releaseWorker);
            return success();
        });
        assertTrue(running.await(2, TimeUnit.SECONDS));
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger releases = new AtomicInteger();
        McpTask queued = manager.submitTask("queued", Map.of(), McpProgramContext.empty(),
            task -> { executions.incrementAndGet(); return success(); }, releases::incrementAndGet);

        assertTrue(manager.cancelTask(queued.getTaskId()));
        assertEquals(McpTask.Status.CANCELLED, queued.getStatus());
        assertEquals(1, releases.get());
        releaseWorker.countDown();
        manager.shutdown();
        assertEquals(0, executions.get());
        assertEquals(1, releases.get());
    }

    @Test
    void runningCancellationRetainsLeaseUntilWorkerStops() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        AtomicInteger releases = new AtomicInteger();
        McpTask task = manager.submitTask("running", Map.of(), McpProgramContext.empty(),
            ignored -> {
                running.countDown();
                awaitUninterruptibly(releaseWorker);
                return success();
            }, () -> { releases.incrementAndGet(); released.countDown(); });
        assertTrue(running.await(2, TimeUnit.SECONDS));

        assertTrue(manager.cancelTask(task.getTaskId()));
        assertEquals(0, releases.get(), "cancellation must not release a live program under a worker");
        releaseWorker.countDown();
        assertTrue(released.await(2, TimeUnit.SECONDS));
        assertEquals(1, releases.get());
        assertEquals(McpTask.Status.CANCELLED, task.getStatus());
    }

    @Test
    void toolErrorIsFailedAndRetainsItsMcpResult() throws Exception {
        McpSchema.CallToolResult error = McpSchema.CallToolResult.builder()
            .addTextContent("Function not found").isError(true).build();
        CountDownLatch released = new CountDownLatch(1);
        McpTask task = manager.submitTask("failure", Map.of(), McpProgramContext.empty(),
            ignored -> error, released::countDown);
        assertTrue(released.await(2, TimeUnit.SECONDS));
        assertEquals(McpTask.Status.FAILED, task.getStatus());
        assertSame(error, manager.getTaskResult(task.getTaskId()));
    }

    @Test
    void fullQueueRejectsWorkAndReleasesItsLease() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        manager.submitTask("blocking", Map.of(), ignored -> {
            running.countDown();
            awaitUninterruptibly(releaseWorker);
            return success();
        });
        assertTrue(running.await(2, TimeUnit.SECONDS));
        for (int i = 0; i < 128; i++) {
            manager.submitTask("queued", Map.of(), ignored -> success());
        }
        AtomicInteger releases = new AtomicInteger();
        assertThrows(RejectedExecutionException.class, () -> manager.submitTask(
            "overloaded", Map.of(), McpProgramContext.empty(), ignored -> success(),
            releases::incrementAndGet));
        assertEquals(1, releases.get());
        assertEquals(129, manager.listTasks(null).size());
    }

    @Test
    void rejectedSubmissionReleasesLeaseExactlyOnce() {
        manager.shutdown();
        AtomicInteger releases = new AtomicInteger();
        assertThrows(RejectedExecutionException.class, () -> manager.submitTask(
            "rejected", Map.of(), McpProgramContext.empty(), ignored -> success(),
            releases::incrementAndGet));
        assertEquals(1, releases.get());
        assertTrue(manager.listTasks(null).isEmpty());
    }

    private static McpSchema.CallToolResult success() {
        return McpSchema.CallToolResult.builder().addTextContent("complete").build();
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    latch.await();
                    return;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
