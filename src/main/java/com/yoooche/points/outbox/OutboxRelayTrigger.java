package com.yoooche.points.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "outbox.relay", name = "enabled", matchIfMissing = true)
public class OutboxRelayTrigger {

    private final OutboxRelay relay;
    private final TaskExecutor executor;
    private final AtomicBoolean passQueued = new AtomicBoolean();

    public OutboxRelayTrigger(OutboxRelay relay, @Qualifier("outboxRelayExecutor") TaskExecutor executor) {
        this.relay = relay;
        this.executor = executor;
    }

    @Scheduled(cron = "${outbox.relay.sweep-cron:*/30 * * * * *}")
    public void sweep() {
        wakeUp();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        wakeUp();
    }

    public void wakeUp() {
        if (!passQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(this::runPass);
        } catch (TaskRejectedException ex) {
            passQueued.set(false);
            log.warn("Outbox relay is not accepting work, rows wait for the next start: {}", ex.getMessage());
        }
    }

    private void runPass() {
        passQueued.set(false);
        try {
            relay.publishPending();
        } catch (RuntimeException ex) {
            log.error("Outbox relay pass failed, will retry on the next wake-up", ex);
        }
    }
}
