package com.example.assistant;

import java.util.Locale;
import java.util.Set;
import org.springframework.security.core.AuthenticatedPrincipal;
import org.springframework.security.core.Authentication;

/**
 * The signed-in user (K1): the JWT {@code sub}, a display name, the user's roles and the customers
 * the user may act for. Customer ids are lower-case; {@code allCustomers} comes from the {@code
 * "*"} entry that admins get.
 */
public record HelpdeskPrincipal(
    String subject, String username, Set<String> roles, Set<String> customers, boolean allCustomers)
    implements AuthenticatedPrincipal {

  /** The "every customer" entry of the customers claim. */
  static final String ALL_CUSTOMERS = "*";

  /** The local-profile user (no identity provider): every role, every customer. */
  static final HelpdeskPrincipal LOCAL =
      new HelpdeskPrincipal(
          ApiKeyFilter.LOCAL_USER,
          ApiKeyFilter.LOCAL_USER,
          Set.of(SecurityConfig.ROLE_AGENT, SecurityConfig.ROLE_ADMIN, SecurityConfig.ROLE_WRITER),
          Set.of(),
          true);

  public HelpdeskPrincipal {
    roles = Set.copyOf(roles);
    customers = Set.copyOf(customers);
  }

  /** The subject, so audit and logs refer to the stable id rather than the display name. */
  @Override
  public String getName() {
    return subject;
  }

  boolean hasRole(String role) {
    return roles.contains(role);
  }

  /** Whether this user may act for {@code customer} (compared case-insensitively). */
  boolean canAccess(String customer) {
    return customer != null
        && (allCustomers || customers.contains(customer.trim().toLowerCase(Locale.ROOT)));
  }

  /** The principal of an authentication made by this app's security chains, otherwise null. */
  static HelpdeskPrincipal of(Authentication authentication) {
    return authentication != null
            && authentication.getPrincipal() instanceof HelpdeskPrincipal principal
        ? principal
        : null;
  }
}
