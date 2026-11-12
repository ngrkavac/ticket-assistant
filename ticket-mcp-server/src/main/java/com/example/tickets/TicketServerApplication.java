package com.example.tickets;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class TicketServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(TicketServerApplication.class, args);
	}

	/** Registers the @Tool methods of TicketTools as MCP tools. */
	@Bean
	ToolCallbackProvider ticketToolCallbacks(TicketTools tools) {
		return MethodToolCallbackProvider.builder().toolObjects(tools).build();
	}

}
