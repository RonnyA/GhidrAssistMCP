package ghidrassistmcp;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class ProjectOperationGateTest {
    @Test
    void everyNormalOperationMustFinishBeforeAnExclusiveTransition() {
        ProjectOperationGate gate = new ProjectOperationGate();
        Runnable first = gate.acquireOperation();
        Runnable second = gate.acquireOperation();
        assertThrows(IllegalStateException.class, gate::acquireTransition);
        first.run();
        first.run();
        assertThrows(IllegalStateException.class, gate::acquireTransition);
        second.run();
        gate.acquireTransition().run();
    }

    @Test
    void exclusiveTransitionRejectsNewOperationsAndOtherTransitions() {
        ProjectOperationGate gate = new ProjectOperationGate();
        Runnable release = gate.acquireTransition();
        assertThrows(IllegalStateException.class, gate::acquireOperation);
        assertThrows(IllegalStateException.class, gate::acquireTransition);
        release.run();
        release.run();
        gate.acquireOperation().run();
        gate.acquireTransition().run();
    }

    @Test
    void normalPermitCanBeReleasedFromAnotherThread() throws Exception {
        ProjectOperationGate gate = new ProjectOperationGate();
        Runnable release = gate.acquireOperation();
        CountDownLatch released = new CountDownLatch(1);
        Thread worker = new Thread(() -> { release.run(); released.countDown(); });
        worker.start();
        assertTrue(released.await(2, TimeUnit.SECONDS));
        worker.join(2000);
        release.run();
        gate.acquireTransition().run();
    }

    @Test
    void exclusivePermitCanBeReleasedFromAnotherThreadWithoutUnlockOwnership() throws Exception {
        ProjectOperationGate gate = new ProjectOperationGate();
        Runnable release = gate.acquireTransition();
        CountDownLatch released = new CountDownLatch(1);
        Thread worker = new Thread(() -> { release.run(); released.countDown(); });
        worker.start();
        assertTrue(released.await(2, TimeUnit.SECONDS));
        worker.join(2000);
        gate.acquireOperation().run();
    }
}
