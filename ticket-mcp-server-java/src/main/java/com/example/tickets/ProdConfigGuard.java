package com.example.tickets;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * Fail-fast validation of the {@code prod} profile (T5.8, contracts K5 and K8).
 *
 * <p>Checks every required setting and fails startup with one message that lists all problems, so
 * an operator fixes a Secret or ConfigMap once instead of once per restart:
 *
 * <ul>
 *   <li>{@code TICKETS_DB_URL}: set, and a {@code jdbc:postgresql:} URL;
 *   <li>{@code TICKETS_DB_USER} and {@code TICKETS_DB_PASSWORD}: set;
 *   <li>{@code TICKETS_DB_MIGRATION_USER} and {@code TICKETS_DB_MIGRATION_PASSWORD}: set, and the
 *       migration user differs from the application user (see {@link MigrationUserConfig});
 *   <li>{@code MCP_SERVICE_TOKEN}: set (see {@link McpServiceAuthConfig}).
 * </ul>
 *
 * <p>The message names the environment variables only and never echoes a value (of a URL only its
 * scheme), so no secret reaches the logs. Runs as a {@link BeanFactoryPostProcessor}, so it fails
 * before the DataSource or Flyway touch any database. Other profiles are not affected: outside
 * {@code local} they only need {@code TICKETS_DB_URL} ({@link TicketsDatabaseUrlGuard}).
 */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
class ProdConfigGuard {

  @Bean
  static BeanFactoryPostProcessor prodConfigCheck(Environment environment) {
    return beanFactory -> check(environment);
  }

  static void check(Environment environment) {
    List<String> problems = problems(environment);
    if (!problems.isEmpty()) {
      throw new IllegalStateException(
          "The prod profile is missing required configuration:\n  - "
              + String.join("\n  - ", problems));
    }
  }

  static List<String> problems(Environment environment) {
    List<String> problems = new ArrayList<>();
    String url = read(environment, "spring.datasource.url");
    if (url == null) {
      problems.add("TICKETS_DB_URL must be set (a jdbc:postgresql: URL)");
    } else if (!url.startsWith("jdbc:postgresql:")) {
      problems.add("TICKETS_DB_URL must be a jdbc:postgresql: URL, got " + scheme(url));
    }
    String appUser = read(environment, "spring.datasource.username");
    requireSet(problems, appUser, "TICKETS_DB_USER");
    requireSet(problems, read(environment, "spring.datasource.password"), "TICKETS_DB_PASSWORD");
    String migrationUser = read(environment, MigrationUserConfig.USER_PROPERTY);
    if (migrationUser == null) {
      problems.add(
          "TICKETS_DB_MIGRATION_USER must be set: Flyway runs as this user (DDL), the"
              + " application as TICKETS_DB_USER (DML only)");
    } else if (migrationUser.equals(appUser)) {
      problems.add(
          "TICKETS_DB_MIGRATION_USER must differ from TICKETS_DB_USER, so the application"
              + " user has no DDL rights");
    }
    requireSet(
        problems,
        read(environment, MigrationUserConfig.PASSWORD_PROPERTY),
        "TICKETS_DB_MIGRATION_PASSWORD");
    if (read(environment, McpServiceAuthConfig.TOKEN_PROPERTY) == null) {
      problems.add(
          "MCP_SERVICE_TOKEN must be set: every MCP request must carry it as 'Authorization:"
              + " Bearer <token>'");
    }
    return problems;
  }

  private static void requireSet(List<String> problems, String value, String variable) {
    if (value == null) {
      problems.add(variable + " must be set");
    }
  }

  /** The property's value, or {@code null} if it is unset, blank or an unresolved placeholder. */
  private static String read(Environment environment, String property) {
    String value;
    try {
      value = environment.getProperty(property);
    } catch (IllegalArgumentException unresolvedPlaceholder) {
      return null;
    }
    return value == null || value.isBlank() || value.startsWith("${") ? null : value;
  }

  /** Only the scheme is echoed, so credentials in the URL never reach the logs. */
  static String scheme(String url) {
    int schemeEnd = url.indexOf(':', "jdbc:".length());
    return schemeEnd < 0 ? "an unknown scheme" : url.substring(0, schemeEnd + 1);
  }
}
