package com.cloak.server.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloak.server.domain.message.MessageCursor;
import com.cloak.server.port.output.message.MessageHistoryQueryPort;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FetchMessageHistoryUseCase}'s limit default/cap — the one piece of logic
 * that isn't already exercised end to end by the controller integration tests, all of which pass an
 * explicit in-range limit.
 */
class FetchMessageHistoryUseCaseTest {

  /** Stub port that records the {@code limit} it was called with and returns no rows. */
  private static final class CapturingQueryPort implements MessageHistoryQueryPort {
    private int capturedLimit;

    @Override
    public List<HistoryRow> page(String recipientSub, MessageCursor cursor, int limit) {
      this.capturedLimit = limit;
      return List.of();
    }
  }

  private int cappedLimitFor(Integer requestedLimit) {
    var port = new CapturingQueryPort();
    new FetchMessageHistoryUseCase(port).fetch("some-sub", null, requestedLimit);
    return port.capturedLimit;
  }

  @Test
  void nullLimit_defaultsTo50() {
    assertThat(cappedLimitFor(null)).isEqualTo(50);
  }

  @Test
  void zeroLimit_defaultsTo50() {
    assertThat(cappedLimitFor(0)).isEqualTo(50);
  }

  @Test
  void negativeLimit_defaultsTo50() {
    assertThat(cappedLimitFor(-5)).isEqualTo(50);
  }

  @Test
  void inRangeLimit_isPassedThroughAsIs() {
    assertThat(cappedLimitFor(10)).isEqualTo(10);
  }

  @Test
  void overMaxLimit_isCappedAt200() {
    assertThat(cappedLimitFor(5000)).isEqualTo(200);
  }
}
