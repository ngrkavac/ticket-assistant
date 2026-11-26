package com.example.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.transformers.autoconfigure.TransformersEmbeddingModelProperties;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** The embedding model and tokenizer URIs come from EMBEDDING_MODEL_URI/_TOKENIZER_URI (K8). */
class EmbeddingModelPropertiesTests {

  private static TransformersEmbeddingModelProperties bind(Map<String, Object> env)
      throws IOException {
    StandardEnvironment environment = new StandardEnvironment();
    environment.getPropertySources().addFirst(new MapPropertySource("env", env));
    new PropertiesPropertySourceLoader()
        .load("application", new ClassPathResource("application.properties"))
        .forEach(environment.getPropertySources()::addLast);
    return Binder.get(environment)
        .bind(
            TransformersEmbeddingModelProperties.CONFIG_PREFIX,
            TransformersEmbeddingModelProperties.class)
        .get();
  }

  @Test
  void defaultsToSpringAisModelAndTokenizer() throws IOException {
    TransformersEmbeddingModelProperties props = bind(Map.of());
    assertThat(props.getOnnx().getModelUri())
        .isEqualTo(TransformersEmbeddingModel.DEFAULT_ONNX_MODEL_URI);
    assertThat(props.getTokenizer().getUri())
        .isEqualTo(TransformersEmbeddingModel.DEFAULT_ONNX_TOKENIZER_URI);
    assertThat(props.getCache().isEnabled()).isTrue();
  }

  @Test
  void usesTheUrisFromTheEnvironment() throws IOException {
    TransformersEmbeddingModelProperties props =
        bind(
            Map.of(
                "EMBEDDING_MODEL_URI", "file:/opt/models/all-MiniLM-L6-v2/model.onnx",
                "EMBEDDING_TOKENIZER_URI", "file:/opt/models/all-MiniLM-L6-v2/tokenizer.json"));
    assertThat(props.getOnnx().getModelUri())
        .isEqualTo("file:/opt/models/all-MiniLM-L6-v2/model.onnx");
    assertThat(props.getTokenizer().getUri())
        .isEqualTo("file:/opt/models/all-MiniLM-L6-v2/tokenizer.json");
  }
}
