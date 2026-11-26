package com.example.requestlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The {@code prod} profile against real PostgreSQL (Testcontainers, needs Docker) with separate
 * users: Flyway migrates as {@code requestlog_migrator}, the application runs as {@code
 * requestlog_app}, which may only read and write rows. Also checks the pool size and the statement
 * timeout on real PostgreSQL.
 */
@SpringBootTest
@ActiveProfiles("prod")
@Testcontainers
class LeastPrivilegeTests {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17-alpine").withInitScript("least-privilege-roles.sql");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("REQUEST_LOG_DB_URL", POSTGRES::getJdbcUrl);
    registry.add("REQUEST_LOG_DB_USER", () -> "requestlog_app");
    registry.add("REQUEST_LOG_DB_PASSWORD", () -> "app-secret");
    registry.add("REQUEST_LOG_DB_MIGRATION_USER", () -> "requestlog_migrator");
    registry.add("REQUEST_LOG_DB_MIGRATION_PASSWORD", () -> "migrator-secret");
    registry.add("MCP_SERVICE_TOKEN", () -> "test-token");
    registry.add("REQUEST_LOG_DB_POOL_SIZE", () -> "3");
    registry.add("REQUEST_LOG_DB_STATEMENT_TIMEOUT", () -> "1s");
  }

  @Autowired DataSource dataSource;

  @Test
  void poolHasTheConfiguredSizeAndFailsFast() {
    var pool = (HikariDataSource) dataSource;
    assertThat(pool.getMaximumPoolSize()).isEqualTo(3);
    assertThat(pool.getConnectionTimeout()).isEqualTo(5000);
  }

  @Test
  void slowStatementsAreCancelledByTheServer() {
    assertThat(app.sql("SHOW statement_timeout").query(String.class).single()).isEqualTo("1s");
    assertThatThrownBy(() -> app.sql("SELECT pg_sleep(3)").query(String.class).single())
        .rootCause()
        .hasMessageContaining("canceling statement due to statement timeout");
  }

  @Autowired RequestLogTools tools;

  @Autowired RetentionPurgeJob purgeJob;

  /** The application's own connection pool, i.e. the application user. */
  @Autowired JdbcClient app;

  @Test
  void applicationRunsAsTheAppUser() {
    assertThat(app.sql("SELECT current_user").query(String.class).single())
        .isEqualTo("requestlog_app");
  }

  @Test
  void migrationsRanAsTheMigrationUserWhoOwnsTheTables() {
    assertThat(
            jdbcAsSuperuser()
                .sql("SELECT DISTINCT installed_by FROM flyway_schema_history")
                .query(String.class)
                .list())
        .containsExactly("requestlog_migrator");
    assertThat(
            jdbcAsSuperuser()
                .sql("SELECT DISTINCT tableowner FROM pg_tables WHERE schemaname = 'public'")
                .query(String.class)
                .list())
        .containsExactly("requestlog_migrator");
  }

  @Test
  void appUserCanReadAndWriteRows() {
    String id = UUID.randomUUID().toString();
    RequestLogEntry saved = tools.logRequest(id, "q", "a", "SUCCESS", null, 1L, "acme", "u", "[]");
    assertThat(tools.getRequest(id).toEntry()).isEqualTo(saved);
    assertThat(tools.listRecentRequests(5, "acme")).contains(saved);
    assertThat(tools.addQuestion("Least privilege " + id + "?").id()).isPositive();
    assertThat(tools.listQuestions()).isNotEmpty();
    // DELETE is granted for the retention purge (B5).
    assertThat(app.sql("DELETE FROM request_log WHERE request_id = :id").param("id", id).update())
        .isEqualTo(1);
  }

  @Test
  void appUserRunsTheRetentionPurgeWithItsShedLock() {
    String old = UUID.randomUUID().toString();
    String recent = UUID.randomUUID().toString();
    jdbcAsSuperuser()
        .sql(
            "INSERT INTO request_log (request_id, question, status, duration_ms, created_at) VALUES"
                + " (:old, 'q', 'SUCCESS', 1, now() - interval '91 days'),"
                + " (:recent, 'q', 'SUCCESS', 1, now() - interval '89 days')")
        .param("old", old)
        .param("recent", recent)
        .update();

    purgeJob.purge();

    assertThat(tools.getRequest(recent)).isNotNull();
    assertThat(
            app.sql("SELECT count(*) FROM request_log WHERE request_id = :id")
                .param("id", old)
                .query(Long.class)
                .single())
        .isZero();
    assertThat(
            app.sql("SELECT locked_by FROM shedlock WHERE name = :name")
                .param("name", RetentionPurgeJob.LOCK_NAME)
                .query(String.class)
                .list())
        .hasSize(1);
  }

  @Test
  void appUserHasNoDdlRightsAndCannotTouchFlywayHistory() {
    assertThatThrownBy(() -> app.sql("CREATE TABLE intruder (id INT)").update())
        .isInstanceOf(BadSqlGrammarException.class)
        .rootCause()
        .hasMessageContaining("permission denied for schema public");
    assertThatThrownBy(() -> app.sql("DROP TABLE request_log").update())
        .rootCause()
        .hasMessageContaining("must be owner");
    assertThatThrownBy(() -> app.sql("ALTER TABLE request_log ADD COLUMN x INT").update())
        .rootCause()
        .hasMessageContaining("must be owner");
    assertThatThrownBy(() -> app.sql("TRUNCATE request_log").update())
        .rootCause()
        .hasMessageContaining("permission denied");
    assertThatThrownBy(
            () -> app.sql("SELECT count(*) FROM flyway_schema_history").query(Long.class).single())
        .rootCause()
        .hasMessageContaining("permission denied");
  }

  private static JdbcClient jdbcAsSuperuser() {
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    return JdbcClient.create(dataSource);
  }
}
