package com.cloak.server.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
  private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/test");

  // A target with a validated method, used to synthesise MethodParameter / validation results.
  @SuppressWarnings("unused")
  private void sample(String first, String second) {}

  private MethodParameter methodParameter(int index) throws Exception {
    Method method = getClass().getDeclaredMethod("sample", String.class, String.class);
    MethodParameter parameter = new MethodParameter(method, index);
    parameter.initParameterNameDiscovery(
        new org.springframework.core.DefaultParameterNameDiscoverer());
    return parameter;
  }

  @Test
  void methodArgumentNotValid_returns400_oneErrorPerField() throws Exception {
    BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "body");
    binding.addError(
        new FieldError(
            "body", "toSub", null, false, new String[] {"NotBlank"}, null, "must not be blank"));
    binding.addError(
        new FieldError(
            "body", "ciphertext", null, false, new String[] {"NotNull"}, null, "must not be null"));
    MethodArgumentNotValidException ex =
        new MethodArgumentNotValidException(methodParameter(0), binding);

    ResponseEntity<WrappedResponse<Void>> response = handler.onMethodArgumentNotValid(ex, request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    List<ApiError> errors = response.getBody().errors();
    assertThat(errors).hasSize(2);
    assertThat(errors).allMatch(e -> e.code().equals("VALIDATION_ERROR"));
    assertThat(errors).extracting(ApiError::field).containsExactlyInAnyOrder("toSub", "ciphertext");
  }

  @Test
  void handlerMethodValidation_returns400_validationError() throws Exception {
    ParameterValidationResult result =
        new ParameterValidationResult(
            methodParameter(0),
            "bad",
            List.of(new DefaultMessageSourceResolvable(new String[] {"Size"}, "size mismatch")),
            null,
            null,
            null,
            (msr, type) -> {
              throw new IllegalArgumentException();
            });
    HandlerMethodValidationException ex =
        new HandlerMethodValidationException(
            MethodValidationResult.create(
                this,
                getClass().getDeclaredMethod("sample", String.class, String.class),
                List.of(result)));

    ResponseEntity<WrappedResponse<Void>> response = handler.onHandlerMethodValidation(ex, request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    List<ApiError> errors = response.getBody().errors();
    assertThat(errors).hasSize(1);
    assertThat(errors.get(0).code()).isEqualTo("VALIDATION_ERROR");
    assertThat(errors.get(0).field()).isEqualTo("first");
    assertThat(errors.get(0).message()).isEqualTo("size mismatch");
  }

  @Test
  void handlerMethodValidation_withFieldErrors_usesFieldName() throws Exception {
    BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "arg");
    binding.addError(
        new FieldError(
            "arg", "toSub", null, false, new String[] {"NotBlank"}, null, "must not be blank"));
    ParameterErrors parameterErrors =
        new ParameterErrors(methodParameter(0), "bad", binding, null, null, null);
    HandlerMethodValidationException ex =
        new HandlerMethodValidationException(
            MethodValidationResult.create(
                this,
                getClass().getDeclaredMethod("sample", String.class, String.class),
                List.of(parameterErrors)));

    ResponseEntity<WrappedResponse<Void>> response = handler.onHandlerMethodValidation(ex, request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    ApiError error = response.getBody().errors().get(0);
    assertThat(error.code()).isEqualTo("VALIDATION_ERROR");
    assertThat(error.field()).isEqualTo("toSub");
    assertThat(error.message()).isEqualTo("must not be blank");
  }

  @Test
  void malformedBody_returns400_malformedRequest() {
    HttpMessageNotReadableException ex =
        new HttpMessageNotReadableException(
            "JSON parse error at line 3 column 7", new MockHttpInputMessage(new byte[0]));

    ResponseEntity<WrappedResponse<Void>> response = handler.onNotReadable(ex, request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    ApiError error = response.getBody().errors().get(0);
    assertThat(error.code()).isEqualTo("MALFORMED_REQUEST");
    assertThat(error.message()).doesNotContain("line 3");
  }

  @Test
  void unknownPath_returns404_notFound() throws Exception {
    NoResourceFoundException ex =
        new NoResourceFoundException(
            org.springframework.http.HttpMethod.GET, "/v1/nope", "v1/nope");

    ResponseEntity<WrappedResponse<Void>> response = handler.onNoResource(ex, request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody().errors().get(0).code()).isEqualTo("NOT_FOUND");
  }

  @Test
  void unsupportedMethod_returns405_methodNotAllowed() {
    HttpRequestMethodNotSupportedException ex =
        new HttpRequestMethodNotSupportedException("DELETE");

    ResponseEntity<WrappedResponse<Void>> response = handler.onMethodNotSupported(ex, request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    assertThat(response.getBody().errors().get(0).code()).isEqualTo("METHOD_NOT_ALLOWED");
  }

  @Test
  void unsupportedMediaType_returns415_unsupportedMediaType() {
    HttpMediaTypeNotSupportedException ex =
        new HttpMediaTypeNotSupportedException(
            MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON));

    ResponseEntity<WrappedResponse<Void>> response = handler.onMediaTypeNotSupported(ex, request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    assertThat(response.getBody().errors().get(0).code()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
  }

  @Test
  void unexpectedException_returns500_safeGenericMessage() {
    RuntimeException cause = new RuntimeException("SELECT secret FROM users; ciphertext-leak");

    ResponseEntity<WrappedResponse<Void>> response = handler.onUnexpected(cause, request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    ApiError error = response.getBody().errors().get(0);
    assertThat(error.code()).isEqualTo("INTERNAL_ERROR");
    assertThat(error.message()).isEqualTo("An unexpected error occurred.");
    assertThat(error.message()).doesNotContain("SELECT");
    assertThat(error.message()).doesNotContain("ciphertext-leak");
  }
}
