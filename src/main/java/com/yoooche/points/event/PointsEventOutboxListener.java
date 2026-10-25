package com.yoooche.points.event;

import com.yoooche.points.filter.RequestIdFilter;
import com.yoooche.points.mq.Topic;
import com.yoooche.points.outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class PointsEventOutboxListener {

    private final OutboxWriter outboxWriter;

    @EventListener
    public void onPointsAdded(PointsAddedEvent event) {
        var requestId = MDC.get(RequestIdFilter.MDC_KEY);
        var message = PointsAddedMessage.of(event, UUID.randomUUID().toString(), requestId, Instant.now().truncatedTo(ChronoUnit.MILLIS));
        outboxWriter.append(Topic.USER_POINTS, "ADDED", message.userId(), message.eventId(), requestId, message);
    }
}
