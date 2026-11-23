package com.example.requestlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Repository tests against real PostgreSQL (Testcontainers, needs Docker) with the Flyway schema.
 *
 * <p>{@code RequestLogTools} is imported only because the application class declares its MCP
 * callback bean. Test-managed transactions are off, so each repository call runs in its own
 * transaction as in production. Rows are kept between tests, so every test uses fresh ids and
 * customers.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({RequestLogRepository.class, QuestionRepository.class, RequestLogTools.class})
@Testcontainers
class PostgresRepositoryTests {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired RequestLogRepository requests;
  @Autowired QuestionRepository questions;
  @Autowired JdbcClient jdbc;
  @Autowired TransactionTemplate tx;

  private static String newId() {
    return UUID.randomUUID().toString();
  }

  /** A customer id no other test uses, so filtered queries only see this test's rows. */
  private static String newCustomer() {
    return "c" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
  }

  @Test
  void runsOnPostgresWithAllMigrations() {
    assertThat(jdbc.sql("SELECT version()").query(String.class).single()).startsWith("PostgreSQL");
    assertThat(
            jdbc.sql(
                    "SELECT version FROM flyway_schema_history WHERE success ORDER BY"
                        + " installed_rank")
                .query(String.class)
                .list())
        .startsWith("1", "2", "3");
  }

  @Test
  void savesAndReadsBackAllColumns() {
    String id = newId();
    Instant before = Instant.now().minusSeconds(5);

    RequestLogDetail saved =
        requests.save(
            id,
            "How do I reset my password?",
            "Use the portal.",
            "SUCCESS",
            null,
            42L,
            "acme",
            null,
            null);

    assertThat(saved.id()).isPositive();
    assertThat(saved.requestId()).isEqualTo(id);
    assertThat(saved.question()).isEqualTo("How do I reset my password?");
    assertThat(saved.answer()).isEqualTo("Use the portal.");
    assertThat(saved.status()).isEqualTo("SUCCESS");
    assertThat(saved.errorType()).isNull();
    assertThat(saved.durationMs()).isEqualTo(42L);
    assertThat(saved.customer()).isEqualTo("acme");
    assertThat(saved.createdAt()).isBetween(before, Instant.now().plusSeconds(5));
    assertThat(requests.findByRequestId(id)).contains(saved);
  }

  @Test
  void savesAndReadsBackAuditColumns() {
    String id = newId();
    String calls = "[{\"tool\":\"getTicket\",\"customer\":\"acme\",\"outcome\":\"OK\"}]";
    String subject = "s".repeat(255);

    RequestLogDetail saved =
        requests.save(id, "q", "a", "SUCCESS", null, 1L, "acme", subject, calls);

    assertThat(saved.userSubject()).isEqualTo(subject);
    assertThat(saved.toolCalls()).isEqualTo(calls);
    assertThat(requests.findByRequestId(id)).contains(saved);
  }

  @Test
  void auditColumnsAreNullableAndNotListed() {
    String customer = newCustomer();
    String id = newId();
    requests.save(id, "q", "a", "SUCCESS", null, 1L, customer, "user-1", "[]");

    RequestLogDetail stored = requests.findByRequestId(id).orElseThrow();
    assertThat(requests.findRecent(10, customer)).containsExactly(stored.toEntry());

    String bare = newId();
    assertThat(requests.save(bare, "q", "a", "SUCCESS", null, 1L, null, null, null))
        .extracting(RequestLogDetail::userSubject, RequestLogDetail::toolCalls)
        .containsExactly(null, null);
  }

  @Test
  void identityIdsIncrease() {
    long first = requests.save(newId(), "q", "a", "SUCCESS", null, 1L, null, null, null).id();
    long second = requests.save(newId(), "q", "a", "SUCCESS", null, 1L, null, null, null).id();
    assertThat(second).isGreaterThan(first);
  }

  @Test
  void duplicateRequestIdReturnsStoredRowEvenInsideATransaction() {
    String id = newId();
    RequestLogDetail first = requests.save(id, "q", "first", "SUCCESS", null, 1L, null, null, null);

    // On PostgreSQL a failed statement aborts the surrounding transaction, so a retry must not
    // rely on catching a duplicate-key error and then reading in the same transaction.
    RequestLogDetail retry =
        tx.execute(status -> requests.save(id, "q", "second", "ERROR", "X", 2L, null, null, null));

    assertThat(retry).isEqualTo(first);
    assertThat(
            jdbc.sql("SELECT count(*) FROM request_log WHERE request_id = :id")
                .param("id", id)
                .query(Long.class)
                .single())
        .isEqualTo(1L);
  }

  @Test
  void findRecentBindsLimitAndOrdersNewestFirst() {
    String customer = newCustomer();
    String older = newId();
    String newer = newId();
    requests.save(older, "q1", "a", "SUCCESS", null, 1L, customer, null, null);
    requests.save(newer, "q2", "a", "SUCCESS", null, 1L, customer, null, null);

    assertThat(requests.findRecent(1, customer))
        .extracting(RequestLogEntry::requestId)
        .containsExactly(newer);
    assertThat(requests.findRecent(10, customer))
        .extracting(RequestLogEntry::requestId)
        .containsExactly(newer, older);
  }

  @Test
  void sameTimestampIsOrderedByIdDescending() {
    String customer = newCustomer();
    String a = newId();
    String b = newId();
    // One transaction: CURRENT_TIMESTAMP is the transaction start time on PostgreSQL.
    tx.executeWithoutResult(
        status -> {
          requests.save(a, "q", "a", "SUCCESS", null, 1L, customer, null, null);
          requests.save(b, "q", "a", "SUCCESS", null, 1L, customer, null, null);
        });

    List<RequestLogEntry> recent = requests.findRecent(10, customer);
    assertThat(recent.get(0).createdAt()).isEqualTo(recent.get(1).createdAt());
    assertThat(recent).extracting(RequestLogEntry::requestId).containsExactly(b, a);
  }

  @Test
  void findRecentFiltersByCustomerOnlyWhenGiven() {
    String acme = newCustomer();
    String globex = newCustomer();
    String acmeRequest = newId();
    String globexRequest = newId();
    String noCustomer = newId();
    requests.save(acmeRequest, "q", "a", "SUCCESS", null, 1L, acme, null, null);
    requests.save(globexRequest, "q", "a", "SUCCESS", null, 1L, globex, null, null);
    requests.save(noCustomer, "q", "a", "SUCCESS", null, 1L, null, null, null);

    assertThat(requests.findRecent(100, acme))
        .extracting(RequestLogEntry::requestId)
        .containsExactly(acmeRequest);
    assertThat(requests.findRecent(100, null))
        .extracting(RequestLogEntry::requestId)
        .contains(acmeRequest, globexRequest, noCustomer);
  }

  @Test
  void findByRequestIdIsEmptyForUnknownId() {
    assertThat(requests.findByRequestId(newId())).isEmpty();
  }

  @Test
  void seedQuestionsArePresentInIdOrder() {
    List<Question> all = questions.findAll();
    assertThat(all)
        .extracting(Question::text)
        .startsWith("My VPN keeps dropping, what should I try?", "How do I reset my password?");
    assertThat(all).extracting(Question::id).isSorted();
  }

  @Test
  void savingTheSameQuestionTwiceReturnsTheStoredOne() {
    String text = "Is printer " + newId() + " working?";
    Question added = questions.save(text);
    Question again = tx.execute(status -> questions.save(text));

    assertThat(again).isEqualTo(added);
    assertThat(questions.findByText(text)).contains(added);
    assertThat(added.createdAt()).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
    assertThat(questions.findAll().getLast()).isEqualTo(added);
  }
}
