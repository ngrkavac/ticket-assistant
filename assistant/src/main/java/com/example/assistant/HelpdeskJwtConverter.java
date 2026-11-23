package com.example.assistant;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;
import org.springframework.util.StringUtils;

/**
 * Turns a validated access token into a {@link HelpdeskPrincipal} (K1). Each entry of the roles
 * claim becomes a granted authority with the same name (e.g. {@code helpdesk-admin}); the customers
 * claim becomes the set of allowed customer ids, lower-cased, where {@code "*"} means all. A claim
 * may be a JSON array or a string of space- or comma-separated values; a missing claim means no
 * roles or no customers. A token without {@code sub} is rejected (401).
 */
class HelpdeskJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

  private final String rolesClaim;

  private final String customersClaim;

  HelpdeskJwtConverter(HelpdeskProperties.Security security) {
    this.rolesClaim = security.rolesClaim();
    this.customersClaim = security.customersClaim();
  }

  @Override
  public AbstractAuthenticationToken convert(Jwt jwt) {
    HelpdeskPrincipal principal = principal(jwt);
    List<GrantedAuthority> authorities =
        principal.roles().stream()
            .sorted()
            .<GrantedAuthority>map(SimpleGrantedAuthority::new)
            .toList();
    return new Authentication(jwt, principal, authorities);
  }

  HelpdeskPrincipal principal(Jwt jwt) {
    String subject = jwt.getSubject();
    if (!StringUtils.hasText(subject)) {
      throw new InvalidBearerTokenException("The access token has no subject");
    }
    String username = jwt.getClaimAsString("preferred_username");
    Set<String> roles = values(jwt.getClaims(), rolesClaim);
    Set<String> customers = new LinkedHashSet<>();
    boolean all = false;
    for (String customer : values(jwt.getClaims(), customersClaim)) {
      if (HelpdeskPrincipal.ALL_CUSTOMERS.equals(customer)) {
        all = true;
      } else {
        customers.add(customer.toLowerCase(Locale.ROOT));
      }
    }
    return new HelpdeskPrincipal(
        subject, StringUtils.hasText(username) ? username : subject, roles, customers, all);
  }

  /** The non-blank string values of a claim; {@code name} may be a dotted path into objects. */
  static Set<String> values(Map<String, Object> claims, String name) {
    Object value = claims.containsKey(name) ? claims.get(name) : nested(claims, name);
    Set<String> out = new LinkedHashSet<>();
    if (value instanceof Collection<?> list) {
      list.forEach(v -> add(out, v));
    } else if (value instanceof String s) {
      for (String part : s.split("[\\s,]+")) {
        add(out, part);
      }
    }
    return out;
  }

  private static Object nested(Map<String, Object> claims, String path) {
    Object current = claims;
    for (String key : path.split("\\.")) {
      if (!(current instanceof Map<?, ?> map)) {
        return null;
      }
      current = map.get(key);
    }
    return current;
  }

  private static void add(Set<String> out, Object value) {
    if (value instanceof String s && StringUtils.hasText(s)) {
      out.add(s.trim());
    }
  }

  /** A JWT authentication whose principal is the {@link HelpdeskPrincipal}. */
  static final class Authentication extends AbstractOAuth2TokenAuthenticationToken<Jwt> {

    Authentication(Jwt jwt, HelpdeskPrincipal principal, List<GrantedAuthority> authorities) {
      super(jwt, principal, jwt, authorities);
      setAuthenticated(true);
    }

    @Override
    public Map<String, Object> getTokenAttributes() {
      return getToken().getClaims();
    }
  }
}
