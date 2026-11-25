package com.example.tickets;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Fails startup with a clear message when the ticket database is not configured.
 *
 * <p>Outside the {@code local} profile, {@code TICKETS_DB_URL} is required (there is no silent
 * in-memory fallback). In the {@code prod} profile it must also be a PostgreSQL URL, and Flyway
 * must use its own migration user ({@code TICKETS_DB_MIGRATION_USER}, see {@link
 * MigrationUserConfig}) so the application user needs no DDL rights. Runs as a {@link
 * BeanFactoryPostProcessor}, so it fails before the DataSource or Flyway touch any database.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!local")
class TicketsDatabaseUrlGuard {

  static final String URL_PROPERTY = "spring.datasource.url";

  static final String USER_PROPERTY = "spring.datasource.username";

  @Bean
  static BeanFactoryPostProcessor ticketsDatabaseUrlCheck(Environment environment) {
    return beanFactory -> {
      boolean prod = environment.acceptsProfiles(Profiles.of("prod"));
      check(read(environment, URL_PROPERTY), prod);
      if (prod) {
        checkMigrationUser(
            read(environment, MigrationUserConfig.USER_PROPERTY), read(environment, USER_PROPERTY));
      }
    };
  }

  private static String read(Environment environment, String property) {
    try {
      return environment.getProperty(property);
    } catch (IllegalArgumentException unresolvedPlaceholder) {
      return null;
    }
  }

  static void check(String url, boolean prod) {
    if (url == null || url.isBlank() || url.startsWith("${")) {
      throw new IllegalStateException(
          "TICKETS_DB_URL must be set (a jdbc:postgresql: URL). For an in-memory H2 database with"
              + " demo data on a developer machine, run with the 'local' profile.");
    }
    if (prod && !url.startsWith("jdbc:postgresql:")) {
      // Only the scheme is echoed, so credentials in the URL never reach the logs.
      int schemeEnd = url.indexOf(':', "jdbc:".length());
      String scheme = schemeEnd < 0 ? "an unknown scheme" : url.substring(0, schemeEnd + 1);
      throw new IllegalStateException(
          "TICKETS_DB_URL must be a jdbc:postgresql: URL in the prod profile, got " + scheme);
    }
  }

  /** In {@code prod}: a migration user is required and must not be the application user. */
  static void checkMigrationUser(String migrationUser, String appUser) {
    if (migrationUser == null || migrationUser.isBlank()) {
      throw new IllegalStateException(
          "TICKETS_DB_MIGRATION_USER and TICKETS_DB_MIGRATION_PASSWORD must be set in the prod"
              + " profile: Flyway runs as this user (DDL), the application as TICKETS_DB_USER (DML"
              + " only).");
    }
    if (migrationUser.equals(appUser)) {
      throw new IllegalStateException(
          "TICKETS_DB_MIGRATION_USER must differ from TICKETS_DB_USER in the prod profile, so the"
              + " application user has no DDL rights.");
    }
  }
}
