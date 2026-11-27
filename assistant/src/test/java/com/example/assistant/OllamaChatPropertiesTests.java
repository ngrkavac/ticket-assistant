package com.example.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.ollama.autoconfigure.OllamaChatProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** application.properties caps how many tokens the model may generate per chat call. */
class OllamaChatPropertiesTests {

  private static OllamaChatProperties bind(Map<String, Object> env) throws IOException {
    StandardEnvironment environment = new StandardEnvironment();
    environment.getPropertySources().addFirst(new MapPropertySource("env", env));
    new PropertiesPropertySourceLoader()
        .load("application", new ClassPathResource("application.properties"))
        .forEach(environment.getPropertySources()::addLast);
    return Binder.get(environment)
        .bind(OllamaChatProperties.CONFIG_PREFIX, OllamaChatProperties.class)
        .get();
  }

  @Test
  void generationIsCappedByDefault() throws IOException {
    assertThat(bind(Map.of()).getOptions().getNumPredict()).isEqualTo(1024);
  }

  @Test
  void theCapComesFromTheEnvironment() throws IOException {
    assertThat(bind(Map.of("OLLAMA_NUM_PREDICT", "256")).getOptions().getNumPredict())
        .isEqualTo(256);
    assertThat(bind(Map.of("OLLAMA_NUM_PREDICT", "-1")).getOptions().getNumPredict()).isEqualTo(-1);
  }
}
