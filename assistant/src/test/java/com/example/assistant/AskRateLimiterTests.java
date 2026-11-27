package com.example.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.benmanes.caffeine.cache.Ticker;
import io.github.bucket4j.TimeMeter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AskRateLimiterTests {

  private static final HelpdeskPrincipal ALICE = user("alice");

  private static final HelpdeskPrincipal BOB = user("bob");

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  /** Drives both Bucket4j's refill and Caffeine's expiry. */
  private final AtomicLong nanos = new AtomicLong(1_000_000_000L);

  private final TimeMeter clock =
      new TimeMeter() {
        @Override
        public long currentTimeNanos() {
          return nanos.get();
        }

        @Override
        public boolean isWallClockBased() {
          return false;
        }
      };

  private final Ticker ticker = nanos::get;

  private AskRateLimiter limiter(int perMinute) {
    return new AskRateLimiter(perMinute, meters, clock, ticker);
  }

  private void advance(Duration d) {
    nanos.addAndGet(d.toNanos());
  }

  private double rejected() {
    return meters
        .get(AskRateLimiter.REJECTED_METRIC)
        .tag("reason", AskRateLimiter.REASON_RATE_LIMIT)
        .counter()
        .count();
  }

  @Test
  void allowsTheLimitPerMinuteThenRejectsWithTheWaitForTheNextAsk() {
    AskRateLimiter limiter = limiter(3);
    for (int i = 0; i < 3; i++) {
      limiter.acquire(ALICE);
    }
    // greedy refill: one ask every 20 s
    assertThatThrownBy(() -> limiter.acquire(ALICE))
        .isInstanceOfSatisfying(
            AskRateLimitedException.class,
            e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(20)));
    assertThat(rejected()).isEqualTo(1);

    advance(Duration.ofSeconds(5));
    assertThatThrownBy(() -> limiter.acquire(ALICE))
        .isInstanceOfSatisfying(
            AskRateLimitedException.class,
            e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(15)));
    assertThat(rejected()).isEqualTo(2);

    advance(Duration.ofSeconds(15));
    limiter.acquire(ALICE);
    assertThatThrownBy(() -> limiter.acquire(ALICE)).isInstanceOf(AskRateLimitedException.class);
  }

  @Test
  void eachUserHasTheirOwnAllowance() {
    AskRateLimiter limiter = limiter(1);
    limiter.acquire(ALICE);
    assertThatThrownBy(() -> limiter.acquire(ALICE)).isInstanceOf(AskRateLimitedException.class);
    assertThatCode(() -> limiter.acquire(BOB)).doesNotThrowAnyException();
    // the same subject with other roles or customers is the same user
    HelpdeskPrincipal aliceAsAdmin =
        new HelpdeskPrincipal("alice", "Alice", Set.of(SecurityConfig.ROLE_ADMIN), Set.of(), true);
    assertThatThrownBy(() -> limiter.acquire(aliceAsAdmin))
        .isInstanceOf(AskRateLimitedException.class);
  }

  @Test
  void anIdleUserGetsAFullAllowanceAgain() {
    AskRateLimiter limiter = limiter(2);
    limiter.acquire(ALICE);
    limiter.acquire(ALICE);
    advance(Duration.ofMinutes(2)); // the bucket expires from the cache and is created again
    limiter.acquire(ALICE);
    limiter.acquire(ALICE);
    assertThatThrownBy(() -> limiter.acquire(ALICE)).isInstanceOf(AskRateLimitedException.class);
  }

  @Test
  void zeroTurnsTheLimitOff() {
    AskRateLimiter limiter = limiter(0);
    assertThat(limiter.enabled()).isFalse();
    for (int i = 0; i < 1000; i++) {
      limiter.acquire(ALICE);
    }
    assertThat(rejected()).isZero();
  }

  @Test
  void readsTheLimitFromTheProperties() {
    var props =
        new HelpdeskProperties(
            null, null, null, null, 0, 0, 0, null, null, null, new HelpdeskProperties.RateLimit(1));
    AskRateLimiter limiter = new AskRateLimiter(props, meters);
    assertThat(limiter.enabled()).isTrue();
    limiter.acquire(ALICE);
    assertThatThrownBy(() -> limiter.acquire(ALICE)).isInstanceOf(AskRateLimitedException.class);
  }

  private static HelpdeskPrincipal user(String subject) {
    return new HelpdeskPrincipal(
        subject, subject, Set.of(SecurityConfig.ROLE_AGENT), Set.of("acme"), false);
  }
}
