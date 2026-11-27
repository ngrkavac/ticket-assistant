package com.example.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** application.properties reads the per-user /ask limit from RATE_LIMIT_ASKS_PER_MINUTE (K8). */
class RateLimitPropertiesTests {

  private static HelpdeskProperties bind(Map<String, Object> env) throws IOException {
    StandardEnvironment environment = new StandardEnvironment();
    environment.getPropertySources().addFirst(new MapPropertySource("env", env));
    new PropertiesPropertySourceLoader()
        .load("application", new ClassPathResource("application.properties"))
        .forEach(environment.getPropertySources()::addLast);
    return Binder.get(environment).bind("helpdesk", HelpdeskProperties.class).get();
  }

  @Test
  void twentyAsksPerMinuteByDefault() throws IOException {
    assertThat(bind(Map.of()).rateLimit().asksPerMinute()).isEqualTo(20);
  }

  @Test
  void theLimitComesFromTheEnvironment() throws IOException {
    assertThat(bind(Map.of("RATE_LIMIT_ASKS_PER_MINUTE", "5")).rateLimit().asksPerMinute())
        .isEqualTo(5);
    assertThat(bind(Map.of("RATE_LIMIT_ASKS_PER_MINUTE", "0")).rateLimit().asksPerMinute())
        .isZero();
  }
}
