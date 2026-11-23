package com.example.requestlog;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Server-side timeouts for the application's PostgreSQL connections, so a slow query or a hung
 * connection cannot hold a pool connection (and a request thread) forever.
 *
 * <p>For a {@code jdbc:postgresql:} URL, every pooled connection gets {@code statement_timeout}
 * from {@code request-log.db.statement-timeout} ({@code REQUEST_LOG_DB_STATEMENT_TIMEOUT}, default
 * 5s) through the driver's {@code options} property, plus a driver {@code socketTimeout} a little
 * longer than that as a backstop for a dead network. This is done here rather than in the JDBC URL,
 * because the URL comes from the environment, and rather than in the properties, because H2 (the
 * {@code local} profile) rejects these settings. An {@code options} setting already present in the
 * URL or in {@code spring.datasource.hikari.data-source-properties} wins. {@code 0} turns both off.
 *
 * <p>With a separate migration user (required in {@code prod}, see {@link MigrationUserConfig}),
 * Flyway connects outside the pool, so long migrations are not cut off. Without one, Flyway uses
 * the pool and the timeout applies to migrations too.
 */
@Configuration(proxyBeanMethods = false)
class DatabaseTimeoutsConfig {

  static final String STATEMENT_TIMEOUT_PROPERTY = "request-log.db.statement-timeout";

  /** How much longer the client waits on the socket than the server lets a statement run. */
  static final Duration SOCKET_TIMEOUT_MARGIN = Duration.ofSeconds(10);

  private static final Logger log = LoggerFactory.getLogger(DatabaseTimeoutsConfig.class);

  @Bean
  static BeanPostProcessor postgresTimeouts(Environment environment) {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof HikariDataSource dataSource) {
          Duration timeout =
              environment.getProperty(
                  STATEMENT_TIMEOUT_PROPERTY, Duration.class, Duration.ofSeconds(5));
          apply(dataSource, timeout);
        }
        return bean;
      }
    };
  }

  static void apply(HikariDataSource dataSource, Duration statementTimeout) {
    if (statementTimeout.isNegative()) {
      throw new IllegalStateException(
          "REQUEST_LOG_DB_STATEMENT_TIMEOUT must not be negative, got " + statementTimeout);
    }
    String url = dataSource.getJdbcUrl();
    if (url == null || !url.startsWith("jdbc:postgresql:") || statementTimeout.isZero()) {
      return;
    }
    if (url.contains("options=") || dataSource.getDataSourceProperties().containsKey("options")) {
      log.info("PostgreSQL 'options' already configured; statement_timeout left as configured");
      return;
    }
    dataSource.addDataSourceProperty(
        "options", "-c statement_timeout=" + statementTimeout.toMillis());
    if (!url.contains("socketTimeout=")
        && !dataSource.getDataSourceProperties().containsKey("socketTimeout")) {
      long seconds = statementTimeout.plus(SOCKET_TIMEOUT_MARGIN).toSeconds();
      dataSource.addDataSourceProperty("socketTimeout", Long.toString(seconds));
    }
  }
}
