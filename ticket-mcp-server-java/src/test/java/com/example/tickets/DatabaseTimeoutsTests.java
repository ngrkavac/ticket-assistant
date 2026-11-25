package com.example.tickets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.mock.env.MockEnvironment;

/** Which pooled connections get the PostgreSQL timeouts (no database needed). */
class DatabaseTimeoutsTests {

  private static HikariDataSource pool(String url) {
    var dataSource = new HikariDataSource();
    dataSource.setJdbcUrl(url);
    return dataSource;
  }

  @Test
  void postgresGetsStatementAndSocketTimeouts() {
    var dataSource = pool("jdbc:postgresql://db:5432/tickets");

    DatabaseTimeoutsConfig.apply(dataSource, Duration.ofSeconds(5));

    assertThat(dataSource.getDataSourceProperties())
        .containsEntry("options", "-c statement_timeout=5000")
        .containsEntry("socketTimeout", "15");
  }

  @Test
  void theBeanPostProcessorReadsTheConfiguredTimeout() {
    var environment =
        new MockEnvironment().withProperty(DatabaseTimeoutsConfig.STATEMENT_TIMEOUT_PROPERTY, "2s");
    // Spring Boot installs this conversion service (it parses "2s"); MockEnvironment does not.
    environment.setConversionService(new ApplicationConversionService());
    var dataSource = pool("jdbc:postgresql://db/tickets");
    var processor = DatabaseTimeoutsConfig.postgresTimeouts(environment);

    assertThat(processor.postProcessBeforeInitialization(dataSource, "dataSource"))
        .isSameAs(dataSource);
    assertThat(processor.postProcessBeforeInitialization("other bean", "other"))
        .isEqualTo("other bean");
    assertThat(dataSource.getDataSourceProperties())
        .containsEntry("options", "-c statement_timeout=2000")
        .containsEntry("socketTimeout", "12");
  }

  @Test
  void h2AndZeroTimeoutAreLeftAlone() {
    var h2 = pool("jdbc:h2:mem:tickets");
    DatabaseTimeoutsConfig.apply(h2, Duration.ofSeconds(5));
    assertThat(h2.getDataSourceProperties()).isEmpty();

    var off = pool("jdbc:postgresql://db/tickets");
    DatabaseTimeoutsConfig.apply(off, Duration.ZERO);
    assertThat(off.getDataSourceProperties()).isEmpty();

    var noUrl = new HikariDataSource();
    DatabaseTimeoutsConfig.apply(noUrl, Duration.ofSeconds(5));
    assertThat(noUrl.getDataSourceProperties()).isEmpty();
  }

  @Test
  void explicitDriverSettingsWin() {
    var inUrl = pool("jdbc:postgresql://db/tickets?options=-c%20statement_timeout=1000");
    DatabaseTimeoutsConfig.apply(inUrl, Duration.ofSeconds(5));
    assertThat(inUrl.getDataSourceProperties()).isEmpty();

    var inProperties = pool("jdbc:postgresql://db/tickets");
    inProperties.addDataSourceProperty("options", "-c statement_timeout=1000");
    DatabaseTimeoutsConfig.apply(inProperties, Duration.ofSeconds(5));
    assertThat(inProperties.getDataSourceProperties())
        .containsEntry("options", "-c statement_timeout=1000")
        .doesNotContainKey("socketTimeout");

    var socketInUrl = pool("jdbc:postgresql://db/tickets?socketTimeout=60");
    DatabaseTimeoutsConfig.apply(socketInUrl, Duration.ofSeconds(5));
    assertThat(socketInUrl.getDataSourceProperties())
        .containsEntry("options", "-c statement_timeout=5000")
        .doesNotContainKey("socketTimeout");
  }

  @Test
  void negativeTimeoutIsRejected() {
    assertThatThrownBy(
            () ->
                DatabaseTimeoutsConfig.apply(
                    pool("jdbc:postgresql://db/tickets"), Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TICKETS_DB_STATEMENT_TIMEOUT");
  }
}
