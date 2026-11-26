package com.example.tickets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
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
 * users: Flyway migrates as {@code tickets_migrator}, the application runs as {@code tickets_app},
 * which may only read and write rows and draw ticket numbers. Also checks the pool size and the
 * statement timeout on real PostgreSQL.
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
    registry.add("TICKETS_DB_URL", POSTGRES::getJdbcUrl);
    registry.add("TICKETS_DB_USER", () -> "tickets_app");
    registry.add("TICKETS_DB_PASSWORD", () -> "app-secret");
    registry.add("TICKETS_DB_MIGRATION_USER", () -> "tickets_migrator");
    registry.add("TICKETS_DB_MIGRATION_PASSWORD", () -> "migrator-secret");
    registry.add("TICKETS_DB_POOL_SIZE", () -> "3");
    registry.add("TICKETS_DB_STATEMENT_TIMEOUT", () -> "1s");
    registry.add("MCP_SERVICE_TOKEN", () -> "test-token");
  }

  @Autowired DataSource dataSource;

  @Autowired Environment environment;

  @Test
  void prodLogsAreJson() {
    assertThat(environment.getProperty("logging.structured.format.console")).isEqualTo("ecs");
  }

  @Autowired TicketTools tools;

  /** The application's own connection pool, i.e. the application user. */
  @Autowired JdbcClient app;

  /** What an operator does in prod: add a customer as a privileged user. */
  @BeforeEach
  void customerExists() {
    jdbcAsSuperuser()
        .sql("INSERT INTO customer (id) VALUES ('acme') ON CONFLICT DO NOTHING")
        .update();
  }

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

  @Test
  void applicationRunsAsTheAppUser() {
    assertThat(app.sql("SELECT current_user").query(String.class).single())
        .isEqualTo("tickets_app");
  }

  @Test
  void migrationsRanAsTheMigrationUserWhoOwnsTablesAndSequence() {
    JdbcClient superuser = jdbcAsSuperuser();
    assertThat(
            superuser
                .sql("SELECT DISTINCT installed_by FROM flyway_schema_history")
                .query(String.class)
                .list())
        .containsExactly("tickets_migrator");
    assertThat(
            superuser
                .sql("SELECT DISTINCT tableowner FROM pg_tables WHERE schemaname = 'public'")
                .query(String.class)
                .list())
        .containsExactly("tickets_migrator");
    assertThat(
            superuser
                .sql(
                    "SELECT sequenceowner FROM pg_sequences WHERE sequencename ="
                        + " 'ticket_number_seq'")
                .query(String.class)
                .single())
        .isEqualTo("tickets_migrator");
  }

  @Test
  void appUserRunsEveryTool() {
    Ticket created = tools.createTicket("acme", "Least privilege", "LOW");

    assertThat(created.id()).startsWith("T-");
    assertThat(tools.listCustomers()).contains("acme");
    assertThat(tools.getTicket("acme", created.id())).isEqualTo(created);
    assertThat(tools.listTickets("acme", "OPEN")).contains(created);
    assertThat(tools.listOpenTickets("acme")).contains(created);
  }

  @Test
  void appUserHasNoDdlRightsAndCannotTouchFlywayHistoryOrTheSequence() {
    assertThatThrownBy(() -> app.sql("CREATE TABLE intruder (id INT)").update())
        .isInstanceOf(BadSqlGrammarException.class)
        .rootCause()
        .hasMessageContaining("permission denied for schema public");
    assertThatThrownBy(() -> app.sql("DROP TABLE ticket").update())
        .rootCause()
        .hasMessageContaining("must be owner");
    assertThatThrownBy(() -> app.sql("ALTER TABLE ticket ADD COLUMN x INT").update())
        .rootCause()
        .hasMessageContaining("must be owner");
    assertThatThrownBy(() -> app.sql("TRUNCATE ticket").update())
        .rootCause()
        .hasMessageContaining("permission denied");
    assertThatThrownBy(
            () -> app.sql("SELECT count(*) FROM flyway_schema_history").query(Long.class).single())
        .rootCause()
        .hasMessageContaining("permission denied");
    // nextval is allowed (createTicket), resetting the numbering is not.
    assertThatThrownBy(
            () -> app.sql("SELECT setval('ticket_number_seq', 1)").query(Long.class).single())
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
