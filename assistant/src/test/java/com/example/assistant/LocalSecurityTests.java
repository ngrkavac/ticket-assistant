package com.example.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The {@code local} profile: X-API-Key instead of OIDC, still deny by default. */
@WebMvcTest(CustomerController.class)
@Import({SecurityConfig.class, RequestIdFilter.class, ApiExceptionHandler.class})
@ActiveProfiles("local")
@TestPropertySource(properties = "helpdesk.security.api-key=secret")
@EnableConfigurationProperties(HelpdeskProperties.class)
class LocalSecurityTests {

  @Autowired MockMvc mvc;

  @MockitoBean TicketDirectoryClient tickets;

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void validKeyIsAccepted() throws Exception {
    given(tickets.customers()).willReturn(List.of("acme"));
    mvc.perform(get("/customers").header("X-API-Key", "secret")).andExpect(status().isOk());
  }

  @Test
  void missingOrWrongKeyIs401Problem() throws Exception {
    mvc.perform(get("/customers"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().doesNotExist("WWW-Authenticate"))
        .andExpect(content().contentType("application/problem+json"));
    mvc.perform(get("/customers").header("X-API-Key", "nope")).andExpect(status().isUnauthorized());
  }

  @Test
  void securityHeadersAreSetToo() throws Exception {
    mvc.perform(get("/customers").header("X-API-Key", "secret"))
        .andExpect(
            header().string("Content-Security-Policy", SecurityConfig.CONTENT_SECURITY_POLICY));
  }

  @Test
  void blankKeyLetsEveryRequestInAsTheLocalUserWithAllRoles() throws Exception {
    var filter = new ApiKeyFilter(" ");
    assertThat(filter.keyRequired()).isFalse();
    var chain = new MockFilterChain();
    filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
    var auth = SecurityContextHolder.getContext().getAuthentication();
    assertThat(auth.isAuthenticated()).isTrue();
    assertThat(auth.getName()).isEqualTo(ApiKeyFilter.LOCAL_USER);
    assertThat(HelpdeskPrincipal.of(auth)).isEqualTo(HelpdeskPrincipal.LOCAL);
    assertThat(auth.getAuthorities())
        .extracting(GrantedAuthority::getAuthority)
        .containsExactlyInAnyOrder("helpdesk-agent", "helpdesk-admin", "tickets-write");
    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  void wrongKeyLeavesTheRequestAnonymous() throws Exception {
    var request = new MockHttpServletRequest();
    request.addHeader("X-API-Key", "nope");
    new ApiKeyFilter("secret")
        .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }
}
