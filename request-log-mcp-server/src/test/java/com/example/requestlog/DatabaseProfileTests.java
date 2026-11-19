package com.example.requestlog;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
