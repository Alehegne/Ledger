package com.example.Ledger.outbox.service;

import com.example.Ledger.outbox.model.OutboxEvent;
import com.example.Ledger.outbox.model.OutboxStatus;
import com.example.Ledger.outbox.repository.OutboxEventRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final int batchSize;
    private final boolean dispatcherEnabled;

    public OutboxDispatcher(
            OutboxEventRepository outboxEventRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${ledger.outbox.batch-size:50}") int batchSize,
            @Value("${ledger.outbox.dispatcher.enabled:true}") boolean dispatcherEnabled
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.batchSize = Math.max(1, batchSize);
        this.dispatcherEnabled = dispatcherEnabled;
    }

    @Scheduled(fixedDelayString = "${ledger.outbox.poll-delay-ms:1000}")
    public void scheduledPoll() {
        if (!dispatcherEnabled) {
            return;
        }
        dispatchPendingBatch();
    }

    @Transactional
    public int dispatchPendingBatch() {
        Instant now = Instant.now();
        List<OutboxEvent> dueEvents = outboxEventRepository.findDuePendingEventsForUpdate(now, batchSize);
        if (dueEvents.isEmpty()) {
            return 0;
        }

        int processedCount = 0;
        for (OutboxEvent event : dueEvents) {
            try {
                ProducerRecord<String, String> record = new ProducerRecord<>(
                        event.getTopic(),
                        event.getPartitionKey(),
                        event.getPayloadJson()
                );
                record.headers().add(new RecordHeader("outbox-event-id", event.getId().toString().getBytes(StandardCharsets.UTF_8)));
                record.headers().add(new RecordHeader("event-type", event.getEventType().getBytes(StandardCharsets.UTF_8)));
                record.headers().add(new RecordHeader("aggregate-type", event.getAggregateType().getBytes(StandardCharsets.UTF_8)));
                record.headers().add(new RecordHeader("aggregate-id", event.getAggregateId().getBytes(StandardCharsets.UTF_8)));
                record.headers().add(new RecordHeader("organization-id", event.getOrganization().getId().toString().getBytes(StandardCharsets.UTF_8)));

                kafkaTemplate.send(record).get(5, TimeUnit.SECONDS);

                event.setStatus(OutboxStatus.PROCESSED);
                event.setProcessedAt(Instant.now());
                event.setLastError(null);
                outboxEventRepository.save(event);
                processedCount++;
            } catch (Exception ex) {
                int nextRetryCount = event.getRetryCount() + 1;
                event.setRetryCount(nextRetryCount);
                event.setLastError(truncateError(ex));

                if (nextRetryCount >= event.getMaxRetries()) {
                    event.setStatus(OutboxStatus.FAILED);
                    log.error(
                            "Outbox event id={} (type={}, org={}) exhausted maxRetries={}; marked FAILED",
                            event.getId(),
                            event.getEventType(),
                            event.getOrganization().getId(),
                            event.getMaxRetries(),
                            ex
                    );
                } else {
                    long backoffSeconds = Math.min(300L, (1L << Math.min(nextRetryCount, 8)) * 2L);
                    event.setNextAttemptAt(Instant.now().plusSeconds(backoffSeconds));
                    log.warn(
                            "Failed to publish outbox event id={} (attempt {}/{}); next retry in {}s",
                            event.getId(),
                            nextRetryCount,
                            event.getMaxRetries(),
                            backoffSeconds,
                            ex
                    );
                }
                outboxEventRepository.save(event);
            }
        }

        return processedCount;
    }

    private static String truncateError(Exception ex) {
        String message = ex.getMessage() != null ? ex.getClass().getSimpleName() + ": " + ex.getMessage() : ex.getClass().getName();
        return message.length() <= 2000 ? message : message.substring(0, 2000);
    }
}
