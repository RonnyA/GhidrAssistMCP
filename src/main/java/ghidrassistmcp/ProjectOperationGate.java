package ghidrassistmcp;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Non-blocking admission for MCP work and exclusive project lifecycle operations.
 * Permits can be released by a different thread after an asynchronous task really exits.
 */
public final class ProjectOperationGate {
    private int activeOperations;
    private boolean transitionActive;

    public synchronized Runnable acquireOperation() {
        if (transitionActive) {
            throw new IllegalStateException("A Ghidra project operation is in progress. Retry after it completes.");
        }
        activeOperations++;
        AtomicBoolean released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) {
                synchronized (ProjectOperationGate.this) {
                    activeOperations--;
                }
            }
        };
    }

    public synchronized Runnable acquireTransition() {
        if (transitionActive) {
            throw new IllegalStateException("A Ghidra project operation is already in progress. Retry after it completes.");
        }
        if (activeOperations != 0) {
            throw new IllegalStateException("Cannot change or save the Ghidra project while "
                + activeOperations + " MCP operations are active. Wait for them to finish, then retry.");
        }
        transitionActive = true;
        AtomicBoolean released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) {
                synchronized (ProjectOperationGate.this) {
                    transitionActive = false;
                }
            }
        };
    }
}
