package com.example.requestlog;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Deletes request-log rows older than {@code request-log.retention} ({@code REQUEST_LOG_RETENTION},
 * ISO-8601, default {@code P90D}). The stored questions, answers, user subjects and tool calls are
 * personal data, so they are kept no longer than needed.
 *
 * <p>Runs on {@code request-log.purge.cron} (daily by default). ShedLock makes sure that only one
 * replica purges at a time. Rows are deleted in batches of {@code request-log.purge.batch-size},
 * each in its own transaction, and at most {@code request-log.purge.max-batches} per run, so a
 * large backlog is worked off over several runs instead of one long transaction. The deleted rows
 * are counted in {@code requestlog_purged_rows_total} (K9). The question catalog is not purged: it
 * holds no per-request data.
 */
class RetentionPurgeJob {

  static final String LOCK_NAME = "requestLogRetentionPurge";

  /** Exported by Prometheus as {@code requestlog_purged_rows_total}. */
  static final String METRIC = "requestlog.purged.rows";

  /** Shorter retentions are almost certainly a typo (e.g. PT90M for P90D). */
  static final Duration MIN_RETENTION = Duration.ofDays(1);

  static final int MAX_BATCH_SIZE = 10_000;

  private static final Logger log = LoggerFactory.getLogger(RetentionPurgeJob.class);

  private final RequestLogRepository requests;
  private final Duration retention;
  private final int batchSize;
  private final int maxBatches;
  private final Counter purgedRows;

  RetentionPurgeJob(
      RequestLogRepository requests,
      MeterRegistry meters,
      Duration retention,
      int batchSize,
      int maxBatches) {
    if (retention.compareTo(MIN_RETENTION) < 0) {
      throw new IllegalStateException(
          "REQUEST_LOG_RETENTION must be at least P1D (ISO-8601, e.g. P90D), got " + retention);
    }
    if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
      throw new IllegalStateException(
          "request-log.purge.batch-size must be between 1 and " + MAX_BATCH_SIZE);
    }
    if (maxBatches < 1) {
      throw new IllegalStateException("request-log.purge.max-batches must be at least 1");
    }
    this.requests = requests;
    this.retention = retention;
    this.batchSize = batchSize;
    this.maxBatches = maxBatches;
    this.purgedRows =
        Counter.builder(METRIC)
            .description("Request-log rows deleted by the retention purge")
            .baseUnit("rows")
            .register(meters);
  }

  /** The scheduled entry point; skipped when another replica holds the lock. */
  @Scheduled(cron = "${request-log.purge.cron}", zone = "UTC")
  @SchedulerLock(
      name = LOCK_NAME,
      lockAtMostFor = "${request-log.purge.lock-at-most-for}",
      lockAtLeastFor = "${request-log.purge.lock-at-least-for}")
  public void purge() {
    purgeOlderThan(Instant.now().minus(retention));
  }

  /** Deletes rows created before {@code cutoff} and returns how many were deleted. */
  long purgeOlderThan(Instant cutoff) {
    long total = 0;
    int batches = 0;
    int deleted;
    do {
      deleted = requests.deleteCreatedBefore(cutoff, batchSize);
      purgedRows.increment(deleted);
      total += deleted;
      batches++;
    } while (deleted == batchSize && batches < maxBatches);
    if (deleted == batchSize) {
      log.warn(
          "Retention purge stopped after {} batches ({} rows); the rest follows in the next run",
          batches,
          total);
    } else {
      log.info("Retention purge deleted {} request-log rows created before {}", total, cutoff);
    }
    return total;
  }
}
