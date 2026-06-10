package com.cloak.server.common.web;

import java.util.List;
import org.slf4j.MDC;

/**
 * Standard REST response envelope (ARCHITECTURE_GUIDE §9.2). Every REST response — success or
 * failure — is a {@code WrappedResponse} with the same three fields:
 *
 * <ul>
 *   <li>{@code data} — the resource on success; {@code null} on failure.
 *   <li>{@code errors} — {@code null} on success; a non-empty array on failure (one element per
 *       distinct problem).
 *   <li>{@code traceId} — present on every body, sourced from the {@code CorrelationFilter} MDC;
 *       may be {@code null} outside a request context.
 * </ul>
 *
 * <p>Deliberately not annotated with {@code @JsonInclude(NON_NULL)} — {@code data:null} and {@code
 * errors:null} must be shown so the shape is uniform across success and failure.
 */
public record WrappedResponse<T>(T data, List<ApiError> errors, String traceId) {

  private static String currentTraceId() {
    return MDC.get("traceId");
  }

  /** Success envelope: {@code data} set, {@code errors} null, {@code traceId} from MDC. */
  public static <T> WrappedResponse<T> ok(T data) {
    return new WrappedResponse<>(data, null, currentTraceId());
  }

  /** Failure envelope: {@code data} null, {@code errors} set, {@code traceId} from MDC. */
  public static WrappedResponse<Void> error(List<ApiError> errors) {
    return new WrappedResponse<>(null, errors, currentTraceId());
  }

  /** Convenience failure envelope from one or more {@link ApiError} values. */
  public static WrappedResponse<Void> error(ApiError... errors) {
    return error(List.of(errors));
  }
}
