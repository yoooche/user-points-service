package com.yoooche.points.outbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTriggerTest {

    @Mock
    private OutboxRelay relay;

    private final List<Runnable> queued = new ArrayList<>();

    @Test
    void wakeUpsBeforeThePassStartsAreMerged() {
        var trigger = new OutboxRelayTrigger(relay, queued::add);

        trigger.wakeUp();
        trigger.wakeUp();
        trigger.wakeUp();

        assertThat(queued).hasSize(1);
        queued.getFirst().run();
        verify(relay).publishPending();
    }

    @Test
    void wakeUpsDuringAPassQueueExactlyOneMore() {
        var trigger = new OutboxRelayTrigger(relay, queued::add);
        doAnswer(invocation -> {
            trigger.wakeUp();
            trigger.wakeUp();
            return null;
        }).when(relay).publishPending();

        trigger.wakeUp();
        queued.getFirst().run();

        assertThat(queued).hasSize(2);
    }

    @Test
    void rejectedWakeUpDoesNotThrowOrBlockTheNextOne() {
        TaskExecutor rejectFirst = new TaskExecutor() {
            private boolean rejected;

            @Override
            public void execute(Runnable task) {
                if (!rejected) {
                    rejected = true;
                    throw new TaskRejectedException("shutting down");
                }
                queued.add(task);
            }
        };
        var trigger = new OutboxRelayTrigger(relay, rejectFirst);

        trigger.wakeUp();
        trigger.wakeUp();

        assertThat(queued).hasSize(1);
    }
}
