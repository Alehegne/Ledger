package com.example.Ledger.common.observability;

import com.example.Ledger.reconciliation.model.ReconciliationRun;
import com.example.Ledger.reconciliation.model.ReconciliationStatus;
import com.example.Ledger.reconciliation.repository.ReconciliationRunRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class LedgerMetricsService {

    private static final Logger log = LoggerFactory.getLogger(LedgerMetricsService.class);

    public static final String METRIC_JOURNAL_POSTED_COUNT = "ledger.journal.posted.count";
    public static final String METRIC_JOURNAL_REVERSED_COUNT = "ledger.journal.reversed.count";
    public static final String METRIC_RECONCILIATION_STATUS = "ledger.reconciliation.status";
    public static final String METRIC_TRANSACTION_DURATION = "ledger.transaction.duration";

    private static final int STATUS_UNKNOWN = -1;
    private static final int STATUS_FAILED = 0;
    private static final int STATUS_PASSED = 1;

    private final MeterRegistry meterRegistry;
    private final ReconciliationRunRepository reconciliationRunRepository;
    private final Counter journalPostedCounter;
    private final Counter journalReversedCounter;
    private final Timer transactionDurationTimer;
    private final AtomicInteger reconciliationStatusCode = new AtomicInteger(STATUS_UNKNOWN);

    public LedgerMetricsService(
            MeterRegistry meterRegistry,
            ReconciliationRunRepository reconciliationRunRepository
    ) {
        this.meterRegistry = meterRegistry;
        this.reconciliationRunRepository = reconciliationRunRepository;

        this.journalPostedCounter = Counter.builder(METRIC_JOURNAL_POSTED_COUNT)
                .description("Total number of committed posted journal entries")
                .register(meterRegistry);

        this.journalReversedCounter = Counter.builder(METRIC_JOURNAL_REVERSED_COUNT)
                .description("Total number of committed journal entry reversals")
                .register(meterRegistry);

        this.transactionDurationTimer = Timer.builder(METRIC_TRANSACTION_DURATION)
                .description("Duration of core ledger financial transactions")
                .publishPercentileHistogram()
                .register(meterRegistry);

        Gauge.builder(METRIC_RECONCILIATION_STATUS, reconciliationStatusCode, AtomicInteger::get)
                .description("Latest committed reconciliation status (1=PASSED, 0=FAILED, -1=UNKNOWN)")
                .register(meterRegistry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeReconciliationStatus() {
        try {
            Optional<ReconciliationRun> latest = reconciliationRunRepository.findTopByOrderByCreatedAtDescIdDesc();
            latest.ifPresent(run -> updateReconciliationStatus(run.getStatus()));
        } catch (Exception ex) {
            log.warn("Unable to initialize reconciliation status metric from database", ex);
        }
    }

    public Timer.Sample startTransactionTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopTransactionTimer(Timer.Sample sample) {
        if (sample != null) {
            sample.stop(transactionDurationTimer);
        }
    }

    public void recordJournalPostedAfterCommit() {
        runAfterCommit(journalPostedCounter::increment);
    }

    public void recordJournalReversedAfterCommit() {
        runAfterCommit(journalReversedCounter::increment);
    }

    public void recordReconciliationStatusAfterCommit(ReconciliationStatus status) {
        runAfterCommit(() -> updateReconciliationStatus(status));
    }

    private void updateReconciliationStatus(ReconciliationStatus status) {
        if (status == null) {
            reconciliationStatusCode.set(STATUS_UNKNOWN);
            return;
        }
        reconciliationStatusCode.set(status == ReconciliationStatus.PASSED ? STATUS_PASSED : STATUS_FAILED);
    }

    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
