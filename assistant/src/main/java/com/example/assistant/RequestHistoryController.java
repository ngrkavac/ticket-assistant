package com.example.assistant;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only view of the request log for the UI, optionally for one customer. Protected by the same
 * API key as /ask; answers and error details are not returned.
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
      @RequestParam(required = false) @Pattern(regexp = "[A-Za-z0-9-]{1,64}") String customer) {
    return requestLog.recent(limit, customer == null ? null : customer.toLowerCase(Locale.ROOT));
  }
}
