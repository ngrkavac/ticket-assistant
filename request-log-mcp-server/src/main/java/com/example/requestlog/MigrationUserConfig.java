package com.example.requestlog;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Least privilege for the request-log database: Flyway runs as a separate migration user (DDL, owns
 * the tables), the application runs as {@code REQUEST_LOG_DB_USER} with DML rights only.
 *
 * <p>When {@code REQUEST_LOG_DB_MIGRATION_USER} is set, Flyway connects with it instead of the
 * application user. After every migrate on PostgreSQL, the migration user grants the application
 * user {@code SELECT, INSERT, UPDATE, DELETE} on all tables of the schema (except Flyway's history
 * table) and usage of its sequences, so new tables from later migrations are covered too. Without a
 * separate migration user (e.g. the {@code local} profile on H2) nothing changes.
 */
@Configuration(proxyBeanMethods = false)
class MigrationUserConfig {

  static final String USER_PROPERTY = "request-log.migration.user";

  static final String PASSWORD_PROPERTY = "request-log.migration.password";

  @Bean
  FlywayConfigurationCustomizer migrationUserCustomizer(
      @Value("${" + USER_PROPERTY + ":}") String user,
      @Value("${" + PASSWORD_PROPERTY + ":}") String password,
      JdbcConnectionDetails connection) {
    return configuration -> {
      if (!user.isBlank()) {
        configuration.dataSource(connection.getJdbcUrl(), user, password);
      }
    };
  }

  @Bean
  Callback appUserGrants(JdbcConnectionDetails connection) {
    return new AppUserGrants(connection.getUsername());
  }

  /** Grants the application user DML on the migrated schema; runs as the migration user. */
  static final class AppUserGrants implements Callback {

    private static final Logger log = LoggerFactory.getLogger(AppUserGrants.class);

    private final String appUser;

    AppUserGrants(String appUser) {
      this.appUser = appUser;
    }

    @Override
    public boolean supports(Event event, Context context) {
      return event == Event.AFTER_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
      return true;
    }

    @Override
    public void handle(Event event, Context context) {
      try {
        Connection connection = context.getConnection();
        String migrationUser = connection.getMetaData().getUserName();
        if (!isPostgres(connection) || appUser == null || appUser.equals(migrationUser)) {
          return;
        }
        String schema = connection.getSchema();
        String table = context.getConfiguration().getTable();
        try (Statement statement = connection.createStatement()) {
          for (String sql : grants(schema, table, appUser)) {
            statement.execute(sql);
          }
        }
        log.info("Granted DML on schema {} to application user {}", schema, appUser);
      } catch (SQLException e) {
        throw new IllegalStateException(
            "Could not grant the application user access to the request-log schema: "
                + e.getMessage(),
            e);
      }
    }

    @Override
    public String getCallbackName() {
      return "appUserGrants";
    }

    private static boolean isPostgres(Connection connection) throws SQLException {
      return "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName());
    }

    /** The idempotent grant statements, with every identifier quoted. */
    static List<String> grants(String schema, String historyTable, String appUser) {
      String s = quote(schema);
      String u = quote(appUser);
      return List.of(
          "GRANT USAGE ON SCHEMA " + s + " TO " + u,
          "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA " + s + " TO " + u,
          "REVOKE ALL ON TABLE " + s + "." + quote(historyTable) + " FROM " + u,
          "GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA " + s + " TO " + u);
    }

    static String quote(String identifier) {
      return '"' + identifier.replace("\"", "\"\"") + '"';
    }
  }
}
