package com.example.assistant;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** Builds the in-memory vector store and indexes the markdown files in classpath:kb/. */
@Configuration
class RagConfig {

	private static final Logger log = LoggerFactory.getLogger(RagConfig.class);

	@Bean
	VectorStore vectorStore(EmbeddingModel embeddingModel) {
		return SimpleVectorStore.builder(embeddingModel).build();
	}

	@Bean
	ApplicationRunner indexKnowledgeBase(VectorStore vectorStore) {
		return args -> {
			List<Document> docs = new ArrayList<>();
			for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath:kb/*.md")) {
				docs.add(new Document(read(r), java.util.Map.of("source", String.valueOf(r.getFilename()))));
			}
			List<Document> chunks = new TokenTextSplitter().apply(docs);
			vectorStore.add(chunks);
			log.info("Indexed {} KB files as {} chunks", docs.size(), chunks.size());
		};
	}

	private static String read(Resource r) throws IOException {
		return new String(r.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
	}

}
