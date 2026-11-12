package com.example.assistant;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

/** Reads the markdown files at helpdesk.kb.location, splits them into chunks and indexes them. */
@Component
class KnowledgeBaseIndexer {

	private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseIndexer.class);

	private final VectorStore vectorStore;

	private final ResourcePatternResolver resolver;

	private final String location;

	private final AtomicInteger indexedChunks = new AtomicInteger();

	KnowledgeBaseIndexer(VectorStore vectorStore, ResourcePatternResolver resolver, HelpdeskProperties props) {
		this.vectorStore = vectorStore;
		this.resolver = resolver;
		this.location = props.kb().location();
	}

	/** Indexes all matching files and returns the number of chunks added. */
	int index() throws IOException {
		List<Document> docs = new ArrayList<>();
		for (Resource r : resolver.getResources(location)) {
			String text = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			docs.add(new Document(text, Map.of("source", String.valueOf(r.getFilename()))));
		}
		if (docs.isEmpty()) {
			log.warn("No knowledge base files found at {}", location);
			return 0;
		}
		List<Document> chunks = new TokenTextSplitter().apply(docs);
		vectorStore.add(chunks);
		indexedChunks.set(chunks.size());
		log.info("Indexed {} KB files from {} as {} chunks", docs.size(), location, chunks.size());
		return chunks.size();
	}

	int indexedChunks() {
		return indexedChunks.get();
	}

}
