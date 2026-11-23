package com.example.requestlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** There is no silent in-memory fallback: only the {@code local} profile may default to H2. */
class DatabaseProfileTests {

  private static final String MISSING_URL = "REQUEST_LOG_DB_URL must be set";

  private static void start(String profile, String... args) {
    SpringApplicationBuilder app =
        new SpringApplicationBuilder(RequestLogServerApplication.class)
            .properties("server.port=0", "spring.main.banner-mode=off");
    if (profile != null) {
      app.profiles(profile);
    }
    app.run(args).close();
  }

  private static void assumeNoDatabaseUrlInEnvironment() {
    assumeTrue(System.getenv("REQUEST_LOG_DB_URL") == null, "REQUEST_LOG_DB_URL is set");
  }

  @Test
  void defaultProfileFailsWithoutDatabaseUrl() {
    assumeNoDatabaseUrlInEnvironment();
    assertThatThrownBy(() -> start(null)).hasStackTraceContaining(MISSING_URL);
  }

  @Test
  void prodProfileFailsWithoutDatabaseUrl() {
    assumeNoDatabaseUrlInEnvironment();
    assertThatThrownBy(() -> start("prod")).hasStackTraceContaining(MISSING_URL);
  }

  @Test
  void prodProfileRejectsH2() {
    assertThatThrownBy(
            () ->
                start(
                    "prod",
                    "--REQUEST_LOG_DB_URL=jdbc:h2:mem:prodguard;DB_CLOSE_DELAY=-1",
                    "--REQUEST_LOG_DB_USER=sa",
                    "--REQUEST_LOG_DB_PASSWORD="))
        .hasStackTraceContaining(
            "must be a jdbc:postgresql: URL in the prod profile, got jdbc:h2:");
  }

  @Test
  void localProfileDefaultsToH2() {
    assumeNoDatabaseUrlInEnvironment();
    assertThatCode(() -> start("local")).doesNotThrowAnyException();
  }

  @Test
  void guardAcceptsPostgresInProdAndAnyUrlOutsideProd() {
    assertThatCode(() -> DatabaseUrlGuard.check("jdbc:postgresql://db:5432/requestlog", true))
        .doesNotThrowAnyException();
    assertThatCode(() -> DatabaseUrlGuard.check("jdbc:h2:mem:x", false)).doesNotThrowAnyException();
  }

  @Test
  void prodProfileRequiresASeparateMigrationUser() {
    String[] database = {
      // Never contacted: the guard fails before the DataSource or Flyway are created.
      "--REQUEST_LOG_DB_URL=jdbc:postgresql://localhost:1/requestlog",
      "--REQUEST_LOG_DB_USER=requestlog_app",
      "--REQUEST_LOG_DB_PASSWORD=x"
    };
    assumeTrue(System.getenv("REQUEST_LOG_DB_MIGRATION_USER") == null, "migration user is set");
    assertThatThrownBy(() -> start("prod", database))
        .hasStackTraceContaining("REQUEST_LOG_DB_MIGRATION_USER and")
        .hasStackTraceContaining("must be set in the prod profile");
    String[] sameUser = Arrays.copyOf(database, database.length + 1);
    sameUser[database.length] = "--REQUEST_LOG_DB_MIGRATION_USER=requestlog_app";
    assertThatThrownBy(() -> start("prod", sameUser))
        .hasStackTraceContaining("must differ from REQUEST_LOG_DB_USER");
  }

  @Test
  void guardChecksTheMigrationUser() {
    assertThatThrownBy(() -> DatabaseUrlGuard.checkMigrationUser(null, "app"))
        .hasMessageContaining("must be set");
    assertThatThrownBy(() -> DatabaseUrlGuard.checkMigrationUser(" ", "app"))
        .hasMessageContaining("must be set");
    assertThatThrownBy(() -> DatabaseUrlGuard.checkMigrationUser("app", "app"))
        .hasMessageContaining("must differ");
    assertThatCode(() -> DatabaseUrlGuard.checkMigrationUser("migrator", "app"))
        .doesNotThrowAnyException();
    assertThatCode(() -> DatabaseUrlGuard.checkMigrationUser("migrator", null))
        .doesNotThrowAnyException();
  }

  @Test
  void grantsQuoteEveryIdentifier() {
    assertThat(MigrationUserConfig.AppUserGrants.grants("public", "flyway_schema_history", "a\"b"))
        .containsExactly(
            "GRANT USAGE ON SCHEMA \"public\" TO \"a\"\"b\"",
            "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA \"public\" TO \"a\"\"b\"",
            "REVOKE ALL ON TABLE \"public\".\"flyway_schema_history\" FROM \"a\"\"b\"",
            "GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA \"public\" TO \"a\"\"b\"");
  }

  @Test
  void guardRejectsMissingAndUnknownUrls() {
    assertThatThrownBy(() -> DatabaseUrlGuard.check("  ", false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(MISSING_URL);
    assertThatThrownBy(() -> DatabaseUrlGuard.check("${REQUEST_LOG_DB_URL}", false))
        .hasMessageContaining(MISSING_URL);
    assertThatThrownBy(() -> DatabaseUrlGuard.check("postgres", true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageEndingWith("got an unknown scheme");
  }
}
