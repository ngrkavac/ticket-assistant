package com.example.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.actuate.health.Status;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class KnowledgeBaseIndexerTests {

	private final VectorStore store = mock(VectorStore.class);

	private KnowledgeBaseIndexer indexer(String location) {
		var props = new HelpdeskProperties(null, null, new HelpdeskProperties.Kb(location), null, 0, 0);
		return new KnowledgeBaseIndexer(store, new PathMatchingResourcePatternResolver(), props);
	}

	@Test
	@SuppressWarnings("unchecked")
	void indexesFilesFromAnExternalFileLocation(@TempDir Path dir) throws IOException {
		Files.writeString(dir.resolve("a.md"), "# A\nFirst article about printers.");
		Files.writeString(dir.resolve("b.md"), "# B\nSecond article about badges.");
		Files.writeString(dir.resolve("ignored.txt"), "not markdown");

		KnowledgeBaseIndexer indexer = indexer("file:" + dir + "/*.md");
		int chunks = indexer.index();

		assertThat(chunks).isEqualTo(2);
		assertThat(indexer.indexedChunks()).isEqualTo(2);
		ArgumentCaptor<List<Document>> added = ArgumentCaptor.forClass(List.class);
		verify(store).add(added.capture());
		assertThat(added.getValue()).extracting(d -> d.getMetadata().get("source"))
			.containsExactlyInAnyOrder("a.md", "b.md");
	}

	@Test
	void indexesBundledKnowledgeBaseByDefault() throws IOException {
		assertThat(indexer(null).index()).isGreaterThanOrEqualTo(3);
	}

	@Test
	void doesNothingWhenNoFilesMatch(@TempDir Path dir) throws IOException {
		KnowledgeBaseIndexer indexer = indexer("file:" + dir + "/*.md");
		assertThat(indexer.index()).isZero();
		assertThat(indexer.indexedChunks()).isZero();
		verify(store, never()).add(anyList());
	}

	@Test
	void healthIsDownUntilIndexedThenUp(@TempDir Path dir) throws IOException {
		Files.writeString(dir.resolve("a.md"), "# A\nSomething.");
		KnowledgeBaseIndexer indexer = indexer("file:" + dir + "/*.md");
		var health = new RagConfig().knowledgeBase(indexer);

		assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
		indexer.index();
		assertThat(health.health().getStatus()).isEqualTo(Status.UP);
		assertThat(health.health().getDetails()).containsEntry("chunks", 1);
	}

	@Test
	void runnerTriggersIndexing(@TempDir Path dir) throws Exception {
		Files.writeString(dir.resolve("a.md"), "# A\nSomething.");
		KnowledgeBaseIndexer indexer = indexer("file:" + dir + "/*.md");
		new RagConfig().indexKnowledgeBase(indexer).run(null);
		assertThat(indexer.indexedChunks()).isEqualTo(1);
	}

}
