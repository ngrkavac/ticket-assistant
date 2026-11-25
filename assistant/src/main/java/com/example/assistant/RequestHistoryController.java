package com.example.assistant;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Locale;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only view of the request log for the UI, for one customer. The customer must be in the
 * caller's token (403 otherwise); only a user with {@code customers: ["*"]} may leave it out to see
 * every customer's requests. Answers and error details are not returned.
 */
@RestController
class RequestHistoryController {

  private final RequestLogClient requestLog;

  RequestHistoryController(RequestLogClient requestLog) {
    this.requestLog = requestLog;
  }

  @GetMapping("/requests")
  List<RequestLogClient.LoggedRequest> recent(
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
      @RequestParam(required = false) @Pattern(regexp = "[A-Za-z0-9-]{1,64}") String customer,
      Authentication authentication) {
    HelpdeskPrincipal principal = HelpdeskPrincipal.require(authentication);
    if (customer == null) {
      if (!principal.allCustomers()) {
        throw new CustomerNotAllowedException();
      }
      return requestLog.recent(limit, null);
    }
    principal.requireAccess(customer);
    return requestLog.recent(limit, customer.toLowerCase(Locale.ROOT));
  }
}
