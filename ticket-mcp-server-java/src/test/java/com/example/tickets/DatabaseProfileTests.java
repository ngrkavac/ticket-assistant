package com.example.tickets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Profiles: there is no silent in-memory fallback, only the {@code local} profile defaults to H2,
 * and the demo data exists only in {@code local}.
 */
@Testcontainers
class DatabaseProfileTests {

  private static final String MISSING_URL = "TICKETS_DB_URL must be set";

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17-alpine").withInitScript("least-privilege-roles.sql");

  private static ConfigurableApplicationContext start(String profile, String... args) {
    SpringApplicationBuilder app =
        new SpringApplicationBuilder(TicketServerApplication.class)
            .properties("server.port=0", "spring.main.banner-mode=off");
    if (profile != null) {
      app.profiles(profile);
    }
    return app.run(args);
  }

  /** PostgreSQL with separate migration and application users, as the prod profile requires. */
  private static String[] postgresArgs() {
    return new String[] {
      "--TICKETS_DB_URL=" + POSTGRES.getJdbcUrl(),
      "--TICKETS_DB_USER=tickets_app",
      "--TICKETS_DB_PASSWORD=app-secret",
      "--TICKETS_DB_MIGRATION_USER=tickets_migrator",
      "--TICKETS_DB_MIGRATION_PASSWORD=migrator-secret",
      "--MCP_SERVICE_TOKEN=test-token"
    };
  }

  private static void assumeNoDatabaseUrlInEnvironment() {
    assumeTrue(System.getenv("TICKETS_DB_URL") == null, "TICKETS_DB_URL is set");
  }

  @Test
  void defaultProfileFailsWithoutDatabaseUrl() {
    assumeNoDatabaseUrlInEnvironment();
    assertThatThrownBy(() -> start(null).close()).hasStackTraceContaining(MISSING_URL);
  }

  @Test
  void prodProfileFailsWithoutDatabaseUrl() {
    assumeNoDatabaseUrlInEnvironment();
    assertThatThrownBy(() -> start("prod").close()).hasStackTraceContaining(MISSING_URL);
  }

  @Test
  void prodProfileRejectsH2() {
    assertThatThrownBy(
            () ->
                start(
                        "prod",
                        "--TICKETS_DB_URL=jdbc:h2:mem:prodguard;DB_CLOSE_DELAY=-1",
                        "--TICKETS_DB_USER=sa",
                        "--TICKETS_DB_PASSWORD=")
                    .close())
        .hasStackTraceContaining(
            "must be a jdbc:postgresql: URL in the prod profile, got jdbc:h2:");
  }

  @Test
  void prodProfileRequiresASeparateMigrationUser() {
    assertThatThrownBy(
            () ->
                start(
                        "prod",
                        "--TICKETS_DB_URL=" + POSTGRES.getJdbcUrl(),
                        "--TICKETS_DB_USER=tickets_app",
                        "--TICKETS_DB_PASSWORD=app-secret")
                    .close())
        .hasStackTraceContaining("TICKETS_DB_MIGRATION_USER and TICKETS_DB_MIGRATION_PASSWORD");
    assertThatThrownBy(
            () ->
                start(
                        "prod",
                        "--TICKETS_DB_URL=" + POSTGRES.getJdbcUrl(),
                        "--TICKETS_DB_USER=tickets_app",
                        "--TICKETS_DB_PASSWORD=app-secret",
                        "--TICKETS_DB_MIGRATION_USER=tickets_app")
                    .close())
        .hasStackTraceContaining("TICKETS_DB_MIGRATION_USER must differ from TICKETS_DB_USER");
  }

  @Test
  void guardAcceptsADistinctMigrationUser() {
    assertThatCode(() -> TicketsDatabaseUrlGuard.checkMigrationUser("tickets_migrator", "app"))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> TicketsDatabaseUrlGuard.checkMigrationUser(" ", "app"))
        .hasMessageContaining("must be set in the prod profile");
  }

  @Test
  void guardAcceptsPostgresAndHidesUnknownSchemes() {
    assertThatCode(() -> TicketsDatabaseUrlGuard.check("jdbc:postgresql://db/tickets", true))
        .doesNotThrowAnyException();
    assertThatCode(() -> TicketsDatabaseUrlGuard.check("jdbc:h2:mem:x", false))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> TicketsDatabaseUrlGuard.check("secret-url", true))
        .hasMessageEndingWith("got an unknown scheme");
    assertThatThrownBy(() -> TicketsDatabaseUrlGuard.check(" ", false))
        .hasMessageContaining(MISSING_URL);
    assertThatThrownBy(() -> TicketsDatabaseUrlGuard.check("${TICKETS_DB_URL}", false))
        .hasMessageContaining(MISSING_URL);
  }

  @Test
  void localProfileDefaultsToH2WithDemoData() {
    assumeNoDatabaseUrlInEnvironment();
    try (ConfigurableApplicationContext context = start("local")) {
      TicketTools tools = context.getBean(TicketTools.class);
      assertThat(tools.listCustomers()).containsExactly("acme", "globex", "initech");
      assertThat(tools.getTicket("acme", "T-1001").subject())
          .isEqualTo("VPN drops every 10 minutes");
    }
  }

  @Test
  void prodProfileOnPostgresHasSchemaButNoDemoData() {
    try (ConfigurableApplicationContext context = start("prod", postgresArgs())) {
      TicketTools tools = context.getBean(TicketTools.class);
      assertThat(tools.listCustomers()).isEmpty();
      assertThat(tools.listTickets("acme", null)).isEmpty();
      assertThatThrownBy(() -> tools.createTicket("acme", "x", "LOW"))
          .hasMessage("Unknown customer acme");

      // An operator adds a customer; numbering starts at T-1001 without the seed.
      JdbcClient.create(
              new DriverManagerDataSource(
                  POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()))
          .sql("INSERT INTO customer (id) VALUES ('acme')")
          .update();
      assertThat(tools.createTicket("acme", "First real ticket", "HIGH"))
          .isEqualTo(new Ticket("T-1001", "acme", "First real ticket", "OPEN", "HIGH"));
    }
  }
}
