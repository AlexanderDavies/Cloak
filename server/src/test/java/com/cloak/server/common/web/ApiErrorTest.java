package com.cloak.server.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ApiErrorTest {

  private final JsonMapper objectMapper = new JsonMapper();

  @Test
  void ofFactory_setsCodeAndMessage_noField() {
    ApiError error = ApiError.of("CODE", "message");

    assertThat(error.code()).isEqualTo("CODE");
    assertThat(error.message()).isEqualTo("message");
    assertThat(error.field()).isNull();
  }

  @Test
  void fieldFactory_setsAllThreeFields() {
    ApiError error = ApiError.field("CODE", "message", "toSub");

    assertThat(error.code()).isEqualTo("CODE");
    assertThat(error.message()).isEqualTo("message");
    assertThat(error.field()).isEqualTo("toSub");
  }

  @Test
  void nullField_isOmittedFromJson() throws Exception {
    String json = objectMapper.writeValueAsString(ApiError.of("CODE", "message"));

    assertThat(json).doesNotContain("field");
    assertThat(json).contains("\"code\":\"CODE\"");
    assertThat(json).contains("\"message\":\"message\"");
  }

  @Test
  void presentField_isSerialisedInJson() throws Exception {
    String json = objectMapper.writeValueAsString(ApiError.field("CODE", "message", "toSub"));

    assertThat(json).contains("\"field\":\"toSub\"");
  }
}
