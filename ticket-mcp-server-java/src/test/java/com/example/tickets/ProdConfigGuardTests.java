package com.example.tickets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.mock.env.MockEnvironment;

/** The prod profile fails fast, with one message listing every missing setting (T5.8). */
class ProdConfigGuardTests {

  private static MockEnvironment complete() {
    return new MockEnvironment()
        .withProperty("spring.datasource.url", "jdbc:postgresql://db:5432/tickets")
        .withProperty("spring.datasource.username", "tickets_app")
        .withProperty("spring.datasource.password", "app-secret")
        .withProperty(MigrationUserConfig.USER_PROPERTY, "tickets_migrator")
        .withProperty(MigrationUserConfig.PASSWORD_PROPERTY, "migrator-secret")
        .withProperty(McpServiceAuthConfig.TOKEN_PROPERTY, "service-token");
  }

  @Test
  void completeConfigurationPasses() {
    assertThat(ProdConfigGuard.problems(complete())).isEmpty();
    assertThatCode(() -> ProdConfigGuard.check(complete())).doesNotThrowAnyException();
  }

  @Test
  void everyMissingSettingIsListedInOneMessage() {
    // Unresolved placeholders, as application-prod.properties leaves them without the env vars.
    var environment =
        new MockEnvironment()
            .withProperty("spring.datasource.url", "${TICKETS_DB_URL}")
            .withProperty("spring.datasource.username", "${TICKETS_DB_USER}")
            .withProperty("spring.datasource.password", "")
            .withProperty(MigrationUserConfig.USER_PROPERTY, " ")
            .withProperty(McpServiceAuthConfig.TOKEN_PROPERTY, "");

    assertThat(ProdConfigGuard.problems(environment))
        .containsExactly(
            "TICKETS_DB_URL must be set (a jdbc:postgresql: URL)",
            "TICKETS_DB_USER must be set",
            "TICKETS_DB_PASSWORD must be set",
            "TICKETS_DB_MIGRATION_USER must be set: Flyway runs as this user (DDL), the"
                + " application as TICKETS_DB_USER (DML only)",
            "TICKETS_DB_MIGRATION_PASSWORD must be set",
            "MCP_SERVICE_TOKEN must be set: every MCP request must carry it as 'Authorization:"
                + " Bearer <token>'");
    assertThatThrownBy(() -> ProdConfigGuard.check(environment))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("The prod profile is missing required configuration:\n  - ")
        .hasMessageContaining("\n  - MCP_SERVICE_TOKEN must be set");
  }

  @Test
  void migrationUserMustDifferFromTheAppUser() {
    var environment = complete().withProperty(MigrationUserConfig.USER_PROPERTY, "tickets_app");
    assertThat(ProdConfigGuard.problems(environment))
        .containsExactly(
            "TICKETS_DB_MIGRATION_USER must differ from TICKETS_DB_USER, so the"
                + " application user has no DDL rights");
  }

  @Test
  void onlyTheSchemeOfAForeignUrlIsEchoed() {
    var h2 = complete().withProperty("spring.datasource.url", "jdbc:h2:mem:x;PASSWORD=secret");
    assertThat(ProdConfigGuard.problems(h2))
        .containsExactly("TICKETS_DB_URL must be a jdbc:postgresql: URL, got jdbc:h2:");
    var unknown = complete().withProperty("spring.datasource.url", "secret-url");
    assertThat(ProdConfigGuard.problems(unknown))
        .containsExactly("TICKETS_DB_URL must be a jdbc:postgresql: URL, got an unknown scheme");
  }

  @Test
  void secretsNeverAppearInTheMessage() {
    var environment =
        complete()
            .withProperty("spring.datasource.url", "jdbc:mysql://u:db-secret@db/x")
            .withProperty(MigrationUserConfig.PASSWORD_PROPERTY, "");
    assertThatThrownBy(() -> ProdConfigGuard.check(environment))
        .hasMessageNotContaining("db-secret")
        .hasMessageNotContaining("app-secret")
        .hasMessageNotContaining("service-token");
  }

  @Test
  void prodStartupFailsWithTheFullList() {
    assumeTrue(System.getenv("TICKETS_DB_URL") == null, "TICKETS_DB_URL is set");
    assumeTrue(System.getenv("MCP_SERVICE_TOKEN") == null, "MCP_SERVICE_TOKEN is set");
    assertThatThrownBy(
            () ->
                new SpringApplicationBuilder(TicketServerApplication.class)
                    .properties("server.port=0", "spring.main.banner-mode=off")
                    .profiles("prod")
                    .run()
                    .close())
        .hasMessageStartingWith("The prod profile is missing required configuration:")
        .hasMessageContaining("TICKETS_DB_URL must be set")
        .hasMessageContaining("TICKETS_DB_PASSWORD must be set")
        .hasMessageContaining("TICKETS_DB_MIGRATION_PASSWORD must be set")
        .hasMessageContaining("MCP_SERVICE_TOKEN must be set");
  }
}
