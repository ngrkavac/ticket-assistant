import org.springframework.ai.transformers.TransformersEmbeddingModel;

/**
 * Run by the Dockerfile's {@code djl-natives} stage (Java single-file source launch, with the
 * assistant's dependency jars on the classpath). It embeds one sentence with the baked model,
 * exactly as the assistant does at startup, so DJL fetches the native libraries it needs (PyTorch
 * for the pooling maths, HuggingFace tokenizers) for this platform into {@code DJL_CACHE_DIR}. The
 * image then ships that directory and runs DJL offline. It also proves the baked model works.
 */
public class WarmUpEmbedding {

  public static void main(String[] args) throws Exception {
    TransformersEmbeddingModel model = new TransformersEmbeddingModel();
    model.setModelResource(args[0]);
    model.setTokenizerResource(args[1]);
    model.afterPropertiesSet();
    float[] vector = model.embed("How do I reset my password?");
    if (vector.length != 384) {
      throw new IllegalStateException("Expected 384 dimensions, got " + vector.length);
    }
    System.out.println("Baked embedding model works: " + vector.length + " dimensions");
  }
}
