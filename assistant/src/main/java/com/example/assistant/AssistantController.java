package com.example.assistant;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * IT helpdesk assistant. Answers how-to questions from the knowledge base (RAG) and looks
 * up live ticket data through the MCP ticket server (tools).
 */
@RestController
class AssistantController {

	private static final String SYSTEM = """
			You are an IT helpdesk assistant. For how-to and policy questions, answer only from the
			provided knowledge base context and say so if it does not cover the question.
			For questions about specific tickets or a customer's tickets, call the ticket tools.
			Keep answers short and mention ticket ids or KB sources you used.
			""";

	private final ChatClient chat;

	AssistantController(ChatClient.Builder builder, VectorStore vectorStore, ToolCallbackProvider mcpTools) {
		this.chat = builder.defaultSystem(SYSTEM)
			.defaultAdvisors(QuestionAnswerAdvisor.builder(vectorStore)
				.searchRequest(SearchRequest.builder().topK(3).similarityThreshold(0.3).build())
				.build())
			.defaultToolCallbacks(mcpTools)
			.build();
	}

	record Question(String question) {
	}

	record Answer(String answer) {
	}

	@PostMapping("/ask")
	Answer ask(@RequestBody Question q) {
		return new Answer(chat.prompt().user(q.question()).call().content());
	}

}
