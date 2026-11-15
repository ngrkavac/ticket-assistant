package com.example.requestlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Full context on in-memory H2 with the Flyway schema. */
@SpringBootTest
class RequestLogToolsTests {

	@Autowired
	RequestLogTools tools;

	@Autowired
	ToolCallbackProvider callbacks;

	private static String newId() {
		return UUID.randomUUID().toString();
	}

	@Test
	void storesAndReadsBackARequest() {
		String id = newId();
		RequestLogEntry saved = tools.logRequest(id, "How do I reset my password?", "Use the portal.", "success", null,
				42L);

		assertThat(saved.id()).isPositive();
		assertThat(saved.status()).isEqualTo("SUCCESS");
		assertThat(saved.createdAt()).isNotNull();
		assertThat(tools.getRequest(" " + id + " ")).isEqualTo(saved);
	}

	@Test
	void storesFailedRequestWithErrorType() {
		String id = newId();
		RequestLogEntry saved = tools.logRequest(id, "q", null, "ERROR", "  RestClientException ", 7L);
		assertThat(saved.answer()).isNull();
		assertThat(saved.errorType()).isEqualTo("RestClientException");
	}

	@Test
	void sameRequestIdIsStoredOnlyOnce() {
		String id = newId();
		RequestLogEntry first = tools.logRequest(id, "q", "a", "SUCCESS", null, 1L);
		RequestLogEntry retry = tools.logRequest(id, "q", "a", "SUCCESS", null, 1L);
		assertThat(retry.id()).isEqualTo(first.id());
	}

	@Test
	void listsNewestFirstWithLimit() {
		String older = newId();
		String newer = newId();
		tools.logRequest(older, "q1", "a", "SUCCESS", null, 1L);
		tools.logRequest(newer, "q2", "a", "SUCCESS", null, 1L);

		assertThat(tools.listRecentRequests(2)).extracting(RequestLogEntry::requestId).containsExactly(newer, older);
		assertThat(tools.listRecentRequests(null)).hasSizeGreaterThanOrEqualTo(2);
	}

	@Test
	void rejectsInvalidInput() {
		assertThatThrownBy(() -> tools.logRequest(null, "q", "a", "SUCCESS", null, 1L))
			.hasMessageContaining("requestId is required");
		assertThatThrownBy(() -> tools.logRequest("bad id; drop", "q", "a", "SUCCESS", null, 1L))
			.hasMessageContaining("Invalid requestId");
		assertThatThrownBy(() -> tools.logRequest("x".repeat(65), "q", "a", "SUCCESS", null, 1L))
			.hasMessageContaining("Invalid requestId");
		assertThatThrownBy(() -> tools.logRequest(newId(), " ", "a", "SUCCESS", null, 1L))
			.hasMessageContaining("question is required");
		assertThatThrownBy(() -> tools.logRequest(newId(), "q".repeat(10_001), "a", "SUCCESS", null, 1L))
			.hasMessageContaining("longer than");
		assertThatThrownBy(() -> tools.logRequest(newId(), "q", "a", "MAYBE", null, 1L))
			.hasMessageContaining("status must be");
		assertThatThrownBy(() -> tools.logRequest(newId(), "q", "a", null, null, 1L))
			.hasMessageContaining("status must be");
		assertThatThrownBy(() -> tools.logRequest(newId(), "q", "a", "SUCCESS", null, -1L))
			.hasMessageContaining("durationMs");
		assertThatThrownBy(() -> tools.logRequest(newId(), "q", "a", "SUCCESS", null, null))
			.hasMessageContaining("durationMs");
		assertThatThrownBy(() -> tools.listRecentRequests(0)).hasMessageContaining("limit");
		assertThatThrownBy(() -> tools.listRecentRequests(101)).hasMessageContaining("limit");
		assertThatThrownBy(() -> tools.getRequest("missing-id")).hasMessageContaining("not found");
	}

	@Test
	void truncatesOverlongErrorType() {
		RequestLogEntry saved = tools.logRequest(newId(), "q", null, "ERROR", "E".repeat(300), 1L);
		assertThat(saved.errorType()).hasSize(200);
	}

	@Test
	void toolsAreExposedAndResultsSerializeToJson() {
		ToolCallback[] all = callbacks.getToolCallbacks();
		assertThat(Arrays.stream(all).map(c -> c.getToolDefinition().name())).containsExactlyInAnyOrder("logRequest",
				"getRequest", "listRecentRequests");

		ToolCallback log = Arrays.stream(all)
			.filter(c -> c.getToolDefinition().name().equals("logRequest"))
			.findFirst()
			.orElseThrow();
		String id = newId();
		String json = log.call("""
				{"requestId":"%s","question":"VPN drops","answer":"Switch to TCP","status":"SUCCESS","durationMs":12}
				""".formatted(id));
		assertThat(json).contains(id).contains("\"status\":\"SUCCESS\"").containsPattern("\"createdAt\":\"\\d{4}-\\d{2}-\\d{2}T");
	}

}
