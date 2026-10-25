package com.cloak.server.domain.message;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConversationIdTest {
  @Test
  void canonicalIsOrderIndependentAndMatchesSha256() {
    String a = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    String b = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    String expected = "32eaa687f333efc245cc57c857f22ca5fb0e4757ea0a3c147ee2b6da131f77d0";
    assertThat(ConversationId.canonical(a, b)).isEqualTo(expected);
    assertThat(ConversationId.canonical(b, a)).isEqualTo(expected); // order-independent
  }
}
