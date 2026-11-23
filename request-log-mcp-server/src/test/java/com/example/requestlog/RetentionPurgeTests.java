package com.example.requestlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * The retention purge on in-memory H2 (the {@code local} profile), with a small batch size so the
 * batching is exercised. The H2 database is shared with other test classes, so every test uses its
 * own request ids and checks only those rows.
 */
@SpringBootTest(properties = {"request-log.purge.batch-size=2", "REQUEST_LOG_RETENTION=P30D"})
@ActiveProfiles("local")
class RetentionPurgeTests {

  @Autowired RetentionPurgeJob job;
  @Autowired RequestLogRepository requests;
  @Autowired MeterRegistry meters;
  @Autowired JdbcClient jdbc;

  /**
   * A finished run keeps its lock for lock-at-least-for, so each test starts unlocked. The lock row
   * is expired rather than deleted: ShedLock remembers that it created the row and then only
   * updates it.
   */
  @BeforeEach
  void releaseLocks() {
    setLockUntil("2000-01-01 00:00:00");
  }

  private void setLockUntil(String utc) {
    jdbc.sql("UPDATE shedlock SET lock_until = CAST(:until AS TIMESTAMP)")
        .param("until", utc)
        .update();
  }

  @Test
  void deletesOnlyRowsOlderThanTheRetentionInBatchesAndCountsThem() {
    List<String> old = IntStream.range(0, 5).mapToObj(i -> insert(Duration.ofDays(31))).toList();
    String recent = insert(Duration.ofDays(29));
    double before = purgedRows();

    job.purge();

    old.forEach(id -> assertThat(requests.findByRequestId(id)).isEmpty());
    assertThat(requests.findByRequestId(recent)).isPresent();
    assertThat(purgedRows() - before).isGreaterThanOrEqualTo(5);
  }

  @Test
  void recordsTheRunInTheShedLockTable() {
    job.purge();

    assertThat(
            jdbc.sql("SELECT count(*) FROM shedlock WHERE name = :name")
                .param("name", RetentionPurgeJob.LOCK_NAME)
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void skipsTheRunWhileAnotherReplicaHoldsTheLock() {
    job.purge(); // makes sure the lock row exists
    setLockUntil("2999-01-01 00:00:00");
    String old = insert(Duration.ofDays(60));

    job.purge();

    assertThat(requests.findByRequestId(old)).isPresent();
    requests.deleteCreatedBefore(Instant.now().minus(Duration.ofDays(59)), 1000);
  }

  @Test
  void stopsAfterMaxBatchesAndLeavesTheRestForTheNextRun() {
    var registry = new SimpleMeterRegistry();
    var limited = new RetentionPurgeJob(requests, registry, Duration.ofDays(1), 2, 1);
    Instant cutoff = Instant.now().minus(Duration.ofDays(400));
    List<String> old = IntStream.range(0, 3).mapToObj(i -> insert(Duration.ofDays(401))).toList();

    assertThat(limited.purgeOlderThan(cutoff)).isEqualTo(2);
    assertThat(old.stream().filter(id -> requests.findByRequestId(id).isPresent())).hasSize(1);
    assertThat(registry.get(RetentionPurgeJob.METRIC).counter().count()).isEqualTo(2);

    assertThat(limited.purgeOlderThan(cutoff)).isEqualTo(1);
    assertThat(limited.purgeOlderThan(cutoff)).isZero();
  }

  @Test
  void rejectsUnsafeSettings() {
    var registry = new SimpleMeterRegistry();
    assertThatThrownBy(
            () -> new RetentionPurgeJob(requests, registry, Duration.ofMinutes(90), 100, 10))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("REQUEST_LOG_RETENTION must be at least P1D");
    assertThatThrownBy(() -> new RetentionPurgeJob(requests, registry, Duration.ofDays(1), 0, 10))
        .hasMessageContaining("batch-size");
    assertThatThrownBy(
            () -> new RetentionPurgeJob(requests, registry, Duration.ofDays(1), 10_001, 10))
        .hasMessageContaining("batch-size");
    assertThatThrownBy(() -> new RetentionPurgeJob(requests, registry, Duration.ofDays(1), 10, 0))
        .hasMessageContaining("max-batches");
  }

  @Test
  void theMetricIsRegisteredBeforeTheFirstRun(@Autowired ApplicationContext context) {
    assertThat(meters.find(RetentionPurgeJob.METRIC).counter()).isNotNull();
    assertThat(context.getBean(RetentionPurgeConfig.class)).isNotNull();
  }

  private double purgedRows() {
    return meters.get(RetentionPurgeJob.METRIC).counter().count();
  }

  /** Inserts a request created {@code age} ago and returns its request id. */
  private String insert(Duration age) {
    String id = UUID.randomUUID().toString();
    jdbc.sql(
            "INSERT INTO request_log (request_id, question, status, duration_ms, created_at)"
                + " VALUES (:id, 'q', 'SUCCESS', 1, :created)")
        .param("id", id)
        .param("created", Timestamp.from(Instant.now().minus(age)))
        .update();
    return id;
  }
}
