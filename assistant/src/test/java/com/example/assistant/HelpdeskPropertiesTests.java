package com.example.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HelpdeskPropertiesTests {

	@Test
	void appliesDefaultsWhenNothingIsConfigured() {
		var p = new HelpdeskProperties(null, null, null, 0, 0);
		assertThat(p.rag().topK()).isEqualTo(3);
		assertThat(p.rag().similarityThreshold()).isEqualTo(0.3);
		assertThat(p.security().apiKey()).isNull();
		assertThat(p.kb().location()).isEqualTo("classpath:kb/*.md");
		assertThat(p.maxQuestionLength()).isEqualTo(1000);
		assertThat(p.maxConcurrentRequests()).isEqualTo(8);
	}

	@Test
	void keepsConfiguredValuesAndFallsBackOnBlankKbLocation() {
		var p = new HelpdeskProperties(new HelpdeskProperties.Rag(5, 0.5), new HelpdeskProperties.Security("k"),
				new HelpdeskProperties.Kb(" "), 200, 2);
		assertThat(p.rag().topK()).isEqualTo(5);
		assertThat(p.security().apiKey()).isEqualTo("k");
		assertThat(p.kb().location()).isEqualTo("classpath:kb/*.md");
		assertThat(p.maxQuestionLength()).isEqualTo(200);
		assertThat(p.maxConcurrentRequests()).isEqualTo(2);
	}

}
