package com.example.assistant;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "helpdesk")
public record HelpdeskProperties(Rag rag, Security security, Kb kb, @Min(1) @Max(10000) int maxQuestionLength,
		@Min(0) @Max(1000) int maxConcurrentRequests) {

	public HelpdeskProperties {
		rag = rag == null ? new Rag(3, 0.3) : rag;
		security = security == null ? new Security(null) : security;
		kb = kb == null || kb.location() == null || kb.location().isBlank() ? new Kb(Kb.DEFAULT_LOCATION) : kb;
		maxQuestionLength = maxQuestionLength == 0 ? 1000 : maxQuestionLength;
		maxConcurrentRequests = maxConcurrentRequests == 0 ? 8 : maxConcurrentRequests;
	}

	public record Rag(@Min(1) @Max(20) int topK, @Min(0) @Max(1) double similarityThreshold) {
	}

	/** Where knowledge base markdown files are read from: classpath:..., file:... (Ant-style patterns). */
	public record Kb(String location) {

		static final String DEFAULT_LOCATION = "classpath:kb/*.md";

	}

	/** If apiKey is blank, /ask is open (local dev only). */
	public record Security(String apiKey) {
	}

}
