package com.example.assistant;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Per-user rate limit on {@code POST /ask} (T1.8): every user (JWT {@code sub}) may ask {@code
 * helpdesk.rate-limit.asks-per-minute} questions per minute, refilled continuously, with bursts up
 * to that number. Further asks get 429 with a Retry-After of the time until the next question is
 * allowed, and are counted in {@code helpdesk_ask_rejected_total{reason="rate_limit"}} (K9).
 *
 * <p>The buckets live in memory on each pod, so with N replicas a user can get up to N times the
 * limit (acceptable for now, see T1.8). A bucket that has not been used for a minute is full again,
 * so it is dropped then; at most {@link #MAX_USERS} buckets are kept.
 */
@Component
class AskRateLimiter {

  private static final Logger log = LoggerFactory.getLogger(AskRateLimiter.class);

  static final String REJECTED_METRIC = "helpdesk.ask.rejected";

  static final String REASON_RATE_LIMIT = "rate_limit";

  static final long MAX_USERS = 100_000;

  private static final Duration PERIOD = Duration.ofMinutes(1);

  private final int asksPerMinute;

  private final TimeMeter clock;

  private final Cache<String, Bucket> buckets;

  private final Counter rejected;

  @Autowired
  AskRateLimiter(HelpdeskProperties props, MeterRegistry meters) {
    this(
        props.rateLimit().asksPerMinute(),
        meters,
        TimeMeter.SYSTEM_NANOTIME,
        Ticker.systemTicker());
  }

  AskRateLimiter(int asksPerMinute, MeterRegistry meters, TimeMeter clock, Ticker ticker) {
    this.asksPerMinute = asksPerMinute;
    this.clock = clock;
    this.buckets =
        Caffeine.newBuilder()
            .expireAfterAccess(PERIOD)
            .maximumSize(MAX_USERS)
            .ticker(ticker)
            .build();
    this.rejected =
        Counter.builder(REJECTED_METRIC)
            .description("POST /ask requests rejected before reaching the model")
            .tag("reason", REASON_RATE_LIMIT)
            .register(meters);
    if (!enabled()) {
      log.warn("helpdesk.rate-limit.asks-per-minute is 0: /ask is not rate limited per user");
    }
  }

  boolean enabled() {
    return asksPerMinute > 0;
  }

  /**
   * Takes one ask from {@code principal}'s allowance.
   *
   * @throws AskRateLimitedException (429) if the user has no ask left in this minute
   */
  void acquire(HelpdeskPrincipal principal) {
    if (!enabled()) {
      return;
    }
    ConsumptionProbe probe =
        buckets.get(principal.subject(), k -> newBucket()).tryConsumeAndReturnRemaining(1);
    if (!probe.isConsumed()) {
      rejected.increment();
      log.info(
          "Rate limit: {} asked more than {} questions a minute",
          principal.subject(),
          asksPerMinute);
      throw new AskRateLimitedException(Duration.ofNanos(probe.getNanosToWaitForRefill()));
    }
  }

  private Bucket newBucket() {
    return Bucket.builder()
        .addLimit(l -> l.capacity(asksPerMinute).refillGreedy(asksPerMinute, PERIOD))
        .withCustomTimePrecision(clock)
        .build();
  }
}
