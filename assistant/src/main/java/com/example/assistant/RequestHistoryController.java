package com.example.assistant;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only view of the request log for the UI. Protected by the same API key as /ask; answers and
 * error details are not returned.
 */
@RestController
class RequestHistoryController {

  private final RequestLogClient requestLog;

  RequestHistoryController(RequestLogClient requestLog) {
    this.requestLog = requestLog;
  }

  @GetMapping("/requests")
  List<RequestLogClient.LoggedRequest> recent(
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
    return requestLog.recent(limit);
  }
}
