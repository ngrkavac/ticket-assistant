package com.example.requestlog;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * Fails startup with a clear message when the request-log database is not configured.
 *
 * <p>Outside the {@code local} and {@code prod} profiles (e.g. compose, which runs without a
 * profile), only {@code REQUEST_LOG_DB_URL} is required, so there is no silent in-memory fallback.
 * The {@code prod} profile checks this and all its other required settings in {@link
 * ProdConfigGuard}. Runs as a {@link BeanFactoryPostProcessor}, so it fails before the DataSource
 * or Flyway touch any database.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!local & !prod")
class DatabaseUrlGuard {

  static final String URL_PROPERTY = "spring.datasource.url";

  @Bean
  static BeanFactoryPostProcessor databaseUrlCheck(Environment environment) {
    return beanFactory -> check(read(environment));
  }

  private static String read(Environment environment) {
    try {
      return environment.getProperty(URL_PROPERTY);
    } catch (IllegalArgumentException unresolvedPlaceholder) {
      return null;
    }
  }

  static void check(String url) {
    if (url == null || url.isBlank() || url.startsWith("${")) {
      throw new IllegalStateException(
          "REQUEST_LOG_DB_URL must be set (a jdbc:postgresql: URL). For an in-memory H2 database"
              + " on a developer machine, run with the 'local' profile.");
    }
  }
}
