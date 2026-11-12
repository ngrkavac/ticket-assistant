package com.example.assistant;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** In-memory vector store, filled at startup from the configured knowledge base location. */
@Configuration
class RagConfig {

	@Bean
	VectorStore vectorStore(EmbeddingModel embeddingModel) {
		return SimpleVectorStore.builder(embeddingModel).build();
	}

	@Bean
	ApplicationRunner indexKnowledgeBase(KnowledgeBaseIndexer indexer) {
		return args -> indexer.index();
	}

	/** Readiness fails until the knowledge base has been indexed. */
	@Bean
	HealthIndicator knowledgeBase(KnowledgeBaseIndexer indexer) {
		return () -> indexer.indexedChunks() > 0 ? Health.up().withDetail("chunks", indexer.indexedChunks()).build()
				: Health.down().withDetail("reason", "knowledge base not indexed").build();
	}

}
