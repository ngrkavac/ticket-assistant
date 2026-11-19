package com.example.requestlog;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Fails startup with a clear message when the request-log database is not configured.
 *
 * <p>Outside the {@code local} profile, {@code REQUEST_LOG_DB_URL} is required (there is no silent
 * in-memory fallback). In the {@code prod} profile it must also be a PostgreSQL URL. Runs as a
 * {@link BeanFactoryPostProcessor}, so it fails before the DataSource or Flyway touch any database.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!local")
class DatabaseUrlGuard {

  static final String URL_PROPERTY = "spring.datasource.url";

  @Bean
  static BeanFactoryPostProcessor databaseUrlCheck(Environment environment) {
    return beanFactory ->
        check(readUrl(environment), environment.acceptsProfiles(Profiles.of("prod")));
  }

  private static String readUrl(Environment environment) {
    try {
      return environment.getProperty(URL_PROPERTY);
    } catch (IllegalArgumentException unresolvedPlaceholder) {
      return null;
    }
  }

  static void check(String url, boolean prod) {
    if (url == null || url.isBlank() || url.startsWith("${")) {
      throw new IllegalStateException(
          "REQUEST_LOG_DB_URL must be set (a jdbc:postgresql: URL). For an in-memory H2 database"
              + " on a developer machine, run with the 'local' profile.");
    }
    if (prod && !url.startsWith("jdbc:postgresql:")) {
      // Only the scheme is echoed, so credentials in the URL never reach the logs.
      int schemeEnd = url.indexOf(':', "jdbc:".length());
      String scheme = schemeEnd < 0 ? "an unknown scheme" : url.substring(0, schemeEnd + 1);
      throw new IllegalStateException(
          "REQUEST_LOG_DB_URL must be a jdbc:postgresql: URL in the prod profile, got " + scheme);
    }
  }
}
