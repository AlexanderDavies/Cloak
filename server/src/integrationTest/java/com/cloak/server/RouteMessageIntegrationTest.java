package com.cloak.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloak.server.domain.message.MessageId;
import com.cloak.server.port.output.message.MessageRepositoryPort;
import com.cloak.server.support.IntegrationTestBase;
import com.cloak.server.usecase.RouteMessageCommand;
import com.cloak.server.usecase.RouteMessageUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RouteMessageIntegrationTest extends IntegrationTestBase {

  @Autowired RouteMessageUseCase useCase;
  @Autowired MessageRepositoryPort repository;

  @Test
  void routedMessage_isPersisted_andRetrievable() {
    byte[] cipher = {7, 7, 7};
    var id = "55555555-5555-5555-5555-555555555555";
    useCase.route(new RouteMessageCommand(id, "alice-sub", "bob-sub", null, cipher));
    assertThat(repository.find(new MessageId(id)).orElseThrow().ciphertext().value())
        .containsExactly(cipher);
  }
}
