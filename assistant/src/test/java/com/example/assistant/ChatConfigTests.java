package com.example.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;

class ChatConfigTests {

	private static ToolExecutionException failure() {
		ToolDefinition tool = ToolDefinition.builder().name("getTicket").description("d").inputSchema("{}").build();
		return new ToolExecutionException(tool, new IllegalStateException("Ticket T-1234 not found"));
	}

	@Test
	void toolFailureIsReturnedToTheModel() {
		var processor = new ChatConfig().toolExecutionExceptionProcessor(false);
		assertThat(processor.process(failure())).contains("Ticket T-1234 not found");
	}

	@Test
	void toolFailureIsRethrownWhenConfigured() {
		var processor = new ChatConfig().toolExecutionExceptionProcessor(true);
		assertThatThrownBy(() -> processor.process(failure())).isInstanceOf(ToolExecutionException.class);
	}

}
