package com.yoooche.points;

import com.yoooche.points.event.PointsAddedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Injects a failure before commit, exercising the real keyed service transaction. */
public class TestTransactionFailure {

    private final ThreadLocal<Runnable> verification = new ThreadLocal<>();

    public void runWithRollback(Runnable operation, Runnable beforeRollback) {
        verification.set(beforeRollback);
        try {
            operation.run();
        } finally {
            verification.remove();
        }
    }

    @EventListener
    public void onPointsAdded(PointsAddedEvent event) {
        var check = verification.get();
        if (check == null) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                check.run();
                throw new IllegalStateException("force rollback");
            }
        });
    }
}
