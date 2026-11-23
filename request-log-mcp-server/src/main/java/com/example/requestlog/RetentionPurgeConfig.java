package com.example.requestlog;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Schedules the {@link RetentionPurgeJob} with a ShedLock JDBC lock (table {@code shedlock}, see
 * {@code V4__shedlock.sql}), so with several replicas only one purges. Lock times come from the
 * database clock, so clock skew between pods does not matter. Turned off with {@code
 * request-log.purge.enabled=false}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "request-log.purge.enabled", havingValue = "true")
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
class RetentionPurgeConfig {

  @Bean
  LockProvider lockProvider(DataSource dataSource) {
    return new JdbcTemplateLockProvider(
        JdbcTemplateLockProvider.Configuration.builder()
            .withJdbcTemplate(new JdbcTemplate(dataSource))
            .usingDbTime()
            .build());
  }

  @Bean
  RetentionPurgeJob retentionPurgeJob(
      RequestLogRepository requests,
      MeterRegistry meters,
      @Value("${request-log.retention}") Duration retention,
      @Value("${request-log.purge.batch-size}") int batchSize,
      @Value("${request-log.purge.max-batches}") int maxBatches) {
    return new RetentionPurgeJob(requests, meters, retention, batchSize, maxBatches);
  }
}
