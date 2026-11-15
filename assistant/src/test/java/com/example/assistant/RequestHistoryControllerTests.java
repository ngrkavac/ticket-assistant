package com.example.assistant;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RequestHistoryController.class)
@Import({ ApiKeyFilter.class, RequestIdFilter.class, ApiExceptionHandler.class })
@TestPropertySource(properties = "helpdesk.security.api-key=secret")
@EnableConfigurationProperties(HelpdeskProperties.class)
class RequestHistoryControllerTests {

	@Autowired
	MockMvc mvc;

	@MockitoBean
	RequestLogClient requestLog;

	@Test
	void returnsRecentRequests() throws Exception {
		given(requestLog.recent(20)).willReturn(
				List.of(new RequestLogClient.LoggedRequest("r-1", "VPN?", "SUCCESS", "2026-11-15T10:42:00Z")));
		mvc.perform(get("/requests").header("X-API-Key", "secret"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].requestId").value("r-1"))
			.andExpect(jsonPath("$[0].question").value("VPN?"))
			.andExpect(jsonPath("$[0].answer").doesNotExist());
	}

	@Test
	void requiresApiKey() throws Exception {
		mvc.perform(get("/requests")).andExpect(status().isUnauthorized());
		mvc.perform(get("/requests").header("X-API-Key", "nope")).andExpect(status().isUnauthorized());
	}

	@Test
	void rejectsLimitOutOfRange() throws Exception {
		mvc.perform(get("/requests?limit=0").header("X-API-Key", "secret")).andExpect(status().isBadRequest());
		mvc.perform(get("/requests?limit=101").header("X-API-Key", "secret")).andExpect(status().isBadRequest());
	}

	@Test
	void logServerFailureIsA503() throws Exception {
		given(requestLog.recent(5)).willThrow(new RequestLogUnavailableException("down", null));
		mvc.perform(get("/requests?limit=5").header("X-API-Key", "secret"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.detail").value("Request history is temporarily unavailable."));
	}

}
