package com.cloak.server.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;

class WrappedResponseTest {

  private final JsonMapper objectMapper = new JsonMapper();

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void ok_setsData_nullErrors_traceIdFromMdc() {
    MDC.put("traceId", "trace-123");

    WrappedResponse<String> response = WrappedResponse.ok("payload");

    assertThat(response.data()).isEqualTo("payload");
    assertThat(response.errors()).isNull();
    assertThat(response.traceId()).isEqualTo("trace-123");
  }

  @Test
  void errorList_nullData_setsErrors_traceIdFromMdc() {
    MDC.put("traceId", "trace-456");

    WrappedResponse<Void> response = WrappedResponse.error(List.of(ApiError.of("CODE", "message")));

    assertThat(response.data()).isNull();
    assertThat(response.errors()).hasSize(1);
    assertThat(response.errors().get(0).code()).isEqualTo("CODE");
    assertThat(response.traceId()).isEqualTo("trace-456");
  }

  @Test
  void errorVarargs_buildsNonEmptyErrorList() {
    WrappedResponse<Void> response =
        WrappedResponse.error(ApiError.of("A", "a"), ApiError.of("B", "b"));

    assertThat(response.errors()).extracting(ApiError::code).containsExactly("A", "B");
  }

  @Test
  void ok_outsideRequestContext_hasNullTraceId() {
    WrappedResponse<String> response = WrappedResponse.ok("payload");

    assertThat(response.traceId()).isNull();
  }

  @Test
  void okEnvelope_serialisesDataNull_andErrorsNull_shown() throws Exception {
    String json = objectMapper.writeValueAsString(WrappedResponse.ok("payload"));

    assertThat(json).contains("\"errors\":null");
    assertThat(json).contains("\"data\":\"payload\"");
  }

  @Test
  void errorEnvelope_serialisesDataNull_shown() throws Exception {
    String json =
        objectMapper.writeValueAsString(WrappedResponse.error(ApiError.of("CODE", "message")));

    assertThat(json).contains("\"data\":null");
  }
}
