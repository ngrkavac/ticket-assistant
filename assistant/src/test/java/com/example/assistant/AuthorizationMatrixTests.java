package com.example.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Authorization of every API endpoint for every kind of user (T1.9, K1/K3): endpoint × role ×
 * customer. Requests carry real bearer tokens through the OIDC chain; only the signature check is
 * replaced (the {@link JwtDecoder} is a mock returning the claims below), so the claim mapping
 * ({@link HelpdeskJwtConverter}), the URL rules ({@link SecurityConfig}) and the per-customer
 * checks in the controllers all run as in production.
 */
@WebMvcTest({
  AssistantController.class,
  CustomerController.class,
  RequestHistoryController.class,
  QuestionController.class
})
@Import({SecurityConfig.class, RequestIdFilter.class, ApiExceptionHandler.class})
@EnableConfigurationProperties(HelpdeskProperties.class)
class AuthorizationMatrixTests {

  private static final String AGENT = SecurityConfig.ROLE_AGENT;

  private static final String ADMIN = SecurityConfig.ROLE_ADMIN;

  private static final String WRITER = SecurityConfig.ROLE_WRITER;

  /** The ticket server's customers. */
  private static final List<String> CUSTOMERS = List.of("acme", "globex", "initech");

  /** Token value → claims (K1). "anonymous" sends no token, "expired" one the decoder rejects. */
  private static final Map<String, User> USERS =
      Map.of(
          "no-role", new User(List.of(), List.of("acme")),
          "writer-only", new User(List.of(WRITER), List.of("acme")),
          "acme-agent", new User(List.of(AGENT), List.of("acme")),
          "acme-writer", new User(List.of(AGENT, WRITER), List.of("acme")),
          "globex-agent", new User(List.of(AGENT), List.of("globex")),
          "admin-only", new User(List.of(ADMIN), List.of("*")),
          "admin", new User(List.of(AGENT, ADMIN), List.of("*")));

  private static final String[] ENDPOINTS = {
    "ask:acme",
    "ask:globex",
    "customers",
    "requests:acme",
    "requests:globex",
    "requests:all",
    "questions",
    "add-question"
  };

  /**
   * The expected status per user (row) and endpoint (column, as {@link #ENDPOINTS}). 401: no valid
   * token. 403 for every endpoint: no helpdesk role. 403 for one customer: not in the token. POST
   * /ask needs helpdesk-agent, POST /questions helpdesk-admin, and only customers ["*"] may list
   * every customer's requests.
   */
  private static final String MATRIX =
      """
      #             ask:acme ask:globex customers req:acme req:globex req:all questions add-question
      anonymous     401      401        401       401      401        401     401       401
      expired       401      401        401       401      401        401     401       401
      no-role       403      403        403       403      403        403     403       403
      writer-only   403      403        403       403      403        403     403       403
      acme-agent    200      403        200       200      403        403     200       403
      acme-writer   200      403        200       200      403        403     200       403
      globex-agent  403      200        200       403      200        403     200       403
      admin-only    403      403        200       200      200        200     200       201
      admin         200      200        200       200      200        200     200       201
      """;

  @Autowired MockMvc mvc;

  @MockitoBean JwtDecoder jwtDecoder;

  @MockitoBean AssistantService service;

  @MockitoBean TicketDirectoryClient tickets;

  @MockitoBean RequestLogClient requestLog;

  @MockitoBean AskRateLimiter rateLimiter;

  record User(List<String> roles, List<String> customers) {}

  static Stream<Arguments> matrix() {
    List<Arguments> cases = new ArrayList<>();
    for (String line : MATRIX.strip().split("\n")) {
      if (line.startsWith("#")) {
        continue;
      }
      String[] cells = line.trim().split("\\s+");
      assertThat(cells).hasSize(ENDPOINTS.length + 1);
      for (int i = 0; i < ENDPOINTS.length; i++) {
        cases.add(Arguments.of(cells[0], ENDPOINTS[i], Integer.parseInt(cells[i + 1])));
      }
    }
    return cases.stream();
  }

  @BeforeEach
  void stubs() {
    USERS.forEach(
        (token, user) ->
            given(jwtDecoder.decode(token))
                .willReturn(
                    Jwt.withTokenValue(token)
                        .header("alg", "RS256")
                        .subject("sub-" + token)
                        .claim("preferred_username", token)
                        .claim("roles", user.roles())
                        .claim("customers", user.customers())
                        .build()));
    given(jwtDecoder.decode("expired")).willThrow(new BadJwtException("Jwt expired"));
    given(tickets.customers()).willReturn(CUSTOMERS);
    for (String c : CUSTOMERS) {
      given(tickets.requireKnown(c)).willReturn(c);
    }
    given(service.ask(anyString(), anyString(), any())).willReturn("answer");
    given(requestLog.recent(anyInt(), any())).willReturn(List.of());
    given(requestLog.questions()).willReturn(List.of());
    given(requestLog.addQuestion(anyString()))
        .willReturn(new RequestLogClient.CatalogQuestion(1, "How do I reset my password?"));
  }

  @ParameterizedTest(name = "{0} {1} -> {2}")
  @MethodSource("matrix")
  void endpointRoleCustomer(String user, String endpoint, int expected) throws Exception {
    mvc.perform(as(user, request(endpoint))).andExpect(status().is(expected));
    if (expected >= 400) {
      // nothing behind the API is reached: no model call, no ticket or request-log server call
      verifyNoInteractions(service, tickets, requestLog);
      return;
    }
    String subject = "sub-" + user;
    switch (endpoint) {
      case "ask:acme", "ask:globex" ->
          verify(service)
              .ask(eq("hi"), eq(endpoint.substring(4)), argThat(p -> p.subject().equals(subject)));
      case "requests:acme", "requests:globex" ->
          verify(requestLog).recent(20, endpoint.substring(9));
      case "requests:all" -> verify(requestLog).recent(eq(20), isNull());
      default -> {}
    }
  }

  @ParameterizedTest(name = "{0} sees {1}")
  @CsvSource({
    "acme-agent, acme",
    "acme-writer, acme",
    "globex-agent, globex",
    "admin-only, acme globex initech",
    "admin, acme globex initech"
  })
  void customersListOnlyTheTokensCustomers(String user, String visible) throws Exception {
    String[] expected = visible.split(" ");
    var result =
        mvc.perform(as(user, get("/customers")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(expected.length));
    for (int i = 0; i < expected.length; i++) {
      result.andExpect(jsonPath("$[" + i + "]").value(expected[i]));
    }
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({"ACME", "Acme", "aCmE"})
  void customerIdsInTheRequestAreComparedCaseInsensitively(String customer) throws Exception {
    mvc.perform(as("acme-agent", get("/requests").param("customer", customer)))
        .andExpect(status().isOk());
    verify(requestLog).recent(20, "acme");
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({"initech", "umbrella", "'*'"})
  void aCustomerOutsideTheTokenIsDeniedWhetherOrNotItExists(String customer) throws Exception {
    mvc.perform(
            as(
                "acme-agent",
                post("/ask")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"question\":\"hi\",\"customer\":\"" + customer + "\"}")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.detail").value("Not allowed for this customer"));
    verifyNoInteractions(service, tickets);
  }

  private static MockHttpServletRequestBuilder request(String endpoint) {
    return switch (endpoint) {
      case "ask:acme", "ask:globex" ->
          post("/ask")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"question\":\"hi\",\"customer\":\"" + endpoint.substring(4) + "\"}");
      case "customers" -> get("/customers");
      case "requests:acme", "requests:globex" ->
          get("/requests").param("customer", endpoint.substring(9));
      case "requests:all" -> get("/requests");
      case "questions" -> get("/questions");
      case "add-question" ->
          post("/questions")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"text\":\"How do I reset my password?\"}");
      default -> throw new IllegalArgumentException(endpoint);
    };
  }

  private static MockHttpServletRequestBuilder as(String user, MockHttpServletRequestBuilder req) {
    return user.equals("anonymous") ? req : req.header(HttpHeaders.AUTHORIZATION, "Bearer " + user);
  }
}
