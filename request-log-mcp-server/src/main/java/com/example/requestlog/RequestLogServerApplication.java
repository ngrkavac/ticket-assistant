package com.example.requestlog;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class RequestLogServerApplication {

  public static void main(String[] args) {
    SpringApplication.run(RequestLogServerApplication.class, args);
  }

  /** Registers the @Tool methods of RequestLogTools as MCP tools. */
  @Bean
  ToolCallbackProvider requestLogToolCallbacks(RequestLogTools tools) {
    return MethodToolCallbackProvider.builder().toolObjects(tools).build();
  }
}
